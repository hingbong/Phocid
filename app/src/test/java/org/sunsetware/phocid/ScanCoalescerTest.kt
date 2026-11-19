package org.sunsetware.phocid

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.InvalidTrack
import org.sunsetware.phocid.data.UnfilteredTrackIndex
import org.sunsetware.phocid.data.coalesceUnchangedScan
import org.sunsetware.phocid.utils.ScanCoalescer

// InvalidTrack/UnfilteredTrackIndex touch Android types during class initialisation, so the
// plain JVM runner fails with ExceptionInInitializerError; the Robolectric sandbox supplies them.
@RunWith(RobolectricTestRunner::class)
class ScanCoalescerTest {

    // The first scan is held in flight via CompletableDeferreds, so a second request is
    // deterministically queued behind it before it completes.

    @Test
    fun redundantRequestIsCoalescedIntoTheInFlightScan() {
        runBlocking {
            val coalescer = ScanCoalescer()
            val scans = AtomicInteger(0)
            val scanStarted = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()

            // UNDISPATCHED so the request reaches the contended lock before the driver resumes.
            val first =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    coalescer.scan(false) {
                        scans.incrementAndGet()
                        scanStarted.complete(Unit)
                        finishScan.await()
                    }
                }
            scanStarted.await()

            val second =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    coalescer.scan(false) { scans.incrementAndGet() }
                }
            finishScan.complete(Unit)

            first.join()
            second.join()
            assertThat(scans.get()).isEqualTo(1)
        }
    }

    @Test
    fun forcedRequestRunsAfterTheInFlightScan() {
        runBlocking {
            val coalescer = ScanCoalescer()
            val scans = AtomicInteger(0)
            val scanStarted = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()

            // UNDISPATCHED so the request reaches the contended lock before the driver resumes.
            val first =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    coalescer.scan(false) {
                        scans.incrementAndGet()
                        scanStarted.complete(Unit)
                        finishScan.await()
                    }
                }
            scanStarted.await()

            val second =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    coalescer.scan(true) { scans.incrementAndGet() }
                }
            finishScan.complete(Unit)

            first.join()
            second.join()
            assertThat(scans.get()).isEqualTo(2)
        }
    }

    @Test
    fun failedScanIsRetriedByTheWaitingRequest() {
        runBlocking {
            val coalescer = ScanCoalescer()
            val scans = AtomicInteger(0)
            val scanStarted = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()

            // UNDISPATCHED so the request reaches the contended lock before the driver resumes.
            val first =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        coalescer.scan(false) {
                            scanStarted.complete(Unit)
                            finishScan.await()
                            throw IllegalStateException("scan failed")
                        }
                    }
                }
            scanStarted.await()

            val second =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    coalescer.scan(false) { scans.incrementAndGet() }
                }
            finishScan.complete(Unit)

            first.join()
            second.join()
            assertThat(scans.get()).isEqualTo(1)
        }
    }

    @Test
    fun failedInFlightScanTriggersOnlyOneCoalescedRetry() {
        runBlocking {
            val coalescer = ScanCoalescer()
            val scans = AtomicInteger(0)
            val scanStarted = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()
            val firstFailure = CompletableDeferred<Throwable>()
            val waiterResults = List(3) { CompletableDeferred<Boolean>() }

            // UNDISPATCHED so the request reaches the contended lock before the driver resumes.
            val first =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    firstFailure.complete(
                        runCatching {
                            coalescer.scan(false) {
                                scanStarted.complete(Unit)
                                finishScan.await()
                                throw IllegalStateException("scan failed")
                            }
                        }
                            .exceptionOrNull()!!
                    )
                }
            scanStarted.await()

            // All waiters park behind the in-flight scan before it fails, so they all observe the
            // same unchanged generation. Only one of them may retry; the rest must coalesce into
            // that retry instead of each running a duplicate full scan.
            val waiters = waiterResults.map { result ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    result.complete(coalescer.scan(false) { scans.incrementAndGet() })
                }
            }
            finishScan.complete(Unit)

            first.join()
            waiters.forEach { it.join() }
            assertThat(firstFailure.await()).isInstanceOf(IllegalStateException::class.java)
            assertThat(scans.get()).isEqualTo(1)
            assertThat(waiterResults.map { it.await() }).containsExactly(true, false, false)
        }
    }

    @Test
    fun failedRetryHandsOffToTheNextWaiterOnce() {
        runBlocking {
            val coalescer = ScanCoalescer()
            val scans = AtomicInteger(0)
            val scanStarted = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()
            val firstFailure = CompletableDeferred<Throwable>()
            val retryFailure = CompletableDeferred<Throwable>()
            val secondWaiterResult = CompletableDeferred<Boolean>()
            val thirdWaiterResult = CompletableDeferred<Boolean>()

            // UNDISPATCHED so the request reaches the contended lock before the driver resumes.
            val first =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    firstFailure.complete(
                        runCatching {
                            coalescer.scan(false) {
                                scanStarted.complete(Unit)
                                finishScan.await()
                                throw IllegalStateException("scan failed")
                            }
                        }
                            .exceptionOrNull()!!
                    )
                }
            scanStarted.await()

            // The first waiter's retry fails too: exactly the next waiter retries, and the third
            // coalesces into that successful retry. Each failure yields one retry, never N.
            val retryingWaiter =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    retryFailure.complete(
                        runCatching {
                            coalescer.scan(false) {
                                scans.incrementAndGet()
                                throw IllegalStateException("retry failed")
                            }
                        }
                            .exceptionOrNull()!!
                    )
                }
            val secondWaiter =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    secondWaiterResult.complete(coalescer.scan(false) { scans.incrementAndGet() })
                }
            val thirdWaiter =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    thirdWaiterResult.complete(coalescer.scan(false) { scans.incrementAndGet() })
                }
            finishScan.complete(Unit)

            first.join()
            retryingWaiter.join()
            secondWaiter.join()
            thirdWaiter.join()
            assertThat(firstFailure.await()).isInstanceOf(IllegalStateException::class.java)
            assertThat(retryFailure.await()).isInstanceOf(IllegalStateException::class.java)
            assertThat(scans.get()).isEqualTo(2)
            assertThat(secondWaiterResult.await()).isTrue()
            assertThat(thirdWaiterResult.await()).isFalse()
        }
    }

    @Test
    fun cancelledInFlightScanPropagatesAndRetriesOnce() {
        runBlocking {
            val coalescer = ScanCoalescer()
            val scans = AtomicInteger(0)
            val scanStarted = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()
            val firstFailure = CompletableDeferred<Throwable>()
            val waiterResults = List(2) { CompletableDeferred<Boolean>() }

            // UNDISPATCHED so the request reaches the contended lock before the driver resumes.
            val first =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    firstFailure.complete(
                        runCatching {
                            coalescer.scan(false) {
                                scanStarted.complete(Unit)
                                finishScan.await()
                                throw CancellationException("scan cancelled")
                            }
                        }
                            .exceptionOrNull()!!
                    )
                }
            scanStarted.await()

            val waiters = waiterResults.map { result ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    result.complete(coalescer.scan(false) { scans.incrementAndGet() })
                }
            }
            finishScan.complete(Unit)

            first.join()
            waiters.forEach { it.join() }
            // CancellationException must reach the caller unswallowed, and the waiters still
            // coalesce into a single retry.
            assertThat(firstFailure.await()).isInstanceOf(CancellationException::class.java)
            assertThat(scans.get()).isEqualTo(1)
            assertThat(waiterResults.map { it.await() }).containsExactly(true, false)
        }
    }

    @Test
    fun unchangedScanKeepsTheOldIndexInstance() {
        val old = UnfilteredTrackIndex("v", mapOf(InvalidTrack.id to InvalidTrack))
        // A scan that finds nothing changed reuses the old Track instances, so the result must
        // be the same instance with the same flowVersion: no new derived index is published.
        val result = coalesceUnchangedScan(old, "v", old.tracks)
        assertThat(result).isSameAs(old)
    }

    @Test
    fun changedScanPublishesANewIndex() {
        val old = UnfilteredTrackIndex("v", mapOf(InvalidTrack.id to InvalidTrack))
        val result =
            coalesceUnchangedScan(
                old,
                "v",
                mapOf(InvalidTrack.id to InvalidTrack.copy(title = "different")),
            )
        assertThat(result).isNotSameAs(old)
        assertThat(result.tracks.getValue(InvalidTrack.id).title).isEqualTo("different")
    }

    @Test
    fun mediastoreVersionBumpPublishesANewIndex() {
        val old = UnfilteredTrackIndex("v1", mapOf(InvalidTrack.id to InvalidTrack))
        val result = coalesceUnchangedScan(old, "v2", mapOf(InvalidTrack.id to InvalidTrack))
        assertThat(result).isNotSameAs(old)
        assertThat(result.version).isEqualTo("v2")
    }

    @Test
    fun firstScanHasNothingToCoalesce() {
        val result = coalesceUnchangedScan(null, "v", mapOf(InvalidTrack.id to InvalidTrack))
        assertThat(result.tracks).hasSize(1)
    }

    @Test
    fun changedParseFingerprintRestampsTheSurvivingIndex() {
        val old =
            UnfilteredTrackIndex("v", mapOf(InvalidTrack.id to InvalidTrack), parseFingerprint = 1)
        val result = coalesceUnchangedScan(old, "v", old.tracks, parseFingerprint = 2)
        assertThat(result).isNotSameAs(old)
        assertThat(result.parseFingerprint).isEqualTo(2L)
        assertThat(result.tracks).isEqualTo(old.tracks)
    }

    @Test
    fun unchangedParseFingerprintKeepsTheOldInstance() {
        val old =
            UnfilteredTrackIndex("v", mapOf(InvalidTrack.id to InvalidTrack), parseFingerprint = 1)
        val result = coalesceUnchangedScan(old, "v", old.tracks, parseFingerprint = 1)
        assertThat(result).isSameAs(old)
    }
}
