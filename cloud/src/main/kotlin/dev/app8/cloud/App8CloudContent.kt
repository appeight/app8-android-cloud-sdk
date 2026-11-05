package dev.app8.cloud

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A rendered App8 screen, flow or app, or the fallback that replaced it: the Compose counterpart
 * of the `UIViewController` iOS returns. Draw it with [Content]; it can be drawn again (e.g. after
 * a configuration change) without reloading.
 */
public class App8CloudContent internal constructor(
    /** Non-null when this is a fallback: the error that triggered it. */
    public val error: App8CloudError?,
    private val body: @Composable (Modifier) -> Unit,
) {
    /** True when the host fallback replaced the App8 content. */
    public val isFallback: Boolean get() = error != null

    @Composable
    public fun Content(modifier: Modifier = Modifier) {
        body(modifier)
    }
}
