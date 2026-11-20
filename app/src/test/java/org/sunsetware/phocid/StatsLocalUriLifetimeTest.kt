package org.sunsetware.phocid

import androidx.room.Room
import java.io.File
import java.util.Random
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
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
import org.sunsetware.phocid.data.InvalidTrack
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.domain.StatsRepository
import org.sunsetware.phocid.stats.fingerprint.AudioFingerprint
import org.sunsetware.phocid.stats.fingerprint.LocalUriKey
import org.sunsetware.phocid.stats.fingerprint.TagKey
import org.sunsetware.phocid.stats.resolver.TrackMeta
import org.sunsetware.phocid.stats.resolver.TrackResolver

/**
 * G3: a LOCAL_URI hint is (MediaStore id, size). MediaStore reuses row ids, so after a file is
 * deleted and a different file of the same byte size is scanned onto the same id, the stale hint
 * still points at the old row (TrackResolver deliberately never steals it). lifetimeForTrack must
 * not display the old track's totals for the new file.
 *
 * CONTINUITY: the hint stays the first lookup and is distrusted only on positive proof of different
 * audio (contradicting tags AND a freshly computed audio hash the row does not own). Rows whose
 * only alias is LOCAL_URI, retagged/renamed copies of the same audio, and unprovable cases must
 * keep returning exactly the same row as before. No alias is rewritten or invalidated by the
 * lookup.
 */
@RunWith(RobolectricTestRunner::class)
class StatsLocalUriLifetimeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var db: StatsDatabase
    private lateinit var repository: StatsRepository

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    StatsDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        repository = StatsRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun resolver(fingerprint: (TrackMeta) -> String? = ::realFingerprint) =
        TrackResolver(db = db, fingerprint = fingerprint, mediaRoots = { emptyList() })

    private fun realFingerprint(meta: TrackMeta): String? =
        meta.file?.let { AudioFingerprint.compute(it) }

    private fun meta(
        file: File?,
        uri: String = REUSED_URI,
        title: String? = "Song",
        artist: String? = "Artist",
        album: String? = "Album",
    ) =
        TrackMeta(
            uri = uri,
            file = file,
            size = file?.length() ?: 4096,
            modified = 1,
            title = title,
            artist = artist,
            album = album,
            durationMillis = 180_000,
        )

    /** Distinct pseudo-random audio bytes; [length] is kept equal to model the same-size reuse. */
    private fun audioFile(name: String, seed: Long, length: Int = 4096): File {
        val bytes = ByteArray(length)
        Random(seed).nextBytes(bytes)
        return temporaryFolder.newFile(name).apply { writeBytes(bytes) }
    }

    /**
     * ID3v2 header + 256-byte tag body + the same audio payload. Changing only the tag body models
     * an in-place retag (or a renamed retagged copy): same size, same audio hash, different tags.
     */
    private fun id3File(name: String, payload: ByteArray, tagFill: Byte): File {
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0)
        // Syncsafe size of the 256-byte tag body: 0,0,2,0.
        val syncsafe = byteArrayOf(0, 0, 2, 0)
        val body = ByteArray(256) { tagFill }
        return temporaryFolder.newFile(name).apply {
            writeBytes(header + syncsafe + body + payload)
        }
    }

    private fun track(
        file: File?,
        title: String?,
        artist: String?,
        album: String?,
        id: Long = 7,
        size: Long = file?.length() ?: 4096,
        path: String = file?.absolutePath.orEmpty(),
    ): Track =
        InvalidTrack.copy(
            id = id,
            path = path,
            fileName = file?.name ?: "song.mp3",
            title = title,
            artists = listOfNotNull(artist),
            album = album,
            duration = 180.seconds,
            size = size,
        )

    private suspend fun totals(rowId: Long, count: Int) {
        db.totalsDao().accumulate(rowId, count, count * 1000L, 10, 0, 0, 0, 0)
    }

    private suspend fun localUriOwner(size: Long): Long? =
        db.aliasDao().resolve(AliasKind.LOCAL_URI, LocalUriKey.of(REUSED_URI, size))

    @Test
    fun staleLocalUriHintDoesNotShowAnotherTracksTotals() =
        runBlocking<Unit> {
            val oldFile = audioFile("old.mp3", seed = 1)
            val oldRow =
                resolver()
                    .resolve(
                        meta(
                            oldFile,
                            title = "Old Song",
                            artist = "Old Artist",
                            album = "Old Album",
                        ),
                    )
            totals(oldRow, 5)
            val oldUuid = db.trackDao().byRowId(oldRow)!!.uuid

            // A different file of the same byte size lands on the reused MediaStore id. It has
            // not been played (resolved) yet, so no row or alias exists for it.
            val newFile = audioFile("new.mp3", seed = 2)
            assertThat(newFile.length()).isEqualTo(oldFile.length())
            val newTrack = track(newFile, "New Song", "New Artist", "New Album")

            assertThat(repository.lifetimeForTrack(newTrack)).isNull()

            // The old track's data and aliases are untouched: no invalidation, same UUID.
            assertThat(db.trackDao().byRowId(oldRow)!!.uuid).isEqualTo(oldUuid)
            assertThat(db.totalsDao().byRowId(oldRow)!!.totalCount).isEqualTo(5)
            assertThat(localUriOwner(oldFile.length())).isEqualTo(oldRow)
            assertThat(
                    db.aliasDao().resolve(AliasKind.AUDIO_HASH, realFingerprint(meta(oldFile))!!),
                )
                .isEqualTo(oldRow)
            // And the old track itself still resolves to its own totals through the same hint.
            val oldTrack = track(oldFile, "Old Song", "Old Artist", "Old Album")
            assertThat(repository.lifetimeForTrack(oldTrack)!!.trackRowId).isEqualTo(oldRow)
        }

    @Test
    fun staleLocalUriHintFallsThroughToTheReplacementRow() =
        runBlocking<Unit> {
            val oldFile = audioFile("old.mp3", seed = 1)
            val oldRow =
                resolver()
                    .resolve(
                        meta(
                            oldFile,
                            title = "Old Song",
                            artist = "Old Artist",
                            album = "Old Album",
                        ),
                    )
            totals(oldRow, 5)

            val newFile = audioFile("new.mp3", seed = 2)
            val newRow =
                resolver()
                    .resolve(
                        meta(
                            newFile,
                            title = "New Song",
                            artist = "New Artist",
                            album = "New Album",
                        ),
                    )
            totals(newRow, 2)
            assertThat(newRow).isNotEqualTo(oldRow)
            // TrackResolver keeps the stale hint on the old row; the lookup must not trust it.
            assertThat(localUriOwner(newFile.length())).isEqualTo(oldRow)

            val newTrack = track(newFile, "New Song", "New Artist", "New Album")
            assertThat(repository.lifetimeForTrack(newTrack)!!.trackRowId).isEqualTo(newRow)
        }

    @Test
    fun untaggedFingerprintedRowRejectsProvenDifferentAudio() =
        runBlocking<Unit> {
            // The old file had no tags at all: its row owns AUDIO_HASH + LOCAL_URI only.
            val oldFile = audioFile("old.mp3", seed = 1)
            val oldRow =
                resolver().resolve(meta(oldFile, title = null, artist = null, album = null))
            totals(oldRow, 3)

            val newFile = audioFile("new.mp3", seed = 2)
            val newTrack = track(newFile, "New Song", "New Artist", "New Album")

            assertThat(repository.lifetimeForTrack(newTrack)).isNull()
            assertThat(db.totalsDao().byRowId(oldRow)!!.totalCount).isEqualTo(3)
        }

    @Test
    fun localUriOnlyRowKeepsItsResolution() =
        runBlocking<Unit> {
            // Continuity: a row whose ONLY alias is LOCAL_URI (never fingerprinted, never tagged)
            // must keep resolving through the hint exactly as before.
            val row = resolver {
                null
            }
                .resolve(meta(null, title = null, artist = null, album = null))
            totals(row, 4)
            assertThat(db.aliasDao().aliasesOf(row).map { it.kind })
                .containsExactly(AliasKind.LOCAL_URI)

            val hintOnlyTrack = track(null, title = null, artist = null, album = null)
            assertThat(repository.lifetimeForTrack(hintOnlyTrack)!!.trackRowId).isEqualTo(row)
        }

    @Test
    fun matchingTagsKeepTheHintWithoutReadingTheFile() =
        runBlocking<Unit> {
            val file = audioFile("song.mp3", seed = 1)
            val row = resolver().resolve(meta(file))
            totals(row, 2)

            // Same tags: the hint is trusted immediately, even when the path is unreadable. No
            // file IO happens on the normal lookup path.
            val ghost = track(file, "Song", "Artist", "Album", path = "/nonexistent/ghost.mp3")
            assertThat(repository.lifetimeForTrack(ghost)!!.trackRowId).isEqualTo(row)
        }

    @Test
    fun retaggedRenamedSameAudioKeepsTheHint() =
        runBlocking<Unit> {
            // Continuity: an in-place retag plus rename keeps the MediaStore id and size. The
            // stored tags now contradict the track, but the audio payload is byte-identical, so
            // the hash proof keeps the historical resolution.
            val payload = ByteArray(4096).also { Random(3).nextBytes(it) }
            val oldFile = id3File("old.mp3", payload, tagFill = 'A'.code.toByte())
            val newFile = id3File("renamed.mp3", payload, tagFill = 'B'.code.toByte())
            assertThat(newFile.length()).isEqualTo(oldFile.length())
            assertThat(AudioFingerprint.compute(newFile))
                .isEqualTo(AudioFingerprint.compute(oldFile))

            val row =
                resolver()
                    .resolve(
                        meta(
                            oldFile,
                            title = "Old Song",
                            artist = "Old Artist",
                            album = "Old Album",
                        ),
                    )
            totals(row, 7)

            val retagged = track(newFile, "New Song", "New Artist", "New Album")
            assertThat(repository.lifetimeForTrack(retagged)!!.trackRowId).isEqualTo(row)
        }

    @Test
    fun neverFingerprintedRowKeepsTheHintDespiteTagDrift() =
        runBlocking<Unit> {
            // Characterization of the residual window: without an AUDIO_HASH alias on the row
            // there is no proof, so continuity wins and the hint is kept even though the tags
            // drifted and the current file hashes differently.
            val oldFile = audioFile("old.mp3", seed = 1)
            val row = resolver {
                null
            }
                .resolve(
                    meta(oldFile, title = "Old Song", artist = "Old Artist", album = "Old Album"),
                )
            totals(row, 6)
            assertThat(db.aliasDao().aliasesOf(row).map { it.kind })
                .doesNotContain(AliasKind.AUDIO_HASH)

            val newFile = audioFile("new.mp3", seed = 2)
            val drifted = track(newFile, "New Song", "New Artist", "New Album")
            assertThat(repository.lifetimeForTrack(drifted)!!.trackRowId).isEqualTo(row)
        }

    @Test
    fun unreadableFileKeepsTheHint() =
        runBlocking<Unit> {
            // Continuity: tags contradict, but the current file cannot be hashed (SAF-only or
            // missing path). Without proof the historical resolution is preserved.
            val oldFile = audioFile("old.mp3", seed = 1)
            val row =
                resolver()
                    .resolve(
                        meta(
                            oldFile,
                            title = "Old Song",
                            artist = "Old Artist",
                            album = "Old Album",
                        ),
                    )
            totals(row, 1)

            val safOnly = track(null, "New Song", "New Artist", "New Album", path = "")
            assertThat(repository.lifetimeForTrack(safOnly)!!.trackRowId).isEqualTo(row)
        }

    @Test
    fun tagFallbackStillResolvesWithoutLocalUri() =
        runBlocking<Unit> {
            // No regression: with no LOCAL_URI hint the TAG_KEY fallback still links the track.
            val row =
                db.trackDao()
                    .insert(
                        TrackEntity(
                            uuid = UUID.randomUUID().toString(),
                            title = "Song",
                            artist = "Artist",
                            album = "Album",
                            durationMillis = 180_000,
                            createdAt = 0,
                        ),
                    )
            val key = TagKey.candidates("Artist", "Album", "Song", 180_000).first()
            db.aliasDao().put(TrackAliasEntity(AliasKind.TAG_KEY, key, row))
            totals(row, 2)

            val otherDevice = track(null, "Song", "Artist", "Album", id = 99)
            assertThat(repository.lifetimeForTrack(otherDevice)!!.trackRowId).isEqualTo(row)
        }

    private companion object {
        const val REUSED_URI = "content://media/external/audio/media/7"
    }
}
