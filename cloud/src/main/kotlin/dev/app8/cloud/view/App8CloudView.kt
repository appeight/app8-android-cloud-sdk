package dev.app8.cloud.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import dev.app8.cloud.App8CloudError
import dev.app8.cloud.App8CloudInstance
import dev.app8.cloud.compose.App8CloudApp
import dev.app8.cloud.compose.App8CloudScreen

/**
 * View-system host for apps without Compose (the UIKit-embedding counterpart of the iOS
 * `screen(...)` view controllers). Set [instance], then [setScreen] or [showApp]; on failure the
 * view from [fallbackFactory] is shown.
 */
public class App8CloudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private sealed interface Target {
        data class Screen(val id: String, val version: String?, val parameters: Map<String, Any?>) : Target
        data class App(val version: String?) : Target
    }

    private var instanceState by mutableStateOf<App8CloudInstance?>(null)
    private var targetState by mutableStateOf<Target?>(null)
    private var placeholderState by mutableStateOf<((Context) -> View)?>(null)
    private var fallbackState by mutableStateOf<(Context, App8CloudError) -> View>({ ctx, _ -> View(ctx) })

    public var instance: App8CloudInstance?
        get() = instanceState
        set(value) {
            instanceState = value
        }

    /** Builds the native view shown when loading fails. Defaults to an empty view. */
    public var fallbackFactory: (Context, App8CloudError) -> View
        get() = fallbackState
        set(value) {
            fallbackState = value
        }

    /** Builds the view shown while loading; `null` shows nothing. */
    public var placeholderFactory: ((Context) -> View)?
        get() = placeholderState
        set(value) {
            placeholderState = value
        }

    /** Shows screen [screenId] ([version] `null` = latest). */
    @JvmOverloads
    public fun setScreen(screenId: String, version: String? = null, parameters: Map<String, Any?> = emptyMap()) {
        targetState = Target.Screen(screenId, version, parameters)
    }

    /** Shows the whole app from `navigation.startFlow`. */
    @JvmOverloads
    public fun showApp(version: String? = null) {
        targetState = Target.App(version)
    }

    private val composeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val instance = instanceState ?: return@setContent
            val placeholder = placeholderState
            val fallback = fallbackState
            val placeholderContent: @androidx.compose.runtime.Composable () -> Unit = {
                if (placeholder != null) AndroidView(factory = placeholder)
            }
            when (val target = targetState) {
                is Target.Screen -> App8CloudScreen(
                    instance, target.id, version = target.version, parameters = target.parameters,
                    placeholder = placeholderContent,
                    fallback = { error -> AndroidView(factory = { ctx -> fallback(ctx, error) }) },
                )
                is Target.App -> App8CloudApp(
                    instance, version = target.version,
                    placeholder = placeholderContent,
                    fallback = { error -> AndroidView(factory = { ctx -> fallback(ctx, error) }) },
                )
                null -> Unit
            }
        }
    }

    init {
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}
