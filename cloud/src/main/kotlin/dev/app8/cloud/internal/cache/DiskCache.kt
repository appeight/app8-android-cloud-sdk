package dev.app8.cloud.internal.cache

import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.SDKVersion
import dev.app8.cloud.internal.parseJson
import dev.app8.cloud.internal.toBytes
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.time.Instant

/** File-backed cache (iOS `DiskCache`). IO errors become misses / `false` (best-effort). */
internal class DiskCache(
    val layout: CacheLayout,
    versionsToKeep: Int,
    private val log: Diagnostics,
) {
    /** Cached version files kept per screen besides `_latest`; 0 disables pruning. */
    private val versionsToKeep = maxOf(0, versionsToKeep)

    /** Serializes read-modify-write of `meta.json` ([touchMeta] / [updateAppResourceMeta]). */
    private val metaLock = Any()

    init {
        // Wipe a stale-schema cache so disk-first readers don't decode bytes shaped for an older schema.
        val existing = MetaStore.readApp(layout.metaFile)
        if (existing != null && existing.schemaVersion != CacheLayout.SCHEMA_VERSION) {
            log.warning { "DiskCache: on-disk schema '${existing.schemaVersion}' != current '${CacheLayout.SCHEMA_VERSION}' — wiping app cache." }
            layout.rootForApp.deleteRecursively()
        }
        ensureDirectories()
    }

    // region Existence and meta

    fun hasUsableAppCache(): Boolean = MetaStore.readApp(layout.metaFile)?.schemaVersion == CacheLayout.SCHEMA_VERSION

    fun readAppMeta(): CacheMeta? = MetaStore.readApp(layout.metaFile)

    fun readScreenMeta(screenId: String): ScreenMeta? = MetaStore.readScreen(layout.screenMetaFile(screenId))

    fun readAppResourceMeta(key: String): ResourceMeta? = readAppMeta()?.appResources?.get(key)

    // endregion
    // region Reads

    fun readManifest(): ByteArray? = layout.manifestFile.readBytesOrNull()
    fun readStylesBlob(): ByteArray? = layout.stylesFile.readBytesOrNull()
    fun readScreen(screenId: String, version: String?): ByteArray? = layout.screenFile(screenId, version).readBytesOrNull()
    fun readComponent(componentId: String): ByteArray? = layout.componentFile(componentId).readBytesOrNull()

    fun readAllComponents(): List<ByteArray> =
        layout.componentsDir.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }?.mapNotNull { it.readBytesOrNull() }.orEmpty()

    fun readDatasource(category: String, name: String): ByteArray? = layout.datasourceFile(category, name).readBytesOrNull()
    fun readLocalizations(): ByteArray? = layout.localizationsFile.readBytesOrNull()
    fun readAssetsManifest(): ByteArray? = layout.assetsManifestFile.readBytesOrNull()
    fun readScreensCatalog(): ByteArray? = layout.screensCatalogFile.readBytesOrNull()

    fun readStyles(): List<ByteArray> = readStylesBlob()?.let(::decodeArrayOfBlobs).orEmpty()

    fun enumerateScreenIds(): List<String> =
        layout.screensDir.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()

    // endregion
    // region Writes

    fun writeManifest(data: ByteArray): Boolean = atomicWrite(data, layout.manifestFile)
    fun writeStyles(blobs: List<ByteArray>): Boolean = atomicWrite(encodeArrayOfBlobs(blobs), layout.stylesFile)

    fun writeScreen(screenId: String, version: String?, data: ByteArray): Boolean {
        val ok = atomicWrite(data, layout.screenFile(screenId, version))
        pruneScreenVersions(screenId)
        return ok
    }

    fun writeComponent(componentId: String, data: ByteArray): Boolean = atomicWrite(data, layout.componentFile(componentId))
    fun writeDatasource(category: String, name: String, data: ByteArray): Boolean =
        atomicWrite(data, layout.datasourceFile(category, name))
    fun writeLocalizations(data: ByteArray): Boolean = atomicWrite(data, layout.localizationsFile)
    fun writeAssetsManifest(data: ByteArray): Boolean = atomicWrite(data, layout.assetsManifestFile)
    fun writeScreensCatalog(data: ByteArray): Boolean = atomicWrite(data, layout.screensCatalogFile)
    fun clearScreensCatalog() {
        layout.screensCatalogFile.delete()
    }

    fun writeComponents(blobs: List<ByteArray>, idResolver: (ByteArray) -> String?): Boolean {
        var allOk = true
        for (blob in blobs) {
            val id = idResolver(blob) ?: continue
            allOk = atomicWrite(blob, layout.componentFile(id)) && allOk
        }
        return allOk
    }

    fun touchMeta(dslVersion: String? = null): Boolean = synchronized(metaLock) {
        val existing = MetaStore.readApp(layout.metaFile)
        MetaStore.writeApp(
            CacheMeta(
                sdkVersion = SDKVersion.CURRENT,
                appResources = existing?.appResources,
                fetchedAt = Instant.now(),
                dslVersion = dslVersion ?: existing?.dslVersion,
            ),
            layout.metaFile,
        )
    }

    fun writeScreenMeta(meta: ScreenMeta, screenId: String): Boolean = MetaStore.writeScreen(meta, layout.screenMetaFile(screenId))

    fun updateAppResourceMeta(key: String, meta: ResourceMeta): Boolean = synchronized(metaLock) {
        val existing = MetaStore.readApp(layout.metaFile)
        val resources = (existing?.appResources ?: emptyMap()) + (key to meta)
        MetaStore.writeApp(
            CacheMeta(
                schemaVersion = CacheLayout.SCHEMA_VERSION,
                sdkVersion = SDKVersion.CURRENT,
                appResources = resources,
                fetchedAt = Instant.now(),
                dslVersion = existing?.dslVersion,
            ),
            layout.metaFile,
        )
    }

    // endregion
    // region Flow channel

    fun readFlowManifest(flowKey: String, version: String?): ByteArray? = layout.flowManifestFile(flowKey, version).readBytesOrNull()
    fun writeFlowManifest(data: ByteArray, flowKey: String, version: String?): Boolean =
        atomicWrite(data, layout.flowManifestFile(flowKey, version))

    fun readFlowScreen(flowKey: String, version: String?, screenKey: String): ByteArray? =
        layout.flowScreenFile(flowKey, version, screenKey).readBytesOrNull()
    fun writeFlowScreen(data: ByteArray, flowKey: String, version: String?, screenKey: String): Boolean =
        atomicWrite(data, layout.flowScreenFile(flowKey, version, screenKey))

    fun readFlowStyles(flowKey: String, version: String?): List<ByteArray>? =
        layout.flowStylesFile(flowKey, version).readBytesOrNull()?.let(::decodeArrayOfBlobs)
    fun writeFlowStyles(blobs: List<ByteArray>, flowKey: String, version: String?): Boolean =
        atomicWrite(encodeArrayOfBlobs(blobs), layout.flowStylesFile(flowKey, version))

    fun readFlowComponents(flowKey: String, version: String?): List<ByteArray>? =
        layout.flowComponentsFile(flowKey, version).readBytesOrNull()?.let(::decodeArrayOfBlobs)
    fun writeFlowComponents(blobs: List<ByteArray>, flowKey: String, version: String?): Boolean =
        atomicWrite(encodeArrayOfBlobs(blobs), layout.flowComponentsFile(flowKey, version))

    // endregion
    // region Clear

    fun clearAll() {
        layout.rootForApp.deleteRecursively()
        ensureDirectories()
    }

    fun clearScreen(id: String) {
        layout.screenDir(id).deleteRecursively()
    }

    // endregion

    /** Keeps `_latest` plus the [versionsToKeep] most recently written version files of a screen. */
    private fun pruneScreenVersions(screenId: String) {
        if (versionsToKeep <= 0) return
        val entries = layout.screenDir(screenId).listFiles() ?: return
        val latestName = "${CacheLayout.LATEST_VERSION_SENTINEL}.json"
        val versioned = entries.filter {
            it.extension == "json" && it.name != latestName && it.name != CacheLayout.SCREEN_META_FILENAME
        }
        if (versioned.size <= versionsToKeep) return
        versioned.sortedByDescending { it.lastModified() }.drop(versionsToKeep).forEach { victim ->
            victim.delete()
            log.debug { "DiskCache: pruned old screen version ${victim.name} for '$screenId'." }
        }
    }

    private fun ensureDirectories() {
        listOf(
            layout.rootForApp, layout.componentsDir, layout.screensDir, layout.datasourcesDir,
            layout.assetsDir, layout.assetBlobsDir, layout.lruDir,
        ).forEach { it.mkdirs() }
    }

    private fun encodeArrayOfBlobs(blobs: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write('['.code)
        blobs.forEachIndexed { i, blob ->
            if (i > 0) out.write(','.code)
            out.write(blob)
        }
        out.write(']'.code)
        return out.toByteArray()
    }

    private fun decodeArrayOfBlobs(data: ByteArray): List<ByteArray> =
        runCatching { (parseJson(data) as? JsonArray)?.map { it.toBytes() } }.getOrNull().orEmpty()
}

/** Default cache root: `<cacheDir>/App8Cloud`. */
internal fun defaultCacheRoot(cacheDir: File?): File =
    File(cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "."), "App8Cloud")
