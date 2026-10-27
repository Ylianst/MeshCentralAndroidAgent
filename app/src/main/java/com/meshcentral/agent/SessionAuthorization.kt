package com.meshcentral.agent

internal class SessionAuthorization {
    private enum class State { NEW, PENDING, APPROVED, CLOSED }
    @Volatile private var state = State.NEW

    val isApproved: Boolean get() = state == State.APPROVED
    val isPending: Boolean get() = state == State.PENDING
    val isClosed: Boolean get() = state == State.CLOSED

    @Synchronized
    fun begin(promptRequired: Boolean) {
        if (state == State.NEW) state = if (promptRequired) State.PENDING else State.APPROVED
    }

    @Synchronized
    fun approve(): Boolean {
        if (state != State.PENDING) return false
        state = State.APPROVED
        return true
    }

    @Synchronized
    fun close(): Boolean {
        if (state == State.CLOSED) return false
        state = State.CLOSED
        return true
    }
}
