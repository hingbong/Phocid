package org.sunsetware.phocid.agent

import org.json.JSONObject
import org.sunsetware.phocid.sortkey.SortKeyText
import org.sunsetware.phocid.sortkey.SortKeyValue

data class SortKeyItem(val libraryId: Long, val sortKey: String?)

data class SortKeyItemError(
    val libraryId: Long?,
    val code: String,
    val message: String,
)

data class SortKeyBatch(
    val items: List<SortKeyItem>,
    val errors: List<SortKeyItemError>,
    val overwrite: Boolean,
    val dryRun: Boolean,
    val truncated: Boolean,
    val remaining: Int,
)

sealed interface SortKeyRequestResult {
    data class Ok(val batch: SortKeyBatch) : SortKeyRequestResult

    data class Failure(val code: String, val message: String) : SortKeyRequestResult
}

object SortKeyRequest {
    const val MAX_ITEMS = 200

    fun parse(raw: String): SortKeyRequestResult {
        val root =
            try {
                JSONObject(raw)
            } catch (e: Exception) {
                return SortKeyRequestResult.Failure(
                    "invalid_request",
                    "Request is not valid JSON: " + (e.message ?: e.javaClass.simpleName),
                )
            }

        val array =
            root.optJSONArray("items")
                ?: return SortKeyRequestResult.Failure(
                    "invalid_request",
                    "items must be an array",
                )
        if (array.length() == 0) {
            return SortKeyRequestResult.Failure(
                "invalid_request",
                "items must contain at least one entry",
            )
        }
        val total = array.length()
        val accepted = minOf(total, MAX_ITEMS)

        val items = mutableListOf<SortKeyItem>()
        val errors = mutableListOf<SortKeyItemError>()
        for (index in 0 until accepted) {
            val entry = array.optJSONObject(index)
            if (entry == null) {
                errors +=
                    SortKeyItemError(null, "invalid_item", "items[" + index + "] is not an object")
                continue
            }
            val libraryId = entry.optLong("libraryId", -1L)
            if (libraryId <= 0) {
                errors +=
                    SortKeyItemError(
                        null,
                        "invalid_library_id",
                        "items[" + index + "].libraryId must be a positive integer",
                    )
                continue
            }
            if (!entry.has("sortKey")) {
                errors +=
                    SortKeyItemError(
                        libraryId,
                        "invalid_sort_key",
                        "items[" + index + "].sortKey is required",
                    )
                continue
            }
            val value = entry.opt("sortKey")
            val rawSortKey =
                when {
                    value == null || value == JSONObject.NULL -> null
                    value is String -> value
                    else -> {
                        errors +=
                            SortKeyItemError(
                                libraryId,
                                "invalid_sort_key",
                                "sortKey must be a string or null",
                            )
                        continue
                    }
                }
            when (val parsed = SortKeyText.parse(rawSortKey)) {
                is SortKeyValue.Value -> items += SortKeyItem(libraryId, parsed.text)
                SortKeyValue.Clear -> items += SortKeyItem(libraryId, null)
                is SortKeyValue.Invalid ->
                    errors +=
                        SortKeyItemError(libraryId, "invalid_sort_key", parsed.message)
            }
        }

        return SortKeyRequestResult.Ok(
            SortKeyBatch(
                items = items,
                errors = errors,
                overwrite = root.optBoolean("overwrite", false),
                dryRun = root.optBoolean("dryRun", false),
                truncated = total > accepted,
                remaining = total - accepted,
            )
        )
    }
}
