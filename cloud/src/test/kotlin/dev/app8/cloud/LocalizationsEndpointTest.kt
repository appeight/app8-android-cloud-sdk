package dev.app8.cloud

import dev.app8.cloud.internal.net.Endpoint
import dev.app8.cloud.internal.net.HeaderBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalizationsEndpointTest {

    @Test fun localizationsEndpointPathMatchesBackendRoute() {
        assertEquals("/apps/ABC123/localizations", Endpoint.Localizations("ABC123").path)
    }

    @Test fun localizationsEndpointIsGet() {
        assertEquals("GET", Endpoint.Localizations("ABC123").method)
    }

    @Test fun localizationsEndpointHasNoQueryItems() {
        assertTrue(Endpoint.Localizations("ABC123").queryItems.isEmpty())
    }

    @Test fun localizationsCoalesceKeyIsAppScoped() {
        // appId scope keeps a second instance from piggybacking on the first one's fetch.
        val a = Endpoint.Localizations("app-1")
        val b = Endpoint.Localizations("app-2")
        assertNotEquals(a.coalesceKey, b.coalesceKey)
        assertEquals("localizations:app-1", a.coalesceKey)
    }

    @Test fun resolveBuildsAbsoluteUrl() {
        val url = Endpoint.Localizations("ABC123").resolve("https://app8.example.com/sdk/v1")
        assertEquals("https://app8.example.com/sdk/v1/apps/ABC123/localizations", url.toString())
    }

    @Test fun standardHeadersOmitAcceptLanguage() {
        val headers = HeaderBuilder("test-token", "0.3.0", "1.0").standardHeaders()
        assertNull(headers["Accept-Language"])
        assertNull(headers["accept-language"])
    }

    @Test fun standardHeadersStillIncludeAuthAndSdkVersion() {
        val headers = HeaderBuilder("test-token", "0.3.0", "1.0").standardHeaders()
        assertEquals("Bearer test-token", headers["Authorization"])
        assertEquals("0.3.0", headers["X-App8-SDK-Version"])
        assertEquals("1.0", headers["X-App8-DSL-Max"])
    }

    /** Android-only: the backend tells platforms apart by this header. */
    @Test fun standardHeadersCarryAndroidPlatform() {
        assertEquals("android", HeaderBuilder("t", "0.3.0", "1.0").standardHeaders()["X-App8-Platform"])
    }
}
