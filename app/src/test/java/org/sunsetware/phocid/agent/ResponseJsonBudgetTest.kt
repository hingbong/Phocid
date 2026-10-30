package org.sunsetware.phocid.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseJsonBudgetTest {
    private fun item(id: Int): String = """{"libraryId":$id,"title":"Track $id"}"""

    @Test
    fun exactFitKeepsEveryItem() {
        val items = listOf(item(1), item(2))
        val wide = ResponseJsonBudget(Int.MAX_VALUE)
        val base =
            (wide.searchPage(10, 2, 5, items.asSequence())
                as ResponseJsonBudget.SearchPageResult.Page)
        val exact = ResponseJsonBudget(base.json.length * 2)
        val page =
            (exact.searchPage(10, 2, 5, items.asSequence())
                as ResponseJsonBudget.SearchPageResult.Page)

        assertEquals(base.json, page.json)
        assertEquals(2, page.returnedTracks)
        assertEquals(4, page.nextOffset)
        assertFalse(page.byteTruncated)
        assertTrue(page.json.startsWith("""{"total":10,"offset":2,"limit":5,"tracks":["""))
    }

    @Test
    fun oneByteTooSmallStopsBeforeTheOffendingTrack() {
        val items = listOf(item(1), item(2), item(3))
        val wide = ResponseJsonBudget(Int.MAX_VALUE)
        val base =
            (wide.searchPage(10, 2, 5, items.asSequence())
                as ResponseJsonBudget.SearchPageResult.Page)
        val tight = ResponseJsonBudget(base.json.length * 2 - 2)
        val page =
            (tight.searchPage(10, 2, 5, items.asSequence())
                as ResponseJsonBudget.SearchPageResult.Page)

        assertEquals(2, page.returnedTracks)
        assertEquals(4, page.nextOffset)
        assertTrue(page.byteTruncated)
        assertFalse(page.json.contains("\"libraryId\":3"))
        assertTrue(page.json.contains("\"byteTruncated\":true"))
    }

    @Test
    fun requestedLimitStopsPageWithoutByteTruncation() {
        val items = (1..5).map(::item)
        val budget = ResponseJsonBudget(Int.MAX_VALUE)
        val page =
            (budget.searchPage(10, 3, 2, items.asSequence())
                as ResponseJsonBudget.SearchPageResult.Page)

        assertEquals(2, page.returnedTracks)
        assertEquals(5, page.nextOffset)
        assertFalse(page.byteTruncated)
    }

    @Test
    fun singleOversizedSummaryReturnsReadableError() {
        val budget = ResponseJsonBudget(20)
        val result = budget.searchPage(1, 0, 1, sequenceOf(item(1)))

        assertTrue(result is ResponseJsonBudget.SearchPageResult.TooLarge)
        assertTrue(
            (result as ResponseJsonBudget.SearchPageResult.TooLarge)
                .message
                .contains("response budget")
        )
    }

    @Test
    fun detailOverBudgetDropsHeavyFieldsAndMarksThem() {
        val heavy =
            "{\"libraryId\":1,\"comment\":\"" +
                "x".repeat(2_000) +
                "\",\"unsyncedLyrics\":\"" +
                "y".repeat(2_000) +
                "\",\"statsUuid\":null}"
        val compact = """{"libraryId":1,"statsUuid":null}"""
        val fallback =
            """{"libraryId":1,"statsUuid":null,"truncatedFields":["comment","unsyncedLyrics"]}"""
        val budget = ResponseJsonBudget(fallback.length * 2)

        val result =
            budget.trackDetail(heavy, compact, listOf("comment", "unsyncedLyrics"))
                as ResponseJsonBudget.TrackDetailResult.Ok

        assertTrue(result.truncated)
        assertEquals(fallback, result.json)
        assertTrue(result.json.contains("\"truncatedFields\":[\"comment\",\"unsyncedLyrics\"]"))
        assertFalse(result.json.contains("\"comment\":\"x"))
    }

    @Test
    fun detailWithinBudgetKeepsHeavyFields() {
        val heavy = """{"libraryId":1,"comment":"short","statsUuid":null}"""
        val compact = """{"libraryId":1,"statsUuid":null}"""
        val budget = ResponseJsonBudget(heavy.length * 2)

        val result =
            budget.trackDetail(heavy, compact, listOf("comment", "unsyncedLyrics"))
                as ResponseJsonBudget.TrackDetailResult.Ok

        assertFalse(result.truncated)
        assertEquals(heavy, result.json)
    }

    @Test
    fun detailFallbackOverBudgetReturnsReadableError() {
        val fallback =
            """{"libraryId":1,"statsUuid":null,"truncatedFields":["comment","unsyncedLyrics"]}"""
        val budget = ResponseJsonBudget(fallback.length * 2 - 2)
        val heavy = "{\"libraryId\":1,\"comment\":\"" + "x".repeat(2_000) + "\",\"statsUuid\":null}"

        val result =
            budget.trackDetail(
                heavy,
                """{"libraryId":1,"statsUuid":null}""",
                listOf("comment", "unsyncedLyrics"),
            )

        assertTrue(result is ResponseJsonBudget.TrackDetailResult.TooLarge)
        assertTrue(
            (result as ResponseJsonBudget.TrackDetailResult.TooLarge)
                .message
                .contains("response budget")
        )
    }
}
