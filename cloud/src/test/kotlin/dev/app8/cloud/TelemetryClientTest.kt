package dev.app8.cloud

import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.cache.Iso8601
import dev.app8.cloud.internal.telemetry.TelemetryClient
import dev.app8.cloud.internal.telemetry.TelemetryEvent
import dev.app8.cloud.internal.telemetry.sanitizeJsonMap
import dev.app8.cloud.internal.telemetry.telemetryReasonString
import dev.app8.engine.App8Error
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import java.io.IOException
import java.time.Instant
import java.util.Base64
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class TelemetryClientTest {
    private val backend = MockBackend()
    private val clients = mutableListOf<TelemetryClient>()

    @After fun tearDown() {
        clients.forEach { it.close() }
        backend.close()
    }

    private fun makeTelemetry(identity: Map<String, String> = emptyMap()) =
        TelemetryClient(makeHttpClient(backend), "app1", { identity }, Diagnostics.disabled, flushInterval = 1.hours).also { clients += it }

    private fun events(body: ByteArray?): List<JsonObject> =
        (Json.parseToJsonElement(body!!.decodeToString()).jsonObject["events"] as JsonArray).map { it.jsonObject }

    private val bodies get() = backend.requests.map { it.body?.toByteArray() }

    @Test fun enqueueAndFlushPostsToTelemetryEndpoint() = runBlocking<Unit> {
        backend.handler = { status(202, """{"accepted":1,"skipped":0}""") }
        val client = makeTelemetry()
        client.enqueue(TelemetryEvent("sdk_init", Instant.ofEpochSecond(1_770_000_000), context = mapOf("hostBundleId" to "com.partner.app")))
        client.flush()
        val req = backend.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/sdk/v1/apps/app1/telemetry", req.path)
        assertEquals("application/json", req.headers["Content-Type"])
        val events = events(req.body?.toByteArray())
        assertEquals(1, events.size)
        assertEquals("sdk_init", events.first()["type"]?.jsonPrimitive?.content)
        assertNotNull(events.first()["occurredAt"]?.jsonPrimitive?.content)
    }

    @Test fun emptyFlushDoesNotPost() = runBlocking<Unit> {
        backend.handler = { status(202) }
        makeTelemetry().flush()
        assertEquals(0, backend.totalCount)
    }

    @Test fun batchCapsAt100Events() = runBlocking<Unit> {
        backend.handler = { status(202) }
        val client = makeTelemetry()
        repeat(150) { client.enqueue(TelemetryEvent("custom", context = mapOf("i" to it))) }
        repeat(5) { client.flush() }
        val counts = bodies.map { events(it).size }
        assertTrue(counts.isNotEmpty())
        assertTrue(counts.any { it in 1..100 }, "expected a batch of up to 100 events; got $counts")
        counts.forEach { assertTrue(it <= 100) }
        assertEquals(150, counts.sum())
    }

    @Test fun bufferCapsAt500EventsDropsOldest() = runBlocking<Unit> {
        backend.handler = { status(202) }
        val client = makeTelemetry()
        repeat(600) { client.enqueue(TelemetryEvent("custom", context = mapOf("i" to it))) }
        repeat(10) { client.flush() }
        // The last batch can only hold events that survived the cap.
        val indices = events(bodies.last()).map { it["context"]!!.jsonObject["i"]!!.jsonPrimitive.int }
        indices.forEach { assertTrue(it >= 100, "event i=$it should have been evicted (cap=500, total=600)") }
    }

    @Test fun non2xxResponseDropsBatch() = runBlocking<Unit> {
        backend.handler = { status(400) }
        val client = makeTelemetry()
        client.enqueue(TelemetryEvent("custom"))
        client.flush()
        // 400 is not retryable: one attempt, then the batch is dropped.
        assertEquals(1, backend.totalCount)
        client.flush()
        assertEquals(1, backend.totalCount)
    }

    @Test fun iso8601EncodingInWirePayload() = runBlocking<Unit> {
        backend.handler = { status(202) }
        val client = makeTelemetry()
        val whenAt = Instant.ofEpochSecond(1_768_530_600)
        client.enqueue(TelemetryEvent("sdk_init", whenAt))
        client.flush()
        val occurredAt = events(bodies.single()).first()["occurredAt"]!!.jsonPrimitive.content
        assertEquals(whenAt, Iso8601.parse(occurredAt))
        assertEquals("2026-01-16T02:30:00Z", occurredAt)
    }

    @Test fun identityHeaderIncludedFromProvider() = runBlocking<Unit> {
        backend.handler = { status(202) }
        val client = makeTelemetry(identity = mapOf("userId" to "u1"))
        client.enqueue(TelemetryEvent("custom"))
        client.flush()
        val header = assertNotNull(backend.requests.single().headers["X-App8-Identity"])
        val attrs = Json.parseToJsonElement(Base64.getDecoder().decode(header).decodeToString()).jsonObject
        assertEquals("u1", attrs["userId"]?.jsonPrimitive?.content)
    }

    @Test fun shutdownFlushesPendingEvents() = runBlocking<Unit> {
        backend.handler = { status(202) }
        val client = makeTelemetry()
        client.enqueue(TelemetryEvent("sdk_init"))
        client.shutdown()
        assertEquals(1, backend.totalCount)
    }

    /** Every emitted type round-trips through enqueue + flush: catches typos in `type` strings. */
    @Test fun allEmittedEventTypesReachWireBody() = runBlocking<Unit> {
        backend.handler = { status(202) }
        val client = makeTelemetry()
        val now = Instant.ofEpochSecond(1_770_000_000)
        val cases: List<Triple<String, String?, Map<String, Any?>?>> = listOf(
            Triple("sdk_init", null, mapOf("hostBundleId" to "com.partner.app")),
            Triple("screen_render", "home", mapOf("kind" to "screen", "durationMs" to 120, "fromCache" to true)),
            Triple(
                "screen_presented", "home",
                mapOf("kind" to "screen", "width" to 390.0, "height" to 844.0, "scale" to 3.0, "horizontalSizeClass" to "compact", "verticalSizeClass" to "regular", "deviceIdiom" to "phone"),
            ),
            Triple("screen_render_failed", "home", mapOf("kind" to "screen", "reason" to "server_error", "status" to 500)),
            Triple("render_fallback", "home", mapOf("reason" to "network_unavailable")),
            Triple("prefetch_completed", null, mapOf("scope" to "specific", "screenCount" to 3, "successCount" to 3, "failureCount" to 0, "cancelled" to false)),
            Triple("asset_fetch_failed", null, mapOf("assetId" to "asset-1", "reason" to "timeout")),
            Triple("attributes_set", null, mapOf("count" to 2, "droppedReservedCount" to 1)),
            Triple("attributes_cleared", null, null),
            Triple("custom", null, mapOf("name" to "checkout_started")),
        )
        cases.forEach { (type, key, ctx) -> client.enqueue(TelemetryEvent(type, now, key, ctx)) }
        client.flush()
        val events = events(bodies.single())
        val types = events.map { it["type"]!!.jsonPrimitive.content }.toSet()
        cases.forEach { assertTrue(it.first in types, "missing event type '${it.first}' in wire body; got $types") }
        val failed = events.first { it["type"]!!.jsonPrimitive.content == "screen_render_failed" }["context"]!!.jsonObject
        assertEquals("server_error", failed["reason"]!!.jsonPrimitive.content)
        assertEquals(500, failed["status"]!!.jsonPrimitive.int)
        // attributes_cleared has no context: the encoder omits it.
        assertNull(events.first { it["type"]!!.jsonPrimitive.content == "attributes_cleared" }["context"])
    }

    @Test fun telemetryReasonStringCoversEveryErrorCase() {
        val cases = listOf(
            App8CloudError.AuthInvalid() to "auth_invalid",
            App8CloudError.AppNotFound("a") to "app_not_found",
            App8CloudError.ScreenNotFound("s") to "screen_not_found",
            App8CloudError.ScreenVersionNotFound("s", "v") to "screen_version_not_found",
            App8CloudError.NoNetwork(IOException("offline")) to "network_unavailable",
            App8CloudError.Timeout() to "timeout",
            App8CloudError.ServerError(500, true) to "server_error",
            App8CloudError.DecodeFailed("x", IllegalStateException()) to "decode_failed",
            App8CloudError.DslVersionUnsupported("2.0", "1.0") to "dsl_version_unsupported",
            App8CloudError.OfflineResourceMissing("x") to "offline_resource_missing",
            App8CloudError.OfflineBundleInvalid("x") to "offline_bundle_invalid",
            App8CloudError.Engine(App8Error.AppInitFailed()) to "engine_error",
        )
        cases.forEach { (error, expected) -> assertEquals(expected, telemetryReasonString(error)) }
    }

    @Test fun sanitizeJsonDictDropsNonJsonValues() {
        class Custom
        val cleaned = sanitizeJsonMap(
            mapOf(
                "ok" to "value", "n" to 42, "f" to 3.14, "b" to true,
                "nested" to mapOf("inner" to "ok"), "arr" to listOf(1, "two", false),
                "bad" to Custom(), "nan" to Double.NaN,
            ),
        )
        assertEquals("value", cleaned["ok"]!!.jsonPrimitive.content)
        assertEquals(42, cleaned["n"]!!.jsonPrimitive.int)
        assertEquals(3.14, cleaned["f"]!!.jsonPrimitive.doubleOrNull)
        assertEquals(true, cleaned["b"]!!.jsonPrimitive.booleanOrNull)
        assertNotNull(cleaned["nested"]?.jsonObject)
        assertEquals(3, cleaned["arr"]!!.jsonArray.size)
        assertNull(cleaned["bad"])
        assertNull(cleaned["nan"])
    }
}
