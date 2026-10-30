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

data class SortKeySkipped(val libraryId: Long, val reason: String)

data class SortKeyApplyResult(
    val updated: Int,
    val cleared: Int,
    val skipped: Int,
    val errors: List<SortKeyApplyError>,
    val skippedItems: List<SortKeySkipped>,
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
        dryRun: Boolean = false,
    ): SortKeyApplyResult = database.withTransaction {
        var updated = 0
        var cleared = 0
        var skipped = 0
        val errors = mutableListOf<SortKeyApplyError>()
        val skippedItems = mutableListOf<SortKeySkipped>()
        val model = SortKeyBatchModel(dao.getAll())

        for (target in targets) {
            val local = model.findByLocalUriKey(target.localUriKey)
            val tagRows =
                if (local == null && target.tagCandidates.isNotEmpty()) {
                    model.findByTagKeys(target.tagCandidates)
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
                existing == null && desired == null -> {
                    skipped++
                    skippedItems += SortKeySkipped(target.libraryId, "already_absent")
                }
                existing == null -> {
                    val row =
                        SortKeyEntity(
                            localUriKey = target.localUriKey,
                            tagKey = target.tagCandidates.firstOrNull(),
                            sortKey = desired!!,
                            updatedAt = nowMillis,
                        )
                    if (dryRun) {
                        model.insert(row)
                    } else {
                        val id = dao.insert(row)
                        check(id > 0) { "Insert did not return a sort key id" }
                        model.insert(row.copy(id = id))
                    }
                    updated++
                }
                // Do not let overwrite=false clear an existing value.
                !target.overwrite -> {
                    skipped++
                    skippedItems += SortKeySkipped(target.libraryId, "existing_value")
                }
                desired == null -> {
                    if (!dryRun) dao.delete(existing)
                    model.delete(existing)
                    cleared++
                }
                else -> {
                    val row =
                        existing.copy(
                            localUriKey = target.localUriKey,
                            tagKey = target.tagCandidates.firstOrNull(),
                            sortKey = desired,
                            updatedAt = nowMillis,
                        )
                    if (!dryRun) dao.update(row)
                    model.update(existing, row)
                    updated++
                }
            }
        }

        SortKeyApplyResult(
            updated = updated,
            cleared = cleared,
            skipped = skipped,
            errors = errors,
            skippedItems = skippedItems,
        )
    }
}

/**
 * Mutable view of sort-key rows for one batch.
 *
 * Both dry runs and real writes make every lookup and mutation against this model, so earlier
 * entries in a batch are visible in later entries in both modes. Real mode additionally performs
 * the DAO operation and stores the auto-generated id, so a later update/delete never receives an id
 * of 0. Dry-run entries get a synthetic id that is only ever used inside the model.
 */
private class SortKeyBatchModel(rows: List<SortKeyEntity>) {
    private val byId = LinkedHashMap<Long, SortKeyEntity>()
    private val idByLocalUriKey = HashMap<String, Long>()
    private val idsByTagKey = HashMap<String, MutableSet<Long>>()
    private var nextSyntheticId = -1L

    init {
        rows.forEach(::add)
    }

    fun findByLocalUriKey(localUriKey: String): SortKeyEntity? =
        idByLocalUriKey[localUriKey]?.let(byId::get)

    fun findByTagKeys(tagKeys: List<String>): List<SortKeyEntity> {
        val ids = LinkedHashSet<Long>()
        for (tagKey in tagKeys) {
            idsByTagKey[tagKey]?.let(ids::addAll)
        }
        return ids.mapNotNull(byId::get)
    }

    /** Returns the stored row; a row without an id gets a synthetic one for dry-run batches. */
    fun insert(entity: SortKeyEntity): SortKeyEntity {
        val stored = if (entity.id == 0L) entity.copy(id = nextSyntheticId--) else entity
        add(stored)
        return stored
    }

    fun update(previous: SortKeyEntity, updated: SortKeyEntity) {
        remove(previous)
        add(updated)
    }

    fun delete(entity: SortKeyEntity) {
        remove(entity)
    }

    private fun add(entity: SortKeyEntity) {
        byId[entity.id] = entity
        idByLocalUriKey[entity.localUriKey] = entity.id
        entity.tagKey?.let { tagKey ->
            idsByTagKey.getOrPut(tagKey) { mutableSetOf() }.add(entity.id)
        }
    }

    private fun remove(entity: SortKeyEntity) {
        byId.remove(entity.id)
        if (idByLocalUriKey[entity.localUriKey] == entity.id) {
            idByLocalUriKey.remove(entity.localUriKey)
        }
        entity.tagKey?.let { tagKey ->
            idsByTagKey[tagKey]?.let { ids ->
                ids.remove(entity.id)
                if (ids.isEmpty()) idsByTagKey.remove(tagKey)
            }
        }
    }
}
