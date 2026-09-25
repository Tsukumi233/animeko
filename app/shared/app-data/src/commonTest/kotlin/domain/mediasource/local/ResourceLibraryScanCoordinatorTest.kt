package me.him188.ani.app.domain.mediasource.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceLibraryScanCoordinatorTest {
    private fun root(id: String = "a", completed: Long? = null) = LibraryScanRootEntity(id, "source", "{}", id, lastCompletedMillis = completed)

    @Test fun `two callers join app task and cancelling a waiter leaves work alive`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var starts = 0
        var cleaned = false
        val coordinator = ResourceLibraryScanCoordinator(backgroundScope, { listOf(root()) }, { root() }, {
            starts++
            try { gate.await(); true } finally { cleaned = true }
        })
        val first = async { coordinator.request("a") }
        val second = async { coordinator.request("a") }
        runCurrent()
        assertEquals(1, starts)
        assertEquals(setOf("a"), coordinator.activeRootIds.value)
        first.cancelAndJoin()
        assertFalse(cleaned)
        assertFalse(second.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertEquals(true, second.await())
        assertTrue(cleaned)
        assertTrue(coordinator.activeRootIds.value.isEmpty())
    }

    @Test fun `explicit cancel cleans up before immediately retrying`() = runTest {
        var starts = 0
        var cleaned = false
        val coordinator = ResourceLibraryScanCoordinator(backgroundScope, { listOf(root()) }, { root() }, {
            if (++starts == 1) try { awaitCancellation() } finally { cleaned = true }
            true
        })
        val first = async { coordinator.request("a") }
        runCurrent()
        coordinator.cancel("a")
        assertTrue(cleaned)
        runCurrent()
        assertTrue(first.isCancelled)
        assertTrue(coordinator.activeRootIds.value.isEmpty())
        val second = async { coordinator.request("a") }
        runCurrent()
        assertEquals(true, second.await())
        assertEquals(2, starts)
    }

    @Test fun `foreground checks only saved stale roots and never schedules a timer`() = runTest {
        var time = 1_000_000L
        val saved = mutableMapOf("fresh" to root("fresh", time), "old" to root("old", 0), "first" to root("first"))
        val scanned = mutableListOf<String>()
        val coordinator = ResourceLibraryScanCoordinator(backgroundScope, { saved.values.toList() }, { saved[it] }, {
            scanned += it.id
            saved[it.id] = it.copy(lastCompletedMillis = time)
            true
        }, now = { time })
        coordinator.refreshStaleOnForeground()
        runCurrent()
        assertEquals(setOf("old", "first"), scanned.toSet())
        coordinator.refreshStaleOnForeground()
        runCurrent()
        assertEquals(2, scanned.size)
        time += 900_000
        advanceTimeBy(900_000)
        runCurrent()
        assertEquals(2, scanned.size)
        coordinator.refreshStaleOnForeground()
        runCurrent()
        assertEquals(5, scanned.size)
    }

    @Test fun `failure cooldown starts at completion and manual retry bypasses it`() = runTest {
        var time = 1_000_000L
        var starts = 0
        val coordinator = ResourceLibraryScanCoordinator(backgroundScope, { listOf(root()) }, { root() }, {
            starts++
            time += 120_000
            error("Offline")
        }, now = { time })
        coordinator.refreshStaleOnForeground()
        runCurrent()
        coordinator.refreshStaleOnForeground()
        runCurrent()
        assertEquals(1, starts)
        val manual = async { runCatching { coordinator.request("a") } }
        runCurrent()
        assertTrue(manual.await().isFailure)
        assertEquals(2, starts)
        time += 60_000
        coordinator.refreshStaleOnForeground()
        runCurrent()
        assertEquals(3, starts)
    }

    @Test fun `bounded workers reload queued scope and omit roots removed while waiting`() = runTest {
        val saved = mutableMapOf("a" to root(), "b" to root("b"))
        val gate = CompletableDeferred<Unit>()
        val scanned = mutableListOf<String>()
        val coordinator = ResourceLibraryScanCoordinator(backgroundScope, { saved.values.toList() }, { saved[it] }, {
            scanned += it.id
            gate.await()
            true
        }, parallelism = 1)
        val first = async { coordinator.request("a") }
        runCurrent()
        val removed = async { coordinator.request("b") }
        runCurrent()
        assertEquals(listOf("a"), scanned)
        saved.remove("b")
        gate.complete(Unit)
        runCurrent()
        assertEquals(true, first.await())
        assertNull(removed.await())
        assertEquals(listOf("a"), scanned)
    }
    @Test fun `interrupted persisted token refreshes even when the last successful scan is recent`() = runTest {
        var saved = root(completed = 1_000_000).copy(activeScanToken = "previous-process")
        var starts = 0
        val coordinator = ResourceLibraryScanCoordinator(backgroundScope, { listOf(saved) }, { saved }, {
            starts++
            saved = it.copy(activeScanToken = null)
            true
        }, now = { 1_000_000 })
        coordinator.refreshStaleOnForeground()
        runCurrent()
        assertEquals(1, starts)
        coordinator.refreshStaleOnForeground()
        runCurrent()
        assertEquals(1, starts)
    }
    @Test fun `cancelling before dispatch does not leave a permanently cancelled root task`() = runTest {
        var starts = 0
        val coordinator = ResourceLibraryScanCoordinator(backgroundScope, { listOf(root()) }, { root() }, { starts++; true })
        val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request("a") }
        coordinator.cancel("a")
        runCurrent()
        assertTrue(first.isCancelled)
        assertEquals(0, starts)
        assertTrue(coordinator.activeRootIds.value.isEmpty())
        val retry = async { coordinator.request("a") }
        runCurrent()
        assertEquals(true, retry.await())
        assertEquals(1, starts)
    }
}
