package org.sunsetware.phocid.agent

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.globals.GlobalData
import org.sunsetware.phocid.stats.db.DayBucket
import org.sunsetware.phocid.stats.db.RangeSummary
import org.sunsetware.phocid.stats.db.TrackAggregate
import org.sunsetware.phocid.stats.domain.StatsRange
import org.sunsetware.phocid.stats.domain.StatsSort

/**
 * Read-only query surface for the companion agent bridge.
 *
 * The service is exported with a signature permission. It never accepts writes and it never
 * exposes the filesystem path by default.
 */
class AgentQueryService : Service() {
    private val binder =
        object : IPhocidQuery.Stub() {
            override fun capabilities(): String = guarded { capabilitiesJson() }

            override fun searchTracks(requestJson: String): String =
                guarded { searchTracksJson(requestJson) }

            override fun getTrack(libraryId: Long): String = guarded { getTrackJson(libraryId) }

            override fun statsSummary(range: String): String =
                guarded { statsSummaryJson(range) }

            override fun statsTopTracks(requestJson: String): String =
                guarded { statsTopTracksJson(requestJson) }

            override fun statsDaily(range: String): String = guarded { statsDailyJson(range) }

            override fun resolveStatsUuid(libraryId: Long): String =
                guarded { resolveStatsUuidJson(libraryId) }
        }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun capabilitiesJson(): String {
        val index = GlobalData.libraryIndex.value
        return JSONObject()
            .put("apiVersion", API_VERSION)
            .put("libraryRevision", index.flowVersion)
            .put("zoneId", ZoneId.systemDefault().id)
            .put("maxLimit", MAX_LIMIT)
            .put("ranges", stringArray(RANGES))
            .put("fields", stringArray(TRACK_FIELDS))
            .put("statsDailyRetentionDays", 1095)
            .toString()
    }

    private fun searchTracksJson(requestJson: String): String {
        val request = JSONObject(requestJson)
        val filter =
            SearchFilter(
                query = request.optString("query", "").trim(),
                title = request.optString("title", "").trim(),
                artist = request.optString("artist", "").trim(),
                album = request.optString("album", "").trim(),
                albumArtist = request.optString("albumArtist", "").trim(),
                genre = request.optString("genre", "").trim(),
                year = request.optIntOrNull("year"),
                yearFrom = request.optIntOrNull("yearFrom"),
                yearTo = request.optIntOrNull("yearTo"),
                format = request.optString("format", "").trim(),
                hasArtwork = request.optBooleanOrNull("hasArtwork"),
                minDurationMs = request.optLongOrNull("minDurationMs"),
                maxDurationMs = request.optLongOrNull("maxDurationMs"),
            )
        val sort = request.optString("sort", "title").lowercase()
        val descending = request.optBoolean("descending", false)
        val offset = request.optInt("offset", 0).coerceAtLeast(0)
        val limit = request.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val matches =
            GlobalData.libraryIndex.value.tracks.values
                .asSequence()
                .filter { filter.matches(it) }
                .toList()
        val comparator = comparatorFor(sort)
        val sorted = matches.sortedWith(if (descending) comparator.reversed() else comparator)
        val page = sorted.drop(offset).take(limit)

        return JSONObject()
            .put("total", sorted.size)
            .put("offset", offset)
            .put("limit", limit)
            .put("tracks", JSONArray().apply { page.forEach { put(trackJson(it)) } })
            .toString()
    }

    private fun getTrackJson(libraryId: Long): String {
        val track = GlobalData.libraryIndex.value.tracks[libraryId]
        if (track == null) {
            return errorJson("not_found", "No library track with id " + libraryId)
        }
        val statsUuid = statsUuidFor(track)
        return trackJson(track).put("statsUuid", statsUuid ?: JSONObject.NULL).toString()
    }

    private fun statsSummaryJson(range: String): String {
        val parsed = parseRange(range)
        if (parsed == null) {
            return errorJson("invalid_range", "Unknown range: " + range)
        }
        val summary = runBlocking { GlobalData.statsManager.repository.summary(parsed).first() }
        return summaryJson(range, summary)
    }

    private fun statsTopTracksJson(requestJson: String): String {
        val request = JSONObject(requestJson)
        val parsed = parseRange(request.optString("range", "30d"))
        if (parsed == null) {
            return errorJson("invalid_range", "Unknown range")
        }
        val sort = if (request.optString("sort", "plays").equals("time", true)) {
            StatsSort.TIME
        } else {
            StatsSort.PLAYS
        }
        val limit = request.optInt("limit", 20).coerceIn(1, MAX_LIMIT)
        val rows = runBlocking { GlobalData.statsManager.repository.topTracks(parsed, sort, limit).first() }
        return JSONObject()
            .put("range", request.optString("range", "30d"))
            .put("sort", if (sort == StatsSort.TIME) "time" else "plays")
            .put("tracks", JSONArray().apply { rows.forEach { put(aggregateJson(it)) } })
            .toString()
    }

    private fun statsDailyJson(range: String): String {
        val parsed = parseRange(range)
        if (parsed == null) {
            return errorJson("invalid_range", "Unknown range: " + range)
        }
        val days = runBlocking { GlobalData.statsManager.repository.dailyTrend(parsed).first() }
        return JSONObject()
            .put("range", range)
            .put("days", JSONArray().apply { days.forEach { put(dayJson(it)) } })
            .toString()
    }

    private fun resolveStatsUuidJson(libraryId: Long): String {
        val track = GlobalData.libraryIndex.value.tracks[libraryId]
        if (track == null) {
            return errorJson("not_found", "No library track with id " + libraryId)
        }
        val statsUuid = statsUuidFor(track)
        return JSONObject()
            .put("libraryId", libraryId)
            .put("statsUuid", statsUuid ?: JSONObject.NULL)
            .toString()
    }

    private fun statsUuidFor(track: Track): String? =
        runBlocking {
            val totals =
                GlobalData.statsManager.repository.lifetimeForTrack(track)
                    ?: return@runBlocking null
            GlobalData.statsManager.database.trackDao().byRowId(totals.trackRowId)?.uuid
        }

    private fun summaryJson(range: String, summary: RangeSummary): String =
        JSONObject()
            .put("range", range)
            .put("plays", summary.plays)
            .put("millis", summary.millis)
            .put("uniqueTracks", summary.uniqueTracks)
            .put("activeDays", summary.activeDays)
            .put("zoneId", ZoneId.systemDefault().id)
            .toString()

    private fun aggregateJson(row: TrackAggregate): JSONObject =
        JSONObject()
            .put("uuid", row.uuid)
            .put("title", row.title)
            .put("artist", row.artist)
            .put("album", row.album)
            .put("durationMs", row.durationMillis)
            .put("plays", row.plays)
            .put("millis", row.millis)
            .put("lastPlayedAt", row.lastPlayedAt)

    private fun dayJson(day: DayBucket): JSONObject =
        JSONObject()
            .put("epochDay", day.epochDay)
            .put("date", LocalDate.ofEpochDay(day.epochDay.toLong()).toString())
            .put("plays", day.plays)
            .put("millis", day.millis)

    private fun trackJson(track: Track): JSONObject =
        JSONObject()
            .put("libraryId", track.id)
            .put("fileName", track.fileName)
            .putNullable("title", track.title)
            .put("artists", stringArray(track.artists))
            .putNullable("album", track.album)
            .put("albumArtists", stringArray(track.albumArtists))
            .put("genres", stringArray(track.genres))
            .putNullable("year", track.year)
            .putNullable("originalYear", track.originalYear)
            .putNullable("trackNumber", track.trackNumber)
            .putNullable("discNumber", track.discNumber)
            .put("durationMs", track.duration.inWholeMilliseconds)
            .put("size", track.size)
            .put("format", track.format)
            .put("sampleRate", track.sampleRate)
            .put("bitRate", track.bitRate)
            .put("bitDepth", track.bitDepth)
            .put("hasArtwork", track.hasArtwork)
            .putNullable("comment", track.comment)
            .putNullable("unsyncedLyrics", track.unsyncedLyrics)
            .put("dateAdded", track.dateAdded)

    private fun comparatorFor(sort: String): Comparator<Track> =
        when (sort) {
            "artist" -> compareBy { it.artists.joinToString(", ").lowercase() }
            "album" -> compareBy { (it.album ?: "").lowercase() }
            "year" -> compareBy { it.year ?: Int.MIN_VALUE }
            "dateadded", "date_added" -> compareBy { it.dateAdded }
            "duration" -> compareBy { it.duration.inWholeMilliseconds }
            else -> compareBy { (it.title ?: it.fileName).lowercase() }
        }

    private fun parseRange(raw: String): StatsRange? =
        when (raw.trim().lowercase()) {
            "7d", "days_7" -> StatsRange.DAYS_7
            "30d", "days_30" -> StatsRange.DAYS_30
            "month", "calendar_month" -> StatsRange.CALENDAR_MONTH
            "90d", "days_90" -> StatsRange.DAYS_90
            "365d", "days_365" -> StatsRange.DAYS_365
            "year", "calendar_year" -> StatsRange.CALENDAR_YEAR
            "3y", "years_3" -> StatsRange.YEARS_3
            "all" -> StatsRange.ALL
            else -> null
        }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
        if (has(name) && !isNull(name)) optBoolean(name) else null

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun stringArray(values: List<String>): JSONArray =
        JSONArray().apply { values.forEach { put(it) } }

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    private inline fun guarded(block: () -> String): String {
        return try {
            if (!GlobalData.initialized.get()) {
                errorJson("not_ready", "Phocid is still initializing")
            } else {
                block()
            }
        } catch (t: Throwable) {
            errorJson("internal_error", t.message ?: t.javaClass.simpleName)
        }
    }

    private data class SearchFilter(
        val query: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val genre: String,
        val year: Int?,
        val yearFrom: Int?,
        val yearTo: Int?,
        val format: String,
        val hasArtwork: Boolean?,
        val minDurationMs: Long?,
        val maxDurationMs: Long?,
    ) {
        fun matches(track: Track): Boolean {
            if (query.isNotBlank()) {
                val haystack = mutableListOf<String>()
                track.title?.let { haystack.add(it) }
                track.album?.let { haystack.add(it) }
                haystack.add(track.fileName)
                haystack.addAll(track.artists)
                haystack.addAll(track.albumArtists)
                haystack.addAll(track.genres)
                if (haystack.none { it.contains(query, ignoreCase = true) }) return false
            }
            if (title.isNotBlank() &&
                track.title?.contains(title, ignoreCase = true) != true
            ) {
                return false
            }
            if (artist.isNotBlank() &&
                track.artists.none { it.contains(artist, ignoreCase = true) }
            ) {
                return false
            }
            if (album.isNotBlank() &&
                track.album?.contains(album, ignoreCase = true) != true
            ) {
                return false
            }
            if (albumArtist.isNotBlank() &&
                track.albumArtists.none { it.contains(albumArtist, ignoreCase = true) }
            ) {
                return false
            }
            if (genre.isNotBlank() &&
                track.genres.none { it.contains(genre, ignoreCase = true) }
            ) {
                return false
            }
            if (year != null && track.year != year) return false
            if (yearFrom != null && (track.year == null || track.year < yearFrom)) return false
            if (yearTo != null && (track.year == null || track.year > yearTo)) return false
            if (format.isNotBlank() && !track.format.equals(format, ignoreCase = true)) {
                return false
            }
            if (hasArtwork != null && track.hasArtwork != hasArtwork) return false
            val duration = track.duration.inWholeMilliseconds
            if (minDurationMs != null && duration < minDurationMs) return false
            if (maxDurationMs != null && duration > maxDurationMs) return false
            return true
        }
    }

    companion object {
        private const val API_VERSION = 1
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 200
        private val RANGES =
            listOf("7d", "30d", "month", "90d", "365d", "year", "3y", "all")
        private val TRACK_FIELDS =
            listOf(
                "libraryId",
                "fileName",
                "title",
                "artists",
                "album",
                "albumArtists",
                "genres",
                "year",
                "originalYear",
                "trackNumber",
                "discNumber",
                "durationMs",
                "size",
                "format",
                "sampleRate",
                "bitRate",
                "bitDepth",
                "hasArtwork",
                "comment",
                "unsyncedLyrics",
                "dateAdded",
            )
    }
}
