package dev.app8.cloud.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/** Lenient on unknown keys (the backend adds fields), strict on JSON syntax. */
internal val CloudJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal fun parseJson(bytes: ByteArray): JsonElement = CloudJson.parseToJsonElement(bytes.decodeToString())

internal fun JsonElement.toBytes(): ByteArray = CloudJson.encodeToString(JsonElement.serializer(), this).encodeToByteArray()

internal val JsonElement.objOrNull: JsonObject? get() = this as? JsonObject
internal val JsonElement.arrOrNull: JsonArray? get() = this as? JsonArray
internal val JsonElement.stringOrNull: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.string(key: String): String? = this[key]?.stringOrNull

/** Each element of a JSON array as its own bytes (iOS `decodeRawJSONArrayIfPresent`). Null for missing/null. */
internal fun JsonElement?.blobArrayOrNull(): List<ByteArray>? = when (this) {
    null, is JsonNull -> null
    is JsonArray -> map { it.toBytes() }
    else -> throw IllegalArgumentException("expected an array")
}

/** The top-level `id` of a JSON object blob (components are keyed by it). */
internal fun extractDslId(blob: ByteArray): String? =
    runCatching { parseJson(blob).objOrNull?.string("id") }.getOrNull()

/** SHA-256 of a body as lowercase hex (iOS `ContentHash.sha256Hex`). */
internal object ContentHash {
    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

/**
 * Dotted numeric version compare ("1.0", "1.2.3"): true when [found] <= [max]. Missing or
 * non-numeric components count as 0, so "1.0" == "1.0.0" and "1.10" > "1.2".
 */
internal fun dslVersionIsSupported(found: String, max: String): Boolean {
    fun parts(s: String) = s.split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(found)
    val b = parts(max)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return true
}
