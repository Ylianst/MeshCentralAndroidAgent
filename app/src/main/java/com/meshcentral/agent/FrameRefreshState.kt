package com.meshcentral.agent

import java.util.concurrent.atomic.AtomicLong

internal class FrameRefreshState {
    private val requested = AtomicLong(1)
    private var delivered = 0L

    fun request() { requested.incrementAndGet() }
    fun pending(): Long? = requested.get().takeIf { it != delivered }
    fun delivered(generation: Long) { delivered = generation }
}
