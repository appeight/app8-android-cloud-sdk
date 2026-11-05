package dev.app8.cloud.internal.net

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import java.util.Locale

/** Host app identity for `X-App8-Host-*` headers and `sdk_init` telemetry. */
internal data class HostInfo(val packageName: String?, val appVersion: String?) {
    companion object {
        val Unknown = HostInfo(null, null)

        /** `versionName+versionCode` like iOS `CFBundleShortVersionString+CFBundleVersion`. */
        fun of(context: Context): HostInfo = runCatching {
            val pkg = context.packageName
            val info = pkg?.let { context.packageManager?.getPackageInfo(it, 0) }
            val name = info?.versionName
            val code = info?.longVersionCode?.takeIf { it > 0 }?.toString()
            val version = when {
                name != null && code != null -> "$name+$code"
                name != null -> name
                else -> code
            }
            HostInfo(pkg, version)
        }.getOrDefault(Unknown)
    }
}

/** iOS `HeaderBuilder`. */
internal class HeaderBuilder(
    val token: String,
    val sdkVersion: String,
    val maxSupportedDslVersion: String,
    val host: HostInfo = HostInfo.Unknown,
) {
    fun standardHeaders(): Map<String, String> {
        val h = linkedMapOf(
            "Authorization" to "Bearer $token",
            "X-App8-SDK-Version" to sdkVersion,
            "X-App8-DSL-Max" to maxSupportedDslVersion,
            "X-App8-Platform" to PLATFORM,
            "User-Agent" to userAgent(),
            "Accept" to "application/json",
        )
        // No Accept-Language: locale selection is client-side (the engine's translation store).
        host.packageName?.let { h["X-App8-Host-Bundle-Id"] = it }
        host.appVersion?.let { h["X-App8-Host-Version"] = it }
        return h
    }

    /** `X-App8-Identity`: base64 of the attributes as JSON with sorted keys; null when empty. */
    fun identityHeader(attributes: Map<String, String>?): String? {
        if (attributes.isNullOrEmpty()) return null
        val json = JsonObject(attributes.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()
        return Base64.getEncoder().encodeToString(json.encodeToByteArray())
    }

    fun userAgent(): String {
        val os = Build.VERSION.RELEASE?.let { "Android $it" } ?: "Android"
        val device = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        return "App8Cloud/$sdkVersion ($os; $device; ${Locale.getDefault().toLanguageTag()})"
    }

    companion object {
        const val PLATFORM: String = "android"
    }
}
