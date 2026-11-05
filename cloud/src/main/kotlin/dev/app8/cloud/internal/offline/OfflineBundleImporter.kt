package dev.app8.cloud.internal.offline

import dev.app8.cloud.App8CloudError
import dev.app8.cloud.OfflineImportSummary
import dev.app8.cloud.internal.ContentHash
import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.cache.AssetCache
import dev.app8.cloud.internal.cache.DiskCache
import dev.app8.cloud.internal.cache.ResourceMeta
import dev.app8.cloud.internal.cache.ScreenMeta
import dev.app8.cloud.internal.dslVersionIsSupported
import dev.app8.cloud.internal.extractDslId
import dev.app8.cloud.internal.fonts.FontRegistry
import dev.app8.cloud.internal.objOrNull
import dev.app8.cloud.internal.string
import dev.app8.cloud.internal.toBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Imports a `.a8pack` into the existing disk cache (iOS `OfflineBundleImporter`), so the
 * cache-first read path renders it offline and refreshes it when online. See
 * `docs/OFFLINE_BUNDLE_FORMAT.md`.
 */
internal object OfflineBundleImporter {

    const val TYPE_SCREEN: String = "app8.offline.screen"
    const val TYPE_FLOW: String = "app8.offline.flow"

    fun importBundle(
        pack: PackageSource,
        instanceAppId: String,
        maxSupportedDslVersion: String,
        diskCache: DiskCache?,
        assetCache: AssetCache?,
        fontRegistry: FontRegistry,
        log: Diagnostics,
    ): OfflineImportSummary {
        diskCache ?: throw App8CloudError.OfflineBundleInvalid("disk cache is disabled; enable DiskCachePolicy to import bundles")
        val manifest = OfflineManifest.load(pack)
        if (manifest.bundleFormat > OfflineManifest.SUPPORTED_BUNDLE_FORMAT) {
            throw App8CloudError.OfflineBundleInvalid(
                "bundleFormat ${manifest.bundleFormat} is newer than supported ${OfflineManifest.SUPPORTED_BUNDLE_FORMAT}",
            )
        }
        if (manifest.appId != instanceAppId) {
            throw App8CloudError.OfflineBundleInvalid("bundle appId '${manifest.appId}' != instance appId '$instanceAppId'")
        }
        manifest.dslVersion?.let { dsl ->
            if (!dslVersionIsSupported(dsl, maxSupportedDslVersion)) {
                throw App8CloudError.DslVersionUnsupported(dsl, maxSupportedDslVersion)
            }
        }
        // Validate the asset list before writing anything.
        manifest.assets.forEach { checkPath(it.path) }

        val payload = manifest.decodePayload()
        val version = manifest.version

        // App-wide pieces (both bundle types).
        payload["localizations"]?.let(::jsonData)?.let { data ->
            diskCache.writeLocalizations(data)
            diskCache.updateAppResourceMeta("localizations", resourceMeta(payload, "localizations", data))
        }
        payload["datasources"]?.objOrNull?.forEach { (compoundKey, value) ->
            val data = jsonData(value) ?: return@forEach
            val parts = compoundKey.split('/', limit = 2)
            if (parts.size != 2) {
                log.warning { "offline import: datasource key '$compoundKey' is not 'category/name' — skipping" }
                return@forEach
            }
            diskCache.writeDatasource(parts[0], parts[1], data)
        }

        val screensImported = when (manifest.type) {
            TYPE_SCREEN -> importScreenBundle(payload, version, diskCache)
            TYPE_FLOW -> importFlowBundle(payload, manifest.key, version, diskCache, log)
            else -> throw App8CloudError.OfflineBundleInvalid("unknown bundle type '${manifest.type}'")
        }

        // Seal app meta so `hasUsableAppCache()` is true and disk-first reads engage.
        diskCache.touchMeta(manifest.dslVersion)

        val (assets, fonts) = importAssets(manifest.assets, pack, assetCache, fontRegistry, log)
        log.debug {
            "offline import: ${manifest.type} key='${manifest.key}' version=${version ?: "latest"} " +
                "screens=$screensImported assets=$assets fonts=$fonts"
        }
        return OfflineImportSummary(manifest.type, manifest.key, version, screensImported, assets, fonts)
    }

    private fun importScreenBundle(payload: JsonObject, version: String?, diskCache: DiskCache): Int {
        payload["app"]?.let(::jsonData)?.let { data ->
            diskCache.writeManifest(data)
            diskCache.updateAppResourceMeta("manifest", resourceMeta(payload, "manifest", data))
        }
        // Write styles.json even when empty so the styles loader reads disk instead of the network.
        diskCache.writeStyles(blobArray(payload["styles"]))
        diskCache.updateAppResourceMeta("styles", resourceMeta(payload, "styles", payload["styles"]?.let(::jsonData) ?: EMPTY_ARRAY))
        diskCache.writeComponents(blobArray(payload["components"]), ::extractDslId)

        var count = 0
        payload["screens"]?.objOrNull?.forEach { (screenKey, value) ->
            val entry = value.objOrNull ?: return@forEach
            val data = entry["data"]?.let(::jsonData) ?: return@forEach
            val v = entry.string("version") ?: version
            // Both the explicit version and `_latest`, so latest and pinned reads resolve offline.
            diskCache.writeScreen(screenKey, v, data)
            diskCache.writeScreen(screenKey, null, data)
            diskCache.writeScreenMeta(
                ScreenMeta(servedVersion = v, requestedVersion = v, updatedAt = entry.string("updatedAt"), contentHash = ContentHash.sha256Hex(data)),
                screenKey,
            )
            count++
        }
        return count
    }

    private fun importFlowBundle(payload: JsonObject, flowKey: String, version: String?, diskCache: DiskCache, log: Diagnostics): Int {
        val flow = payload["flow"]?.objOrNull
        if (flow == null) {
            log.warning { "offline import: flow bundle is missing its 'flow' payload" }
            return 0
        }
        flow["manifest"]?.let(::jsonData)?.let { data ->
            diskCache.writeFlowManifest(data, flowKey, version)
            diskCache.writeFlowManifest(data, flowKey, null)
        }
        val styles = blobArray(flow["styles"])
        diskCache.writeFlowStyles(styles, flowKey, version)
        diskCache.writeFlowStyles(styles, flowKey, null)
        val components = blobArray(flow["components"])
        diskCache.writeFlowComponents(components, flowKey, version)
        diskCache.writeFlowComponents(components, flowKey, null)

        var count = 0
        flow["screens"]?.objOrNull?.forEach { (screenKey, value) ->
            val data = jsonData(value) ?: return@forEach
            diskCache.writeFlowScreen(data, flowKey, version, screenKey)
            diskCache.writeFlowScreen(data, flowKey, null, screenKey)
            count++
        }
        return count
    }

    /** Relative, no traversal: the path must stay under the bundle root. */
    private fun checkPath(path: String) {
        if (path.startsWith("/") || path.startsWith("\\") || path.split('/', '\\').any { it == ".." }) {
            throw App8CloudError.OfflineBundleInvalid("unsafe asset path '$path'")
        }
    }

    private fun importAssets(
        descriptors: List<OfflineAssetDescriptor>,
        pack: PackageSource,
        assetCache: AssetCache?,
        fontRegistry: FontRegistry,
        log: Diagnostics,
    ): Pair<Int, Int> {
        var assetCount = 0
        var fontCount = 0
        for (d in descriptors) {
            checkPath(d.path)
            val bytes = pack.read(d.path) ?: throw App8CloudError.OfflineBundleInvalid("missing asset file '${d.path}'")
            val expected = d.sha256
            if (!expected.isNullOrEmpty()) {
                val actual = ContentHash.sha256Hex(bytes)
                if (actual != expected.lowercase()) {
                    throw App8CloudError.OfflineBundleInvalid("checksum mismatch for '${d.path}' (expected $expected, got $actual)")
                }
            }
            // Seed every key the engine may resolve by: id, filename, basename, name.
            val keys = LinkedHashSet<String>()
            listOfNotNull(d.id, d.filename, d.name).filter { it.isNotEmpty() }.forEach(keys::add)
            d.filename?.substringBeforeLast('.')?.takeIf { it.isNotEmpty() }?.let(keys::add)
            if (assetCache != null) {
                keys.forEach { assetCache.write(it, bytes) }
            } else {
                log.warning { "offline import: asset cache disabled — image '${d.path}' will miss at render time" }
            }
            assetCount++

            if (d.kind == "font" || FontRegistry.isFont(d.filename.orEmpty(), d.mimeType)) {
                val assetId = d.id ?: d.filename ?: d.path
                if (fontRegistry.register(bytes, assetId, d.filename ?: d.path) != null) fontCount++
            }
        }
        return assetCount to fontCount
    }

    private val EMPTY_ARRAY = "[]".encodeToByteArray()

    /** Objects and arrays re-serialized to bytes; scalars and null are not DSL documents. */
    private fun jsonData(value: JsonElement?): ByteArray? = when (value) {
        is JsonObject, is JsonArray -> value.toBytes()
        null, is JsonNull -> null
        else -> null
    }

    private fun blobArray(value: JsonElement?): List<ByteArray> =
        (value as? JsonArray)?.mapNotNull(::jsonData).orEmpty()

    private fun resourceMeta(payload: JsonObject, key: String, body: ByteArray): ResourceMeta {
        val rm = payload["resourceMeta"]?.objOrNull?.get(key)?.objOrNull
        return ResourceMeta(rm?.string("updatedAt"), rm?.string("contentHash") ?: ContentHash.sha256Hex(body), Instant.now())
    }
}
