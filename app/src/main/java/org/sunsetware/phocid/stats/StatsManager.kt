package org.sunsetware.phocid.stats

import android.content.Context
import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList
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

    fun createRecorder(): PlaybackStatsRecorder =
        PlaybackStatsRecorder(database, resolver).also { recorders += it }

    suspend fun clear() {
        backupManager.clear()
        resolver.invalidate()
        // Recorders may still hold accrual or row ids that referred to the cleared data.
        for (recorder in recorders) recorder.onStoreCleared()
    }

    /**
     * Restores [payload]. Its identities are matched against local rows, which can create new rows
     * or move existing aggregates, so every recorder must re-resolve before its next write.
     */
    suspend fun restore(payload: StatsBackup, replace: Boolean) {
        backupManager.restore(payload, replace)
        resolver.invalidate()
        for (recorder in recorders) recorder.onStoreRestored(replace)
    }

    fun invalidateResolver() {
        resolver.invalidate()
    }
}
