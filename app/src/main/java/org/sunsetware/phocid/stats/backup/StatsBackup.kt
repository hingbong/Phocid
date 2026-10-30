package org.sunsetware.phocid.stats.backup

import androidx.room.withTransaction
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.StatsRetention
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.db.TrackDailyEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.db.TrackTotalsEntity

/**
 * Backup format. It contains tracks, aliases, lifetime totals, and per-day aggregates. Raw play
 * events are deliberately excluded: they are large and only serve local history.
 *
 * Identity is the track UUID, never the internal row id or a file path.
 */
@Serializable
data class StatsBackup(
    val version: Int = VERSION,
    val exportedAt: Long,
    val zoneId: String,
    val tracks: List<BackupTrack>,
    val aliases: List<BackupAlias>,
    val totals: List<BackupTotals>,
    val daily: List<BackupDaily>,
) {
    companion object {
        // Local-only aliases are filtered from the payload; the wire format stays version 1.
        const val VERSION = 1
        const val MAGIC = "MSTATS1\u0000"
    }
}

@Serializable
data class BackupTrack(
    val uuid: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMillis: Long,
    val createdAt: Long,
)

@Serializable data class BackupAlias(val kind: Int, val value: String, val uuid: String)

@Serializable
data class BackupTotals(
    val uuid: String,
    val totalCount: Int,
    val totalMillis: Long,
    val firstPlayedAt: Long,
    val lastPlayedAt: Long,
)

@Serializable
data class BackupDaily(
    val uuid: String,
    val epochDay: Int,
    val playCount: Int,
    val playedMillis: Long,
    val lastPlayedAt: Long,
)

@OptIn(ExperimentalSerializationApi::class)
class StatsBackupManager(
    private val db: StatsDatabase,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val cbor = Cbor { ignoreUnknownKeys = true }

    suspend fun export(out: OutputStream) =
        withContext(Dispatchers.IO) {
            val cutoffDay =
                LocalDate.now(zoneId())
                    .minusDays(StatsRetention.DAILY_RETENTION_DAYS.toLong())
                    .toEpochDay()
                    .toInt()

            val tracks = mutableListOf<BackupTrack>()
            val aliases = mutableListOf<BackupAlias>()
            val totals = mutableListOf<BackupTotals>()
            val daily = mutableListOf<BackupDaily>()
            val idToUuid = HashMap<Long, String>()

            db.withTransaction {
                db.totalsDao().all().forEach { total ->
                    val track = db.trackDao().byRowId(total.trackRowId) ?: return@forEach
                    idToUuid[track.id] = track.uuid
                    tracks +=
                        BackupTrack(
                            uuid = track.uuid,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            durationMillis = track.durationMillis,
                            createdAt = track.createdAt,
                        )
                    totals +=
                        BackupTotals(
                            uuid = track.uuid,
                            totalCount = total.totalCount,
                            totalMillis = total.totalMillis,
                            firstPlayedAt = total.firstPlayedAt,
                            lastPlayedAt = total.lastPlayedAt,
                        )
                }
                db.aliasDao().all().forEach { alias ->
                    if (alias.kind in AliasKind.PORTABLE_ALIAS_KINDS) {
                        idToUuid[alias.trackRowId]?.let {
                            aliases += BackupAlias(alias.kind, alias.value, it)
                        }
                    }
                }
                db.dailyDao().exportSince(cutoffDay).forEach { day ->
                    idToUuid[day.trackRowId]?.let {
                        daily +=
                            BackupDaily(
                                uuid = it,
                                epochDay = day.epochDay,
                                playCount = day.playCount,
                                playedMillis = day.playedMillis,
                                lastPlayedAt = day.lastPlayedAt,
                            )
                    }
                }
            }

            val payload =
                StatsBackup(
                    exportedAt = System.currentTimeMillis(),
                    zoneId = zoneId().id,
                    tracks = tracks,
                    aliases = aliases,
                    totals = totals,
                    daily = daily,
                )

            GZIPOutputStream(out.buffered()).use { gzip ->
                gzip.write(StatsBackup.MAGIC.toByteArray(Charsets.US_ASCII))
                gzip.write(cbor.encodeToByteArray(payload))
            }
        }

    suspend fun read(input: InputStream): StatsBackup =
        withContext(Dispatchers.IO) {
            GZIPInputStream(input.buffered()).use { gzip ->
                val magic = ByteArray(StatsBackup.MAGIC.length)
                var offset = 0
                while (offset < magic.size) {
                    val read = gzip.read(magic, offset, magic.size - offset)
                    require(read > 0) { "Not a Phocid statistics backup" }
                    offset += read
                }
                require(String(magic, Charsets.US_ASCII) == StatsBackup.MAGIC) {
                    "Not a Phocid statistics backup"
                }
                val payload = cbor.decodeFromByteArray<StatsBackup>(gzip.readBytes())
                require(payload.version <= StatsBackup.VERSION) {
                    "Backup was created by a newer version"
                }
                payload
            }
        }

    /**
     * [replace] clears the local database first. Merge keeps the larger value for overlapping
     * totals/days, so importing the same backup twice does not double the numbers.
     */
    suspend fun restore(payload: StatsBackup, replace: Boolean) =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                if (replace) {
                    db.eventDao().clear()
                    db.dailyDao().clear()
                    db.totalsDao().clear()
                    db.aliasDao().clear()
                    db.trackDao().clear()
                }

                val uuidToRowId = HashMap<String, Long>(payload.tracks.size)
                payload.tracks.forEach { track ->
                    val existing = db.trackDao().rowIdOfUuid(track.uuid)
                    uuidToRowId[track.uuid] =
                        existing
                            ?: db.trackDao()
                                .insert(
                                    TrackEntity(
                                        uuid = track.uuid,
                                        title = track.title,
                                        artist = track.artist,
                                        album = track.album,
                                        durationMillis = track.durationMillis,
                                        createdAt = track.createdAt,
                                    )
                                )
                }

                db.aliasDao()
                    .putAll(
                        payload.aliases.mapNotNull { alias ->
                            if (alias.kind !in AliasKind.PORTABLE_ALIAS_KINDS)
                                return@mapNotNull null
                            uuidToRowId[alias.uuid]?.let {
                                TrackAliasEntity(alias.kind, alias.value, it)
                            }
                        }
                    )

                if (replace) {
                    db.totalsDao()
                        .insertAll(
                            payload.totals.mapNotNull { total ->
                                uuidToRowId[total.uuid]?.let {
                                    TrackTotalsEntity(
                                        trackRowId = it,
                                        totalCount = total.totalCount,
                                        totalMillis = total.totalMillis,
                                        firstPlayedAt = total.firstPlayedAt,
                                        lastPlayedAt = total.lastPlayedAt,
                                    )
                                }
                            }
                        )
                    db.dailyDao()
                        .insertAll(
                            payload.daily.mapNotNull { day ->
                                uuidToRowId[day.uuid]?.let {
                                    TrackDailyEntity(
                                        trackRowId = it,
                                        epochDay = day.epochDay,
                                        playCount = day.playCount,
                                        playedMillis = day.playedMillis,
                                        lastPlayedAt = day.lastPlayedAt,
                                    )
                                }
                            }
                        )
                } else {
                    payload.totals.forEach { total ->
                        val rowId = uuidToRowId[total.uuid] ?: return@forEach
                        val current = db.totalsDao().byRowId(rowId)
                        db.totalsDao()
                            .insertAll(
                                listOf(
                                    TrackTotalsEntity(
                                        trackRowId = rowId,
                                        totalCount =
                                            maxOf(current?.totalCount ?: 0, total.totalCount),
                                        totalMillis =
                                            maxOf(
                                                current?.totalMillis ?: 0L,
                                                total.totalMillis,
                                            ),
                                        firstPlayedAt =
                                            minOf(
                                                current?.firstPlayedAt ?: Long.MAX_VALUE,
                                                total.firstPlayedAt,
                                            ),
                                        lastPlayedAt =
                                            maxOf(
                                                current?.lastPlayedAt ?: 0L,
                                                total.lastPlayedAt,
                                            ),
                                    )
                                )
                            )
                    }

                    val existing =
                        db.dailyDao().since(Int.MIN_VALUE).associateBy {
                            it.trackRowId to it.epochDay
                        }
                    db.dailyDao()
                        .insertAll(
                            payload.daily.mapNotNull { day ->
                                val rowId = uuidToRowId[day.uuid] ?: return@mapNotNull null
                                val current = existing[rowId to day.epochDay]
                                TrackDailyEntity(
                                    trackRowId = rowId,
                                    epochDay = day.epochDay,
                                    playCount = maxOf(current?.playCount ?: 0, day.playCount),
                                    playedMillis =
                                        maxOf(
                                            current?.playedMillis ?: 0L,
                                            day.playedMillis,
                                        ),
                                    lastPlayedAt =
                                        maxOf(
                                            current?.lastPlayedAt ?: 0L,
                                            day.lastPlayedAt,
                                        ),
                                )
                            }
                        )
                }
            }
        }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.eventDao().clear()
                db.dailyDao().clear()
                db.totalsDao().clear()
                db.aliasDao().clear()
                db.trackDao().clear()
            }
        }
}
