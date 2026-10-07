package com.omilator.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The close-path report bookkeeping (finding: Esc → close within the
 * request window used to lose the async playtime POST because
 * exitProcess(0) never joined it). Pure tracker over a test scope — no
 * window, no server.
 */
class PendingPlaytimeReportsTest {

    private fun newTracker(): Pair<PendingPlaytimeReports, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return PendingPlaytimeReports(scope) to scope
    }

    @Test
    fun awaitAllCompletesImmediatelyWithNothingPending() {
        val (tracker, scope) = newTracker()
        kotlinx.coroutines.runBlocking {
            assertTrue(tracker.awaitAll(timeoutMillis = 1_000))
        }
        scope.cancel()
    }

    @Test
    fun awaitAllJoinsInFlightReports() {
        val (tracker, scope) = newTracker()
        val completed = java.util.Collections.synchronizedList(mutableListOf<Int>())
        repeat(3) { i ->
            tracker.launch {
                kotlinx.coroutines.delay(50)
                completed += i
            }
        }
        kotlinx.coroutines.runBlocking {
            assertTrue(tracker.awaitAll(timeoutMillis = 5_000))
        }
        assertEquals(listOf(0, 1, 2), completed.sorted(), "all reports must complete before awaitAll returns")
        scope.cancel()
    }

    @Test
    fun awaitAllIsBoundedWhenASendHangs() {
        val (tracker, scope) = newTracker()
        tracker.launch {
            // A wedged server: connect/read hangs well past the budget.
            kotlinx.coroutines.delay(60_000)
        }
        val startedAt = System.currentTimeMillis()
        kotlinx.coroutines.runBlocking {
            assertFalse(tracker.awaitAll(timeoutMillis = 200), "budget expiry must be reported")
        }
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue(elapsed < 5_000, "awaitAll must return promptly, took ${elapsed}ms")
        scope.cancel()
    }

    @Test
    fun completedReportsDoNotBlockLaterAwaitAll() {
        val (tracker, scope) = newTracker()
        tracker.launch { kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.runBlocking { tracker.awaitAll(timeoutMillis = 5_000) }
        // Long session: many completed jobs must be pruned, not joined
        // forever / accumulated unboundedly.
        repeat(100) { tracker.launch { } }
        kotlinx.coroutines.runBlocking {
            assertTrue(tracker.awaitAll(timeoutMillis = 5_000))
        }
        scope.cancel()
    }
}
