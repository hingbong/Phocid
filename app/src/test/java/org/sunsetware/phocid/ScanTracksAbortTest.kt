package org.sunsetware.phocid

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.sunsetware.phocid.data.InvalidTrack
import org.sunsetware.phocid.data.UnfilteredTrackIndex
import org.sunsetware.phocid.data.scanTracks

/**
 * A null MediaStore cursor must abort the scan, and the progress callback must reach its terminal
 * state. [TestShadowMediaStore] keeps the real MediaStore.getVersion implementation (which queries
 * the "media" provider) out of the way so the registered test providers fully control the cursor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ScanTracksAbortTest.TestShadowMediaStore::class])
class ScanTracksAbortTest {
    @Implements(MediaStore::class)
    class TestShadowMediaStore {
        companion object {
            @JvmStatic
            @Implementation
            fun getVersion(@Suppress("unused") context: Context): String = "test-version"
        }
    }

    private fun grantReadPermission() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(org.sunsetware.phocid.READ_PERMISSION)
    }

    @Test
    fun aNullMediaStoreCursorAbortsTheScanAndKeepsTheOldIndex() = runBlocking<Unit> {
        grantReadPermission()
        Robolectric.buildContentProvider(NullCursorProvider::class.java).create("media")
        val old = UnfilteredTrackIndex("v1", mapOf(InvalidTrack.id to InvalidTrack))

        val result =
            scanTracks(
                RuntimeEnvironment.getApplication(),
                false,
                false,
                old,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            ) { _, _ -> }

        // The scan must abort rather than publish an empty library over the old index.
        assertThat(result).isNull()
    }

    @Test
    fun scanProgressReachesTheTerminalState() = runBlocking<Unit> {
        grantReadPermission()
        Robolectric.buildContentProvider(OneCrudeRowProvider::class.java).create("media")
        val reports = mutableListOf<Pair<Int, Int>>()
        val old = UnfilteredTrackIndex("test-version", emptyMap())

        val result =
            scanTracks(
                RuntimeEnvironment.getApplication(),
                false,
                false,
                old,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            ) { current, total -> reports.add(current to total) }

        // The crude track's file does not exist, so the scan worker drops it; the scan still
        // completes, and the last progress report must be the terminal (total, total) state.
        assertThat(result).isNotNull()
        assertThat(reports).isNotEmpty()
        assertThat(reports.last()).isEqualTo(1 to 1)
    }

    @Test
    fun parseSettingsChangeInvalidatesTheScanCache() = runBlocking<Unit> {
        grantReadPermission()
        Robolectric.buildContentProvider(OneCrudeRowProvider::class.java).create("media")
        val context = RuntimeEnvironment.getApplication()
        val oldTrack =
            InvalidTrack.copy(id = 7L, version = 1L, path = "/nonexistent-music/song.flac")
        val old =
            UnfilteredTrackIndex("test-version", mapOf(7L to oldTrack), parseFingerprint = 1L)

        // Same parse settings: the matching row is reused without touching the file, so the
        // scan hands back the old index instance.
        val reused =
            scanTracks(
                context,
                false,
                false,
                old,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                parseFingerprint = 1L,
            ) { _, _ -> }
        assertThat(reused).isSameAs(old)

        // Different settings: every row must be re-read. The file is missing, so the row is
        // dropped and the fresh index carries the new fingerprint.
        val reparsed =
            scanTracks(
                context,
                false,
                false,
                old,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                parseFingerprint = 2L,
            ) { _, _ -> }
        assertThat(reparsed).isNotSameAs(old)
        assertThat(reparsed!!.tracks).isEmpty()
        assertThat(reparsed.parseFingerprint).isEqualTo(2L)
    }

    class NullCursorProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    class OneCrudeRowProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? =
            MatrixCursor(
                    arrayOf(
                        Media._ID,
                        Media.DATA,
                        Media.DATE_ADDED,
                        Media.DATE_MODIFIED,
                        Media.TITLE,
                        Media.ARTIST,
                        Media.ALBUM,
                        Media.ALBUM_ARTIST,
                        Media.GENRE,
                        Media.YEAR,
                        Media.TRACK,
                        Media.DISC_NUMBER,
                        Media.DURATION,
                        Media.SIZE,
                        Media.BITRATE,
                    )
                )
                .apply {
                    addRow(
                        arrayOf(
                            7L,
                            "/nonexistent-music/song.flac",
                            0L,
                            1L,
                            "Song",
                            "Artist",
                            "Album",
                            null,
                            "Genre",
                            2020,
                            1,
                            null,
                            180_000L,
                            1024L,
                            null,
                        )
                    )
                }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
