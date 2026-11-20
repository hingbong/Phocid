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
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.resolver.RelPathAliasRebuild
import org.sunsetware.phocid.stats.resolver.TrackMeta

/**
 * Safe-scope characterization of the G4 scan-time REL_PATH rebuild ([RelPathAliasRebuild]).
 *
 * The rebuild only strengthens a basename-only legacy key when an AUDIO_HASH anchors the row AND a
 * scanned file's computed hash positively matches it AND the rooted key is a genuine subdir
 * improvement that no other row owns. Every weaker case is skipped so identity is never stranded or
 * stolen. These tests pin that safe scope; resolve()/doResolve() and the legacy characterization in
 * StatsRelPathLegacyAliasTest are deliberately untouched.
 */
@RunWith(RobolectricTestRunner::class)
class StatsRelPathRebuildTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var db: StatsDatabase
    private val hashes = mutableMapOf<String, String>()

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

    private fun rebuild(mediaRoots: () -> List<String>) =
        RelPathAliasRebuild(
            db = db,
            fingerprint = { meta -> meta.file?.let { hashes[it.absolutePath] } },
            mediaRoots = mediaRoots,
        )

    private fun root() = temporaryFolder.root.absolutePath

    private fun fileIn(subdir: String?, name: String, hash: String): File {
        val dir = if (subdir == null) temporaryFolder.root else temporaryFolder.newFolder(subdir)
        val file = File(dir, name)
        file.writeBytes(byteArrayOf(1, 2, 3))
        hashes[file.absolutePath] = hash
        return file
    }

    private fun meta(file: File) =
        TrackMeta(
            uri = "content://media/external/audio/media/${file.absolutePath.hashCode()}",
            file = file,
            size = 3,
            modified = 1,
            title = null,
            artist = null,
            album = null,
            durationMillis = 180_000,
        )

    private suspend fun insertTrack(uuid: String, title: String = "Song"): Long =
        db.trackDao()
            .insert(
                TrackEntity(
                    uuid = uuid,
                    title = title,
                    artist = "Artist",
                    album = "Album",
                    durationMillis = 180_000,
                    createdAt = 1,
                ),
            )

    private suspend fun putAlias(kind: Int, value: String, rowId: Long) {
        db.aliasDao().put(TrackAliasEntity(kind, value, rowId))
    }

    @Test
    fun hashAnchoredLegacyRowIsUpgradedToRootedKeyAndIsIdempotent() =
        runBlocking<Unit> {
            val row = insertTrack("uuid-legacy")
            putAlias(AliasKind.REL_PATH, "song.flac", row)
            putAlias(AliasKind.AUDIO_HASH, "hash-a", row)
            db.totalsDao().accumulate(row, 4, 900_000, 10, 0, 0, 0, 0)
            val file = fileIn("sub", "song.flac", "hash-a")

            rebuild { listOf(root()) }.rebuild(listOf(meta(file)))

            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "sub/song.flac")).isEqualTo(row)
            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "song.flac")).isNull()
            // The strong anchor and the lifetime stats survive on the SAME row.
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash-a")).isEqualTo(row)
            assertThat(db.totalsDao().byRowId(row)!!.totalCount).isEqualTo(4)
            assertThat(db.totalsDao().byRowId(row)!!.totalMillis).isEqualTo(900_000)

            val before = db.aliasDao().all()
            rebuild { listOf(root()) }.rebuild(listOf(meta(file)))
            assertThat(db.aliasDao().all()).containsExactlyInAnyOrderElementsOf(before)
        }

    @Test
    fun hashlessSoleBasenameRowIsUntouched() =
        runBlocking<Unit> {
            val row = insertTrack("uuid-hashless")
            putAlias(AliasKind.REL_PATH, "song.flac", row)
            db.totalsDao().accumulate(row, 4, 900_000, 10, 0, 0, 0, 0)
            val file = fileIn("sub", "song.flac", "hash-a")

            rebuild { listOf(root()) }.rebuild(listOf(meta(file)))

            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "song.flac")).isEqualTo(row)
            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "sub/song.flac")).isNull()
            assertThat(db.totalsDao().byRowId(row)!!.totalCount).isEqualTo(4)
        }

    @Test
    fun scannedFileWithDifferentHashDoesNotUpgrade() =
        runBlocking<Unit> {
            val row = insertTrack("uuid-legacy")
            putAlias(AliasKind.REL_PATH, "song.flac", row)
            putAlias(AliasKind.AUDIO_HASH, "hash-a", row)
            val file = fileIn("sub", "song.flac", "hash-b")

            rebuild { listOf(root()) }.rebuild(listOf(meta(file)))

            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "song.flac")).isEqualTo(row)
            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "sub/song.flac")).isNull()
        }

    @Test
    fun rootedKeyOwnedByAnotherRowIsNotStolen() =
        runBlocking<Unit> {
            val row = insertTrack("uuid-legacy")
            putAlias(AliasKind.REL_PATH, "song.flac", row)
            putAlias(AliasKind.AUDIO_HASH, "hash-a", row)
            val other = insertTrack("uuid-other")
            putAlias(AliasKind.REL_PATH, "sub/song.flac", other)
            val file = fileIn("sub", "song.flac", "hash-a")

            rebuild { listOf(root()) }.rebuild(listOf(meta(file)))

            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "sub/song.flac")).isEqualTo(other)
            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "song.flac")).isEqualTo(row)
        }

    @Test
    fun rootDirectFileIsSkipped() =
        runBlocking<Unit> {
            val row = insertTrack("uuid-legacy")
            putAlias(AliasKind.REL_PATH, "song.flac", row)
            putAlias(AliasKind.AUDIO_HASH, "hash-a", row)
            val file = fileIn(null, "song.flac", "hash-a")

            rebuild { listOf(root()) }.rebuild(listOf(meta(file)))

            // rooted == "song.flac" (no slash): indistinguishable from the legacy key, no gain.
            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "song.flac")).isEqualTo(row)
            assertThat(db.aliasDao().aliasesOf(row).count { it.kind == AliasKind.REL_PATH })
                .isEqualTo(1)
        }

    @Test
    fun unrootedFileIsSkipped() =
        runBlocking<Unit> {
            val row = insertTrack("uuid-legacy")
            putAlias(AliasKind.REL_PATH, "song.flac", row)
            putAlias(AliasKind.AUDIO_HASH, "hash-a", row)
            val file = fileIn("sub", "song.flac", "hash-a")

            // No media root contains the file: RelPathKey.of returns null.
            rebuild { emptyList() }.rebuild(listOf(meta(file)))

            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "song.flac")).isEqualTo(row)
            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "sub/song.flac")).isNull()
        }
}
