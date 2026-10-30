package org.sunsetware.phocid.agent

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.sunsetware.phocid.PlaybackService
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.globals.GlobalData
import org.sunsetware.phocid.sortkey.SortKeyKeys
import org.sunsetware.phocid.sortkey.SortKeyTarget
import org.sunsetware.phocid.stats.db.DayBucket
import org.sunsetware.phocid.stats.db.RangeSummary
import org.sunsetware.phocid.stats.db.TrackAggregate
import org.sunsetware.phocid.stats.domain.StatsRange
import org.sunsetware.phocid.stats.domain.StatsSort

/**
 * Read-only query surface for the companion agent bridge.
 *
 * The service is exported with a signature permission. It exposes read methods plus one opt-in
 * method for writing local sort keys. It never exposes the filesystem path by default.
 */
class AgentQueryService : Service() {
    private val binder =
        object : IPhocidQuery.Stub() {
            override fun capabilities(): String = guarded { capabilitiesJson() }

            override fun searchTracks(requestJson: String): String = guarded {
                searchTracksJson(requestJson)
            }

            override fun getTrack(libraryId: Long): String = guarded { getTrackJson(libraryId) }

            override fun statsSummary(range: String): String = guarded { statsSummaryJson(range) }

            override fun statsTopTracks(requestJson: String): String = guarded {
                statsTopTracksJson(requestJson)
            }

            override fun statsDaily(range: String): String = guarded { statsDailyJson(range) }

            override fun resolveStatsUuid(libraryId: Long): String = guarded {
                resolveStatsUuidJson(libraryId)
            }

            override fun listFacets(requestJson: String): String = guarded {
                listFacetsJson(requestJson)
            }

            override fun playerState(): String = guarded { playerStateJson() }

            override fun updateSortKeys(requestJson: String): String = guarded {
                updateSortKeysJson(requestJson)
            }
        }

    override fun onBind(intent: Intent?): IBinder = binder

    private val responseBudget = ResponseJsonBudget()

    private fun capabilitiesJson(): String {
        val index = GlobalData.libraryIndex.value
        return JSONObject()
            .put("apiVersion", API_VERSION)
            .put("libraryRevision", index.flowVersion)
            .put("zoneId", ZoneId.systemDefault().id)
            .put("maxLimit", MAX_LIMIT)
            .put("writeSortKeys", true)
            .put("ranges", stringArray(RANGES))
            .put("facetKinds", stringArray(FACET_KINDS))
            .put("fields", stringArray(TRACK_FIELDS))
            .put(
                "counts",
                JSONObject()
                    .put("tracks", index.tracks.size)
                    .put("artists", index.artists.size)
                    .put("albums", index.albums.size)
                    .put("albumArtists", index.albumArtists.size)
                    .put("genres", index.genres.size),
            )
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
                hasSortKey = request.optBooleanOrNull("hasSortKey"),
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
        val sorted =
            if (sort == "sortkey") {
                matches.sortedWith(sortKeyComparator(descending))
            } else {
                val comparator = comparatorFor(sort)
                matches.sortedWith(if (descending) comparator.reversed() else comparator)
            }
        val summaries =
            sorted.asSequence().drop(offset).take(limit).map { trackSummaryJson(it).toString() }
        return when (
            val result = responseBudget.searchPage(sorted.size, offset, limit, summaries)
        ) {
            is ResponseJsonBudget.SearchPageResult.Page -> result.json
            is ResponseJsonBudget.SearchPageResult.TooLarge ->
                errorJson("response_too_large", result.message)
        }
    }

    private fun listFacetsJson(requestJson: String): String {
        val request = JSONObject(requestJson)
        val kind = request.optString("kind", "").trim().lowercase()
        val query = request.optString("query", "").trim()
        val limit = request.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val offset = request.optInt("offset", 0).coerceAtLeast(0)
        val index = GlobalData.libraryIndex.value
        val collator = GlobalData.preferences.value.sortCollator

        return when (kind) {
            "artists",
            "artist" -> {
                val page =
                    pageFacets(
                        items = index.artists.values.toList(),
                        query = query,
                        limit = limit,
                        offset = offset,
                        compare = { left, right -> collator.compare(left, right) },
                        name = { it.name },
                        searchText = { it.name },
                    )
                facetResponse("artists", page) { artist ->
                        JSONObject()
                            .put("name", artist.name)
                            .put("trackCount", artist.tracks.size)
                            .put("albumCount", artist.albumSlices.size)
                    }
                    .toString()
            }
            "albums",
            "album" -> {
                val page =
                    pageFacets(
                        items = index.albums.values.toList(),
                        query = query,
                        limit = limit,
                        offset = offset,
                        compare = { left, right -> collator.compare(left, right) },
                        name = { it.name },
                        searchText = { it.name + " " + it.albumArtists.joinToString(" ") },
                    )
                facetResponse("albums", page) { album ->
                        JSONObject()
                            .put("name", album.name)
                            .put("albumArtists", stringArray(album.albumArtists))
                            .putNullable("year", album.year)
                            .putNullable("originalYear", album.originalYear)
                            .put("trackCount", album.tracks.size)
                    }
                    .toString()
            }
            "album_artists",
            "album_artist" -> {
                val page =
                    pageFacets(
                        items = index.albumArtists.values.toList(),
                        query = query,
                        limit = limit,
                        offset = offset,
                        compare = { left, right -> collator.compare(left, right) },
                        name = { it.name },
                        searchText = { it.name },
                    )
                facetResponse("album_artists", page) { albumArtist ->
                        JSONObject()
                            .put("name", albumArtist.name)
                            .put("trackCount", albumArtist.tracks.size)
                            .put("albumCount", albumArtist.albums.size)
                    }
                    .toString()
            }
            "genres",
            "genre" -> {
                val page =
                    pageFacets(
                        items = index.genres.values.toList(),
                        query = query,
                        limit = limit,
                        offset = offset,
                        compare = { left, right -> collator.compare(left, right) },
                        name = { it.name },
                        searchText = { it.name },
                    )
                facetResponse("genres", page) { genre ->
                        JSONObject().put("name", genre.name).put("trackCount", genre.tracks.size)
                    }
                    .toString()
            }
            else -> errorJson("invalid_kind", "Unknown facet kind: " + kind)
        }
    }

    private fun <T> facetResponse(
        kind: String,
        page: FacetPage<T>,
        toJson: (T) -> JSONObject,
    ): JSONObject =
        JSONObject()
            .put("kind", kind)
            .put("total", page.total)
            .put("offset", page.offset)
            .put("limit", page.limit)
            .put("items", JSONArray().apply { page.items.forEach { put(toJson(it)) } })

    private fun playerStateJson(): String {
        if (!PlaybackService.isRunning) {
            return inactivePlayerJson()
        }

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controller =
            try {
                MediaController.Builder(this, sessionToken)
                    .setApplicationLooper(Looper.getMainLooper())
                    .buildAsync()
                    .get(2, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return errorJson("player_unavailable", "Interrupted while connecting to player")
            } catch (e: Exception) {
                return errorJson("player_unavailable", e.message ?: e.javaClass.simpleName)
            }

        try {
            if (!PlaybackService.isRunning) {
                return inactivePlayerJson()
            }
            val snapshot =
                withController(controller) {
                    val hasMedia = controller.mediaItemCount > 0
                    PlayerSnapshot(
                        isPlaying = controller.isPlaying,
                        positionMs = controller.currentPosition.coerceAtLeast(0L),
                        durationMs = controller.duration,
                        hasMedia = hasMedia,
                        mediaId = controller.currentMediaItem?.mediaId,
                        queueLength = controller.mediaItemCount,
                        currentIndex = controller.currentMediaItemIndex,
                        shuffle = controller.shuffleModeEnabled,
                        repeatMode = controller.repeatMode,
                        playbackState = controller.playbackState,
                        sampledAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    )
                }
            val track =
                snapshot.mediaId?.toLongOrNull()?.let { GlobalData.libraryIndex.value.tracks[it] }
            return JSONObject()
                .put("active", true)
                .put("isPlaying", snapshot.isPlaying)
                .put("positionAvailable", snapshot.hasMedia)
                .putNullable("positionMs", if (snapshot.hasMedia) snapshot.positionMs else null)
                .putNullable(
                    "durationMs",
                    if (snapshot.durationMs > 0L) snapshot.durationMs else null,
                )
                .put("sampledAtElapsedRealtimeMs", snapshot.sampledAtElapsedRealtimeMs)
                .putNullable("currentMediaId", snapshot.mediaId)
                .putNullable("currentTrack", track?.let { playerTrackSummaryJson(it) })
                .put("queueLength", snapshot.queueLength)
                .putNullable(
                    "currentIndex",
                    if (snapshot.hasMedia) snapshot.currentIndex else null,
                )
                .put("shuffle", snapshot.shuffle)
                .put("repeat", repeatLabel(snapshot.repeatMode))
                .put("playbackState", playbackStateLabel(snapshot.playbackState))
                .toString()
        } catch (e: Exception) {
            return errorJson("player_unavailable", e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { withController(controller) { controller.release() } }
        }
    }

    private fun <T> withController(controller: MediaController, block: () -> T): T {
        val looper = controller.applicationLooper
        if (Looper.myLooper() == looper) {
            return block()
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        Handler(looper).post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out waiting for the MediaController thread")
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private data class PlayerSnapshot(
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val hasMedia: Boolean,
        val mediaId: String?,
        val queueLength: Int,
        val currentIndex: Int,
        val shuffle: Boolean,
        val repeatMode: Int,
        val playbackState: Int,
        val sampledAtElapsedRealtimeMs: Long,
    )

    private fun inactivePlayerJson(): String =
        JSONObject()
            .put("active", false)
            .put("isPlaying", false)
            .put("positionAvailable", false)
            .putNullable("positionMs", null)
            .putNullable("durationMs", null)
            .put("sampledAtElapsedRealtimeMs", 0L)
            .putNullable("currentMediaId", null)
            .putNullable("currentTrack", null)
            .put("queueLength", 0)
            .putNullable("currentIndex", null)
            .put("shuffle", false)
            .put("repeat", "off")
            .put("playbackState", "idle")
            .toString()

    private fun playerTrackSummaryJson(track: Track): JSONObject =
        JSONObject()
            .put("libraryId", track.id)
            .putNullable("title", track.title)
            .put("artists", stringArray(track.artists))
            .putNullable("album", track.album)
            .put("durationMs", track.duration.inWholeMilliseconds)

    private fun repeatLabel(mode: Int): String =
        when (mode) {
            Player.REPEAT_MODE_ONE -> "one"
            Player.REPEAT_MODE_ALL -> "all"
            else -> "off"
        }

    private fun playbackStateLabel(state: Int): String =
        when (state) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            Player.STATE_ENDED -> "ended"
            else -> "unknown"
        }

    private fun getTrackJson(libraryId: Long): String {
        val track = GlobalData.libraryIndex.value.tracks[libraryId]
        if (track == null) {
            return errorJson("not_found", "No library track with id " + libraryId)
        }
        val statsUuid = statsUuidFor(track)
        val full = trackJson(track).put("statsUuid", statsUuid ?: JSONObject.NULL).toString()
        val compact =
            trackJson(track, includeHeavyFields = false)
                .put("statsUuid", statsUuid ?: JSONObject.NULL)
                .toString()
        return when (val result = responseBudget.trackDetail(full, compact, HEAVY_TRACK_FIELDS)) {
            is ResponseJsonBudget.TrackDetailResult.Ok -> result.json
            is ResponseJsonBudget.TrackDetailResult.TooLarge ->
                errorJson("response_too_large", result.message)
        }
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
        val sort =
            if (request.optString("sort", "plays").equals("time", true)) {
                StatsSort.TIME
            } else {
                StatsSort.PLAYS
            }
        val limit = request.optInt("limit", 20).coerceIn(1, MAX_LIMIT)
        val rows = runBlocking {
            GlobalData.statsManager.repository.topTracks(parsed, sort, limit).first()
        }
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

    private fun updateSortKeysJson(requestJson: String): String {
        val parsed = SortKeyRequest.parse(requestJson)
        if (parsed is SortKeyRequestResult.Failure) {
            return errorJson(parsed.code, parsed.message)
        }
        val batch = (parsed as SortKeyRequestResult.Ok).batch
        val index = GlobalData.libraryIndex.value
        val errors = batch.errors.toMutableList()
        val targets = mutableListOf<SortKeyTarget>()
        for (item in batch.items) {
            val track = index.tracks[item.libraryId]
            if (track == null) {
                errors +=
                    SortKeyItemError(
                        item.libraryId,
                        "not_found",
                        "No library track with id " + item.libraryId,
                    )
                continue
            }
            targets +=
                SortKeyTarget(
                    libraryId = item.libraryId,
                    localUriKey = SortKeyKeys.localUriKey(track),
                    tagCandidates = SortKeyKeys.tagCandidates(track),
                    sortKey = item.sortKey,
                    overwrite = batch.overwrite,
                )
        }
        val result = runBlocking { GlobalData.sortKeyManager.apply(targets, dryRun = batch.dryRun) }
        errors += result.errors.map { SortKeyItemError(it.libraryId, it.code, it.message) }
        return JSONObject()
            .put("dryRun", batch.dryRun)
            .put("updated", result.updated)
            .put("cleared", result.cleared)
            .put("skipped", result.skipped)
            .put("truncated", batch.truncated)
            .put("remaining", batch.remaining)
            .put(
                "skippedItems",
                JSONArray().apply {
                    result.skippedItems.forEach { skipped ->
                        put(
                            JSONObject()
                                .put("libraryId", skipped.libraryId)
                                .put("reason", skipped.reason)
                        )
                    }
                },
            )
            .put(
                "errors",
                JSONArray().apply {
                    errors.forEach { error ->
                        put(
                            JSONObject()
                                .put("libraryId", error.libraryId ?: JSONObject.NULL)
                                .put("code", error.code)
                                .put("message", error.message)
                        )
                    }
                },
            )
            .toString()
    }

    private fun statsUuidFor(track: Track): String? = runBlocking {
        val totals =
            GlobalData.statsManager.repository.lifetimeForTrack(track) ?: return@runBlocking null
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

    private fun trackSummaryJson(track: Track): JSONObject =
        trackJson(track, includeHeavyFields = false)

    private fun trackJson(track: Track, includeHeavyFields: Boolean = true): JSONObject =
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
            .put("hasSortKey", track.sortKey != null)
            .putNullable("sortKey", track.sortKey)
            .apply {
                if (includeHeavyFields) {
                    putNullable("comment", track.comment)
                    putNullable("unsyncedLyrics", track.unsyncedLyrics)
                }
            }
            .put("dateAdded", track.dateAdded)

    private fun comparatorFor(sort: String): Comparator<Track> {
        val collator = GlobalData.preferences.value.sortCollator
        return when (sort) {
            "artist" -> compareBy { it.artists.joinToString(", ").lowercase() }
            "album" -> compareBy { (it.album ?: "").lowercase() }
            "year" -> compareBy { it.year ?: Int.MIN_VALUE }
            "dateadded",
            "date_added" -> compareBy { it.dateAdded }
            "duration" -> compareBy { it.duration.inWholeMilliseconds }
            "rawtitle" -> Comparator { a, b -> collator.compare(a.displayTitle, b.displayTitle) }
            "sortkey" -> sortKeyComparator(descending = false)
            else ->
                Comparator { a, b ->
                    collator.compare(
                        a.sortTitle ?: a.displayTitle,
                        b.sortTitle ?: b.displayTitle,
                    )
                }
        }
    }

    private fun sortKeyComparator(descending: Boolean): Comparator<Track> {
        val collator = GlobalData.preferences.value.sortCollator
        return Comparator { a, b ->
            val aKey = a.sortKey
            val bKey = b.sortKey
            when {
                aKey == null && bKey == null -> collator.compare(a.displayTitle, b.displayTitle)
                aKey == null -> 1
                bKey == null -> -1
                else -> {
                    val result = collator.compare(aKey, bKey)
                    if (result != 0) {
                        if (descending) -result else result
                    } else {
                        collator.compare(a.displayTitle, b.displayTitle)
                    }
                }
            }
        }
    }

    private fun parseRange(raw: String): StatsRange? =
        when (raw.trim().lowercase()) {
            "7d",
            "days_7" -> StatsRange.DAYS_7
            "30d",
            "days_30" -> StatsRange.DAYS_30
            "month",
            "calendar_month" -> StatsRange.CALENDAR_MONTH
            "90d",
            "days_90" -> StatsRange.DAYS_90
            "365d",
            "days_365" -> StatsRange.DAYS_365
            "year",
            "calendar_year" -> StatsRange.CALENDAR_YEAR
            "3y",
            "years_3" -> StatsRange.YEARS_3
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
        JSONObject().put("error", JSONObject().put("code", code).put("message", message)).toString()

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
        val hasSortKey: Boolean?,
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
            if (title.isNotBlank() && track.title?.contains(title, ignoreCase = true) != true) {
                return false
            }
            if (
                artist.isNotBlank() && track.artists.none { it.contains(artist, ignoreCase = true) }
            ) {
                return false
            }
            if (album.isNotBlank() && track.album?.contains(album, ignoreCase = true) != true) {
                return false
            }
            if (
                albumArtist.isNotBlank() &&
                    track.albumArtists.none { it.contains(albumArtist, ignoreCase = true) }
            ) {
                return false
            }
            if (genre.isNotBlank() && track.genres.none { it.contains(genre, ignoreCase = true) }) {
                return false
            }
            if (year != null && track.year != year) return false
            if (yearFrom != null && (track.year == null || track.year < yearFrom)) return false
            if (yearTo != null && (track.year == null || track.year > yearTo)) return false
            if (format.isNotBlank() && !track.format.equals(format, ignoreCase = true)) {
                return false
            }
            if (hasArtwork != null && track.hasArtwork != hasArtwork) return false
            if (hasSortKey != null && (track.sortKey != null) != hasSortKey) return false
            val duration = track.duration.inWholeMilliseconds
            if (minDurationMs != null && duration < minDurationMs) return false
            if (maxDurationMs != null && duration > maxDurationMs) return false
            return true
        }
    }

    companion object {
        private const val API_VERSION = 5
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 200
        private val HEAVY_TRACK_FIELDS = listOf("comment", "unsyncedLyrics")
        private val RANGES = listOf("7d", "30d", "month", "90d", "365d", "year", "3y", "all")
        private val FACET_KINDS = listOf("artists", "albums", "album_artists", "genres")
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
                "hasSortKey",
                "sortKey",
                "comment",
                "unsyncedLyrics",
                "dateAdded",
            )
    }
}
