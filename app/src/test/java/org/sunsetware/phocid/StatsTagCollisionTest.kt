package org.sunsetware.phocid

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.fingerprint.TagKey
import org.sunsetware.phocid.stats.resolver.TrackMeta
import org.sunsetware.phocid.stats.resolver.TrackResolver

/**
 * Characterization of the G2 TagKey collision surface, written while the G2 decision is pending.
 *
 * These tests pin TODAY'S behavior; they deliberately do not change it. Findings:
 * - The key includes the album, so cross-album same-name/same-length tracks do NOT collide. The
 *   real collision surface is same-album duplicates whose distinguishing mark is stripped by
 *   normalization (remaster/live/feat/year brackets, deluxe-edition album suffix), tracks inside
 *   one 2 s duration bucket, and the neighbor-bucket candidates near a boundary.
 * - TAG_KEY is a weak clue: with both audio hashes present, [TrackResolver] never merges on a tag
 *   collision (ownsConflictingHash guard + insertIgnore). The collision loser gets its own row and
 *   merely misses the tag link; existing stats are untouched.
 * - The only wrong-merge windows need a missing hash (current resolve or the tag-owning row). That
 *   path is also the designed continuity fallback for retags and for formats whose hash changes on
 *   tag edits (OGG/Opus, see AudioFingerprint), so tightening it would split existing users' stats.
 *   A real fix therefore requires a key/schema migration decision (G2), not a resolver tweak.
 *
 * Continuity red line: TAG_KEY values are stored in users' databases. The golden-key tests below
 * fail if any change alters the key of a track that resolves today.
 */
@RunWith(RobolectricTestRunner::class)
class StatsTagCollisionTest {
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
        TrackResolver(db = db, fingerprint = fingerprint, mediaRoots = { emptyList() })

    private fun meta(
        uri: String,
        title: String? = "Song",
        artist: String? = "Artist",
        album: String? = "Album",
        durationMillis: Long = 180_000,
    ) =
        TrackMeta(
            uri = uri,
            file = null,
            size = 1024,
            modified = 1,
            title = title,
            artist = artist,
            album = album,
            durationMillis = durationMillis,
        )

    // ---------------------------------------------------------------------------------------
    // TagKey collision surface (pure key level)
    // ---------------------------------------------------------------------------------------

    @Test
    fun crossAlbumSameNameSameLengthDoesNotCollide() {
        // The review's broad claim does not hold: the album is part of the key.
        assertThat(TagKey.of("Artist", "Album One", "Song", 180_000))
            .isNotEqualTo(TagKey.of("Artist", "Album Two", "Song", 180_000))
    }

    @Test
    fun sameAlbumStrippedMarkDuplicatesCollide() {
        val plain = TagKey.of("Artist", "Album", "Song", 180_000)
        // Real same-album duplicates whose only distinguishing mark is normalized away.
        assertThat(TagKey.of("Artist", "Album", "Song (Remaster)", 180_000)).isEqualTo(plain)
        assertThat(TagKey.of("Artist", "Album", "Song (Live)", 180_000)).isEqualTo(plain)
        assertThat(TagKey.of("Artist", "Album", "Song (2011 Remaster)", 180_000)).isEqualTo(plain)
        assertThat(TagKey.of("Artist", "Album", "Song feat. Someone", 180_000)).isEqualTo(plain)
    }

    @Test
    fun deluxeEditionAlbumSuffixCollidesWithStandardEdition() {
        assertThat(TagKey.of("Artist", "Album (Deluxe Edition)", "Song", 180_000))
            .isEqualTo(TagKey.of("Artist", "Album", "Song", 180_000))
    }

    @Test
    fun distinctTracksInsideOneDurationBucketCollide() {
        // Two different tracks 1.9 s apart share bucket 90.
        assertThat(TagKey.of("Artist", "Album", "Song", 180_000))
            .isEqualTo(TagKey.of("Artist", "Album", "Song", 181_900))
    }

    @Test
    fun neighborCandidatesOverlapAcrossBucketBoundary() {
        // 179.9 s and 180.1 s are different tracks, yet each one's candidate set contains both
        // buckets, so each can tag-match the other's stored key.
        val below = TagKey.candidates("Artist", "Album", "Song", 179_900)
        val above = TagKey.candidates("Artist", "Album", "Song", 180_100)
        assertThat(below).hasSize(2)
        assertThat(above).hasSize(2)
        assertThat(below).containsExactlyInAnyOrderElementsOf(above)
    }

    @Test
    fun storedKeyFormatIsPinnedForContinuity() {
        // Golden values: these exact strings live in users' TAG_KEY aliases. Any algorithm change
        // that alters them splits existing stats and must not ship without a migration decision.
        assertThat(TagKey.of("Artist", "Album", "Song", 180_000))
            .isEqualTo("artist\u0000album\u0000song\u000090")
        assertThat(TagKey.of(" artist ", "ALBUM", "Song (Remastered)", 180_020))
            .isEqualTo("artist\u0000album\u0000song\u000090")
        assertThat(TagKey.candidates("Artist", "Album", "Song", 180_000))
            .containsExactly(
                "artist\u0000album\u0000song\u000090",
                "artist\u0000album\u0000song\u000089",
            )
    }

    // ---------------------------------------------------------------------------------------
    // Resolver behavior on collisions (characterization; no behavior change)
    // ---------------------------------------------------------------------------------------

    @Test
    fun collidingTagsWithDistinctHashesSplitSafelyInsteadOfMerging() =
        runBlocking<Unit> {
            val resolver = resolver { m -> if (m.title == "Song") "hash-a" else "hash-b" }
            val a = resolver.resolve(meta(uri = "content://media/external/audio/media/1"))
            db.totalsDao().accumulate(a, 5, 500, 1, 0, 0, 0, 0)

            // Same album, same length, distinguishing mark stripped -> identical tag key.
            val b =
                resolver.resolve(
                    meta(
                        uri = "content://media/external/audio/media/2",
                        title = "Song (Remaster)",
                    ),
                )

            // No wrong merge: the hash guard gives the collision loser its own row.
            assertThat(b).isNotEqualTo(a)
            assertThat(db.totalsDao().byRowId(a)!!.totalCount).isEqualTo(5)
            assertThat(db.totalsDao().byRowId(b)).isNull()
            // The tag alias keeps its first owner (insertIgnore): the loser merely misses the
            // tag link, which is the safe direction.
            val key = TagKey.of("Artist", "Album", "Song", 180_000)!!
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, key)).isEqualTo(a)
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash-b")).isEqualTo(b)

            // Repeated resolves are stable and never merge the rows later.
            assertThat(
                    resolver.resolve(
                        meta(
                            uri = "content://media/external/audio/media/2",
                            title = "Song (Remaster)",
                        ),
                    ),
                )
                .isEqualTo(b)
            assertThat(db.trackDao().byRowId(a)).isNotNull()
            assertThat(db.totalsDao().byRowId(a)!!.totalCount).isEqualTo(5)
        }

    @Test
    fun nearBoundaryDistinctTracksWithHashesKeepSeparateRows() =
        runBlocking<Unit> {
            val resolver = resolver { m -> if (m.durationMillis < 180_000) "hash-a" else "hash-b" }
            val a =
                resolver.resolve(
                    meta(uri = "content://media/external/audio/media/1", durationMillis = 179_900),
                )
            db.totalsDao().accumulate(a, 3, 300, 1, 0, 0, 0, 0)
            val b =
                resolver.resolve(
                    meta(uri = "content://media/external/audio/media/2", durationMillis = 180_100),
                )

            // The neighbor-bucket overlap is caught by the same hash guard: separate rows, and
            // both boundary keys stay with the first owner.
            assertThat(b).isNotEqualTo(a)
            assertThat(db.totalsDao().byRowId(a)!!.totalCount).isEqualTo(3)
            TagKey.candidates("Artist", "Album", "Song", 180_100).forEach {
                assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, it)).isEqualTo(a)
            }
        }

    @Test
    fun collidingTagsWithoutAnyHashShareOneRow() =
        runBlocking<Unit> {
            // G2 wrong-merge window #1: when no hash is available, a colliding tag key is the
            // only clue and both tracks accumulate into one row. This is ALSO the designed
            // continuity path for retagged files whose hash changed (e.g. OGG/Opus tag edits,
            // pinned by StatsResolverTest.tagKeyMatchesWhenHashIsUnavailable), so it cannot be
            // tightened without splitting existing users' stats. Characterization only.
            val resolver = resolver { null }
            val a = resolver.resolve(meta(uri = "content://media/external/audio/media/1"))
            db.totalsDao().accumulate(a, 2, 200, 1, 0, 0, 0, 0)
            val b =
                resolver.resolve(
                    meta(uri = "content://media/external/audio/media/2", title = "Song (Live)"),
                )
            db.totalsDao().accumulate(b, 1, 100, 2, 0, 0, 0, 0)

            assertThat(b).isEqualTo(a)
            assertThat(db.totalsDao().byRowId(a)!!.totalCount).isEqualTo(3)
        }

    @Test
    fun hashlessTagRowAdoptsLaterHashEvenOnCollision() =
        runBlocking<Unit> {
            // G2 wrong-merge window #2: a row created while fingerprinting failed owns no hash,
            // so a later colliding track with a hash is adopted into it. Indistinguishable from
            // the intended "tag row gains its hash later" link without new stored metadata.
            // Characterization only.
            var hash: String? = null
            val resolver = resolver { hash }
            val a = resolver.resolve(meta(uri = "content://media/external/audio/media/1"))
            assertThat(db.aliasDao().aliasesOf(a)).noneMatch { it.kind == AliasKind.AUDIO_HASH }

            hash = "hash-b"
            val b =
                resolver.resolve(
                    meta(
                        uri = "content://media/external/audio/media/2",
                        title = "Song (Remaster)",
                    ),
                )

            assertThat(b).isEqualTo(a)
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash-b")).isEqualTo(a)
        }

    @Test
    fun unambiguousWeakTagLinkAndStoredAliasValueAreUnchanged() =
        runBlocking<Unit> {
            // Continuity guard: the unambiguous retag fallback still resolves to the SAME row,
            // and the stored TAG_KEY alias value is byte-for-byte the pinned golden string.
            val resolver = resolver { m -> if (m.uri.endsWith("/1")) "hash-a" else null }
            val a = resolver.resolve(meta(uri = "content://media/external/audio/media/1"))
            val key = TagKey.of("Artist", "Album", "Song", 180_000)!!
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, key)).isEqualTo(a)

            val retagged =
                resolver.resolve(
                    meta(
                        uri = "content://media/external/audio/media/2",
                        title = "Song (Remastered)",
                        durationMillis = 180_020,
                    ),
                )

            assertThat(retagged).isEqualTo(a)
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, key)).isEqualTo(a)
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash-a")).isEqualTo(a)
            assertThat(db.aliasDao().aliasesOf(a).filter { it.kind == AliasKind.TAG_KEY })
                .allMatch { it.value == key || it.value == "artist\u0000album\u0000song\u000089" }
        }

    @Test
    fun ambiguousTwoRowTagConflictStillUsesDedicatedRow() =
        runBlocking<Unit> {
            // Both boundary keys owned by different rows -> the existing AMBIGUOUS_TAG mechanism
            // keeps all three rows apart and reuses the dedicated row on repeats.
            val keys = TagKey.candidates("Artist", "Album", "Song", 180_000)
            assertThat(keys).hasSize(2)
            val resolver = resolver { m -> if (m.durationMillis < 180_000) "hash-a" else "hash-b" }
            val a =
                resolver.resolve(
                    meta(uri = "content://media/external/audio/media/1", durationMillis = 178_000),
                )
            val b =
                resolver.resolve(
                    meta(uri = "content://media/external/audio/media/2", durationMillis = 181_000),
                )
            // Re-key the aliases so each boundary key has a distinct owner, as it happens when
            // the two tracks were first resolved far from the boundary.
            db.aliasDao().put(TrackAliasEntity(AliasKind.TAG_KEY, keys[0], a))
            db.aliasDao().put(TrackAliasEntity(AliasKind.TAG_KEY, keys[1], b))
            db.totalsDao().accumulate(a, 1, 100, 1, 0, 0, 0, 0)
            db.totalsDao().accumulate(b, 2, 200, 2, 0, 0, 0, 0)

            val hashless = resolver { null }
            val first = hashless.resolve(meta(uri = "content://media/external/audio/media/3"))
            val second = hashless.resolve(meta(uri = "content://media/external/audio/media/3"))

            assertThat(first).isNotEqualTo(a).isNotEqualTo(b)
            assertThat(second).isEqualTo(first)
            assertThat(db.totalsDao().byRowId(a)!!.totalCount).isEqualTo(1)
            assertThat(db.totalsDao().byRowId(b)!!.totalCount).isEqualTo(2)
        }
}
