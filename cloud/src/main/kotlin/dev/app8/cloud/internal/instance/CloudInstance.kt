package dev.app8.cloud.internal.instance

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import dev.app8.cloud.App8CloudAdvancedApi
import dev.app8.cloud.App8CloudConfig
import dev.app8.cloud.App8CloudContent
import dev.app8.cloud.App8CloudError
import dev.app8.cloud.App8CloudInstance
import dev.app8.cloud.AppFallback
import dev.app8.cloud.CacheScope
import dev.app8.cloud.DiskCachePolicy
import dev.app8.cloud.FallbackEvent
import dev.app8.cloud.FlowFallback
import dev.app8.cloud.NetworkPolicy
import dev.app8.cloud.OfflineImportSummary
import dev.app8.cloud.PrefetchEvent
import dev.app8.cloud.PrefetchEvent.AppResourceStatus
import dev.app8.cloud.PrefetchTarget
import dev.app8.cloud.RenderEvent
import dev.app8.cloud.ScreenAvailability
import dev.app8.cloud.ScreenCatalog
import dev.app8.cloud.ScreenFallback
import dev.app8.cloud.TelemetryPolicy
import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.SDKVersion
import dev.app8.cloud.internal.arrOrNull
import dev.app8.cloud.internal.cache.AssetCache
import dev.app8.cloud.internal.cache.CacheLayout
import dev.app8.cloud.internal.cache.DiskCache
import dev.app8.cloud.internal.cache.ScreenCatalogStore
import dev.app8.cloud.internal.cache.ScreenFreshness
import dev.app8.cloud.internal.cache.defaultCacheRoot
import dev.app8.cloud.internal.data.AttributeBag
import dev.app8.cloud.internal.data.FlowScopedDataSource
import dev.app8.cloud.internal.data.RenderingBridge
import dev.app8.cloud.internal.data.RenderingDataSource
import dev.app8.cloud.internal.data.RenderingDataSource.PrefetchOutcome
import dev.app8.cloud.internal.data.ScreenReferenceWalker
import dev.app8.cloud.internal.dslVersionIsSupported
import dev.app8.cloud.internal.fonts.FontRegistry
import dev.app8.cloud.internal.net.HeaderBuilder
import dev.app8.cloud.internal.net.HostInfo
import dev.app8.cloud.internal.net.HttpClient
import dev.app8.cloud.internal.objOrNull
import dev.app8.cloud.internal.offline.OfflineBundleImporter
import dev.app8.cloud.internal.offline.OfflinePackageDiscovery
import dev.app8.cloud.internal.offline.PackageSource
import dev.app8.cloud.internal.parseJson
import dev.app8.cloud.internal.string
import dev.app8.cloud.internal.telemetry.TelemetryClient
import dev.app8.cloud.internal.telemetry.TelemetryEvent
import dev.app8.cloud.internal.telemetry.sanitizeJsonMap
import dev.app8.cloud.internal.telemetry.telemetryReasonString
import dev.app8.engine.App8
import dev.app8.engine.App8Error
import dev.app8.engine.App8Instance
import dev.app8.engine.events.App8AnalyticsBus
import dev.app8.engine.events.App8AnalyticsConfig
import dev.app8.engine.events.App8AnalyticsEvent
import dev.app8.engine.events.App8Event
import dev.app8.engine.events.App8EventBus
import dev.app8.engine.events.App8Subscription
import dev.app8.engine.style.App8FontProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The [App8CloudInstance] implementation (iOS `A8CInstance`). Wires the HTTP client, disk and
 * asset caches, font registry, screen catalog, telemetry and the engine together.
 */
internal class CloudInstance internal constructor(
    context: Context,
    private val config: App8CloudConfig,
    requestTimeout: kotlin.time.Duration = 30.seconds,
    okHttpClient: OkHttpClient? = null,
    retryDelaysMs: LongArray = longArrayOf(400, 1_200, 3_600),
) : App8CloudInstance, RenderingBridge {

    private val appContext: Context = context.applicationContext ?: context
    private val appId = config.appId
    private val maxSupportedDslVersion = config.maxSupportedDslVersion
    private val log = Diagnostics(config.diagnosticLoggingEnabled)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    // region Infrastructure

    private val attributeBag = AttributeBag(log)
    private val diskCache: DiskCache?
    private val assetCache: AssetCache?
    private val headers: HeaderBuilder
    private val httpClient: HttpClient
    private val fontRegistry: FontRegistry
    private val dataSource: RenderingDataSource
    private val engineInstance: App8Instance
    private val catalogStore: ScreenCatalogStore
    private val telemetry: TelemetryClient?

    // endregion
    // region Per-screen pins and served versions

    private val pinnedVersions = HashMap<String, String>()
    private val lastServedVersions = HashMap<String, String?>()
    private val lastServedFromCache = HashMap<String, Boolean>()

    // endregion
    // region Active flow

    /**
     * A published flow renders through its own engine over a [FlowScopedDataSource]. Kept until
     * the next [flow] or [stopApp]; its buses forward to the main engine's.
     */
    private var flowEngine: App8Instance? = null
    private var flowEventSubscription: App8Subscription? = null
    private var flowAnalyticsSubscription: App8Subscription? = null

    // endregion

    private var hostFontProvider: App8FontProvider = App8FontProvider.System
    private var eventHandlerSubscription: App8Subscription? = null
    private var analyticsHandlerSubscription: App8Subscription? = null

    @Volatile override var onScreenRendered: ((RenderEvent) -> Unit)? = null
    @Volatile override var onFallbackInvoked: ((FallbackEvent) -> Unit)? = null
    @Volatile override var onPrefetchCompleted: ((PrefetchEvent) -> Unit)? = null

    init {
        if (!config.environment.baseUrl.lowercase().startsWith("https://")) {
            log.configurationWarning(
                "App8Cloud: base URL is not HTTPS — the SDK token will be sent over an insecure connection. Use an https:// URL.",
            )
        }
        val cacheConfig = (config.diskCache as? DiskCachePolicy.Enabled)?.config
        val cacheRoot = cacheConfig?.rootDirectory ?: defaultCacheRoot(runCatching { appContext.cacheDir }.getOrNull())
        if (cacheConfig != null) {
            val layout = CacheLayout(cacheRoot, appId)
            diskCache = DiskCache(layout, cacheConfig.versionsToKeep, log)
            assetCache = AssetCache(layout.assetBlobsDir, cacheConfig.assetByteBudget, layout.lruIndexFile, log)
        } else {
            diskCache = null
            assetCache = null
        }
        headers = HeaderBuilder(config.token, SDKVersion.CURRENT, maxSupportedDslVersion, HostInfo.of(appContext))
        httpClient = HttpClient(
            config.environment.baseUrl, headers, requestTimeout, log,
            offlineOnly = config.networkPolicy == NetworkPolicy.OFFLINE_ONLY,
            clientOverride = okHttpClient,
            retryDelaysMs = retryDelaysMs,
        )
        fontRegistry = FontRegistry(File(File(cacheRoot, "fonts"), sanitizedAppDir(appId)), log)
        dataSource = RenderingDataSource(appId, httpClient, diskCache, assetCache, fontRegistry, log)
        engineInstance = App8.instance(appContext, dataSource, config.engine)
        // Teach both buses the cloud version once: every later dispatch, engine- or cloud-fired,
        // stamps `cloudVersion` (and analytics merge `cloud_version` into properties).
        engineInstance.analyticsBus.cloudVersion = SDKVersion.CURRENT
        engineInstance.eventBus.cloudVersion = SDKVersion.CURRENT
        engineInstance.fontProvider = fontRegistry.provider(hostFontProvider)
        dataSource.onFontsRegistered = ::refreshFontProviders

        catalogStore = ScreenCatalogStore(
            appId, diskCache,
            (cacheConfig?.catalogTtl ?: 24.hours).toJavaDuration(),
            log,
        )
        catalogStore.loadFromDisk()
        dataSource.bind(catalogStore)

        telemetry = when (config.telemetry) {
            TelemetryPolicy.ENABLED -> TelemetryClient(httpClient, appId, { attributeBag.snapshot }, log, appContext)
            TelemetryPolicy.DISABLED -> null
        }
        dataSource.bind(this as RenderingBridge)
        dataSource.bind(telemetry)

        telemetry?.enqueue(
            TelemetryEvent(
                "sdk_init",
                context = mapOf(
                    "hostBundleId" to (headers.host.packageName ?: "unknown"),
                    "hostVersion" to (headers.host.appVersion ?: "unknown"),
                    "sdkVersion" to SDKVersion.CURRENT,
                    "platform" to HeaderBuilder.PLATFORM,
                ),
            ),
        )
        // Non-blocking catalog refresh so the next `screen(id)` can already short-circuit.
        startCatalogRefreshIfNeeded(force = false)
    }

    // region Identity

    override fun setAttributes(attributes: Map<String, String>) {
        val rejected = attributeBag.setAttributes(attributes)
        telemetry?.enqueue(
            TelemetryEvent("attributes_set", context = mapOf("count" to attributes.size - rejected.size, "droppedReservedCount" to rejected.size)),
        )
    }

    override fun clearAttributes() {
        attributeBag.clearAttributes()
        telemetry?.enqueue(TelemetryEvent("attributes_cleared"))
    }

    override val currentAttributes: Map<String, String> get() = attributeBag.snapshot

    // endregion
    // region Locale, buses, fonts (engine pass-through)

    override fun setLocale(locale: String?) {
        engineInstance.setLocale(locale)
    }

    override val currentLocale: String get() = engineInstance.currentLocale

    @App8CloudAdvancedApi
    override val engine: App8Instance get() = engineInstance

    override val eventBus: App8EventBus get() = engineInstance.eventBus

    override val analyticsBus: App8AnalyticsBus get() = engineInstance.analyticsBus

    override var analyticsConfig: App8AnalyticsConfig
        get() = engineInstance.analyticsConfig
        set(value) {
            engineInstance.analyticsConfig = value
        }

    override var fontProvider: App8FontProvider
        get() = synchronized(lock) { hostFontProvider }
        set(value) {
            synchronized(lock) { hostFontProvider = value }
            refreshFontProviders()
        }

    override fun setEventHandler(handler: ((App8Event) -> Unit)?) {
        synchronized(lock) {
            eventHandlerSubscription?.cancel()
            eventHandlerSubscription = handler?.let { eventBus.subscribe(handler = it) }
        }
    }

    override fun setAnalyticsHandler(handler: ((App8AnalyticsEvent) -> Unit)?) {
        synchronized(lock) {
            analyticsHandlerSubscription?.cancel()
            analyticsHandlerSubscription = handler?.let { analyticsBus.observe(it) }
        }
    }

    /** New downloaded fonts (or a new host provider): hand the engines a fresh provider so text re-resolves. */
    private fun refreshFontProviders() {
        val provider = fontRegistry.provider(synchronized(lock) { hostFontProvider })
        val flow = synchronized(lock) { flowEngine }
        Snapshot.withMutableSnapshot {
            engineInstance.fontProvider = provider
            flow?.fontProvider = provider
        }
    }

    // endregion
    // region Render: throwing variants

    override suspend fun screen(id: String, version: String?, parameters: Map<String, Any?>): App8CloudContent {
        synchronized(lock) { if (version != null) pinnedVersions[id] = version else pinnedVersions.remove(id) }
        val started = System.nanoTime()
        log.info { "[Render] screen(id='$id') start" }

        // Unknown ids fail from the catalog without a round-trip, with the same errors and
        // telemetry the network path would produce.
        shortCircuitError(id, version)?.let { error ->
            emitShortCircuitTelemetry(id, error)
            emitRenderFailedTelemetry("screen", id, version, error)
            throw error
        }
        try {
            loadEngine(engineInstance)
            // Surfaces screen errors (404, 412, offline miss) as cloud errors before drawing.
            dataSource.getScreen(id)
            val fontsStarted = System.nanoTime()
            dataSource.ensureScreenFontsRegistered(id)
            log.info { "[Render] screen(id='$id') done — fonts ${(System.nanoTime() - fontsStarted) / 1_000_000}ms, total ${(System.nanoTime() - started) / 1_000_000}ms" }
            val engine = engineInstance
            val content = renderedContent("screen", id) { modifier ->
                engine.Screen(id, parameters, modifier, onError = { log.warning { "[Render] screen '$id' failed while drawing: $it" } })
            }
            fireRenderEvent("screen", id, version, started)
            return content
        } catch (e: App8CloudError) {
            // The catalog may list a screen the backend just unpublished: drop the stale entry.
            if (e is App8CloudError.ScreenNotFound) catalogStore.markStaleIfBackendSays404(id)
            emitRenderFailedTelemetry("screen", id, version, e)
            throw e
        } catch (e: App8Error) {
            val error = App8CloudError.Engine(e)
            emitRenderFailedTelemetry("screen", id, version, error)
            throw error
        }
    }

    override suspend fun startApp(version: String?): App8CloudContent {
        val started = System.nanoTime()
        try {
            loadEngine(engineInstance)
            val start = startScreenOf(dataSource.getApp()) ?: throw App8Error.AppMissingInitialScreenId()
            dataSource.getScreen(start)
            dataSource.ensureScreenFontsRegistered(start)
            val engine = engineInstance
            val content = renderedContent("app", APP_RENDER_SCREEN_KEY) { modifier ->
                engine.App(modifier, onError = { log.warning { "[Render] app failed while drawing: $it" } })
            }
            fireRenderEvent("app", APP_RENDER_SCREEN_KEY, version, started)
            return content
        } catch (e: App8CloudError) {
            emitRenderFailedTelemetry("app", APP_RENDER_SCREEN_KEY, version, e)
            throw e
        } catch (e: App8Error) {
            val error = App8CloudError.Engine(e)
            emitRenderFailedTelemetry("app", APP_RENDER_SCREEN_KEY, version, error)
            throw error
        }
    }

    override suspend fun flow(id: String, version: String?): App8CloudContent {
        val started = System.nanoTime()
        log.info { "[Render] flow(id='$id') start" }
        tearDownActiveFlow()
        try {
            val manifest = dataSource.getFlowManifest(id, version)
            // The manifest is fetched per request, so its minDslVersion is authoritative for the
            // served version: gate pinned and latest requests alike.
            manifest.minDslVersion?.let { minDsl ->
                if (!dslVersionIsSupported(minDsl, maxSupportedDslVersion)) {
                    throw App8CloudError.DslVersionUnsupported(minDsl, maxSupportedDslVersion)
                }
            }
            // Pin the session to the version the manifest resolved to, so a re-publish mid-flow
            // can't drift member screens, styles or components to a newer version.
            val resolvedVersion = manifest.servedVersion ?: version
            val scoped = FlowScopedDataSource(dataSource, id, resolvedVersion, manifest)
            val flowEngine = App8.instance(appContext, scoped, config.engine)
            flowEngine.analyticsBus.cloudVersion = SDKVersion.CURRENT
            flowEngine.eventBus.cloudVersion = SDKVersion.CURRENT
            flowEngine.fontProvider = fontRegistry.provider(synchronized(lock) { hostFontProvider })
            // Forward in-flow events to the buses host code subscribed to.
            val mainEvents = engineInstance.eventBus
            val mainAnalytics = engineInstance.analyticsBus
            synchronized(lock) {
                flowEventSubscription = flowEngine.eventBus.subscribe { mainEvents.dispatch(it) }
                flowAnalyticsSubscription = flowEngine.analyticsBus.observe { mainAnalytics.dispatch(it) }
                this.flowEngine = flowEngine
            }

            loadEngine(flowEngine)
            scoped.getScreen(manifest.startScreen)
            // Fonts and named images of the entry screen before first paint, or media pops in
            // after (the "blink"). Videos are skipped: they stream behind their warmed poster.
            dataSource.ensureScreenFontsRegistered(manifest.startScreen, scoped)
            dataSource.ensureScreenImagesWarmed(manifest.startScreen, scoped)

            val content = renderedContent("flow", id) { modifier ->
                flowEngine.Flow(id, modifier, onError = { log.warning { "[Render] flow '$id' failed while drawing: $it" } })
            }
            // Warm the other members in the background so a push paints at once.
            val remaining = manifest.screens.map { it.screenKey }.filter { it != manifest.startScreen }
            if (remaining.isNotEmpty()) {
                scope.launch {
                    for (screenKey in remaining) {
                        if (synchronized(lock) { this@CloudInstance.flowEngine } !== flowEngine) return@launch // torn down / replaced
                        dataSource.ensureScreenFontsRegistered(screenKey, scoped)
                        dataSource.ensureScreenImagesWarmed(screenKey, scoped)
                    }
                }
            }
            log.info { "[Render] flow(id='$id') done — total ${(System.nanoTime() - started) / 1_000_000}ms" }
            fireRenderEvent("flow", id, version, started)
            return content
        } catch (e: App8CloudError) {
            tearDownActiveFlow()
            emitRenderFailedTelemetry("flow", id, version, e)
            throw e
        } catch (e: App8Error) {
            tearDownActiveFlow()
            val error = App8CloudError.Engine(e)
            emitRenderFailedTelemetry("flow", id, version, error)
            throw error
        }
    }

    /** Releases the active flow engine and stops forwarding its buses. */
    private fun tearDownActiveFlow() {
        synchronized(lock) {
            flowEventSubscription?.cancel()
            flowAnalyticsSubscription?.cancel()
            flowEventSubscription = null
            flowAnalyticsSubscription = null
            flowEngine = null
        }
        dataSource.clearFlowScreenCache()
    }

    override fun stopApp() {
        tearDownActiveFlow()
        telemetry?.let { t -> scope.launch { t.shutdown() } }
    }

    /** Cancels background work without flushing (tests). */
    internal fun close() {
        tearDownActiveFlow()
        telemetry?.close()
        scope.cancel()
    }

    /** Loads the engine's app, styles, translations and templates; data-source errors surface as they are. */
    private suspend fun loadEngine(engine: App8Instance) {
        engine.prefetch()
    }

    /** Wraps engine content; with telemetry on, reports `screen_presented` at the first non-zero layout. */
    private fun renderedContent(kind: String, screenId: String, draw: @Composable (Modifier) -> Unit): App8CloudContent {
        if (telemetry == null) return App8CloudContent(null) { modifier -> Box(modifier) { draw(Modifier.fillMaxSize()) } }
        val sentinel = FirstLayoutSentinel()
        return App8CloudContent(null) { modifier ->
            val density = LocalDensity.current.density
            val configuration = LocalConfiguration.current
            val uiModeType = configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
            val smallestWidthDp = configuration.smallestScreenWidthDp
            Box(
                modifier.onSizeChanged { size ->
                    sentinel.onSize(size.width, size.height) { w, h ->
                        emitScreenPresented(kind, screenId, w / density, h / density, density, uiModeType, smallestWidthDp)
                    }
                },
            ) { draw(Modifier.fillMaxSize()) }
        }
    }

    // endregion
    // region Render: fallback variants

    override suspend fun screen(id: String, version: String?, parameters: Map<String, Any?>, fallback: ScreenFallback): App8CloudContent =
        try {
            screen(id, version, parameters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: App8CloudError) {
            invokeFallback(e, id, FallbackEvent.Source.SCREEN, id, fallback)
        } catch (e: Exception) {
            invokeFallback(App8CloudError.Engine(App8Error.AppInitFailed()), id, FallbackEvent.Source.SCREEN, id, fallback)
        }

    override suspend fun startApp(version: String?, fallback: AppFallback): App8CloudContent =
        try {
            startApp(version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: App8CloudError) {
            invokeFallback(e, null, FallbackEvent.Source.APP, APP_RENDER_SCREEN_KEY, fallback)
        } catch (e: Exception) {
            invokeFallback(App8CloudError.Engine(App8Error.AppInitFailed()), null, FallbackEvent.Source.APP, APP_RENDER_SCREEN_KEY, fallback)
        }

    override suspend fun flow(id: String, version: String?, fallback: FlowFallback): App8CloudContent =
        try {
            flow(id, version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: App8CloudError) {
            invokeFallback(e, id, FallbackEvent.Source.FLOW, FLOW_RENDER_SCREEN_KEY, fallback)
        } catch (e: Exception) {
            invokeFallback(App8CloudError.Engine(App8Error.AppInitFailed()), id, FallbackEvent.Source.FLOW, FLOW_RENDER_SCREEN_KEY, fallback)
        }

    /** Builds the fallback (closure first), then notifies [onFallbackInvoked], then telemetry. */
    private fun invokeFallback(
        error: App8CloudError,
        screenId: String?,
        source: FallbackEvent.Source,
        telemetryScreenKey: String,
        fallback: (App8CloudError) -> @Composable () -> Unit,
    ): App8CloudContent {
        val body = fallback(error)
        val content = App8CloudContent(error) { modifier -> Box(modifier) { body() } }
        onFallbackInvoked?.invoke(FallbackEvent(error, screenId, source))
        emitFallbackTelemetry(error, telemetryScreenKey)
        return content
    }

    // endregion
    // region Render telemetry

    private fun errorProperties(error: App8CloudError, snake: Boolean): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        when (error) {
            is App8CloudError.DslVersionUnsupported -> {
                out[if (snake) "dsl_version_required" else "dslVersionRequired"] = error.found
                out[if (snake) "dsl_version_client_max" else "dslVersionClientMax"] = error.max
            }
            is App8CloudError.ScreenVersionNotFound -> out["version"] = error.version
            is App8CloudError.ServerError -> out["status"] = error.status
            else -> Unit
        }
        return out
    }

    private fun emitRenderFailedTelemetry(kind: String, screenId: String?, requestedVersion: String?, error: App8CloudError) {
        // The host bus fires regardless of the remote-telemetry opt-out, gated by `autoCloudEvents`
        // (not `autoScreenEvents`, which only gates the engine's screen lifecycle).
        if (engineInstance.analyticsConfig.autoCloudEvents) {
            val props = linkedMapOf<String, Any?>("kind" to kind, "reason" to telemetryReasonString(error))
            requestedVersion?.let { props["requested_version"] = it }
            props += errorProperties(error, snake = true)
            engineInstance.analyticsBus.dispatch(App8AnalyticsEvent(App8AnalyticsEvent.Auto.RENDER_FAILED, screenId = screenId, properties = props))
        }
        val telemetry = telemetry ?: return
        val ctx = linkedMapOf<String, Any?>("kind" to kind, "reason" to telemetryReasonString(error))
        requestedVersion?.let { ctx["requestedVersion"] = it }
        ctx += errorProperties(error, snake = false)
        telemetry.enqueue(TelemetryEvent("screen_render_failed", screenKey = screenId, context = ctx))
    }

    private fun emitFallbackTelemetry(error: App8CloudError, screenId: String?) {
        if (engineInstance.analyticsConfig.autoCloudEvents) {
            val kind = if (screenId == APP_RENDER_SCREEN_KEY) "app" else "screen"
            val props = linkedMapOf<String, Any?>("kind" to kind, "reason" to telemetryReasonString(error))
            props += errorProperties(error, snake = true)
            engineInstance.analyticsBus.dispatch(App8AnalyticsEvent(App8AnalyticsEvent.Auto.RENDER_FALLBACK, screenId = screenId, properties = props))
        }
        val telemetry = telemetry ?: return
        val ctx = linkedMapOf<String, Any?>("reason" to telemetryReasonString(error))
        when (error) {
            is App8CloudError.DslVersionUnsupported -> {
                ctx["required"] = error.found
                ctx["clientMax"] = error.max
            }
            is App8CloudError.ScreenVersionNotFound -> ctx["version"] = error.version
            is App8CloudError.ServerError -> ctx["status"] = error.status
            else -> Unit
        }
        telemetry.enqueue(TelemetryEvent("render_fallback", screenKey = screenId, context = ctx))
    }

    private fun fireRenderEvent(kind: String, screenId: String, requestedVersion: String?, startedNanos: Long) {
        val durationMs = ((System.nanoTime() - startedNanos) / 1_000_000).toInt()
        val (served, fromCache) = synchronized(lock) { lastServedVersions[screenId] to (lastServedFromCache[screenId] ?: false) }
        onScreenRendered?.invoke(RenderEvent(screenId, served, durationMs, fromCache, engineInstance.currentLocale))

        // `app8.screen.rendered`: the success arm next to render.failed / render.fallback.
        if (engineInstance.analyticsConfig.autoCloudEvents) {
            val props = linkedMapOf<String, Any?>("kind" to kind, "render_ms" to durationMs, "from_cache" to fromCache)
            served?.let { props["served_version"] = it }
            requestedVersion?.let { props["requested_version"] = it }
            engineInstance.analyticsBus.dispatch(App8AnalyticsEvent(App8AnalyticsEvent.Auto.SCREEN_RENDERED, screenId = screenId, properties = props))
        }
        val telemetry = telemetry ?: return
        val ctx = linkedMapOf<String, Any?>("kind" to kind, "durationMs" to durationMs, "fromCache" to fromCache)
        requestedVersion?.let { ctx["requestedVersion"] = it }
        served?.let { ctx["servedVersion"] = it }
        telemetry.enqueue(TelemetryEvent("screen_render", screenKey = screenId, context = ctx))
    }

    private fun emitScreenPresented(
        kind: String,
        screenId: String,
        widthDp: Float,
        heightDp: Float,
        scale: Float,
        uiModeType: Int,
        smallestWidthDp: Int,
    ) {
        val telemetry = telemetry ?: return
        telemetry.enqueue(
            TelemetryEvent(
                "screen_presented",
                screenKey = screenId,
                context = mapOf(
                    "kind" to kind,
                    "width" to widthDp.toDouble(),
                    "height" to heightDp.toDouble(),
                    "scale" to scale.toDouble(),
                    "horizontalSizeClass" to sizeClassString(widthDp.toInt(), 600),
                    "verticalSizeClass" to sizeClassString(heightDp.toInt(), 480),
                    "deviceIdiom" to deviceIdiomString(uiModeType, smallestWidthDp),
                ),
            ),
        )
    }

    override fun track(name: String, context: Map<String, Any?>) {
        val telemetry = telemetry ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 128) {
            log.warning { "App8Cloud.track: invalid event name (empty or >128 chars) — dropped." }
            return
        }
        val safe = sanitizeJsonMap(context)
        val payload = linkedMapOf<String, Any?>("name" to trimmed)
        if (safe.isNotEmpty()) payload["data"] = safe
        telemetry.enqueue(TelemetryEvent("custom", context = payload))
    }

    // endregion
    // region Prefetch

    /** `refreshedCount + unchangedCount = successCount`; `invalidatedCount` ⊆ `refreshedCount`. */
    internal class PrefetchSummary {
        var targetsCount = 0
        var successCount = 0
        var failureCount = 0
        var refreshedCount = 0
        var unchangedCount = 0
        var invalidatedCount = 0
        var cancelled = false
        var manifestStatus = AppResourceStatus.NOT_RUN
        var stylesStatus = AppResourceStatus.NOT_RUN
        var componentsStatus = AppResourceStatus.NOT_RUN
        var localizationsStatus = AppResourceStatus.NOT_RUN
        var manifestReason: String? = null
        var stylesReason: String? = null
        var componentsReason: String? = null
        var localizationsReason: String? = null
    }

    private var inFlightPrefetch: Deferred<Unit>? = null

    override suspend fun prefetch(screens: List<PrefetchTarget>, includingAssets: Boolean) {
        val started = System.nanoTime()
        val summary = PrefetchSummary()
        runPrefetchTask(summary) {
            val snapshot = fetchSnapshotBestEffort()
            if (!currentCoroutineContext().isActive) return@runPrefetchTask
            runPrefetch(snapshot, screens, freshnessLookup(snapshot), skipManifestRefresh = false, includingAssets, summary)
        }
        emitPrefetchTelemetry("specific", screens.size, summary, includingAssets, started)
    }

    /** The backend's `/screens` listing unioned with a BFS over the manifest; the backend wins (it has version pins). */
    override suspend fun prefetchAll(includingAssets: Boolean) {
        val started = System.nanoTime()
        val summary = PrefetchSummary()
        runPrefetchTask(summary) {
            val discoveryStarted = System.nanoTime()
            val snapshot = try {
                dataSource.fetchPublishedScreens()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warning { "[Prefetch] /screens fetch failed: $e. Falling back to flow BFS only." }
                null
            }
            if (!currentCoroutineContext().isActive) return@runPrefetchTask
            // The manifest must be fresh before the BFS walks its navigation.
            try {
                dataSource.refreshManifestIfChanged(snapshot?.resources?.manifest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warning { "[Prefetch] manifest refresh failed: $e. BFS will run against stale manifest." }
            }
            if (!currentCoroutineContext().isActive) return@runPrefetchTask

            val published = ArrayList<PrefetchTarget>()
            if (snapshot != null) {
                var skipped = 0
                for (p in snapshot.screens) {
                    if (dslVersionIsSupported(p.minDslVersion, maxSupportedDslVersion)) published += PrefetchTarget(p.screenKey, p.version) else skipped++
                }
                log.info { "[Prefetch] backend lists ${snapshot.screens.size} published screen(s); kept ${published.size}, skipped $skipped above DSL max $maxSupportedDslVersion." }
            }
            val bfs = try {
                ScreenReferenceWalker.discover(dataSource.getApp(), runCatching { dataSource.getComponents() }.getOrDefault(emptyList())) { id ->
                    dataSource.getScreen(id)
                }.map { PrefetchTarget(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warning { "[Prefetch] flow BFS failed: $e" }
                emptyList()
            }
            val seen = HashSet<String>()
            val targets = (published + bfs).filter { seen.add(it.id) }
            val discoveryMs = (System.nanoTime() - discoveryStarted) / 1_000_000
            if (targets.isEmpty()) {
                log.warning { "[Prefetch] no screens discovered — only app-level state will be warmed." }
            } else {
                log.info { "[Prefetch] discovered ${targets.size} screen(s) in ${discoveryMs}ms (backend ${published.size} + BFS ${bfs.size} unioned): ${targets.map { it.id }.sorted()}" }
            }
            runPrefetch(snapshot, targets, freshnessLookup(snapshot), skipManifestRefresh = true, includingAssets, summary)
        }
        emitPrefetchTelemetry("all", summary.targetsCount, summary, includingAssets, started)
    }

    /**
     * Runs [body] as the single in-flight prefetch: the previous one is cancelled and awaited
     * first, so only one writer touches `meta.json` at a time. Cancellation marks [summary].
     */
    private suspend fun runPrefetchTask(summary: PrefetchSummary, body: suspend () -> Unit) {
        val task = synchronized(lock) {
            val previous = inFlightPrefetch
            scope.async {
                previous?.let {
                    it.cancel()
                    it.join()
                }
                if (!isActive) return@async
                body()
            }.also { inFlightPrefetch = it }
        }
        try {
            task.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                task.cancel()
                throw e
            }
        }
        if (task.isCancelled) summary.cancelled = true
        synchronized(lock) { if (inFlightPrefetch === task) inFlightPrefetch = null }
    }

    override fun cancelPrefetch() {
        synchronized(lock) {
            inFlightPrefetch?.cancel()
            inFlightPrefetch = null
        }
    }

    private suspend fun fetchSnapshotBestEffort(): RenderingDataSource.PublishedSnapshot? = try {
        dataSource.fetchPublishedScreens()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warning { "[Prefetch] /screens fetch failed: $e. Using hash-only freshness." }
        null
    }

    private fun freshnessLookup(snapshot: RenderingDataSource.PublishedSnapshot?): Map<String, ScreenFreshness> =
        snapshot?.screens?.associate { it.screenKey to ScreenFreshness(it.version, it.updatedAt) }.orEmpty()

    private fun statusOf(outcome: PrefetchOutcome): Pair<AppResourceStatus, String?> = when (outcome) {
        PrefetchOutcome.CachedFresh -> AppResourceStatus.CACHED to null
        PrefetchOutcome.Unchanged -> AppResourceStatus.UNCHANGED to null
        is PrefetchOutcome.Refreshed -> AppResourceStatus.REFRESHED to outcome.reason.wire
    }

    private suspend fun runPrefetch(
        snapshot: RenderingDataSource.PublishedSnapshot?,
        screens: List<PrefetchTarget>,
        freshnessBy: Map<String, ScreenFreshness>,
        skipManifestRefresh: Boolean,
        includingAssets: Boolean,
        summary: PrefetchSummary,
    ) {
        val prefetchStart = System.nanoTime()
        summary.targetsCount = screens.size
        suspend fun cancelled(): Boolean = !currentCoroutineContext().isActive.also { if (!it) summary.cancelled = true }

        // Cancellation checks between refreshes stop a cancelled task writing meta.json.
        if (!skipManifestRefresh) {
            if (cancelled()) return
            try {
                val (s, r) = statusOf(dataSource.refreshManifestIfChanged(snapshot?.resources?.manifest))
                summary.manifestStatus = s
                summary.manifestReason = r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                summary.manifestStatus = AppResourceStatus.FAILED
                log.warning { "Prefetch: manifest refresh failed: $e" }
            }
        } else {
            summary.manifestStatus = AppResourceStatus.SKIPPED
        }
        if (cancelled()) return
        try {
            val (s, r) = statusOf(dataSource.refreshStylesIfChanged(snapshot?.resources?.styles))
            summary.stylesStatus = s
            summary.stylesReason = r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            summary.stylesStatus = AppResourceStatus.FAILED
            log.warning { "Prefetch: styles refresh failed: $e" }
        }
        if (cancelled()) return
        try {
            val (s, r) = statusOf(dataSource.refreshComponentsIfChanged(snapshot?.resources?.components))
            summary.componentsStatus = s
            summary.componentsReason = r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            summary.componentsStatus = AppResourceStatus.FAILED
            log.warning { "Prefetch: components refresh failed: $e" }
        }
        if (cancelled()) return
        // Non-fatal: a backend without /localizations still finishes the prefetch.
        try {
            val (s, r) = statusOf(dataSource.refreshLocalizationsIfChanged(snapshot?.resources?.localizations))
            summary.localizationsStatus = s
            summary.localizationsReason = r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            summary.localizationsStatus = AppResourceStatus.FAILED
            log.warning { "Prefetch: localizations refresh skipped — $e" }
        }
        // Always warm the asset manifest, even without assets: otherwise the first render's font
        // lookup fetches it.
        if (cancelled()) return
        dataSource.prepareFontsIfNeeded()

        if (screens.isNotEmpty()) {
            val permits = Semaphore(4)
            coroutineScope {
                for (target in screens) {
                    launch {
                        permits.withPermit {
                            if (!isActive) {
                                synchronized(summary) { summary.cancelled = true }
                                return@withPermit
                            }
                            prefetchOne(target, freshnessBy[target.id], includingAssets, summary)
                        }
                    }
                }
            }
        }
        fun render(s: AppResourceStatus, reason: String?) = if (s == AppResourceStatus.REFRESHED && reason != null) "${s.wire}($reason)" else s.wire
        log.info {
            "[Prefetch] done in ${(System.nanoTime() - prefetchStart) / 1_000_000}ms — app: " +
                "{manifest=${render(summary.manifestStatus, summary.manifestReason)}, " +
                "styles=${render(summary.stylesStatus, summary.stylesReason)}, " +
                "components=${render(summary.componentsStatus, summary.componentsReason)}, " +
                "localizations=${render(summary.localizationsStatus, summary.localizationsReason)}} — screens: " +
                "cached=${summary.unchangedCount} refreshed=${summary.refreshedCount} " +
                "invalidated=${summary.invalidatedCount} failed=${summary.failureCount}" +
                if (summary.cancelled) " cancelled" else ""
        }
    }

    private suspend fun prefetchOne(target: PrefetchTarget, expected: ScreenFreshness?, includingAssets: Boolean, summary: PrefetchSummary) {
        val started = System.nanoTime()
        val outcome = try {
            dataSource.prefetchScreen(target.id, target.version, expected)
        } catch (e: CancellationException) {
            synchronized(summary) { summary.cancelled = true }
            throw e
        } catch (e: Exception) {
            log.warning { "[Prefetch] screen '${target.id}' (version=${target.version ?: "latest"}) DSL fetch failed: $e" }
            synchronized(summary) { summary.failureCount++ }
            return
        }
        val dslMs = (System.nanoTime() - started) / 1_000_000
        var assetsMs = 0L
        if (includingAssets) {
            val assetsStarted = System.nanoTime()
            dataSource.prefetchScreenImages(target.id)
            assetsMs = (System.nanoTime() - assetsStarted) / 1_000_000
        }
        log.info { "[Prefetch] screen '${target.id}' $outcome — DSL ${dslMs}ms, assets ${assetsMs}ms, total ${(System.nanoTime() - started) / 1_000_000}ms" }
        synchronized(summary) {
            summary.successCount++
            when (outcome) {
                PrefetchOutcome.CachedFresh, PrefetchOutcome.Unchanged -> summary.unchangedCount++
                is PrefetchOutcome.Refreshed -> {
                    summary.refreshedCount++
                    if (outcome.reason == RenderingDataSource.InvalidationReason.VERSION_CHANGED ||
                        outcome.reason == RenderingDataSource.InvalidationReason.UPDATED_AT_CHANGED
                    ) {
                        summary.invalidatedCount++
                    }
                }
            }
        }
    }

    private fun emitPrefetchTelemetry(scope: String, screenCount: Int, summary: PrefetchSummary, includingAssets: Boolean, startedNanos: Long) {
        val durationMs = ((System.nanoTime() - startedNanos) / 1_000_000).toInt()
        // Fires even with remote telemetry disabled.
        onPrefetchCompleted?.invoke(
            PrefetchEvent(
                durationMs = durationMs,
                scope = scope,
                manifest = summary.manifestStatus,
                styles = summary.stylesStatus,
                components = summary.componentsStatus,
                localizations = summary.localizationsStatus,
                manifestReason = summary.manifestReason,
                stylesReason = summary.stylesReason,
                componentsReason = summary.componentsReason,
                localizationsReason = summary.localizationsReason,
                screensCount = screenCount,
                screensCached = summary.unchangedCount,
                screensRefreshed = summary.refreshedCount,
                screensInvalidated = summary.invalidatedCount,
                screensFailed = summary.failureCount,
                cancelled = summary.cancelled,
            ),
        )
        telemetry?.enqueue(
            TelemetryEvent(
                "prefetch_completed",
                context = mapOf(
                    "scope" to scope,
                    "screenCount" to screenCount,
                    "successCount" to summary.successCount,
                    "failureCount" to summary.failureCount,
                    "refreshedCount" to summary.refreshedCount,
                    "unchangedCount" to summary.unchangedCount,
                    "invalidatedCount" to summary.invalidatedCount,
                    "includingAssets" to includingAssets,
                    "cancelled" to summary.cancelled,
                    "durationMs" to durationMs,
                ),
            ),
        )
    }

    // endregion
    // region Cache

    override suspend fun clearCache(scope: CacheScope) {
        withContext(Dispatchers.IO) {
            when (scope) {
                CacheScope.All -> {
                    diskCache?.clearAll()
                    assetCache?.reset()
                }
                is CacheScope.Screen -> diskCache?.clearScreen(scope.id)
                CacheScope.AssetsOnly -> assetCache?.reset()
            }
        }
        // The in-memory mirror too, or the next prefetch skips the refetch.
        dataSource.resetInMemoryState(scope)
        if (scope == CacheScope.All) {
            // clearAll already removed screens_catalog.json. Drop the in-memory copy and refresh in
            // the background so an immediate `screen(id)` isn't stuck with an empty catalog.
            catalogStore.resetForCacheClear()
            startCatalogRefreshIfNeeded(force = true)
        }
    }

    // endregion
    // region Offline bundles

    override suspend fun importOfflineBundle(directory: File): OfflineImportSummary =
        importPackage(PackageSource.Directory(directory))

    private suspend fun importPackage(pack: PackageSource): OfflineImportSummary = withContext(Dispatchers.IO) {
        OfflineBundleImporter.importBundle(pack, appId, maxSupportedDslVersion, diskCache, assetCache, fontRegistry, log)
    }.also { if (it.fontsRegistered > 0) refreshFontProviders() }

    override suspend fun importBundledPackages(): List<OfflineImportSummary> {
        val out = ArrayList<OfflineImportSummary>()
        for (pack in withContext(Dispatchers.IO) { OfflinePackageDiscovery.discover(appContext, appId) }) {
            try {
                out += importPackage(pack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warning { "importBundledPackages: skipped '${pack.name}': $e" }
            }
        }
        return out
    }

    // endregion
    // region Screen availability

    override fun availability(screenId: String): ScreenAvailability = when (catalogStore.availability(screenId)) {
        is ScreenCatalogStore.Decision.Known -> ScreenAvailability.KNOWN
        ScreenCatalogStore.Decision.UnknownFresh -> ScreenAvailability.UNKNOWN
        ScreenCatalogStore.Decision.UnknownStale, ScreenCatalogStore.Decision.CatalogNotLoaded -> ScreenAvailability.CATALOG_NOT_LOADED
    }

    override val catalog: ScreenCatalog? get() = catalogStore.publicSnapshot()

    override suspend fun awaitCatalogReady(timeout: kotlin.time.Duration): ScreenCatalog? {
        catalogStore.publicSnapshot()?.let { return it }
        startCatalogRefreshIfNeeded(force = false)
        val job = synchronized(lock) { catalogRefreshJob } ?: return catalogStore.publicSnapshot()
        // Null on timeout (not a partial snapshot): callers use this to assert "ready".
        val finished = withTimeoutOrNull(timeout) {
            job.join()
            true
        } ?: false
        return if (finished) catalogStore.publicSnapshot() else null
    }

    override suspend fun refreshCatalog() {
        startCatalogRefreshIfNeeded(force = true)
        synchronized(lock) { catalogRefreshJob }?.join()
    }

    private var catalogRefreshJob: Job? = null

    /** Identity of the installed refresh, so a replaced refresh never clears its successor's slot. */
    private var catalogRefreshToken: Any? = null

    /**
     * `force` cancels and replaces an in-flight refresh (the HTTP coalescer still collapses the
     * overlapping GETs); without it, an in-flight refresh is reused.
     */
    private fun startCatalogRefreshIfNeeded(force: Boolean) {
        synchronized(lock) {
            if (!force && catalogRefreshJob != null) return
            catalogRefreshJob?.cancel()
            val token = Any()
            catalogRefreshToken = token
            catalogRefreshJob = scope.launch { performCatalogRefresh(token) }
        }
    }

    private suspend fun performCatalogRefresh(token: Any) {
        try {
            // Updates the catalog as a side effect (replace on success, unsupported on 404).
            dataSource.fetchPublishedScreens()
        } catch (e: CancellationException) {
            // Replaced by a forced refresh.
        } catch (e: Exception) {
            log.warning { "[Catalog] background refresh failed: $e. Will retry on next trigger." }
        } finally {
            synchronized(lock) {
                if (catalogRefreshToken === token) {
                    catalogRefreshJob = null
                    catalogRefreshToken = null
                }
            }
        }
    }

    private fun shortCircuitError(id: String, requestedVersion: String?): App8CloudError? =
        when (val decision = catalogStore.availability(id)) {
            is ScreenCatalogStore.Decision.Known -> {
                // The DSL gate applies to "latest" only: for a pinned version the server decides.
                val minDsl = decision.entry.minDslVersion
                if (requestedVersion == null && minDsl != null && !dslVersionIsSupported(minDsl, maxSupportedDslVersion)) {
                    App8CloudError.DslVersionUnsupported(minDsl, maxSupportedDslVersion)
                } else {
                    null
                }
            }
            ScreenCatalogStore.Decision.UnknownFresh -> App8CloudError.ScreenNotFound(id)
            // Stale or missing catalog: fall through to the network, refresh in the background.
            ScreenCatalogStore.Decision.UnknownStale, ScreenCatalogStore.Decision.CatalogNotLoaded -> {
                startCatalogRefreshIfNeeded(force = false)
                null
            }
        }

    private fun emitShortCircuitTelemetry(screenId: String, error: App8CloudError) {
        val ageMs = catalogStore.ageMillis() ?: -1L
        val source = catalogStore.currentSource().wire
        val count = catalogStore.screenCount()
        if (engineInstance.analyticsConfig.autoCloudEvents) {
            engineInstance.analyticsBus.dispatch(
                App8AnalyticsEvent(
                    App8AnalyticsEvent.Auto.SCREEN_SHORTCIRCUIT,
                    screenId = screenId,
                    properties = mapOf(
                        "reason" to telemetryReasonString(error),
                        "catalog_age_ms" to ageMs,
                        "catalog_source" to source,
                        "catalog_screen_count" to count,
                    ),
                ),
            )
        }
        telemetry?.enqueue(
            TelemetryEvent(
                "screen_availability_shortcircuit",
                screenKey = screenId,
                context = mapOf(
                    "reason" to telemetryReasonString(error),
                    "catalogAge_ms" to ageMs,
                    "catalogSource" to source,
                    "catalogScreenCount" to count,
                ),
            ),
        )
    }

    // endregion
    // region RenderingBridge

    override val identityAttributes: Map<String, String> get() = attributeBag.snapshot

    override fun pinnedVersion(screenId: String): String? = synchronized(lock) { pinnedVersions[screenId] }

    override fun recordServed(version: String?, fromCache: Boolean, screenId: String) {
        synchronized(lock) {
            lastServedVersions[screenId] = version
            lastServedFromCache[screenId] = fromCache
        }
    }

    // endregion

    internal companion object {
        /** Synthetic screen keys so app- and flow-level render events join across event types. */
        const val APP_RENDER_SCREEN_KEY: String = "<app>"
        const val FLOW_RENDER_SCREEN_KEY: String = "<flow>"

        private fun sanitizedAppDir(appId: String): String = dev.app8.cloud.internal.cache.sanitizedPathComponent(appId)

        /** `navigation.startFlow`'s start screen, else a legacy top-level `initialScreenId`. */
        fun startScreenOf(manifest: ByteArray): String? = runCatching {
            val app = parseJson(manifest).objOrNull ?: return null
            val nav = app["navigation"]?.objOrNull
            val startFlow = nav?.string("startFlow")
            val flows = nav?.get("flows")?.arrOrNull?.mapNotNull { it.objOrNull }.orEmpty()
            flows.firstOrNull { it.string("id") == startFlow }?.string("startScreen") ?: app.string("initialScreenId")
        }.getOrNull()
    }
}
