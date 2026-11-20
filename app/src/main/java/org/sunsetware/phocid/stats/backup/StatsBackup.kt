package org.sunsetware.phocid.stats.backup

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.DateTimeException
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
        // Version 2 adds the global active-day list. Version 3 adds lifecycle counters, the
        // per-day boundaries captured when each day was written, and the earliest observed
        // playback instant. Local-only aliases are still filtered from the payload.
        const val VERSION = 3
        const val MIN_VERSION = 1
        const val MAGIC = "MSTATS1\u0000"
        /** Upper bound on the decompressed CBOR payload. Guards against gzip bombs. */
        const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        /**
         * Upper bound on any single list in a payload. Enforced on the raw CBOR structure before
         * decoding ([CborStructureGuard]) and re-checked in validate() as defense in depth.
         */
        const val MAX_LIST_ENTRIES = 1_000_000
        /** Plausible epoch-day range (roughly ±8,000 years); blocks Int.MIN/MAX overflow. */
        const val MIN_EPOCH_DAY = -3_000_000
        const val MAX_EPOCH_DAY = 3_000_000
        /** UTC offsets never exceed 18 hours in either direction. */
        const val MAX_UTC_OFFSET_MINUTES = 18 * 60
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
    val startedCount: Int = 0,
    val completedCount: Int = 0,
    val skipCount: Int = 0,
    val completedMillis: Long = 0,
)

@Serializable
data class BackupDaily(
    val uuid: String,
    val epochDay: Int,
    val playCount: Int,
    val playedMillis: Long,
    val lastPlayedAt: Long,
    val firstPlayedAt: Long? = null,
    val startedCount: Int = 0,
    val completedCount: Int = 0,
    val skipCount: Int = 0,
    val completedMillis: Long = 0,
    val dayStartMs: Long? = null,
    val dayEndMs: Long? = null,
    val utcOffsetMinutes: Int? = null,
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
                            startedCount = total.startedCount,
                            completedCount = total.completedCount,
                            skipCount = total.skipCount,
                            completedMillis = total.completedMillis,
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
                                firstPlayedAt = day.firstPlayedAt,
                                startedCount = day.startedCount,
                                completedCount = day.completedCount,
                                skipCount = day.skipCount,
                                completedMillis = day.completedMillis,
                                dayStartMs = day.dayStartMs,
                                dayEndMs = day.dayEndMs,
                                utcOffsetMinutes = day.utcOffsetMinutes,
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
                // A gzip stream can expand by orders of magnitude. Read into a bounded buffer
                // instead of gzip.readBytes(), which would inflate an arbitrary amount into the
                // heap before CBOR decoding even starts.
                val bounded = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = gzip.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= StatsBackup.MAX_PAYLOAD_BYTES) {
                        "Backup is too large after decompression"
                    }
                    bounded.write(buffer, 0, read)
                }
                val bytes = bounded.toByteArray()
                // The list caps in validate() run only after decodeFromByteArray has built the
                // whole object graph. Reject oversized structures on the raw bytes first, so a
                // crafted payload cannot fan out into a huge heap during decoding.
                CborStructureGuard.check(bytes)
                val payload = cbor.decodeFromByteArray<StatsBackup>(bytes)
                require(payload.version in StatsBackup.MIN_VERSION..StatsBackup.VERSION) {
                    if (payload.version > StatsBackup.VERSION)
                        "Backup was created by a newer version"
                    else "Backup has an unsupported version ${payload.version}"
                }
                payload.validate()
                payload
            }
        }

    /** Rejects semantically invalid numeric fields before they reach the database. */
    private fun StatsBackup.validate() {
        fun requireNonNegative(value: Long, field: String) {
            require(value >= 0) { "Backup has a negative $field: $value" }
        }

        require(tracks.size <= StatsBackup.MAX_LIST_ENTRIES) { "Backup has too many tracks" }
        require(aliases.size <= StatsBackup.MAX_LIST_ENTRIES) { "Backup has too many aliases" }
        require(totals.size <= StatsBackup.MAX_LIST_ENTRIES) { "Backup has too many totals" }
        require(daily.size <= StatsBackup.MAX_LIST_ENTRIES) { "Backup has too many daily rows" }
        require(activeDays.size <= StatsBackup.MAX_LIST_ENTRIES) {
            "Backup has too many active days"
        }
        requireNonNegative(exportedAt, "exportedAt")

        tracks.forEach { track ->
            requireNonNegative(track.durationMillis, "track durationMillis")
            requireNonNegative(track.createdAt, "track createdAt")
        }
        totals.forEach { total ->
            requireNonNegative(total.totalCount.toLong(), "totalCount")
            requireNonNegative(total.totalMillis, "totalMillis")
            requireNonNegative(total.firstPlayedAt, "firstPlayedAt")
            requireNonNegative(total.lastPlayedAt, "lastPlayedAt")
            requireNonNegative(total.startedCount.toLong(), "startedCount")
            requireNonNegative(total.completedCount.toLong(), "completedCount")
            requireNonNegative(total.skipCount.toLong(), "skipCount")
            requireNonNegative(total.completedMillis, "completedMillis")
        }
        daily.forEach { day ->
            require(day.epochDay in StatsBackup.MIN_EPOCH_DAY..StatsBackup.MAX_EPOCH_DAY) {
                "Backup daily row has an implausible epochDay ${day.epochDay}"
            }
            requireNonNegative(day.playCount.toLong(), "playCount")
            requireNonNegative(day.playedMillis, "playedMillis")
            requireNonNegative(day.lastPlayedAt, "lastPlayedAt")
            requireNonNegative(day.startedCount.toLong(), "startedCount")
            requireNonNegative(day.completedCount.toLong(), "completedCount")
            requireNonNegative(day.skipCount.toLong(), "skipCount")
            requireNonNegative(day.completedMillis, "completedMillis")
            day.firstPlayedAt?.let { requireNonNegative(it, "firstPlayedAt") }
            day.dayStartMs?.let { requireNonNegative(it, "dayStartMs") }
            day.dayEndMs?.let { requireNonNegative(it, "dayEndMs") }
            val startMs = day.dayStartMs
            val endMs = day.dayEndMs
            if (startMs != null && endMs != null) {
                require(startMs <= endMs) { "Backup daily row has dayStartMs > dayEndMs" }
            }
            day.utcOffsetMinutes?.let { offset ->
                require(
                    offset in
                        -StatsBackup.MAX_UTC_OFFSET_MINUTES..StatsBackup.MAX_UTC_OFFSET_MINUTES,
                ) {
                    "Backup daily row has an implausible utcOffsetMinutes $offset"
                }
            }
        }
        activeDays.forEach { day ->
            require(day in StatsBackup.MIN_EPOCH_DAY..StatsBackup.MAX_EPOCH_DAY) {
                "Backup has an implausible active day $day"
            }
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

                // Daily rows keep their source calendar days: a cross-timezone restore must not
                // remap them. Their origins are mixed (an exported backup may itself contain
                // days recorded on another device), so they cannot be validated against a single
                // zone/export timestamp. Only the zone identifier's format is checked here.
                try {
                    ZoneId.of(payload.zoneId)
                } catch (e: DateTimeException) {
                    throw IllegalArgumentException(
                        "Backup has an invalid time zone ID \"${payload.zoneId}\"",
                        e,
                    )
                }

                if (replace) {
                    // A replacement owns the whole local aggregate set, so pending recovery rows
                    // that described it are no longer meaningful and must not be replayed.
                    db.outboxDao().clear()
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
                            "Backup track $uuid is ambiguous: it matches local rows ${rows.sorted().joinToString()}",
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
                            "Backup tracks ${groupUuids.sorted().joinToString()} share a portable alias but match different local rows ${groupRows.sorted().joinToString()}",
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
                                        ),
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
                            "Backup alias ${alias.kind}:${alias.value} for track ${alias.uuid} belongs to local row $current; refusing to move it to row $target",
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
            val startedCount = total.startedCountFor()
            grouped[rowId] =
                if (current == null) {
                    TrackTotalsEntity(
                        trackRowId = rowId,
                        totalCount = total.totalCount,
                        totalMillis = total.totalMillis,
                        firstPlayedAt = total.firstPlayedAt,
                        lastPlayedAt = total.lastPlayedAt,
                        startedCount = startedCount,
                        completedCount = total.completedCount,
                        skipCount = total.skipCount,
                        completedMillis = total.completedMillis,
                    )
                } else {
                    TrackTotalsEntity(
                        trackRowId = rowId,
                        totalCount = maxOf(current.totalCount, total.totalCount),
                        totalMillis = maxOf(current.totalMillis, total.totalMillis),
                        firstPlayedAt = minOf(current.firstPlayedAt, total.firstPlayedAt),
                        lastPlayedAt =
                            maxOf(
                                current.lastPlayedAt,
                                total.lastPlayedAt,
                                minOf(current.firstPlayedAt, total.firstPlayedAt),
                            ),
                        startedCount = maxOf(current.startedCount, startedCount),
                        completedCount = maxOf(current.completedCount, total.completedCount),
                        skipCount = maxOf(current.skipCount, total.skipCount),
                        completedMillis = maxOf(current.completedMillis, total.completedMillis),
                    )
                }
        }

        val rows =
            if (replace) {
                grouped.values.toList()
            } else {
                grouped.values.map { group ->
                    val current = db.totalsDao().byRowId(group.trackRowId) ?: return@map group
                    val firstPlayedAt = minOf(current.firstPlayedAt, group.firstPlayedAt)
                    TrackTotalsEntity(
                        trackRowId = group.trackRowId,
                        totalCount = maxOf(current.totalCount, group.totalCount),
                        totalMillis = maxOf(current.totalMillis, group.totalMillis),
                        firstPlayedAt = firstPlayedAt,
                        lastPlayedAt =
                            maxOf(current.lastPlayedAt, group.lastPlayedAt, firstPlayedAt),
                        startedCount = maxOf(current.startedCount, group.startedCount),
                        completedCount = maxOf(current.completedCount, group.completedCount),
                        skipCount = maxOf(current.skipCount, group.skipCount),
                        completedMillis = maxOf(current.completedMillis, group.completedMillis),
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
            val firstPlayedAt = day.firstPlayedAtFor()
            val startedCount = day.startedCountFor()
            grouped[key] =
                if (current == null) {
                    TrackDailyEntity(
                        trackRowId = rowId,
                        epochDay = day.epochDay,
                        playCount = day.playCount,
                        playedMillis = day.playedMillis,
                        lastPlayedAt = maxOf(day.lastPlayedAt, firstPlayedAt ?: 0L),
                        firstPlayedAt = firstPlayedAt,
                        startedCount = startedCount,
                        completedCount = day.completedCount,
                        skipCount = day.skipCount,
                        completedMillis = day.completedMillis,
                        dayStartMs = day.dayStartMs,
                        dayEndMs = day.dayEndMs,
                        utcOffsetMinutes = day.utcOffsetMinutes,
                    )
                } else {
                    TrackDailyEntity(
                        trackRowId = rowId,
                        epochDay = day.epochDay,
                        playCount = maxOf(current.playCount, day.playCount),
                        playedMillis = maxOf(current.playedMillis, day.playedMillis),
                        lastPlayedAt =
                            maxOf(
                                current.lastPlayedAt,
                                day.lastPlayedAt,
                                firstPlayedAt ?: 0L,
                            ),
                        firstPlayedAt =
                            listOfNotNull(current.firstPlayedAt, firstPlayedAt).minOrNull(),
                        startedCount = maxOf(current.startedCount, startedCount),
                        completedCount = maxOf(current.completedCount, day.completedCount),
                        skipCount = maxOf(current.skipCount, day.skipCount),
                        completedMillis = maxOf(current.completedMillis, day.completedMillis),
                        dayStartMs = current.dayStartMs ?: day.dayStartMs,
                        dayEndMs = current.dayEndMs ?: day.dayEndMs,
                        utcOffsetMinutes = current.utcOffsetMinutes ?: day.utcOffsetMinutes,
                    )
                }
        }

        val rows =
            if (replace) {
                grouped.values.toList()
            } else {
                // Only load the exact (trackRowId, epochDay) pairs this backup can overlap, in
                // bounded chunks. The old since(Int.MIN_VALUE) scanned the entire track_daily
                // table on every merge restore; the interim IN(rowIds) x IN(epochDays) form was
                // still a cartesian grid that over-fetched every cross combination for sparse
                // backups and issued a multiplicative number of chunk-pair queries. Row-value IN
                // (SQLite >= 3.15) matches whole pairs, so both the fetched rows and the query
                // count are proportional to the payload alone.
                val existing = HashMap<Pair<Long, Int>, TrackDailyEntity>()
                // 400 pairs = 800 bind parameters, well below SQLite's 999-parameter limit.
                for (pairChunk in grouped.keys.chunked(400)) {
                    val tuples = pairChunk.joinToString(",") { "(?,?)" }
                    val args = pairChunk.flatMap { listOf<Any>(it.first, it.second) }.toTypedArray()
                    db.dailyDao()
                        .byExactPairs(
                            SimpleSQLiteQuery(
                                "SELECT * FROM track_daily WHERE (trackRowId, epochDay) IN (VALUES $tuples)",
                                args,
                            ),
                        )
                        .forEach { existing[it.trackRowId to it.epochDay] = it }
                }
                grouped.values.map { group ->
                    val current = existing[group.trackRowId to group.epochDay] ?: return@map group
                    val firstPlayedAt =
                        listOfNotNull(current.firstPlayedAt, group.firstPlayedAt).minOrNull()
                    TrackDailyEntity(
                        trackRowId = group.trackRowId,
                        epochDay = group.epochDay,
                        playCount = maxOf(current.playCount, group.playCount),
                        playedMillis = maxOf(current.playedMillis, group.playedMillis),
                        lastPlayedAt =
                            maxOf(current.lastPlayedAt, group.lastPlayedAt, firstPlayedAt ?: 0L),
                        firstPlayedAt = firstPlayedAt,
                        startedCount = maxOf(current.startedCount, group.startedCount),
                        completedCount = maxOf(current.completedCount, group.completedCount),
                        skipCount = maxOf(current.skipCount, group.skipCount),
                        completedMillis = maxOf(current.completedMillis, group.completedMillis),
                        dayStartMs = current.dayStartMs ?: group.dayStartMs,
                        dayEndMs = current.dayEndMs ?: group.dayEndMs,
                        utcOffsetMinutes = current.utcOffsetMinutes ?: group.utcOffsetMinutes,
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
                // Pending outbox time belongs to the aggregate set being cleared, so it goes in the
                // same transaction. A later recovery must not resurrect data the user deleted.
                db.outboxDao().clear()
                db.eventDao().clear()
                db.dailyDao().clear()
                db.totalsDao().clear()
                db.activeDayDao().clear()
                db.aliasDao().clear()
                db.trackDao().clear()
            }
        }
}

/**
 * Pre-decode structural guard for untrusted CBOR payloads.
 *
 * [StatsBackupManager.read] bounds the inflated payload to [StatsBackup.MAX_PAYLOAD_BYTES], but a
 * few megabytes of CBOR can still describe millions of list elements. Definite-length array/map
 * headers declare a count that kotlinx.serialization feeds into collection sizing before the
 * elements are read, and indefinite-length containers (which [Cbor.Default] writes, so Phocid's own
 * exports use them) declare no count at all. Enforcing [StatsBackup.MAX_LIST_ENTRIES] only after
 * [Cbor.decodeFromByteArray] would let the object graph blow up first. This scanner walks the raw
 * bytes instead, allocating nothing per element, and rejects oversized or malformed structures
 * before any decoding happens:
 * - definite-length array/map counts are checked against the entry cap and against the bytes
 *   actually remaining (every child needs at least one byte), so a lying header cannot pre-size a
 *   huge collection;
 * - indefinite-length containers are counted element by element and rejected as soon as they exceed
 *   the cap;
 * - string and byte-string bodies are skipped, never materialized;
 * - nesting is capped so neither this explicit frame stack nor the recursive decoder behind it can
 *   be exhausted.
 *
 * Residual limitation: within the caps, decoding still amplifies payload bytes by a bounded
 * constant factor (boxed numbers, strings, row objects). That factor is inherent to object-graph
 * decoding; this guard removes the unbounded, header-driven fan-out, not the constant.
 */
private object CborStructureGuard {
    private const val MAX_DEPTH = 32
    private const val INDEFINITE = -1L

    fun check(bytes: ByteArray) {
        // One frame per open container: remaining definite children (or INDEFINITE), children
        // seen so far, and the child cap. Frame 0 is a sentinel expecting one top-level item.
        val remaining = LongArray(MAX_DEPTH)
        val seen = LongArray(MAX_DEPTH)
        val limit = LongArray(MAX_DEPTH)
        var depth = 1
        remaining[0] = 1
        var pos = 0

        // Marks one item as consumed at the current depth, cascading through finished frames.
        fun consumeOne() {
            while (depth > 0) {
                val d = depth - 1
                if (remaining[d] == INDEFINITE) {
                    seen[d]++
                    require(seen[d] <= limit[d]) {
                        "Backup contains an oversized CBOR container (more than ${StatsBackup.MAX_LIST_ENTRIES} entries)"
                    }
                    return
                }
                remaining[d]--
                if (remaining[d] > 0) return
                depth--
            }
        }

        fun pushFrame(children: Long, cap: Long) {
            require(depth < MAX_DEPTH) { "Backup CBOR structure is nested too deeply" }
            remaining[depth] = children
            seen[depth] = 0
            limit[depth] = cap
            depth++
        }

        // Reads the argument of a header whose additional-info is [info], advancing pos. For
        // info < 24 the argument is the info itself and no extra bytes follow.
        fun argument(info: Int): Long {
            if (info < 24) return info.toLong()
            val width =
                when (info) {
                    24 -> 1
                    25 -> 2
                    26 -> 4
                    27 -> 8
                    else -> throw IllegalArgumentException("Backup CBOR payload is malformed")
                }
            require(pos + width <= bytes.size) { "Backup CBOR payload is truncated" }
            var value = 0L
            repeat(width) { value = (value shl 8) or (bytes[pos++].toInt() and 0xFF).toLong() }
            return value
        }

        fun skipStringBody(length: Long) {
            require(length >= 0 && length <= (bytes.size - pos).toLong()) {
                "Backup CBOR payload is truncated"
            }
            pos += length.toInt()
        }

        val arrayCap = StatsBackup.MAX_LIST_ENTRIES.toLong()
        val mapCap = 2L * StatsBackup.MAX_LIST_ENTRIES
        while (depth > 0) {
            require(pos < bytes.size) { "Backup CBOR payload is truncated" }
            val header = bytes[pos++].toInt() and 0xFF
            val major = header ushr 5
            val info = header and 0x1F
            if (info == 31) {
                when (major) {
                    // BREAK closes the nearest indefinite-length container.
                    7 -> {
                        require(depth > 1 && remaining[depth - 1] == INDEFINITE) {
                            "Backup CBOR payload has an unexpected break"
                        }
                        depth--
                        consumeOne()
                    }
                    4 -> pushFrame(INDEFINITE, arrayCap)
                    5 -> pushFrame(INDEFINITE, mapCap)
                    // Indefinite-length string: skip definite chunks until BREAK.
                    2,
                    3 -> {
                        while (true) {
                            require(pos < bytes.size) { "Backup CBOR payload is truncated" }
                            val chunk = bytes[pos++].toInt() and 0xFF
                            if (chunk == 0xFF) break
                            require(chunk ushr 5 == major && chunk and 0x1F != 31) {
                                "Backup CBOR payload is malformed"
                            }
                            skipStringBody(argument(chunk and 0x1F))
                        }
                        consumeOne()
                    }
                    else -> throw IllegalArgumentException("Backup CBOR payload is malformed")
                }
                continue
            }
            val value = argument(info)
            when (major) {
                0,
                1,
                7 -> consumeOne()
                2,
                3 -> {
                    skipStringBody(value)
                    consumeOne()
                }
                4 -> {
                    require(value in 0..arrayCap) {
                        "Backup declares an oversized CBOR array ($value entries)"
                    }
                    require(value <= (bytes.size - pos).toLong()) {
                        "Backup declares more CBOR entries than the payload can hold"
                    }
                    if (value == 0L) consumeOne() else pushFrame(value, arrayCap)
                }
                5 -> {
                    require(value in 0..arrayCap) {
                        "Backup declares an oversized CBOR map ($value pairs)"
                    }
                    require(2 * value <= (bytes.size - pos).toLong()) {
                        "Backup declares more CBOR entries than the payload can hold"
                    }
                    if (value == 0L) consumeOne() else pushFrame(2 * value, mapCap)
                }
                // A tag wraps exactly one item.
                6 -> pushFrame(1, 1)
                else -> throw IllegalArgumentException("Backup CBOR payload is malformed")
            }
        }
    }
}

/**
 * A legacy row may have no lifecycle counters. Every counted play, completed session, skipped
 * session, or listening timestamp proves at least one start, so raise the counter to its provable
 * lower bound instead of leaving a start-only row at zero.
 */
private fun BackupTotals.startedCountFor(): Int =
    maxOf(
        startedCount,
        totalCount,
        completedCount + skipCount,
        if (lastPlayedAt > 0L) 1 else 0,
    )

/**
 * Per-day variant of [startedCountFor]. A selected and immediately skipped track has no
 * [playCount], so the timestamp is the only evidence that it was started.
 */
private fun BackupDaily.startedCountFor(): Int =
    maxOf(
        startedCount,
        playCount,
        completedCount + skipCount,
        if (lastPlayedAt > 0L) 1 else 0,
    )

/**
 * Legacy backups did not record the first instant of a day. The last observed instant is the only
 * timestamp they carry, so it becomes the earliest known value. A zero-length skip stays unknown.
 */
private fun BackupDaily.firstPlayedAtFor(): Long? = firstPlayedAt ?: lastPlayedAt.takeIf { it > 0L }

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
