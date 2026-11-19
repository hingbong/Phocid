package org.sunsetware.phocid.utils

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises scan requests and coalesces redundant ones.
 *
 * A request that arrives while a scan is already in flight waits for that scan to finish instead of
 * queueing another full scan behind it, so rapid repeat triggers (activity RESUMED events, a
 * permission grant racing a lifecycle trigger) cannot multiply the work. Requests with [scan]'s
 * [force] flag still run after the in-flight scan completes, because a manual rescan must not be
 * silently dropped. If the in-flight scan fails without completing, exactly one waiter retries
 * under the scan lock and the remaining waiters coalesce into that retry, so a failure cannot
 * stampede every waiter into its own duplicate full scan.
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
        // Wait for the in-flight scan, then re-check the generation and run any needed scan in
        // the SAME critical section. A failed in-flight scan leaves the generation unchanged, so
        // the first waiter to acquire the lock retries; its retry bumps the generation before the
        // lock is released, so every later waiter observes a completed scan and coalesces instead
        // of queueing its own duplicate retry. If the retry itself fails, the generation again
        // stays unchanged and exactly the next waiter retries.
        return mutex.withLock {
            if (force || generation.get() == generationBeforeQueueing) {
                scanWork()
                generation.incrementAndGet()
                true
            } else {
                false
            }
        }
    }
}
