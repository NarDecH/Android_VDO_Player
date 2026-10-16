package com.nardech.vdoplayer.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Getting this wrong sends files to the phone that is holding them, or to an address that
 * only exists once a group is up. Both are silent failures on a real device, so the rule —
 * a sender dials the group *owner*, and the owner receives — is pinned here.
 */
class DirectGroupPlannerTest {

    @Test
    fun `a sender dials the group owner`() {
        assertEquals(
            "http://192.168.49.1:45871",
            DirectGroupPlanner.senderBaseUrl(
                ownerAddress = "192.168.49.1",
                isGroupOwner = false,
                port = 45871,
            ),
        )
        assertEquals(
            "http://192.168.49.42:8080",
            DirectGroupPlanner.senderBaseUrl(
                ownerAddress = "192.168.49.42",
                isGroupOwner = false,
                port = 8080,
            ),
        )
    }

    @Test
    fun `the owner has nothing to dial`() {
        assertNull(
            DirectGroupPlanner.senderBaseUrl(
                ownerAddress = "192.168.49.1",
                isGroupOwner = true,
                port = 45871,
            ),
        )
    }

    @Test
    fun `an address that only exists inside a group is not treated as reachable`() {
        assertNull(DirectGroupPlanner.senderBaseUrl(null, isGroupOwner = false, port = 45871))
        assertNull(DirectGroupPlanner.senderBaseUrl("", isGroupOwner = false, port = 45871))
        assertNull(DirectGroupPlanner.senderBaseUrl("   ", isGroupOwner = false, port = 45871))
        assertNull(
            DirectGroupPlanner.senderBaseUrl("0.0.0.0", isGroupOwner = false, port = 45871),
        )
        assertNull(DirectGroupPlanner.senderBaseUrl("::", isGroupOwner = false, port = 45871))
    }

    @Test
    fun `the receiving phone falls back to the group owner address`() {
        assertEquals("192.168.49.1", DirectGroupPlanner.ownerAddress(null))
        assertEquals("192.168.49.1", DirectGroupPlanner.ownerAddress("0.0.0.0"))
        assertEquals("192.168.49.7", DirectGroupPlanner.ownerAddress("192.168.49.7"))
    }

    @Test
    fun `usable addresses are exactly the ones with content`() {
        assertTrue(DirectGroupPlanner.isUsableAddress("192.168.49.1"))
        assertFalse(DirectGroupPlanner.isUsableAddress(""))
        assertFalse(DirectGroupPlanner.isUsableAddress("  "))
        assertFalse(DirectGroupPlanner.isUsableAddress("0.0.0.0"))
        assertFalse(DirectGroupPlanner.isUsableAddress("::"))
    }
}
