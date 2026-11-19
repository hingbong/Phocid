package org.sunsetware.phocid

import android.content.Intent
import android.net.Uri
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.InvalidTrack

@RunWith(RobolectricTestRunner::class)
class ShareIntentTest {
    private fun track(id: Long, path: String) = InvalidTrack.copy(id = id, path = path)

    @Test
    fun emptyTracksProduceNoIntent() {
        assertThat(MainActivity.buildShareIntent(emptyList())).isNull()
    }

    @Test
    fun singleTrackShareGrantsReadPermission() {
        val track = track(1, "/music/song.mp3")
        val intent = requireNotNull(MainActivity.buildShareIntent(listOf(track)))

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotZero()
        // Sharing is read-only; no write grant should be handed out.
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isZero()
        @Suppress("DEPRECATION")
        assertThat(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).isEqualTo(track.uri)
    }

    @Test
    fun multipleTrackShareGrantsReadPermissionForEveryUri() {
        val tracks = listOf(track(1, "/music/a.mp3"), track(2, "/music/b.flac"))
        val intent = requireNotNull(MainActivity.buildShareIntent(tracks))

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotZero()
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isZero()
        @Suppress("DEPRECATION")
        assertThat(intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
            .containsExactlyElementsOf(tracks.map { it.uri })
        // Mixed formats fall back to the generic audio type.
        assertThat(intent.type).isEqualTo("audio/*")
    }
}
