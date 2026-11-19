package org.sunsetware.phocid.utils

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises scan requests and coalesces redundant ones.
 *
 * A request that arrives while a scan is already in flight waits for that scan to finish instead
 * of queueing another full scan behind it, so rapid repeat triggers (activity RESUMED events, a
 * permission grant racing a lifecycle trigger) cannot multiply the work. Requests with [scan]'s
 * [force] flag still run after the in-flight scan completes, because a manual rescan must not be
 * silently dropped. If the in-flight scan fails, waiters run their own scan, so a failure is
 * retried instead of being reported as done.
 */
class ScanCoalescer {
    private val mutex = Mutex()

    /** Bumped after every completed scan; lets waiters detect that they were coalesced. */
    private val generation = AtomicLong(0)

    /**
     * Runs [scanWork] under the scan lock when this request must perform a scan itself. Returns
     * true when this call ran the work, false when it was coalesced into a scan that completed
     * while this request waited.
     */
    suspend fun scan(force: Boolean, scanWork: suspend () -> Unit): Boolean {
        val generationBeforeQueueing = generation.get()
        if (mutex.tryLock()) {
            try {
                scanWork()
                generation.incrementAndGet()
            } finally {
                mutex.unlock()
            }
            return true
        }
        // Wait for the in-flight scan to finish. The empty critical section only parks this
        // coroutine until the lock is free; it holds no state.
        mutex.withLock {}
        if (force || generation.get() == generationBeforeQueueing) {
            // Either the caller explicitly asked for a fresh scan, or the in-flight scan failed
            // without completing: run one ourselves.
            mutex.withLock {
                scanWork()
                generation.incrementAndGet()
            }
            return true
        }
        return false
    }
}
