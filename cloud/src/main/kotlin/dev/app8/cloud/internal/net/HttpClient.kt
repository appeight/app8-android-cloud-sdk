package dev.app8.cloud.internal.net

import dev.app8.cloud.App8CloudError
import dev.app8.cloud.internal.CloudJson
import dev.app8.cloud.internal.Diagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

internal class HttpResult(val data: ByteArray, val status: Int)

internal class ResponseTooLargeError(val byteCount: Long) : Exception("response is $byteCount bytes")

/**
 * OkHttp-backed client for `/sdk/v1` (iOS `HTTPClient`). No HTTP cache: caching belongs to
 * `DiskCache`. Transient failures (timeouts, connectivity, 408/429/502/503/504) retry with
 * exponential backoff 0.4s, 1.2s, 3.6s (+ up to 0.1s jitter); 4xx never retries.
 */
internal class HttpClient(
    private val baseUrl: String,
    private val headers: HeaderBuilder,
    timeout: Duration,
    private val log: Diagnostics,
    /**
     * `NetworkPolicy.OFFLINE_ONLY`: every request fails with `OfflineResourceMissing` before
     * touching the network. All reads are cache-first, so only a genuine miss gets here.
     */
    private val offlineOnly: Boolean = false,
    clientOverride: OkHttpClient? = null,
    private val retryDelaysMs: LongArray = longArrayOf(400, 1_200, 3_600),
    private val jitterMs: Long = 100,
) {
    private val client: OkHttpClient = clientOverride ?: OkHttpClient.Builder()
        .connectTimeout(timeout.toJavaDuration())
        .readTimeout(timeout.toJavaDuration())
        .writeTimeout(timeout.toJavaDuration())
        // Whole-call budget: 4x the per-request timeout, floored at 120s.
        .callTimeout(maxOf(timeout * 4, 120.seconds).toJavaDuration())
        .cache(null)
        .build()

    /** Post-download cap on one body so a misbehaving backend can't make the SDK cache an unbounded blob. */
    private val maxResponseBytes: Long = 32L * 1024 * 1024

    suspend fun get(endpoint: Endpoint, identity: Map<String, String>? = null): HttpResult {
        guardOffline(endpoint.coalesceKey)
        return sendWithRetry {
            val url = endpoint.resolve(baseUrl)
            val request = Request.Builder().url(url).get().applyHeaders(identity).build()
            log.debug { "HTTP ${endpoint.method} $url" }
            send(request, endpoint)
        }
    }

    /** 4xx is not retried; 5xx/transient retries with backoff. */
    suspend fun post(endpoint: Endpoint, body: ByteArray, identity: Map<String, String>? = null): HttpResult {
        guardOffline(endpoint.coalesceKey)
        return sendWithRetry {
            val url = endpoint.resolve(baseUrl)
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .applyHeaders(identity)
                .build()
            log.debug { "HTTP ${endpoint.method} $url bytes=${body.size}" }
            send(request, endpoint)
        }
    }

    /** A presigned asset URL: no SDK token or identity (the URL carries its own auth). */
    suspend fun getRawUrl(url: String): HttpResult {
        val parsed = url.toHttpUrlOrNull()
        guardOffline("asset:${parsed?.host.orEmpty()}${parsed?.encodedPath.orEmpty()}")
        val target = parsed ?: throw App8CloudError.DecodeFailed("asset url", IllegalArgumentException("invalid URL"))
        return sendWithRetry {
            val request = Request.Builder().url(target).get()
                .header("User-Agent", headers.userAgent())
                .build()
            log.debug { "HTTP GET ${target.host}${target.encodedPath}" }
            sendBare(request)
        }
    }

    private fun Request.Builder.applyHeaders(identity: Map<String, String>?): Request.Builder = apply {
        headers.standardHeaders().forEach { (k, v) -> header(k, v) }
        headers.identityHeader(identity)?.let { header("X-App8-Identity", it) }
    }

    private fun guardOffline(context: String) {
        if (offlineOnly) {
            log.debug { "HTTP suppressed (offlineOnly): $context" }
            throw App8CloudError.OfflineResourceMissing(context)
        }
    }

    private suspend fun send(request: Request, endpoint: Endpoint): HttpResult =
        client.newCall(request).await().use { response ->
            val data = response.body.bytes()
            if (response.isSuccessful) {
                enforceSizeLimit(data, endpoint.coalesceKey)
                HttpResult(data, response.code)
            } else {
                throw map(response.code, endpoint, data)
            }
        }

    private suspend fun sendBare(request: Request): HttpResult =
        client.newCall(request).await().use { response ->
            val data = response.body.bytes()
            if (response.isSuccessful) {
                enforceSizeLimit(data, "asset")
                HttpResult(data, response.code)
            } else {
                throw genericMap(response.code)
            }
        }

    private fun enforceSizeLimit(data: ByteArray, endpoint: String) {
        if (data.size <= maxResponseBytes) return
        log.warning { "HTTP response for $endpoint is ${data.size} bytes, over the $maxResponseBytes-byte limit — rejecting." }
        throw App8CloudError.DecodeFailed(
            "response too large (${data.size} bytes) for $endpoint",
            ResponseTooLargeError(data.size.toLong()),
        )
    }

    private suspend fun sendWithRetry(work: suspend () -> HttpResult): HttpResult {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                return work()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val retry = isTransient(e) && attempt < retryDelaysMs.size
                if (!retry) throw mapIoError(e)
                delay(retryDelaysMs[attempt] + if (jitterMs > 0) Random.nextLong(0, jitterMs + 1) else 0)
                attempt++
            }
        }
    }

    private fun isTransient(error: Throwable): Boolean = when (error) {
        is App8CloudError.ServerError -> error.status in TRANSIENT_STATUSES
        is App8CloudError.Timeout, is App8CloudError.NoNetwork -> true
        is App8CloudError -> false
        is SocketTimeoutException, is UnknownHostException, is ConnectException,
        is NoRouteToHostException, is SocketException -> true
        is InterruptedIOException -> true // OkHttp call timeout
        else -> false
    }

    /** Body of every non-2xx response; 412 also carries top-level `required` / `client_max`. */
    @Serializable
    private class BackendErrorBody(
        val error: String? = null,
        val code: String? = null,
        val required: String? = null,
        @kotlinx.serialization.SerialName("client_max") val clientMax: String? = null,
        val details: Map<String, String>? = null,
    )

    private fun map(status: Int, endpoint: Endpoint, body: ByteArray): App8CloudError {
        val parsed = runCatching { CloudJson.decodeFromString(BackendErrorBody.serializer(), body.decodeToString()) }.getOrNull()
        if (parsed != null) {
            log.warning { "HTTP $status endpoint=${endpoint.coalesceKey} code=${parsed.code ?: "?"} details=${parsed.details ?: "[]"}" }
        }
        return when {
            status == 401 || status == 403 -> App8CloudError.AuthInvalid()
            status == 404 && endpoint is Endpoint.Manifest -> App8CloudError.AppNotFound(endpoint.appId)
            status == 404 && endpoint is Endpoint.Screen && endpoint.version == null -> App8CloudError.ScreenNotFound(endpoint.screenId)
            status == 404 && endpoint is Endpoint.Screen -> App8CloudError.ScreenVersionNotFound(endpoint.screenId, endpoint.version!!)
            status == 404 -> App8CloudError.ServerError(404, retryable = false)
            status == 412 && (endpoint is Endpoint.Screen || endpoint is Endpoint.Manifest) ->
                App8CloudError.DslVersionUnsupported(parsed?.required ?: "?", parsed?.clientMax ?: "?")
            status in TRANSIENT_STATUSES -> App8CloudError.ServerError(status, retryable = true)
            else -> App8CloudError.ServerError(status, retryable = false)
        }
    }

    private fun genericMap(status: Int): App8CloudError = when (status) {
        401, 403 -> App8CloudError.AuthInvalid()
        in TRANSIENT_STATUSES -> App8CloudError.ServerError(status, retryable = true)
        else -> App8CloudError.ServerError(status, retryable = false)
    }

    private fun mapIoError(error: Throwable): Throwable = when (error) {
        is App8CloudError -> error
        is SocketTimeoutException -> App8CloudError.Timeout()
        is InterruptedIOException -> if (error.message == "timeout") App8CloudError.Timeout() else App8CloudError.NoNetwork(error)
        is IOException -> App8CloudError.NoNetwork(error)
        else -> error
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val TRANSIENT_STATUSES = setOf(408, 429, 502, 503, 504)
    }
}

/** Suspends until the call completes; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}
