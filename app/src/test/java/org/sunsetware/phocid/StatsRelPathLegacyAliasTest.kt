package org.sunsetware.phocid

import androidx.room.Room
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.stats.backup.BackupAlias
import org.sunsetware.phocid.stats.backup.BackupDaily
import org.sunsetware.phocid.stats.backup.BackupTotals
import org.sunsetware.phocid.stats.backup.BackupTrack
import org.sunsetware.phocid.stats.backup.StatsBackup
import org.sunsetware.phocid.stats.backup.StatsBackupManager
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.fingerprint.RelPathKey
import org.sunsetware.phocid.stats.resolver.TrackMeta
import org.sunsetware.phocid.stats.resolver.TrackResolver

/**
 * Characterization of the G4 legacy basename REL_PATH surface, written while the G4 decision is
 * pending. These tests pin TODAY'S behavior; they deliberately do not change it.
 *
 * History: before commit 55d9139, [RelPathKey.of] fell back to `path.substringAfterLast('/')` for
 * paths outside every media root, so an unrooted /downloads/Song.flac stored the portable alias
 * REL_PATH="song.flac". That fix stopped NEW such aliases, but stored ones live on in users'
 * databases and in already-exported backups.
 *
 * Findings (each pinned by a test below):
 * 1. A basename-only value is NOT distinguishable from a legitimate key: a file directly under a
 *    media root produces the exact same basename-only shape, and even slash-containing keys collide
 *    across different roots by design (cross-device continuity). No provenance flag was ever
 *    stored, so shape-based classification has false positives in both directions.
 * 2. Provenance is unrecoverable from stored data: tracks.path is a mutable local display hint,
 *    deliberately excluded from backups and blank after a restore; the (kind, value) primary key
 *    means a local database can only ever hold ONE side of a collision (the loser's insert was
 *    IGNORE'd), so the two-sided evidence exists only inside a backup payload.
 * 3. Restore mis-merge mechanism: groupBackupTracks union-finds backup UUIDs that share any
 *    portable alias (AUDIO_HASH, TAG_KEY, REL_PATH). One group becomes one row; the first track
 *    supplies metadata and the other UUIDs vanish. writeTotals/writeDaily then combine the group
 *    with per-field MAX, not SUM: the quieter track's counts are silently DROPPED. A merge restore
 *    can also fail hard ("share a portable alias but match different local rows"), blocking the
 *    whole import.
 * 4. Conflicting AUDIO_HASH values are NOT proof of distinct tracks: OGG/Opus tag edits change the
 *    hash by design (see AudioFingerprint), and REL_PATH is the designed continuity fallback for
 *    exactly that case. A "delete the alias when hashes conflict" rule would therefore split
 *    legitimate retag/format-change merges — the continuity red line.
 * 5. A basename-shape cleanup splits load-bearing aliases: the alias can be a row's ONLY identity
 *    (untagged file, fingerprint unavailable). Deleting it strands the lifetime totals on the old
 *    row while new plays accumulate on a fresh row (simulated below, in-test only).
 *
 * MIGRATION PROPOSAL (G4 — requires an explicit decision; do NOT implement silently): No provably
 * safe one-time cleanup can be derived from stored data alone, because findings 1, 2 and 4 remove
 * every candidate discriminator (value shape, stored path, conflicting hashes). A safe rebuild
 * would instead require:
 * 1. Re-resolution from full paths at the next library scan: only when a scanned file's AUDIO_HASH
 *    or rooted REL_PATH positively matches the row may a basename-only alias be rewritten to the
 *    rooted key (insert the new alias first, retire the old one in the same transaction). An alias
 *    that is the row's only portable identity must never be deleted before a stronger one exists.
 * 2. A provenance marker for future writes (a new alias kind or a DB-version gate) so post-fix keys
 *    are classifiable; existing rows remain unclassifiable without step 1.
 * 3. Restore-side containment for old backups, which are immutable artifacts no DB migration can
 *    reach: e.g. user confirmation for a group whose UUIDs carry conflicting AUDIO_HASH evidence,
 *    or demoting basename-only REL_PATH to non-grouping evidence. Both change restore outcomes for
 *    existing backups and touch the designed OGG/Opus retag fallback, so they need a product
 *    decision, not a migration.
 * 4. Any migration must keep the Room chain continuous (no destructive fallback) and must never
 *    remove an alias based on basename shape alone. Until that decision is made, this file pins the
 *    behavior so any future change is deliberate.
 */
@RunWith(RobolectricTestRunner::class)
class StatsRelPathLegacyAliasTest {
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

    private fun resolver(
        mediaRoots: () -> List<String> = { emptyList() },
        fingerprint: (TrackMeta) -> String?,
    ) = TrackResolver(db = db, fingerprint = fingerprint, mediaRoots = mediaRoots)

    private fun untaggedMeta(uri: String, file: File?) =
        TrackMeta(
            uri = uri,
            file = file,
            size = 1024,
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
                )
            )

    private suspend fun putAlias(kind: Int, value: String, rowId: Long) {
        db.aliasDao().put(TrackAliasEntity(kind, value, rowId))
    }

    private fun track(uuid: String, title: String) =
        BackupTrack(uuid, title, "Artist", "Album", 180_000, 1)

    private fun backup(
        tracks: List<BackupTrack>,
        aliases: List<BackupAlias> = emptyList(),
        totals: List<BackupTotals> = emptyList(),
        daily: List<BackupDaily> = emptyList(),
    ) =
        StatsBackup(
            exportedAt = 0,
            zoneId = "UTC",
            tracks = tracks,
            aliases = aliases,
            totals = totals,
            daily = daily,
        )

    private fun countRows(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    // -----------------------------------------------------------------------------------------
    // Key level: legacy basename values are indistinguishable from legitimate keys
    // -----------------------------------------------------------------------------------------

    @Test
    fun legacyBasenameAndRootDirectKeysHaveIdenticalShape() {
        // The pre-55d9139 fallback for an unrooted path was the lowercased file name.
        val legacyValue = "/downloads/Song.flac".substringAfterLast('/').lowercase()
        // A legitimate file DIRECTLY under a media root produces the exact same value today.
        val legitimateValue =
            RelPathKey.of(
                "/storage/emulated/0/Music/Song.flac",
                listOf("/storage/emulated/0/Music"),
            )
        assertThat(legacyValue).isEqualTo("song.flac")
        assertThat(legitimateValue).isEqualTo(legacyValue)
    }

    @Test
    fun slashContainingKeysStillCollideAcrossDifferentRoots() {
        // "Contains a slash" is not a safety signal either: identical relative paths under two
        // different roots share one key by design (cross-root/cross-device continuity), so a
        // slash-based whitelist cannot separate collisions from legitimate links.
        val internal =
            RelPathKey.of(
                "/storage/emulated/0/Music/x/song.flac",
                listOf("/storage/emulated/0/Music"),
            )
        val sdCard =
            RelPathKey.of(
                "/storage/ABCD-1234/Music/x/song.flac",
                listOf("/storage/ABCD-1234/Music"),
            )
        assertThat(internal).isEqualTo("x/song.flac").isEqualTo(sdCard)
    }

    // -----------------------------------------------------------------------------------------
    // Restore level: the exact mis-merge mechanism and its data loss
    // -----------------------------------------------------------------------------------------

    @Test
    fun replaceRestoreCollapsesTwoTracksSharingALegacyBasenameAlias() =
        runBlocking<Unit> {
            // Two different-directory same-name tracks exported with the legacy basename alias.
            val payload =
                backup(
                    tracks = listOf(track("uuid-a", "A"), track("uuid-b", "B")),
                    aliases =
                        listOf(
                            BackupAlias(AliasKind.REL_PATH, "song.flac", "uuid-a"),
                            BackupAlias(AliasKind.REL_PATH, "song.flac", "uuid-b"),
                        ),
                    totals =
                        listOf(
                            BackupTotals("uuid-a", 5, 500_000, 0, 5),
                            BackupTotals("uuid-b", 3, 900_000, 1, 7),
                        ),
                    daily =
                        listOf(
                            BackupDaily("uuid-a", 20_000, 2, 200_000, 4),
                            BackupDaily("uuid-b", 20_000, 3, 500_000, 6),
                        ),
                )

            StatsBackupManager(db).restore(payload, replace = true)

            // groupBackupTracks unions the two UUIDs; only the first track's identity survives.
            assertThat(countRows("tracks")).isEqualTo(1)
            assertThat(db.trackDao().rowIdOfUuid("uuid-b")).isNull()
            val row = db.trackDao().rowIdOfUuid("uuid-a")!!
            assertThat(db.trackDao().byRowId(row)!!.title).isEqualTo("A")
            // writeTotals combines with per-field MAX, not SUM: the true lifetime is 8 plays /
            // 1_400_000 ms, but the merged row shows 5 / 900_000 — 3 plays and 500_000 ms are
            // silently lost, and the result matches NEITHER source track.
            val totals = db.totalsDao().byRowId(row)!!
            assertThat(totals.totalCount).isEqualTo(5)
            assertThat(totals.totalMillis).isEqualTo(900_000)
            assertThat(totals.firstPlayedAt).isEqualTo(0)
            assertThat(totals.lastPlayedAt).isEqualTo(7)
            // Same per-day MAX collapse: the true day is 5 plays / 700_000 ms, stored is 3 /
            // 500_000.
            val daily = db.dailyDao().since(20_000).single()
            assertThat(daily.playCount).isEqualTo(3)
            assertThat(daily.playedMillis).isEqualTo(500_000)
            assertThat(daily.lastPlayedAt).isEqualTo(6)
        }

    @Test
    fun mergeRestoreLandsOnTheWrongLocalTrackViaALegacyBasenameAlias() =
        runBlocking<Unit> {
            // The local row is /downloads/a/song.flac (legacy unrooted alias); the backup row is
            // /downloads/b/song.flac, a different track that happens to share the file name.
            val local = insertTrack("uuid-local", "Local")
            putAlias(AliasKind.REL_PATH, "song.flac", local)
            db.totalsDao().accumulate(local, 2, 360_000, 2, 0, 0, 0, 0)
            val payload =
                backup(
                    tracks = listOf(track("uuid-remote", "Remote")),
                    aliases = listOf(BackupAlias(AliasKind.REL_PATH, "song.flac", "uuid-remote")),
                    totals = listOf(BackupTotals("uuid-remote", 5, 900_000, 0, 9)),
                )

            StatsBackupManager(db).restore(payload, replace = false)

            assertThat(countRows("tracks")).isEqualTo(1)
            assertThat(db.trackDao().rowIdOfUuid("uuid-remote")).isNull()
            val totals = db.totalsDao().byRowId(local)!!
            assertThat(totals.totalCount).isEqualTo(5)
            assertThat(totals.totalMillis).isEqualTo(900_000)
        }

    @Test
    fun legacyAliasGroupMatchingTwoLocalRowsFailsTheWholeRestore() =
        runBlocking<Unit> {
            // Both local rows already own their strong identities. The backup's shared legacy
            // alias groups two UUIDs that map to different local rows: restore refuses, so one
            // colliding basename can block importing an entire otherwise-valid backup.
            val rowA = insertTrack("uuid-a", "A")
            val rowB = insertTrack("uuid-b", "B")
            putAlias(AliasKind.AUDIO_HASH, "hash-a", rowA)
            putAlias(AliasKind.AUDIO_HASH, "hash-b", rowB)
            db.totalsDao().accumulate(rowA, 2, 360_000, 2, 0, 0, 0, 0)
            val payload =
                backup(
                    tracks = listOf(track("uuid-a", "A"), track("uuid-b", "B")),
                    aliases =
                        listOf(
                            BackupAlias(AliasKind.AUDIO_HASH, "hash-a", "uuid-a"),
                            BackupAlias(AliasKind.AUDIO_HASH, "hash-b", "uuid-b"),
                            BackupAlias(AliasKind.REL_PATH, "song.flac", "uuid-a"),
                            BackupAlias(AliasKind.REL_PATH, "song.flac", "uuid-b"),
                        ),
                    totals = listOf(BackupTotals("uuid-a", 9, 900_000, 0, 9)),
                )

            assertThatThrownBy {
                    runBlocking { StatsBackupManager(db).restore(payload, replace = false) }
                }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("share a portable alias but match different local rows")

            assertThat(countRows("tracks")).isEqualTo(2)
            assertThat(db.totalsDao().byRowId(rowA)!!.totalCount).isEqualTo(2)
            assertThat(db.totalsDao().byRowId(rowB)).isNull()
        }

    @Test
    fun conflictingHashesSharingARelPathAliasStillMerge() =
        runBlocking<Unit> {
            // This payload shape is BOTH the cross-directory collision AND the designed OGG/Opus
            // retag continuity path (AudioFingerprint: tag edits change the hash; RelPathKey is
            // the fallback). The stored data cannot tell them apart, so "delete the alias when
            // the hashes conflict" is not provably safe: it would split this legitimate merge.
            val payload =
                backup(
                    tracks = listOf(track("uuid-old", "Old"), track("uuid-new", "New")),
                    aliases =
                        listOf(
                            BackupAlias(AliasKind.AUDIO_HASH, "hash-old", "uuid-old"),
                            BackupAlias(AliasKind.AUDIO_HASH, "hash-new", "uuid-new"),
                            BackupAlias(AliasKind.REL_PATH, "song.flac", "uuid-old"),
                            BackupAlias(AliasKind.REL_PATH, "song.flac", "uuid-new"),
                        ),
                    totals =
                        listOf(
                            BackupTotals("uuid-old", 2, 200_000, 0, 2),
                            BackupTotals("uuid-new", 3, 300_000, 1, 3),
                        ),
                )

            StatsBackupManager(db).restore(payload, replace = true)

            assertThat(countRows("tracks")).isEqualTo(1)
            val row = db.trackDao().rowIdOfUuid("uuid-old")!!
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash-old")).isEqualTo(row)
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash-new")).isEqualTo(row)
            assertThat(db.totalsDao().byRowId(row)!!.totalCount).isEqualTo(3)
        }

    @Test
    fun restoredTracksCarryNoPathSoAliasProvenanceIsUnrecoverable() =
        runBlocking<Unit> {
            // BackupTrack deliberately has no path field, and a restored row's path is blank.
            // The full path (and therefore the media root) behind a stored REL_PATH value cannot
            // be recovered from a backup, so no restore-side rebuild can re-derive the key.
            val payload =
                backup(
                    tracks = listOf(track("uuid-x", "X")),
                    aliases = listOf(BackupAlias(AliasKind.REL_PATH, "song.flac", "uuid-x")),
                    totals = listOf(BackupTotals("uuid-x", 1, 60_000, 0, 1)),
                )

            StatsBackupManager(db).restore(payload, replace = true)

            val row = db.trackDao().rowIdOfUuid("uuid-x")!!
            assertThat(db.trackDao().byRowId(row)!!.path).isEmpty()
        }

    @Test
    fun exportStillPropagatesStoredLegacyBasenameAliases() =
        runBlocking<Unit> {
            // Until a cleanup decision is made, every new export keeps carrying the legacy
            // alias, so the collision surface persists in freshly written backups too.
            val row = insertTrack("uuid-legacy")
            putAlias(AliasKind.REL_PATH, "song.flac", row)
            db.totalsDao().accumulate(row, 1, 60_000, 1, 0, 0, 0, 0)

            val out = ByteArrayOutputStream()
            StatsBackupManager(db).export(out)
            val payload = StatsBackupManager(db).read(ByteArrayInputStream(out.toByteArray()))

            assertThat(payload.aliases)
                .contains(BackupAlias(AliasKind.REL_PATH, "song.flac", "uuid-legacy"))
        }

    // -----------------------------------------------------------------------------------------
    // Resolver level: stored legacy aliases are still active local clues
    // -----------------------------------------------------------------------------------------

    @Test
    fun rootedFileStillLinksToALegacyUnrootedRowLocally() =
        runBlocking<Unit> {
            // The legacy row was created for unrooted /downloads/song.flac (basename alias, no
            // hash, no tags). A legitimate root-direct song.flac with no hash/tags resolves to
            // its relative key "song.flac" and is credited to the WRONG legacy row.
            val legacy = insertTrack("uuid-legacy", "Legacy")
            putAlias(AliasKind.REL_PATH, "song.flac", legacy)
            db.totalsDao().accumulate(legacy, 3, 700, 10, 0, 0, 0, 0)
            val file = temporaryFolder.newFile("song.flac")
            val resolver =
                resolver(mediaRoots = { listOf(temporaryFolder.root.absolutePath) }) { null }

            val resolved =
                resolver.resolve(untaggedMeta("content://media/external/audio/media/51", file))

            assertThat(resolved).isEqualTo(legacy)
            assertThat(db.totalsDao().byRowId(legacy)!!.totalCount).isEqualTo(3)
        }

    @Test
    fun currentHashIsAttachedToTheHashlessLegacyRow() =
        runBlocking<Unit> {
            // ownsConflictingHash only protects when the legacy row ALREADY owns a hash. A
            // hashless legacy row absorbs the new file's strong identity, so hash-conflict
            // evidence only exists after the wrong link has already been written.
            val legacy = insertTrack("uuid-legacy", "Legacy")
            putAlias(AliasKind.REL_PATH, "song.flac", legacy)
            val file = temporaryFolder.newFile("song.flac")
            val resolver =
                resolver(mediaRoots = { listOf(temporaryFolder.root.absolutePath) }) { "hash-new" }

            val resolved =
                resolver.resolve(untaggedMeta("content://media/external/audio/media/52", file))

            assertThat(resolved).isEqualTo(legacy)
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash-new")).isEqualTo(legacy)
        }

    @Test
    fun deletingABasenameShapedAliasSplitsLoadBearingStats() =
        runBlocking<Unit> {
            // Simulates the blind "delete all basename-shaped REL_PATH aliases" migration
            // IN-TEST ONLY. The alias is this row's sole identity (untagged, no fingerprint);
            // it is also indistinguishable from a legacy collision alias. Deleting it splits
            // the track: lifetime totals stay stranded on the old row while new plays start a
            // fresh row. This is the continuity red line a shape-based cleanup would cross.
            val row = insertTrack("uuid-only", "Only")
            putAlias(AliasKind.REL_PATH, "song.flac", row)
            db.totalsDao().accumulate(row, 4, 900, 10, 0, 0, 0, 0)
            val file = temporaryFolder.newFile("song.flac")
            val resolver =
                resolver(mediaRoots = { listOf(temporaryFolder.root.absolutePath) }) { null }
            val meta = untaggedMeta("content://media/external/audio/media/53", file)
            assertThat(resolver.resolve(meta)).isEqualTo(row)

            db.aliasDao().deleteKind(row, AliasKind.REL_PATH)
            val afterCleanup = resolver.resolve(meta)

            assertThat(afterCleanup).isNotEqualTo(row)
            assertThat(db.totalsDao().byRowId(row)!!.totalCount).isEqualTo(4)
            assertThat(db.totalsDao().byRowId(afterCleanup)).isNull()
        }

    // -----------------------------------------------------------------------------------------
    // Local storage: only one side of a collision can ever be present
    // -----------------------------------------------------------------------------------------

    @Test
    fun localDatabaseCanOnlyHoldOneSideOfACollision() =
        runBlocking<Unit> {
            // (kind, value) is the primary key and aliases are written with IGNORE, so the
            // second directory's claim on "song.flac" is silently dropped. The local database
            // therefore never contains the two-sided evidence a cleanup would need; that
            // evidence exists only inside a backup payload.
            val rowA = insertTrack(UUID.randomUUID().toString(), "A")
            val rowB = insertTrack(UUID.randomUUID().toString(), "B")
            db.aliasDao().insertIgnore(TrackAliasEntity(AliasKind.REL_PATH, "song.flac", rowA))
            db.aliasDao().insertIgnore(TrackAliasEntity(AliasKind.REL_PATH, "song.flac", rowB))

            assertThat(db.aliasDao().resolve(AliasKind.REL_PATH, "song.flac")).isEqualTo(rowA)
            assertThat(db.aliasDao().aliasesOf(rowB)).noneMatch { it.kind == AliasKind.REL_PATH }
        }
}
