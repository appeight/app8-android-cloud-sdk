package dev.app8.cloud

import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.cache.CacheLayout
import dev.app8.cloud.internal.cache.DiskCache
import dev.app8.cloud.internal.cache.MetaStore
import dev.app8.cloud.internal.cache.ResourceMeta
import dev.app8.cloud.internal.cache.ScreenMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiskCacheTest {
    private val root = tempDir("app8cloud-disk")

    private fun makeCache(versionsToKeep: Int = 100) = DiskCache(CacheLayout(root, "test-app"), versionsToKeep, Diagnostics.disabled)

    private fun b(s: String) = s.encodeToByteArray()

    @Test fun writeAndReadAppLevel() {
        val cache = makeCache()
        // writeStyles joins blobs into a JSON array, so fixtures must be JSON.
        assertTrue(cache.writeManifest(b("MANIFEST")))
        assertTrue(cache.writeStyles(listOf(b("""{"id":"a"}"""), b("""{"id":"b"}"""))))
        assertTrue(cache.writeComponent("card", b("CARD")))
        assertTrue(cache.writeDatasource("datamocks", "products", b("DS")))
        cache.touchMeta()

        assertTrue(cache.hasUsableAppCache())
        assertContentEquals(b("MANIFEST"), cache.readManifest())
        assertEquals(2, cache.readStyles().size)
        assertContentEquals(b("CARD"), cache.readComponent("card"))
        assertContentEquals(b("DS"), cache.readDatasource("datamocks", "products"))
    }

    @Test fun writeAndReadScreenWithVersion() {
        val cache = makeCache()
        assertTrue(cache.writeScreen("home", null, b("LATEST")))
        assertTrue(cache.writeScreen("home", "v3", b("V3")))
        assertTrue(cache.writeScreen("home", "v4", b("V4")))
        assertContentEquals(b("LATEST"), cache.readScreen("home", null))
        assertContentEquals(b("V3"), cache.readScreen("home", "v3"))
        assertContentEquals(b("V4"), cache.readScreen("home", "v4"))
        assertNull(cache.readScreen("home", "v99"), "missing pinned version returns null")
    }

    @Test fun enumerateScreenIds() {
        val cache = makeCache()
        cache.writeScreen("home", null, b("X"))
        cache.writeScreen("settings", "v2", b("Y"))
        cache.writeScreen("profile", null, b("Z"))
        assertEquals(setOf("home", "settings", "profile"), cache.enumerateScreenIds().toSet())
    }

    @Test fun clearScreen() {
        val cache = makeCache()
        cache.writeScreen("home", null, b("X"))
        cache.writeScreen("home", "v3", b("Y"))
        cache.writeScreen("settings", null, b("Z"))
        cache.clearScreen("home")
        assertNull(cache.readScreen("home", null))
        assertNull(cache.readScreen("home", "v3"))
        assertContentEquals(b("Z"), cache.readScreen("settings", null))
    }

    @Test fun clearAll() {
        val cache = makeCache()
        cache.writeManifest(b("M"))
        cache.writeScreen("home", null, b("S"))
        cache.touchMeta()
        cache.clearAll()
        assertNull(cache.readManifest())
        assertNull(cache.readScreen("home", null))
        assertFalse(cache.hasUsableAppCache())
    }

    @Test fun versionPruningKeepsRecentAndLatest() {
        val cache = makeCache(versionsToKeep = 2)
        cache.writeScreen("home", null, b("LATEST"))
        for (v in listOf("v1", "v2", "v3", "v4")) {
            cache.writeScreen("home", v, b(v))
            // Distinct modification times so pruning order is deterministic.
            Thread.sleep(20)
        }
        assertContentEquals(b("LATEST"), cache.readScreen("home", null), "_latest always survives pruning")
        assertContentEquals(b("v4"), cache.readScreen("home", "v4"))
        assertContentEquals(b("v3"), cache.readScreen("home", "v3"))
        assertNull(cache.readScreen("home", "v2"), "v2 should be pruned")
        assertNull(cache.readScreen("home", "v1"), "v1 should be pruned")
    }

    @Test fun screenMetaRoundTrip() {
        val cache = makeCache()
        assertTrue(cache.writeScreenMeta(ScreenMeta(servedVersion = "v3", requestedVersion = null, updatedAt = "2026-05-24T10:00:00Z", contentHash = "abc123"), "home"))
        val read = cache.readScreenMeta("home")
        assertEquals("v3", read?.servedVersion)
        assertEquals("2026-05-24T10:00:00Z", read?.updatedAt)
        assertEquals("abc123", read?.contentHash)
    }

    @Test fun appResourceMetaPreservedAcrossTouch() {
        val cache = makeCache()
        cache.touchMeta()
        cache.updateAppResourceMeta("manifest", ResourceMeta("2026-05-24T09:00:00Z", "h1", Instant.now()))
        // touchMeta must NOT clobber appResources.
        cache.touchMeta()
        assertEquals("h1", cache.readAppResourceMeta("manifest")?.contentHash)
    }

    @Test fun screenMetaSurvivesVersionPruning() {
        val cache = makeCache(versionsToKeep = 1)
        cache.writeScreenMeta(ScreenMeta(servedVersion = "v1", requestedVersion = null, updatedAt = "2026-05-24T10:00:00Z", contentHash = "h"), "home")
        for (v in listOf("v1", "v2", "v3")) {
            cache.writeScreen("home", v, b(v))
            Thread.sleep(20)
        }
        assertNotNull(cache.readScreenMeta("home"), "_meta.json must survive pruning")
    }

    @Test fun schemaMismatchInvalidatesCache() {
        val cache = makeCache()
        cache.writeManifest(b("X"))
        cache.touchMeta()
        cache.layout.metaFile.writeText("""{"schemaVersion":"v999","sdkVersion":"0.0.0","fetchedAt":"2026-01-01T00:00:00Z"}""")
        assertFalse(cache.hasUsableAppCache())
    }

    @Test fun initWipesStaleSchema() {
        val layout = CacheLayout(root, "test-app")
        layout.rootForApp.mkdirs()
        layout.metaFile.writeText("""{"schemaVersion":"v999","sdkVersion":"0.0.0","fetchedAt":"2026-01-01T00:00:00Z"}""")
        layout.manifestFile.writeText("legacy")
        DiskCache(layout, 2, Diagnostics.disabled)
        assertFalse(layout.manifestFile.exists(), "manifest from a stale-schema cache must be wiped at init")
        assertNull(MetaStore.readApp(layout.metaFile), "meta.json from a stale-schema cache must be wiped at init")
    }

    @Test fun initPreservesMatchingSchema() {
        val layout = CacheLayout(root, "test-app")
        val first = DiskCache(layout, 2, Diagnostics.disabled)
        first.writeManifest(b("X"))
        first.touchMeta()
        val second = DiskCache(layout, 2, Diagnostics.disabled)
        assertContentEquals(b("X"), second.readManifest(), "a matching-schema cache must survive a fresh init")
    }

    @Test fun concurrentAppResourceMetaUpdatesDontDropEntries() = runBlocking<Unit> {
        val cache = makeCache()
        cache.touchMeta()
        // Without serialization, last-writer-wins would drop earlier keys.
        (0 until 50).map { i ->
            launch(Dispatchers.IO) { cache.updateAppResourceMeta("k$i", ResourceMeta("T$i", "h$i", Instant.now())) }
        }.forEach { it.join() }
        for (i in 0 until 50) assertNotNull(cache.readAppResourceMeta("k$i"), "k$i lost — concurrent updates must serialize")
    }
}
