package org.sunsetware.phocid

import android.icu.text.Collator
import java.util.Locale
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.InvalidTrack
import org.sunsetware.phocid.data.LibraryIndex
import org.sunsetware.phocid.data.PlaylistEntry
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.data.RealizedPlaylist
import org.sunsetware.phocid.data.RealizedPlaylistEntry
import org.sunsetware.phocid.data.UnfilteredTrackIndex
import org.sunsetware.phocid.data.getChildMediaItems

@RunWith(RobolectricTestRunner::class)
class Media3BrowseTest {
    @Test
    fun playlistChildrenDropDeadEntriesInsteadOfCrashing() {
        val playlistId = UUID.randomUUID()
        val entries =
            listOf(
                RealizedPlaylistEntry(
                    playlistId,
                    0,
                    InvalidTrack,
                    PlaylistEntry(playlistId, "/a"),
                ),
                // The middle entry's file is gone; it must be filtered out instead of being
                // handed to the sort selector as a null track, which crashed the whole browse.
                RealizedPlaylistEntry(playlistId, 1, null, PlaylistEntry(playlistId, "/deleted")),
                RealizedPlaylistEntry(
                    playlistId,
                    2,
                    InvalidTrack,
                    PlaylistEntry(playlistId, "/c"),
                ),
            )
        val playlists = mapOf(playlistId to RealizedPlaylist(playlistId, "list", entries, 0))
        val collator = Collator.getInstance(Locale.ROOT).freeze()
        val libraryIndex =
            LibraryIndex(UnfilteredTrackIndex(null, emptyMap()), collator, emptyList(), emptyList())

        val items = getChildMediaItems(Preferences(), libraryIndex, playlists, "playlists:$playlistId")

        assertThat(items).hasSize(2)
        assertThat(items.map { it.mediaId }).containsOnly(InvalidTrack.id.toString())
    }
}
