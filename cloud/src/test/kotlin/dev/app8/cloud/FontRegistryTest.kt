package dev.app8.cloud

import androidx.compose.ui.text.font.FontWeight
import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.fonts.FontRegistry
import dev.app8.cloud.internal.fonts.SfntInfo
import dev.app8.engine.style.App8FontProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontRegistryTest {

    private fun registry() = FontRegistry(tempDir("app8cloud-fonts"), Diagnostics.disabled)

    // region isFont classification

    @Test fun isFontByMimeType() {
        assertTrue(FontRegistry.isFont("x", "font/ttf"))
        assertTrue(FontRegistry.isFont("x", "font/otf"))
        assertTrue(FontRegistry.isFont("x", "font/sfnt"))
        assertTrue(FontRegistry.isFont("x", "application/font-sfnt"))
        assertTrue(FontRegistry.isFont("x", "application/x-font-ttf"))
        assertTrue(FontRegistry.isFont("x", "application/x-font-otf"))
    }

    @Test fun isFontByExtensionWhenMimeMissing() {
        assertTrue(FontRegistry.isFont("Inter.ttf", null))
        assertTrue(FontRegistry.isFont("Inter.otf", null))
        assertTrue(FontRegistry.isFont("Inter.ttc", null))
        assertTrue(FontRegistry.isFont("Inter.TTF", null))
        // Common case: uploaded as octet-stream.
        assertTrue(FontRegistry.isFont("Inter.ttf", "application/octet-stream"))
    }

    @Test fun isNotFont() {
        assertFalse(FontRegistry.isFont("logo.png", "image/png"))
        assertFalse(FontRegistry.isFont("doc.pdf", "application/pdf"))
        assertFalse(FontRegistry.isFont("script.js", "application/javascript"))
        // WOFF/WOFF2 need a decoder.
        assertFalse(FontRegistry.isFont("Inter.woff", "font/woff"))
        assertFalse(FontRegistry.isFont("Inter.woff2", "font/woff2"))
    }

    // endregion
    // region register() with bad input

    @Test fun registerFailsGracefullyOnGarbageBytes() {
        val registry = registry()
        assertNull(registry.register(ByteArray(64) { 0xFF.toByte() }, "asset-1", "fake.ttf"))
        assertTrue(registry.registeredPostScriptNames.isEmpty())
        // A failed registration rolls back the dedupe entry so a retry with valid bytes can succeed.
        assertFalse(registry.isAssetRegistered("asset-1"))
    }

    @Test fun registerFailsGracefullyOnEmptyBytes() {
        val registry = registry()
        assertNull(registry.register(ByteArray(0), "asset-2", "empty.ttf"))
        assertFalse(registry.isAssetRegistered("asset-2"))
    }

    // endregion
    // region Android: real registration through the provider (iOS defers this to a device test)

    @Test fun registerReadsNamesAndServesTheProvider() {
        val registry = registry()
        val font = minimalFont(postScriptName = "TestSans-Bold", family = "Test Sans", weight = 700)
        assertEquals(SfntInfo("TestSans-Bold", "Test Sans", 700, false), SfntInfo.parse(font))

        val before = registry.provider(App8FontProvider.System)
        assertEquals("TestSans-Bold", registry.register(font, "f1", "TestSans-Bold.ttf"))
        assertTrue(registry.isAssetRegistered("f1"))
        assertEquals(listOf("TestSans-Bold"), registry.registeredPostScriptNames)

        val provider = registry.provider(App8FontProvider.System)
        assertNotEquals(before, provider, "a new font yields a new provider so Compose re-reads it")
        assertNotNull(provider.fontFamily("TestSans-Bold", FontWeight.Normal), "by PostScript name")
        assertNotNull(provider.fontFamily("Test Sans", FontWeight.Bold), "by family name")
        assertNotNull(provider.fontFamily("system", FontWeight.Normal), "falls back to the host provider")
        assertNull(provider.fontFamily("Unknown", FontWeight.Normal))

        // Same font under another asset id: deduped by PostScript name.
        assertEquals("TestSans-Bold", registry.register(font, "f2", "copy.ttf"))
        assertEquals(1, registry.registeredPostScriptNames.size)
    }

    // endregion

    /** A TrueType header with just `name` (ids 1 and 6) and `OS/2` (weight class). */
    private fun minimalFont(postScriptName: String, family: String, weight: Int): ByteArray {
        val strings = listOf(1 to family, 6 to postScriptName).map { (id, s) -> id to s.toByteArray(Charsets.UTF_16BE) }
        val name = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeShort(0)
                writeShort(strings.size)
                writeShort(6 + strings.size * 12)
                var offset = 0
                for ((id, data) in strings) {
                    writeShort(3); writeShort(1); writeShort(0x409); writeShort(id)
                    writeShort(data.size); writeShort(offset)
                    offset += data.size
                }
                strings.forEach { write(it.second) }
            }
        }.toByteArray()
        val os2 = ByteArray(96).also {
            it[4] = (weight shr 8).toByte()
            it[5] = (weight and 0xFF).toByte()
        }
        val tables = listOf("OS/2" to os2, "name" to name)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x00010000)
            writeShort(tables.size); writeShort(0); writeShort(0); writeShort(0)
            var offset = 12 + tables.size * 16
            for ((tag, data) in tables) {
                writeBytes(tag); writeInt(0); writeInt(offset); writeInt(data.size)
                offset += data.size
            }
            tables.forEach { write(it.second) }
        }
        return out.toByteArray()
    }
}
