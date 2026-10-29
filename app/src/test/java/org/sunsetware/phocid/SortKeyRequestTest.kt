package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.agent.SortKeyRequest
import org.sunsetware.phocid.agent.SortKeyRequestResult

@RunWith(RobolectricTestRunner::class)
class SortKeyRequestTest {
    private fun request(vararg items: JSONObject): String =
        JSONObject().put("items", JSONArray().apply { items.forEach { put(it) } }).toString()

    private fun item(id: Long, key: Any?): JSONObject =
        JSONObject().put("libraryId", id).put("sortKey", key ?: JSONObject.NULL)

    @Test
    fun parse_validBatch() {
        val parsed =
            SortKeyRequest.parse(
                request(item(1, "  Ａ   b  "), item(2, null))
            )
        assertThat(parsed).isInstanceOf(SortKeyRequestResult.Ok::class.java)
        val batch = (parsed as SortKeyRequestResult.Ok).batch
        assertThat(batch.items).containsExactly(
            org.sunsetware.phocid.agent.SortKeyItem(1, "A b"),
            org.sunsetware.phocid.agent.SortKeyItem(2, null),
        )
        assertThat(batch.errors).isEmpty()
    }

    @Test
    fun parse_missingItems_fails() {
        assertThat(SortKeyRequest.parse("{}"))
            .isInstanceOf(SortKeyRequestResult.Failure::class.java)
    }

    @Test
    fun parse_emptyItems_fails() {
        assertThat(SortKeyRequest.parse("""{"items":[]}"""))
            .isInstanceOf(SortKeyRequestResult.Failure::class.java)
    }

    @Test
    fun parse_tooManyItems_fails() {
        val array = JSONArray()
        repeat(SortKeyRequest.MAX_ITEMS + 1) { array.put(item(it.toLong() + 1, "x")) }
        val raw = JSONObject().put("items", array).toString()
        assertThat(SortKeyRequest.parse(raw))
            .isInstanceOf(SortKeyRequestResult.Failure::class.java)
    }

    @Test
    fun parse_missingSortKey_isPerItemError() {
        val parsed =
            SortKeyRequest.parse(
                request(JSONObject().put("libraryId", 1))
            )
        val batch = (parsed as SortKeyRequestResult.Ok).batch
        assertThat(batch.errors.single().code).isEqualTo("invalid_sort_key")
    }

    @Test
    fun parse_invalidSortKey_isPerItemError() {
        val parsed = SortKeyRequest.parse(request(item(1, "a\nb")))
        val batch = (parsed as SortKeyRequestResult.Ok).batch
        assertThat(batch.errors.single().code).isEqualTo("invalid_sort_key")
    }

    @Test
    fun parse_badType_isPerItemError() {
        val parsed = SortKeyRequest.parse(request(item(1, 42)))
        val batch = (parsed as SortKeyRequestResult.Ok).batch
        assertThat(batch.errors.single().code).isEqualTo("invalid_sort_key")
    }
}
