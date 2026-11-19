package org.sunsetware.phocid

import java.util.concurrent.atomic.AtomicInteger
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

            val second = launch(start = CoroutineStart.UNDISPATCHED) { coalescer.scan(false) { scans.incrementAndGet() } }
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

            val second = launch(start = CoroutineStart.UNDISPATCHED) { coalescer.scan(true) { scans.incrementAndGet() } }
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

            val second = launch(start = CoroutineStart.UNDISPATCHED) { coalescer.scan(false) { scans.incrementAndGet() } }
            finishScan.complete(Unit)

            first.join()
            second.join()
            assertThat(scans.get()).isEqualTo(1)
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