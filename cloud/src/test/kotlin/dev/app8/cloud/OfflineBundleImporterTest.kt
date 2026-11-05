package dev.app8.cloud

import dev.app8.cloud.internal.ContentHash
import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.cache.AssetCache
import dev.app8.cloud.internal.cache.CacheLayout
import dev.app8.cloud.internal.cache.DiskCache
import dev.app8.cloud.internal.dslVersionIsSupported
import dev.app8.cloud.internal.fonts.FontRegistry
import dev.app8.cloud.internal.offline.OfflineBundleImporter
import dev.app8.cloud.internal.offline.PackageSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Base64
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Importing a `.a8pack` seeds the disk cache so the cache-first path renders offline. */
class OfflineBundleImporterTest {
    private val cacheRoot = tempDir("app8cloud-offline-cache")
    private val bundleRoot = tempDir("app8cloud-offline-bundle")

    private class Caches(val disk: DiskCache, val assets: AssetCache, val fonts: FontRegistry)

    private fun makeCaches(): Caches {
        val layout = CacheLayout(cacheRoot, "app-1")
        return Caches(
            DiskCache(layout, 100, Diagnostics.disabled),
            AssetCache(layout.assetBlobsDir, 50L * 1024 * 1024, layout.lruIndexFile, Diagnostics.disabled),
            FontRegistry(File(cacheRoot, "fonts"), Diagnostics.disabled),
        )
    }

    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    /** Writes a `.a8pack` directory; each asset's sha256 is filled in from its bytes. */
    private fun writeBundle(
        type: String,
        appId: String = "app-1",
        key: String,
        version: String?,
        dslVersion: String?,
        payload: String,
        assets: List<Pair<Map<String, String>, ByteArray>> = emptyList(),
    ): File {
        val dir = File(bundleRoot, "$key.a8pack").apply { mkdirs() }
        val descriptors = assets.map { (desc, bytes) ->
            val file = File(dir, desc.getValue("path"))
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
            JsonObject((desc + ("sha256" to ContentHash.sha256Hex(bytes))).mapValues { JsonPrimitive(it.value) })
        }
        val manifest = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive(type),
            "name" to JsonPrimitive(key),
            "bundleFormat" to JsonPrimitive(1),
            "appId" to JsonPrimitive(appId),
            "key" to JsonPrimitive(key),
            "content" to JsonPrimitive(Base64.getEncoder().encodeToString(json(payload).toString().encodeToByteArray())),
            "assets" to JsonArray(descriptors),
        )
        version?.let { manifest["version"] = JsonPrimitive(it) }
        dslVersion?.let { manifest["dslVersion"] = JsonPrimitive(it) }
        File(dir, "manifest.json").writeText(JsonObject(manifest).toString())
        return dir
    }

    private fun importing(dir: File, caches: Caches, maxDsl: String = "1.0") =
        OfflineBundleImporter.importBundle(PackageSource.Directory(dir), "app-1", maxDsl, caches.disk, caches.assets, caches.fonts, Diagnostics.disabled)

    @Test fun importScreenBundleSeedsDiskCache() {
        val caches = makeCaches()
        val logo = "PNGDATA".encodeToByteArray()
        val dir = writeBundle(
            OfflineBundleImporter.TYPE_SCREEN, key = "home", version = "v3", dslVersion = "1.0",
            payload = """
                {"app":{"id":"app-1","title":"Demo"},
                 "styles":[{"id":"s1"}],
                 "components":[{"id":"card"}],
                 "screens":{"home":{"version":"v3","data":{"id":"home","type":"view"}}},
                 "localizations":{"defaultLocale":"en","locales":{"en":{"hi":"Hi"}}},
                 "datasources":{"datamocks/products":{"items":[]}}}
            """.trimIndent(),
            assets = listOf(
                mapOf("id" to "a1", "name" to "logo", "filename" to "logo.png", "kind" to "image", "mimeType" to "image/png", "path" to "assets/logo.png") to logo,
            ),
        )
        val summary = importing(dir, caches)
        assertEquals("app8.offline.screen", summary.type)
        assertEquals(1, summary.screensImported)
        assertEquals(1, summary.assetsImported)

        val disk = caches.disk
        assertTrue(disk.hasUsableAppCache())
        assertNotNull(disk.readManifest())
        assertEquals(1, disk.readStyles().size)
        assertNotNull(disk.readComponent("card"))
        // Both the pinned version and `_latest`.
        assertNotNull(disk.readScreen("home", "v3"))
        assertNotNull(disk.readScreen("home", null))
        assertEquals("v3", disk.readScreenMeta("home")?.servedVersion)
        assertNotNull(disk.readLocalizations())
        assertNotNull(disk.readDatasource("datamocks", "products"))
        // Resolvable by id, filename, basename and name.
        assertContentEquals(logo, caches.assets.read("a1"))
        assertContentEquals(logo, caches.assets.read("logo.png"))
        assertContentEquals(logo, caches.assets.read("logo"))
    }

    @Test fun importFlowBundleSeedsFlowChannel() {
        val caches = makeCaches()
        val dir = writeBundle(
            OfflineBundleImporter.TYPE_FLOW, key = "onboarding", version = "v2", dslVersion = "1.0",
            payload = """
                {"flow":{"manifest":{"servedVersion":"v2","startScreen":"intro","screens":[{"screenKey":"intro"}]},
                         "styles":[{"id":"s1"}],
                         "components":[{"id":"card"}],
                         "screens":{"intro":{"id":"intro","type":"view"}}}}
            """.trimIndent(),
        )
        val summary = importing(dir, caches)
        assertEquals("app8.offline.flow", summary.type)
        assertEquals(1, summary.screensImported)
        val disk = caches.disk
        assertNotNull(disk.readFlowManifest("onboarding", "v2"))
        assertNotNull(disk.readFlowManifest("onboarding", null))
        assertEquals(1, disk.readFlowStyles("onboarding", "v2")?.size)
        assertEquals(1, disk.readFlowComponents("onboarding", null)?.size)
        assertNotNull(disk.readFlowScreen("onboarding", "v2", "intro"))
        assertNotNull(disk.readFlowScreen("onboarding", null, "intro"))
    }

    @Test fun checksumMismatchThrows() {
        val caches = makeCaches()
        val dir = writeBundle(
            OfflineBundleImporter.TYPE_SCREEN, key = "home", version = "v1", dslVersion = "1.0",
            payload = """{"screens":{"home":{"data":{"id":"home","type":"view"}}}}""",
            assets = listOf(mapOf("id" to "a1", "filename" to "logo.png", "kind" to "image", "path" to "assets/logo.png") to "ORIGINAL".encodeToByteArray()),
        )
        File(dir, "assets/logo.png").writeText("TAMPERED")
        assertFailsWith<App8CloudError.OfflineBundleInvalid> { importing(dir, caches) }
    }

    @Test fun appIdMismatchThrows() {
        val caches = makeCaches()
        val dir = writeBundle(
            OfflineBundleImporter.TYPE_SCREEN, appId = "other-app", key = "home", version = "v1", dslVersion = "1.0",
            payload = """{"screens":{"home":{"data":{"id":"home","type":"view"}}}}""",
        )
        assertFailsWith<App8CloudError.OfflineBundleInvalid> { importing(dir, caches) }
    }

    @Test fun dslVersionTooNewThrows() {
        val caches = makeCaches()
        val dir = writeBundle(
            OfflineBundleImporter.TYPE_SCREEN, key = "home", version = "v1", dslVersion = "2.0",
            payload = """{"screens":{"home":{"data":{"id":"home","type":"view"}}}}""",
        )
        assertFailsWith<App8CloudError.DslVersionUnsupported> { importing(dir, caches, maxDsl = "1.0") }
    }

    /** Android hardening: a traversing path is rejected before anything is written. */
    @Test fun traversingAssetPathThrows() {
        val caches = makeCaches()
        val dir = writeBundle(
            OfflineBundleImporter.TYPE_SCREEN, key = "home", version = "v1", dslVersion = "1.0",
            payload = """{"screens":{"home":{"data":{"id":"home","type":"view"}}}}""",
        )
        val manifest = File(dir, "manifest.json")
        manifest.writeText(manifest.readText().replace("\"assets\":[]", "\"assets\":[{\"id\":\"x\",\"path\":\"../evil.png\"}]"))
        assertFailsWith<App8CloudError.OfflineBundleInvalid> { importing(dir, caches) }
        assertFalse(caches.disk.hasUsableAppCache())
    }

    @Test fun dslVersionCompare() {
        assertTrue(dslVersionIsSupported("1.0", "1.0"))
        assertTrue(dslVersionIsSupported("1.0", "1.0.0"))
        assertTrue(dslVersionIsSupported("1.0", "1.2"))
        assertTrue(dslVersionIsSupported("1.2", "1.10"))
        assertFalse(dslVersionIsSupported("1.3", "1.2"))
        assertFalse(dslVersionIsSupported("2.0", "1.9"))
    }
}
