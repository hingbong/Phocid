package org.sunsetware.phocid

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.InvalidTrack
import org.sunsetware.phocid.sortkey.SortKeyEntity
import org.sunsetware.phocid.sortkey.SortKeyKeys
import org.sunsetware.phocid.sortkey.SortKeyLookup

@RunWith(RobolectricTestRunner::class)
class SortKeyLookupTest {
    private val track =
        InvalidTrack.copy(
            id = 7,
            path = "/music/a.mp3",
            fileName = "a.mp3",
            title = "夜",
            artists = listOf("Artist"),
            album = "Album",
            duration = Duration.ofSeconds(3),
        )

    @Test
    fun lookup_localKeyWins() {
        val lookup =
            SortKeyLookup(
                byLocalUri = mapOf(SortKeyKeys.localUriKey(track) to "local"),
                byTag = mapOf(SortKeyKeys.tagKey(track)!! to "tag"),
            )
        assertThat(lookup.keyFor(track)).isEqualTo("local")
    }

    @Test
    fun lookup_tagFallbackWhenLocalKeyChanges() {
        val row =
            SortKeyEntity(
                localUriKey = "old",
                tagKey = SortKeyKeys.tagKey(track),
                sortKey = "fallback",
                updatedAt = 0,
            )
        assertThat(SortKeyLookup.from(listOf(row)).keyFor(track)).isEqualTo("fallback")
    }

    @Test
    fun lookup_ambiguousTag_ignored() {
        val rows =
            listOf(
                SortKeyEntity(localUriKey = "one", tagKey = SortKeyKeys.tagKey(track), sortKey = "one", updatedAt = 0),
                SortKeyEntity(localUriKey = "two", tagKey = SortKeyKeys.tagKey(track), sortKey = "two", updatedAt = 0),
            )
        assertThat(SortKeyLookup.from(rows).keyFor(track)).isNull()
    }

    @Test
    fun lookup_doesNotUsePathOrFileName() {
        val row =
            SortKeyEntity(
                localUriKey = SortKeyKeys.localUriKey(track),
                tagKey = null,
                sortKey = "value",
                updatedAt = 0,
            )
        val moved = track.copy(path = "/other/c.mp3", fileName = "c.mp3")
        assertThat(SortKeyLookup.from(listOf(row)).keyFor(moved)).isEqualTo("value")
    }
}
