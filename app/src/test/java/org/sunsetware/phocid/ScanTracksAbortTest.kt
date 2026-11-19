package org.sunsetware.phocid

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
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

/** Answers MediaStore's get_version call so the scan sees a stable library version. */
private fun getVersionBundle(method: String): Bundle? =
    if (method == "get_version") Bundle().apply { putString("version", "test-version") } else null

/**
 * A null MediaStore cursor must abort the scan, and the progress callback must reach its terminal
 * state. Each test registers its own "media" provider, so the real MediaStore implementation talks
 * to the fixture and no custom shadow is needed.
 */

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ScanTracksAbortTest.TestShadowMediaStore::class])
class ScanTracksAbortTest {
    /**
     * MediaStore.getVersion goes through ContentResolver.call, which this sandbox does not
     * implement; without the shadow the scan throws before it ever reaches the cursor.
     */
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
                // Artwork colour extraction is skipped: it reaches ContentResolver.loadThumbnail,
                // which Robolectric does not implement, and its bitmap loader owns a non-daemon
                // executor that would keep the test JVM from exiting.
                true,
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
                // Artwork colour extraction is skipped: it reaches ContentResolver.loadThumbnail,
                // which Robolectric does not implement, and its bitmap loader owns a non-daemon
                // executor that would keep the test JVM from exiting.
                true,
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

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
            getVersionBundle(method) ?: super.call(method, arg, extras)

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
                        // Explicit nullable Any: a mixed Long/String/Int/Null array would
                        // otherwise reify to an intersection type, which is a compile error.
                        arrayOf<Any?>(
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

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
            getVersionBundle(method) ?: super.call(method, arg, extras)

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