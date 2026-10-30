package org.sunsetware.phocid

import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.sunsetware.phocid.sortkey.SortKeyApplyResult
import org.sunsetware.phocid.sortkey.SortKeyDatabase
import org.sunsetware.phocid.sortkey.SortKeyEntity
import org.sunsetware.phocid.sortkey.SortKeyManager
import org.sunsetware.phocid.sortkey.SortKeyTarget

@RunWith(RobolectricTestRunner::class)
class SortKeyDatabaseTest {
    private lateinit var db: SortKeyDatabase
    private lateinit var manager: SortKeyManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    SortKeyDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        manager = SortKeyManager(db, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun target(
        local: String,
        tag: String?,
        key: String?,
        overwrite: Boolean = true,
    ): SortKeyTarget =
        SortKeyTarget(
            libraryId = 1,
            localUriKey = local,
            tagCandidates = listOfNotNull(tag),
            sortKey = key,
            overwrite = overwrite,
        )

    private fun entity(local: String, tag: String?, key: String) =
        SortKeyEntity(
            localUriKey = local,
            tagKey = tag,
            sortKey = key,
            updatedAt = 0,
        )

    private suspend fun rows(): List<SortKeyEntity> =
        db.sortKeyDao().getAll().sortedBy { it.localUriKey }

    private suspend fun assertDryRunMatchesReal(
        initial: List<SortKeyEntity>,
        targets: List<SortKeyTarget>,
        nowMillis: Long = 1,
    ): SortKeyApplyResult {
        initial.forEach { db.sortKeyDao().insert(it) }
        val before = rows()
        val dry = manager.apply(targets, nowMillis, dryRun = true)
        assertThat(rows()).isEqualTo(before)
        val real = manager.apply(targets, nowMillis, dryRun = false)
        assertThat(real).isEqualTo(dry)
        return real
    }

    @Test
    fun apply_insertsAndOverwrites() {
        runBlocking {
            val first = manager.apply(listOf(target("a", "tag", "one")), nowMillis = 1)
            assertThat(first.updated).isEqualTo(1)
            assertThat(db.sortKeyDao().findByLocalUriKey("a")?.sortKey).isEqualTo("one")

            val skipped = manager.apply(listOf(target("a", "tag", "two", overwrite = false)), 2)
            assertThat(skipped.skipped).isEqualTo(1)
            assertThat(db.sortKeyDao().findByLocalUriKey("a")?.sortKey).isEqualTo("one")

            val updated = manager.apply(listOf(target("a", "tag", "two")), 3)
            assertThat(updated.updated).isEqualTo(1)
            assertThat(db.sortKeyDao().findByLocalUriKey("a")?.sortKey).isEqualTo("two")
        }
    }

    @Test
    fun apply_clears() {
        runBlocking {
            manager.apply(listOf(target("a", "tag", "one")), 1)
            val cleared = manager.apply(listOf(target("a", "tag", null)), 2)
            assertThat(cleared.cleared).isEqualTo(1)
            assertThat(db.sortKeyDao().findByLocalUriKey("a")).isNull()
        }
    }

    @Test
    fun apply_dryRunDoesNotWrite() {
        runBlocking {
            val result = manager.apply(listOf(target("a", "tag", "one")), dryRun = true)
            assertThat(result.updated).isEqualTo(1)
            assertThat(db.sortKeyDao().findByLocalUriKey("a")).isNull()
        }
    }

    @Test
    fun apply_clearMissing_hasReason() {
        runBlocking {
            val result = manager.apply(listOf(target("a", "tag", null)), 1)
            assertThat(result.skipped).isEqualTo(1)
            assertThat(result.skippedItems.single().reason).isEqualTo("already_absent")
        }
    }

    @Test
    fun apply_reusesUniqueTagRow() {
        runBlocking {
            manager.apply(listOf(target("old", "tag", "one")), 1)
            val updated = manager.apply(listOf(target("new", "tag", "two")), 2)
            assertThat(updated.updated).isEqualTo(1)
            assertThat(db.sortKeyDao().findByLocalUriKey("old")).isNull()
            assertThat(db.sortKeyDao().findByLocalUriKey("new")?.sortKey).isEqualTo("two")
        }
    }

    @Test
    fun apply_ambiguousTagDoesNotWrite() {
        runBlocking {
            db.sortKeyDao()
                .insert(
                    SortKeyEntity(
                        localUriKey = "one",
                        tagKey = "tag",
                        sortKey = "one",
                        updatedAt = 0,
                    )
                )
            db.sortKeyDao()
                .insert(
                    SortKeyEntity(
                        localUriKey = "two",
                        tagKey = "tag",
                        sortKey = "two",
                        updatedAt = 0,
                    )
                )
            val result = manager.apply(listOf(target("new", "tag", "three")), 1)
            assertThat(result.errors.single().code).isEqualTo("ambiguous_identity")
            assertThat(db.sortKeyDao().findByLocalUriKey("new")).isNull()
        }
    }

    @Test
    fun apply_overwriteFalseProtectsExistingValueFromClear() {
        runBlocking {
            manager.apply(listOf(target("a", "tag", "one")), 1)
            val result = manager.apply(listOf(target("a", "tag", null, overwrite = false)), 2)
            assertThat(result.cleared).isZero()
            assertThat(result.skipped).isEqualTo(1)
            assertThat(result.skippedItems.single().reason).isEqualTo("existing_value")
            assertThat(db.sortKeyDao().findByLocalUriKey("a")?.sortKey).isEqualTo("one")
        }
    }

    @Test
    fun apply_overwriteTrueClearsExistingValue() {
        runBlocking {
            manager.apply(listOf(target("a", "tag", "one")), 1)
            val result = manager.apply(listOf(target("a", "tag", null, overwrite = true)), 2)
            assertThat(result.cleared).isEqualTo(1)
            assertThat(db.sortKeyDao().findByLocalUriKey("a")).isNull()
        }
    }

    @Test
    fun apply_missingWithNull_isAlreadyAbsentEvenWhenOverwriteIsTrue() {
        runBlocking {
            val result = manager.apply(listOf(target("a", "tag", null, overwrite = true)), 1)
            assertThat(result.skipped).isEqualTo(1)
            assertThat(result.skippedItems.single().reason).isEqualTo("already_absent")
        }
    }

    @Test
    fun apply_missingWithValue_insertsEvenWhenOverwriteIsFalse() {
        runBlocking {
            val result = manager.apply(listOf(target("a", "tag", "one", overwrite = false)), 1)
            assertThat(result.updated).isEqualTo(1)
            assertThat(db.sortKeyDao().findByLocalUriKey("a")?.sortKey).isEqualTo("one")
        }
    }

    @Test
    fun apply_dryRunAndReal_insertThenUpdateMatch() {
        runBlocking {
            val real =
                assertDryRunMatchesReal(
                    initial = emptyList(),
                    targets =
                        listOf(
                            target("a", "tag", "one"),
                            target("a", "tag", "two"),
                        ),
                )
            assertThat(real.updated).isEqualTo(2)
            assertThat(db.sortKeyDao().findByLocalUriKey("a")?.sortKey).isEqualTo("two")
        }
    }

    @Test
    fun apply_dryRunAndReal_insertThenClearMatch() {
        runBlocking {
            val real =
                assertDryRunMatchesReal(
                    initial = emptyList(),
                    targets =
                        listOf(
                            target("a", "tag", "one"),
                            target("a", "tag", null),
                        ),
                )
            assertThat(real.updated).isEqualTo(1)
            assertThat(real.cleared).isEqualTo(1)
            assertThat(db.sortKeyDao().findByLocalUriKey("a")).isNull()
        }
    }

    @Test
    fun apply_dryRunAndReal_duplicateItemsMatch() {
        runBlocking {
            val real =
                assertDryRunMatchesReal(
                    initial = emptyList(),
                    targets =
                        listOf(
                            target("a", "tag", "one", overwrite = false),
                            target("a", "tag", "two", overwrite = false),
                        ),
                )
            assertThat(real.updated).isEqualTo(1)
            assertThat(real.skipped).isEqualTo(1)
            assertThat(real.skippedItems.single().reason).isEqualTo("existing_value")
            assertThat(db.sortKeyDao().findByLocalUriKey("a")?.sortKey).isEqualTo("one")
        }
    }

    @Test
    fun apply_dryRunAndReal_ambiguousTagMatches() {
        runBlocking {
            val real =
                assertDryRunMatchesReal(
                    initial =
                        listOf(
                            entity("one", "tag", "one"),
                            entity("two", "tag", "two"),
                        ),
                    targets = listOf(target("new", "tag", "three")),
                )
            assertThat(real.errors.single().code).isEqualTo("ambiguous_identity")
            assertThat(db.sortKeyDao().findByLocalUriKey("new")).isNull()
        }
    }

    @Test
    fun apply_dryRunAndReal_existingValueProtectedFromClearMatches() {
        runBlocking {
            val real =
                assertDryRunMatchesReal(
                    initial = listOf(entity("a", "tag", "one")),
                    targets = listOf(target("a", "tag", null, overwrite = false)),
                )
            assertThat(real.skipped).isEqualTo(1)
            assertThat(real.cleared).isZero()
            assertThat(real.skippedItems.single().reason).isEqualTo("existing_value")
            assertThat(db.sortKeyDao().findByLocalUriKey("a")?.sortKey).isEqualTo("one")
        }
    }
}
