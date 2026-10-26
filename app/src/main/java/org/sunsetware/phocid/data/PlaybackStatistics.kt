package org.sunsetware.phocid.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import org.apache.commons.io.FilenameUtils
import org.sunsetware.phocid.PLAYBACK_STATISTICS_FILE_NAME
import org.sunsetware.phocid.utils.trimAndNormalize

const val PLAYBACK_STATISTICS_FORMAT_VERSION = 1

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * Statistics are keyed by a fingerprint of the track's metadata rather than by its path or
 * MediaStore ID, so they survive moving the file and can be restored on another device.
 */
fun Track.statisticsKey(): String {
    val title = statisticsTitle().lowercase(Locale.ROOT)
    val artists =
        statisticsArtistList(artists)
            .map { it.lowercase(Locale.ROOT) }
            .sorted()
            .joinToString("\u0000")
    val album = statisticsAlbum()?.lowercase(Locale.ROOT) ?: ""
    val albumArtists =
        statisticsArtistList(albumArtists)
            .map { it.lowercase(Locale.ROOT) }
            .sorted()
            .joinToString("\u0000")
    val durationSeconds = (duration.inWholeMilliseconds.coerceAtLeast(0) + 500) / 1000
    val signature =
        buildString {
            appendStatisticsField("v1")
            appendStatisticsField(title)
            appendStatisticsField(artists)
            appendStatisticsField(album)
            appendStatisticsField(albumArtists)
            appendStatisticsField(durationSeconds.toString())
            appendStatisticsField(size.coerceAtLeast(0).toString())
            appendStatisticsField((trackNumber ?: 0).coerceAtLeast(0).toString())
            appendStatisticsField((discNumber ?: 0).coerceAtLeast(0).toString())
        }
    return MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray(Charsets.UTF_8))
        .toHex()
}

private fun StringBuilder.appendStatisticsField(value: String) {
    append(value.length).append(':').append(value).append('|')
}

private fun ByteArray.toHex(): String {
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}

private fun Track.statisticsTitle(): String {
    return title?.trimAndNormalize()?.takeIf { it.isNotEmpty() }
        ?: FilenameUtils.getBaseName(fileName).trimAndNormalize()
}

private fun Track.statisticsAlbum(): String? {
    return album?.trimAndNormalize()?.takeIf { it.isNotEmpty() }
}

private fun statisticsArtistList(artists: List<String>): List<String> {
    return artists.map { it.trimAndNormalize() }.filter { it.isNotEmpty() }.sorted()
}

private fun Track.statisticsArtistList(): List<String> {
    return statisticsArtistList(artists)
}

private fun Track.statisticsAlbumArtistList(): List<String> {
    return statisticsArtistList(albumArtists)
}

@Immutable
@Serializable
data class TrackStatistics(
    val title: String = "",
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtists: List<String> = emptyList(),
    val durationMillis: Long = 0,
    val playCount: Int = 0,
    val totalPlayedMillis: Long = 0,
    val firstPlayedAt: Long = 0,
    val lastPlayedAt: Long = 0,
) {
    fun sanitized(): TrackStatistics {
        return copy(
            playCount = playCount.coerceAtLeast(0),
            totalPlayedMillis = totalPlayedMillis.coerceAtLeast(0),
            firstPlayedAt = firstPlayedAt.coerceAtLeast(0),
            lastPlayedAt = lastPlayedAt.coerceAtLeast(0),
        )
    }

    fun mergeWith(other: TrackStatistics): TrackStatistics {
        return copy(
            title = title.ifBlank { other.title },
            artists = artists.ifEmpty { other.artists },
            album = album ?: other.album,
            albumArtists = albumArtists.ifEmpty { other.albumArtists },
            durationMillis = durationMillis.coerceAtLeast(other.durationMillis),
            playCount = addClamped(playCount, other.playCount),
            totalPlayedMillis = addClamped(totalPlayedMillis, other.totalPlayedMillis),
            firstPlayedAt = earliest(firstPlayedAt, other.firstPlayedAt),
            lastPlayedAt = max(lastPlayedAt, other.lastPlayedAt),
        )
    }
}

private fun addClamped(a: Int, b: Int): Int {
    val value = a.toLong() + b.toLong()
    return value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}

private fun addClamped(a: Long, b: Long): Long {
    val value = a.coerceAtLeast(0) + b.coerceAtLeast(0)
    return if (value < 0) Long.MAX_VALUE else value
}

private fun earliest(a: Long, b: Long): Long {
    return when {
        a <= 0 -> b.coerceAtLeast(0)
        b <= 0 -> a
        else -> min(a, b)
    }
}

@Immutable
@Serializable
data class PlaybackStatistics(
    val formatVersion: Int = PLAYBACK_STATISTICS_FORMAT_VERSION,
    val entries: Map<String, TrackStatistics> = emptyMap(),
) {
    fun upgrade(): PlaybackStatistics {
        val sanitizedEntries =
            entries
                .mapNotNull { (key, value) ->
                    if (key.isBlank()) null else key to value.sanitized()
                }
                .toMap()
        return copy(
            formatVersion =
                if (formatVersion > PLAYBACK_STATISTICS_FORMAT_VERSION) formatVersion
                else PLAYBACK_STATISTICS_FORMAT_VERSION,
            entries = sanitizedEntries,
        )
    }
}

fun PlaybackStatistics.recordPlay(
    track: Track,
    playedMillis: Long,
    timestamp: Long,
): PlaybackStatistics {
    val key = track.statisticsKey()
    val played = playedMillis.coerceAtLeast(0)
    val existing = entries[key]
    val newStatistics =
        if (existing == null) {
            TrackStatistics(
                title = track.statisticsTitle(),
                artists = track.statisticsArtistList(),
                album = track.statisticsAlbum(),
                albumArtists = track.statisticsAlbumArtistList(),
                durationMillis = track.duration.inWholeMilliseconds.coerceAtLeast(0),
                playCount = 1,
                totalPlayedMillis = played,
                firstPlayedAt = timestamp.coerceAtLeast(0),
                lastPlayedAt = timestamp.coerceAtLeast(0),
            )
        } else {
            existing.copy(
                playCount = addClamped(existing.playCount, 1),
                totalPlayedMillis = addClamped(existing.totalPlayedMillis, played),
                firstPlayedAt = earliest(existing.firstPlayedAt, timestamp),
                lastPlayedAt = max(existing.lastPlayedAt, timestamp),
            )
        }
    return copy(entries = entries + (key to newStatistics))
}

fun PlaybackStatistics.addPlayedTime(
    track: Track,
    playedMillis: Long,
): PlaybackStatistics {
    if (playedMillis <= 0) return this
    val key = track.statisticsKey()
    val existing = entries[key] ?: return this
    return copy(
        entries =
            entries +
                (key to
                    existing.copy(
                        totalPlayedMillis =
                            addClamped(existing.totalPlayedMillis, playedMillis)
                    ))
    )
}

fun PlaybackStatistics.merge(other: PlaybackStatistics): PlaybackStatistics {
    val merged = entries.toMutableMap()
    for ((key, incoming) in other.entries) {
        val existing = merged[key]
        merged[key] = if (existing == null) incoming.sanitized() else existing.mergeWith(incoming)
    }
    return copy(formatVersion = PLAYBACK_STATISTICS_FORMAT_VERSION, entries = merged)
}

fun PlaybackStatistics.replaceWith(other: PlaybackStatistics): PlaybackStatistics {
    return PlaybackStatistics(
        formatVersion = PLAYBACK_STATISTICS_FORMAT_VERSION,
        entries = other.upgrade().entries,
    )
}

fun PlaybackStatistics.clear(): PlaybackStatistics {
    return PlaybackStatistics()
}

fun PlaybackStatistics.toBackup(exportedAt: Long): PlaybackStatisticsBackup {
    return PlaybackStatisticsBackup(
        formatVersion = PLAYBACK_STATISTICS_FORMAT_VERSION,
        exportedAt = exportedAt,
        entries = entries,
    )
}

@Immutable
@Serializable
data class PlaybackStatisticsBackup(
    val formatVersion: Int = PLAYBACK_STATISTICS_FORMAT_VERSION,
    val exportedAt: Long = 0,
    val entries: Map<String, TrackStatistics> = emptyMap(),
) {
    fun toStatistics(): PlaybackStatistics {
        return PlaybackStatistics(formatVersion = formatVersion, entries = entries).upgrade()
    }
}

@Stable
class PlaybackStatisticsManager(context: Context, coroutineScope: CoroutineScope) {
    private val _statistics =
        MutableStateFlow(
            loadCbor<PlaybackStatistics>(context, PLAYBACK_STATISTICS_FILE_NAME, false)?.upgrade()
                ?: PlaybackStatistics()
        )
    val statistics: StateFlow<PlaybackStatistics> = _statistics.asStateFlow()

    @Suppress("unused")
    private val saveManager =
        SaveManager(
            context,
            coroutineScope,
            _statistics,
            PLAYBACK_STATISTICS_FILE_NAME,
            false,
        )

    fun recordPlay(track: Track, playedMillis: Long) {
        _statistics.update {
            it.recordPlay(track, playedMillis, System.currentTimeMillis())
        }
    }

    fun addPlayedTime(track: Track, playedMillis: Long) {
        _statistics.update { it.addPlayedTime(track, playedMillis) }
    }

    fun merge(other: PlaybackStatistics) {
        _statistics.update { it.merge(other) }
    }

    fun replace(other: PlaybackStatistics) {
        _statistics.update { it.replaceWith(other) }
    }

    fun clear() {
        _statistics.update { it.clear() }
    }
}
