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

internal fun desktopInputPermitted(rights: Long, viewOnly: Boolean): Boolean {
    if (viewOnly) return false
    val mask = rights and 0xffffffffL
    return mask == 0xffffffffL || ((mask and 8L) != 0L && (mask and 256L) == 0L)
}

// A stale UI action must never resolve whichever request happens to be shown next.
internal class ConsentRequests<T> {
    private val pending = LinkedHashMap<String, T>()

    fun add(id: String, request: T) {
        if (!pending.containsKey(id)) pending[id] = request
    }
    fun remove(id: String): T? = pending.remove(id)
    fun first(): T? = pending.values.firstOrNull()
    fun contains(id: String): Boolean = pending.containsKey(id)
}
