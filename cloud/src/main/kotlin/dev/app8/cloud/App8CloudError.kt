package dev.app8.cloud

import dev.app8.engine.App8Error
import java.io.IOException

/** Errors thrown by App8Cloud (iOS `App8Cloud.Error`). */
public sealed class App8CloudError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 401/403: the token was rejected. */
    public class AuthInvalid : App8CloudError("App8Cloud: token rejected (401/403). Check the token and the environment.")

    public class AppNotFound(public val appId: String) : App8CloudError("App8Cloud: app '$appId' not found.")

    public class ScreenNotFound(public val screenId: String) : App8CloudError("App8Cloud: screen '$screenId' not found.")

    public class ScreenVersionNotFound(public val screenId: String, public val version: String) :
        App8CloudError("App8Cloud: screen '$screenId' has no version '$version'.")

    public class NoNetwork(public val underlying: IOException) :
        App8CloudError("App8Cloud: no network (${underlying.javaClass.simpleName}: ${underlying.message}).", underlying)

    public class Timeout : App8CloudError("App8Cloud: request timed out.")

    /** [retryable] `true` means the SDK already retried internally. */
    public class ServerError(public val status: Int, public val retryable: Boolean) :
        App8CloudError("App8Cloud: server error $status (retryable=$retryable).")

    public class DecodeFailed(public val context: String, public val underlying: Throwable) :
        App8CloudError("App8Cloud: decode failed in $context: $underlying.", underlying)

    /** The content needs DSL [found]; this build renders up to [max] (HTTP 412 or a catalog / bundle gate). */
    public class DslVersionUnsupported(public val found: String, public val max: String) :
        App8CloudError("App8Cloud: DSL version $found > max supported $max. Upgrade the SDK.")

    /**
     * [NetworkPolicy.OFFLINE_ONLY] and the resource was not in the cache, so the SDK refused to
     * reach the network. [context] names the resource.
     */
    public class OfflineResourceMissing(public val context: String) :
        App8CloudError(
            "App8Cloud: offline-only and '$context' is not in the cache. " +
                "Import an offline bundle or warm the cache while online.",
        )

    /** An `.a8pack` could not be imported: malformed manifest, unsupported `bundleFormat`, app-id mismatch or a failed checksum. */
    public class OfflineBundleInvalid(public val reason: String) :
        App8CloudError("App8Cloud: offline bundle invalid: $reason.")

    /** The engine failed (decode, missing screen, platform floor). */
    public class Engine(public val error: App8Error) :
        App8CloudError("App8Cloud: engine error: ${error.message}.", error)
}
