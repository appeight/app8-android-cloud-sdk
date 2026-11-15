package dev.app8.cloud

import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.data.RenderingDataSource
import dev.app8.cloud.internal.fonts.FontRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The DSL `reloadDatasource` action asks the data source for fresh bytes (iOS
 * `DatasourceReloadTests`): `getDatasource` keeps serving the render response's copy, while
 * `reloadDatasource` refetches the screen that carries the datasource.
 */
class DatasourceReloadTest {

    private val backend = MockBackend()
    private val homeFetches = AtomicInteger()
    private val failing = AtomicBoolean(false)

    @After fun tearDown() {
        backend.close()
    }

    private fun dataSource(): RenderingDataSource {
        backend.handler = { req ->
            when {
                failing.get() -> status(500)
                req.path.endsWith("/screens/home") -> {
                    val n = homeFetches.incrementAndGet()
                    ok(
                        """
                        { "servedVersion": "1", "data": { "id": "home", "type": "screen", "content": {} },
                          "datasources": { "datasources/items": { "data": [ { "id": "v$n" } ] } } }
                        """.trimIndent(),
                    )
                }
                else -> notFound()
            }
        }
        return RenderingDataSource(
            "test-app",
            makeHttpClient(backend),
            null,
            null,
            FontRegistry(tempDir("app8cloud-reload-fonts"), Diagnostics.disabled),
            Diagnostics.disabled,
        )
    }

    private fun itemId(bytes: ByteArray): String =
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject["data"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

    @Test fun reloadFetchesFreshBytesWhileGetServesTheCache() = runBlocking<Unit> {
        val ds = dataSource()
        ds.getScreen("home")

        assertEquals("v1", itemId(ds.getDatasource("home", "datasources/items")))
        assertEquals(1, homeFetches.get(), "getDatasource serves the render response's copy")

        assertEquals("v2", itemId(ds.reloadDatasource("home", "datasources/items")))
        assertEquals(2, homeFetches.get())
        assertEquals("v2", itemId(ds.getDatasource("home", "datasources/items")), "the reload refreshes the cache too")
    }

    @Test fun appScopeReloadUsesTheScreenThatCarriedTheDatasource() = runBlocking<Unit> {
        val ds = dataSource()
        ds.getScreen("home")
        // App variables reload with an empty screenId.
        assertEquals("v2", itemId(ds.reloadDatasource("", "datasources/items")))
    }

    @Test fun reloadFailsInsteadOfServingStaleData() = runBlocking<Unit> {
        val ds = dataSource()
        ds.getScreen("home")
        failing.set(true)
        assertFailsWith<Exception>("a failed fetch must throw so the action's onError runs") {
            ds.reloadDatasource("home", "datasources/items")
        }
        assertFailsWith<Exception>("no screen carries this datasource") {
            ds.reloadDatasource("", "datasources/unknown")
        }
    }
}
