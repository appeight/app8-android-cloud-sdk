package dev.app8.cloud

import dev.app8.cloud.internal.SDKVersion
import dev.app8.cloud.internal.instance.CloudInstance
import dev.app8.engine.App8
import dev.app8.engine.App8Config
import dev.app8.engine.App8DataSource
import dev.app8.engine.core.EngineVersion
import dev.app8.engine.events.App8AnalyticsEvent
import dev.app8.engine.events.App8Event
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * iOS `EventAnalyticsPassthroughTests`: the cloud instance exposes the engine's buses, forwards
 * analytics config and locale, and fires its render-lifecycle events on the analytics bus, gated
 * by `autoCloudEvents` (not `autoScreenEvents`).
 */
@OptIn(App8CloudAdvancedApi::class)
class EventAnalyticsPassthroughTest {
    private val backend = MockBackend()
    private val instances = mutableListOf<CloudInstance>()

    @After fun tearDown() {
        instances.forEach { it.close() }
        backend.close()
    }

    private fun makeCloud() = makeInstance(backend).also { instances += it }

    private fun analyticsOf(cloud: App8CloudInstance): Recorder<App8AnalyticsEvent> =
        Recorder<App8AnalyticsEvent>().also { r -> cloud.observeAnalytics { r.add(it) } }

    // region Bus identity

    @Test fun eventBusIsSameInstanceAsEngine() {
        val cloud = makeCloud()
        assertSame(cloud.engine.eventBus, cloud.eventBus, "cloud must expose the engine's eventBus, not a separate one")
    }

    @Test fun analyticsBusIsSameInstanceAsEngine() {
        val cloud = makeCloud()
        assertSame(cloud.engine.analyticsBus, cloud.analyticsBus, "cloud must expose the engine's analyticsBus, not a separate one")
    }

    // endregion
    // region Version stamping

    @Test fun cloudInitTeachesBusItsVersion() {
        val cloud = makeCloud()
        assertEquals(SDKVersion.CURRENT, cloud.engine.analyticsBus.cloudVersion)
        assertEquals(SDKVersion.CURRENT, cloud.engine.eventBus.cloudVersion)
    }

    @Test fun cloudEventsCarryCloudVersionStamp() {
        val cloud = makeCloud()
        val received = analyticsOf(cloud)
        cloud.engine.analyticsBus.dispatch(App8AnalyticsEvent("synth.event", screenId = "home"))
        val event = assertNotNull(received.all.firstOrNull())
        assertEquals(SDKVersion.CURRENT, event.cloudVersion)
        assertEquals(EngineVersion.CURRENT, event.engineVersion)
        assertEquals(SDKVersion.CURRENT, event.properties["cloud_version"])
        assertEquals(EngineVersion.CURRENT, event.properties["engine_version"])
    }

    /** Constructing an engine fires no analytics: the window before the cloud stamps the bus stays empty. */
    @Test fun noAnalyticsEventsInInitWindow() {
        val engine = App8.instance(TestContext(), EmptyDataSource, App8Config(enforcePlatformFloor = false))
        val pre = Recorder<App8AnalyticsEvent>()
        val sub = engine.analyticsBus.observe { pre.add(it) }
        assertTrue(pre.all.isEmpty(), "engine construction must not fire analytics events")
        sub.cancel()
    }

    // endregion
    // region analyticsConfig and locale

    @Test fun analyticsConfigReadsFromEngine() {
        val cloud = makeCloud()
        cloud.engine.analyticsConfig = cloud.engine.analyticsConfig.copy(autoScreenEvents = false)
        assertFalse(cloud.analyticsConfig.autoScreenEvents)
    }

    @Test fun analyticsConfigWriteForwardsToEngine() {
        val cloud = makeCloud()
        cloud.analyticsConfig = cloud.analyticsConfig.copy(autoComponentTaps = false)
        assertFalse(cloud.engine.analyticsConfig.autoComponentTaps, "setting analyticsConfig on cloud must change the engine's")
    }

    @Test fun autoCloudEventsDefaultsTrue() {
        assertTrue(makeCloud().analyticsConfig.autoCloudEvents)
    }

    @Test fun setLocaleForwardsToEngine() {
        val cloud = makeCloud()
        cloud.setLocale("fr-FR")
        assertEquals("fr-FR", cloud.engine.currentLocale)
        assertEquals("fr-FR", cloud.currentLocale)
    }

    @Test fun setLocaleNullRevertsToDeviceDefault() {
        val cloud = makeCloud()
        cloud.setLocale("ja-JP")
        assertEquals("ja-JP", cloud.currentLocale)
        cloud.setLocale(null)
        assertNotEquals("ja-JP", cloud.currentLocale)
    }

    // endregion
    // region Subscriptions

    @Test fun subscribeReceivesEventsDispatchedToEngineBus() {
        val cloud = makeCloud()
        val received = Recorder<String>()
        val sub = cloud.subscribe { received.add(it.name) }
        cloud.engine.eventBus.dispatch(App8Event("host_subscribed", "home", null, null))
        assertEquals(listOf("host_subscribed"), received.all)
        sub.cancel()
    }

    @Test fun subscribeToNameFiltersByEventName() {
        val cloud = makeCloud()
        val received = Recorder<String>()
        val sub = cloud.subscribe("tracked") { received.add(it.name) }
        cloud.engine.eventBus.dispatch(App8Event("tracked", "home", null, null))
        cloud.engine.eventBus.dispatch(App8Event("ignored", "home", null, null))
        assertEquals(listOf("tracked"), received.all)
        sub.cancel()
    }

    @Test fun observeAnalyticsReceivesAnalyticsEvents() {
        val cloud = makeCloud()
        val received = analyticsOf(cloud)
        cloud.engine.analyticsBus.dispatch(App8AnalyticsEvent(App8AnalyticsEvent.Auto.SCREEN_APPEARED, screenId = "home"))
        assertEquals(listOf(App8AnalyticsEvent.Auto.SCREEN_APPEARED), received.all.map { it.name })
    }

    // endregion
    // region Delegate-style handlers (iOS sets the bus delegate; Android keeps one subscription)

    @Test fun setEventHandlerReceivesAndCanBeRemoved() {
        val cloud = makeCloud()
        val received = Recorder<String>()
        cloud.setEventHandler { received.add(it.name) }
        cloud.engine.eventBus.dispatch(App8Event("one", "home", null, null))
        cloud.setEventHandler(null)
        cloud.engine.eventBus.dispatch(App8Event("two", "home", null, null))
        assertEquals(listOf("one"), received.all)
    }

    @Test fun setAnalyticsHandlerReplacesThePreviousOne() {
        val cloud = makeCloud()
        val first = Recorder<String>()
        val second = Recorder<String>()
        cloud.setAnalyticsHandler { first.add(it.name) }
        cloud.setAnalyticsHandler { second.add(it.name) }
        cloud.engine.analyticsBus.dispatch(App8AnalyticsEvent("e"))
        assertTrue(first.all.isEmpty())
        assertEquals(listOf("e"), second.all)
        cloud.setAnalyticsHandler(null)
        cloud.engine.analyticsBus.dispatch(App8AnalyticsEvent("f"))
        assertEquals(listOf("e"), second.all)
    }

    // endregion
    // region app8.render.failed

    @Test fun renderFailedFiresOnAnalyticsBus() = runBlocking<Unit> {
        backend.handler = { status(500) }
        val cloud = makeCloud()
        val received = analyticsOf(cloud)
        runCatching { cloud.screen("home", "v1") }
        val failed = assertNotNull(received.all.firstOrNull { it.name == App8AnalyticsEvent.Auto.RENDER_FAILED }, "app8.render.failed should land on the bus")
        assertEquals("home", failed.screenId)
        assertEquals("screen", failed.properties["kind"])
        assertEquals("v1", failed.properties["requested_version"])
        assertEquals(500, failed.properties["status"])
        assertEquals(EngineVersion.CURRENT, failed.engineVersion)
        assertEquals(SDKVersion.CURRENT, failed.cloudVersion)
    }

    @Test fun renderFailedSuppressedWhenAutoCloudEventsDisabled() = runBlocking<Unit> {
        backend.handler = { status(500) }
        val cloud = makeCloud()
        cloud.analyticsConfig = cloud.analyticsConfig.copy(autoCloudEvents = false)
        val received = analyticsOf(cloud)
        runCatching { cloud.screen("home") }
        assertNull(received.all.firstOrNull { it.name == App8AnalyticsEvent.Auto.RENDER_FAILED })
    }

    /** `autoScreenEvents = false` only gates the engine's lifecycle events, never the cloud's. */
    @Test fun autoScreenEventsDoesNotSuppressCloudEvents() = runBlocking<Unit> {
        backend.handler = { status(500) }
        val cloud = makeCloud()
        cloud.analyticsConfig = cloud.analyticsConfig.copy(autoScreenEvents = false, autoCloudEvents = true)
        val received = analyticsOf(cloud)
        runCatching { cloud.screen("home") }
        assertNotNull(received.all.firstOrNull { it.name == App8AnalyticsEvent.Auto.RENDER_FAILED })
    }

    // endregion
    // region app8.render.fallback

    @Test fun renderFallbackFiresOnAnalyticsBus() = runBlocking<Unit> {
        backend.handler = { status(500) }
        val cloud = makeCloud()
        val received = analyticsOf(cloud)
        cloud.screen("home", null, emptyMap()) { { } }
        val fallback = assertNotNull(received.all.firstOrNull { it.name == App8AnalyticsEvent.Auto.RENDER_FALLBACK })
        assertEquals("home", fallback.screenId)
        assertEquals("screen", fallback.properties["kind"])
        assertEquals(500, fallback.properties["status"])
    }

    @Test fun renderFallbackSuppressedWhenAutoCloudEventsDisabled() = runBlocking<Unit> {
        backend.handler = { status(500) }
        val cloud = makeCloud()
        cloud.analyticsConfig = cloud.analyticsConfig.copy(autoCloudEvents = false)
        val received = analyticsOf(cloud)
        cloud.screen("home", null, emptyMap()) { { } }
        assertNull(received.all.firstOrNull { it.name == App8AnalyticsEvent.Auto.RENDER_FALLBACK })
    }

    // endregion
    // region app8.screen.rendered (the funnel's success arm)

    @Test fun screenRenderedDoesNotFireOnFailurePath() = runBlocking<Unit> {
        backend.handler = { status(500) }
        val cloud = makeCloud()
        val received = analyticsOf(cloud)
        runCatching { cloud.screen("home") }
        assertNull(received.all.firstOrNull { it.name == App8AnalyticsEvent.Auto.SCREEN_RENDERED }, "must not overcount the success arm")
    }

    @Test fun screenRenderedDoesNotFireOnFallbackPath() = runBlocking<Unit> {
        backend.handler = { status(500) }
        val cloud = makeCloud()
        val received = analyticsOf(cloud)
        cloud.screen("home", null, emptyMap()) { { } }
        assertNull(received.all.firstOrNull { it.name == App8AnalyticsEvent.Auto.SCREEN_RENDERED })
    }

    // endregion

    private object EmptyDataSource : App8DataSource {
        override suspend fun getApp(): ByteArray = ByteArray(0)
        override suspend fun getStyles(): List<ByteArray> = emptyList()
        override suspend fun getComponents(): List<ByteArray> = emptyList()
        override suspend fun getScreen(screenId: String): ByteArray = ByteArray(0)
        override suspend fun getComponent(componentId: String): ByteArray = ByteArray(0)
        override suspend fun getAsset(assetId: String?, assetName: String?): ByteArray? = null
    }
}
