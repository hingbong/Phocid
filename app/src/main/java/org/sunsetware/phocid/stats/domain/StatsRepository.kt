package org.sunsetware.phocid.stats.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

private const val HOURS_IN_DAY = 24

class StatsRepository(
    private val db: StatsDatabase,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private fun sinceDay(range: StatsRange, today: LocalDate): Int =
        range.startDate(today)?.toEpochDay()?.toInt() ?: 0

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
    ): Flow<List<TrackAggregate>> {
        if (range.isAll) {
            return when (sort) {
                StatsSort.PLAYS -> db.totalsDao().topTracksByPlays(limit, AliasKind.LOCAL_URI)
                StatsSort.TIME -> db.totalsDao().topTracksByTime(limit, AliasKind.LOCAL_URI)
            }
        }
        // Discrete ranges end at the target device's local today. A row can legitimately be ahead
        // of it when a backup was imported from a timezone ahead of this one, but it does not
        // belong to "the last N days" until that day actually starts here.
        val today = LocalDate.now(zoneId())
        val since = sinceDay(range, today)
        val until = today.toEpochDay().toInt()
        return when (sort) {
            StatsSort.PLAYS ->
                db.dailyDao().topTracksByPlays(since, until, limit, AliasKind.LOCAL_URI)
            StatsSort.TIME ->
                db.dailyDao().topTracksByTime(since, until, limit, AliasKind.LOCAL_URI)
        }
    }

    fun summary(range: StatsRange): Flow<RangeSummary> {
        // ALL is all imported history, not a bounded range: lifetime totals stay unbounded.
        if (range.isAll) return db.totalsDao().summaryAll()
        val today = LocalDate.now(zoneId())
        return db.dailyDao().summarySince(sinceDay(range, today), today.toEpochDay().toInt())
    }

    fun dailyTrend(range: StatsRange): Flow<List<DayBucket>> {
        // ALL still shows a trend, but intentionally only over the last three years. That window
        // is a bounded range too, so it ends at today just like the explicit ranges.
        val today = LocalDate.now(zoneId())
        val window = if (range.isAll) StatsRange.YEARS_3 else range
        return db.dailyDao().dailyTrend(sinceDay(window, today), today.toEpochDay().toInt())
    }

    /**
     * Reads play_events, so it starts from zero after restoring a backup.
     *
     * The bucket is the event's own local hour in [zoneId], not a single current offset applied to
     * every event. Both occurrences of a repeated fall-back hour therefore share hour 1, while a
     * spring-forward gap simply has no events (and so no bucket beyond the ordinary zero-filled
     * 0..23 output).
     */
    fun hourlyDistribution(range: StatsRange): Flow<List<HourBucket>> {
        val zone = zoneId()
        return db.eventDao().hourlyEvents(sinceMillis(range)).map { events ->
            val plays = IntArray(HOURS_IN_DAY)
            val millis = LongArray(HOURS_IN_DAY)
            for (event in events) {
                val hour = Instant.ofEpochMilli(event.playedAt).atZone(zone).hour
                plays[hour]++
                millis[hour] += event.playedMillis
            }
            List(HOURS_IN_DAY) { hour -> HourBucket(hour, plays[hour], millis[hour]) }
        }
    }

    fun history(limit: Int, offset: Int): Flow<List<PlayHistoryItem>> =
        db.eventDao().history(limit, offset)

    suspend fun currentStreak(): Int {
        val today = LocalDate.now(zoneId()).toEpochDay().toInt()
        // The global active-day table is never pruned, so no retention cutoff applies here.
        val days = db.activeDayDao().all().mapTo(HashSet()) { it.epochDay }
        if (days.isEmpty()) return 0
        var cursor = if (days.contains(today)) today else today - 1
        var streak = 0
        while (days.contains(cursor)) {
            streak++
            cursor--
        }
        return streak
    }

    suspend fun lifetime(trackRowId: Long): TrackTotalsEntity? = db.totalsDao().byRowId(trackRowId)

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
