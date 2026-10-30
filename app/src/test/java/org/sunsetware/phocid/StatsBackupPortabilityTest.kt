package org.sunsetware.phocid

import androidx.room.Room
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.stats.backup.BackupAlias
import org.sunsetware.phocid.stats.backup.BackupDaily
import org.sunsetware.phocid.stats.backup.BackupTotals
import org.sunsetware.phocid.stats.backup.BackupTrack
import org.sunsetware.phocid.stats.backup.StatsBackup
import org.sunsetware.phocid.stats.backup.StatsBackupManager
import org.sunsetware.phocid.stats.db.ActiveDayEntity
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.db.TrackTotalsEntity

@RunWith(RobolectricTestRunner::class)
class StatsBackupPortabilityTest {
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

    private suspend fun seed(db: StatsDatabase): Long {
        val row =
            db.trackDao()
                .insert(
                    TrackEntity(
                        uuid = "uuid-1",
                        title = "Song",
                        artist = "Artist",
                        album = "Album",
                        durationMillis = 180_000,
                        createdAt = 1,
                    )
                )
        db.totalsDao()
            .insertAll(
                listOf(
                    TrackTotalsEntity(
                        trackRowId = row,
                        totalCount = 2,
                        totalMillis = 360_000,
                        firstPlayedAt = 1,
                        lastPlayedAt = 2,
                    )
                )
            )
        db.aliasDao()
            .putAll(
                listOf(
                    TrackAliasEntity(AliasKind.AUDIO_HASH, "hash", row),
                    TrackAliasEntity(AliasKind.TAG_KEY, "tag", row),
                    TrackAliasEntity(AliasKind.LOCAL_URI, "content://media/1|1024", row),
                )
            )
        return row
    }

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

    private fun track(
        uuid: String,
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
        durationMillis: Long = 180_000,
        createdAt: Long = 1,
    ) = BackupTrack(uuid, title, artist, album, durationMillis, createdAt)

    private fun total(
        uuid: String,
        count: Int,
        millis: Long,
        firstPlayedAt: Long,
        lastPlayedAt: Long,
    ) = BackupTotals(uuid, count, millis, firstPlayedAt, lastPlayedAt)

    private fun day(
        uuid: String,
        epochDay: Int,
        count: Int,
        millis: Long,
        lastPlayedAt: Long,
    ) = BackupDaily(uuid, epochDay, count, millis, lastPlayedAt)

    private fun backup(
        tracks: List<BackupTrack>,
        aliases: List<BackupAlias> = emptyList(),
        totals: List<BackupTotals> = emptyList(),
        daily: List<BackupDaily> = emptyList(),
        activeDays: List<Int> = emptyList(),
        version: Int = StatsBackup.VERSION,
        exportedAt: Long = 0,
        zoneId: String = "UTC",
    ) =
        StatsBackup(
            version = version,
            exportedAt = exportedAt,
            zoneId = zoneId,
            tracks = tracks,
            aliases = aliases,
            totals = totals,
            daily = daily,
            activeDays = activeDays,
        )

    private fun countRows(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun storedActiveDays(): List<Int> =
        db.openHelper.readableDatabase
            .query("SELECT epochDay FROM active_days ORDER BY epochDay")
            .use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
            }

    /** A version 1 payload has no activeDays key; the CBOR encoder omits the default value. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun version1Bytes(payload: StatsBackup): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            gzip.write(StatsBackup.MAGIC.toByteArray(Charsets.US_ASCII))
            gzip.write(Cbor { ignoreUnknownKeys = true }.encodeToByteArray(payload))
        }
        return out.toByteArray()
    }

    @Test
    fun exportOmitsLocalUriAlias() =
        runBlocking<Unit> {
            seed(db)
            val out = ByteArrayOutputStream()
            StatsBackupManager(db).export(out)
            val payload = StatsBackupManager(db).read(ByteArrayInputStream(out.toByteArray()))

            assertThat(payload.aliases.none { it.kind == AliasKind.LOCAL_URI }).isTrue()
            assertThat(payload.aliases.any { it.kind == AliasKind.AUDIO_HASH }).isTrue()
        }

    @Test
    fun restoreIgnoresLocalUriAlias() =
        runBlocking<Unit> {
            seed(db)
            val out = ByteArrayOutputStream()
            StatsBackupManager(db).export(out)
            val payload = StatsBackupManager(db).read(ByteArrayInputStream(out.toByteArray()))
            val withLocal =
                payload.copy(
                    aliases =
                        payload.aliases +
                            BackupAlias(AliasKind.LOCAL_URI, "content://media/other|2048", "uuid-1")
                )

            StatsBackupManager(db).restore(withLocal, replace = true)

            assertThat(db.aliasDao().resolve(AliasKind.LOCAL_URI, "content://media/other|2048"))
                .isNull()
            assertThat(db.totalsDao().byRowId(db.trackDao().rowIdOfUuid("uuid-1")!!)!!.totalCount)
                .isEqualTo(2)
        }

    @Test
    fun restoreMergesAudioHashIntoExistingRow() =
        runBlocking<Unit> {
            val row = seed(db)
            val payload =
                backup(
                    tracks = listOf(track("uuid-new", title = "Different")),
                    aliases = listOf(BackupAlias(AliasKind.AUDIO_HASH, "hash", "uuid-new")),
                    totals = listOf(total("uuid-new", 5, 900_000, 0, 10)),
                    daily = listOf(day("uuid-new", 20_000, 3, 540_000, 9)),
                )

            StatsBackupManager(db).restore(payload, replace = false)

            assertThat(countRows("tracks")).isEqualTo(1)
            assertThat(db.trackDao().rowIdOfUuid("uuid-new")).isNull()
            assertThat(db.trackDao().byRowId(row)!!.title).isEqualTo("Song")
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash")).isEqualTo(row)
            val totals = db.totalsDao().byRowId(row)!!
            assertThat(totals.totalCount).isEqualTo(5)
            assertThat(totals.totalMillis).isEqualTo(900_000)
            assertThat(totals.firstPlayedAt).isEqualTo(0)
            assertThat(totals.lastPlayedAt).isEqualTo(10)
            val daily = db.dailyDao().since(20_000).single()
            assertThat(daily.trackRowId).isEqualTo(row)
            assertThat(daily.playCount).isEqualTo(3)
            assertThat(daily.playedMillis).isEqualTo(540_000)
            assertThat(daily.lastPlayedAt).isEqualTo(9)
        }

    @Test
    fun restoreMergesTagKeyIntoExistingRow() =
        runBlocking<Unit> {
            val row = seed(db)
            val payload =
                backup(
                    tracks = listOf(track("uuid-new", title = "Different")),
                    aliases = listOf(BackupAlias(AliasKind.TAG_KEY, "tag", "uuid-new")),
                    totals = listOf(total("uuid-new", 4, 720_000, 0, 8)),
                )

            StatsBackupManager(db).restore(payload, replace = false)

            assertThat(countRows("tracks")).isEqualTo(1)
            assertThat(db.trackDao().rowIdOfUuid("uuid-new")).isNull()
            assertThat(db.trackDao().byRowId(row)!!.title).isEqualTo("Song")
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, "tag")).isEqualTo(row)
            val totals = db.totalsDao().byRowId(row)!!
            assertThat(totals.totalCount).isEqualTo(4)
            assertThat(totals.totalMillis).isEqualTo(720_000)
            assertThat(totals.firstPlayedAt).isEqualTo(0)
            assertThat(totals.lastPlayedAt).isEqualTo(8)
        }

    @Test
    fun restoreGroupsBackupUuidsSharingAnAlias() =
        runBlocking<Unit> {
            val row = seed(db)
            val payload =
                backup(
                    tracks =
                        listOf(
                            track("uuid-a", title = "First"),
                            track("uuid-b", title = "Second"),
                        ),
                    aliases =
                        listOf(
                            BackupAlias(AliasKind.AUDIO_HASH, "hash", "uuid-a"),
                            BackupAlias(AliasKind.AUDIO_HASH, "hash", "uuid-b"),
                        ),
                    totals =
                        listOf(
                            total("uuid-a", 5, 500_000, 0, 5),
                            total("uuid-b", 3, 900_000, 1, 7),
                        ),
                    daily =
                        listOf(
                            day("uuid-a", 20_000, 2, 200_000, 4),
                            day("uuid-b", 20_000, 3, 500_000, 6),
                        ),
                )

            StatsBackupManager(db).restore(payload, replace = false)

            assertThat(countRows("tracks")).isEqualTo(1)
            assertThat(db.trackDao().rowIdOfUuid("uuid-a")).isNull()
            assertThat(db.trackDao().rowIdOfUuid("uuid-b")).isNull()
            assertThat(db.trackDao().byRowId(row)!!.title).isEqualTo("Song")
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash")).isEqualTo(row)
            val totals = db.totalsDao().byRowId(row)!!
            assertThat(totals.totalCount).isEqualTo(5)
            assertThat(totals.totalMillis).isEqualTo(900_000)
            assertThat(totals.firstPlayedAt).isEqualTo(0)
            assertThat(totals.lastPlayedAt).isEqualTo(7)
            val daily = db.dailyDao().since(20_000).single()
            assertThat(daily.trackRowId).isEqualTo(row)
            assertThat(daily.playCount).isEqualTo(3)
            assertThat(daily.playedMillis).isEqualTo(500_000)
            assertThat(daily.lastPlayedAt).isEqualTo(6)
        }

    @Test
    fun duplicateImportIsIdempotent() =
        runBlocking<Unit> {
            val row = seed(db)
            val payload =
                backup(
                    tracks = listOf(track("uuid-new", title = "Different")),
                    aliases = listOf(BackupAlias(AliasKind.AUDIO_HASH, "hash", "uuid-new")),
                    totals = listOf(total("uuid-new", 5, 900_000, 0, 10)),
                    daily = listOf(day("uuid-new", 20_000, 3, 540_000, 9)),
                )
            val manager = StatsBackupManager(db)

            manager.restore(payload, replace = false)
            val totalsAfterFirst = db.totalsDao().byRowId(row)!!
            val dailyAfterFirst = db.dailyDao().since(20_000).single()
            manager.restore(payload, replace = false)

            assertThat(db.totalsDao().byRowId(row)).isEqualTo(totalsAfterFirst)
            assertThat(db.dailyDao().since(20_000)).containsExactly(dailyAfterFirst)
            assertThat(countRows("track_totals")).isEqualTo(1)
            assertThat(countRows("track_daily")).isEqualTo(1)
            assertThat(countRows("tracks")).isEqualTo(1)
        }

    @Test
    fun exactUuidConflictingWithAliasThrowsAndLeavesDatabaseUnchanged() =
        runBlocking<Unit> {
            val rowB = insertTrack("uuid-b", "B")
            val rowA = insertTrack("uuid-a", "A")
            db.aliasDao().put(TrackAliasEntity(AliasKind.AUDIO_HASH, "hash", rowA))
            db.totalsDao()
                .insertAll(
                    listOf(
                        TrackTotalsEntity(
                            trackRowId = rowB,
                            totalCount = 2,
                            totalMillis = 360_000,
                            firstPlayedAt = 1,
                            lastPlayedAt = 2,
                        )
                    )
                )
            val payload =
                backup(
                    tracks = listOf(track("uuid-b")),
                    aliases = listOf(BackupAlias(AliasKind.AUDIO_HASH, "hash", "uuid-b")),
                    totals = listOf(total("uuid-b", 9, 900_000, 0, 9)),
                )

            assertThatThrownBy {
                    runBlocking { StatsBackupManager(db).restore(payload, replace = false) }
                }
                .isInstanceOf(IllegalArgumentException::class.java)

            assertThat(countRows("tracks")).isEqualTo(2)
            assertThat(db.trackDao().rowIdOfUuid("uuid-b")).isEqualTo(rowB)
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash")).isEqualTo(rowA)
            assertThat(db.totalsDao().byRowId(rowB)!!.totalCount).isEqualTo(2)
            assertThat(db.totalsDao().byRowId(rowB)!!.totalMillis).isEqualTo(360_000)
        }

    @Test
    fun twoAliasesPointingAtDifferentRowsThrowAndLeaveDatabaseUnchanged() =
        runBlocking<Unit> {
            val rowA = insertTrack("uuid-a", "A")
            val rowB = insertTrack("uuid-b", "B")
            db.aliasDao().put(TrackAliasEntity(AliasKind.AUDIO_HASH, "hash", rowA))
            db.aliasDao().put(TrackAliasEntity(AliasKind.TAG_KEY, "tag", rowB))
            val payload =
                backup(
                    tracks = listOf(track("uuid-x")),
                    aliases =
                        listOf(
                            BackupAlias(AliasKind.AUDIO_HASH, "hash", "uuid-x"),
                            BackupAlias(AliasKind.TAG_KEY, "tag", "uuid-x"),
                        ),
                    totals = listOf(total("uuid-x", 9, 900_000, 0, 9)),
                )

            assertThatThrownBy {
                    runBlocking { StatsBackupManager(db).restore(payload, replace = false) }
                }
                .isInstanceOf(IllegalArgumentException::class.java)

            assertThat(countRows("tracks")).isEqualTo(2)
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash")).isEqualTo(rowA)
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, "tag")).isEqualTo(rowB)
            assertThat(countRows("track_totals")).isEqualTo(0)
        }

    @Test
    fun replaceGroupsPayloadUuidsSharingAnAliasIntoOneRow() =
        runBlocking<Unit> {
            seed(db)
            val payload =
                backup(
                    tracks =
                        listOf(
                            track("uuid-a", title = "First"),
                            track("uuid-b", title = "Second"),
                        ),
                    aliases =
                        listOf(
                            BackupAlias(AliasKind.TAG_KEY, "tag", "uuid-a"),
                            BackupAlias(AliasKind.TAG_KEY, "tag", "uuid-b"),
                        ),
                    totals =
                        listOf(
                            total("uuid-a", 4, 400_000, 0, 4),
                            total("uuid-b", 2, 800_000, 1, 8),
                        ),
                    daily =
                        listOf(
                            day("uuid-a", 20_000, 2, 200_000, 3),
                            day("uuid-b", 20_000, 5, 500_000, 6),
                        ),
                )

            StatsBackupManager(db).restore(payload, replace = true)

            assertThat(countRows("tracks")).isEqualTo(1)
            val row = db.trackDao().rowIdOfUuid("uuid-a")!!
            assertThat(db.trackDao().rowIdOfUuid("uuid-b")).isNull()
            assertThat(db.trackDao().rowIdOfUuid("uuid-1")).isNull()
            assertThat(db.trackDao().byRowId(row)!!.title).isEqualTo("First")
            assertThat(db.aliasDao().resolve(AliasKind.TAG_KEY, "tag")).isEqualTo(row)
            val totals = db.totalsDao().byRowId(row)!!
            assertThat(totals.totalCount).isEqualTo(4)
            assertThat(totals.totalMillis).isEqualTo(800_000)
            assertThat(totals.firstPlayedAt).isEqualTo(0)
            assertThat(totals.lastPlayedAt).isEqualTo(8)
            val daily = db.dailyDao().since(20_000).single()
            assertThat(daily.trackRowId).isEqualTo(row)
            assertThat(daily.playCount).isEqualTo(5)
            assertThat(daily.playedMillis).isEqualTo(500_000)
            assertThat(daily.lastPlayedAt).isEqualTo(6)
            assertThat(countRows("track_totals")).isEqualTo(1)
            assertThat(countRows("track_daily")).isEqualTo(1)
        }

    @Test
    fun replaceRestoreValidationFailureLeavesDatabaseUnchanged() =
        runBlocking<Unit> {
            val row = seed(db)
            val payload =
                backup(
                    tracks = listOf(track("uuid-1")),
                    totals = listOf(total("missing-uuid", 9, 900_000, 0, 9)),
                )

            assertThatThrownBy {
                    runBlocking { StatsBackupManager(db).restore(payload, replace = true) }
                }
                .isInstanceOf(IllegalArgumentException::class.java)

            assertThat(countRows("tracks")).isEqualTo(1)
            assertThat(db.trackDao().rowIdOfUuid("uuid-1")).isEqualTo(row)
            assertThat(db.totalsDao().byRowId(row)!!.totalCount).isEqualTo(2)
        }

    @Test
    fun exportRoundTripsActiveDays() =
        runBlocking<Unit> {
            seed(db)
            db.activeDayDao()
                .insertAll(
                    listOf(
                        ActiveDayEntity(19_500),
                        ActiveDayEntity(20_000),
                        ActiveDayEntity(20_001),
                    )
                )
            val out = ByteArrayOutputStream()
            StatsBackupManager(db).export(out)
            val payload = StatsBackupManager(db).read(ByteArrayInputStream(out.toByteArray()))

            assertThat(payload.version).isEqualTo(2)
            assertThat(payload.activeDays).containsExactly(19_500, 20_000, 20_001)

            StatsBackupManager(db).restore(payload, replace = true)

            assertThat(storedActiveDays()).containsExactly(19_500, 20_000, 20_001)
        }

    @Test
    fun version1PayloadWithoutActiveDaysDerivesThemFromDailyRows() =
        runBlocking<Unit> {
            seed(db)
            db.activeDayDao().insert(ActiveDayEntity(19_000))
            val payload =
                backup(
                    tracks = listOf(track("uuid-1")),
                    totals = listOf(total("uuid-1", 2, 360_000, 1, 2)),
                    daily =
                        listOf(
                            day("uuid-1", 20_000, 1, 180_000, 3),
                            day("uuid-1", 20_000, 2, 360_000, 4),
                            day("uuid-1", 20_001, 1, 180_000, 5),
                        ),
                    version = 1,
                )
            val decoded = StatsBackupManager(db).read(ByteArrayInputStream(version1Bytes(payload)))

            assertThat(decoded.version).isEqualTo(1)
            assertThat(decoded.activeDays).isEmpty()

            StatsBackupManager(db).restore(decoded, replace = true)

            // Replace clears 19_000, and duplicate daily days collapse into one active day.
            assertThat(storedActiveDays()).containsExactly(20_000, 20_001)
        }

    @Test
    fun duplicateRestoreKeepsActiveDaysIdempotent() =
        runBlocking<Unit> {
            val payload =
                backup(
                    tracks = listOf(track("uuid-new", title = "Different")),
                    totals = listOf(total("uuid-new", 5, 900_000, 0, 10)),
                    daily = listOf(day("uuid-new", 20_000, 3, 540_000, 9)),
                    activeDays = listOf(20_000),
                )
            val manager = StatsBackupManager(db)

            manager.restore(payload, replace = false)
            manager.restore(payload, replace = false)

            assertThat(storedActiveDays()).containsExactly(20_000)
            assertThat(countRows("active_days")).isEqualTo(1)
        }

    @Test
    fun futureDailyRelativeToSourceDateIsRejectedAndDatabaseUnchanged() =
        runBlocking<Unit> {
            val row = seed(db)
            val exportedAt = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()
            val sourceToday = LocalDate.of(2026, 6, 15).toEpochDay().toInt()
            val payload =
                backup(
                    tracks = listOf(track("uuid-1")),
                    totals = listOf(total("uuid-1", 9, 900_000, 0, 9)),
                    daily = listOf(day("uuid-1", sourceToday + 1, 1, 60_000, 9)),
                    exportedAt = exportedAt,
                )

            assertThatThrownBy {
                    runBlocking { StatsBackupManager(db).restore(payload, replace = true) }
                }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("after the source export day")

            // Validation runs before replace clears anything, so the local database is intact.
            assertThat(countRows("tracks")).isEqualTo(1)
            assertThat(db.trackDao().rowIdOfUuid("uuid-1")).isEqualTo(row)
            assertThat(db.totalsDao().byRowId(row)!!.totalCount).isEqualTo(2)
            assertThat(db.aliasDao().resolve(AliasKind.AUDIO_HASH, "hash")).isEqualTo(row)
            assertThat(countRows("track_daily")).isEqualTo(0)
        }

    @Test
    fun futureActiveDayRelativeToSourceDateIsRejectedAndDatabaseUnchanged() =
        runBlocking<Unit> {
            val row = seed(db)
            val exportedAt = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()
            val sourceToday = LocalDate.of(2026, 6, 15).toEpochDay().toInt()
            val payload =
                backup(
                    tracks = listOf(track("uuid-1")),
                    activeDays = listOf(sourceToday + 1),
                    exportedAt = exportedAt,
                )

            assertThatThrownBy {
                    runBlocking { StatsBackupManager(db).restore(payload, replace = true) }
                }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("after the source export day")

            assertThat(countRows("tracks")).isEqualTo(1)
            assertThat(db.trackDao().rowIdOfUuid("uuid-1")).isEqualTo(row)
            assertThat(countRows("active_days")).isEqualTo(0)
        }

    @Test
    fun sourceDayAheadOfTargetTodayIsAccepted() =
        runBlocking<Unit> {
            val targetToday = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
            // Two days after this device's calendar day, yet equal to the source export day.
            // This is a legitimate cross-timezone restore and must not be remapped or rejected.
            val sourceDate = LocalDate.now(ZoneId.systemDefault()).plusDays(2)
            val exportedAt = sourceDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val sourceDay = sourceDate.toEpochDay().toInt()
            assertThat(sourceDay.toLong()).isGreaterThan(targetToday)
            val payload =
                backup(
                    tracks = listOf(track("uuid-future")),
                    totals = listOf(total("uuid-future", 1, 60_000, 0, 1)),
                    daily = listOf(day("uuid-future", sourceDay, 1, 60_000, 1)),
                    activeDays = listOf(sourceDay),
                    exportedAt = exportedAt,
                    zoneId = "UTC",
                )

            StatsBackupManager(db).restore(payload, replace = true)

            assertThat(db.dailyDao().since(sourceDay).single().epochDay).isEqualTo(sourceDay)
            assertThat(storedActiveDays()).containsExactly(sourceDay)
        }

    @Test
    fun invalidZoneIdIsRejectedAndDatabaseUnchanged() =
        runBlocking<Unit> {
            val row = seed(db)
            val payload =
                backup(
                    tracks = listOf(track("uuid-1")),
                    daily = listOf(day("uuid-1", 20_000, 1, 60_000, 1)),
                    exportedAt = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli(),
                    zoneId = "Not/AZone",
                )

            assertThatThrownBy {
                    runBlocking { StatsBackupManager(db).restore(payload, replace = true) }
                }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("time zone")

            assertThat(countRows("tracks")).isEqualTo(1)
            assertThat(db.trackDao().rowIdOfUuid("uuid-1")).isEqualTo(row)
            assertThat(countRows("track_daily")).isEqualTo(0)
        }

    @Test
    fun legacyPayloadWithoutExportTimestampSkipsSourceDateValidation() =
        runBlocking<Unit> {
            // exportedAt == 0 has no source calendar day. Accept the row rather than inventing
            // one from the target clock.
            val payload =
                backup(
                    tracks = listOf(track("uuid-legacy")),
                    totals = listOf(total("uuid-legacy", 1, 60_000, 0, 1)),
                    daily = listOf(day("uuid-legacy", 20_000, 1, 60_000, 1)),
                    exportedAt = 0,
                )

            StatsBackupManager(db).restore(payload, replace = true)

            assertThat(db.dailyDao().since(20_000).single().epochDay).isEqualTo(20_000)
        }
}
