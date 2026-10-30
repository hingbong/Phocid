package org.sunsetware.phocid.stats.maintenance

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
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

        val cutoffDay =
            today.minusDays(StatsRetention.DAILY_RETENTION_DAYS.toLong()).toEpochDay().toInt()
        val cutoffMillis =
            today
                .minusDays(StatsRetention.EVENT_RETENTION_DAYS.toLong())
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()

        val deletedEvents = db.eventDao().deleteOlderThan(cutoffMillis)
        val deletedDaily = db.dailyDao().deleteOlderThan(cutoffDay)

        if (deletedEvents + deletedDaily > 5_000) {
            db.openHelper.writableDatabase.execSQL("VACUUM")
        }
        db.openHelper.writableDatabase.execSQL("PRAGMA optimize")
        Result.success()
    }
        .getOrElse { Result.retry() }

    companion object {
        private const val UNIQUE_NAME = "stats-maintenance"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<StatsMaintenanceWorker>(7, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .setRequiresDeviceIdle(true)
                            .build()
                    )
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }
    }
}
