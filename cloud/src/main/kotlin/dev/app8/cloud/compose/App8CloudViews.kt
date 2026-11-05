package dev.app8.cloud.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import dev.app8.cloud.App8CloudContent
import dev.app8.cloud.App8CloudError
import dev.app8.cloud.App8CloudInstance

/**
 * Renders App8 screen [screenId], showing [placeholder] while it loads and [fallback] if the
 * render fails (iOS `App8CloudScreenView`). Reloads when [instance], [screenId] or [version] change.
 */
@Composable
public fun App8CloudScreen(
    instance: App8CloudInstance,
    screenId: String,
    modifier: Modifier = Modifier,
    version: String? = null,
    parameters: Map<String, Any?> = emptyMap(),
    placeholder: @Composable () -> Unit = {},
    fallback: @Composable (App8CloudError) -> Unit,
) {
    val currentParameters by rememberUpdatedState(parameters)
    val currentFallback by rememberUpdatedState(fallback)
    val content by produceState<App8CloudContent?>(null, instance, screenId, version) {
        value = instance.screen(screenId, version, currentParameters) { error -> { currentFallback(error) } }
    }
    LoadedContent(content, modifier, placeholder)
}

/**
 * Launches the whole App8 app, showing [placeholder] while it loads and [fallback] if the launch
 * fails (iOS `App8CloudAppView`).
 */
@Composable
public fun App8CloudApp(
    instance: App8CloudInstance,
    modifier: Modifier = Modifier,
    version: String? = null,
    placeholder: @Composable () -> Unit = {},
    fallback: @Composable (App8CloudError) -> Unit,
) {
    val currentFallback by rememberUpdatedState(fallback)
    val content by produceState<App8CloudContent?>(null, instance, version) {
        value = instance.startApp(version) { error -> { currentFallback(error) } }
    }
    LoadedContent(content, modifier, placeholder)
}

@Composable
private fun LoadedContent(content: App8CloudContent?, modifier: Modifier, placeholder: @Composable () -> Unit) {
    if (content == null) {
        Box(modifier) { placeholder() }
    } else {
        content.Content(modifier.then(Modifier.fillMaxSize()))
    }
}
