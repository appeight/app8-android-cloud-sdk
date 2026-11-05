package dev.app8.cloud

import dev.app8.cloud.internal.instance.FirstLayoutSentinel
import dev.app8.cloud.internal.instance.deviceIdiomString
import dev.app8.cloud.internal.instance.sizeClassString
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** iOS `LayoutSentinelView` tests, against the Compose `onSizeChanged` sentinel. */
class ScreenPresentedTelemetryTest {

    @Test fun sentinelFiresOnceOnFirstNonZeroLayout() {
        val sentinel = FirstLayoutSentinel()
        val calls = mutableListOf<Pair<Int, Int>>()
        sentinel.onSize(390, 844) { w, h -> calls += w to h }
        assertEquals(listOf(390 to 844), calls)
    }

    @Test fun sentinelDoesNotRefireOnSubsequentLayouts() {
        val sentinel = FirstLayoutSentinel()
        var calls = 0
        sentinel.onSize(320, 480) { _, _ -> calls++ }
        sentinel.onSize(500, 700) { _, _ -> calls++ }
        assertEquals(1, calls, "sentinel must fire exactly once even after resize")
    }

    @Test fun sentinelDoesNotFireOnZeroSizedLayout() {
        val sentinel = FirstLayoutSentinel()
        var calls = 0
        sentinel.onSize(0, 0) { _, _ -> calls++ }
        sentinel.onSize(100, 0) { _, _ -> calls++ }
        assertEquals(0, calls)
        assertFalse(sentinel.hasFired)
    }

    @Test fun sentinelIsSpentAfterFiring() {
        val sentinel = FirstLayoutSentinel()
        sentinel.onSize(100, 100) { _, _ -> }
        assertTrue(sentinel.hasFired, "a spent sentinel stays quiet: the host layout is untouched")
    }

    @Test fun sizeClassStringPinsWireValues() {
        assertEquals("compact", sizeClassString(390, 600))
        assertEquals("regular", sizeClassString(800, 600))
        assertEquals("unspecified", sizeClassString(0, 600))
    }

    @Test fun deviceIdiomStringPinsWireValues() {
        assertEquals("phone", deviceIdiomString(android.content.res.Configuration.UI_MODE_TYPE_NORMAL, 411))
        assertEquals("pad", deviceIdiomString(android.content.res.Configuration.UI_MODE_TYPE_NORMAL, 800))
        assertEquals("tv", deviceIdiomString(android.content.res.Configuration.UI_MODE_TYPE_TELEVISION, 540))
        assertEquals("carPlay", deviceIdiomString(android.content.res.Configuration.UI_MODE_TYPE_CAR, 400))
        assertEquals("unspecified", deviceIdiomString(android.content.res.Configuration.UI_MODE_TYPE_NORMAL, 0))
    }
}
