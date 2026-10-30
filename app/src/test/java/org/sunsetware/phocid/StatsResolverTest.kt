package org.sunsetware.phocid

import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.fingerprint.LocalUriKey
import org.sunsetware.phocid.stats.resolver.TrackMeta
import org.sunsetware.phocid.stats.resolver.TrackResolver

@RunWith(RobolectricTestRunner::class)
class StatsResolverTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var db: StatsDatabase

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    StatsDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun resolver(fingerprint: (TrackMeta) -> String?) =
        TrackResolver(
            db = db,
            fingerprint = fingerprint,
            mediaRoots = { emptyList() },
        )

    private fun meta(
        uri: String = "content://media/external/audio/media/1",
        size: Long = 1024,
        file: File? = null,
        title: String? = "Song",
    ) =
        TrackMeta(
            uri = uri,
            file = file,
            size = size,
            modified = 1,
            title = title,
            artist = "Artist",
            album = "Album",
            durationMillis = 180_000,
        )

    @Test
    fun sameHashDifferentUriUsesSameRow() =
        runBlocking<Unit> {
            val resolver = resolver { "hash" }
            val first = resolver.resolve(meta(uri = "content://media/external/audio/media/1"))
            val second = resolver.resolve(meta(uri = "content://media/external/audio/media/2"))

            assertThat(second).isEqualTo(first)
            assertThat(db.trackDao().byRowId(first)!!.uuid).isNotBlank()
            assertThat(
                    db.aliasDao()
                        .resolve(
                            AliasKind.LOCAL_URI,
                            LocalUriKey.of("content://media/external/audio/media/1", 1024),
                        )
                )
                .isNull()
            assertThat(
                    db.aliasDao()
                        .resolve(
                            AliasKind.LOCAL_URI,
                            LocalUriKey.of("content://media/external/audio/media/2", 1024),
                        )
                )
                .isEqualTo(first)
        }

    @Test
    fun tagKeyMatchesWhenHashIsUnavailable() =
        runBlocking<Unit> {
            val resolver = resolver { null }
            val first = resolver.resolve(meta(uri = "content://media/external/audio/media/1"))
            val second = resolver.resolve(meta(uri = "content://media/external/audio/media/2"))

            assertThat(second).isEqualTo(first)
        }

    @Test
    fun localUriMatchesWhenFileAndTagsAreUnavailable() =
        runBlocking<Unit> {
            val resolver = resolver { null }
            val localMeta = meta(uri = "content://media/external/audio/media/9", title = null)
            val localMetaNoTags = localMeta.copy(artist = null, album = null)
            val first = resolver.resolve(localMetaNoTags)
            resolver.invalidate()
            val second = resolver.resolve(localMetaNoTags.copy(title = null))

            assertThat(second).isEqualTo(first)
        }

    @Test
    fun relativePathFallbackStillWorksWithoutHashOrTags() =
        runBlocking<Unit> {
            val file = temporaryFolder.newFile("Song.flac")
            val resolver = resolver { null }
            val first =
                resolver.resolve(
                    meta(
                            uri = "content://media/external/audio/media/1",
                            file = file,
                            title = null,
                        )
                        .copy(artist = null, album = null)
                )
            val second =
                resolver.resolve(
                    meta(
                            uri = "content://media/external/audio/media/2",
                            file = file,
                            title = null,
                        )
                        .copy(artist = null, album = null)
                )

            assertThat(second).isEqualTo(first)
        }
}
