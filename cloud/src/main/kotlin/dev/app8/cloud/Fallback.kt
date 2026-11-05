package dev.app8.cloud

import androidx.compose.runtime.Composable

/**
 * Builds the native content shown when a screen render fails (iOS `ScreenFallback`). Called once,
 * eagerly, with the error; returns the composable to show.
 */
public typealias ScreenFallback = (App8CloudError) -> @Composable () -> Unit

/** Builds the native content shown when an app launch fails (iOS `AppFallback`). */
public typealias AppFallback = (App8CloudError) -> @Composable () -> Unit

/** Builds the native content shown when a flow render fails (iOS `FlowFallback`). */
public typealias FlowFallback = (App8CloudError) -> @Composable () -> Unit

/** Delivered to [App8CloudInstance.onFallbackInvoked] whenever a fallback is used. */
public data class FallbackEvent(
    val error: App8CloudError,
    /** `null` for [App8CloudInstance.startApp] fallbacks; the flow id for flow fallbacks. */
    val screenId: String?,
    val source: Source,
) {
    public enum class Source { SCREEN, APP, FLOW }
}
