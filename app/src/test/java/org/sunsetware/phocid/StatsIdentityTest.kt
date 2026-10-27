package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.stats.fingerprint.LocalUriKey
import org.sunsetware.phocid.stats.fingerprint.RelPathKey
import org.sunsetware.phocid.stats.fingerprint.TagKey

class StatsIdentityTest {
    @Test
    fun tagKeySurvivesReencoding() {
        val original = TagKey.of("Artist", "Album", "Song", 180_000)
        val reencoded = TagKey.of(" artist ", "ALBUM", "Song (Remastered)", 180_020)
        assertThat(reencoded).isEqualTo(original)
    }

    @Test
    fun tagKeyUsesDurationBuckets() {
        assertThat(TagKey.of("A", "B", "C", 60_000))
            .isEqualTo(TagKey.of("A", "B", "C", 61_500))
        assertThat(TagKey.of("A", "B", "C", 60_000))
            .isNotEqualTo(TagKey.of("A", "B", "C", 63_000))
    }

    @Test
    fun relativePathIsStableAcrossMediaRoots() {
        val roots = listOf("/storage/emulated/0/Music", "/sdcard/Music")
        assertThat(RelPathKey.of("/storage/emulated/0/Music/Artist/Song.flac", roots))
            .isEqualTo("artist/song.flac")
    }

    @Test
    fun localUriKeyIncludesSize() {
        val uri = "content://media/external/audio/media/42"
        assertThat(LocalUriKey.of(uri, 1024)).isEqualTo(LocalUriKey.of(uri, 1024))
        assertThat(LocalUriKey.of(uri, 1024)).isNotEqualTo(LocalUriKey.of(uri, 2048))
    }
}
