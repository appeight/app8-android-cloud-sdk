package dev.app8.cloud

import dev.app8.cloud.internal.cache.CacheLayout
import dev.app8.cloud.internal.instance.CloudInstance
import dev.app8.engine.events.App8AnalyticsEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * iOS `ScreenCatalogTests`: tri-state availability, short-circuiting unknown ids without a
 * round-trip, persistence, TTL, backends without `/screens`, seeding and skew correction.
 */
class ScreenCatalogTest {
    private val backend = MockBackend()
    private val instances = mutableListOf<CloudInstance>()

    @After fun tearDown() {
        instances.forEach { it.close() }
        backend.close()
    }

    private object Fixture {
        const val MANIFEST = """{ "configuration": { "id": "test-app", "title": "Test", "initialScreenId": "home" } }"""
        const val STYLES = """{"items":[]}"""
        const val COMPONENTS = """{"items":[]}"""
        const val LOCALIZATIONS = """{"defaultLocale":"en","locales":{}}"""
        const val HOME = """{ "servedVersion": "v1", "data": { "id": "home", "type": "view" } }"""
        const val LIST_PATH = "/sdk/v1/apps/test-app/screens"

        fun screensList(vararg screens: List<String?>): String = screens.joinToString(",", """{"screens":[""", "]}") { (key, version, minDsl, updatedAt) ->
            val updated = updatedAt?.let { ""","updatedAt":"$it"""" } ?: ""
            """{"screenKey":"$key","version":"$version","minDslVersion":"$minDsl"$updated}"""
        }

        /** Manifest, styles, components, localizations and `home`; [list] for `/screens` (404 when null). */
        fun standard(list: String?, home: String = HOME): (RecordedRequest) -> MockResponse = { req ->
            val path = req.path
            when {
                path == LIST_PATH -> list?.let(::ok) ?: notFound()
                path.endsWith("/manifest") -> ok(MANIFEST)
                path.endsWith("/styles") -> ok(STYLES)
                path.endsWith("/components") -> ok(COMPONENTS)
                path.endsWith("/localizations") -> ok(LOCALIZATIONS)
                path == "/sdk/v1/apps/test-app/screens/home" -> ok(home)
                else -> notFound()
            }
        }
    }

    private fun memoryCloud() = makeInstance(backend, telemetry = TelemetryPolicy.DISABLED).also { instances += it }

    private fun diskCloud(root: File = tempDir("app8cloud-catalog"), catalogTtl: Duration = 24.hours) =
        makeInstance(backend, diskRoot = root, catalogTtl = catalogTtl, telemetry = TelemetryPolicy.DISABLED).also { instances += it } to root

    private fun count(path: String) = backend.count(path)

    @Test fun emptyOnFirstUse_shortCircuitDoesNotFire() = runBlocking<Unit> {
        // No `/screens`: without a catalog `screen("nope")` must round-trip and surface ScreenNotFound.
        backend.handler = Fixture.standard(list = null)
        val cloud = memoryCloud()
        cloud.awaitCatalogReady(1.seconds)
        assertFailsWith<App8CloudError.ScreenNotFound> { cloud.screen("nope") }
        assertTrue(count("/sdk/v1/apps/test-app/screens/nope") >= 1, "must round-trip when no catalog is available")
    }

    @Test fun populatedFromPrefetchAll_shortCircuitsUnknownId() = runBlocking<Unit> {
        backend.handler = Fixture.standard(Fixture.screensList(listOf("home", "v1", "1.0", "2026-01-01T00:00:00Z")))
        val cloud = memoryCloud()
        cloud.prefetchAll(includingAssets = false)
        assertEquals(ScreenAvailability.KNOWN, cloud.availability("home"))
        assertEquals(ScreenAvailability.UNKNOWN, cloud.availability("nope"))
        val before = count("/sdk/v1/apps/test-app/screens/nope")
        assertFailsWith<App8CloudError.ScreenNotFound> { cloud.screen("nope") }
        assertEquals(before, count("/sdk/v1/apps/test-app/screens/nope"), "short-circuit must NOT hit the network for unknown ids")
    }

    @Test fun fallbackVariantShortCircuitsImmediately() = runBlocking<Unit> {
        backend.handler = Fixture.standard(Fixture.screensList(listOf("home", "v1", "1.0", null)))
        val cloud = memoryCloud()
        cloud.prefetchAll(includingAssets = false)
        val before = count("/sdk/v1/apps/test-app/screens/nope")
        var hit: App8CloudError? = null
        val content = cloud.screen("nope", null, emptyMap()) { error ->
            hit = error
            { }
        }
        assertTrue(content.isFallback)
        assertIs<App8CloudError.ScreenNotFound>(hit)
        assertEquals(before, count("/sdk/v1/apps/test-app/screens/nope"), "fallback variant must also short-circuit without network")
    }

    @Test fun persistsAcrossInstances() = runBlocking<Unit> {
        backend.handler = Fixture.standard(Fixture.screensList(listOf("home", "v1", "1.0", "2026-01-01T00:00:00Z")))
        val (cloudA, root) = diskCloud()
        cloudA.prefetchAll(includingAssets = false)
        assertEquals(ScreenAvailability.KNOWN, cloudA.availability("home"))
        // Instance B's background refresh fails; the disk catalog stays valid.
        backend.handler = { status(500) }
        val (cloudB, _) = diskCloud(root)
        // Disk hydrate is synchronous in init.
        assertEquals(ScreenAvailability.KNOWN, cloudB.availability("home"))
        assertEquals(ScreenAvailability.UNKNOWN, cloudB.availability("nope"), "a disk-loaded catalog must short-circuit unknown ids")
    }

    @Test fun staleCatalogDoesNotShortCircuit() = runBlocking<Unit> {
        // TTL 0 = always stale: availability answers, but screen(id) falls through to the network.
        backend.handler = Fixture.standard(Fixture.screensList(listOf("home", "v1", "1.0", null)))
        val (cloud, _) = diskCloud(catalogTtl = Duration.ZERO)
        cloud.prefetchAll(includingAssets = false)
        // Stale + unknown collapses to CATALOG_NOT_LOADED so hosts never hide CTAs on a stale negative.
        assertEquals(ScreenAvailability.CATALOG_NOT_LOADED, cloud.availability("nope"))
        val before = count("/sdk/v1/apps/test-app/screens/nope")
        assertFailsWith<App8CloudError.ScreenNotFound> { cloud.screen("nope") }
        assertTrue(count("/sdk/v1/apps/test-app/screens/nope") > before, "a stale catalog must fall through to the network")
    }

    @Test fun backend404OnScreens_doesNotShortCircuit() = runBlocking<Unit> {
        backend.handler = Fixture.standard(list = null)
        val cloud = memoryCloud()
        cloud.awaitCatalogReady(1.seconds)
        assertFailsWith<App8CloudError.ScreenNotFound> { cloud.screen("nope") }
        assertTrue(count("/sdk/v1/apps/test-app/screens/nope") >= 1)
        val catalog = assertNotNull(cloud.catalog, "catalog should be loaded even when the backend can't enumerate")
        assertFalse(catalog.backendSupportsEnumeration)
    }

    @Test fun opportunisticSeedingAfterRender() = runBlocking<Unit> {
        backend.handler = Fixture.standard(list = null)
        val cloud = memoryCloud()
        cloud.awaitCatalogReady(1.seconds)
        runCatching { cloud.screen("home") }
        assertEquals(ScreenAvailability.KNOWN, cloud.availability("home"), "seeded from a successful render")
        assertEquals(ScreenAvailability.CATALOG_NOT_LOADED, cloud.availability("nope"), "a seeded catalog can't speak to unseen ids")
    }

    @Test fun skewHandling_catalogStaleAfterServer404() = runBlocking<Unit> {
        // The catalog lists `home`, the screen endpoint 404s it (just unpublished).
        val list = Fixture.screensList(listOf("home", "v1", "1.0", null))
        backend.handler = { req ->
            if (req.path == "/sdk/v1/apps/test-app/screens/home") notFound() else Fixture.standard(list)(req)
        }
        val cloud = memoryCloud()
        cloud.awaitCatalogReady(1.seconds)
        assertEquals(ScreenAvailability.KNOWN, cloud.availability("home"))
        assertFailsWith<App8CloudError.ScreenNotFound> { cloud.screen("home") }
        assertEquals(ScreenAvailability.UNKNOWN, cloud.availability("home"), "the catalog must drop the stale entry after a server 404")
    }

    @Test fun versionPinBypassesShortCircuit() = runBlocking<Unit> {
        // The server, not the catalog, knows which versions of `home` exist.
        val list = Fixture.screensList(listOf("home", "v1", "1.0", null))
        backend.handler = { req ->
            if (req.path == "/sdk/v1/apps/test-app/screens/home") notFound() else Fixture.standard(list)(req)
        }
        val cloud = memoryCloud()
        cloud.awaitCatalogReady(1.seconds)
        assertFailsWith<App8CloudError.ScreenVersionNotFound> { cloud.screen("home", "v99") }
        assertTrue(count("/sdk/v1/apps/test-app/screens/home") >= 1, "must round-trip for a pinned version")
    }

    @Test fun dslVersionGate_unsupportedShortCircuitsToDslError() = runBlocking<Unit> {
        backend.handler = Fixture.standard(Fixture.screensList(listOf("home", "v1", "5.0", null)))
        val cloud = memoryCloud()
        cloud.awaitCatalogReady(1.seconds)
        val before = count("/sdk/v1/apps/test-app/screens/home")
        val e = assertFailsWith<App8CloudError.DslVersionUnsupported> { cloud.screen("home") }
        assertEquals("5.0", e.found)
        assertEquals("1.0", e.max)
        assertEquals(before, count("/sdk/v1/apps/test-app/screens/home"), "the DSL gate must NOT hit the network")
    }

    @Test fun concurrentScreenCalls_singleRefresh() = runBlocking<Unit> {
        backend.handler = { req -> delayed(Fixture.standard(Fixture.screensList(listOf("home", "v1", "1.0", null)))(req), 50) }
        val cloud = memoryCloud()
        (0 until 10).map { async { cloud.refreshCatalog() } }.awaitAll()
        assertTrue(count(Fixture.LIST_PATH) <= 2, "concurrent triggers collapse in the coalescer (one init + one batch at most)")
    }

    @Test fun awaitCatalogReady_returnsBeforeTimeout() = runBlocking<Unit> {
        backend.handler = Fixture.standard(Fixture.screensList(listOf("home", "v1", "1.0", null)))
        val snapshot = assertNotNull(memoryCloud().awaitCatalogReady(3.seconds))
        assertTrue(snapshot.backendSupportsEnumeration)
        assertEquals(listOf("home"), snapshot.screenIds)
    }

    @Test fun awaitCatalogReady_timesOut() = runBlocking<Unit> {
        backend.handler = { req -> if (req.path == Fixture.LIST_PATH) delayed(notFound(), 1_500) else notFound() }
        val cloud = memoryCloud()
        assertNull(cloud.awaitCatalogReady(300.milliseconds), "a timeout returns null")
        assertTrue(count(Fixture.LIST_PATH) >= 1)
    }

    @Test fun telemetry_emitsShortCircuitEvent() = runBlocking<Unit> {
        backend.handler = Fixture.standard(Fixture.screensList(listOf("home", "v1", "1.0", null)))
        val cloud = memoryCloud()
        val received = Recorder<App8AnalyticsEvent>()
        cloud.observeAnalytics { received.add(it) }
        cloud.awaitCatalogReady(1.seconds)
        runCatching { cloud.screen("nope") }
        val shortCircuit = assertNotNull(received.all.firstOrNull { it.name == App8AnalyticsEvent.Auto.SCREEN_SHORTCIRCUIT }, "must fire on the analytics bus")
        assertEquals("nope", shortCircuit.screenId)
        assertEquals("screen_not_found", shortCircuit.properties["reason"])
        assertEquals("network", shortCircuit.properties["catalog_source"])
        assertEquals(1, shortCircuit.properties["catalog_screen_count"])
        // render.failed still fires: the short-circuit doesn't suppress it.
        assertNotNull(received.all.firstOrNull { it.name == App8AnalyticsEvent.Auto.RENDER_FAILED })
    }

    @Test fun schemaVersionBump_wipesDiskCatalog() {
        val root = tempDir("app8cloud-catalog-schema")
        val layout = CacheLayout(root, "test-app")
        layout.rootForApp.mkdirs()
        layout.screensCatalogFile.writeText(
            """{"schemaVersion":"v0-future","sdkVersion":"x","appId":"test-app","fetchedAt":"2026-01-01T00:00:00Z","source":"disk","backendSupportsEnumeration":true,"screens":{}}""",
        )
        backend.handler = { status(500) }
        val (cloud, _) = diskCloud(root)
        val snap = cloud.catalog
        assertTrue(snap == null || snap.screenIds.isEmpty())
        assertFalse(layout.screensCatalogFile.exists(), "a schema-mismatched catalog file is removed")
    }

    @Test fun clearCacheAll_wipesCatalog() = runBlocking<Unit> {
        backend.handler = Fixture.standard(Fixture.screensList(listOf("home", "v1", "1.0", null)))
        val (cloud, root) = diskCloud()
        cloud.prefetchAll(includingAssets = false)
        assertEquals(ScreenAvailability.KNOWN, cloud.availability("home"))
        val layout = CacheLayout(root, "test-app")
        assertTrue(layout.screensCatalogFile.exists())
        // The post-clear refresh fails, so the catalog stays unloaded.
        backend.handler = { status(500) }
        cloud.clearCache(CacheScope.All)
        assertNull(cloud.catalog, "the in-memory catalog must be wiped by clearCache(All)")
        assertFalse(layout.screensCatalogFile.exists(), "the on-disk catalog file must be removed")
    }
}
