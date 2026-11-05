package dev.app8.cloud

import android.content.Context
import dev.app8.cloud.internal.SDKVersion
import dev.app8.cloud.internal.instance.CloudInstance
import dev.app8.engine.App8Config
import java.io.File

/**
 * Entry point of the App8Cloud SDK: server-driven UI delivery and rendering on top of the App8
 * engine (iOS `App8Cloud`). Start with [instance].
 */
public object App8Cloud {

    /** This SDK's version; equals the bundled engine's `EngineVersion.CURRENT`. */
    public const val VERSION: String = SDKVersion.CURRENT

    /**
     * Creates an SDK instance for one App8 app. Keep it for the app's lifetime: it owns the cache,
     * the engine and the telemetry buffer.
     *
     * @throws dev.app8.engine.App8Error.UnsupportedPlatform below Android 12 (unless
     *   [App8Config.enforcePlatformFloor] is off).
     */
    @JvmStatic
    public fun instance(context: Context, config: App8CloudConfig): App8CloudInstance =
        CloudInstance(context, config)

    /** [instance] with the configuration as named arguments (iOS `App8Cloud.instance(token:appId:environment:…)`). */
    @JvmStatic
    @JvmOverloads
    public fun instance(
        context: Context,
        token: String,
        appId: String,
        environment: Environment,
        diskCache: DiskCachePolicy = DiskCachePolicy.Default,
        telemetry: TelemetryPolicy = TelemetryPolicy.ENABLED,
        diagnosticLoggingEnabled: Boolean = false,
        maxSupportedDslVersion: String = "1.0",
        networkPolicy: NetworkPolicy = NetworkPolicy.ONLINE,
        engine: App8Config = App8Config(),
    ): App8CloudInstance = instance(
        context,
        App8CloudConfig(
            token = token,
            appId = appId,
            environment = environment,
            diskCache = diskCache,
            telemetry = telemetry,
            diagnosticLoggingEnabled = diagnosticLoggingEnabled,
            maxSupportedDslVersion = maxSupportedDslVersion,
            networkPolicy = networkPolicy,
            engine = engine,
        ),
    )

    /**
     * Default location for runtime-delivered `.a8pack` bundles:
     * `<filesDir>/App8Cloud/packages/<appId>/`. Put a downloaded bundle here (or pass it to
     * [App8CloudInstance.importOfflineBundle]) and [App8CloudInstance.importBundledPackages] picks it up.
     */
    @JvmStatic
    public fun defaultOfflinePackageDirectory(context: Context, appId: String): File =
        File(File(File(context.filesDir, "App8Cloud"), "packages"), appId)
}
