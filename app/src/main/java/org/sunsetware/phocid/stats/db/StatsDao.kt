package org.sunsetware.phocid.stats.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** One row of the statistics ranking. */
data class TrackAggregate(
    val trackRowId: Long,
    val uuid: String,
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val localUriKey: String?,
    val durationMillis: Long,
    val plays: Int,
    val millis: Long,
    val lastPlayedAt: Long,
    val firstPlayedAt: Long? = null,
    val startedCount: Int = 0,
    val completedCount: Int = 0,
    val skipCount: Int = 0,
    val completedMillis: Long = 0,
)

data class HourBucket(val hour: Int, val plays: Int, val millis: Long)

/**
 * Aggregated 15-minute UTC slice of play events for [PlayEventDao.hourlyBuckets].
 *
 * The aggregation runs in SQLite, so the full event log is never loaded into memory even for the
 * ALL range. Every post-1970 zone offset and DST transition instant is a multiple of 15 minutes, so
 * each slice lies wholly inside one local hour of any zone; the caller maps the slice start to its
 * local hour, which preserves per-event bucketing across DST transitions.
 */
data class PlayEventSlice(val startMillis: Long, val plays: Int, val millis: Long)

data class DayBucket(
    val epochDay: Int,
    val plays: Int,
    val millis: Long,
    /** Distinct tracks with any row for this day, including a zero-length skip. */
    val touchedTracks: Int = 0,
    /** Distinct tracks with any listening time or a counted play. */
    val listenedTracks: Int = 0,
    val countedTracks: Int = 0,
    val startedCount: Int = 0,
    val completedCount: Int = 0,
    val skipCount: Int = 0,
    val completedMillis: Long = 0,
    val dayStartMs: Long? = null,
    val dayEndMs: Long? = null,
    val utcOffsetMinutes: Int? = null,
)

data class PlayHistoryItem(
    val eventId: Long,
    val playedAt: Long,
    val playedMillis: Long,
    val uuid: String,
    val title: String,
    val artist: String,
)

@Dao
interface TrackDao {
    @Insert suspend fun insert(track: TrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :rowId") suspend fun byRowId(rowId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE uuid = :uuid") suspend fun byUuid(uuid: String): TrackEntity?

    @Query("SELECT id FROM tracks WHERE uuid = :uuid") suspend fun rowIdOfUuid(uuid: String): Long?

    @Query("SELECT id FROM tracks WHERE path = :path LIMIT 1")
    suspend fun rowIdOfPath(path: String): Long?

    @Query(
        """
        UPDATE tracks
        SET title = :title, artist = :artist, album = :album,
            durationMillis = :durationMillis, path = :path
        WHERE id = :rowId
        """
    )
    suspend fun updateMetadata(
        rowId: Long,
        title: String,
        artist: String,
        album: String,
        durationMillis: Long,
        path: String,
    )

    @Query("DELETE FROM tracks WHERE id = :rowId") suspend fun delete(rowId: Long)

    @Query("DELETE FROM tracks") suspend fun clear()

    /**
     * Deletes tracks that carry no statistics anywhere (no raw events, no daily rows, no lifetime
     * totals) and were created before [createdBeforeMillis]. Such rows are resolver bookkeeping for
     * files that were never played; their aliases are removed by the foreign-key cascade. The
     * creation-date grace keeps freshly resolved rows alive until their first play.
     */
    @Query(
        """
        DELETE FROM tracks WHERE id IN (
            SELECT t.id FROM tracks t
            WHERE t.createdAt < :createdBeforeMillis
              AND NOT EXISTS (SELECT 1 FROM play_events pe WHERE pe.trackRowId = t.id)
              AND NOT EXISTS (SELECT 1 FROM track_daily td WHERE td.trackRowId = t.id)
              AND NOT EXISTS (SELECT 1 FROM track_totals tt WHERE tt.trackRowId = t.id)
        )
        """
    )
    suspend fun deleteOrphans(createdBeforeMillis: Long): Int
}

@Dao
interface TrackAliasDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(alias: TrackAliasEntity)

    /**
     * Insert an alias only when (kind, value) is still unused. A conflicting alias must never be
     * overwritten: a weaker clue cannot steal another track's identity.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(alias: TrackAliasEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(aliases: List<TrackAliasEntity>)

    @Query("SELECT trackRowId FROM track_aliases WHERE kind = :kind AND value = :value")
    suspend fun resolve(kind: Int, value: String): Long?

    @Query("SELECT * FROM track_aliases WHERE trackRowId = :rowId")
    suspend fun aliasesOf(rowId: Long): List<TrackAliasEntity>

    @Query("DELETE FROM track_aliases WHERE trackRowId = :rowId AND kind = :kind")
    suspend fun deleteKind(rowId: Long, kind: Int)

    @Query("SELECT * FROM track_aliases") suspend fun all(): List<TrackAliasEntity>

    @Query("UPDATE track_aliases SET trackRowId = :winner WHERE trackRowId = :loser")
    suspend fun repoint(loser: Long, winner: Long)

    @Query("DELETE FROM track_aliases") suspend fun clear()
}

@Dao
interface PlayEventDao {
    @Insert suspend fun insert(event: PlayEventEntity): Long

    @Query("DELETE FROM play_events WHERE playedAt < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("UPDATE play_events SET trackRowId = :winner WHERE trackRowId = :loser")
    suspend fun repoint(loser: Long, winner: Long)

    @Query(
        """
        SELECT (playedAt / 900000) * 900000 AS startMillis,
               COUNT(*) AS plays,
               SUM(playedMillis) AS millis
        FROM play_events
        WHERE playedAt >= :sinceMillis
        GROUP BY startMillis
        ORDER BY startMillis
        """
    )
    fun hourlyBuckets(sinceMillis: Long): Flow<List<PlayEventSlice>>

    @Query(
        """
        SELECT e.id AS eventId,
               e.playedAt AS playedAt,
               e.playedMillis AS playedMillis,
               t.uuid AS uuid,
               t.title AS title,
               t.artist AS artist
        FROM play_events e
        JOIN tracks t ON t.id = e.trackRowId
        ORDER BY e.playedAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun history(limit: Int, offset: Int): Flow<List<PlayHistoryItem>>

    @Query("SELECT COUNT(*) FROM play_events") suspend fun count(): Long

    @Query("DELETE FROM play_events") suspend fun clear()
}

@Dao
interface TrackDailyDao {
    /** Manual UPSERT because Room's @Upsert replaces the whole row. */
    @Query(
        """
        INSERT INTO track_daily (
            trackRowId, epochDay, playCount, playedMillis, lastPlayedAt, firstPlayedAt,
            startedCount, completedCount, skipCount, completedMillis,
            dayStartMs, dayEndMs, utcOffsetMinutes
        )
        VALUES (
            :trackRowId, :epochDay, :playCount, :playedMillis, :at, :firstPlayedAt,
            :startedCount, :completedCount, :skipCount, :completedMillis,
            :dayStartMs, :dayEndMs, :utcOffsetMinutes
        )
        ON CONFLICT(trackRowId, epochDay) DO UPDATE SET
            playCount = playCount + excluded.playCount,
            playedMillis = playedMillis + excluded.playedMillis,
            firstPlayedAt = CASE
                WHEN firstPlayedAt IS NULL THEN excluded.firstPlayedAt
                WHEN excluded.firstPlayedAt IS NULL THEN firstPlayedAt
                ELSE MIN(firstPlayedAt, excluded.firstPlayedAt)
            END,
            startedCount = startedCount + excluded.startedCount,
            completedCount = completedCount + excluded.completedCount,
            skipCount = skipCount + excluded.skipCount,
            completedMillis = completedMillis + excluded.completedMillis,
            dayStartMs = COALESCE(dayStartMs, excluded.dayStartMs),
            dayEndMs = COALESCE(dayEndMs, excluded.dayEndMs),
            utcOffsetMinutes = COALESCE(utcOffsetMinutes, excluded.utcOffsetMinutes),
            lastPlayedAt = MAX(lastPlayedAt, excluded.lastPlayedAt)
        """
    )
    suspend fun accumulate(
        trackRowId: Long,
        epochDay: Int,
        playCount: Int,
        playedMillis: Long,
        at: Long,
        firstPlayedAt: Long,
        startedCount: Int,
        completedCount: Int,
        skipCount: Int,
        completedMillis: Long,
        dayStartMs: Long?,
        dayEndMs: Long?,
        utcOffsetMinutes: Int?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<TrackDailyEntity>)

    @Query("DELETE FROM track_daily WHERE epochDay < :cutoffDay")
    suspend fun deleteOlderThan(cutoffDay: Int): Int

    @Query("SELECT * FROM track_daily WHERE epochDay >= :sinceDay")
    suspend fun since(sinceDay: Int): List<TrackDailyEntity>

    /**
     * Loads only the exact (trackRowId, epochDay) pairs a merge restore can overlap. A raw query is
     * required because the row-value form `(trackRowId, epochDay) IN (VALUES (?,?),...)` needs a
     * dynamically sized tuple list, which Room's declarative `IN (:list)` binding cannot express.
     * The previous `trackRowId IN (:rowIds) AND epochDay IN (:epochDays)` form fetched the full
     * cartesian grid instead of the payload's pairs. Callers must chunk the pairs so the flat bind
     * list (2 parameters per pair) stays below SQLite's 999 bind-parameter limit.
     */
    @RawQuery suspend fun byExactPairs(query: SupportSQLiteQuery): List<TrackDailyEntity>

    /** Merge loser's days into winner. Overlapping days are summed. */
    @Query(
        """
        INSERT INTO track_daily (
            trackRowId, epochDay, playCount, playedMillis, lastPlayedAt, firstPlayedAt,
            startedCount, completedCount, skipCount, completedMillis,
            dayStartMs, dayEndMs, utcOffsetMinutes
        )
        SELECT :winner, epochDay, playCount, playedMillis, lastPlayedAt, firstPlayedAt,
               startedCount, completedCount, skipCount, completedMillis,
               dayStartMs, dayEndMs, utcOffsetMinutes
        FROM track_daily WHERE trackRowId = :loser
        ON CONFLICT(trackRowId, epochDay) DO UPDATE SET
            playCount = playCount + excluded.playCount,
            playedMillis = playedMillis + excluded.playedMillis,
            firstPlayedAt = CASE
                WHEN firstPlayedAt IS NULL THEN excluded.firstPlayedAt
                WHEN excluded.firstPlayedAt IS NULL THEN firstPlayedAt
                ELSE MIN(firstPlayedAt, excluded.firstPlayedAt)
            END,
            startedCount = startedCount + excluded.startedCount,
            completedCount = completedCount + excluded.completedCount,
            skipCount = skipCount + excluded.skipCount,
            completedMillis = completedMillis + excluded.completedMillis,
            dayStartMs = COALESCE(dayStartMs, excluded.dayStartMs),
            dayEndMs = COALESCE(dayEndMs, excluded.dayEndMs),
            utcOffsetMinutes = COALESCE(utcOffsetMinutes, excluded.utcOffsetMinutes),
            lastPlayedAt = MAX(lastPlayedAt, excluded.lastPlayedAt)
        """
    )
    suspend fun mergeInto(loser: Long, winner: Long)

    @Query("DELETE FROM track_daily WHERE trackRowId = :rowId") suspend fun deleteTrack(rowId: Long)

    @Query(
        """
        SELECT d.trackRowId AS trackRowId,
               t.uuid AS uuid,
               t.title AS title,
               t.artist AS artist,
               t.album AS album,
               t.path AS path,
               (SELECT ta.value FROM track_aliases ta
                WHERE ta.trackRowId = t.id AND ta.kind = :localUriKind
                LIMIT 1) AS localUriKey,
               t.durationMillis AS durationMillis,
               SUM(d.playCount) AS plays,
               SUM(d.playedMillis) AS millis,
               MIN(d.firstPlayedAt) AS firstPlayedAt,
               MAX(d.lastPlayedAt) AS lastPlayedAt,
               SUM(d.startedCount) AS startedCount,
               SUM(d.completedCount) AS completedCount,
               SUM(d.skipCount) AS skipCount,
               SUM(d.completedMillis) AS completedMillis
        FROM track_daily d
        JOIN tracks t ON t.id = d.trackRowId
        WHERE d.epochDay >= :sinceDay AND d.epochDay <= :untilDay
        GROUP BY d.trackRowId
        ORDER BY plays DESC, millis DESC
        LIMIT :limit
        """
    )
    fun topTracksByPlays(
        sinceDay: Int,
        untilDay: Int,
        limit: Int,
        localUriKind: Int,
    ): Flow<List<TrackAggregate>>

    @Query(
        """
        SELECT d.trackRowId AS trackRowId,
               t.uuid AS uuid,
               t.title AS title,
               t.artist AS artist,
               t.album AS album,
               t.path AS path,
               (SELECT ta.value FROM track_aliases ta
                WHERE ta.trackRowId = t.id AND ta.kind = :localUriKind
                LIMIT 1) AS localUriKey,
               t.durationMillis AS durationMillis,
               SUM(d.playCount) AS plays,
               SUM(d.playedMillis) AS millis,
               MIN(d.firstPlayedAt) AS firstPlayedAt,
               MAX(d.lastPlayedAt) AS lastPlayedAt,
               SUM(d.startedCount) AS startedCount,
               SUM(d.completedCount) AS completedCount,
               SUM(d.skipCount) AS skipCount,
               SUM(d.completedMillis) AS completedMillis
        FROM track_daily d
        JOIN tracks t ON t.id = d.trackRowId
        WHERE d.epochDay >= :sinceDay AND d.epochDay <= :untilDay
        GROUP BY d.trackRowId
        ORDER BY millis DESC, plays DESC
        LIMIT :limit
        """
    )
    fun topTracksByTime(
        sinceDay: Int,
        untilDay: Int,
        limit: Int,
        localUriKind: Int,
    ): Flow<List<TrackAggregate>>

    // Mixed-timezone restores can produce different local-day boundaries for the same epochDay.
    // The union of a day's boundaries ends at the latest end, hence MAX(dayEndMs) (not MIN).
    @Query(
        """
        SELECT epochDay AS epochDay,
               SUM(playCount) AS plays,
               SUM(playedMillis) AS millis,
               COUNT(DISTINCT trackRowId) AS touchedTracks,
               COUNT(DISTINCT CASE WHEN playedMillis > 0 OR playCount > 0 THEN trackRowId END)
                   AS listenedTracks,
               COUNT(DISTINCT CASE WHEN playCount > 0 THEN trackRowId END) AS countedTracks,
               SUM(startedCount) AS startedCount,
               SUM(completedCount) AS completedCount,
               SUM(skipCount) AS skipCount,
               SUM(completedMillis) AS completedMillis,
               MIN(dayStartMs) AS dayStartMs,
               MAX(dayEndMs) AS dayEndMs,
               MIN(utcOffsetMinutes) AS utcOffsetMinutes
        FROM track_daily
        WHERE epochDay >= :sinceDay AND epochDay <= :untilDay
        GROUP BY epochDay
        ORDER BY epochDay
        """
    )
    fun dailyTrend(sinceDay: Int, untilDay: Int): Flow<List<DayBucket>>

    @Query(
        """
        SELECT COALESCE(SUM(playCount), 0) AS plays,
               COALESCE(SUM(playedMillis), 0) AS millis,
               COUNT(DISTINCT trackRowId) AS touchedTracks,
               COUNT(DISTINCT CASE WHEN playedMillis > 0 OR playCount > 0 THEN trackRowId END)
                   AS listenedTracks,
               COUNT(DISTINCT epochDay) AS activeDays,
               COUNT(DISTINCT CASE WHEN playCount > 0 THEN trackRowId END) AS countedTracks,
               COALESCE(SUM(startedCount), 0) AS startedCount,
               COALESCE(SUM(completedCount), 0) AS completedCount,
               COALESCE(SUM(skipCount), 0) AS skipCount,
               COALESCE(SUM(completedMillis), 0) AS completedMillis
        FROM track_daily
        WHERE epochDay >= :sinceDay AND epochDay <= :untilDay
        """
    )
    fun summarySince(sinceDay: Int, untilDay: Int): Flow<RangeSummary>

    @Query(
        "SELECT DISTINCT epochDay FROM track_daily WHERE epochDay >= :sinceDay ORDER BY epochDay"
    )
    suspend fun activeDays(sinceDay: Int): List<Int>

    @Query("SELECT * FROM track_daily WHERE epochDay >= :sinceDay")
    suspend fun exportSince(sinceDay: Int): List<TrackDailyEntity>

    @Query("DELETE FROM track_daily") suspend fun clear()
}

data class RangeSummary(
    val plays: Int,
    val millis: Long,
    /** Distinct tracks with any row in the range, including a zero-length skip. */
    val touchedTracks: Int,
    val activeDays: Int,
    /** Distinct tracks with any listening time or a counted play. */
    val listenedTracks: Int = 0,
    val countedTracks: Int = 0,
    val startedCount: Int = 0,
    val completedCount: Int = 0,
    val skipCount: Int = 0,
    val completedMillis: Long = 0,
)

/**
 * Global lifetime active days. Rows are only inserted, never removed by retention: the whole point
 * is to outlive [TrackDailyDao]'s 1095-day window.
 */
@Dao
interface ActiveDayDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(day: ActiveDayEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(days: List<ActiveDayEntity>)

    @Query("SELECT * FROM active_days ORDER BY epochDay") suspend fun all(): List<ActiveDayEntity>

    @Query("DELETE FROM active_days") suspend fun clear()
}

@Dao
interface TrackTotalsDao {
    @Query(
        """
        INSERT INTO track_totals (
            trackRowId, totalCount, totalMillis, firstPlayedAt, lastPlayedAt,
            startedCount, completedCount, skipCount, completedMillis
        )
        VALUES (
            :trackRowId, :count, :millis, :at, :at,
            :startedCount, :completedCount, :skipCount, :completedMillis
        )
        ON CONFLICT(trackRowId) DO UPDATE SET
            totalCount = totalCount + excluded.totalCount,
            totalMillis = totalMillis + excluded.totalMillis,
            startedCount = startedCount + excluded.startedCount,
            completedCount = completedCount + excluded.completedCount,
            skipCount = skipCount + excluded.skipCount,
            completedMillis = completedMillis + excluded.completedMillis,
            firstPlayedAt = MIN(firstPlayedAt, excluded.firstPlayedAt),
            lastPlayedAt = MAX(lastPlayedAt, excluded.lastPlayedAt)
        """
    )
    suspend fun accumulate(
        trackRowId: Long,
        count: Int,
        millis: Long,
        at: Long,
        startedCount: Int,
        completedCount: Int,
        skipCount: Int,
        completedMillis: Long,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<TrackTotalsEntity>)

    @Query("SELECT * FROM track_totals WHERE trackRowId = :rowId")
    suspend fun byRowId(rowId: Long): TrackTotalsEntity?

    @Query(
        """
        SELECT tt.* FROM track_totals tt
        JOIN tracks t ON t.id = tt.trackRowId
        WHERE t.path = :path
        LIMIT 1
        """
    )
    suspend fun byPath(path: String): TrackTotalsEntity?

    @Query(
        """
        SELECT tt.* FROM track_totals tt
        JOIN track_aliases ta ON ta.trackRowId = tt.trackRowId
        WHERE ta.kind = :kind AND ta.value = :value
        LIMIT 1
        """
    )
    suspend fun byAlias(kind: Int, value: String): TrackTotalsEntity?

    @Query("SELECT * FROM track_totals") suspend fun all(): List<TrackTotalsEntity>

    @Query(
        """
        INSERT INTO track_totals (
            trackRowId, totalCount, totalMillis, firstPlayedAt, lastPlayedAt,
            startedCount, completedCount, skipCount, completedMillis
        )
        SELECT :winner, totalCount, totalMillis, firstPlayedAt, lastPlayedAt,
               startedCount, completedCount, skipCount, completedMillis
        FROM track_totals WHERE trackRowId = :loser
        ON CONFLICT(trackRowId) DO UPDATE SET
            totalCount = totalCount + excluded.totalCount,
            totalMillis = totalMillis + excluded.totalMillis,
            startedCount = startedCount + excluded.startedCount,
            completedCount = completedCount + excluded.completedCount,
            skipCount = skipCount + excluded.skipCount,
            completedMillis = completedMillis + excluded.completedMillis,
            firstPlayedAt = MIN(firstPlayedAt, excluded.firstPlayedAt),
            lastPlayedAt = MAX(lastPlayedAt, excluded.lastPlayedAt)
        """
    )
    suspend fun mergeInto(loser: Long, winner: Long)

    @Query("DELETE FROM track_totals WHERE trackRowId = :rowId")
    suspend fun deleteTrack(rowId: Long)

    @Query(
        """
        SELECT tt.trackRowId AS trackRowId,
               t.uuid AS uuid,
               t.title AS title,
               t.artist AS artist,
               t.album AS album,
               t.path AS path,
               (SELECT ta.value FROM track_aliases ta
                WHERE ta.trackRowId = t.id AND ta.kind = :localUriKind
                LIMIT 1) AS localUriKey,
               t.durationMillis AS durationMillis,
               tt.totalCount AS plays,
               tt.totalMillis AS millis,
               tt.firstPlayedAt AS firstPlayedAt,
               tt.lastPlayedAt AS lastPlayedAt,
               tt.startedCount AS startedCount,
               tt.completedCount AS completedCount,
               tt.skipCount AS skipCount,
               tt.completedMillis AS completedMillis
        FROM track_totals tt
        JOIN tracks t ON t.id = tt.trackRowId
        ORDER BY tt.totalCount DESC, tt.totalMillis DESC
        LIMIT :limit
        """
    )
    fun topTracksByPlays(limit: Int, localUriKind: Int): Flow<List<TrackAggregate>>

    @Query(
        """
        SELECT tt.trackRowId AS trackRowId,
               t.uuid AS uuid,
               t.title AS title,
               t.artist AS artist,
               t.album AS album,
               t.path AS path,
               (SELECT ta.value FROM track_aliases ta
                WHERE ta.trackRowId = t.id AND ta.kind = :localUriKind
                LIMIT 1) AS localUriKey,
               t.durationMillis AS durationMillis,
               tt.totalCount AS plays,
               tt.totalMillis AS millis,
               tt.firstPlayedAt AS firstPlayedAt,
               tt.lastPlayedAt AS lastPlayedAt,
               tt.startedCount AS startedCount,
               tt.completedCount AS completedCount,
               tt.skipCount AS skipCount,
               tt.completedMillis AS completedMillis
        FROM track_totals tt
        JOIN tracks t ON t.id = tt.trackRowId
        ORDER BY tt.totalMillis DESC, tt.totalCount DESC
        LIMIT :limit
        """
    )
    fun topTracksByTime(limit: Int, localUriKind: Int): Flow<List<TrackAggregate>>

    @Query(
        """
        SELECT COALESCE(SUM(totalCount), 0) AS plays,
               COALESCE(SUM(totalMillis), 0) AS millis,
               COUNT(*) AS touchedTracks,
               (SELECT COUNT(*) FROM active_days) AS activeDays,
               COUNT(CASE WHEN totalMillis > 0 OR totalCount > 0 THEN 1 END)
                   AS listenedTracks,
               COUNT(CASE WHEN totalCount > 0 THEN 1 END) AS countedTracks,
               COALESCE(SUM(startedCount), 0) AS startedCount,
               COALESCE(SUM(completedCount), 0) AS completedCount,
               COALESCE(SUM(skipCount), 0) AS skipCount,
               COALESCE(SUM(completedMillis), 0) AS completedMillis
        FROM track_totals
        """
    )
    fun summaryAll(): Flow<RangeSummary>

    @Query("DELETE FROM track_totals") suspend fun clear()
}

/** Durable shutdown outbox. The row is deleted atomically with the aggregates it carries. */
@Dao
interface PlaybackOutboxDao {
    @Insert suspend fun insert(row: PlaybackOutboxEntity): Long

    @Query("SELECT * FROM playback_outbox ORDER BY id")
    suspend fun all(): List<PlaybackOutboxEntity>

    @Query("SELECT COUNT(*) FROM playback_outbox WHERE id = :id")
    suspend fun countById(id: Long): Int

    @Query("DELETE FROM playback_outbox WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM playback_outbox") suspend fun count(): Long

    /** Deletes all but the newest [keep] rows; the dropped rows are stale recovery state. */
    @Query(
        """
        DELETE FROM playback_outbox WHERE id IN (
            SELECT id FROM playback_outbox ORDER BY id DESC LIMIT -1 OFFSET :keep
        )
        """
    )
    suspend fun trimTo(keep: Int): Int

    @Query("DELETE FROM playback_outbox") suspend fun clear()
}

/** Cross-table transaction for resolving duplicate tracks. */
@Dao
abstract class StatsMaintenanceDao {
    @Transaction
    open suspend fun merge(
        loser: Long,
        winner: Long,
        events: PlayEventDao,
        daily: TrackDailyDao,
        totals: TrackTotalsDao,
        aliases: TrackAliasDao,
        tracks: TrackDao,
    ) {
        if (loser == winner) return
        events.repoint(loser, winner)
        daily.mergeInto(loser, winner)
        daily.deleteTrack(loser)
        totals.mergeInto(loser, winner)
        totals.deleteTrack(loser)
        aliases.repoint(loser, winner)
        tracks.delete(loser)
    }
}
