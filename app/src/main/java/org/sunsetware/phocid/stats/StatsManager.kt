package org.sunsetware.phocid.stats

import android.content.Context
import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sunsetware.phocid.stats.backup.StatsBackup
import org.sunsetware.phocid.stats.backup.StatsBackupManager
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.domain.StatsRepository
import org.sunsetware.phocid.stats.fingerprint.AudioFingerprint
import org.sunsetware.phocid.stats.recorder.PlaybackStatsRecorder
import org.sunsetware.phocid.stats.resolver.TrackResolver
import org.sunsetware.phocid.stats.resolver.defaultMediaRoots

/** Process-wide entry point for the Room-backed statistics store. */
class StatsManager(context: Context) {
    private val appContext = context.applicationContext
    val database: StatsDatabase = StatsDatabase.get(appContext)
    val resolver: TrackResolver =
        TrackResolver(
            database,
            fingerprint = { meta ->
                AudioFingerprint.compute(appContext.contentResolver, Uri.parse(meta.uri))
                    ?: meta.file?.let { AudioFingerprint.compute(it) }
            },
            mediaRoots = { defaultMediaRoots(appContext) },
        )
    val repository: StatsRepository = StatsRepository(database)
    val backupManager: StatsBackupManager = StatsBackupManager(database)
    private val recorders = CopyOnWriteArrayList<PlaybackStatsRecorder>()

    /**
     * One store-wide lock for every aggregate mutation. Recorders take it (then their writer lock)
     * around each transaction; clear/restore keep it across the database change and the recorder
     * invalidation. That closes the window where a flush could commit pending time against data
     * that was just cleared.
     *
     * Lock order is always storeMutex -> recorder.writerMutex. Neither is ever acquired in the
     * opposite order.
     */
    private val storeMutex = Mutex()

    fun createRecorder(): PlaybackStatsRecorder =
        PlaybackStatsRecorder(database, resolver, storeMutex = storeMutex).also {
            recorders += it
            // Recover shutdown outbox rows off the main thread. The job is tracked by the recorder
            // and joined by awaitSettledForTest().
            it.startOutboxRecovery()
        }

    suspend fun clear() {
        storeMutex.withLock {
            // clear() also deletes pending outbox rows in the same transaction, so discarded work
            // cannot be recovered after the user deleted it.
            backupManager.clear()
            resolver.invalidate()
            // Recorders may still hold accrual or row ids that referred to the cleared data.
            for (recorder in recorders) recorder.onStoreClearedLocked()
        }
    }

    /**
     * Restores [payload]. Its identities are matched against local rows, which can create new rows
     * or move existing aggregates, so every recorder must re-resolve before its next write.
     *
     * A replace restore clears the outbox with the aggregate set; a merge keeps it because the
     * local aggregates it will apply to are still there.
     */
    suspend fun restore(payload: StatsBackup, replace: Boolean) {
        storeMutex.withLock {
            backupManager.restore(payload, replace)
            resolver.invalidate()
            for (recorder in recorders) recorder.onStoreRestoredLocked(replace)
        }
    }

    fun invalidateResolver() {
        resolver.invalidate()
    }
}
