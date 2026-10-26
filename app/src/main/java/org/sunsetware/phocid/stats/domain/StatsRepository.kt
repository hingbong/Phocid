package org.sunsetware.phocid.stats.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import org.sunsetware.phocid.stats.db.DayBucket
import org.sunsetware.phocid.stats.db.HourBucket
import org.sunsetware.phocid.stats.db.PlayHistoryItem
import org.sunsetware.phocid.stats.db.RangeSummary
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackAggregate
import org.sunsetware.phocid.stats.db.TrackTotalsEntity

/** Range choices. The longest range matches StatsRetention.DAILY_RETENTION_DAYS. */
enum class StatsRange(val days: Int?) {
    DAYS_7(7),
    DAYS_30(30),
    DAYS_90(90),
    DAYS_365(365),
    YEARS_3(1095),
    ALL(null);

    val isAll: Boolean
        get() = days == null
}

enum class StatsSort {
    PLAYS,
    TIME,
}

class StatsRepository(
    private val db: StatsDatabase,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private fun sinceDay(range: StatsRange): Int {
        val days = range.days ?: 0
        return LocalDate.now(zoneId()).minusDays(days.toLong() - 1).toEpochDay().toInt()
    }

    private fun sinceMillis(range: StatsRange): Long {
        val days = range.days ?: return 0L
        return LocalDate.now(zoneId())
            .minusDays(days.toLong() - 1)
            .atStartOfDay(zoneId())
            .toInstant()
            .toEpochMilli()
    }

    fun topTracks(
        range: StatsRange,
        sort: StatsSort,
        limit: Int = 100,
    ): Flow<List<TrackAggregate>> =
        if (range.isAll) {
            when (sort) {
                StatsSort.PLAYS -> db.totalsDao().topTracksByPlays(limit)
                StatsSort.TIME -> db.totalsDao().topTracksByTime(limit)
            }
        } else {
            val since = sinceDay(range)
            when (sort) {
                StatsSort.PLAYS -> db.dailyDao().topTracksByPlays(since, limit)
                StatsSort.TIME -> db.dailyDao().topTracksByTime(since, limit)
            }
        }

    fun summary(range: StatsRange): Flow<RangeSummary> =
        if (range.isAll) db.totalsDao().summaryAll()
        else db.dailyDao().summarySince(sinceDay(range))

    fun dailyTrend(range: StatsRange): Flow<List<DayBucket>> =
        db.dailyDao().dailyTrend(sinceDay(if (range.isAll) StatsRange.YEARS_3 else range))

    /** Reads play_events, so it starts from zero after restoring a backup. */
    fun hourlyDistribution(range: StatsRange): Flow<List<HourBucket>> {
        val offset = zoneId().rules.getOffset(Instant.now()).totalSeconds
        return db.eventDao().hourlyDistribution(sinceMillis(range), offset)
    }

    fun history(limit: Int, offset: Int): Flow<List<PlayHistoryItem>> =
        db.eventDao().history(limit, offset)

    suspend fun currentStreak(): Int {
        val today = LocalDate.now(zoneId()).toEpochDay().toInt()
        val days = db.dailyDao().activeDays(today - 1095).toHashSet()
        if (days.isEmpty()) return 0
        var cursor = if (days.contains(today)) today else today - 1
        var streak = 0
        while (days.contains(cursor)) {
            streak++
            cursor--
        }
        return streak
    }

    suspend fun lifetime(trackRowId: Long): TrackTotalsEntity? =
        db.totalsDao().byRowId(trackRowId)

    suspend fun lifetimeByPath(path: String): TrackTotalsEntity? = db.totalsDao().byPath(path)
}
