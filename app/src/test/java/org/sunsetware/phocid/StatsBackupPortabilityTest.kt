package org.sunsetware.phocid

import androidx.room.Room
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.stats.backup.BackupAlias
import org.sunsetware.phocid.stats.backup.StatsBackupManager
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

    @Test
    fun exportOmitsLocalUriAlias() = runBlocking {
        seed(db)
        val out = ByteArrayOutputStream()
        StatsBackupManager(db).export(out)
        val payload = StatsBackupManager(db).read(ByteArrayInputStream(out.toByteArray()))

        assertThat(payload.aliases.none { it.kind == AliasKind.LOCAL_URI }).isTrue()
        assertThat(payload.aliases.any { it.kind == AliasKind.AUDIO_HASH }).isTrue()
    }

    @Test
    fun restoreIgnoresLocalUriAlias() = runBlocking {
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

        assertThat(
                db.aliasDao()
                    .resolve(AliasKind.LOCAL_URI, "content://media/other|2048")
            )
            .isNull()
        assertThat(db.totalsDao().byRowId(db.trackDao().rowIdOfUuid("uuid-1")!!)!!.totalCount)
            .isEqualTo(2)
    }
}
