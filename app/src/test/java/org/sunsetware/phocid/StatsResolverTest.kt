package org.sunsetware.phocid

import androidx.room.Room
import java.io.File
import java.util.UUID
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
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.fingerprint.LocalUriKey
import org.sunsetware.phocid.stats.fingerprint.RelPathKey
import org.sunsetware.phocid.stats.fingerprint.TagKey
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
        modified: Long = 1,
        file: File? = null,
        title: String? = "Song",
        artist: String? = "Artist",
        album: String? = "Album",
        durationMillis: Long = 180_000,
    ) =
        TrackMeta(
            uri = uri,
            file = file,
            size = size,
            modified = modified,
            title = title,
            artist = artist,
            album = album,
            durationMillis = durationMillis,
        )

    private fun noIdentityMeta(uri: String, modified: Long = 1) =
        meta(uri = uri, modified = modified, title = null, artist = null, album = null)

    private suspend fun insertTrack(title: String): Long =
        db.trackDao()
            .insert(
                TrackEntity(
                    uuid = UUID.randomUUID().toString(),
                    title = title,
                    artist = "Artist",
                    album = "Album",
                    durationMillis = 180_000,
                    createdAt = 0,
                )
            )

    private suspend fun putAlias(kind: Int, value: String, rowId: Long) {
        db.aliasDao().put(TrackAliasEntity(kind, value, rowId))
    }

    private fun tagKeys(): List<String> = TagKey.candidates("Artist", "Album", "Song", 180_000)

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
    fun localUriAloneDoesNotSelectTheRow() =
        runBlocking<Unit> {
            val resolver = resolver { null }
            val localMeta = noIdentityMeta("content://media/external/audio/media/9")
            val first = resolver.resolve(localMeta)
            val second = resolver.resolve(localMeta)

            // A local URI is only a hint. Without hash or tags the safe fallback is a temporary
            // split.
            assertThat(second).isNotEqualTo(first)
            assertThat(db.trackDao().byRowId(first)).isNotNull()
            assertThat(db.trackDao().byRowId(second)).isNotNull()
            assertThat(
                    db.aliasDao()
                        .resolve(
                            AliasKind.LOCAL_URI,
                            LocalUriKey.of(localMeta.uri, localMeta.size),
                        )
                )
                .isEqualTo(first)
        }

    @Test
    fun relativePathFallbackStillWorksWithoutHashOrTags() =
        runBlocking<Unit> {
            val file = temporaryFolder.newFile("Song.flac")
            val resolver = resolver { null }
            val first =
                resolver.resolve(
                    noIdentityMeta("content://media/external/audio/media/1").copy(file = file)
                )
            val second =
                resolver.resolve(
                    noIdentityMeta("content://media/external/audio/media/2").copy(file = file)
                )

            assertThat(second).isEqualTo(first)
        }

    @Test
    fun sameUriAndSizeWithDifferentAudioCreatesNewRow() =
        runBlocking<Unit> {
            val resolver = resolver { meta -> if (meta.modified == 1L) "old-hash" else "new-hash" }
            val first =
                resolver.resolve(
                    meta(
                        uri = "content://media/external/audio/media/7",
                        modified = 1,
                        title = "Old Song",
                        artist = "Old Artist",
                        album = "Old Album",
                    )
                )
            val second =
                resolver.resolve(
                    meta(
                        uri = "content://media/external/audio/media/7",
                        modified = 2,
                        title = "New Song",
                        artist = "New Artist",
                        album = "New Album",
                    )
                )

            assertThat(second).isNotEqualTo(first)
            assertThat(db.trackDao().byRowId(first)!!.title).isEqualTo("Old Song")
            assertThat(db.trackDao().byRowId(first)!!.artist).isEqualTo("Old Artist")
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "old-hash")).isEqualTo(first)
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "new-hash")).isEqualTo(second)
            // The stale local hint must not be stolen from the old row.
            assertThat(
                    db.aliasDao()
                        .resolve(
                            AliasKind.LOCAL_URI,
                            LocalUriKey.of("content://media/external/audio/media/7", 1024),
                        )
                )
                .isEqualTo(first)
        }

    @Test
    fun ambiguousTagCandidatesCreateNewRowWithoutMerging() =
        runBlocking<Unit> {
            val keys = tagKeys()
            assertThat(keys).hasSize(2)
            val a = insertTrack("A")
            val b = insertTrack("B")
            putAlias(AliasKind.TAG_KEY, keys[0], a)
            putAlias(AliasKind.TAG_KEY, keys[1], b)
            db.totalsDao().accumulate(a, 1, 100, 1)
            db.totalsDao().accumulate(b, 2, 200, 2)

            val resolver = resolver { null }
            val resolved = resolver.resolve(meta())

            assertThat(resolved).isNotEqualTo(a).isNotEqualTo(b)
            assertThat(db.trackDao().byRowId(a)).isNotNull()
            assertThat(db.trackDao().byRowId(b)).isNotNull()
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, keys[0])).isEqualTo(a)
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, keys[1])).isEqualTo(b)
            assertThat(db.totalsDao().byRowId(a)!!.totalCount).isEqualTo(1)
            assertThat(db.totalsDao().byRowId(b)!!.totalCount).isEqualTo(2)
        }

    @Test
    fun hashHitMergesSingleConflictingTagRow() =
        runBlocking<Unit> {
            val keys = tagKeys()
            val hashRow = insertTrack("Hash")
            putAlias(AliasKind.AUDIO_HASH, "hash", hashRow)
            val tagRow = insertTrack("Tag")
            putAlias(AliasKind.TAG_KEY, keys[0], tagRow)
            putAlias(
                AliasKind.LOCAL_URI,
                LocalUriKey.of("content://media/external/audio/media/1", 1024),
                tagRow,
            )
            db.totalsDao().accumulate(tagRow, 2, 500, 10)

            val resolver = resolver { "hash" }
            val resolved = resolver.resolve(meta())

            assertThat(resolved).isEqualTo(hashRow)
            assertThat(db.trackDao().byRowId(tagRow)).isNull()
            assertThat(db.totalsDao().byRowId(hashRow)!!.totalCount).isEqualTo(2)
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, keys[0])).isEqualTo(hashRow)
            assertThat(
                    db.aliasDao()
                        .resolve(
                            AliasKind.LOCAL_URI,
                            LocalUriKey.of("content://media/external/audio/media/1", 1024),
                        )
                )
                .isEqualTo(hashRow)
        }

    @Test
    fun hashHitDoesNotMergeMultipleTagRows() =
        runBlocking<Unit> {
            val keys = tagKeys()
            val hashRow = insertTrack("Hash")
            putAlias(AliasKind.AUDIO_HASH, "hash", hashRow)
            val a = insertTrack("A")
            val b = insertTrack("B")
            putAlias(AliasKind.TAG_KEY, keys[0], a)
            putAlias(AliasKind.TAG_KEY, keys[1], b)
            db.totalsDao().accumulate(a, 1, 100, 1)
            db.totalsDao().accumulate(b, 2, 200, 2)

            val resolver = resolver { "hash" }
            val resolved = resolver.resolve(meta())

            assertThat(resolved).isEqualTo(hashRow)
            assertThat(db.trackDao().byRowId(a)).isNotNull()
            assertThat(db.trackDao().byRowId(b)).isNotNull()
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, keys[0])).isEqualTo(a)
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, keys[1])).isEqualTo(b)
            assertThat(db.totalsDao().byRowId(a)!!.totalCount).isEqualTo(1)
            assertThat(db.totalsDao().byRowId(b)!!.totalCount).isEqualTo(2)
        }

    @Test
    fun hashHitWithConflictingWeakCluesDoesNotMerge() =
        runBlocking<Unit> {
            val keys = tagKeys()
            val file = temporaryFolder.newFile("Song.flac")
            val relPath = RelPathKey.of(file.absolutePath, emptyList())
            val hashRow = insertTrack("Hash")
            putAlias(AliasKind.AUDIO_HASH, "hash", hashRow)
            val tagRow = insertTrack("Tag")
            putAlias(AliasKind.TAG_KEY, keys[0], tagRow)
            val relRow = insertTrack("Rel")
            putAlias(AliasKind.REL_PATH, relPath, relRow)

            val resolver = resolver { "hash" }
            val resolved = resolver.resolve(meta(file = file))

            assertThat(resolved).isEqualTo(hashRow)
            assertThat(db.trackDao().byRowId(tagRow)).isNotNull()
            assertThat(db.trackDao().byRowId(relRow)).isNotNull()
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, keys[0])).isEqualTo(tagRow)
            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, relPath)).isEqualTo(relRow)
        }

    @Test
    fun conflictingLocalUriAliasIsNotStolen() =
        runBlocking<Unit> {
            var fingerprint: String? = null
            val resolver = resolver { fingerprint }
            val localMeta = noIdentityMeta("content://media/external/audio/media/11")
            val oldRow = resolver.resolve(localMeta)

            val hashRow = insertTrack("Hash")
            putAlias(AliasKind.AUDIO_HASH, "hash", hashRow)
            fingerprint = "hash"

            val resolved = resolver.resolve(localMeta)

            assertThat(resolved).isEqualTo(hashRow)
            assertThat(db.trackDao().byRowId(oldRow)).isNotNull()
            assertThat(
                    db.aliasDao()
                        .resolve(
                            AliasKind.LOCAL_URI,
                            LocalUriKey.of(localMeta.uri, localMeta.size),
                        )
                )
                .isEqualTo(oldRow)
        }
}
