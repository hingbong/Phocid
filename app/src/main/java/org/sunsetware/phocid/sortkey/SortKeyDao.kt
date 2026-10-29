package org.sunsetware.phocid.sortkey

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SortKeyDao {
    @Query("SELECT * FROM sort_keys") fun observeAll(): Flow<List<SortKeyEntity>>

    @Query("SELECT * FROM sort_keys") suspend fun getAll(): List<SortKeyEntity>

    @Query("SELECT * FROM sort_keys WHERE local_uri_key = :localUriKey LIMIT 1")
    suspend fun findByLocalUriKey(localUriKey: String): SortKeyEntity?

    @Query("SELECT * FROM sort_keys WHERE tag_key IN (:tagKeys)")
    suspend fun findByTagKeys(tagKeys: List<String>): List<SortKeyEntity>

    @Insert suspend fun insert(entity: SortKeyEntity): Long

    @Update suspend fun update(entity: SortKeyEntity)

    @Delete suspend fun delete(entity: SortKeyEntity)
}
