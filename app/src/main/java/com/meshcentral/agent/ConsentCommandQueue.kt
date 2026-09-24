package com.meshcentral.agent

import java.util.concurrent.Executor

internal class ConsentCommandQueue(
    private val executor: Executor,
    private val maxPendingCommands: Int = 32,
    private val maxQueuedCommands: Int = 256,
    private val onError: (Exception) -> Unit = { it.printStackTrace() }
) {
    private val lock = Any()
    private val commands = ArrayDeque<() -> Unit>()
    private var approved = false
    private var closed = false
    private var draining = false

    fun submit(command: () -> Unit): Boolean = synchronized(lock) {
        if (closed || commands.size >= if (approved) maxQueuedCommands else maxPendingCommands) {
            return false
        }
        commands.addLast(command)
        scheduleDrain()
        true
    }

    fun approve() = synchronized(lock) {
        if (closed) return
        approved = true
        scheduleDrain()
    }

    fun close(cleanup: () -> Unit) = synchronized(lock) {
        if (closed) return
        closed = true
        commands.clear()
        // Cleanup follows the current command on the same executor, including a disk write in progress.
        executor.execute(cleanup)
    }

    private fun scheduleDrain() {
        if (!approved || draining || commands.isEmpty()) return
        draining = true
        executor.execute {
            while (true) {
                val command = synchronized(lock) {
                    if (closed || commands.isEmpty()) {
                        draining = false
                        null
                    } else {
                        commands.removeFirst()
                    }
                } ?: break
                try {
                    command()
                } catch (ex: Exception) {
                    onError(ex)
                }
            }
        }
    }
}
