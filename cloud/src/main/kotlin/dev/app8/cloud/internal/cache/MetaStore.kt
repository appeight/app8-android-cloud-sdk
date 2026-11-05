package dev.app8.cloud.internal.cache

import dev.app8.cloud.internal.CloudJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** ISO 8601 at second precision (`2026-01-01T00:00:00Z`), like iOS `.iso8601`. */
internal object Iso8601 {
    fun format(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))
    fun parse(text: String): Instant = Instant.parse(text)
}

internal object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Iso8601Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(Iso8601.format(value))
    override fun deserialize(decoder: Decoder): Instant = Iso8601.parse(decoder.decodeString())
}

/** App-level meta at `meta.json`. [appResources] tracks freshness of manifest, styles, components, localizations. */
@Serializable
internal data class CacheMeta(
    val schemaVersion: String = CacheLayout.SCHEMA_VERSION,
    val sdkVersion: String,
    val appResources: Map<String, ResourceMeta>? = null,
    @Serializable(with = InstantIsoSerializer::class) val fetchedAt: Instant = Instant.now(),
    val dslVersion: String? = null,
)

/** [updatedAt] is the opaque server timestamp; null means freshness relies on [contentHash] alone. */
@Serializable
internal data class ResourceMeta(
    val updatedAt: String? = null,
    val contentHash: String,
    @Serializable(with = InstantIsoSerializer::class) val fetchedAt: Instant = Instant.now(),
)

/** Persisted at `screens/{id}/_meta.json`. */
@Serializable
internal data class ScreenMeta(
    val schemaVersion: String = CacheLayout.SCHEMA_VERSION,
    val servedVersion: String?,
    val requestedVersion: String?,
    val updatedAt: String?,
    val contentHash: String,
    @Serializable(with = InstantIsoSerializer::class) val fetchedAt: Instant = Instant.now(),
)

internal data class ScreenFreshness(val version: String?, val updatedAt: String?)

internal object MetaStore {
    fun readApp(file: File): CacheMeta? = read(file, CacheMeta.serializer())
    fun writeApp(meta: CacheMeta, file: File): Boolean = write(meta, file, CacheMeta.serializer())
    fun readScreen(file: File): ScreenMeta? = read(file, ScreenMeta.serializer())
    fun writeScreen(meta: ScreenMeta, file: File): Boolean = write(meta, file, ScreenMeta.serializer())

    private fun <T> read(file: File, serializer: KSerializer<T>): T? {
        val bytes = file.readBytesOrNull() ?: return null
        return runCatching { CloudJson.decodeFromString(serializer, bytes.decodeToString()) }.getOrNull()
    }

    private fun <T> write(value: T, file: File, serializer: KSerializer<T>): Boolean {
        val bytes = runCatching { CloudJson.encodeToString(serializer, value).encodeToByteArray() }.getOrNull() ?: return false
        return atomicWrite(bytes, file)
    }
}
