package org.sunsetware.phocid.stats.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** One row of the statistics ranking. */
data class TrackAggregate(
    val trackRowId: Long,
    val uuid: String,
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val plays: Int,
    val millis: Long,
    val lastPlayedAt: Long,
)

data class HourBucket(val hour: Int, val plays: Int, val millis: Long)

data class DayBucket(val epochDay: Int, val plays: Int, val millis: Long)

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
}

@Dao
interface TrackAliasDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(alias: TrackAliasEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(aliases: List<TrackAliasEntity>)

    @Query("SELECT trackRowId FROM track_aliases WHERE kind = :kind AND value = :value")
    suspend fun resolve(kind: Int, value: String): Long?

    @Query("SELECT * FROM track_aliases WHERE trackRowId = :rowId")
    suspend fun aliasesOf(rowId: Long): List<TrackAliasEntity>

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
        SELECT CAST(strftime('%H', playedAt / 1000 + :offsetSeconds, 'unixepoch') AS INTEGER) AS hour,
               COUNT(*) AS plays,
               SUM(playedMillis) AS millis
        FROM play_events
        WHERE playedAt >= :sinceMillis
        GROUP BY hour
        ORDER BY hour
        """
    )
    fun hourlyDistribution(sinceMillis: Long, offsetSeconds: Int): Flow<List<HourBucket>>

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
        INSERT INTO track_daily (trackRowId, epochDay, playCount, playedMillis, lastPlayedAt)
        VALUES (:trackRowId, :epochDay, :playCount, :playedMillis, :at)
        ON CONFLICT(trackRowId, epochDay) DO UPDATE SET
            playCount = playCount + excluded.playCount,
            playedMillis = playedMillis + excluded.playedMillis,
            lastPlayedAt = MAX(lastPlayedAt, excluded.lastPlayedAt)
        """
    )
    suspend fun accumulate(
        trackRowId: Long,
        epochDay: Int,
        playCount: Int,
        playedMillis: Long,
        at: Long,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<TrackDailyEntity>)

    @Query("DELETE FROM track_daily WHERE epochDay < :cutoffDay")
    suspend fun deleteOlderThan(cutoffDay: Int): Int

    @Query("SELECT * FROM track_daily WHERE epochDay >= :sinceDay")
    suspend fun since(sinceDay: Int): List<TrackDailyEntity>

    /** Merge loser's days into winner. Overlapping days are summed. */
    @Query(
        """
        INSERT INTO track_daily (trackRowId, epochDay, playCount, playedMillis, lastPlayedAt)
        SELECT :winner, epochDay, playCount, playedMillis, lastPlayedAt
        FROM track_daily WHERE trackRowId = :loser
        ON CONFLICT(trackRowId, epochDay) DO UPDATE SET
            playCount = playCount + excluded.playCount,
            playedMillis = playedMillis + excluded.playedMillis,
            lastPlayedAt = MAX(lastPlayedAt, excluded.lastPlayedAt)
        """
    )
    suspend fun mergeInto(loser: Long, winner: Long)

    @Query("DELETE FROM track_daily WHERE trackRowId = :rowId")
    suspend fun deleteTrack(rowId: Long)

    @Query(
        """
        SELECT d.trackRowId AS trackRowId,
               t.uuid AS uuid,
               t.title AS title,
               t.artist AS artist,
               t.album AS album,
               t.path AS path,
               SUM(d.playCount) AS plays,
               SUM(d.playedMillis) AS millis,
               MAX(d.lastPlayedAt) AS lastPlayedAt
        FROM track_daily d
        JOIN tracks t ON t.id = d.trackRowId
        WHERE d.epochDay >= :sinceDay
        GROUP BY d.trackRowId
        ORDER BY plays DESC, millis DESC
        LIMIT :limit
        """
    )
    fun topTracksByPlays(sinceDay: Int, limit: Int): Flow<List<TrackAggregate>>

    @Query(
        """
        SELECT d.trackRowId AS trackRowId,
               t.uuid AS uuid,
               t.title AS title,
               t.artist AS artist,
               t.album AS album,
               t.path AS path,
               SUM(d.playCount) AS plays,
               SUM(d.playedMillis) AS millis,
               MAX(d.lastPlayedAt) AS lastPlayedAt
        FROM track_daily d
        JOIN tracks t ON t.id = d.trackRowId
        WHERE d.epochDay >= :sinceDay
        GROUP BY d.trackRowId
        ORDER BY millis DESC, plays DESC
        LIMIT :limit
        """
    )
    fun topTracksByTime(sinceDay: Int, limit: Int): Flow<List<TrackAggregate>>

    @Query(
        """
        SELECT epochDay AS epochDay,
               SUM(playCount) AS plays,
               SUM(playedMillis) AS millis
        FROM track_daily
        WHERE epochDay >= :sinceDay
        GROUP BY epochDay
        ORDER BY epochDay
        """
    )
    fun dailyTrend(sinceDay: Int): Flow<List<DayBucket>>

    @Query(
        """
        SELECT COALESCE(SUM(playCount), 0) AS plays,
               COALESCE(SUM(playedMillis), 0) AS millis,
               COUNT(DISTINCT trackRowId) AS uniqueTracks,
               COUNT(DISTINCT epochDay) AS activeDays
        FROM track_daily
        WHERE epochDay >= :sinceDay
        """
    )
    fun summarySince(sinceDay: Int): Flow<RangeSummary>

    @Query("SELECT DISTINCT epochDay FROM track_daily WHERE epochDay >= :sinceDay ORDER BY epochDay")
    suspend fun activeDays(sinceDay: Int): List<Int>

    @Query("SELECT * FROM track_daily WHERE epochDay >= :sinceDay")
    suspend fun exportSince(sinceDay: Int): List<TrackDailyEntity>

    @Query("DELETE FROM track_daily") suspend fun clear()
}

data class RangeSummary(
    val plays: Int,
    val millis: Long,
    val uniqueTracks: Int,
    val activeDays: Int,
)

@Dao
interface TrackTotalsDao {
    @Query(
        """
        INSERT INTO track_totals (trackRowId, totalCount, totalMillis, firstPlayedAt, lastPlayedAt)
        VALUES (:trackRowId, :count, :millis, :at, :at)
        ON CONFLICT(trackRowId) DO UPDATE SET
            totalCount = totalCount + excluded.totalCount,
            totalMillis = totalMillis + excluded.totalMillis,
            firstPlayedAt = MIN(firstPlayedAt, excluded.firstPlayedAt),
            lastPlayedAt = MAX(lastPlayedAt, excluded.lastPlayedAt)
        """
    )
    suspend fun accumulate(trackRowId: Long, count: Int, millis: Long, at: Long)

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

    @Query("SELECT * FROM track_totals") suspend fun all(): List<TrackTotalsEntity>

    @Query(
        """
        INSERT INTO track_totals (trackRowId, totalCount, totalMillis, firstPlayedAt, lastPlayedAt)
        SELECT :winner, totalCount, totalMillis, firstPlayedAt, lastPlayedAt
        FROM track_totals WHERE trackRowId = :loser
        ON CONFLICT(trackRowId) DO UPDATE SET
            totalCount = totalCount + excluded.totalCount,
            totalMillis = totalMillis + excluded.totalMillis,
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
               tt.totalCount AS plays,
               tt.totalMillis AS millis,
               tt.lastPlayedAt AS lastPlayedAt
        FROM track_totals tt
        JOIN tracks t ON t.id = tt.trackRowId
        ORDER BY tt.totalCount DESC, tt.totalMillis DESC
        LIMIT :limit
        """
    )
    fun topTracksByPlays(limit: Int): Flow<List<TrackAggregate>>

    @Query(
        """
        SELECT tt.trackRowId AS trackRowId,
               t.uuid AS uuid,
               t.title AS title,
               t.artist AS artist,
               t.album AS album,
               t.path AS path,
               tt.totalCount AS plays,
               tt.totalMillis AS millis,
               tt.lastPlayedAt AS lastPlayedAt
        FROM track_totals tt
        JOIN tracks t ON t.id = tt.trackRowId
        ORDER BY tt.totalMillis DESC, tt.totalCount DESC
        LIMIT :limit
        """
    )
    fun topTracksByTime(limit: Int): Flow<List<TrackAggregate>>

    @Query(
        """
        SELECT COALESCE(SUM(totalCount), 0) AS plays,
               COALESCE(SUM(totalMillis), 0) AS millis,
               COUNT(*) AS uniqueTracks,
               0 AS activeDays
        FROM track_totals
        """
    )
    fun summaryAll(): Flow<RangeSummary>

    @Query("DELETE FROM track_totals") suspend fun clear()
}

@Dao
interface FingerprintCacheDao {
    @Query("SELECT audioHash FROM fingerprint_cache WHERE path = :path AND size = :size AND mtime = :mtime")
    suspend fun hit(path: String, size: Long, mtime: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: FingerprintCacheEntity)

    @Query("DELETE FROM fingerprint_cache") suspend fun clear()
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
