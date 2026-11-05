package dev.app8.cloud

import dev.app8.cloud.internal.net.ScreenRenderResponse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RenderResponseTest {

    @Test fun datasourcesNullDecodesAsNull() {
        val r = ScreenRenderResponse.decode(
            """{"servedVersion":"v1","data":{"type":"screen"},"styles":null,"components":null,"datasources":null}""".encodeToByteArray(),
        )
        assertEquals("v1", r.servedVersion)
        assertNull(r.styles)
        assertNull(r.components)
        assertNull(r.datasources)
    }

    @Test fun datasourcesMissingDecodesAsNull() {
        val r = ScreenRenderResponse.decode("""{ "servedVersion": "v1", "data": { "type": "screen" } }""".encodeToByteArray())
        assertNull(r.datasources)
    }

    @Test fun datasourcesPopulatedDecodesAsMap() {
        val r = ScreenRenderResponse.decode(
            """
            {"servedVersion":"v2","data":{"type":"screen"},
             "datasources":{"products/featured":[{"id":1},{"id":2}],"users/me":{"name":"alex"}}}
            """.trimIndent().encodeToByteArray(),
        )
        assertEquals(2, r.datasources?.size)
        assertNotNull(r.datasources?.get("products/featured"))
        assertNotNull(r.datasources?.get("users/me"))
    }

    @Test fun stylesAndComponentsNullDecodeAsNull() {
        val r = ScreenRenderResponse.decode(
            """{"servedVersion":"v1","data":{"type":"screen"},"styles":null,"components":null}""".encodeToByteArray(),
        )
        assertNull(r.styles)
        assertNull(r.components)
    }
}
