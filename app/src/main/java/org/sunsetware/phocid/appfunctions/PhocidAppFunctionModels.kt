package org.sunsetware.phocid.appfunctions

import androidx.appfunctions.AppFunctionSerializable

/** A track from the user's local Phocid library. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class TrackSummary(
    /** Stable numeric identifier of the track inside Phocid, matching the MediaStore audio id. */
    val trackId: Long,
    /** Display title, falling back to the file name when the file has no title tag. */
    val title: String,
    /** Track artists in tag order. Empty when the file has no artist tag. */
    val artists: List<String>,
    /** Album title, or null when the file has no album tag. */
    val album: String?,
    /** Release year, or null when unknown. */
    val year: Int?,
    /** Track duration in milliseconds. */
    val durationMs: Long,
    /** When the track was added to the device library, in epoch milliseconds. */
    val dateAddedMs: Long,
)

/** A snapshot of the Phocid player at the moment the function was called. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class NowPlayingState(
    /** True when the Phocid playback service is running and a queue can be inspected. */
    val active: Boolean,
    /** True while audio is actually playing. */
    val isPlaying: Boolean,
    /** Identifier of the current track, or null when nothing is loaded. */
    val trackId: Long?,
    /** Title of the current track, or null when nothing is loaded or the tag is missing. */
    val title: String?,
    /** Artists of the current track. Empty when nothing is loaded. */
    val artists: List<String>,
    /** Album of the current track, or null. */
    val album: String?,
    /** Duration of the current track in milliseconds, or 0 when unknown. */
    val durationMs: Long,
    /** Current playback position in milliseconds, or 0 when nothing is loaded. */
    val positionMs: Long,
    /** Number of items in the play queue. */
    val queueLength: Int,
    /** Zero-based index of the current item in the play queue, or 0 when the queue is empty. */
    val currentIndex: Int,
    /** Whether shuffle is enabled. */
    val shuffle: Boolean,
    /** Repeat mode: "off", "one", or "all". */
    val repeatMode: String,
    /** Media3 playback state: "idle", "buffering", "ready", "ended", or "unknown". */
    val playbackState: String,
)

/** Aggregate counts for the user's indexed local music library. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class LibraryStats(
    /** Number of indexed tracks. */
    val trackCount: Int,
    /** Number of distinct track artists. */
    val artistCount: Int,
    /** Number of distinct albums. */
    val albumCount: Int,
    /** Number of distinct album artists. */
    val albumArtistCount: Int,
    /** Number of distinct genres. */
    val genreCount: Int,
    /** Sum of every indexed track's duration in milliseconds. */
    val totalDurationMs: Long,
)

/** Playback statistics for one time range, derived from Phocid's listening history. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PlaybackStats(
    /** Effective range: today, 7d, 30d, month, 90d, 365d, year, 3y, or all. */
    val range: String,
    /** Number of counted plays. A play counts once listening reaches min(30s, duration/2). */
    val plays: Int,
    /** Total listened time in milliseconds. */
    val playedMillis: Long,
    /** Distinct tracks with any row in the range, including zero-length skips. */
    val touchedTracks: Int,
    /** Distinct tracks with listening time or a counted play. */
    val listenedTracks: Int,
    /** Distinct tracks with at least one counted play. */
    val countedTracks: Int,
    /** Number of local days with playback activity. */
    val activeDays: Int,
    /** Number of playback sessions started. */
    val startedCount: Int,
    /** Number of playback sessions that reached the completion threshold. */
    val completedCount: Int,
    /** Number of skipped playback sessions. */
    val skipCount: Int,
)
