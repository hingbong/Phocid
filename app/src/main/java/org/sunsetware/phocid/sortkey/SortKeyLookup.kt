package org.sunsetware.phocid.sortkey

import androidx.compose.runtime.Immutable
import org.sunsetware.phocid.data.Track

@Immutable
data class SortKeyLookup(
    val byLocalUri: Map<String, String> = emptyMap(),
    val byTag: Map<String, String> = emptyMap(),
) {
    fun keyFor(track: Track): String? {
        byLocalUri[SortKeyKeys.localUriKey(track)]?.let {
            return it
        }
        if (byTag.isEmpty()) return null
        return SortKeyKeys.tagCandidates(track).firstNotNullOfOrNull { byTag[it] }
    }

    companion object {
        val EMPTY = SortKeyLookup()

        fun from(rows: List<SortKeyEntity>): SortKeyLookup {
            val byLocalUri = rows.associate { it.localUriKey to it.sortKey }
            val byTag =
                rows.mapNotNull { row -> row.tagKey?.let { it to row.sortKey } }
                    .groupBy({ it.first }, { it.second })
                    .mapNotNull { (key, values) ->
                        if (values.size == 1) key to values.single() else null
                    }
                    .toMap()
            return SortKeyLookup(byLocalUri = byLocalUri, byTag = byTag)
        }
    }
}
