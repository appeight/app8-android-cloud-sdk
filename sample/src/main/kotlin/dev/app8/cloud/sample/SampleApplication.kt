package dev.app8.cloud.sample

import android.app.Application
import dev.app8.cloud.App8Cloud
import dev.app8.cloud.App8CloudInstance
import dev.app8.cloud.Environment
import dev.app8.cloud.NetworkPolicy
import dev.app8.cloud.TelemetryPolicy

/**
 * Holds the one App8Cloud instance. Offline-only: everything renders from the `.a8pack` in
 * `assets/App8OfflinePackages/`, so the sample needs no backend or token.
 */
class SampleApplication : Application() {
    val cloud: App8CloudInstance by lazy {
        App8Cloud.instance(
            context = this,
            token = "app8_test_offline_sample",
            appId = "facetheory-demo",
            environment = Environment.Custom("https://api.app8.dev/sdk/v1"),
            telemetry = TelemetryPolicy.DISABLED,
            networkPolicy = NetworkPolicy.OFFLINE_ONLY,
        )
    }
}
