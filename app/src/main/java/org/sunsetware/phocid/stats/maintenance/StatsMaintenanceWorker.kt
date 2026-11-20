package org.sunsetware.phocid.stats.maintenance

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.StatsRetention

/**
 * Weekly aging and space reclamation. Lifetime totals are written incrementally, so aging is a pure
 * DELETE and never needs to rebuild totals.
 */
class StatsMaintenanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val db = StatsDatabase.get(applicationContext)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        // Daily rows are compared by epoch day and raw events by instant: anchor both cutoffs at
        // the same local-midnight boundary of the same start day, so the raw log and the daily
        // aggregates always cover the identical calendar window.
        val retentionStart = today.minusDays(StatsRetention.DAILY_RETENTION_DAYS.toLong())
        val cutoffDay = retentionStart.toEpochDay().toInt()
        val cutoffMillis = retentionStart.atStartOfDay(zone).toInstant().toEpochMilli()

        // active_days is deliberately never pruned: lifetime active days and streaks depend on it.
        val deletedEvents = db.eventDao().deleteOlderThan(cutoffMillis)
        val deletedDaily = db.dailyDao().deleteOlderThan(cutoffDay)

        // Reclaim identity scaffolding that never became statistics: a track with no events, no
        // daily rows and no lifetime totals was never played, so the row (and its aliases, which
        // the foreign-key cascade removes) is pure resolver bookkeeping. The creation-date grace
        // keeps freshly resolved rows alive until their first play.
        val deletedOrphans =
            db.trackDao()
                .deleteOrphans(
                    System.currentTimeMillis() - StatsRetention.ORPHAN_TRACK_GRACE_MILLIS,
                )

        // Cap the outbox so a device that never drains it stays bounded. Recorder inserts trim as
        // well; this pass also covers overflow left by older builds.
        db.outboxDao().trimTo(StatsRetention.OUTBOX_MAX_ROWS)

        if (deletedEvents + deletedDaily + deletedOrphans > 5_000) {
            db.openHelper.writableDatabase.execSQL("VACUUM")
        }
        db.openHelper.writableDatabase.execSQL("PRAGMA optimize")
        Result.success()
    }
        .getOrElse { error ->
            // Cancellation is not a failure: WorkManager must be allowed to stop us without the
            // job being retried forever.
            if (error is CancellationException) throw error
            Log.e("StatsMaintenanceWorker", "Maintenance failed, scheduling retry", error)
            Result.retry()
        }

    companion object {
        private const val UNIQUE_NAME = "stats-maintenance"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<StatsMaintenanceWorker>(7, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            // Idle-only work rarely becomes eligible on devices that are used
                            // while charging, and maintenance is a handful of bounded DELETEs:
                            // it must actually run. Battery-not-low stays as politeness.
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    )
                    .build()
            // UPDATE re-applies period and constraints to work that is already enqueued, so
            // existing installations pick up the loosened constraints; KEEP would keep them on
            // the idle-only request from an older build forever.
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
        }
    }
}
