package org.sunsetware.phocid.stats

import android.content.Context
import android.net.Uri
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

    fun createRecorder(): PlaybackStatsRecorder =
        PlaybackStatsRecorder(database, resolver)

    suspend fun clear() {
        backupManager.clear()
        resolver.invalidate()
    }

    fun invalidateResolver() {
        resolver.invalidate()
    }
}
