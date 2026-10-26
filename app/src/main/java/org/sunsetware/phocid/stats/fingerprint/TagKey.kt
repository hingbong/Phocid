package org.sunsetware.phocid.stats.fingerprint

import java.text.Normalizer
import java.util.Locale

/**
 * Second identity layer: normalized tags plus a duration bucket.
 *
 * It survives re-encoding and format changes, where the audio hash necessarily changes.
 * Duration is bucketed because different encoders disagree by tens of milliseconds.
 */
object TagKey {
    private const val BUCKET_MILLIS = 2000L

    private val BRACKETS =
        Regex(
            """[\(\[【（]\s*(remaster(ed)?|re-?master|deluxe|bonus|explicit|clean|mono|stereo|live|remix|version|edit|hd|hq|\d{4})[^)\]】）]*[\)\]】）]""",
            RegexOption.IGNORE_CASE,
        )
    private val FEAT =
        Regex(
            """\s*[\(\[]?\s*(feat\.?|ft\.?|featuring|with)\s+[^)\]]*[\)\]]?\s*$""",
            RegexOption.IGNORE_CASE,
        )
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    fun of(
        artist: String?,
        album: String?,
        title: String?,
        durationMillis: Long,
    ): String? {
        val normalizedTitle = normalize(title) ?: return null
        val normalizedArtist = normalize(artist).orEmpty()
        val normalizedAlbum = normalize(album).orEmpty()
        val bucket = durationMillis / BUCKET_MILLIS
        return "$normalizedArtist\u0000$normalizedAlbum\u0000$normalizedTitle\u0000$bucket"
    }

    /** Returns the exact bucket and, near a boundary, the neighboring bucket. */
    fun candidates(
        artist: String?,
        album: String?,
        title: String?,
        durationMillis: Long,
    ): List<String> {
        val base = of(artist, album, title, durationMillis) ?: return emptyList()
        val offset = durationMillis % BUCKET_MILLIS
        val neighbor =
            when {
                offset < 300 -> of(artist, album, title, durationMillis - 400)
                offset > BUCKET_MILLIS - 300 -> of(artist, album, title, durationMillis + 400)
                else -> null
            }
        return listOfNotNull(base, neighbor).distinct()
    }

    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var value =
            Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        value = FEAT.replace(value, "")
        value = BRACKETS.replace(value, " ")
        value = NON_ALNUM.replace(value, " ").trim()
        return value.ifBlank { null }
    }
}

/** Third identity layer: lowercase path relative to a media root. */
object RelPathKey {
    fun of(absolutePath: String, mediaRoots: List<String>): String {
        val path = absolutePath.replace('\\', '/')
        val root =
            mediaRoots
                .map { it.replace('\\', '/').trimEnd('/') }
                .filter { path.startsWith("$it/") }
                .maxByOrNull { it.length }
        val relative =
            if (root != null) path.removePrefix("$root/") else path.substringAfterLast('/')
        return relative.lowercase(Locale.ROOT)
    }
}
