package dev.app8.cloud

import android.content.Context
import android.content.ContextWrapper
import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.SDKVersion
import dev.app8.cloud.internal.instance.CloudInstance
import dev.app8.cloud.internal.net.HeaderBuilder
import dev.app8.cloud.internal.net.HttpClient
import dev.app8.engine.App8Config
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** A JVM [Context]: android.jar stubs plus the few methods the SDK reads. */
internal class TestContext(private val dir: File = Files.createTempDirectory("app8cloud-ctx").toFile()) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "dev.app8.cloud.test"
    override fun getCacheDir(): File = File(dir, "cache").apply { mkdirs() }
    override fun getFilesDir(): File = File(dir, "files").apply { mkdirs() }
}

internal fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

/**
 * MockWebServer standing in for `/sdk/v1` (the role of iOS `MockURLProtocol`). Set [handler];
 * every request is recorded and counted by path.
 */
internal class MockBackend : Closeable {
    val server = MockWebServer()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile var handler: (RecordedRequest) -> MockResponse = { notFound() }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                counts.getOrPut(request.url.encodedPath) { AtomicInteger() }.incrementAndGet()
                return handler(request)
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/sdk/v1").toString()

    fun count(path: String): Int = counts[path]?.get() ?: 0

    val totalCount: Int get() = counts.values.sumOf { it.get() }

    val paths: Set<String> get() = counts.keys.toSet()

    override fun close() {
        server.close()
    }
}

internal val RecordedRequest.path: String get() = url.encodedPath

internal fun ok(body: String): MockResponse = MockResponse.Builder().code(200).body(body).build()

internal fun status(code: Int, body: String = ""): MockResponse = MockResponse.Builder().code(code).body(body).build()

internal fun notFound(): MockResponse = status(404)

internal fun delayed(body: MockResponse, millis: Long): MockResponse =
    body.newBuilder().headersDelay(millis, TimeUnit.MILLISECONDS).build()

internal const val TEST_TOKEN = "app8_test_abc1234567"

internal fun makeHttpClient(backend: MockBackend, offlineOnly: Boolean = false): HttpClient = HttpClient(
    baseUrl = backend.baseUrl,
    headers = HeaderBuilder(TEST_TOKEN, "0.3.0", "1.0"),
    timeout = 5.seconds,
    log = Diagnostics.disabled,
    offlineOnly = offlineOnly,
    retryDelaysMs = longArrayOf(10, 20, 40),
    jitterMs = 0,
)

/**
 * A [CloudInstance] against [backend] (iOS tests build `A8CInstance` with an injected session).
 * Disk cache disabled unless [diskRoot] is given; fast retries; engine floor check off (JVM).
 */
internal fun makeInstance(
    backend: MockBackend,
    diskRoot: File? = null,
    catalogTtl: Duration = 24.hours,
    telemetry: TelemetryPolicy = TelemetryPolicy.ENABLED,
    appId: String = "test-app",
    networkPolicy: NetworkPolicy = NetworkPolicy.ONLINE,
    context: Context = TestContext(),
): CloudInstance = CloudInstance(
    context,
    App8CloudConfig(
        token = TEST_TOKEN,
        appId = appId,
        environment = Environment.Custom(backend.baseUrl),
        diskCache = diskRoot?.let { DiskCachePolicy.Enabled(DiskCacheConfig(rootDirectory = it, catalogTtl = catalogTtl)) }
            ?: DiskCachePolicy.Disabled,
        telemetry = telemetry,
        networkPolicy = networkPolicy,
        engine = App8Config(enforcePlatformFloor = false),
    ),
    requestTimeout = 2.seconds,
    retryDelaysMs = longArrayOf(10, 20, 40),
)

/** Collects bus events thread-safely. */
internal class Recorder<T> {
    private val items = CopyOnWriteArrayList<T>()
    fun add(item: T) {
        items += item
    }

    val all: List<T> get() = items.toList()
}

internal val CURRENT_SDK_VERSION: String get() = SDKVersion.CURRENT
