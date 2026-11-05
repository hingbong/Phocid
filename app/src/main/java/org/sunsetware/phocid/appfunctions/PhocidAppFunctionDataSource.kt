package org.sunsetware.phocid.appfunctions

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionNotSupportedException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import org.sunsetware.phocid.PlaybackService
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.globals.GlobalData
import org.sunsetware.phocid.stats.domain.StatsRange

/**
 * Executes AppFunction calls against the same in-process library, statistics, and player that the
 * UI uses. Keeping the logic here leaves the annotated service wrapper free of Android plumbing.
 */
@RequiresApi(36)
internal object PhocidAppFunctionDataSource {
    private const val MAX_SEARCH_RESULTS = 50
    private const val DEFAULT_SEARCH_RESULTS = 20
    private const val DEFAULT_RANGE = "30d"
    private const val PLAYER_TIMEOUT_SECONDS = 2L
    private const val READY_TIMEOUT_MS = 2_000L
    private const val READY_POLL_MS = 16L
    private val RANGE_NAMES =
        listOf("today", "7d", "30d", "month", "90d", "365d", "year", "3y", "all")
    private val PLAYBACK_COMMANDS = listOf("play", "pause", "toggle", "next", "previous")
    private val WHITESPACE = Regex("\\s+")

    fun searchTracks(query: String, limit: Int): List<TrackSummary> {
        awaitReady()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            throw AppFunctionInvalidArgumentException("query must not be blank")
        }
        // AppFunctions passes the type's empty value, not the Kotlin default, when a caller
        // omits an optional parameter.
        val effectiveLimit = if (limit == 0) DEFAULT_SEARCH_RESULTS else limit
        if (effectiveLimit !in 1..MAX_SEARCH_RESULTS) {
            throw AppFunctionInvalidArgumentException(
                "limit must be between 1 and $MAX_SEARCH_RESULTS"
            )
        }
        val tokens = trimmed.split(WHITESPACE)
        return GlobalData.libraryIndex.value.tracks.values
            .asSequence()
            .filter { matches(it, tokens) }
            .sortedWith(compareBy { it.displayTitle.lowercase() })
            .take(effectiveLimit)
            .map(::toTrackSummary)
            .toList()
    }

    fun getLibraryStats(): LibraryStats {
        awaitReady()
        val index = GlobalData.libraryIndex.value
        return LibraryStats(
            trackCount = index.tracks.size,
            artistCount = index.artists.size,
            albumCount = index.albums.size,
            albumArtistCount = index.albumArtists.size,
            genreCount = index.genres.size,
            totalDurationMs = index.tracks.values.sumOf { it.duration.inWholeMilliseconds },
        )
    }

    suspend fun getPlaybackStats(range: String?): PlaybackStats {
        awaitReady()
        // An omitted range arrives as null rather than the Kotlin default.
        val requested = range?.trim()?.ifEmpty { DEFAULT_RANGE } ?: DEFAULT_RANGE
        val parsed =
            parseRange(requested)
                ?: throw AppFunctionInvalidArgumentException(
                    "range must be one of ${RANGE_NAMES.joinToString(", ")}; got \"$requested\""
                )
        val summary = GlobalData.statsManager.repository.summary(parsed).first()
        return PlaybackStats(
            range = parsed.wireName(),
            plays = summary.plays,
            playedMillis = summary.millis,
            touchedTracks = summary.touchedTracks,
            listenedTracks = summary.listenedTracks,
            countedTracks = summary.countedTracks,
            activeDays = summary.activeDays,
            startedCount = summary.startedCount,
            completedCount = summary.completedCount,
            skipCount = summary.skipCount,
        )
    }

    fun getNowPlaying(context: Context): NowPlayingState =
        withController(context) { controller -> readNowPlaying(controller) }

    fun setPlayback(context: Context, command: String): NowPlayingState {
        val normalized = command.trim().lowercase()
        if (normalized !in PLAYBACK_COMMANDS) {
            throw AppFunctionInvalidArgumentException(
                "command must be one of ${PLAYBACK_COMMANDS.joinToString(", ")}; got \"$command\""
            )
        }
        if (!PlaybackService.isRunning) {
            throw AppFunctionNotSupportedException(
                "The Phocid player is not running; open Phocid and start playback first"
            )
        }
        return withController(context) { controller ->
            when (normalized) {
                "play" -> controller.play()
                "pause" -> controller.pause()
                "toggle" -> if (controller.isPlaying) controller.pause() else controller.play()
                "next" -> {
                    if (!controller.hasNextMediaItem()) {
                        throw AppFunctionNotSupportedException(
                            "There is no next track in the queue"
                        )
                    }
                    controller.seekToNextMediaItem()
                    controller.play()
                }
                else -> {
                    if (!controller.hasPreviousMediaItem()) {
                        throw AppFunctionNotSupportedException(
                            "There is no previous track in the queue"
                        )
                    }
                    controller.seekToPreviousMediaItem()
                    controller.play()
                }
            }
            readNowPlaying(controller)
        }
    }

    private fun withController(
        context: Context,
        block: (MediaController) -> NowPlayingState,
    ): NowPlayingState {
        if (!PlaybackService.isRunning) {
            return inactiveNowPlaying()
        }
        val controller =
            try {
                MediaController.Builder(
                        context,
                        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                    )
                    .setApplicationLooper(Looper.getMainLooper())
                    .buildAsync()
                    .get(PLAYER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AppFunctionAppUnknownException(
                    "Interrupted while connecting to the Phocid player"
                )
            } catch (e: Exception) {
                throw AppFunctionAppUnknownException(
                    e.message ?: "Could not connect to the Phocid player"
                )
            }
        try {
            return onMainThread(controller) { block(controller) }
        } finally {
            runCatching { onMainThread(controller) { controller.release() } }
        }
    }

    private fun <T> onMainThread(controller: MediaController, block: () -> T): T {
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
        if (!latch.await(PLAYER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw AppFunctionAppUnknownException("Timed out waiting for the Phocid player")
        }
        failure.get()?.let { error ->
            if (error is AppFunctionException) throw error
            throw AppFunctionAppUnknownException(error.message ?: error.javaClass.simpleName)
        }
        return result.get()
    }

    private fun readNowPlaying(controller: MediaController): NowPlayingState {
        val hasMedia = controller.mediaItemCount > 0
        val track =
            controller.currentMediaItem?.mediaId
                ?.toLongOrNull()
                ?.let { GlobalData.libraryIndex.value.tracks[it] }
        return NowPlayingState(
            active = true,
            isPlaying = controller.isPlaying,
            trackId = track?.id,
            title = track?.displayTitle,
            artists = track?.artists.orEmpty(),
            album = track?.album,
            durationMs =
                controller.duration.takeIf { it > 0L }
                    ?: track?.duration?.inWholeMilliseconds
                    ?: 0L,
            positionMs = if (hasMedia) controller.currentPosition.coerceAtLeast(0L) else 0L,
            queueLength = controller.mediaItemCount,
            currentIndex = if (hasMedia) controller.currentMediaItemIndex else 0,
            shuffle = controller.shuffleModeEnabled,
            repeatMode = repeatLabel(controller.repeatMode),
            playbackState = playbackStateLabel(controller.playbackState),
        )
    }

    private fun inactiveNowPlaying(): NowPlayingState =
        NowPlayingState(
            active = false,
            isPlaying = false,
            trackId = null,
            title = null,
            artists = emptyList(),
            album = null,
            durationMs = 0L,
            positionMs = 0L,
            queueLength = 0,
            currentIndex = 0,
            shuffle = false,
            repeatMode = "off",
            playbackState = "idle",
        )

    private fun matches(track: Track, tokens: List<String>): Boolean {
        val haystack =
            buildList {
                add(track.displayTitle)
                add(track.fileName)
                addAll(track.artists)
                track.album?.let { add(it) }
                addAll(track.albumArtists)
                addAll(track.genres)
            }
        return tokens.all { token -> haystack.any { it.contains(token, ignoreCase = true) } }
    }

    private fun toTrackSummary(track: Track): TrackSummary =
        TrackSummary(
            trackId = track.id,
            title = track.displayTitle,
            artists = track.artists,
            album = track.album,
            year = track.year,
            durationMs = track.duration.inWholeMilliseconds,
            dateAddedMs = track.dateAdded,
        )

    private fun parseRange(raw: String): StatsRange? =
        when (raw.trim().lowercase()) {
            "today" -> StatsRange.TODAY
            "7d" -> StatsRange.DAYS_7
            "30d" -> StatsRange.DAYS_30
            "month" -> StatsRange.CALENDAR_MONTH
            "90d" -> StatsRange.DAYS_90
            "365d" -> StatsRange.DAYS_365
            "year" -> StatsRange.CALENDAR_YEAR
            "3y" -> StatsRange.YEARS_3
            "all" -> StatsRange.ALL
            else -> null
        }

    private fun StatsRange.wireName(): String =
        when (this) {
            StatsRange.TODAY -> "today"
            StatsRange.DAYS_7 -> "7d"
            StatsRange.DAYS_30 -> "30d"
            StatsRange.CALENDAR_MONTH -> "month"
            StatsRange.DAYS_90 -> "90d"
            StatsRange.DAYS_365 -> "365d"
            StatsRange.CALENDAR_YEAR -> "year"
            StatsRange.YEARS_3 -> "3y"
            StatsRange.ALL -> "all"
        }

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

    /**
     * The AppFunction service can cold-start the process, and the application initializes
     * [GlobalData] asynchronously. Wait briefly instead of failing on a still-loading library.
     */
    private fun awaitReady() {
        if (GlobalData.initialized.get()) return
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (!GlobalData.initialized.get() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(READY_POLL_MS)
        }
        if (!GlobalData.initialized.get()) {
            throw AppFunctionAppUnknownException(
                "Phocid is still starting up; retry in a few seconds"
            )
        }
    }
}
