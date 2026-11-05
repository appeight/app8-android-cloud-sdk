package dev.app8.cloud.internal.cache

import java.io.File

/**
 * Disk cache layout keyed by `(appId, screenId, version)` (iOS `CacheLayout`):
 * ```
 * {root}/v1/{appId}/
 *   manifest.json
 *   styles.json
 *   localizations.json
 *   screens_catalog.json
 *   components/{componentDslId}.json
 *   screens/{screenId}/{_latest|version}.json
 *   screens/{screenId}/_meta.json
 *   datasources/{category}/{name}.json
 *   flows/{flowKey}/{_latest|version}/{manifest,styles,components}.json
 *   flows/{flowKey}/{_latest|version}/screens/{screenKey}.json
 *   assets/manifest.json
 *   assets/blobs/{assetIdOrFilename}
 *   meta.json
 *   lru/index.bin
 * ```
 * A schema bump leaves old version dirs on disk; `clearCache(All)` removes them.
 */
internal class CacheLayout(val cacheRoot: File, val appId: String) {

    val rootForApp: File get() = File(File(cacheRoot, SCHEMA_VERSION), sanitizedPathComponent(appId))
    val manifestFile: File get() = File(rootForApp, "manifest.json")
    val stylesFile: File get() = File(rootForApp, "styles.json")
    val componentsDir: File get() = File(rootForApp, "components")
    fun componentFile(componentId: String): File = File(componentsDir, "${sanitizedPathComponent(componentId)}.json")
    val screensDir: File get() = File(rootForApp, "screens")
    fun screenDir(screenId: String): File = File(screensDir, sanitizedPathComponent(screenId))
    fun screenFile(screenId: String, version: String?): File =
        File(screenDir(screenId), "${sanitizedPathComponent(version ?: LATEST_VERSION_SENTINEL)}.json")

    /** Sibling of the DSL files; version pruning skips it. */
    fun screenMetaFile(screenId: String): File = File(screenDir(screenId), SCREEN_META_FILENAME)
    val datasourcesDir: File get() = File(rootForApp, "datasources")
    fun datasourceFile(category: String, name: String): File =
        File(File(datasourcesDir, sanitizedPathComponent(category)), "${sanitizedPathComponent(name)}.json")
    val metaFile: File get() = File(rootForApp, "meta.json")
    val assetsDir: File get() = File(rootForApp, "assets")
    val assetsManifestFile: File get() = File(assetsDir, "manifest.json")
    val assetBlobsDir: File get() = File(assetsDir, "blobs")
    val localizationsFile: File get() = File(rootForApp, "localizations.json")

    /** The persisted screen-availability catalog. */
    val screensCatalogFile: File get() = File(rootForApp, "screens_catalog.json")

    // Flow channel (gated multi-screen bundles)
    val flowsDir: File get() = File(rootForApp, "flows")

    /** `flows/{flowKey}/{version}/`: version-scoped; `_latest` when null. */
    fun flowVersionDir(flowKey: String, version: String?): File =
        File(File(flowsDir, sanitizedPathComponent(flowKey)), sanitizedPathComponent(version ?: LATEST_VERSION_SENTINEL))
    fun flowManifestFile(flowKey: String, version: String?): File = File(flowVersionDir(flowKey, version), "manifest.json")
    fun flowStylesFile(flowKey: String, version: String?): File = File(flowVersionDir(flowKey, version), "styles.json")
    fun flowComponentsFile(flowKey: String, version: String?): File = File(flowVersionDir(flowKey, version), "components.json")
    fun flowScreenFile(flowKey: String, version: String?, screenKey: String): File =
        File(File(flowVersionDir(flowKey, version), "screens"), "${sanitizedPathComponent(screenKey)}.json")

    val lruDir: File get() = File(rootForApp, "lru")
    val lruIndexFile: File get() = File(lruDir, "index.bin")

    companion object {
        const val SCHEMA_VERSION: String = "v1"
        const val LATEST_VERSION_SENTINEL: String = "_latest"
        const val SCREEN_META_FILENAME: String = "_meta.json"
    }
}

/**
 * Maps an arbitrary string to one safe path component: separators and shell-hostile characters
 * become `_`, and the result is never empty, `.` or `..`, so a crafted id cannot escape the cache.
 */
internal fun sanitizedPathComponent(raw: String): String {
    val out = buildString(raw.length) {
        for (ch in raw) {
            append(
                when (ch) {
                    '/', '\\', '\u0000', '\n', '\r', '\t', ':', '?', '*', '<', '>', '|', '"' -> '_'
                    else -> ch
                },
            )
        }
    }
    return if (out.isEmpty() || out == "." || out == "..") "_" else out
}

/** Write to a temp file in the same directory, then rename over the target. Best-effort: false on error. */
internal fun atomicWrite(data: ByteArray, file: File): Boolean = try {
    file.parentFile?.mkdirs()
    val tmp = File.createTempFile(".${file.name.take(40)}", ".tmp", file.parentFile)
    try {
        tmp.writeBytes(data)
        java.nio.file.Files.move(
            tmp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        true
    } finally {
        tmp.delete()
    }
} catch (_: Exception) {
    false
}

internal fun File.readBytesOrNull(): ByteArray? = try {
    if (isFile) readBytes() else null
} catch (_: Exception) {
    null
}
