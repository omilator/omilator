package com.omilator.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
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

    /**
     * Pass C finding 4: the awaited close-path send must be bounded by
     * JOINING it. The production send suspends inside
     * withContext(Dispatchers.IO) blocked on an HttpURLConnection socket
     * (10s connect / 30s read) — cooperative cancellation cannot interrupt
     * that, so the old inline `withTimeoutOrNull { send() }` shape could
     * not resume until the SOCKET budget expired and beachballed the close
     * handler. Thread.sleep inside Dispatchers.IO is the same
     * non-cancellable shape; join() returns at the timeout regardless.
     *
     * Regression pin for the launch mode too: the send must run as a
     * QUEUED coroutine on the IO scope, never UNDISPATCHED on the caller —
     * withContext skips dispatch when the target interceptor equals the
     * coroutine's own (the IO scope), so an undispatched start would run
     * the blocking body INLINE on the close-handler thread before join()
     * even begins, and this test would observe elapsed >= sleep time.
     */
    @Test
    fun awaitedCloseSendIsBoundedAgainstBlockingIo() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tracker = PendingPlaytimeReports(scope)
        val started = java.util.concurrent.atomic.AtomicBoolean(false)

        val startedAt = System.currentTimeMillis()
        val sent = tracker.sendAwaiting({
            started.set(true)
            withContext(Dispatchers.IO) { Thread.sleep(15_000) }
        }, timeoutMillis = 200)
        val elapsed = System.currentTimeMillis() - startedAt

        assertFalse(sent, "a wedged request must be reported as not-sent at budget expiry")
        assertTrue(elapsed < 5_000, "the close budget must be enforced against blocking IO, took ${elapsed}ms")
        // The send must actually have STARTED (queued on the IO pool, not
        // stranded): poll briefly — dispatch latency is microseconds.
        val deadline = System.currentTimeMillis() + 2_000
        while (!started.get() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(started.get(), "the send must have entered its IO phase on the tracker's pool")
        scope.cancel()
    }

    /**
     * Pass C finding 6: an Esc-fired report runs on the tracker's own IO
     * scope — NOT the Compose application scope. The close handler blocks
     * the Main/EDT thread that fired the Esc report inside
     * runBlocking { awaitAll }; a report tracked on that same dispatcher
     * could never start (deterministically dropped after burning the
     * whole budget, even with a healthy server). Here the launcher/close
     * thread is a single-threaded executor playing the EDT while the
     * tracker rides the IO pool: the report must start AND complete while
     * that thread is parked.
     */
    @Test
    fun queuedEscReportStillCompletesWhenTheCloseHandlerBlocksTheLauncherThread() {
        val edt = java.util.concurrent.Executors
            .newSingleThreadExecutor { r -> Thread(r, "close-handler-scope").apply { isDaemon = true } }
            .asCoroutineDispatcher()
        try {
            // The tracker's production scope: its own pool, never the
            // blocked launcher thread.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val tracker = PendingPlaytimeReports(scope)
            val started = java.util.concurrent.atomic.AtomicBoolean(false)
            val finished = java.util.concurrent.CountDownLatch(1)
            val awaitResult = booleanArrayOf(false)

            // The single EDT thread executes Esc (launch the async report)
            // and then the close handler (bounded await) back to back —
            // exactly the production interleaving on the EDT.
            edt.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
                tracker.launch {
                    started.set(true)
                    // Production shape: the send's body suspends on a
                    // different dispatcher (the IO/network phase), so the
                    // blocked launcher thread cannot deadlock the report.
                    withContext(Dispatchers.Default) { kotlinx.coroutines.delay(50) }
                }
                awaitResult[0] = kotlinx.coroutines.runBlocking {
                    tracker.awaitAll(timeoutMillis = 2_000)
                }
                finished.countDown()
            })

            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS), "close handler must return")
            assertTrue(started.get(), "the report must have started, not stranded behind the blocked launcher thread")
            assertTrue(awaitResult[0], "the report must have completed within the budget")
            scope.cancel()
        } finally {
            edt.close()
        }
    }
}
