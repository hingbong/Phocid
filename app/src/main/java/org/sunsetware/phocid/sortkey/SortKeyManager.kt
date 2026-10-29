package org.sunsetware.phocid.sortkey

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SortKeyTarget(
    val libraryId: Long,
    val localUriKey: String,
    val tagCandidates: List<String>,
    /** null clears the existing value. */
    val sortKey: String?,
    val overwrite: Boolean,
)

data class SortKeyApplyError(
    val libraryId: Long,
    val code: String,
    val message: String,
)

data class SortKeyApplyResult(
    val updated: Int,
    val cleared: Int,
    val skipped: Int,
    val errors: List<SortKeyApplyError>,
)

class SortKeyManager(
    private val database: SortKeyDatabase,
    scope: CoroutineScope,
) {
    constructor(context: Context, scope: CoroutineScope) :
        this(SortKeyDatabase.get(context.applicationContext), scope)

    private val dao = database.sortKeyDao()
    private val _keys = MutableStateFlow(SortKeyLookup.EMPTY)
    val keys: StateFlow<SortKeyLookup> = _keys.asStateFlow()

    init {
        scope.launch {
            dao.observeAll().collect { _keys.value = SortKeyLookup.from(it) }
        }
    }

    /** Loads a snapshot before the first LibraryIndex build, then Room keeps it fresh. */
    suspend fun loadInitial() {
        _keys.value = SortKeyLookup.from(dao.getAll())
    }

    suspend fun apply(
        targets: List<SortKeyTarget>,
        nowMillis: Long = System.currentTimeMillis(),
    ): SortKeyApplyResult =
        database.withTransaction {
            var updated = 0
            var cleared = 0
            var skipped = 0
            val errors = mutableListOf<SortKeyApplyError>()

            for (target in targets) {
                val local = dao.findByLocalUriKey(target.localUriKey)
                val tagRows =
                    if (local == null && target.tagCandidates.isNotEmpty()) {
                        dao.findByTagKeys(target.tagCandidates)
                    } else {
                        emptyList()
                    }

                if (local == null && tagRows.size > 1) {
                    errors +=
                        SortKeyApplyError(
                            target.libraryId,
                            "ambiguous_identity",
                            "Multiple tracks share this tag key",
                        )
                    continue
                }

                val existing = local ?: tagRows.singleOrNull()
                val desired = target.sortKey
                when {
                    existing == null && desired == null -> skipped++
                    existing == null -> {
                        dao.insert(
                            SortKeyEntity(
                                localUriKey = target.localUriKey,
                                tagKey = target.tagCandidates.firstOrNull(),
                                sortKey = desired!!,
                                updatedAt = nowMillis,
                            )
                        )
                        updated++
                    }
                    desired == null -> {
                        dao.delete(existing)
                        cleared++
                    }
                    !target.overwrite -> skipped++
                    else -> {
                        dao.update(
                            existing.copy(
                                localUriKey = target.localUriKey,
                                tagKey = target.tagCandidates.firstOrNull(),
                                sortKey = desired!!,
                                updatedAt = nowMillis,
                            )
                        )
                        updated++
                    }
                }
            }

            SortKeyApplyResult(
                updated = updated,
                cleared = cleared,
                skipped = skipped,
                errors = errors,
            )
        }
}
