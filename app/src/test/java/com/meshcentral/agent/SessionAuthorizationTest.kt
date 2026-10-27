package com.meshcentral.agent

import org.junit.Assert.*
import org.junit.Test

class SessionAuthorizationTest {
    @Test
    fun approvalBelongsToOneSession() {
        val first = SessionAuthorization()
        val second = SessionAuthorization()
        first.begin(true)
        second.begin(true)

        assertFalse(first.isApproved)
        assertTrue(first.approve())
        assertTrue(first.isApproved)
        assertTrue(second.isPending)
        assertFalse(second.isApproved)
    }

    @Test
    fun closedSessionsCannotBeApprovedOrReopened() {
        val session = SessionAuthorization()
        session.begin(true)
        assertTrue(session.close())
        assertFalse(session.approve())
        session.begin(false)
        assertTrue(session.isClosed)
        assertFalse(session.isApproved)
        assertFalse(session.close())
    }

    @Test
    fun closingRevokesAutomaticApproval() {
        val session = SessionAuthorization()
        session.begin(false)
        assertTrue(session.isApproved)
        session.close()
        assertFalse(session.isApproved)
    }

    @Test
    fun inputRequiresControlRightsAndHonorsViewOnly() {
        assertFalse(desktopInputPermitted(0, false))
        assertFalse(desktopInputPermitted(256, false))
        assertFalse(desktopInputPermitted(8 or 256, false))
        assertTrue(desktopInputPermitted(8, false))
        assertTrue(desktopInputPermitted(0xffffffffL, false))
        assertTrue(desktopInputPermitted(-1, false))
        assertFalse(desktopInputPermitted(8, true))
        assertFalse(desktopInputPermitted(0xffffffffL, true))
        assertFalse(desktopInputPermitted(-1, true))
    }

    @Test
    fun staleResponsesLeaveTheNextRequestPending() {
        val requests = ConsentRequests<SessionAuthorization>()
        val first = SessionAuthorization().apply { begin(true) }
        val second = SessionAuthorization().apply { begin(true) }
        requests.add("first", first)
        requests.add("second", second)
        assertSame(first, requests.first())

        requests.remove("first")?.approve()
        assertSame(second, requests.first())
        assertNull(requests.remove("first"))
        assertTrue(second.isPending)
        assertFalse(second.isApproved)
    }

    @Test
    fun timeoutResolutionOnlyRemovesItsOwnRequest() {
        val requests = ConsentRequests<String>()
        requests.add("shown", "first")
        requests.add("waiting", "second")
        assertEquals("second", requests.remove("waiting"))
        assertEquals("first", requests.first())
        assertTrue(requests.contains("shown"))
    }
}
