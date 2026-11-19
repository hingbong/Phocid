package org.sunsetware.phocid

import android.icu.text.Collator
import java.util.Locale
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.InvalidTrack
import org.sunsetware.phocid.data.LibraryIndex
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.data.UnfilteredTrackIndex
import org.sunsetware.phocid.utils.distinctCaseInsensitive

/**
 * The artist, album-artist, and genre indexes are built by a single-pass inverted index. These
 * tests re-derive every group with the naive per-name full-library filter that the old quadratic
 * implementation used, and assert that both algorithms agree on a mixed-case corpus with
 * multi-artist tracks, duplicated names inside one track, and shared genres.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryGroupingParityTest {
    private val collator = Collator.getInstance(Locale.ROOT).freeze()

    private fun track(
        id: Long,
        artists: List<String>,
        genres: List<String>,
        album: String?,
        albumArtist: String?,
    ) =
        InvalidTrack.copy(
            id = id,
            artists = artists,
            genres = genres,
            album = album,
            albumArtists = listOfNotNull(albumArtist),
        )

    private val corpus =
        listOf(
            // A duplicated spelling inside one track must not double-count the membership.
            track(
                id = 1,
                artists = listOf("A", "a", "B"),
                genres = listOf("Rock", "rock", "Pop"),
                album = "Album X",
                albumArtist = "AA",
            ),
            track(
                id = 2,
                artists = listOf("a"),
                genres = listOf("pop"),
                album = "album x",
                albumArtist = "aa",
            ),
            track(
                id = 3,
                artists = listOf("C"),
                genres = listOf("Jazz"),
                album = "Album Y",
                albumArtist = "AA",
            ),
            track(
                id = 4,
                artists = listOf("b"),
                genres = listOf("Rock"),
                album = null,
                albumArtist = null,
            ),
        )

    private val index =
        LibraryIndex(
            UnfilteredTrackIndex(null, corpus.associateBy { it.id }),
            collator,
            emptyList(),
            emptyList(),
        )

    private fun idsOf(tracks: Collection<Track>) = tracks.map { it.id }.toSet()

    @Test
    fun artistGroupsMatchTheNaiveFilter() {
        val naiveNames = corpus.flatMap { it.artists }.distinctCaseInsensitive()
        assertThat(index.artists).hasSize(naiveNames.size)

        for (name in naiveNames) {
            val expected =
                idsOf(corpus.filter { track -> track.artists.any { it.equals(name, true) } })
            assertThat(index.artists[name]!!.tracks.map { it.id })
                .containsExactlyInAnyOrderElementsOf(expected)
            // Album slices are a subset of the artist's tracks (null albums form no slice).
            assertThat(
                    index.artists[name]!!.albumSlices.flatMap { it.tracks }.map { it.id }.toSet()
                )
                .isSubsetOf(expected)
        }

        // The representative spelling is the most frequent variant, exactly as before: the "b"
        // group saw "B" first and ties break by first occurrence.
        assertThat(index.artists["a"]!!.name).isEqualTo("a")
        assertThat(index.artists["b"]!!.name).isEqualTo("B")
    }

    @Test
    fun genreGroupsMatchTheNaiveFilterIncludingArtistSlices() {
        val naiveNames = corpus.flatMap { it.genres }.distinctCaseInsensitive()
        assertThat(index.genres).hasSize(naiveNames.size)

        for (name in naiveNames) {
            val expected =
                idsOf(corpus.filter { track -> track.genres.any { it.equals(name, true) } })
            assertThat(index.genres[name]!!.tracks.map { it.id })
                .containsExactlyInAnyOrderElementsOf(expected)

            // Every artist's slice must hold exactly the artist's own tracks in this genre, and
            // artists without tracks in the genre must have no slice at all.
            for (artist in index.artists.values) {
                val expectedSlice =
                    idsOf(artist.tracks.filter { it.genres.any { g -> g.equals(name, true) } })
                val slice = index.genres[name]!!.artistSlices.firstOrNull { it.artist === artist }
                if (expectedSlice.isEmpty()) {
                    assertThat(slice).isNull()
                } else {
                    assertThat(slice!!.tracks.map { it.id })
                        .containsExactlyInAnyOrderElementsOf(expectedSlice)
                }
            }
        }
    }

    @Test
    fun albumArtistGroupsMatchTheNaiveFilter() {
        val naiveNames = index.albums.values.flatMap { it.albumArtists }.distinctCaseInsensitive()
        assertThat(index.albumArtists).hasSize(naiveNames.size)

        for (name in naiveNames) {
            val expected =
                index.albums.values
                    .filter { it.albumArtists.any { it.equals(name, true) } }
                    .flatMap { it.tracks }
                    .map { it.id }
                    .toSet()
            assertThat(
                    index.albumArtists[name]!!.albums.flatMap { it.tracks }.map { it.id }.toSet()
                )
                .containsExactlyInAnyOrderElementsOf(expected)
        }
    }
}
