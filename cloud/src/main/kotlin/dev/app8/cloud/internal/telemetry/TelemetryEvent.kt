package dev.app8.cloud.internal.telemetry

import dev.app8.cloud.App8CloudError
import dev.app8.cloud.internal.cache.Iso8601
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * One remote telemetry event; [type] is the discriminator (same wire values as iOS):
 * - `sdk_init`: once per instance.
 * - `screen_render`: every successful screen, flow or app render.
 * - `screen_presented`: once the host lays the content out at a non-zero size (container size,
 *   density, size classes, device idiom). Not sent if the content is never drawn.
 * - `screen_render_failed`: every render failure (throwing or fallback path).
 * - `render_fallback`: the host fallback was used.
 * - `screen_availability_shortcircuit`: `screen(id)` failed from the local catalog with no
 *   round-trip; always paired with a `screen_render_failed`.
 * - `prefetch_completed`: a prefetch batch finished.
 * - `asset_fetch_failed`: an asset blob fetch failed after retries.
 * - `attributes_set` / `attributes_cleared`: identity changes.
 * - `custom`: the public `track(name, context)`.
 *
 * The fallback paths emit both `screen_render_failed` and `render_fallback` for one failure.
 */
internal data class TelemetryEvent(
    val type: String,
    val occurredAt: Instant = Instant.now(),
    val screenKey: String? = null,
    val context: Map<String, Any?>? = null,
) {
    fun toWire(): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["type"] = JsonPrimitive(type)
        m["occurredAt"] = JsonPrimitive(Iso8601.format(occurredAt))
        screenKey?.let { m["screenKey"] = JsonPrimitive(it) }
        context?.let(::sanitizeJsonMap)?.takeIf { it.isNotEmpty() }?.let { m["context"] = it }
        return JsonObject(m)
    }
}

/** Stable reason tag for an error, shared by `render_fallback`, `screen_render_failed`, `asset_fetch_failed`. */
internal fun telemetryReasonString(error: App8CloudError): String = when (error) {
    is App8CloudError.AuthInvalid -> "auth_invalid"
    is App8CloudError.AppNotFound -> "app_not_found"
    is App8CloudError.ScreenNotFound -> "screen_not_found"
    is App8CloudError.ScreenVersionNotFound -> "screen_version_not_found"
    is App8CloudError.NoNetwork -> "network_unavailable"
    is App8CloudError.Timeout -> "timeout"
    is App8CloudError.ServerError -> "server_error"
    is App8CloudError.DecodeFailed -> "decode_failed"
    is App8CloudError.DslVersionUnsupported -> "dsl_version_unsupported"
    is App8CloudError.OfflineResourceMissing -> "offline_resource_missing"
    is App8CloudError.OfflineBundleInvalid -> "offline_bundle_invalid"
    is App8CloudError.Engine -> "engine_error"
}

/** Keeps JSON-encodable values (strings, booleans, finite numbers, lists, string-keyed maps), recursively; drops the rest. */
internal fun sanitizeJsonMap(input: Map<String, Any?>): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    for ((k, v) in input) sanitizeJsonValue(v)?.let { out[k] = it }
    return JsonObject(out)
}

internal fun sanitizeJsonValue(value: Any?): JsonElement? = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int, is Long, is Short, is Byte -> JsonPrimitive(value as Number)
    is Double -> if (value.isFinite()) JsonPrimitive(value) else null
    is Float -> if (value.isFinite()) JsonPrimitive(value) else null
    is List<*> -> JsonArray(value.mapNotNull(::sanitizeJsonValue))
    is Array<*> -> JsonArray(value.mapNotNull(::sanitizeJsonValue))
    is Map<*, *> -> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in value) if (k is String) sanitizeJsonValue(v)?.let { out[k] = it }
        JsonObject(out)
    }
    else -> null
}
