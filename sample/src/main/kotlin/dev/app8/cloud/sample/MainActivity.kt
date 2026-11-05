package dev.app8.cloud.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app8.cloud.App8CloudError
import dev.app8.cloud.compose.App8CloudApp

/** Imports the bundled `.a8pack` (idempotent), then launches the app from the cache. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val cloud = (application as SampleApplication).cloud
        setContent {
            var imported by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val summaries = cloud.importBundledPackages()
                Log.i("App8CloudSample", "imported: $summaries")
                imported = true
            }
            if (!imported) {
                Loading()
            } else {
                App8CloudApp(
                    instance = cloud,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = { Loading() },
                    fallback = { error -> NativeFallback(error) },
                )
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** The host's own screen when App8 content can't load. */
@Composable
private fun NativeFallback(error: App8CloudError) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("Content unavailable: ${error.message}")
    }
}
