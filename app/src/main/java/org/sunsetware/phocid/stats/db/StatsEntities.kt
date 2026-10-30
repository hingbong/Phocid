package org.sunsetware.phocid.stats.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A track in the statistics database.
 *
 * [id] is the compact internal key used by every statistics table. [uuid] is the portable identity
 * used by backups, so it must never be derived from a file path or a MediaStore id.
 *
 * [path] is only a local display hint for looking up artwork and metadata. It is deliberately not
 * part of the identity and is not included in backups.
 */
@Entity(tableName = "tracks", indices = [Index(value = ["uuid"], unique = true), Index("path")])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMillis: Long,
    val path: String = "",
    val createdAt: Long,
)

/**
 * Recognition clue -> track. A track may have multiple rows, one per kind.
 *
 * (kind, value) is the primary key, so one clue can only point at one track. [AliasKind] defines
 * the lookup priority.
 */
@Entity(
    tableName = "track_aliases",
    primaryKeys = ["kind", "value"],
    indices = [Index("trackRowId")],
    foreignKeys =
        [
            ForeignKey(
                entity = TrackEntity::class,
                parentColumns = ["id"],
                childColumns = ["trackRowId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class TrackAliasEntity(
    val kind: Int,
    val value: String,
    val trackRowId: Long,
)

object AliasKind {
    /** Hash of the audio payload. Survives renaming, moving, and tag edits. */
    const val AUDIO_HASH = 1

    /** Normalized artist/album/title plus a duration bucket. Survives re-encoding. */
    const val TAG_KEY = 2

    /** Lowercase path relative to a media root. Fallback for untagged files. */
    const val REL_PATH = 3

    /**
     * Content URI plus size. Local-only fast lookup; never treat it as portable identity and never
     * include it in backups.
     */
    const val LOCAL_URI = 4

    val PRIORITY = intArrayOf(AUDIO_HASH, TAG_KEY, LOCAL_URI, REL_PATH)

    /** Aliases that remain meaningful after moving to another device. */
    val PORTABLE_ALIAS_KINDS = setOf(AUDIO_HASH, TAG_KEY, REL_PATH)
}

/**
 * Raw play events. They are intentionally not part of backups: the aggregate tables are enough to
 * restore every range, while the raw log is large and machine-specific.
 */
@Entity(
    tableName = "play_events",
    indices = [Index("playedAt"), Index("trackRowId", "playedAt")],
    foreignKeys =
        [
            ForeignKey(
                entity = TrackEntity::class,
                parentColumns = ["id"],
                childColumns = ["trackRowId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackRowId: Long,
    /** epoch millis, taken at flush time */
    val playedAt: Long,
    val playedMillis: Long,
)

/**
 * Per-day aggregate. Every range query reads this table instead of scanning play_events.
 *
 * [epochDay] is the local date at write time (LocalDate.toEpochDay()). [lastPlayedAt] is retained
 * so the "recently played" sort also works inside a date range.
 */
@Entity(
    tableName = "track_daily",
    primaryKeys = ["trackRowId", "epochDay"],
    indices = [Index("epochDay")],
    foreignKeys =
        [
            ForeignKey(
                entity = TrackEntity::class,
                parentColumns = ["id"],
                childColumns = ["trackRowId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class TrackDailyEntity(
    val trackRowId: Long,
    val epochDay: Int,
    val playCount: Int,
    val playedMillis: Long,
    val lastPlayedAt: Long = 0,
)

/**
 * One row per calendar day that had any playback anywhere. This is the lifetime "active days" set.
 * It is global instead of per track and is never pruned, so streaks and all-time active day counts
 * survive retention of [TrackDailyEntity] and [PlayEventEntity].
 *
 * Older backups and the 1 -> 2 migration can only recover the days still present in track_daily, so
 * a restored/migrated set is a lower bound.
 */
@Entity(tableName = "active_days") data class ActiveDayEntity(@PrimaryKey val epochDay: Int)

/**
 * Lifetime totals. They are incremented while writing, never rebuilt during retention. Retention
 * can therefore be a pure DELETE with no double-counting risk.
 */
@Entity(
    tableName = "track_totals",
    foreignKeys =
        [
            ForeignKey(
                entity = TrackEntity::class,
                parentColumns = ["id"],
                childColumns = ["trackRowId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class TrackTotalsEntity(
    @PrimaryKey val trackRowId: Long,
    val totalCount: Int,
    val totalMillis: Long,
    val firstPlayedAt: Long,
    val lastPlayedAt: Long,
)

/** Local cache for audio hashes. Derived data, never included in backups. */
@Entity(tableName = "fingerprint_cache")
data class FingerprintCacheEntity(
    @PrimaryKey val path: String,
    val size: Long,
    val mtime: Long,
    @ColumnInfo(name = "audioHash") val audioHash: String?,
)

object StatsRetention {
    const val EVENT_RETENTION_DAYS = 1095
    const val DAILY_RETENTION_DAYS = 1095
}
