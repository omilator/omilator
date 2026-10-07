package com.omilator.core.libretro.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex

/**
 * Single-flight guard for platform core controllers whose native
 * callbacks resolve through shared state instead of per-handle context
 * (iOS: every env/video/audio trampoline resolves the thread-local
 * `nativeControllerInstance`, which is set around each native call — two
 * overlapping controller lifecycles race that target: the old session's
 * `unloadCore` can null it mid-init of the new core, or the old teardown's
 * callbacks route into the NEW controller's sinks).
 *
 * One process-wide instance (held in the platform controller's companion)
 * serializes core sessions: `loadCore` acquires and `unloadCore` releases,
 * so session N+1's load cannot begin until session N's teardown (SRAM
 * flush → detach → unloadGame → unloadCore) completed. Compose cancels a
 * leaving screen's effect without joining its NonCancellable teardown,
 * and iOS's deep-link poller can fire the next session within one 100 ms
 * tick of exit — without the guard those two coroutines coexist on
 * Dispatchers.Default workers with no happens-before between them.
 *
 * Android's JNI bridge is immune by construction (per-handle CoreState
 * behind a thread-local active guard) and does not use this class.
 *
 * [acquire] mints a per-session [Token] that every [release] of that
 * session must present. The token is the mutex's owner key: kotlinx's
 * owner-keyed Mutex SUSPENDS a lock attempt while a different owner
 * holds (the serialization this guard exists for) but THROWS
 * IllegalStateException for a repeated lock by the SAME owner — a shared
 * owner constant would make every back-to-back session's loadCore throw
 * instead of waiting, so uniqueness is load-bearing, not cosmetic. It
 * also makes [release] safe to call from overlapping failure paths:
 * releasing a token the caller no longer holds (another session acquired
 * in between) is a `holdsLock` no-op, never a foreign unlock.
 *
 * The token must be released on every path out of a session:
 *  - `loadCore` releases before rethrowing its own failures;
 *  - `unloadCore` releases at its tail — gated on the controller still
 *    holding the token, so the runCatching-wrapped teardown that follows
 *    a failed load cannot double-release.
 */
class CoreSessionGuard {

    /** Per-session ownership key; opaque, identity-compared by the mutex. */
    class Token internal constructor()

    private val mutex = Mutex()

    /** Suspends until every previous session's teardown completed. */
    suspend fun acquire(): Token {
        val token = Token()
        mutex.lock(token)
        return token
    }

    /** No-op when [token] is not the current holder — defensive release
     *  calls on overlapping failure paths cannot unlock the next session
     *  and cannot throw. [holdsLock] has been API-stable since
     *  kotlinx.coroutines 1.1; its experimental marker is opted in here
     *  and nowhere else. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun release(token: Token?) {
        if (token != null && mutex.holdsLock(token)) mutex.unlock(token)
    }

    /** Whether [token] still holds this guard. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun isHeld(token: Token?): Boolean =
        token != null && mutex.holdsLock(token)
}
