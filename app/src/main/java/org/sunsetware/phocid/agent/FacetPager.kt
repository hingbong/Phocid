package org.sunsetware.phocid.agent

internal data class FacetPage<T>(
    val total: Int,
    val offset: Int,
    val limit: Int,
    val items: List<T>,
)

internal fun <T> pageFacets(
    items: List<T>,
    query: String,
    limit: Int,
    offset: Int,
    compare: (String, String) -> Int,
    name: (T) -> String,
    searchText: (T) -> String,
): FacetPage<T> {
    val safeLimit = limit.coerceIn(1, 200)
    val safeOffset = offset.coerceAtLeast(0)
    val filtered =
        if (query.isBlank()) items
        else items.filter { searchText(it).contains(query, ignoreCase = true) }
    val sorted =
        filtered.sortedWith(
            Comparator { left, right ->
                val leftName = name(left)
                val rightName = name(right)
                val byCollator = compare(leftName, rightName)
                if (byCollator != 0) byCollator
                else leftName.compareTo(rightName, ignoreCase = true)
            }
        )
    return FacetPage(
        total = sorted.size,
        offset = safeOffset,
        limit = safeLimit,
        items = sorted.drop(safeOffset).take(safeLimit),
    )
}
