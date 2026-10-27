package org.sunsetware.phocid.stats.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.DayBucket
import org.sunsetware.phocid.stats.db.HourBucket
import org.sunsetware.phocid.stats.db.PlayHistoryItem
import org.sunsetware.phocid.stats.db.RangeSummary
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackAggregate
import org.sunsetware.phocid.stats.db.TrackTotalsEntity
import org.sunsetware.phocid.stats.fingerprint.LocalUriKey
import org.sunsetware.phocid.stats.fingerprint.TagKey

/** Range choices. The longest range matches StatsRetention.DAILY_RETENTION_DAYS. */
enum class StatsRange(val days: Int?) {
    DAYS_7(7),
    DAYS_30(30),
    CALENDAR_MONTH(null),
    DAYS_90(90),
    DAYS_365(365),
    CALENDAR_YEAR(null),
    YEARS_3(1095),
    ALL(null);

    val isAll: Boolean
        get() = this == ALL

    /**
     * First day included in this range, or null for all time.
     *
     * Calendar ranges start on the first day of the current month or year. Day-based ranges include
     * today and the preceding `days - 1` days.
     */
    fun startDate(today: LocalDate): LocalDate? =
        when (this) {
            CALENDAR_MONTH -> today.withDayOfMonth(1)
            CALENDAR_YEAR -> today.withDayOfYear(1)
            ALL -> null
            DAYS_7,
            DAYS_30,
            DAYS_90,
            DAYS_365,
            YEARS_3 -> today.minusDays(requireNotNull(days).toLong() - 1)
        }
}

enum class StatsSort {
    PLAYS,
    TIME,
}

class StatsRepository(
    private val db: StatsDatabase,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private fun sinceDay(range: StatsRange): Int =
        range.startDate(LocalDate.now(zoneId()))?.toEpochDay()?.toInt() ?: 0

    private fun sinceMillis(range: StatsRange): Long =
        range
            .startDate(LocalDate.now(zoneId()))
            ?.atStartOfDay(zoneId())
            ?.toInstant()
            ?.toEpochMilli() ?: 0L

    fun topTracks(
        range: StatsRange,
        sort: StatsSort,
        limit: Int = 100,
    ): Flow<List<TrackAggregate>> =
        if (range.isAll) {
            when (sort) {
                StatsSort.PLAYS ->
                    db.totalsDao().topTracksByPlays(limit, AliasKind.LOCAL_URI)
                StatsSort.TIME -> db.totalsDao().topTracksByTime(limit, AliasKind.LOCAL_URI)
            }
        } else {
            val since = sinceDay(range)
            when (sort) {
                StatsSort.PLAYS ->
                    db.dailyDao().topTracksByPlays(since, limit, AliasKind.LOCAL_URI)
                StatsSort.TIME ->
                    db.dailyDao().topTracksByTime(since, limit, AliasKind.LOCAL_URI)
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

    suspend fun lifetimeForTrack(track: Track): TrackTotalsEntity? {
        db.totalsDao()
            .byAlias(AliasKind.LOCAL_URI, LocalUriKey.of(track.uri.toString(), track.size))
            ?.let {
                return it
            }
        if (track.path.isNotBlank()) {
            db.totalsDao().byPath(track.path)?.let {
                return it
            }
        }
        for (key in
            TagKey.candidates(
                track.artists.joinToString(", "),
                track.album,
                track.title ?: track.fileName.substringBeforeLast('.'),
                track.duration.inWholeMilliseconds,
            )) {
            db.totalsDao().byAlias(AliasKind.TAG_KEY, key)?.let {
                return it
            }
        }
        return null
    }
}
