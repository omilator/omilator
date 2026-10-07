package com.omilator.core.libretro.api

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pass C finding 1: iOS routes every native callback through shared
 * trampoline state, so two core-controller lifecycles must never overlap
 * (the old session's NonCancellable teardown races the next session's
 * load when the deep-link poller fires within one 100ms tick of exit).
 * NativeCoreController acquires this guard in loadCore and releases it at
 * the end of unloadCore; these tests pin the serialization property the
 * controller relies on.
 *
 * Every session acquires with its OWN token: kotlinx's owner-keyed Mutex
 * suspends a lock attempt while a different owner holds but THROWS for a
 * repeated same-owner lock — a shared owner key made back-to-back
 * sessions' loadCore throw IllegalStateException instead of waiting
 * (caught by the first test below on desktop).
 */
class CoreSessionGuardTest {

    @Test
    fun nextSessionWaitsForThePreviousSessionTeardown() = runBlocking {
        val guard = CoreSessionGuard()
        val session1 = guard.acquire() // session 1 loadCore

        var session2Loaded = false
        val session2 = launch {
            val token = guard.acquire() // session 2 loadCore — must WAIT, not throw
            session2Loaded = true
            guard.release(token) // session 2 unloadCore
        }
        // Session 1's teardown window (SRAM flash write + retro_unload_game
        // is tens of ms — the same order as the poller's tick).
        delay(200)
        assertFalse(session2Loaded, "session 2's load must not start while session 1 is live")
        assertTrue(session2.isActive, "session 2 must be suspended waiting, not crashed")

        guard.release(session1) // session 1 unloadCore completes
        withTimeout(1_000) { session2.join() }
        assertTrue(session2Loaded)
    }

    @Test
    fun failedLoadReleasesSoTheNextSessionIsNotWedged() = runBlocking {
        val guard = CoreSessionGuard()
        val token = guard.acquire()
        // The failure path of loadCore: release before rethrowing.
        guard.release(token)
        // The follow-up runCatching teardown calls unloadCore again — a
        // second release of the same (consumed) token must be a no-op,
        // not an error.
        guard.release(token)
        // And a completely stale token must not unlock the NEXT session.
        val next = guard.acquire()
        assertTrue(guard.isHeld(next), "the next session must hold the slot")
        guard.release(token) // stale defensive release from the failed session
        assertTrue(guard.isHeld(next), "a stale release must not unlock the next session")
        guard.release(next)
    }

    @Test
    fun isHeldReflectsAcquireRelease() = runBlocking {
        val guard = CoreSessionGuard()
        assertFalse(guard.isHeld(null))
        val token = guard.acquire()
        assertTrue(guard.isHeld(token))
        guard.release(token)
        assertFalse(guard.isHeld(token))
    }

    @Test
    fun anAcquireCancelledWhileWaitingLeavesNoResidue() = runBlocking {
        // A loadCore cancelled while suspended in acquire() (the user
        // backed out during the previous session's teardown) must leave
        // nothing held: the next session acquires immediately.
        val guard = CoreSessionGuard()
        val first = guard.acquire()
        val waiter = async { guard.acquire() }
        delay(50) // let it park on the mutex
        waiter.cancel()
        guard.release(first)
        val next = withTimeout(1_000) { guard.acquire() }
        guard.release(next)
    }
}
