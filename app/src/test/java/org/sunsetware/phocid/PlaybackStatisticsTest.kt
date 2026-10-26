package org.sunsetware.phocid

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.PlaybackStatistics
import org.sunsetware.phocid.data.PlaybackStatisticsBackup
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.data.addPlayedTime
import org.sunsetware.phocid.data.merge
import org.sunsetware.phocid.data.recordPlay
import org.sunsetware.phocid.data.replaceWith
import org.sunsetware.phocid.data.statisticsKey
import org.sunsetware.phocid.data.toBackup

@RunWith(RobolectricTestRunner::class)
class PlaybackStatisticsTest {
    private fun track(
        id: Long = 1,
        path: String = "/music/song.flac",
        title: String? = "Song",
        artists: List<String> = listOf("Artist"),
        album: String? = "Album",
        albumArtists: List<String> = listOf("Album Artist"),
        duration: Duration = 180.seconds,
        size: Long = 1000,
        trackNumber: Int? = 1,
        discNumber: Int? = 1,
    ): Track {
        return Track(
            id = id,
            path = path,
            fileName = path.substringAfterLast('/'),
            dateAdded = 0,
            version = 0,
            title = title,
            artists = artists,
            album = album,
            albumArtists = albumArtists,
            genres = emptyList(),
            year = null,
            trackNumber = trackNumber,
            discNumber = discNumber,
            duration = duration,
            size = size,
            format = "FLAC",
            sampleRate = 44100,
            bitRate = 1000,
            bitDepth = 16,
            hasArtwork = false,
            vibrantColor = null,
            mutedColor = null,
            unsyncedLyrics = null,
            comment = null,
        )
    }

    @Test
    fun statisticsKey_ignoresPathAndMediaStoreId() {
        val original = track(id = 1, path = "/music/song.flac")
        val moved = track(id = 2, path = "/other/folder/song.flac")
        assertThat(original.statisticsKey()).isEqualTo(moved.statisticsKey())
    }

    @Test
    fun statisticsKey_normalizesCaseAndWhitespace() {
        val first = track(title = " Song ", artists = listOf("Artist"), album = " Album ")
        val second = track(title = "song", artists = listOf(" artist "), album = "album")
        assertThat(first.statisticsKey()).isEqualTo(second.statisticsKey())
    }

    @Test
    fun statisticsKey_distinguishesMetadata() {
        val base = track()
        assertThat(base.statisticsKey()).isNotEqualTo(track(album = "Other").statisticsKey())
        assertThat(base.statisticsKey()).isNotEqualTo(track(duration = 181.seconds).statisticsKey())
        assertThat(base.statisticsKey()).isNotEqualTo(track(size = 1001).statisticsKey())
    }

    @Test
    fun recordPlay_accumulatesCountAndTime() {
        val track = track()
        val first = PlaybackStatistics().recordPlay(track, 60_000, 1_000)
        val firstStatistics = first.entries.values.single()
        assertThat(firstStatistics.playCount).isEqualTo(1)
        assertThat(firstStatistics.totalPlayedMillis).isEqualTo(60_000)
        assertThat(firstStatistics.firstPlayedAt).isEqualTo(1_000)
        assertThat(firstStatistics.lastPlayedAt).isEqualTo(1_000)
        assertThat(firstStatistics.title).isEqualTo("Song")
        assertThat(firstStatistics.artists).containsExactly("Artist")
        assertThat(firstStatistics.album).isEqualTo("Album")

        val second = first.recordPlay(track, 30_000, 2_000)
        val secondStatistics = second.entries.values.single()
        assertThat(secondStatistics.playCount).isEqualTo(2)
        assertThat(secondStatistics.totalPlayedMillis).isEqualTo(90_000)
        assertThat(secondStatistics.firstPlayedAt).isEqualTo(1_000)
        assertThat(secondStatistics.lastPlayedAt).isEqualTo(2_000)
    }

    @Test
    fun addPlayedTime_onlyUpdatesExistingEntry() {
        val track = track()
        assertThat(PlaybackStatistics().addPlayedTime(track, 5_000))
            .isEqualTo(PlaybackStatistics())

        val statistics = PlaybackStatistics().recordPlay(track, 10_000, 1_000)
        val updated = statistics.addPlayedTime(track, 5_000)
        assertThat(updated.entries.values.single().playCount).isEqualTo(1)
        assertThat(updated.entries.values.single().totalPlayedMillis).isEqualTo(15_000)
    }

    @Test
    fun merge_sumsCountsAndKeepsExtremes() {
        val track = track()
        val first = PlaybackStatistics().recordPlay(track, 10_000, 1_000)
        val second = PlaybackStatistics().recordPlay(track, 20_000, 2_000)
        val merged = first.merge(second).entries.values.single()
        assertThat(merged.playCount).isEqualTo(2)
        assertThat(merged.totalPlayedMillis).isEqualTo(30_000)
        assertThat(merged.firstPlayedAt).isEqualTo(1_000)
        assertThat(merged.lastPlayedAt).isEqualTo(2_000)
    }

    @Test
    fun replaceWith_usesImportedEntries() {
        val firstTrack = track()
        val secondTrack = track(id = 2, path = "/music/other.flac", title = "Other")
        val first = PlaybackStatistics().recordPlay(firstTrack, 10_000, 1_000)
        val second = PlaybackStatistics().recordPlay(secondTrack, 20_000, 2_000)
        val replaced = first.replaceWith(second)
        assertThat(replaced.entries.keys).containsExactly(secondTrack.statisticsKey())
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun backupJson_containsNoPathAndRoundTrips() {
        val track = track(path = "/music/song.flac")
        val statistics = PlaybackStatistics().recordPlay(track, 60_000, 1_000)
        val backup = statistics.toBackup(123_456)
        val json =
            Json { encodeDefaults = true }
                .encodeToString(backup)
        assertThat(json).doesNotContain("/music/")
        assertThat(json).doesNotContain("song.flac")

        val decoded =
            Json { ignoreUnknownKeys = true }
                .decodeFromString<PlaybackStatisticsBackup>(json)
        assertThat(decoded.entries).isEqualTo(backup.entries)
        assertThat(decoded.toStatistics()).isEqualTo(statistics)
    }
}
