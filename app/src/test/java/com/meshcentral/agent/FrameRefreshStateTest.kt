package com.meshcentral.agent

import org.junit.Assert.*
import org.junit.Test

class FrameRefreshStateTest {
    @Test
    fun firstFrameRequiresAFullRefresh() {
        val state = FrameRefreshState()
        val frame = state.pending()!!
        state.delivered(frame)
        assertNull(state.pending())
    }

    @Test
    fun completingAnOlderFramePreservesNewRequests() {
        val state = FrameRefreshState()
        val encoding = state.pending()!!
        state.request()
        state.request()
        state.delivered(encoding)
        val next = state.pending()!!
        assertNotEquals(encoding, next)
        state.delivered(next)
        assertNull(state.pending())
    }

    @Test
    fun failedFrameLeavesRefreshPending() {
        val state = FrameRefreshState()
        val failed = state.pending()!!
        assertEquals(failed, state.pending())
        state.request()
        assertNotEquals(failed, state.pending())
    }
}
