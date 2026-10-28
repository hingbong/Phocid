package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.agent.pageFacets

class FacetPagerTest {
    @Test
    fun filtersSortsAndPagesCaseInsensitively() {
        val page =
            pageFacets(
                items = listOf("gamma", "Alpha", "beta"),
                query = "a",
                limit = 2,
                offset = 1,
                compare = { left, right -> left.compareTo(right, ignoreCase = true) },
                name = { it },
                searchText = { it },
            )

        assertThat(page.total).isEqualTo(3)
        assertThat(page.offset).isEqualTo(1)
        assertThat(page.limit).isEqualTo(2)
        assertThat(page.items).containsExactly("beta", "gamma")
    }

    @Test
    fun noMatchesReturnsEmptyPage() {
        val page =
            pageFacets(
                items = listOf("alpha", "beta"),
                query = "zzz",
                limit = 50,
                offset = 0,
                compare = { left, right -> left.compareTo(right, ignoreCase = true) },
                name = { it },
                searchText = { it },
            )

        assertThat(page.total).isZero()
        assertThat(page.items).isEmpty()
    }

    @Test
    fun clampsLimitAndOffset() {
        val page =
            pageFacets(
                items = listOf("alpha", "beta"),
                query = "",
                limit = 0,
                offset = -1,
                compare = { left, right -> left.compareTo(right, ignoreCase = true) },
                name = { it },
                searchText = { it },
            )

        assertThat(page.limit).isEqualTo(1)
        assertThat(page.offset).isZero()
        assertThat(page.items).containsExactly("alpha")
    }
}
