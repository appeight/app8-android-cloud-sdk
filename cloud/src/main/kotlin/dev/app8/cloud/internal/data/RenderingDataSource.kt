package dev.app8.cloud.internal.data

import dev.app8.cloud.App8CloudError
import dev.app8.cloud.CacheScope
import dev.app8.cloud.internal.CloudJson
import dev.app8.cloud.internal.ContentHash
import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.arrOrNull
import dev.app8.cloud.internal.cache.AssetCache
import dev.app8.cloud.internal.cache.CacheLayout
import dev.app8.cloud.internal.cache.DiskCache
import dev.app8.cloud.internal.cache.InstantIsoSerializer
import dev.app8.cloud.internal.cache.ResourceMeta
import dev.app8.cloud.internal.cache.ScreenCatalogStore
import dev.app8.cloud.internal.cache.ScreenFreshness
import dev.app8.cloud.internal.cache.ScreenMeta
import dev.app8.cloud.internal.extractDslId
import dev.app8.cloud.internal.fonts.FontRegistry
import dev.app8.cloud.internal.net.AppManifestResponse
import dev.app8.cloud.internal.net.AssetsResponse
import dev.app8.cloud.internal.net.Endpoint
import dev.app8.cloud.internal.net.FlowManifestResponse
import dev.app8.cloud.internal.net.HttpClient
import dev.app8.cloud.internal.net.ItemsResponse
import dev.app8.cloud.internal.net.ListScreensResponse
import dev.app8.cloud.internal.net.ScreenRenderResponse
import dev.app8.cloud.internal.objOrNull
import dev.app8.cloud.internal.parseJson
import dev.app8.cloud.internal.telemetry.TelemetryClient
import dev.app8.cloud.internal.telemetry.TelemetryEvent
import dev.app8.cloud.internal.telemetry.telemetryReasonString
import dev.app8.cloud.internal.toBytes
import dev.app8.engine.App8DataSource
import dev.app8.engine.core.vars.DatasourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** What the data source needs from the instance (iOS `RenderingBridge`). */
internal interface RenderingBridge {
    val identityAttributes: Map<String, String>
    fun pinnedVersion(screenId: String): String?
    fun recordServed(version: String?, fromCache: Boolean, screenId: String)
}

/**
 * The engine's [App8DataSource] over `/sdk/v1` (iOS `RenderingDataSource`): memory → disk →
 * network for every DSL document, an LRU asset cache, font registration, the flow channel and
 * the prefetch freshness checks. Thread-safe.
 */
internal class RenderingDataSource(
    val appId: String,
    private val client: HttpClient,
    private val cache: DiskCache?,
    private val assetCache: AssetCache?,
    private val fontRegistry: FontRegistry,
    private val log: Diagnostics,
    private val coalescer: InFlightCoalescer = InFlightCoalescer(),
) : App8DataSource {

    // region State

    /** In-memory mirror of the disk cache for hot paths. */
    private val lock = Any()
    private var manifest: ByteArray? = null
    private var styles: List<ByteArray> = emptyList()

    /** Explicit flag: an app with zero styles would otherwise refetch on every render. */
    private var stylesLoaded = false
    private var components: List<ByteArray> = emptyList()
    private var componentsByDslId: Map<String, ByteArray> = emptyMap()
    private var componentsLoaded = false
    private val screensByCacheKey = HashMap<String, ByteArray>()
    private val datasources = HashMap<String, ByteArray>()
    private val allScreenIds = ArrayList<String>()
    private var localizations: ByteArray? = null

    private val assetLock = Any()
    private var assetManifest: AssetManifestEntries? = null
    private var fontsPrepared = false
    private var inFlightFontPrep: CompletableDeferred<Unit>? = null

    /**
     * Flow member screen bytes keyed `flowKey/screenKey@version`, separate from
     * [screensByCacheKey] so flow screens never leak into the single-screen channel.
     */
    private val flowScreens = HashMap<String, ByteArray>()

    @Volatile private var bridge: RenderingBridge? = null
    @Volatile private var telemetry: TelemetryClient? = null
    @Volatile private var catalog: ScreenCatalogStore? = null

    /** Called after new fonts register, so the instance can hand the engine a fresh provider. */
    @Volatile var onFontsRegistered: (() -> Unit)? = null

    fun bind(bridge: RenderingBridge) {
        this.bridge = bridge
    }

    fun bind(telemetry: TelemetryClient?) {
        this.telemetry = telemetry
    }

    fun bind(catalog: ScreenCatalogStore) {
        this.catalog = catalog
    }

    // endregion
    // region Cache reset

    fun resetInMemoryState(scope: CacheScope) {
        when (scope) {
            CacheScope.All -> {
                synchronized(lock) {
                    manifest = null
                    styles = emptyList()
                    stylesLoaded = false
                    components = emptyList()
                    componentsByDslId = emptyMap()
                    componentsLoaded = false
                    screensByCacheKey.clear()
                    datasources.clear()
                    allScreenIds.clear()
                    localizations = null
                }
                synchronized(assetLock) {
                    assetManifest = null
                    fontsPrepared = false
                    inFlightFontPrep = null
                }
                synchronized(flowScreens) { flowScreens.clear() }
            }
            is CacheScope.Screen -> {
                val prefix = "${scope.id}@"
                synchronized(lock) {
                    screensByCacheKey.keys.removeAll { it.startsWith(prefix) }
                    allScreenIds.remove(scope.id)
                }
                // Flow members are keyed `flowKey/screenKey@version`: drop this screen's too.
                synchronized(flowScreens) { flowScreens.keys.removeAll { "/${scope.id}@" in it } }
            }
            // Only the blob cache is wiped; the in-memory manifest's URLs stay valid.
            CacheScope.AssetsOnly -> Unit
        }
    }

    /** Drops flow member bytes on flow teardown: bounds growth to one active flow session. */
    fun clearFlowScreenCache() {
        synchronized(flowScreens) { flowScreens.clear() }
    }

    // endregion
    // region App8DataSource

    override suspend fun getApp(): ByteArray {
        loadManifestIfNeeded()
        return synchronized(lock) { manifest } ?: throw App8CloudError.DecodeFailed("getApp", NoBlobError())
    }

    /**
     * The manifest's `transitions` registry as raw object blobs: the flow channel borrows it until
     * a published flow carries its own. Empty when absent or unparseable.
     */
    suspend fun appManifestTransitions(): List<ByteArray> {
        val blob = runCatching { getApp() }.getOrNull() ?: return emptyList()
        return runCatching {
            parseJson(blob).objOrNull?.get("transitions")?.arrOrNull?.mapNotNull { it.objOrNull?.toBytes() }
        }.getOrNull().orEmpty()
    }

    override suspend fun getStyles(): List<ByteArray> {
        loadStylesIfNeeded()
        return synchronized(lock) { styles }
    }

    override suspend fun getComponents(): List<ByteArray> {
        loadComponentsIfNeeded()
        return synchronized(lock) { components }
    }

    override suspend fun getScreen(screenId: String): ByteArray = loadScreen(screenId, reportCacheHits = true)

    /**
     * Memory → disk → network. [reportCacheHits] off for SDK-internal reads (asset scans), so
     * they don't overwrite the served version the render just recorded.
     */
    private suspend fun loadScreen(screenId: String, reportCacheHits: Boolean): ByteArray {
        val pinned = readPinnedVersion(screenId)
        val key = cacheKey(screenId, pinned)
        synchronized(lock) { screensByCacheKey[key] }?.let {
            if (reportCacheHits) reportServed(pinned, true, screenId)
            return it
        }
        cache?.readScreen(screenId, pinned)?.let { blob ->
            synchronized(lock) { screensByCacheKey[key] = blob }
            if (reportCacheHits) reportServed(pinned, true, screenId)
            return blob
        }
        return fetchScreen(screenId, pinned)
    }

    override suspend fun getComponent(componentId: String): ByteArray {
        loadComponentsIfNeeded()
        synchronized(lock) { componentsByDslId[componentId] }?.let { return it }
        cache?.readComponent(componentId)?.let { return it }
        throw App8CloudError.DecodeFailed("getComponent($componentId)", NoBlobError())
    }

    override suspend fun getDatasource(screenId: String, datasourceId: String): ByteArray {
        synchronized(lock) { datasources[datasourceId] }?.let { return it }
        val parts = datasourceId.split('/', limit = 2)
        if (parts.size == 2) cache?.readDatasource(parts[0], parts[1])?.let { return it }
        // The engine's datasource contract; iOS throws decodeFailed here.
        throw DatasourceException.NotFound(screenId, datasourceId)
    }

    override suspend fun getAsset(assetId: String?, assetName: String?): ByteArray? {
        val key = assetId ?: assetName ?: ""
        if (key.isEmpty()) return null
        assetCache?.read(key)?.let {
            log.debug { "[Asset] hit key='$key' (id=$assetId name=$assetName)" }
            return it
        }
        log.debug { "[Asset] MISS key='$key' (id=$assetId name=$assetName) — fetching" }
        val started = System.nanoTime()
        val manifest = ensureAssetManifest()
        val entry = manifest.resolve(assetId, assetName)
        if (entry == null) {
            log.warning { "[Asset] not in manifest: id=$assetId name=$assetName" }
            return null
        }
        if (!entry.downloadUrl.lowercase().startsWith("https://")) {
            log.warning { "[Asset] rejecting non-HTTPS download URL for key='$key'." }
            return null
        }
        val data = try {
            coalescer.run("asset:${entry.id}") { client.getRawUrl(entry.downloadUrl).data }
        } catch (e: Exception) {
            emitAssetFetchFailed(entry.id, e)
            throw e
        }
        assetCache?.write(key, data)
        if (entry.id != key) assetCache?.write(entry.id, data)
        log.debug { "[Asset] fetched key='$key' (${data.size} bytes) in ${(System.nanoTime() - started) / 1_000_000}ms" }
        return data
    }

    override suspend fun getAllScreenIds(): List<String>? {
        // The catalog is authoritative when the backend lists screens.
        catalog?.publicSnapshot()?.takeIf { it.backendSupportsEnumeration && it.screenIds.isNotEmpty() }?.let { return it.screenIds }
        val ids = synchronized(lock) { allScreenIds.toList() }
        if (ids.isNotEmpty()) return ids
        return cache?.enumerateScreenIds()?.takeIf { it.isNotEmpty() }
    }

    // Streaming is unsupported: the engine falls back to request/response (the stream* defaults).

    /** Full all-locales payload for the engine's translation store. */
    override suspend fun getTranslations(): ByteArray {
        synchronized(lock) { localizations }?.let { return it }
        if (cache != null && cache.hasUsableAppCache()) {
            cache.readLocalizations()?.let { disk ->
                synchronized(lock) { localizations = disk }
                return disk
            }
        }
        val raw = fetch(Endpoint.Localizations(appId))
        synchronized(lock) { localizations = raw }
        if (cache != null) {
            cache.writeLocalizations(raw)
            // Seed meta so the next prefetch can hash-compare instead of refetching as no_prior_cache.
            val existingUpdatedAt = cache.readAppResourceMeta(KEY_LOCALIZATIONS)?.updatedAt
            cache.updateAppResourceMeta(KEY_LOCALIZATIONS, ResourceMeta(existingUpdatedAt, ContentHash.sha256Hex(raw), Instant.now()))
        }
        return raw
    }

    // endregion
    // region Flow channel

    /** A published flow's lazy manifest. Network-first (fresh online), disk fallback (seeded/offline). */
    suspend fun getFlowManifest(flowKey: String, version: String?): FlowManifestResponse {
        return try {
            val raw = fetch(Endpoint.Flow(appId, flowKey, version))
            val decoded = decode("FlowManifestResponse") { FlowManifestResponse.decode(raw) }
            cache?.writeFlowManifest(raw, flowKey, version)
            decoded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val disk = cache?.readFlowManifest(flowKey, version)
            val decoded = disk?.let { runCatching { FlowManifestResponse.decode(it) }.getOrNull() } ?: throw e
            log.debug { "getFlowManifest('$flowKey'): served from disk after network error: $e" }
            decoded
        }
    }

    /**
     * A flow member screen's DSL, reachable only through the flow channel (never [getScreen]).
     * Kept in memory so back-navigation doesn't refetch.
     */
    suspend fun getFlowScreenData(flowKey: String, screenKey: String, version: String?): ByteArray {
        val key = "$flowKey/$screenKey@${version ?: CacheLayout.LATEST_VERSION_SENTINEL}"
        synchronized(flowScreens) { flowScreens[key] }?.let { return it }
        return try {
            val raw = fetch(Endpoint.FlowScreen(appId, flowKey, screenKey, version))
            val response = decode("FlowScreenRenderResponse") { ScreenRenderResponse.decode(raw) }
            synchronized(flowScreens) { flowScreens[key] = response.data }
            cache?.writeFlowScreen(response.data, flowKey, version, screenKey)
            response.data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val disk = cache?.readFlowScreen(flowKey, version, screenKey) ?: throw e
            synchronized(flowScreens) { flowScreens[key] = disk }
            log.debug { "getFlowScreenData('$flowKey/$screenKey'): served from disk after network error: $e" }
            disk
        }
    }

    /** The flow's pinned styles (the backend falls back to app-level latest when none are pinned). */
    suspend fun getFlowStyles(flowKey: String, version: String?): List<ByteArray> = try {
        val raw = fetch(Endpoint.FlowStyles(appId, flowKey, version))
        val items = decode("FlowStyleArrayResponse") { ItemsResponse.decode(raw) }
        cache?.writeFlowStyles(items, flowKey, version)
        items
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val disk = cache?.readFlowStyles(flowKey, version) ?: throw e
        log.debug { "getFlowStyles('$flowKey'): served from disk after network error: $e" }
        disk
    }

    /** The flow's pinned components (or app-level latest when none are pinned). */
    suspend fun getFlowComponents(flowKey: String, version: String?): List<ByteArray> = try {
        val raw = fetch(Endpoint.FlowComponents(appId, flowKey, version))
        val items = decode("FlowComponentArrayResponse") { ItemsResponse.decode(raw) }
        cache?.writeFlowComponents(items, flowKey, version)
        items
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val disk = cache?.readFlowComponents(flowKey, version) ?: throw e
        log.debug { "getFlowComponents('$flowKey'): served from disk after network error: $e" }
        disk
    }

    // endregion
    // region Asset references, fonts and images

    /** The assets screen [id] needs, read from [source] (this data source or a flow-scoped one). */
    suspend fun collectAssetReferences(id: String, source: App8DataSource = this): AssetReferenceSet {
        val screen = if (source === this) loadScreen(id, reportCacheHits = false) else source.getScreen(id)
        val styleBlobs = runCatching { source.getStyles() }.getOrDefault(emptyList())
        val componentBlobs = runCatching { source.getComponents() }.getOrDefault(emptyList())
        return AssetReferenceCollector.collect(screen, styleBlobs, componentBlobs)
    }

    /** Registers screen [id]'s fonts before render so text doesn't fall back. Idempotent. */
    suspend fun ensureScreenFontsRegistered(id: String, source: App8DataSource = this) {
        val refs = try {
            collectAssetReferences(id, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warning { "ensureScreenFontsRegistered: collectAssetReferences failed for $id: $e" }
            return
        }
        val toFetch = refs.fonts.filter { !fontRegistry.isRegistered(it.postScriptName) }
        if (toFetch.isEmpty()) return
        val started = System.nanoTime()
        bounded(toFetch) { downloadAndRegisterFont(it) }
        log.debug { "ensureScreenFontsRegistered: screen=$id — registered ${toFetch.size} font(s) in ${(System.nanoTime() - started) / 1_000_000}ms." }
    }

    /**
     * Warms screen [id]'s named images into the asset cache so the first paint is complete (no
     * blank-then-pop). Skips videos (`video/` MIME types): large, and they stream behind their pre-warmed poster.
     */
    suspend fun ensureScreenImagesWarmed(id: String, source: App8DataSource = this) {
        val refs = try {
            collectAssetReferences(id, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warning { "ensureScreenImagesWarmed: collectAssetReferences failed for $id: $e" }
            return
        }
        val named = refs.images.filter { it.id != null || it.name != null }
        if (named.isEmpty()) return
        val manifest = runCatching { ensureAssetManifest() }.getOrNull()
        val toWarm = named.filter { ref ->
            val entry = manifest?.resolve(ref.id, ref.name) ?: return@filter true // unknown mime: warm it
            entry.mimeType?.startsWith("video/") != true
        }
        bounded(toWarm) { ref -> runCatching { getAsset(ref.id, ref.name) } }
    }

    /**
     * Prefetches the images and fonts screen [id] references that are not cached yet. Raw `https`
     * URLs without an id/name are not warmed: the engine's image cache is internal on Android
     * (iOS warms the shared `URLCache` the engine reads).
     */
    suspend fun prefetchScreenImages(id: String) {
        val refs = try {
            collectAssetReferences(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warning { "prefetchScreenImages: collectAssetReferences failed for $id: $e" }
            return
        }
        log.debug { "[Prefetch] screen='$id' refs: images=${refs.images.size} fonts=${refs.fonts.size}" }
        val images = refs.images.filter { ref ->
            val key = ref.id?.takeIf { it.isNotEmpty() } ?: ref.name?.takeIf { it.isNotEmpty() } ?: return@filter false
            assetCache?.read(key) == null
        }
        val fonts = refs.fonts.filter { !fontRegistry.isRegistered(it.postScriptName) }
        if (images.isEmpty() && fonts.isEmpty()) {
            log.debug { "[Prefetch] screen='$id' — nothing to fetch (${refs.images.size} images, ${refs.fonts.size} fonts already warm)." }
            return
        }
        val started = System.nanoTime()
        // Images and fonts share one bounded pool: they share the network anyway.
        val work: List<suspend () -> Unit> = images.map { ref ->
            suspend {
                try {
                    getAsset(ref.id, ref.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warning { "prefetchScreenImages: getAsset failed for id=${ref.id} name=${ref.name}: $e" }
                }
                Unit
            }
        } + fonts.map { ref -> suspend { downloadAndRegisterFont(ref) } }
        bounded(work) { it() }
        log.debug { "prefetchScreenImages: screen=$id — warmed ${images.size} image(s) + ${fonts.size} font(s) in ${(System.nanoTime() - started) / 1_000_000}ms." }
    }

    /** The engine's asset hint first; otherwise a manifest file whose basename is the PostScript name. */
    private suspend fun downloadAndRegisterFont(ref: FontReference) {
        val assetId: String?
        val assetName: String?
        if (ref.asset != null) {
            assetId = ref.asset.id
            assetName = ref.asset.name
        } else {
            val manifest = runCatching { ensureAssetManifest() }.getOrNull() ?: return
            val hit = manifest.byFilename.entries.firstOrNull { (filename, _) -> filename.substringBeforeLast('.') == ref.postScriptName }
            assetId = hit?.value?.id
            assetName = hit?.key
        }
        if (assetId == null && assetName == null) return
        try {
            val data = getAsset(assetId, assetName)
            if (data == null) {
                log.warning { "downloadAndRegisterFont: asset not found for ${ref.postScriptName}." }
                return
            }
            val registerId = assetId ?: assetName ?: ref.postScriptName
            if (fontRegistry.register(data, registerId, assetName ?: ref.postScriptName) != null) {
                onFontsRegistered?.invoke()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warning { "downloadAndRegisterFont: getAsset failed for ${ref.postScriptName}: $e" }
        }
    }

    // endregion
    // region Prefetch (cache warming, no engine render)

    sealed interface PrefetchOutcome {
        data object CachedFresh : PrefetchOutcome
        data object Unchanged : PrefetchOutcome
        data class Refreshed(val reason: InvalidationReason) : PrefetchOutcome
    }

    enum class InvalidationReason(val wire: String) {
        VERSION_CHANGED("version_changed"),
        UPDATED_AT_CHANGED("updated_at_changed"),
        CONTENT_CHANGED("content_changed"),
        NO_PRIOR_CACHE("no_prior_cache"),
    }

    suspend fun prefetchScreen(id: String, version: String?, expected: ScreenFreshness? = null): PrefetchOutcome {
        val key = cacheKey(id, version)
        val stored = cache?.readScreenMeta(id)
        val storedBlob = cache?.readScreen(id, version)

        if (expected != null && stored != null && storedBlob != null && freshnessMatches(stored, expected)) {
            synchronized(lock) { screensByCacheKey[key] = storedBlob }
            return PrefetchOutcome.CachedFresh
        }
        // No signal: trust the cache when the backend ships no updatedAt (warm-once semantics).
        if (expected?.updatedAt == null && storedBlob != null && (expected?.version == null || stored?.servedVersion == expected.version)) {
            synchronized(lock) { screensByCacheKey[key] = storedBlob }
            return PrefetchOutcome.CachedFresh
        }

        // Wipe BEFORE fetching so a failed fetch leaves the screen uncached.
        val preCheckReason = when {
            expected != null && stored != null -> mismatchReason(stored, expected)
            stored == null -> InvalidationReason.NO_PRIOR_CACHE
            else -> null
        }
        if (preCheckReason != null && preCheckReason != InvalidationReason.NO_PRIOR_CACHE) {
            cache?.clearScreen(id)
            synchronized(lock) { screensByCacheKey.keys.removeAll { it.startsWith("$id@") } }
        }

        val response = fetchScreenResponse(id, version)
        val newHash = ContentHash.sha256Hex(response.data)
        // Skip the write only when the blob exists AND its hash matches.
        val canTrustStoredBlob = preCheckReason == null && storedBlob != null
        val priorHash = if (canTrustStoredBlob) stored?.contentHash else null
        val bytesChanged = priorHash != newHash

        applyScreenResponse(response, id, version, expected, stored, newHash, skipDiskWrite = !bytesChanged)
        reportServed(response.servedVersion, false, id)
        return if (!bytesChanged) PrefetchOutcome.Unchanged else PrefetchOutcome.Refreshed(preCheckReason ?: InvalidationReason.CONTENT_CHANGED)
    }

    /** Matches only when `updatedAt` is on both sides: without it an in-place edit can't be ruled out. */
    private fun freshnessMatches(stored: ScreenMeta, expected: ScreenFreshness): Boolean {
        val expectedUpdated = expected.updatedAt ?: return false
        val storedUpdated = stored.updatedAt ?: return false
        if (expectedUpdated != storedUpdated) return false
        if (expected.version != null && expected.version != stored.servedVersion) return false
        return true
    }

    private fun mismatchReason(stored: ScreenMeta, expected: ScreenFreshness): InvalidationReason? {
        if (expected.version != null && stored.servedVersion != null && expected.version != stored.servedVersion) {
            return InvalidationReason.VERSION_CHANGED
        }
        if (expected.updatedAt != null && stored.updatedAt != null && expected.updatedAt != stored.updatedAt) {
            return InvalidationReason.UPDATED_AT_CHANGED
        }
        return null
    }

    // endregion
    // region Screen fetch

    private fun cacheKey(screenId: String, version: String?): String =
        "$screenId@${version ?: CacheLayout.LATEST_VERSION_SENTINEL}"

    /** Render-time fetch on a cache miss. */
    private suspend fun fetchScreen(screenId: String, version: String?): ByteArray {
        val priorMeta = cache?.readScreenMeta(screenId)
        val response = fetchScreenResponse(screenId, version)
        applyScreenResponse(response, screenId, version, null, priorMeta, ContentHash.sha256Hex(response.data), skipDiskWrite = false)
        reportServed(response.servedVersion, false, screenId)
        return response.data
    }

    private suspend fun fetchScreenResponse(screenId: String, version: String?): ScreenRenderResponse {
        val raw = fetch(Endpoint.Screen(appId, screenId, version))
        return decode("ScreenRenderResponse") { ScreenRenderResponse.decode(raw) }
    }

    private fun applyScreenResponse(
        response: ScreenRenderResponse,
        screenId: String,
        requestedVersion: String?,
        expected: ScreenFreshness?,
        existingMeta: ScreenMeta?,
        contentHash: String,
        skipDiskWrite: Boolean,
    ) {
        val body = response.data
        val key = cacheKey(screenId, requestedVersion)
        catalog?.mergeSeen(screenId)
        synchronized(lock) {
            screensByCacheKey[key] = body
            if (screenId !in allScreenIds) allScreenIds += screenId
            response.styles?.let { styles = mergeBlobs(styles, it) }
            response.components?.let { inline ->
                val byId = HashMap(componentsByDslId)
                inline.forEach { blob -> extractDslId(blob)?.let { byId[it] = blob } }
                componentsByDslId = byId
                components = mergeBlobs(components, inline)
            }
            // Mark the loaders satisfied so the app-level fetches (which REPLACE) don't clobber the
            // merged set. Assumes inline styles/components are the complete set.
            if (response.styles != null) stylesLoaded = true
            if (response.components != null) componentsLoaded = true
            response.datasources?.forEach { (path, blob) -> datasources[path] = blob }
        }
        if (!skipDiskWrite) cache?.writeScreen(screenId, requestedVersion, body)
        // Served-version alias for later explicit-pin reads; rewritten if pruning removed it.
        val served = response.servedVersion
        if (served != null && served != (requestedVersion ?: "") && cache?.readScreen(screenId, served) == null) {
            cache?.writeScreen(screenId, served, body)
        }
        // The render-time fetch passes expected = null: keep the prior updatedAt so the next
        // prefetch's cheap pre-check still works.
        cache?.writeScreenMeta(
            ScreenMeta(
                servedVersion = response.servedVersion,
                requestedVersion = requestedVersion,
                updatedAt = expected?.updatedAt ?: existingMeta?.updatedAt,
                contentHash = contentHash,
            ),
            screenId,
        )
    }

    private fun mergeBlobs(existing: List<ByteArray>, new: List<ByteArray>): List<ByteArray> {
        val seen = existing.mapTo(HashSet()) { it.decodeToString() }
        val out = ArrayList(existing)
        for (blob in new) if (seen.add(blob.decodeToString())) out += blob
        return out
    }

    // endregion
    // region App-level freshness checks (prefetch only; render uses load*IfNeeded)

    suspend fun refreshManifestIfChanged(expectedUpdatedAt: String?): PrefetchOutcome {
        val stored = cache?.readAppResourceMeta(KEY_MANIFEST)
        if (expectedUpdatedAt != null && stored != null && stored.updatedAt == expectedUpdatedAt && cache?.readManifest() != null) {
            hydrateManifestFromDiskIfCold()
            return PrefetchOutcome.CachedFresh
        }
        // No signal: trust the cache, capped by the max-age so silent backend updates surface daily.
        if (expectedUpdatedAt == null && cache?.readManifest() != null && isWithinNoSignalMaxAge(stored?.fetchedAt)) {
            hydrateManifestFromDiskIfCold()
            return PrefetchOutcome.CachedFresh
        }
        val raw = fetch(Endpoint.Manifest(appId))
        val response = decode("AppManifestResponse") { AppManifestResponse.decode(raw) }
        val newHash = ContentHash.sha256Hex(response.configuration)
        val priorHash = stored?.contentHash
        val changed = priorHash != newHash
        if (changed) {
            synchronized(lock) { manifest = response.configuration }
            cache?.writeManifest(response.configuration)
        } else {
            synchronized(lock) { if (manifest == null) manifest = response.configuration }
        }
        cache?.updateAppResourceMeta(KEY_MANIFEST, ResourceMeta(expectedUpdatedAt ?: stored?.updatedAt, newHash, Instant.now()))
        return outcome(changed, priorHash)
    }

    suspend fun refreshStylesIfChanged(expectedUpdatedAt: String?): PrefetchOutcome {
        val stored = cache?.readAppResourceMeta(KEY_STYLES)
        if (expectedUpdatedAt != null && stored != null && stored.updatedAt == expectedUpdatedAt && cache?.readStylesBlob() != null) {
            hydrateStylesFromDiskIfCold()
            return PrefetchOutcome.CachedFresh
        }
        if (expectedUpdatedAt == null && cache?.readStylesBlob() != null && isWithinNoSignalMaxAge(stored?.fetchedAt)) {
            hydrateStylesFromDiskIfCold()
            return PrefetchOutcome.CachedFresh
        }
        val raw = fetch(Endpoint.Styles(appId))
        val items = decode("StyleArrayResponse") { ItemsResponse.decode(raw) }
        val newHash = ContentHash.sha256Hex(raw)
        val priorHash = stored?.contentHash
        val changed = priorHash != newHash
        if (changed) {
            synchronized(lock) {
                styles = items
                stylesLoaded = true
            }
            cache?.writeStyles(items)
        } else {
            synchronized(lock) {
                if (!stylesLoaded) {
                    styles = items
                    stylesLoaded = true
                }
            }
        }
        cache?.updateAppResourceMeta(KEY_STYLES, ResourceMeta(expectedUpdatedAt ?: stored?.updatedAt, newHash, Instant.now()))
        return outcome(changed, priorHash)
    }

    suspend fun refreshComponentsIfChanged(expectedUpdatedAt: String?): PrefetchOutcome {
        val stored = cache?.readAppResourceMeta(KEY_COMPONENTS)
        if (expectedUpdatedAt != null && stored != null && stored.updatedAt == expectedUpdatedAt) {
            val blobs = cache?.readAllComponents().orEmpty()
            if (blobs.isNotEmpty()) {
                hydrateComponentsFromDiskIfCold(blobs)
                return PrefetchOutcome.CachedFresh
            }
        }
        if (expectedUpdatedAt == null && isWithinNoSignalMaxAge(stored?.fetchedAt)) {
            val blobs = cache?.readAllComponents().orEmpty()
            if (blobs.isNotEmpty()) {
                hydrateComponentsFromDiskIfCold(blobs)
                return PrefetchOutcome.CachedFresh
            }
        }
        val raw = fetch(Endpoint.Components(appId))
        val items = decode("ComponentArrayResponse") { ItemsResponse.decode(raw) }
        val newHash = ContentHash.sha256Hex(raw)
        val priorHash = stored?.contentHash
        val changed = priorHash != newHash
        val byId = items.mapNotNull { blob -> extractDslId(blob)?.let { it to blob } }.toMap()
        if (changed) {
            synchronized(lock) {
                components = items
                componentsByDslId = byId
                componentsLoaded = true
            }
            cache?.writeComponents(items, ::extractDslId)
        } else {
            synchronized(lock) {
                if (!componentsLoaded) {
                    components = items
                    componentsByDslId = byId
                    componentsLoaded = true
                }
            }
        }
        cache?.updateAppResourceMeta(KEY_COMPONENTS, ResourceMeta(expectedUpdatedAt ?: stored?.updatedAt, newHash, Instant.now()))
        return outcome(changed, priorHash)
    }

    suspend fun refreshLocalizationsIfChanged(expectedUpdatedAt: String?): PrefetchOutcome {
        val stored = cache?.readAppResourceMeta(KEY_LOCALIZATIONS)
        if (expectedUpdatedAt != null && stored != null && stored.updatedAt == expectedUpdatedAt && cache?.readLocalizations() != null) {
            hydrateLocalizationsFromDiskIfCold()
            return PrefetchOutcome.CachedFresh
        }
        if (expectedUpdatedAt == null && cache?.readLocalizations() != null && isWithinNoSignalMaxAge(stored?.fetchedAt)) {
            hydrateLocalizationsFromDiskIfCold()
            return PrefetchOutcome.CachedFresh
        }
        val raw = fetch(Endpoint.Localizations(appId))
        val newHash = ContentHash.sha256Hex(raw)
        val priorHash = stored?.contentHash
        val changed = priorHash != newHash
        if (changed) {
            synchronized(lock) { localizations = raw }
            cache?.writeLocalizations(raw)
        } else {
            synchronized(lock) { if (localizations == null) localizations = raw }
        }
        cache?.updateAppResourceMeta(KEY_LOCALIZATIONS, ResourceMeta(expectedUpdatedAt ?: stored?.updatedAt, newHash, Instant.now()))
        return outcome(changed, priorHash)
    }

    private fun outcome(changed: Boolean, priorHash: String?): PrefetchOutcome = when {
        !changed -> PrefetchOutcome.Unchanged
        priorHash == null -> PrefetchOutcome.Refreshed(InvalidationReason.NO_PRIOR_CACHE)
        else -> PrefetchOutcome.Refreshed(InvalidationReason.CONTENT_CHANGED)
    }

    /** `null` is legacy meta without `fetchedAt`: one pass while it gets rewritten. */
    private fun isWithinNoSignalMaxAge(fetchedAt: Instant?): Boolean =
        fetchedAt == null || Duration.between(fetchedAt, Instant.now()) < NO_SIGNAL_MAX_AGE

    private fun hydrateManifestFromDiskIfCold() {
        val blob = cache?.readManifest() ?: return
        synchronized(lock) { if (manifest == null) manifest = blob }
    }

    private fun hydrateStylesFromDiskIfCold() {
        val cache = cache ?: return
        if (cache.readStylesBlob() == null) return
        val disk = cache.readStyles()
        synchronized(lock) {
            if (!stylesLoaded) {
                styles = disk
                stylesLoaded = true
            }
        }
    }

    private fun hydrateComponentsFromDiskIfCold(blobs: List<ByteArray>) {
        if (synchronized(lock) { componentsLoaded } || blobs.isEmpty()) return
        val byId = blobs.mapNotNull { blob -> extractDslId(blob)?.let { it to blob } }.toMap()
        synchronized(lock) {
            components = blobs
            componentsByDslId = byId
            componentsLoaded = true
        }
    }

    private fun hydrateLocalizationsFromDiskIfCold() {
        val disk = cache?.readLocalizations() ?: return
        synchronized(lock) { if (localizations == null) localizations = disk }
    }

    // endregion
    // region Render-time loaders

    private suspend fun loadManifestIfNeeded() {
        if (synchronized(lock) { manifest } == null) {
            val disk = if (cache != null && cache.hasUsableAppCache()) cache.readManifest() else null
            if (disk != null) {
                synchronized(lock) { manifest = disk }
            } else {
                val raw = fetch(Endpoint.Manifest(appId))
                val response = decode("AppManifestResponse") { AppManifestResponse.decode(raw) }
                synchronized(lock) { manifest = response.configuration }
                cache?.writeManifest(response.configuration)
                cache?.touchMeta()
            }
        }
        prepareFontsIfNeeded()
    }

    /** Warms the asset manifest once; concurrent callers share the run. */
    suspend fun prepareFontsIfNeeded() {
        val (task, owner) = synchronized(assetLock) {
            if (fontsPrepared) return
            inFlightFontPrep?.let { return@synchronized it to false }
            CompletableDeferred<Unit>().also { inFlightFontPrep = it } to true
        }
        if (!owner) {
            task.await()
            return
        }
        try {
            val started = System.nanoTime()
            try {
                ensureAssetManifest()
                log.debug { "prepareFontsIfNeeded: asset manifest cached in ${(System.nanoTime() - started) / 1_000_000}ms." }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warning { "prepareFontsIfNeeded: asset manifest fetch failed ($e) — skipping font registration." }
            }
            // Fonts download per screen (prefetch / render), not eagerly from the manifest.
        } finally {
            synchronized(assetLock) {
                fontsPrepared = true
                if (inFlightFontPrep === task) inFlightFontPrep = null
            }
            task.complete(Unit)
        }
    }

    private suspend fun loadStylesIfNeeded() {
        // The engine asks for styles before the manifest: prepare fonts here too.
        prepareFontsIfNeeded()
        if (synchronized(lock) { stylesLoaded }) return
        // File presence (even an empty array) means we fetched before.
        if (cache != null && cache.readStylesBlob() != null) {
            val disk = cache.readStyles()
            synchronized(lock) {
                styles = disk
                stylesLoaded = true
            }
            return
        }
        val raw = fetch(Endpoint.Styles(appId))
        val items = decode("StyleArrayResponse") { ItemsResponse.decode(raw) }
        synchronized(lock) {
            styles = items
            stylesLoaded = true
        }
        cache?.writeStyles(items)
    }

    private suspend fun loadComponentsIfNeeded() {
        if (synchronized(lock) { componentsLoaded }) return
        // Disk-first: otherwise every cold start would hit /components.
        if (cache != null && cache.hasUsableAppCache()) {
            val disk = cache.readAllComponents()
            if (disk.isNotEmpty()) {
                val byId = disk.mapNotNull { blob -> extractDslId(blob)?.let { it to blob } }.toMap()
                synchronized(lock) {
                    components = disk
                    componentsByDslId = byId
                    componentsLoaded = true
                }
                return
            }
        }
        val raw = fetch(Endpoint.Components(appId))
        val items = decode("ComponentArrayResponse") { ItemsResponse.decode(raw) }
        val byId = items.mapNotNull { blob -> extractDslId(blob)?.let { it to blob } }.toMap()
        synchronized(lock) {
            components = items
            componentsByDslId = byId
            componentsLoaded = true
        }
        cache?.writeComponents(items, ::extractDslId)
    }

    // endregion
    // region Asset manifest

    private class AssetEntry(val id: String, val filename: String, val mimeType: String?, val downloadUrl: String)

    private class AssetManifestEntries(
        val byId: Map<String, AssetEntry>,
        val byFilename: Map<String, AssetEntry>,
        /** Filename without extension: DSL `remoteAsset` refs use bare names ("IconMeteors" for "IconMeteors.png"). */
        val byBasename: Map<String, AssetEntry>,
        val expiresAt: Instant,
    ) {
        fun resolve(id: String?, name: String?): AssetEntry? {
            id?.let { byId[it] }?.let { return it }
            if (name != null) {
                // Exact filename first (DSL includes the extension), then the bare basename.
                byFilename[name]?.let { return it }
                byBasename[name]?.let { return it }
            }
            return null
        }

        val isExpired: Boolean get() = !Instant.now().isBefore(expiresAt)
    }

    @Serializable
    private class PersistedAssetsManifest(
        val schemaVersion: String,
        @Serializable(with = InstantIsoSerializer::class) val fetchedAt: Instant,
        val expiresIn: Int,
        val assets: List<Item>,
    ) {
        @Serializable
        class Item(val id: String, val filename: String, val mimeType: String? = null, val downloadUrl: String)
    }

    private suspend fun ensureAssetManifest(): AssetManifestEntries {
        synchronized(assetLock) { assetManifest }?.takeIf { !it.isExpired }?.let { return it }
        // Disk hydrate saves a round trip on warm starts.
        if (synchronized(assetLock) { assetManifest } == null && cache != null && cache.hasUsableAppCache()) {
            val persisted = cache.readAssetsManifest()?.let { bytes ->
                runCatching { CloudJson.decodeFromString(PersistedAssetsManifest.serializer(), bytes.decodeToString()) }.getOrNull()
            }
            if (persisted != null) {
                val now = Instant.now()
                val expiresAt = persisted.fetchedAt.plusSeconds(persisted.expiresIn.toLong())
                // `fetchedAt <= now` guards against a wall-clock rollback.
                if (diskManifestUsable(persisted.fetchedAt, expiresAt, now)) {
                    val entries = entriesFrom(persisted.assets.map { AssetEntry(it.id, it.filename, it.mimeType, it.downloadUrl) }, expiresAt)
                    synchronized(assetLock) { assetManifest = entries }
                    log.debug { "ensureAssetManifest: hydrated from disk (${persisted.assets.size} asset(s), expires $expiresAt)." }
                    return entries
                }
                log.debug { "ensureAssetManifest: disk copy expired at $expiresAt; re-fetching." }
            }
        }
        val prior = synchronized(assetLock) { assetManifest }
        log.debug { if (prior != null) "ensureAssetManifest: re-fetching — prior manifest expired at ${prior.expiresAt}." else "ensureAssetManifest: first fetch." }
        val raw = fetch(Endpoint.AssetsManifest(appId))
        val decoded = decode("AssetsResponse") { AssetsResponse.decode(raw) }
        // Floor at 60s: a backend sending 0 would refetch on every lookup.
        val effectiveExpiresIn = maxOf(decoded.expiresIn, 60)
        if (effectiveExpiresIn != decoded.expiresIn) {
            log.warning { "ensureAssetManifest: backend expiresIn=${decoded.expiresIn}s is too low; flooring to ${effectiveExpiresIn}s for in-memory cache." }
        }
        val now = Instant.now()
        val entries = entriesFrom(
            decoded.assets.map { AssetEntry(it.id, it.filename, it.mimeType, it.downloadUrl) },
            now.plusSeconds(effectiveExpiresIn.toLong()),
        )
        synchronized(assetLock) { assetManifest = entries }
        // Persist the effective (floored) expiresIn so the next launch applies the same policy.
        val persisted = PersistedAssetsManifest(
            CacheLayout.SCHEMA_VERSION, now, effectiveExpiresIn,
            decoded.assets.map { PersistedAssetsManifest.Item(it.id, it.filename, it.mimeType, it.downloadUrl) },
        )
        runCatching { CloudJson.encodeToString(PersistedAssetsManifest.serializer(), persisted).encodeToByteArray() }
            .getOrNull()?.let { cache?.writeAssetsManifest(it) }
        return entries
    }

    private fun entriesFrom(items: List<AssetEntry>, expiresAt: Instant): AssetManifestEntries {
        val byId = HashMap<String, AssetEntry>()
        val byFilename = LinkedHashMap<String, AssetEntry>()
        val byBasename = HashMap<String, AssetEntry>()
        for (item in items) {
            byId[item.id] = item
            byFilename[item.filename] = item
            // First wins on basename collisions (icon.png vs icon.jpg): a bare ref is ambiguous anyway.
            val base = item.filename.substringBeforeLast('.')
            if (base.isNotEmpty() && base !in byBasename) byBasename[base] = item
        }
        return AssetManifestEntries(byId, byFilename, byBasename, expiresAt)
    }

    // endregion
    // region Published-screen discovery

    data class PublishedScreen(val screenKey: String, val version: String, val minDslVersion: String, val updatedAt: String?)

    /** Per-resource `updatedAt` from `/screens`; null fields fall back to hash compares. */
    data class AppResourcesSnapshot(val manifest: String?, val styles: String?, val components: String?, val localizations: String?)

    class PublishedSnapshot(val screens: List<PublishedScreen>, val resources: AppResourcesSnapshot?)

    /** `null` when the backend predates the endpoint (404). Throws on transport, auth or decode failures. */
    suspend fun fetchPublishedScreens(): PublishedSnapshot? {
        val raw = try {
            fetch(Endpoint.ListScreens(appId))
        } catch (e: App8CloudError.ServerError) {
            if (e.status != 404) throw e
            log.debug { "[Prefetch] backend has no /apps/{id}/screens endpoint (404) — falling back to flow BFS only." }
            catalog?.markBackendUnsupported()
            return null
        }
        val decoded = decode("ListScreensResponse") { ListScreensResponse.decode(raw) }
        val screens = decoded.screens.map { PublishedScreen(it.screenKey, it.version, it.minDslVersion, it.updatedAt) }
        val resources = decoded.resources?.let { AppResourcesSnapshot(it.manifest, it.styles, it.components, it.localizations) }
        catalog?.replace(
            ScreenCatalogStore.NetworkSnapshot(
                screens.map { ScreenCatalogStore.NetworkSnapshot.Screen(it.screenKey, it.version, it.minDslVersion, it.updatedAt) },
            ),
        )
        return PublishedSnapshot(screens, resources)
    }

    // endregion
    // region Internals

    private fun readIdentity(): Map<String, String> = bridge?.identityAttributes.orEmpty()

    private fun readPinnedVersion(screenId: String): String? = bridge?.pinnedVersion(screenId)

    private fun reportServed(version: String?, fromCache: Boolean, screenId: String) {
        bridge?.recordServed(version, fromCache, screenId)
    }

    private fun emitAssetFetchFailed(assetId: String, error: Throwable) {
        // Cancellation isn't a failure.
        if (error is CancellationException) return
        val telemetry = telemetry ?: return
        val ctx = linkedMapOf<String, Any?>("assetId" to assetId)
        if (error is App8CloudError) {
            ctx["reason"] = telemetryReasonString(error)
            if (error is App8CloudError.ServerError) ctx["status"] = error.status
        } else {
            ctx["reason"] = "unknown"
        }
        telemetry.enqueue(TelemetryEvent("asset_fetch_failed", context = ctx))
    }

    private suspend fun fetch(endpoint: Endpoint): ByteArray {
        val identity = readIdentity()
        return coalescer.run(endpoint.coalesceKey) { client.get(endpoint, identity).data }
    }

    private inline fun <T> decode(context: String, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw App8CloudError.DecodeFailed(context, e)
    }

    /** Runs [work] over [items] with at most four in flight. */
    private suspend fun <T> bounded(items: List<T>, work: suspend (T) -> Unit) {
        if (items.isEmpty()) return
        val permits = Semaphore(4)
        coroutineScope {
            items.forEach { item -> launch { permits.withPermit { work(item) } } }
        }
    }

    // endregion

    private class NoBlobError : Exception("no blob")

    companion object {
        const val KEY_MANIFEST: String = "manifest"
        const val KEY_STYLES: String = "styles"
        const val KEY_COMPONENTS: String = "components"
        const val KEY_LOCALIZATIONS: String = "localizations"

        /** Cap on the no-signal cache-trust window: a daily re-check catches silent backend updates. */
        val NO_SIGNAL_MAX_AGE: Duration = Duration.ofHours(24)

        /** Disk-hydrate gate for the asset manifest: unexpired, and not fetched "in the future" (clock rollback). */
        fun diskManifestUsable(fetchedAt: Instant, expiresAt: Instant, now: Instant): Boolean =
            expiresAt.isAfter(now) && !fetchedAt.isAfter(now)
    }
}
