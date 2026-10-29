package org.sunsetware.phocid.sortkey

import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.stats.fingerprint.LocalUriKey
import org.sunsetware.phocid.stats.fingerprint.TagKey

/** Non-path keys shared by reads and writes. */
object SortKeyKeys {
    fun localUriKey(track: Track): String = LocalUriKey.of(track.uri.toString(), track.size)

    fun tagCandidates(track: Track): List<String> =
        TagKey.candidates(
            artist = track.artists.joinToString(", ").takeIf { it.isNotBlank() },
            album = track.album?.takeIf { it.isNotBlank() },
            title = track.title?.takeIf { it.isNotBlank() },
            durationMillis = track.duration.inWholeMilliseconds.coerceAtLeast(0),
        )

    fun tagKey(track: Track): String? = tagCandidates(track).firstOrNull()
}
