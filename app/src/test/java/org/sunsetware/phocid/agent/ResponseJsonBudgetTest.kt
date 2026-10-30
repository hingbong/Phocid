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

    private fun renderPage(
        head: String,
        items: List<String>,
        extra: String?,
        nextOffset: Int,
        byteTruncated: Boolean,
    ): String =
        head +
            (items + listOfNotNull(extra)).joinToString(",", prefix = "[", postfix = "]") +
            ""","nextOffset":$nextOffset,"byteTruncated":$byteTruncated}"""

    @Test
    fun genericPageAdvancesNextOffsetFromRequestedOffset() {
        val budget = ResponseJsonBudget(Int.MAX_VALUE)
        val items = (1..5).map(::item)
        val page =
            budget.boundedPage(
                total = 10,
                offset = 3,
                limit = 2,
                items = items.asSequence(),
                tooLargeMessage = "too large",
            ) { included, extra, nextOffset, byteTruncated ->
                renderPage(
                    """{"offset":3,"items":""",
                    included,
                    extra,
                    nextOffset,
                    byteTruncated,
                )
            } as ResponseJsonBudget.PageResult.Page

        assertEquals(2, page.returnedItems)
        assertEquals(5, page.nextOffset)
        assertFalse(page.byteTruncated)
    }

    @Test
    fun genericPageTruncatesLongFacetItemsBeforeDefaultBudget() {
        val budget = ResponseJsonBudget()
        val limit = 200
        val items =
            (1..limit).map { index -> """{"name":"${"f".repeat(4_000)}","trackCount":$index}""" }
        val page =
            budget.boundedPage(
                total = items.size,
                offset = 0,
                limit = limit,
                items = items.asSequence(),
                tooLargeMessage = "facet too large",
            ) { included, extra, nextOffset, byteTruncated ->
                renderPage(
                    """{"kind":"artists","total":$limit,"offset":0,"limit":$limit,"items":""",
                    included,
                    extra,
                    nextOffset,
                    byteTruncated,
                )
            } as ResponseJsonBudget.PageResult.Page

        assertTrue(page.byteTruncated)
        assertTrue(page.returnedItems in 1 until limit)
        assertEquals(page.returnedItems, page.nextOffset)
        assertTrue(page.json.length * 2 <= ResponseJsonBudget.DEFAULT_MAX_UTF16_BYTES)
        assertTrue(page.json.contains("\"byteTruncated\":true"))
    }

    @Test
    fun genericPageRejectsSingleFacetLargerThanDefaultBudget() {
        val budget = ResponseJsonBudget()
        val huge =
            """{"name":"${"x".repeat(ResponseJsonBudget.DEFAULT_MAX_UTF16_BYTES / 2)}","trackCount":1}"""
        val result =
            budget.boundedPage(
                total = 1,
                offset = 0,
                limit = 1,
                items = sequenceOf(huge),
                tooLargeMessage = "facet too large",
            ) { included, extra, nextOffset, byteTruncated ->
                renderPage(
                    """{"kind":"artists","total":1,"offset":0,"limit":1,"items":""",
                    included,
                    extra,
                    nextOffset,
                    byteTruncated,
                )
            }

        assertTrue(result is ResponseJsonBudget.PageResult.TooLarge)
        assertEquals(
            "facet too large",
            (result as ResponseJsonBudget.PageResult.TooLarge).message,
        )
    }

    @Test
    fun genericPageTruncatesLongAggregatesBeforeDefaultBudget() {
        val budget = ResponseJsonBudget()
        val limit = 200
        val items =
            (1..limit).map { index ->
                """{"uuid":"u$index","title":"${"t".repeat(2_000)}","artist":"${"a".repeat(2_000)}","album":"${"b".repeat(2_000)}"}"""
            }
        val page =
            budget.boundedPage(
                total = items.size,
                offset = 0,
                limit = limit,
                items = items.asSequence(),
                tooLargeMessage = "aggregate too large",
            ) { included, extra, nextOffset, byteTruncated ->
                renderPage(
                    """{"range":"30d","sort":"plays","offset":0,"limit":$limit,"tracks":""",
                    included,
                    extra,
                    nextOffset,
                    byteTruncated,
                )
            } as ResponseJsonBudget.PageResult.Page

        assertTrue(page.byteTruncated)
        assertTrue(page.returnedItems in 1 until limit)
        assertTrue(page.json.length * 2 <= ResponseJsonBudget.DEFAULT_MAX_UTF16_BYTES)
    }

    @Test
    fun oversizedFinalResponseBecomesBudgetError() {
        val budget = ResponseJsonBudget()
        val oversized = "x".repeat(ResponseJsonBudget.DEFAULT_MAX_UTF16_BYTES / 2 + 1)
        val bounded = budget.boundedResponse(oversized)

        assertTrue(bounded != oversized)
        assertTrue(bounded.contains("\"code\":\"response_too_large\""))
        assertTrue(bounded.length * 2 <= ResponseJsonBudget.DEFAULT_MAX_UTF16_BYTES)
        assertEquals(bounded, budget.boundedResponse(bounded))
    }

    @Test
    fun fittingResponseIsUnchanged() {
        val budget = ResponseJsonBudget()
        val response = """{"error":{"code":"not_found"}}"""

        assertEquals(response, budget.boundedResponse(response))
    }

    @Test
    fun budgetErrorItselfFitsTheBudget() {
        val budget = ResponseJsonBudget(1_024)
        val bounded = budget.boundedResponse("x".repeat(10_000))

        assertTrue(bounded.contains("response_too_large"))
        assertTrue(bounded.length * 2 <= 1_024)
    }
}
