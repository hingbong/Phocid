package org.sunsetware.phocid.agent

/**
 * Builds JSON payloads that stay inside a conservative UTF-16 byte budget.
 *
 * Android Binder transactions are capped at roughly 1 MiB, and a JSON response crosses the boundary
 * as a UTF-16 string. This helper measures 2 bytes per UTF-16 code unit and defaults to 512 KiB,
 * leaving headroom for the rest of the transaction. Search pages are built one track at a time so a
 * page can stop before an item that would overflow instead of failing the whole call.
 */
internal class ResponseJsonBudget(private val maxUtf16Bytes: Int = DEFAULT_MAX_UTF16_BYTES) {
    init {
        require(maxUtf16Bytes > 0)
    }

    sealed interface SearchPageResult {
        data class Page(
            val json: String,
            val returnedTracks: Int,
            val nextOffset: Int,
            val byteTruncated: Boolean,
        ) : SearchPageResult

        data class TooLarge(val message: String) : SearchPageResult
    }

    sealed interface TrackDetailResult {
        data class Ok(val json: String, val truncated: Boolean) : TrackDetailResult

        data class TooLarge(val message: String) : TrackDetailResult
    }

    /**
     * Serializes at most [limit] tracks and stops before the first track that would exceed the
     * budget. [byteTruncated] is set only when that budget stopped the page; reaching [limit] or
     * the end of the result set does not set it. [nextOffset] always points at the next track that
     * was not returned.
     */
    fun searchPage(
        total: Int,
        offset: Int,
        limit: Int,
        tracks: Sequence<String>,
    ): SearchPageResult {
        val remaining = (total - offset).coerceAtLeast(0)
        val maxTracks = minOf(limit, remaining)
        val included = ArrayList<String>(minOf(maxTracks, 64))
        for (track in tracks) {
            if (included.size >= maxTracks) break
            val candidateOffset = offset + included.size + 1
            val candidate =
                renderSearchPage(
                    total = total,
                    offset = offset,
                    limit = limit,
                    tracks = included,
                    extra = track,
                    nextOffset = candidateOffset,
                    byteTruncated = false,
                )
            if (!fits(candidate)) {
                if (included.isEmpty()) {
                    return SearchPageResult.TooLarge(
                        "A single search summary exceeds the " +
                            "${maxUtf16Bytes / 1024} KiB response budget; narrow the filters " +
                            "or use library_get_track"
                    )
                }
                val nextOffset = offset + included.size
                return SearchPageResult.Page(
                    json =
                        renderSearchPage(
                            total = total,
                            offset = offset,
                            limit = limit,
                            tracks = included,
                            extra = null,
                            nextOffset = nextOffset,
                            byteTruncated = true,
                        ),
                    returnedTracks = included.size,
                    nextOffset = nextOffset,
                    byteTruncated = true,
                )
            }
            included.add(track)
        }

        val nextOffset = offset + included.size
        return SearchPageResult.Page(
            json =
                renderSearchPage(
                    total = total,
                    offset = offset,
                    limit = limit,
                    tracks = included,
                    extra = null,
                    nextOffset = nextOffset,
                    byteTruncated = false,
                ),
            returnedTracks = included.size,
            nextOffset = nextOffset,
            byteTruncated = false,
        )
    }

    /**
     * Returns the full detail payload when it fits. Otherwise removes the heavy fields (already
     * absent from [compactJson]) and records them in `truncatedFields`. If even the compact payload
     * does not fit, returns a readable budget error instead of an oversized response.
     */
    fun trackDetail(
        fullJson: String,
        compactJson: String,
        truncatedFields: List<String>,
    ): TrackDetailResult {
        if (fits(fullJson)) {
            return TrackDetailResult.Ok(fullJson, truncated = false)
        }
        val compact = appendTruncatedFields(json = compactJson, fields = truncatedFields)
        if (fits(compact)) {
            return TrackDetailResult.Ok(compact, truncated = true)
        }
        return TrackDetailResult.TooLarge(
            "The track detail response exceeds the ${maxUtf16Bytes / 1024} KiB response budget"
        )
    }

    private fun fits(json: String): Boolean = json.length <= maxUtf16Bytes / 2

    private fun renderSearchPage(
        total: Int,
        offset: Int,
        limit: Int,
        tracks: List<String>,
        extra: String?,
        nextOffset: Int,
        byteTruncated: Boolean,
    ): String = buildString {
        append("{\"total\":").append(total)
        append(",\"offset\":").append(offset)
        append(",\"limit\":").append(limit)
        append(",\"tracks\":[")
        var first = true
        for (track in tracks) {
            if (!first) append(',')
            append(track)
            first = false
        }
        if (extra != null) {
            if (!first) append(',')
            append(extra)
        }
        append("],\"nextOffset\":").append(nextOffset)
        append(",\"byteTruncated\":").append(byteTruncated)
        append('}')
    }

    private fun appendTruncatedFields(json: String, fields: List<String>): String {
        if (fields.isEmpty()) return json
        val end = json.lastIndexOf('}')
        if (end < 0) return json
        val body = json.substring(0, end)
        val separator = if (body.endsWith("{")) "" else ","
        val encoded =
            fields.joinToString(separator = ",", prefix = "[", postfix = "]") {
                quote(it)
            }
        return body + separator + "\"truncatedFields\":" + encoded + "}"
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character.code < 0x20) {
                        append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
            }
        }
        append('"')
    }

    companion object {
        const val DEFAULT_MAX_UTF16_BYTES = 512 * 1024
    }
}
