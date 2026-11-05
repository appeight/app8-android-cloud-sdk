package dev.app8.cloud

import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.data.AttributeBag
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttributeBagTest {
    private fun makeBag() = AttributeBag(Diagnostics.disabled)

    @Test fun initialBagIsEmpty() {
        assertTrue(makeBag().snapshot.isEmpty())
    }

    @Test fun setAttributesMerges() {
        val bag = makeBag()
        bag.setAttributes(mapOf("userId" to "u1", "plan" to "pro"))
        assertEquals("u1", bag.snapshot["userId"])
        assertEquals("pro", bag.snapshot["plan"])
        bag.setAttributes(mapOf("plan" to "enterprise", "country" to "US"))
        assertEquals("u1", bag.snapshot["userId"], "userId should persist across calls")
        assertEquals("enterprise", bag.snapshot["plan"], "later keys should override")
        assertEquals("US", bag.snapshot["country"])
    }

    @Test fun reservedKeyIsRejected() {
        val bag = makeBag()
        val rejected = bag.setAttributes(mapOf("userId" to "u1", "\$deviceId" to "no", "\$debug" to "no"))
        assertEquals(setOf("\$deviceId", "\$debug"), rejected.toSet())
        assertNull(bag.snapshot["\$deviceId"], "reserved keys must not be stored")
        assertNull(bag.snapshot["\$debug"])
        assertEquals("u1", bag.snapshot["userId"], "non-reserved keys still merge")
    }

    @Test fun clearAttributesRemovesAll() {
        val bag = makeBag()
        bag.setAttributes(mapOf("userId" to "u1", "plan" to "pro", "country" to "US"))
        bag.clearAttributes()
        assertTrue(bag.snapshot.isEmpty())
    }
}
