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
import org.sunsetware.phocid.stats.db.ActiveDayEntity
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.StatsRetention
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.db.TrackDailyEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.db.TrackTotalsEntity

/**
 * Backup format. It contains tracks, aliases, lifetime totals, per-day aggregates, and the global
 * lifetime active-day list. Raw play events are deliberately excluded: they are large and only
 * serve local history.
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
    /** Version 2+. Older payloads decode with an empty list and derive it from [daily]. */
    val activeDays: List<Int> = emptyList(),
) {
    companion object {
        // Version 2 adds the global active-day list; earlier fields keep their meaning. Local-only
        // aliases are still filtered from the payload.
        const val VERSION = 2
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
            val activeDays = mutableListOf<Int>()
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
                // Not pruned, and not tied to a track, so this is the full lifetime set.
                activeDays += db.activeDayDao().all().map { it.epochDay }
            }

            val payload =
                StatsBackup(
                    exportedAt = System.currentTimeMillis(),
                    zoneId = zoneId().id,
                    tracks = tracks,
                    aliases = aliases,
                    totals = totals,
                    daily = daily,
                    activeDays = activeDays,
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
     *
     * A backup UUID is matched against the local database before anything is written. It is only
     * trusted when its exact UUID or one of its portable aliases identifies a single local row.
     * Backup UUIDs that share a portable alias describe the same audio and are collapsed onto one
     * row.
     */
    suspend fun restore(payload: StatsBackup, replace: Boolean) =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val backupUuids = payload.tracks.map { it.uuid }
                require(backupUuids.distinct().size == backupUuids.size) {
                    "Backup contains duplicate track UUIDs"
                }
                val knownUuids = backupUuids.toHashSet()
                payload.aliases.forEach { alias ->
                    if (alias.kind in AliasKind.PORTABLE_ALIAS_KINDS) {
                        require(alias.uuid in knownUuids) {
                            "Backup alias ${alias.kind}:${alias.value} references unknown track UUID ${alias.uuid}"
                        }
                    }
                }
                payload.totals.forEach { total ->
                    require(total.uuid in knownUuids) {
                        "Backup totals reference unknown track UUID ${total.uuid}"
                    }
                }
                payload.daily.forEach { day ->
                    require(day.uuid in knownUuids) {
                        "Backup daily row references unknown track UUID ${day.uuid}"
                    }
                }

                if (replace) {
                    db.eventDao().clear()
                    db.dailyDao().clear()
                    db.totalsDao().clear()
                    db.activeDayDao().clear()
                    db.aliasDao().clear()
                    db.trackDao().clear()
                }

                val groups = groupBackupTracks(payload)

                // The exact UUID and every portable alias are equal evidence. Disagreement is a
                // hard error instead of silently preferring one clue.
                val candidates = HashMap<String, MutableSet<Long>>(payload.tracks.size)
                backupUuids.forEach { candidates[it] = HashSet() }
                if (!replace) {
                    payload.tracks.forEach { track ->
                        db.trackDao().rowIdOfUuid(track.uuid)?.let {
                            candidates.getValue(track.uuid).add(it)
                        }
                    }
                    payload.aliases.forEach { alias ->
                        if (alias.kind !in AliasKind.PORTABLE_ALIAS_KINDS) return@forEach
                        db.aliasDao().resolve(alias.kind, alias.value)?.let {
                            candidates.getValue(alias.uuid).add(it)
                        }
                    }
                }
                candidates.forEach { (uuid, rows) ->
                    if (rows.size > 1) {
                        throw IllegalArgumentException(
                            "Backup track $uuid is ambiguous: it matches local rows ${rows.sorted().joinToString()}"
                        )
                    }
                }
                val mappedRows = candidates.mapValues { it.value.singleOrNull() }

                val uuidToRowId = HashMap<String, Long>(payload.tracks.size)
                groups.forEach { group ->
                    val groupUuids = group.map { it.uuid }
                    val groupRows = groupUuids.mapNotNull { mappedRows.getValue(it) }.distinct()
                    if (groupRows.size > 1) {
                        throw IllegalArgumentException(
                            "Backup tracks ${groupUuids.sorted().joinToString()} share a portable alias but match different local rows ${groupRows.sorted().joinToString()}"
                        )
                    }
                    val rowId =
                        groupRows.singleOrNull()
                            ?: run {
                                // One group is one track, even when the backup carries several
                                // UUIDs for the same audio. The first track supplies metadata.
                                val first = group.first()
                                db.trackDao()
                                    .insert(
                                        TrackEntity(
                                            uuid = first.uuid,
                                            title = first.title,
                                            artist = first.artist,
                                            album = first.album,
                                            durationMillis = first.durationMillis,
                                            createdAt = first.createdAt,
                                        )
                                    )
                            }
                    groupUuids.forEach { uuidToRowId[it] = rowId }
                }

                payload.aliases.forEach { alias ->
                    if (alias.kind !in AliasKind.PORTABLE_ALIAS_KINDS) return@forEach
                    val target = uuidToRowId.getValue(alias.uuid)
                    val current = db.aliasDao().resolve(alias.kind, alias.value)
                    if (current != null && current != target) {
                        throw IllegalArgumentException(
                            "Backup alias ${alias.kind}:${alias.value} for track ${alias.uuid} belongs to local row $current; refusing to move it to row $target"
                        )
                    }
                    // IGNORE only fills a free alias. A conflicting clue is never stolen.
                    db.aliasDao().insertIgnore(TrackAliasEntity(alias.kind, alias.value, target))
                }

                writeTotals(payload, uuidToRowId, replace)
                writeDaily(payload, uuidToRowId, replace)
                writeActiveDays(payload)
            }
        }

    private suspend fun writeTotals(
        payload: StatsBackup,
        uuidToRowId: Map<String, Long>,
        replace: Boolean,
    ) {
        val grouped = LinkedHashMap<Long, TrackTotalsEntity>()
        payload.totals.forEach { total ->
            val rowId = uuidToRowId.getValue(total.uuid)
            val current = grouped[rowId]
            grouped[rowId] =
                if (current == null) {
                    TrackTotalsEntity(
                        trackRowId = rowId,
                        totalCount = total.totalCount,
                        totalMillis = total.totalMillis,
                        firstPlayedAt = total.firstPlayedAt,
                        lastPlayedAt = total.lastPlayedAt,
                    )
                } else {
                    TrackTotalsEntity(
                        trackRowId = rowId,
                        totalCount = maxOf(current.totalCount, total.totalCount),
                        totalMillis = maxOf(current.totalMillis, total.totalMillis),
                        firstPlayedAt = minOf(current.firstPlayedAt, total.firstPlayedAt),
                        lastPlayedAt = maxOf(current.lastPlayedAt, total.lastPlayedAt),
                    )
                }
        }

        val rows =
            if (replace) {
                grouped.values.toList()
            } else {
                grouped.values.map { group ->
                    val current = db.totalsDao().byRowId(group.trackRowId) ?: return@map group
                    TrackTotalsEntity(
                        trackRowId = group.trackRowId,
                        totalCount = maxOf(current.totalCount, group.totalCount),
                        totalMillis = maxOf(current.totalMillis, group.totalMillis),
                        firstPlayedAt = minOf(current.firstPlayedAt, group.firstPlayedAt),
                        lastPlayedAt = maxOf(current.lastPlayedAt, group.lastPlayedAt),
                    )
                }
            }
        db.totalsDao().insertAll(rows)
    }

    private suspend fun writeDaily(
        payload: StatsBackup,
        uuidToRowId: Map<String, Long>,
        replace: Boolean,
    ) {
        val grouped = LinkedHashMap<Pair<Long, Int>, TrackDailyEntity>()
        payload.daily.forEach { day ->
            val rowId = uuidToRowId.getValue(day.uuid)
            val key = rowId to day.epochDay
            val current = grouped[key]
            grouped[key] =
                if (current == null) {
                    TrackDailyEntity(
                        trackRowId = rowId,
                        epochDay = day.epochDay,
                        playCount = day.playCount,
                        playedMillis = day.playedMillis,
                        lastPlayedAt = day.lastPlayedAt,
                    )
                } else {
                    TrackDailyEntity(
                        trackRowId = rowId,
                        epochDay = day.epochDay,
                        playCount = maxOf(current.playCount, day.playCount),
                        playedMillis = maxOf(current.playedMillis, day.playedMillis),
                        lastPlayedAt = maxOf(current.lastPlayedAt, day.lastPlayedAt),
                    )
                }
        }

        val rows =
            if (replace) {
                grouped.values.toList()
            } else {
                val existing =
                    db.dailyDao().since(Int.MIN_VALUE).associateBy { it.trackRowId to it.epochDay }
                grouped.values.map { group ->
                    val current = existing[group.trackRowId to group.epochDay] ?: return@map group
                    TrackDailyEntity(
                        trackRowId = group.trackRowId,
                        epochDay = group.epochDay,
                        playCount = maxOf(current.playCount, group.playCount),
                        playedMillis = maxOf(current.playedMillis, group.playedMillis),
                        lastPlayedAt = maxOf(current.lastPlayedAt, group.lastPlayedAt),
                    )
                }
            }
        db.dailyDao().insertAll(rows)
    }

    /**
     * Version 2 payloads carry the global active-day list. A version 1 payload only has per-track
     * daily rows, so its distinct days are the best available lower bound.
     */
    private suspend fun writeActiveDays(payload: StatsBackup) {
        val days =
            if (payload.activeDays.isNotEmpty()) payload.activeDays
            else payload.daily.map { it.epochDay }
        db.activeDayDao().insertAll(days.distinct().map { ActiveDayEntity(it) })
    }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.eventDao().clear()
                db.dailyDao().clear()
                db.totalsDao().clear()
                db.activeDayDao().clear()
                db.aliasDao().clear()
                db.trackDao().clear()
            }
        }
}

private class BackupUuidUnionFind(uuids: Collection<String>) {
    private val parent = uuids.associateWith { it }.toMutableMap()

    fun find(uuid: String): String {
        var root = uuid
        while (parent.getValue(root) != root) root = parent.getValue(root)
        var current = uuid
        while (parent.getValue(current) != current) {
            val next = parent.getValue(current)
            parent[current] = root
            current = next
        }
        return root
    }

    fun union(left: String, right: String) {
        val leftRoot = find(left)
        val rightRoot = find(right)
        if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
    }
}

/**
 * Collapse backup tracks that share a portable alias into one identity group. Aliases are the only
 * evidence that two backup UUIDs describe the same audio, and each group later becomes one local
 * row.
 */
private fun groupBackupTracks(payload: StatsBackup): List<List<BackupTrack>> {
    val unionFind = BackupUuidUnionFind(payload.tracks.map { it.uuid })
    val uuidsByAlias = HashMap<Pair<Int, String>, MutableList<String>>()
    payload.aliases.forEach { alias ->
        if (alias.kind !in AliasKind.PORTABLE_ALIAS_KINDS) return@forEach
        uuidsByAlias.getOrPut(alias.kind to alias.value) { mutableListOf() }.add(alias.uuid)
    }
    uuidsByAlias.values.forEach { uuids ->
        val first = uuids.first()
        uuids.drop(1).forEach { unionFind.union(first, it) }
    }

    val groups = LinkedHashMap<String, MutableList<BackupTrack>>()
    payload.tracks.forEach { track ->
        groups.getOrPut(unionFind.find(track.uuid)) { mutableListOf() }.add(track)
    }
    return groups.values.toList()
}
