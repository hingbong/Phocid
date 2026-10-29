package org.sunsetware.phocid

import android.icu.text.Collator
import kotlin.time.Duration.Companion.seconds
import java.util.Locale
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.InvalidTrack
import org.sunsetware.phocid.data.LibraryIndex
import org.sunsetware.phocid.data.UnfilteredTrackIndex
import org.sunsetware.phocid.sortkey.SortKeyKeys
import org.sunsetware.phocid.sortkey.SortKeyLookup

@RunWith(RobolectricTestRunner::class)
class SortKeyLibraryIndexTest {
    private val collator = Collator.getInstance(Locale.ROOT).freeze()
    private val track =
        InvalidTrack.copy(
            id = 1,
            path = "/music/a.mp3",
            fileName = "a.mp3",
            title = "夜",
            artists = listOf("Artist"),
            album = "Album",
            duration = 3.seconds,
        )

    private fun index(lookup: SortKeyLookup): LibraryIndex =
        LibraryIndex(
            UnfilteredTrackIndex(null, mapOf(track.id to track)),
            collator,
            emptyList(),
            emptyList(),
            lookup,
        )

    @Test
    fun index_appliesSortKey() {
        val lookup =
            SortKeyLookup(
                byLocalUri = mapOf(SortKeyKeys.localUriKey(track) to "よる"),
            )
        val indexed = index(lookup).tracks.getValue(track.id)
        assertThat(indexed.sortTitle).isEqualTo("よる")
    }

    @Test
    fun index_withoutSortKey_fallsBackToTitle() {
        val indexed = index(SortKeyLookup.EMPTY).tracks.getValue(track.id)
        assertThat(indexed.sortTitle).isEqualTo("夜")
    }
}
