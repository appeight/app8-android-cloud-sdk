// The README "Quick start" code, compiled with the sample so the README cannot drift from the
// public API. Keep the two in sync: edit here first, then paste into README.md.
package dev.app8.cloud.sample.readme

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import dev.app8.cloud.App8Cloud
import dev.app8.cloud.App8CloudInstance
import dev.app8.cloud.Environment
import dev.app8.cloud.PrefetchTarget
import dev.app8.cloud.compose.App8CloudScreen
import dev.app8.cloud.prefetch
import dev.app8.cloud.subscribe
import dev.app8.cloud.view.App8CloudView
import kotlinx.coroutines.launch

// region 1. Create one instance
object App8 {
    lateinit var cloud: App8CloudInstance
        private set

    fun start(activity: ComponentActivity) {
        if (::cloud.isInitialized) return
        cloud = App8Cloud.instance(
            context = activity.applicationContext,
            token = "app8_live_abc123xyz", // your App8 SDK token
            appId = "com.partner.flagship", // your App8 app identifier
            environment = Environment.Custom("https://your-api.example.com/sdk/v1"),
        )
        // Optional: identity attributes for analytics dashboards.
        cloud.setAttributes(mapOf("userId" to "u-42", "plan" to "free"))
    }
}
// endregion

// region 2. Render with Compose
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App8.start(this)
        setContent {
            App8CloudScreen(
                instance = App8.cloud,
                screenId = "home",
                placeholder = { CircularProgressIndicator() },
                fallback = { error -> Text("Native home (${error.message})") },
            )
        }
    }
}
// endregion

// region 2b. Render in a View hierarchy
class ViewHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App8.start(this)
        val view = App8CloudView(this).apply {
            instance = App8.cloud
            fallbackFactory = { context, _ -> android.widget.TextView(context).apply { text = "Native home" } }
            setScreen("home")
        }
        setContentView(view)
    }
}
// endregion

// region 3. Preload and observe
fun warmUp(activity: ComponentActivity) {
    activity.lifecycleScope.launch {
        App8.cloud.prefetch() // discovers and warms every screen + assets
        App8.cloud.prefetch(listOf(PrefetchTarget("home"), PrefetchTarget("settings")))
    }
    App8.cloud.subscribe("connect.tapped") { event -> Log.i("App8", "connect tapped: ${event.payload}") }
}
// endregion
