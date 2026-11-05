package org.sunsetware.phocid.appfunctions

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exposes a small, read-mostly slice of Phocid to on-device agents through Android AppFunctions.
 *
 * The KDoc on each function is compiled into the AppFunction schema, so it is written for an agent
 * that cannot see the app UI. KSP generates the concrete [PhocidAppFunctionService] subclass and
 * the matching assets/phocid_app_functions.xml inventory from this class.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "PhocidAppFunctionService",
    appFunctionXmlFileName = "phocid_app_functions",
)
abstract class BasePhocidAppFunctionService : AppFunctionService() {
    /**
     * Searches the user's local Phocid music library with free-text words and returns matching
     * tracks.
     *
     * The query is split on whitespace. Every word must appear in at least one of the track's
     * title, file name, artists, album, album artists, or genres; matching is case-insensitive.
     * Results are ordered by title. Use this to find a track by name before acting on it, or to
     * answer questions about what the user has.
     *
     * @param query Free-text search words, for example "miles davis kind of blue". Must not be
     *   blank.
     * @param limit Maximum number of tracks to return, between 1 and 50. Defaults to 20.
     * @return Matching tracks ordered by title, at most [limit] items; empty when nothing matches.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    internal suspend fun searchTracks(query: String, limit: Int = 20): List<TrackSummary> =
        withContext(Dispatchers.IO) {
            PhocidAppFunctionDataSource.searchTracks(query, limit)
        }

    /**
     * Returns what the Phocid player is doing right now: the current track, playback state, and
     * position.
     *
     * Call this when the user asks what is playing, who the artist is, or how far into the track
     * they are. When the playback service is not running, [NowPlayingState.active] is false and
     * the remaining fields are empty or zero.
     *
     * @return A snapshot of the current playback session.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    internal suspend fun getNowPlaying(): NowPlayingState =
        withContext(Dispatchers.IO) {
            PhocidAppFunctionDataSource.getNowPlaying(applicationContext)
        }

    /**
     * Returns aggregate counts for the user's indexed local music library: tracks, artists,
     * albums, album artists, genres, and the summed duration of every indexed track.
     *
     * Use this for questions such as "how many albums do I have". For listening history, use
     * [getPlaybackStats] instead.
     *
     * @return Library-wide counts and total duration.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    internal suspend fun getLibraryStats(): LibraryStats =
        withContext(Dispatchers.IO) { PhocidAppFunctionDataSource.getLibraryStats() }

    /**
     * Returns the user's listening statistics for one time range.
     *
     * Use this when the user asks how much they listened, how many plays a period had, or how
     * active their listening was. A play is counted once accumulated listening reaches
     * min(30 seconds, half the track duration).
     *
     * @param range Time window. One of "today", "7d", "30d", "month", "90d", "365d", "year",
     *   "3y", or "all"; defaults to "30d". Calendar ranges start on the first day of the current
     *   month or year, and "all" covers all imported history.
     * @return Playback totals for the requested range.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    internal suspend fun getPlaybackStats(range: String? = null): PlaybackStats =
        withContext(Dispatchers.IO) { PhocidAppFunctionDataSource.getPlaybackStats(range) }

    /**
     * Controls the Phocid player: play, pause, toggle, or skip to the next or previous track.
     *
     * The playback service must already be running, and skipping requires a next or previous item
     * in the queue; otherwise the call fails with a "not supported" error. The response is the
     * resulting playback snapshot, so the caller can confirm the change took effect.
     *
     * @param command One of "play", "pause", "toggle", "next", or "previous". Case-insensitive;
     *   surrounding whitespace is ignored.
     * @return The playback snapshot after the command has been applied.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    internal suspend fun setPlayback(command: String): NowPlayingState =
        withContext(Dispatchers.IO) {
            PhocidAppFunctionDataSource.setPlayback(applicationContext, command)
        }
}
