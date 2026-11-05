package dev.app8.cloud

import dev.app8.cloud.internal.data.InFlightCoalescer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InFlightCoalescerTest {

    @Test fun concurrentCallsCoalesceToOneTask() = runBlocking<Unit> {
        val coalescer = InFlightCoalescer()
        val counter = AtomicInteger()
        val payload = byteArrayOf(0xAA.toByte())
        // The work sleeps 100ms so all 50 callers look the key up before it completes.
        val results = (0 until 50).map {
            async {
                coalescer.run("k1") {
                    counter.incrementAndGet()
                    yield()
                    delay(100)
                    payload
                }
            }
        }.awaitAll()
        assertEquals(50, results.size)
        assertTrue(results.all { it.contentEquals(payload) })
        assertEquals(1, counter.get(), "Coalescer should have invoked work exactly once.")
    }

    @Test fun differentKeysDoNotCoalesce() = runBlocking<Unit> {
        val coalescer = InFlightCoalescer()
        val counter = AtomicInteger()
        val a = async { coalescer.run("a") { counter.incrementAndGet(); ByteArray(0) } }
        val b = async { coalescer.run("b") { counter.incrementAndGet(); ByteArray(0) } }
        a.await()
        b.await()
        assertEquals(2, counter.get())
    }

    @Test fun callsAfterCompletionAreFreshFetches() = runBlocking<Unit> {
        val coalescer = InFlightCoalescer()
        val counter = AtomicInteger()
        coalescer.run("k") { counter.incrementAndGet(); ByteArray(0) }
        coalescer.run("k") { counter.incrementAndGet(); ByteArray(0) }
        assertEquals(2, counter.get())
    }
}
