package org.sunsetware.phocid.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * This cache is only designed to have a very small size, due to lookup having O(n) complexity.
 *
 * The entry list is an immutable snapshot held in an [AtomicReference]. All reads and mutations are
 * lock-free CAS loops, so [get] never blocks the caller thread: it is safe to call directly from
 * composition on the main thread. The previous implementation used `runBlocking` plus a `Mutex`,
 * which could stall the main thread whenever a creation or eviction coroutine held the mutex on
 * [Dispatchers.IO].
 */
class AsyncCache<TKey : Any, TValue : Any>(
    private val coroutineScope: CoroutineScope,
    val size: Int,
) {
    /** Immutable LRU snapshot, ordered from least- to most-recently-used. */
    private val entries = AtomicReference(emptyList<Pair<TKey, TValue>>())
    private val evictionJob = AtomicReference(null as Job?)
    private val creationJobs = ConcurrentHashMap<TKey, Deferred<TValue>>()

    val actualSize: Int
        get() = entries.get().size

    suspend fun getOrPut(key: TKey, create: (TKey) -> TValue): TValue {
        // CAS loop: find the entry and, if present, move it to the most-recently-used end.
        var existing: TValue? = null
        while (true) {
            val snapshot = entries.get()
            val index = snapshot.indexOfFirst { it.first == key }
            if (index < 0) {
                // The entry may have been evicted between CAS attempts; do not return a stale
                // value from a previous iteration.
                existing = null
                break
            }
            existing = snapshot[index].second
            val reordered = snapshot.toMutableList()
            reordered += reordered.removeAt(index)
            if (entries.compareAndSet(snapshot, reordered)) break
        }
        existing?.let {
            return it
        }

        val newCreationJob =
            coroutineScope.async(start = CoroutineStart.LAZY) {
                withContext(Dispatchers.IO) {
                    val value = create(key)
                    // CAS loop: replace any existing row for this key, then append as newest.
                    while (true) {
                        val snapshot = entries.get()
                        val next = snapshot.filterNot { it.first == key } + (key to value)
                        if (entries.compareAndSet(snapshot, next)) break
                    }
                    creationJobs.remove(key)
                    value
                }
            }
        val creationJob = creationJobs.getOrPut(key) { newCreationJob }
        if (creationJob != newCreationJob) {
            newCreationJob.cancel()
        }
        // A failed or cancelled creation must not stay in the map: every later getOrPut would
        // await the dead job, poisoning the key forever. This also runs for successful jobs,
        // where the coroutine's own removal below has already taken care of it.
        creationJob.invokeOnCompletion { creationJobs.remove(key, creationJob) }

        val newEvictionJob = getEvictionJob()
        if (evictionJob.compareAndSet(null, newEvictionJob)) {
            newEvictionJob.start()
        } else {
            newEvictionJob.cancel()
        }

        return creationJob.await()
    }

    /**
     * Non-blocking LRU read. Returns the cached value for [key] (renewing its recency) or null.
     * Never suspends and never blocks on I/O, so it is safe to call from composition.
     */
    fun get(key: TKey): TValue? {
        while (true) {
            val snapshot = entries.get()
            val index = snapshot.indexOfFirst { it.first == key }
            if (index < 0) return null
            val value = snapshot[index].second
            val reordered = snapshot.toMutableList()
            reordered += reordered.removeAt(index)
            if (entries.compareAndSet(snapshot, reordered)) return value
        }
    }

    private fun getEvictionJob(): Job {
        val job =
            coroutineScope.launch(start = CoroutineStart.LAZY) {
                withContext(Dispatchers.IO) {
                    // CAS loop: keep only the newest [size] entries.
                    while (true) {
                        val snapshot = entries.get()
                        val next = if (snapshot.size > size) snapshot.takeLast(size) else snapshot
                        if (entries.compareAndSet(snapshot, next)) break
                    }
                }
            }
        // Clear the slot on *any* completion, including cancellation. The previous code cleared
        // it only from inside the coroutine body, so a cancelled eviction job disabled eviction
        // permanently for the lifetime of the cache.
        job.invokeOnCompletion { evictionJob.compareAndSet(job, null) }
        return job
    }
}
