package dev.app8.cloud

import dev.app8.engine.App8Config
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Options fixed when an instance is created (the parameters of iOS `App8Cloud.instance(...)`).
 *
 * @property token Publishable SDK token (`app8_live_…` / `app8_test_…`).
 * @property appId The App8 app to render screens from.
 * @property environment Backend `/sdk/v1` base URL.
 * @property diskCache On-disk cache policy; enabled by default.
 * @property telemetry Remote render/usage telemetry; enabled by default.
 * @property diagnosticLoggingEnabled SDK diagnostics to logcat (tag `App8Cloud`). Off by default.
 * @property maxSupportedDslVersion Highest DSL version this build renders, sent as `X-App8-DSL-Max`.
 * @property networkPolicy [NetworkPolicy.OFFLINE_ONLY] serves only from the cache.
 * @property engine Options for the underlying engine instance (analytics toggles, log level, map provider…).
 */
public data class App8CloudConfig(
    val token: String,
    val appId: String,
    val environment: Environment,
    val diskCache: DiskCachePolicy = DiskCachePolicy.Default,
    val telemetry: TelemetryPolicy = TelemetryPolicy.ENABLED,
    val diagnosticLoggingEnabled: Boolean = false,
    val maxSupportedDslVersion: String = "1.0",
    val networkPolicy: NetworkPolicy = NetworkPolicy.ONLINE,
    val engine: App8Config = App8Config(),
)

/**
 * The backend the SDK talks to. Only [Custom] exists for now; named environments arrive once the
 * public endpoints settle (same as iOS).
 */
public sealed interface Environment {
    public val baseUrl: String

    /** [baseUrl] is the `/sdk/v1` base, e.g. `https://api.example.com/sdk/v1`. */
    public data class Custom(override val baseUrl: String) : Environment
}

/** Whether DSL bundles and assets persist on disk between launches. */
public sealed interface DiskCachePolicy {
    public data class Enabled(val config: DiskCacheConfig = DiskCacheConfig()) : DiskCachePolicy
    public data object Disabled : DiskCachePolicy

    public companion object {
        public val Default: DiskCachePolicy = Enabled(DiskCacheConfig())
    }
}

/**
 * Tunes the on-disk cache.
 *
 * @property rootDirectory `null` resolves to `<cacheDir>/App8Cloud/` (the cache lives under `v1/<appId>/`).
 * @property assetByteBudget LRU budget for asset blobs.
 * @property versionsToKeep Pinned screen versions retained per screen besides the latest.
 * @property catalogTtl How long the screen catalog (`GET /apps/{id}/screens`) is trusted to
 *   short-circuit unknown screen ids without a round-trip. Older catalogs refresh in the
 *   background and fall through to the network. [Duration.ZERO] never short-circuits.
 */
public data class DiskCacheConfig(
    val rootDirectory: File? = null,
    val assetByteBudget: Long = 50L * 1024 * 1024,
    val versionsToKeep: Int = 2,
    val catalogTtl: Duration = 24.hours,
)

/** What [App8CloudInstance.clearCache] removes. */
public sealed interface CacheScope {
    public data object All : CacheScope
    public data class Screen(val id: String) : CacheScope
    public data object AssetsOnly : CacheScope
}

/** Whether the SDK collects and sends anonymous render/usage telemetry. */
public enum class TelemetryPolicy { ENABLED, DISABLED }

/**
 * Whether the SDK may reach the network.
 *
 * - [ONLINE] (default): cache-first, then refresh from the backend.
 * - [OFFLINE_ONLY]: serve only from the disk cache (seed it with
 *   [App8CloudInstance.importOfflineBundle]). A miss throws [App8CloudError.OfflineResourceMissing].
 */
public enum class NetworkPolicy { ONLINE, OFFLINE_ONLY }
