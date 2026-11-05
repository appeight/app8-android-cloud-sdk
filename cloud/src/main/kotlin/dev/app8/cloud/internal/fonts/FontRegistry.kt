package dev.app8.cloud.internal.fonts

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.cache.atomicWrite
import dev.app8.cloud.internal.cache.sanitizedPathComponent
import dev.app8.engine.style.App8FontProvider
import java.io.File

/**
 * Fonts the SDK downloads or imports, served to the engine as an [App8FontProvider] (iOS
 * `FontRegistry`, which registers with CoreText; Android has no process font registry, so the
 * engine asks the provider by DSL name). Idempotent per asset id and per PostScript name.
 * Files are written under [fontDir] and loaded lazily by Compose.
 */
internal class FontRegistry(private val fontDir: File, private val log: Diagnostics) {

    private class Face(val info: SfntInfo, val font: Font)

    private val lock = Any()
    private val registeredAssetIds = HashSet<String>()
    private val faces = LinkedHashMap<String, Face>()

    /** Bumped on every new font, so a provider snapshot changes identity and Compose re-reads it. */
    @Volatile
    var generation: Int = 0
        private set

    val registeredPostScriptNames: List<String> get() = synchronized(lock) { faces.keys.toList() }

    fun isAssetRegistered(id: String): Boolean = synchronized(lock) { id in registeredAssetIds }

    fun isRegistered(postScriptName: String): Boolean = synchronized(lock) { postScriptName in faces }

    /** Registers [data]; returns its PostScript name, or null when the bytes are not a usable font. Same asset id again is a no-op. */
    fun register(data: ByteArray, assetId: String, filename: String): String? {
        synchronized(lock) {
            if (!registeredAssetIds.add(assetId)) return null
        }
        val info = SfntInfo.parse(data)
        if (info == null) {
            synchronized(lock) { registeredAssetIds.remove(assetId) }
            log.warning { "FontRegistry: could not read a font from $filename (asset $assetId)." }
            return null
        }
        val ps = info.postScriptName
        // The same font can arrive under several asset ids: register it once.
        if (isRegistered(ps)) {
            log.debug { "FontRegistry: '$ps' already registered; skipping $filename." }
            return ps
        }
        val ext = filename.substringAfterLast('.', "ttf").lowercase().takeIf { it in setOf("ttf", "otf", "ttc") } ?: "ttf"
        val file = File(fontDir, "${sanitizedPathComponent(ps)}.$ext")
        if (!atomicWrite(data, file)) {
            synchronized(lock) { registeredAssetIds.remove(assetId) }
            log.warning { "FontRegistry: registration failed for $filename: could not write ${file.name}." }
            return null
        }
        val font = Font(file, FontWeight(info.weight), if (info.italic) FontStyle.Italic else FontStyle.Normal)
        synchronized(lock) {
            if (ps !in faces) {
                faces[ps] = Face(info, font)
                generation++
            }
        }
        log.info { "FontRegistry: registered '$ps' from $filename." }
        return ps
    }

    /** By PostScript name (one face), else by family name (every registered face of the family). */
    fun fontFamily(name: String): FontFamily? = synchronized(lock) {
        faces[name]?.let { return FontFamily(it.font) }
        val family = faces.values.filter { it.info.family == name }
        if (family.isEmpty()) null else FontFamily(family.map { it.font })
    }

    /** A provider over the current fonts, falling back to [fallback] for unknown names. */
    fun provider(fallback: App8FontProvider): App8FontProvider = Provider(this, fallback, generation)

    private data class Provider(val registry: FontRegistry, val fallback: App8FontProvider, val generation: Int) : App8FontProvider {
        override fun fontFamily(name: String, weight: FontWeight): FontFamily? =
            registry.fontFamily(name) ?: fallback.fontFamily(name, weight)
    }

    companion object {
        /** TrueType / OpenType / collections; WOFF and WOFF2 need a decoder and are skipped. */
        fun isFont(filename: String, mimeType: String?): Boolean {
            val mime = mimeType?.lowercase().orEmpty()
            val ext = filename.substringAfterLast('.', "").lowercase()
            if (ext == "woff" || ext == "woff2") return false
            if (mime == "font/woff" || mime == "font/woff2") return false
            if (mime.startsWith("font/")) return true
            if (mime == "application/font-sfnt") return true
            if (mime == "application/x-font-ttf" || mime == "application/x-font-otf") return true
            return ext in setOf("ttf", "otf", "ttc")
        }
    }
}
