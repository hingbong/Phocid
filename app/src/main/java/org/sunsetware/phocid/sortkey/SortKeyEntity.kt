package org.sunsetware.phocid.sortkey

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sort_keys",
    indices =
        [
            Index(value = ["local_uri_key"], unique = true),
            Index(value = ["tag_key"]),
        ],
)
data class SortKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "local_uri_key") val localUriKey: String,
    @ColumnInfo(name = "tag_key") val tagKey: String?,
    @ColumnInfo(name = "sort_key") val sortKey: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
