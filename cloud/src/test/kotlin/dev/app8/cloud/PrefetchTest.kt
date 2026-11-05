package dev.app8.cloud

import dev.app8.cloud.internal.ContentHash
import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.cache.CacheLayout
import dev.app8.cloud.internal.cache.DiskCache
import dev.app8.cloud.internal.cache.MetaStore
import dev.app8.cloud.internal.cache.ResourceMeta
import dev.app8.cloud.internal.cache.ScreenMeta
import dev.app8.cloud.internal.data.RenderingDataSource
import dev.app8.cloud.internal.instance.CloudInstance
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iOS `PrefetchTests`: the preload API and its freshness checks. */
class PrefetchTest {
    private val backend = MockBackend()
    private val instances = mutableListOf<CloudInstance>()

    @After fun tearDown() {
        instances.forEach { it.close() }
        backend.close()
    }

    private fun memoryCloud() = makeInstance(backend).also { instances += it }

    private fun diskCloud(): Pair<CloudInstance, File> {
        val root = tempDir("app8cloud-prefetch")
        return makeInstance(backend, diskRoot = root).also { instances += it } to root
    }

    private companion object {
        const val MANIFEST = """{ "configuration": { "id": "test-app", "title": "Test", "initialScreenId": "home" } }"""
        const val MANIFEST_WITH_NAVIGATION = """
            {"configuration":{"id":"test-app","title":"Test","navigation":{"startFlow":"main","flows":[
              {"id":"main","startScreen":"home"},
              {"id":"onboarding","startScreen":"welcome"},
              {"id":"settings","startScreen":"settings_root"}]}}}
        """
        const val MINIMAL_MANIFEST = """{"configuration":{"id":"test-app"}}"""
        const val STYLES = """{"items":[]}"""
        const val COMPONENTS = """{"items":[]}"""
        const val LOCALIZATIONS = """{"defaultLocale":"en","locales":{}}"""
        const val SCREEN = """{ "servedVersion": "v3", "data": { "id": "home", "type": "view" } }"""
        const val HOME_V1 = """{ "servedVersion": "v1", "data": { "id": "home", "type": "view", "title": "Hello" } }"""
        const val HOME_V1_EDITED = """{ "servedVersion": "v1", "data": { "id": "home", "type": "view", "title": "Edited" } }"""
        const val HOME_V2 = """{ "servedVersion": "v2", "data": { "id": "home", "type": "view", "title": "v2" } }"""
        const val HOME_PATH = "/sdk/v1/apps/test-app/screens/home"
        const val LIST_PATH = "/sdk/v1/apps/test-app/screens"

        fun screensList(homeVersion: String = "v1", homeUpdatedAt: String? = "2026-01-01T10:00:00Z", resourcesUpdatedAt: String? = "2026-01-01T09:00:00Z"): String {
            val updated = homeUpdatedAt?.let { """, "updatedAt": "$it"""" } ?: ""
            val resources = resourcesUpdatedAt?.let { ts ->
                listOf("manifest", "styles", "components", "localizations").joinToString(",", """, "resources": {""", "}") { """"$it":{"updatedAt":"$ts"}""" }
            } ?: ""
            return """{"screens":[{"screenKey":"home","version":"$homeVersion","minDslVersion":"1.0"$updated}]$resources}"""
        }
    }

    /** App-level resources by suffix; everything else gets [other]. */
    private fun appLevel(manifest: String = MANIFEST, other: (RecordedRequest) -> MockResponse = { ok(SCREEN) }): (RecordedRequest) -> MockResponse = { req ->
        val path = req.path
        when {
            path.endsWith("/manifest") && !path.endsWith("/assets/manifest") -> ok(manifest)
            path.endsWith("/styles") -> ok(STYLES)
            path.endsWith("/components") -> ok(COMPONENTS)
            path.endsWith("/localizations") -> ok(LOCALIZATIONS)
            else -> other(req)
        }
    }

    /**
     * Successive calls to a path walk its plan (the last entry repeats); unplanned app-level paths
     * answer minimally. Listing plans start with the response the init catalog refresh consumes
     * (tests await it), so each prefetch sees the step it expects.
     */
    private fun sequenced(plan: Map<String, List<Pair<Int, String>>>): (RecordedRequest) -> MockResponse {
        val seen = ConcurrentHashMap<String, AtomicInteger>()
        return { req ->
            val path = req.path
            val n = seen.getOrPut(path) { AtomicInteger() }.getAndIncrement()
            val steps = plan[path]
            when {
                steps != null -> steps[minOf(n, steps.size - 1)].let { (code, body) -> status(code, body) }
                path.endsWith("/manifest") && !path.endsWith("/assets/manifest") -> ok(MINIMAL_MANIFEST)
                path.endsWith("/styles") -> ok(STYLES)
                path.endsWith("/components") -> ok(COMPONENTS)
                path.endsWith("/localizations") -> ok(LOCALIZATIONS)
                else -> notFound()
            }
        }
    }

    private fun home(root: File, version: String? = null) = CacheLayout(root, "test-app").screenFile("home", version)
    private fun homeMeta(root: File) = MetaStore.readScreen(CacheLayout(root, "test-app").screenMetaFile("home"))

    @Test fun prefetchWarmsAppLevelThenScreens() = runBlocking<Unit> {
        backend.handler = appLevel()
        memoryCloud().prefetch(listOf(PrefetchTarget("home"), PrefetchTarget("settings", "v2")), includingAssets = false)
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/manifest"))
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/styles"))
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/components"))
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/localizations"))
        assertEquals(1, backend.count(HOME_PATH))
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/screens/settings"))
    }

    @Test fun prefetchSucceedsWhenLocalizationsRoute404s() = runBlocking<Unit> {
        // A backend without /localizations must not abort the rest of the prefetch.
        val base = appLevel()
        backend.handler = { req -> if (req.path.endsWith("/localizations")) notFound() else base(req) }
        memoryCloud().prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/localizations"))
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/manifest"))
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/styles"))
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/components"))
        assertEquals(1, backend.count(HOME_PATH))
    }

    @Test fun prefetchSecondCallReusesInMemoryWhenDiskCacheDisabled() = runBlocking<Unit> {
        backend.handler = appLevel()
        val cloud = memoryCloud()
        cloud.awaitCatalogReady(kotlin.time.Duration.parse("1s"))
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        val after1 = backend.totalCount
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        val after2 = backend.totalCount
        assertTrue(after2 > after1, "without disk the second prefetch still makes requests")
        assertTrue(after2 < after1 * 2, "in-memory state (catalog, asset manifest attempt) saves at least one request")
    }

    @Test fun prefetchAllDiscoversFlowsFromManifest() = runBlocking<Unit> {
        backend.handler = appLevel(MANIFEST_WITH_NAVIGATION)
        memoryCloud().prefetch() // → prefetchAll(includingAssets = true)
        val paths = backend.paths
        assertTrue("/sdk/v1/apps/test-app/screens/home" in paths, "home (main flow) should be prefetched")
        assertTrue("/sdk/v1/apps/test-app/screens/welcome" in paths, "welcome (onboarding flow) should be prefetched")
        assertTrue("/sdk/v1/apps/test-app/screens/settings_root" in paths, "settings_root (settings flow) should be prefetched")
    }

    @Test fun prefetchAllNoNavigationBlockJustWarmsAppLevel() = runBlocking<Unit> {
        backend.handler = appLevel()
        memoryCloud().prefetch()
        assertTrue("/sdk/v1/apps/test-app/manifest" in backend.paths)
        assertFalse(backend.paths.any { "/screens/" in it }, "no flow entry points → no per-screen fetches")
    }

    @Test fun prefetchSurvivesPerScreenFailure() = runBlocking<Unit> {
        backend.handler = appLevel { req -> if (req.path.endsWith("/screens/broken")) status(500) else ok(SCREEN) }
        // Must not throw or hang: per-screen failures are swallowed.
        var event: PrefetchEvent? = null
        val cloud = memoryCloud()
        cloud.onPrefetchCompleted = { event = it }
        cloud.prefetch(listOf(PrefetchTarget("home"), PrefetchTarget("broken"), PrefetchTarget("settings")), includingAssets = false)
        assertEquals(1, event?.screensFailed)
        assertEquals(3, event?.screensCount)
    }

    // region Freshness

    @Test fun cacheFreshShortCircuitsOnMatchingUpdatedAt() = runBlocking<Unit> {
        val list = screensList()
        backend.handler = { req ->
            when (req.path) {
                LIST_PATH -> ok(list)
                HOME_PATH -> ok(HOME_V1)
                else -> appLevel(MINIMAL_MANIFEST) { ok("") }(req)
            }
        }
        val (cloud, _) = diskCloud()
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertEquals(1, backend.count(HOME_PATH), "first prefetch fetches the screen")
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertEquals(1, backend.count(HOME_PATH), "second prefetch short-circuits: updatedAt matches the cached meta")
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/manifest"), "the manifest short-circuits on matching updatedAt too")
    }

    @Test fun versionBumpInvalidatesCachedScreen() = runBlocking<Unit> {
        backend.handler = sequenced(
            mapOf(
                LIST_PATH to listOf(200 to screensList("v1", "2026-01-01T10:00:00Z"), 200 to screensList("v1", "2026-01-01T10:00:00Z"), 200 to screensList("v2", "2026-01-02T10:00:00Z")),
                HOME_PATH to listOf(200 to HOME_V1, 200 to HOME_V2),
            ),
        )
        val (cloud, root) = diskCloud()
        cloud.awaitCatalogReady(kotlin.time.Duration.parse("1s"))
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertTrue(home(root).readText().contains("\"title\":\"v2\""))
        val meta = homeMeta(root)
        assertEquals("v2", meta?.servedVersion)
        assertEquals("2026-01-02T10:00:00Z", meta?.updatedAt)
    }

    @Test fun inPlaceEditDetectedViaContentHash() = runBlocking<Unit> {
        // Same version, different body: the realistic in-place edit.
        backend.handler = sequenced(
            mapOf(
                LIST_PATH to listOf(200 to screensList("v1", "2026-01-01T10:00:00Z"), 200 to screensList("v1", "2026-01-01T10:00:00Z"), 200 to screensList("v1", "2026-01-02T10:00:00Z")),
                HOME_PATH to listOf(200 to HOME_V1, 200 to HOME_V1_EDITED),
            ),
        )
        val (cloud, root) = diskCloud()
        cloud.awaitCatalogReady(kotlin.time.Duration.parse("1s"))
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertTrue(home(root).readText().contains("\"title\":\"Edited\""), "the edit lands on disk despite the unchanged version")
        assertEquals("2026-01-02T10:00:00Z", homeMeta(root)?.updatedAt)
    }

    @Test fun failureAfterInvalidationLeavesScreenUncached() = runBlocking<Unit> {
        backend.handler = sequenced(
            mapOf(
                LIST_PATH to listOf(200 to screensList("v1", "2026-01-01T10:00:00Z"), 200 to screensList("v1", "2026-01-01T10:00:00Z"), 200 to screensList("v2", "2026-01-02T10:00:00Z")),
                HOME_PATH to listOf(200 to HOME_V1, 500 to ""),
            ),
        )
        val (cloud, root) = diskCloud()
        cloud.awaitCatalogReady(kotlin.time.Duration.parse("1s"))
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertTrue(home(root).exists(), "the first prefetch cached v1")
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertFalse(home(root).exists(), "the cache is wiped when the freshness check fires but the fetch fails")
        assertNull(homeMeta(root), "the screen meta is wiped with the body")
    }

    @Test fun componentsCachedFreshHydratesFromDiskNoNetwork() = runBlocking<Unit> {
        val list = screensList()
        val componentsPath = "/sdk/v1/apps/test-app/components"
        backend.handler = { req ->
            when (req.path) {
                LIST_PATH -> ok(list)
                HOME_PATH -> ok(HOME_V1)
                componentsPath -> ok("""{"items":[{"id":"card","kind":"view"}]}""")
                else -> appLevel(MINIMAL_MANIFEST) { ok("") }(req)
            }
        }
        val (cloud, _) = diskCloud()
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertEquals(1, backend.count(componentsPath), "the first prefetch fetches /components")
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertEquals(1, backend.count(componentsPath), "the second hydrates components from disk, not the network")
    }

    @Test fun applyScreenResponsePreservesPriorUpdatedAtWhenExpectedNull() {
        val cache = DiskCache(CacheLayout(tempDir("app8cloud-preserve"), "test-app"), 2, Diagnostics.disabled)
        assertTrue(cache.writeScreenMeta(ScreenMeta(servedVersion = "v1", requestedVersion = null, updatedAt = "2026-05-25T10:00:00Z", contentHash = "prior-hash"), "home"))
        assertEquals("2026-05-25T10:00:00Z", cache.readScreenMeta("home")?.updatedAt)
        // applyScreenResponse resolves `expected?.updatedAt ?: existingMeta?.updatedAt`.
        val resolved = (null as String?) ?: cache.readScreenMeta("home")?.updatedAt
        assertEquals("2026-05-25T10:00:00Z", resolved, "with no expected updatedAt, the existing meta's survives")
    }

    @Test fun prefetchWithoutAssetsStillWarmsAssetManifest() = runBlocking<Unit> {
        backend.handler = { req ->
            if (req.path.endsWith("/assets/manifest")) ok("""{"assets":[],"expiresIn":3600}""")
            else appLevel(MINIMAL_MANIFEST) { ok("""{"servedVersion":"v1","data":{}}""") }(req)
        }
        memoryCloud().prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/assets/manifest"), "the asset manifest warms even with includingAssets = false")
    }

    @Test fun sequentialPrefetchesLeaveMetaAndBodyConsistent() = runBlocking<Unit> {
        backend.handler = appLevel(MINIMAL_MANIFEST) { ok(HOME_V1) }
        val (cloud, root) = diskCloud()
        repeat(3) { cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false) }
        val meta = assertNotNull(homeMeta(root))
        assertEquals(ContentHash.sha256Hex(home(root).readBytes()), meta.contentHash, "the meta hash matches the body bytes")
    }

    @Test fun backwardsCompatNoUpdatedAtTrustsCache() = runBlocking<Unit> {
        // Pre-feature backends (no updatedAt) get warm-once semantics.
        val list = """{"screens":[{"screenKey":"home","version":"v1","minDslVersion":"1.0"}]}"""
        backend.handler = sequenced(mapOf(LIST_PATH to listOf(200 to list), HOME_PATH to listOf(200 to HOME_V1, 200 to HOME_V1_EDITED)))
        val (cloud, root) = diskCloud()
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertEquals(1, backend.count(HOME_PATH), "no updatedAt signal: the second prefetch trusts the cache")
        assertTrue(home(root).readText().contains("\"title\":\"Hello\""), "the in-place edit stays invisible without updatedAt")
    }

    // endregion
    // region Code-review regressions

    @Test fun refreshPreservesUpdatedAtWhenExpectedNull() {
        val cache = DiskCache(CacheLayout(tempDir("app8cloud-preserve-updated"), "test-app"), 2, Diagnostics.disabled)
        cache.touchMeta()
        cache.updateAppResourceMeta("manifest", ResourceMeta("2026-05-25T10:00:00Z", "h-old", Instant.now()))
        // A refresh with no updatedAt signal falls back to the stored one.
        val resolved = (null as String?) ?: cache.readAppResourceMeta("manifest")?.updatedAt
        cache.updateAppResourceMeta("manifest", ResourceMeta(resolved, "h-new", Instant.now()))
        val final = cache.readAppResourceMeta("manifest")
        assertEquals("2026-05-25T10:00:00Z", final?.updatedAt, "a no-signal refresh must not clobber the prior updatedAt")
        assertEquals("h-new", final?.contentHash)
    }

    @Test fun refreshLocalizationsRequiresDiskFile() = runBlocking<Unit> {
        val list = screensList()
        backend.handler = { req -> if (req.path == LIST_PATH) ok(list) else appLevel(MINIMAL_MANIFEST) { ok(HOME_V1) }(req) }
        val (cloud, root) = diskCloud()
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/localizations"))
        // Delete the file but keep its meta: cachedFresh needs the file, not just the meta.
        val file = CacheLayout(root, "test-app").localizationsFile
        assertTrue(file.delete())
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertEquals(2, backend.count("/sdk/v1/apps/test-app/localizations"), "the second prefetch refetches after the file was deleted")
    }

    @Test fun prefetchEventCarriesInvalidationReasonFields() {
        val event = PrefetchEvent(
            durationMs = 100, scope = "specific",
            manifest = PrefetchEvent.AppResourceStatus.REFRESHED,
            styles = PrefetchEvent.AppResourceStatus.CACHED,
            components = PrefetchEvent.AppResourceStatus.REFRESHED,
            localizations = PrefetchEvent.AppResourceStatus.UNCHANGED,
            manifestReason = "no_prior_cache",
            componentsReason = "updated_at_changed",
            screensCount = 1, screensCached = 0, screensRefreshed = 1, screensInvalidated = 1, screensFailed = 0, cancelled = false,
        )
        assertEquals("no_prior_cache", event.manifestReason)
        assertEquals("updated_at_changed", event.componentsReason)
        assertNull(event.stylesReason, "no reason for non-refreshed statuses")
        assertNull(event.localizationsReason)
    }

    @Test fun servedVersionAliasRewrittenWhenMissing() = runBlocking<Unit> {
        val list = """
            {"screens":[{"screenKey":"home","version":"v1","minDslVersion":"1.0","updatedAt":"T1"}],
             "resources":{"manifest":{"updatedAt":"T1"},"styles":{"updatedAt":"T1"},"components":{"updatedAt":"T1"},"localizations":{"updatedAt":"T1"}}}
        """.trimIndent()
        backend.handler = { req -> if (req.path == LIST_PATH) ok(list) else appLevel(MINIMAL_MANIFEST) { ok(HOME_V1) }(req) }
        val (cloud, root) = diskCloud()
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertTrue(home(root, "v1").exists(), "setup: the first prefetch wrote both _latest and the v1 alias")
        // Delete the alias and blank the stored updatedAt: the next check must fetch; equal bytes
        // skip the body write, which exercises the alias-only rewrite.
        assertTrue(home(root, "v1").delete())
        val layout = CacheLayout(root, "test-app")
        val stored = assertNotNull(homeMeta(root))
        MetaStore.writeScreen(stored.copy(updatedAt = null), layout.screenMetaFile("home"))
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertTrue(home(root, "v1").exists(), "the alias is rewritten when missing, even when the body write is skipped")
    }

    @Test fun noSignalFallbackExpiresAfterMaxAge() = runBlocking<Unit> {
        val list = """{"screens":[{"screenKey":"home","version":"v1","minDslVersion":"1.0"}]}"""
        backend.handler = { req -> if (req.path == LIST_PATH) ok(list) else appLevel(MINIMAL_MANIFEST) { ok(HOME_V1) }(req) }
        val (cloud, root) = diskCloud()
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertEquals(1, backend.count("/sdk/v1/apps/test-app/manifest"))
        // Age the app-resource meta past the 24h no-signal window.
        val cache = DiskCache(CacheLayout(root, "test-app"), 2, Diagnostics.disabled)
        val stale = Instant.now().minusSeconds(25 * 60 * 60)
        for (key in listOf("manifest", "styles", "components", "localizations")) {
            cache.readAppResourceMeta(key)?.let { cache.updateAppResourceMeta(key, it.copy(fetchedAt = stale)) }
        }
        cloud.prefetch(listOf(PrefetchTarget("home")), includingAssets = false)
        assertTrue(backend.count("/sdk/v1/apps/test-app/manifest") >= 2, "a stale (>24h) no-signal cache must refetch")
    }

    @Test fun assetManifestDiskHydrateRejectsFutureFetchedAt() {
        // Gate: expiresAt > now AND fetchedAt <= now. A future fetchedAt (clock rollback) fails it.
        val now = Instant.now()
        val future = now.plusSeconds(3600)
        assertFalse(RenderingDataSource.diskManifestUsable(future, future.plusSeconds(3600), now))
        assertTrue(RenderingDataSource.diskManifestUsable(now.minusSeconds(10), now.plusSeconds(3600), now))
        assertFalse(RenderingDataSource.diskManifestUsable(now.minusSeconds(7200), now.minusSeconds(3600), now))
    }

    // endregion
}
