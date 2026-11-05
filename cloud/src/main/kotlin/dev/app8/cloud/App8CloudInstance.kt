package dev.app8.cloud

import dev.app8.engine.App8Instance
import dev.app8.engine.events.App8AnalyticsBus
import dev.app8.engine.events.App8AnalyticsConfig
import dev.app8.engine.events.App8AnalyticsEvent
import dev.app8.engine.events.App8Event
import dev.app8.engine.events.App8EventBus
import dev.app8.engine.events.App8Subscription
import dev.app8.engine.style.App8FontProvider
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import kotlin.time.Duration

/**
 * The App8Cloud interface (iOS `App8Cloud.Instance`): renders screens, flows and apps from the
 * backend, manages identity attributes, prefetching, the cache, offline bundles and telemetry.
 * Create one with [App8Cloud.instance] and keep it for the app's lifetime.
 *
 * Suspend functions are main-safe; network and disk work runs on background dispatchers.
 * Callbacks ([onScreenRendered], [onFallbackInvoked], [onPrefetchCompleted]) run on the thread
 * that resumed the call.
 */
public interface App8CloudInstance {

    // region Identity (analytics passthrough only)

    /**
     * Merges [attributes] into the identity sent as `X-App8-Identity` (analytics only; it does not
     * select variants). Reserved keys (`$` prefix) are dropped.
     */
    public fun setAttributes(attributes: Map<String, String>)

    public fun clearAttributes()

    public val currentAttributes: Map<String, String>

    // endregion
    // region Locale

    /** Overrides the locale for `{"$i18n": …}` text; `null` reverts to the device. Applies to later renders. */
    public fun setLocale(locale: String?)

    /** Override → device → the bundle's `defaultLocale`. */
    public val currentLocale: String

    // endregion
    // region Render

    /**
     * Loads screen [id] ([version] `null` = latest published) and returns it ready to draw.
     * [parameters] seed DSL variables.
     * @throws App8CloudError
     */
    public suspend fun screen(id: String, version: String? = null, parameters: Map<String, Any?> = emptyMap()): App8CloudContent

    /** Never throws: on failure returns [fallback]'s content (and reports [onFallbackInvoked]). */
    public suspend fun screen(id: String, version: String?, parameters: Map<String, Any?>, fallback: ScreenFallback): App8CloudContent

    /**
     * Loads published flow [id] (a gated multi-screen bundle, by `flow_key`). The start screen
     * paints first; member screens load as the user navigates. A flow's screens are delivered
     * only through the flow channel, never through [screen].
     * @throws App8CloudError
     */
    public suspend fun flow(id: String, version: String? = null): App8CloudContent

    /** Never throws: on failure returns [fallback]'s content. */
    public suspend fun flow(id: String, version: String?, fallback: FlowFallback): App8CloudContent

    /**
     * Loads the whole app from `navigation.startFlow`. [version] is reserved for app-level
     * version pinning and is currently ignored.
     * @throws App8CloudError
     */
    public suspend fun startApp(version: String? = null): App8CloudContent

    /** Never throws: on failure returns [fallback]'s content. */
    public suspend fun startApp(version: String?, fallback: AppFallback): App8CloudContent

    /** Releases the active flow and flushes telemetry. */
    public fun stopApp()

    // endregion
    // region Telemetry callbacks

    public var onScreenRendered: ((RenderEvent) -> Unit)?

    public var onFallbackInvoked: ((FallbackEvent) -> Unit)?

    /** Fires after every prefetch with a per-resource freshness summary. */
    public var onPrefetchCompleted: ((PrefetchEvent) -> Unit)?

    /** Fire-and-forget custom event, buffered and POSTed in batches. No-op with [TelemetryPolicy.DISABLED]. */
    public fun track(name: String, context: Map<String, Any?> = emptyMap())

    // endregion
    // region Preload

    /** Warms [screens] (and app-level state). `screens` empty → app-level state only. [includingAssets] also warms images and fonts. */
    public suspend fun prefetch(screens: List<PrefetchTarget>, includingAssets: Boolean = true)

    /** Discovers every published + reachable screen and warms it. */
    public suspend fun prefetchAll(includingAssets: Boolean = true)

    public fun cancelPrefetch()

    // endregion
    // region Cache

    public suspend fun clearCache(scope: CacheScope)

    // endregion
    // region Offline bundles

    /**
     * Imports an offline `.a8pack` bundle (a directory) into the disk cache, so a published flow
     * or screen renders with no network; online, the normal freshness refresh layers on top.
     * Works as a cache warm-up whatever the [NetworkPolicy]. See `docs/OFFLINE_BUNDLE_FORMAT.md`.
     * @throws App8CloudError.OfflineBundleInvalid on a malformed bundle, app-id mismatch,
     *   unsupported `bundleFormat` or failed checksum; [App8CloudError.DslVersionUnsupported]
     *   when the bundle needs a newer DSL.
     */
    public suspend fun importOfflineBundle(directory: File): OfflineImportSummary

    /**
     * Imports every `.a8pack` in the default locations: `assets/App8OfflinePackages/` in the APK
     * (ship-with-app) and [App8Cloud.defaultOfflinePackageDirectory] (downloaded at runtime). A
     * package that fails is logged and skipped.
     */
    public suspend fun importBundledPackages(): List<OfflineImportSummary>

    // endregion
    // region Screen availability

    /** Synchronous: the last known catalog state. Gate host UI on it without a round-trip. */
    public fun availability(screenId: String): ScreenAvailability

    /** The current catalog snapshot, or `null` before one is loaded. */
    public val catalog: ScreenCatalog?

    /** Suspends until the catalog is loaded; `null` on [timeout]. */
    public suspend fun awaitCatalogReady(timeout: Duration): ScreenCatalog?

    /** Refreshes the catalog now (e.g. from a push that implies new content). */
    public suspend fun refreshCatalog()

    // endregion
    // region Engine pass-through

    @App8CloudAdvancedApi
    public val engine: App8Instance

    /** The engine's event bus: one bus per cloud instance (flow events are forwarded to it). */
    public val eventBus: App8EventBus

    /**
     * The engine's analytics bus. The cloud SDK adds `app8.screen.rendered`,
     * `app8.render.failed`, `app8.render.fallback` and `app8.screen.shortcircuit` to it.
     */
    public val analyticsBus: App8AnalyticsBus

    public var analyticsConfig: App8AnalyticsConfig

    /**
     * Fonts the host ships. Fonts the SDK downloads take precedence; unknown names fall back to
     * this provider (default [App8FontProvider.System]).
     */
    public var fontProvider: App8FontProvider

    /** Registers one delegate-style event handler, replacing the previous one; `null` removes it. */
    public fun setEventHandler(handler: ((App8Event) -> Unit)?)

    /** Registers one delegate-style analytics handler, replacing the previous one; `null` removes it. */
    public fun setAnalyticsHandler(handler: ((App8AnalyticsEvent) -> Unit)?)

    // endregion
}

// region Conveniences (iOS protocol extensions)

/** Latest published flow, never throws. */
public suspend fun App8CloudInstance.flow(id: String, fallback: FlowFallback): App8CloudContent = flow(id, null, fallback)

/** Discover and warm every screen with assets (iOS `prefetch()`). */
public suspend fun App8CloudInstance.prefetch(): Unit = prefetchAll(includingAssets = true)

/** Every `emit` event; hold the subscription, [App8Subscription.cancel] to stop. */
public fun App8CloudInstance.subscribe(handler: (App8Event) -> Unit): App8Subscription = eventBus.subscribe(handler = handler)

/** `emit` events named [eventName]. */
public fun App8CloudInstance.subscribe(eventName: String, handler: (App8Event) -> Unit): App8Subscription =
    eventBus.subscribe(name = eventName, handler = handler)

/** `emit` events from screen [screenId]. */
public fun App8CloudInstance.subscribeOnScreen(screenId: String, handler: (App8Event) -> Unit): App8Subscription =
    eventBus.subscribe(screenId = screenId, handler = handler)

/** Hot flow of `emit` events (iOS `events` / `eventStream`). */
public val App8CloudInstance.events: SharedFlow<App8Event> get() = eventBus.events

/** Every analytics event, auto and author-declared. */
public fun App8CloudInstance.observeAnalytics(handler: (App8AnalyticsEvent) -> Unit): App8Subscription =
    analyticsBus.observe(handler)

/** Hot flow of analytics events (iOS `analytics` / `analyticsStream`). */
public val App8CloudInstance.analytics: SharedFlow<App8AnalyticsEvent> get() = analyticsBus.events

// endregion
