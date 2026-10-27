package com.meshcentral.agent

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConsentCommandQueueTest {
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test
    fun holdsCommandsUntilApprovedAndPreservesArrivalOrder() {
        val executor = ManualExecutor()
        val queue = ConsentCommandQueue(executor)
        val received = mutableListOf<Int>()
        queue.submit { received.add(1) }
        queue.submit { received.add(2) }
        executor.runAll()
        assertTrue(received.isEmpty())

        queue.approve()
        queue.submit { received.add(3) }
        executor.runAll()
        assertEquals(listOf(1, 2, 3), received)
    }

    @Test
    fun arrivalsDuringReplayFollowAllHeldCommands() {
        val executor = Executors.newSingleThreadExecutor()
        val queue = ConsentCommandQueue(executor)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val received = mutableListOf<Int>()
        try {
            queue.submit {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                received.add(1)
            }
            queue.submit { received.add(2) }
            queue.approve()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.submit { received.add(3); finished.countDown() }
            resume.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2, 3), received)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun closingDiscardsPendingWorkAndRunsCleanupAfterCurrentWrite() {
        val executor = Executors.newSingleThreadExecutor()
        val queue = ConsentCommandQueue(executor)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val received = mutableListOf<String>()
        try {
            queue.submit {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                received.add("write")
            }
            queue.submit { received.add("discarded") }
            queue.approve()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.close { received.add("cleanup"); finished.countDown() }
            assertFalse(queue.submit { received.add("late") })
            queue.approve()
            resume.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("write", "cleanup"), received)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun denialDropsHeldCommands() {
        val executor = ManualExecutor()
        val queue = ConsentCommandQueue(executor)
        val received = mutableListOf<String>()
        queue.submit { received.add("command") }
        queue.close { received.add("cleanup") }
        queue.approve()
        executor.runAll()
        assertEquals(listOf("cleanup"), received)
    }

    @Test
    fun reportsOverflowInsteadOfSilentlyLosingCommands() {
        val queue = ConsentCommandQueue(ManualExecutor(), maxPendingCommands = 1, maxQueuedCommands = 2)
        assertTrue(queue.submit {})
        assertFalse(queue.submit {})
        queue.approve()
        assertTrue(queue.submit {})
        assertFalse(queue.submit {})
    }

    @Test
    fun commandFailureDoesNotStrandLaterWork() {
        val executor = ManualExecutor()
        val errors = mutableListOf<Exception>()
        val queue = ConsentCommandQueue(executor, onError = { errors.add(it) })
        var completed = false
        queue.submit { throw IllegalStateException("storage unavailable") }
        queue.submit { completed = true }
        queue.approve()
        executor.runAll()
        assertEquals(1, errors.size)
        assertTrue(completed)
    }
}
