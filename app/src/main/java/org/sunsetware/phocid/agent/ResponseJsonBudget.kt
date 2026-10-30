package org.sunsetware.phocid.agent

/**
 * Builds JSON payloads that stay inside a conservative UTF-16 byte budget.
 *
 * Android Binder transactions are capped at roughly 1 MiB, and a JSON response crosses the boundary
 * as a UTF-16 string. This helper measures 2 bytes per UTF-16 code unit and defaults to 512 KiB,
 * leaving headroom for the rest of the transaction. Pages are built one item at a time so a page
 * can stop before an item that would overflow instead of failing the whole call.
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

    /** Result of [boundedPage]: either a page that fits or a readable budget error. */
    sealed interface PageResult {
        data class Page(
            val json: String,
            val returnedItems: Int,
            val nextOffset: Int,
            val byteTruncated: Boolean,
        ) : PageResult

        data class TooLarge(val message: String) : PageResult
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
        val tooLargeMessage =
            "A single search summary exceeds the " +
                "${maxUtf16Bytes / 1024} KiB response budget; narrow the filters " +
                "or use library_get_track"
        return when (
            val result =
                boundedPage(
                    total = total,
                    offset = offset,
                    limit = limit,
                    items = tracks,
                    tooLargeMessage = tooLargeMessage,
                ) { included, extra, nextOffset, byteTruncated ->
                    renderSearchPage(
                        total = total,
                        offset = offset,
                        limit = limit,
                        tracks = included,
                        extra = extra,
                        nextOffset = nextOffset,
                        byteTruncated = byteTruncated,
                    )
                }
        ) {
            is PageResult.Page ->
                SearchPageResult.Page(
                    json = result.json,
                    returnedTracks = result.returnedItems,
                    nextOffset = result.nextOffset,
                    byteTruncated = result.byteTruncated,
                )
            is PageResult.TooLarge -> SearchPageResult.TooLarge(result.message)
        }
    }

    /**
     * Serializes at most [limit] [items] starting at [offset] and stops before the first item that
     * would exceed the budget. [render] builds the payload for the items accepted so far; it also
     * receives the candidate item being measured, so each caller keeps its own response layout.
     * [byteTruncated] is set only when the budget stopped the page; reaching [limit] or the end of
     * the result set does not set it. [nextOffset] always points at the next item that was not
     * returned.
     */
    fun <T> boundedPage(
        total: Int,
        offset: Int,
        limit: Int,
        items: Sequence<T>,
        tooLargeMessage: String,
        render: (items: List<T>, extra: T?, nextOffset: Int, byteTruncated: Boolean) -> String,
    ): PageResult {
        val remaining = (total - offset).coerceAtLeast(0)
        val maxItems = minOf(limit, remaining)
        val included = ArrayList<T>(minOf(maxItems, 64))
        for (item in items) {
            if (included.size >= maxItems) break
            val candidateOffset = offset + included.size + 1
            val candidate = render(included, item, candidateOffset, false)
            if (!fits(candidate)) {
                if (included.isEmpty()) {
                    return PageResult.TooLarge(tooLargeMessage)
                }
                val nextOffset = offset + included.size
                return PageResult.Page(
                    json = render(included, null, nextOffset, true),
                    returnedItems = included.size,
                    nextOffset = nextOffset,
                    byteTruncated = true,
                )
            }
            included.add(item)
        }

        val nextOffset = offset + included.size
        return PageResult.Page(
            json = render(included, null, nextOffset, false),
            returnedItems = included.size,
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

    /**
     * Enforces the budget for a finished response. An oversized payload is replaced by a small
     * `response_too_large` error built without the oversized input, so enforcing the fallback again
     * cannot recurse or grow the response.
     */
    fun boundedResponse(json: String): String {
        if (fits(json)) return json
        val message = "The response exceeds the ${maxUtf16Bytes / 1024} KiB response budget"
        val error = "{\"error\":{\"code\":\"response_too_large\",\"message\":${quote(message)}}}"
        if (fits(error)) return error
        // Keep the shortest readable error rather than echoing the oversized payload back.
        return "{\"error\":{\"code\":\"response_too_large\"}}"
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
            fields.joinToString(separator = ",", prefix = "[", postfix = "]") { quote(it) }
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
