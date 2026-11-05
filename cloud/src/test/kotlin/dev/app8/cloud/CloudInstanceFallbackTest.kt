package dev.app8.cloud

import androidx.compose.runtime.Composable
import dev.app8.cloud.internal.instance.CloudInstance
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** iOS `A8CInstanceFallbackTests`: fallbacks run on errors, the throwing variant still throws. */
class CloudInstanceFallbackTest {
    private val backend = MockBackend()
    private val instances = mutableListOf<CloudInstance>()

    @After fun tearDown() {
        instances.forEach { it.close() }
        backend.close()
    }

    private fun makeCloud() = makeInstance(backend).also { instances += it }

    private val sentinel: @Composable () -> Unit = {}

    @Test fun screenFallbackInvokedOnAuthError() = runBlocking<Unit> {
        backend.handler = { status(401) }
        val cloud = makeCloud()
        var received: App8CloudError? = null
        val content = cloud.screen("home", null, emptyMap()) { error ->
            received = error
            sentinel
        }
        assertTrue(content.isFallback, "the fallback's content should be returned")
        assertIs<App8CloudError.AuthInvalid>(content.error)
        assertIs<App8CloudError.AuthInvalid>(received)
    }

    @Test fun onFallbackInvokedFiresAfterClosure() = runBlocking<Unit> {
        backend.handler = { status(401) }
        val cloud = makeCloud()
        val order = CopyOnWriteArrayList<String>()
        var event: FallbackEvent? = null
        cloud.onFallbackInvoked = {
            order += "callback"
            event = it
        }
        cloud.screen("home", null, emptyMap()) {
            order += "closure"
            sentinel
        }
        assertEquals(listOf("closure", "callback"), order, "fallback closure must run before onFallbackInvoked")
        assertEquals("home", event?.screenId)
        assertEquals(FallbackEvent.Source.SCREEN, event?.source)
        assertIs<App8CloudError.AuthInvalid>(event?.error)
    }

    @Test fun throwingVariantStillThrowsOn401() = runBlocking<Unit> {
        backend.handler = { status(401) }
        assertFailsWith<App8CloudError.AuthInvalid> { makeCloud().screen("home") }
    }

    @Test fun startAppFallbackPath() = runBlocking<Unit> {
        backend.handler = { status(401) }
        val cloud = makeCloud()
        var received: App8CloudError? = null
        var event: FallbackEvent? = null
        cloud.onFallbackInvoked = { event = it }
        val content = cloud.startApp(null) { error ->
            received = error
            sentinel
        }
        assertTrue(content.isFallback)
        assertIs<App8CloudError.AuthInvalid>(received)
        assertEquals(FallbackEvent.Source.APP, event?.source)
        assertNull(event?.screenId, "startApp fallbacks have a null screenId")
    }

    @Test fun fallbackOnDecodeError() = runBlocking<Unit> {
        backend.handler = { ok("not valid json {{{") }
        var received: App8CloudError? = null
        makeCloud().screen("home", null, emptyMap()) { error ->
            received = error
            sentinel
        }
        when (assertNotNull(received)) {
            is App8CloudError.DecodeFailed, is App8CloudError.Engine -> Unit
            else -> fail("expected a decode-class error; got $received")
        }
    }

    /** Android: the success path returns App8 content and skips the fallback. */
    @Test fun successPathSkipsFallback() = runBlocking<Unit> {
        backend.handler = { req ->
            when {
                req.path.endsWith("/manifest") -> ok("""{"configuration":{"title":"Test","navigation":{"startFlow":"main","flows":[{"id":"main","startScreen":"home"}]}}}""")
                req.path.endsWith("/styles") || req.path.endsWith("/components") -> ok("""{"items":[]}""")
                req.path.endsWith("/localizations") -> ok("""{"defaultLocale":"en","locales":{}}""")
                req.path == "/sdk/v1/apps/test-app/screens/home" -> ok("""{"servedVersion":"v3","data":{"id":"home","type":"view"}}""")
                else -> notFound()
            }
        }
        val cloud = makeCloud()
        var fallbackRan = false
        var rendered: RenderEvent? = null
        cloud.onScreenRendered = { rendered = it }
        val content = cloud.screen("home", null, emptyMap()) {
            fallbackRan = true
            sentinel
        }
        assertTrue(!content.isFallback && !fallbackRan)
        assertEquals("home", rendered?.screenId)
        assertEquals("v3", rendered?.servedVersion)
        assertEquals(false, rendered?.fromCache)
        assertSame(null, content.error)
    }
}
