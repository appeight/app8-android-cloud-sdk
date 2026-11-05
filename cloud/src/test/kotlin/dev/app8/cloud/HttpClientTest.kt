package dev.app8.cloud

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import java.util.Base64
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import dev.app8.cloud.internal.net.Endpoint

class HttpClientTest {
    private val backend = MockBackend()

    @After fun tearDown() = backend.close()

    @Test fun offlineOnlyRefusesNetwork() = runBlocking<Unit> {
        backend.handler = { ok("{}") }
        val client = makeHttpClient(backend, offlineOnly = true)
        assertFailsWith<App8CloudError.OfflineResourceMissing> { client.get(Endpoint.Manifest("app1")) }
        assertEquals(0, backend.totalCount, "network handler must never run in offline-only mode")
    }

    @Test fun getInjectsBearerHeader() = runBlocking<Unit> {
        backend.handler = { ok("{}") }
        makeHttpClient(backend).get(Endpoint.Manifest("app1"))
        val req = backend.requests.single()
        assertEquals("Bearer $TEST_TOKEN", req.headers["Authorization"])
        assertEquals("0.3.0", req.headers["X-App8-SDK-Version"])
        assertEquals("android", req.headers["X-App8-Platform"])
        assertEquals("/sdk/v1/apps/app1/manifest", req.path)
        assertEquals("GET", req.method)
    }

    @Test fun getWithVersionAddsQuery() = runBlocking<Unit> {
        backend.handler = { ok("{}") }
        makeHttpClient(backend).get(Endpoint.Screen("app1", "home", "v3"))
        val req = backend.requests.single()
        assertEquals("/sdk/v1/apps/app1/screens/home", req.path)
        assertEquals("version=v3", req.url.encodedQuery)
    }

    @Test fun identityHeaderEncodedAsBase64() = runBlocking<Unit> {
        backend.handler = { ok("{}") }
        makeHttpClient(backend).get(Endpoint.Manifest("app1"), mapOf("userId" to "u1", "plan" to "pro"))
        val header = assertNotNull(backend.requests.single().headers["X-App8-Identity"])
        val json = Json.parseToJsonElement(Base64.getDecoder().decode(header).decodeToString()) as JsonObject
        assertEquals("u1", json["userId"]?.jsonPrimitive?.content)
        assertEquals("pro", json["plan"]?.jsonPrimitive?.content)
    }

    @Test fun status401MapsToAuthInvalid() = runBlocking<Unit> {
        backend.handler = { status(401) }
        assertFailsWith<App8CloudError.AuthInvalid> { makeHttpClient(backend).get(Endpoint.Manifest("app1")) }
        Unit
    }

    @Test fun status404OnScreenWithVersionMapsToScreenVersionNotFound() = runBlocking<Unit> {
        backend.handler = { status(404) }
        val e = assertFailsWith<App8CloudError.ScreenVersionNotFound> {
            makeHttpClient(backend).get(Endpoint.Screen("app1", "home", "v99"))
        }
        assertEquals("home", e.screenId)
        assertEquals("v99", e.version)
    }

    @Test fun status404OnScreenWithoutVersionMapsToScreenNotFound() = runBlocking<Unit> {
        backend.handler = { status(404) }
        val e = assertFailsWith<App8CloudError.ScreenNotFound> {
            makeHttpClient(backend).get(Endpoint.Screen("app1", "ghost", null))
        }
        assertEquals("ghost", e.screenId)
    }

    @Test fun status503TriggersRetryThenSucceeds() = runBlocking<Unit> {
        backend.handler = { if (backend.totalCount < 3) status(503) else ok("{}") }
        val result = makeHttpClient(backend).get(Endpoint.Manifest("app1"))
        assertEquals(3, backend.totalCount)
        assertEquals("{}", result.data.decodeToString())
    }

    @Test fun status412ParsesRequiredAndClientMaxFromBody() = runBlocking<Unit> {
        backend.handler = {
            status(412, """{"error":"Bundle requires DSL 1.2","code":"dsl_version_mismatch","required":"1.2","client_max":"1.0"}""")
        }
        val e = assertFailsWith<App8CloudError.DslVersionUnsupported> {
            makeHttpClient(backend).get(Endpoint.Screen("app1", "checkout", null))
        }
        assertEquals("1.2", e.found)
        assertEquals("1.0", e.max)
    }

    @Test fun status412FallsBackToPlaceholdersWhenBodyMissing() = runBlocking<Unit> {
        backend.handler = { status(412) }
        val e = assertFailsWith<App8CloudError.DslVersionUnsupported> { makeHttpClient(backend).get(Endpoint.Manifest("app1")) }
        assertEquals("?", e.found)
        assertEquals("?", e.max)
    }

    @Test fun postTelemetrySendsJsonBody() = runBlocking<Unit> {
        backend.handler = { status(202, """{"accepted":1,"skipped":0}""") }
        val payload = """{"events":[{"type":"sdk_init"}]}""".encodeToByteArray()
        makeHttpClient(backend).post(Endpoint.Telemetry("app1"), payload)
        val req = backend.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/sdk/v1/apps/app1/telemetry", req.path)
        assertEquals("application/json", req.headers["Content-Type"])
        assertContentEquals(payload, req.body?.toByteArray())
    }

    @Test fun postTelemetry400Throws() = runBlocking<Unit> {
        backend.handler = { status(400, """{"error":"batch_too_large","code":"batch_too_large"}""") }
        val e = assertFailsWith<App8CloudError.ServerError> {
            makeHttpClient(backend).post(Endpoint.Telemetry("app1"), "{}".encodeToByteArray())
        }
        assertEquals(400, e.status)
        assertFalse(e.retryable)
    }

    @Test fun rawAssetUrlDoesNotIncludeBearer() = runBlocking<Unit> {
        backend.handler = { ok("x") }
        makeHttpClient(backend).getRawUrl(backend.server.url("/blob?token=xyz").toString())
        val req = backend.requests.single()
        assertNull(req.headers["Authorization"])
        assertNull(req.headers["X-App8-Identity"])
    }
}
