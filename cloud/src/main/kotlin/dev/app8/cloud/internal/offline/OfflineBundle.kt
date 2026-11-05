package dev.app8.cloud.internal.offline

import android.content.res.AssetManager
import dev.app8.cloud.App8CloudError
import dev.app8.cloud.internal.CloudJson
import dev.app8.cloud.internal.objOrNull
import dev.app8.cloud.internal.parseJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Base64

/** The `manifest.json` envelope. The DSL payload rides base64 in [content] and is parsed separately. */
@Serializable
internal class OfflineManifest(
    val type: String,
    val name: String,
    val version: String? = null,
    val dslVersion: String? = null,
    val bundleFormat: Int,
    val appId: String,
    val key: String,
    val createdAt: String? = null,
    val content: String,
    val assets: List<OfflineAssetDescriptor>,
) {
    fun decodePayload(): JsonObject {
        val raw = runCatching { Base64.getDecoder().decode(content) }.getOrNull()
            ?: throw App8CloudError.OfflineBundleInvalid("content is not valid base64")
        return runCatching { parseJson(raw).objOrNull }.getOrNull()
            ?: throw App8CloudError.OfflineBundleInvalid("content payload is not a JSON object")
    }

    companion object {
        /** The newest container schema this build understands. */
        const val SUPPORTED_BUNDLE_FORMAT: Int = 1

        fun load(pack: PackageSource): OfflineManifest {
            val data = pack.read("manifest.json")
                ?: throw App8CloudError.OfflineBundleInvalid("missing manifest.json at ${pack.name}")
            return try {
                CloudJson.decodeFromString(serializer(), data.decodeToString())
            } catch (e: Exception) {
                throw App8CloudError.OfflineBundleInvalid("manifest.json decode failed: $e")
            }
        }
    }
}

@Serializable
internal class OfflineAssetDescriptor(
    val id: String? = null,
    val name: String? = null,
    val filename: String? = null,
    val kind: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val postScriptName: String? = null,
    val path: String,
)

/**
 * Where a `.a8pack` lives: an unpacked directory on disk, or a folder inside the APK's assets
 * (Android's counterpart of an iOS bundle folder reference).
 */
internal interface PackageSource {
    val name: String

    /** Bytes at [relativePath] under the package root, or null when absent. Callers validate the path first. */
    fun read(relativePath: String): ByteArray?

    class Directory(private val root: File) : PackageSource {
        override val name: String get() = root.name

        override fun read(relativePath: String): ByteArray? {
            val base = root.canonicalFile
            val file = File(base, relativePath).canonicalFile
            // Stays under the bundle root.
            if (!file.path.startsWith(base.path + File.separator)) {
                throw App8CloudError.OfflineBundleInvalid("asset path escapes bundle: '$relativePath'")
            }
            return runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
        }
    }

    class Assets(private val assets: AssetManager, private val path: String) : PackageSource {
        override val name: String get() = path.substringAfterLast('/')

        override fun read(relativePath: String): ByteArray? =
            runCatching { assets.open("$path/$relativePath").use { it.readBytes() } }.getOrNull()
    }
}
