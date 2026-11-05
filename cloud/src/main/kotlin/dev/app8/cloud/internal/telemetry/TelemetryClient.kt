package dev.app8.cloud.internal.telemetry

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.net.Endpoint
import dev.app8.cloud.internal.net.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Best-effort, fire-and-forget batching of [TelemetryEvent]s to `POST /apps/{id}/telemetry`
 * (iOS `TelemetryClient`). A failed flush drops its batch. Buffer cap 500 (oldest dropped),
 * flush at 50 events, every 10s, and when the app goes to the background; at most 100 per POST.
 */
internal class TelemetryClient(
    private val client: HttpClient,
    private val appId: String,
    private val identityProvider: () -> Map<String, String>,
    private val log: Diagnostics,
    context: Context? = null,
    flushInterval: Duration = 10.seconds,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val bufferCap = 500
    private val flushAtSize = 50
    private val batchLimit = 100

    private val lock = Any()
    private val buffer = ArrayDeque<TelemetryEvent>()
    private var flushJob: Job? = null
    private var timerJob: Job? = null
    private var appContext: Context? = null

    /** iOS flushes on `willResignActive`; Android's closest signal is UI-hidden trim. */
    private val backgroundCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) triggerFlush()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    init {
        timerJob = scope.launch {
            while (isActive) {
                delay(flushInterval)
                if (synchronized(lock) { buffer.isNotEmpty() }) triggerFlush()
            }
        }
        appContext = context
        runCatching { context?.registerComponentCallbacks(backgroundCallbacks) }
    }

    fun enqueue(event: TelemetryEvent) {
        val shouldFlush = synchronized(lock) {
            if (buffer.size >= bufferCap) {
                repeat(buffer.size - bufferCap + 1) { buffer.removeFirst() }
                log.warning { "Telemetry: buffer at cap ($bufferCap); dropped oldest event." }
            }
            buffer.addLast(event)
            buffer.size >= flushAtSize
        }
        if (shouldFlush) triggerFlush()
    }

    /** Sends one batch (up to 100) and waits for it. */
    suspend fun flush() {
        triggerFlush()
        val job = synchronized(lock) { flushJob }
        job?.join()
    }

    /** Stops the timer and background flushes, then flushes. Call on teardown. */
    suspend fun shutdown() {
        timerJob?.cancel()
        timerJob = null
        appContext?.let { ctx -> runCatching { ctx.unregisterComponentCallbacks(backgroundCallbacks) } }
        appContext = null
        flush()
    }

    /** Stops everything without flushing. */
    fun close() {
        scope.cancel()
    }

    private fun triggerFlush() {
        synchronized(lock) {
            if (flushJob?.isActive == true) return
            val count = minOf(buffer.size, batchLimit)
            if (count == 0) return
            val batch = List(count) { buffer.removeFirst() }
            // Not recursive: more events wait for the next size trigger or tick, so a flapping
            // backend can't cause a hot loop.
            flushJob = scope.launch { send(batch) }
        }
    }

    private suspend fun send(events: List<TelemetryEvent>) {
        val identity = identityProvider()
        val body = runCatching {
            JsonObject(mapOf("events" to JsonArray(events.map { it.toWire() }))).toString().encodeToByteArray()
        }.getOrElse { e ->
            log.warning { "Telemetry: payload serialization failed ($e) — dropping ${events.size} events." }
            return
        }
        try {
            client.post(Endpoint.Telemetry(appId), body, identity.takeIf { it.isNotEmpty() })
            log.debug { "Telemetry: flushed ${events.size} events." }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warning { "Telemetry: flush failed ($e) — dropped ${events.size} events." }
        }
    }
}
