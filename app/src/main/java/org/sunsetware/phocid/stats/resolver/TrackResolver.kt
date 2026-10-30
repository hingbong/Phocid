package org.sunsetware.phocid.stats.resolver

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.fingerprint.LocalUriKey
import org.sunsetware.phocid.stats.fingerprint.RelPathKey
import org.sunsetware.phocid.stats.fingerprint.TagKey
import org.sunsetware.phocid.utils.externalStorageRoots

/** Track metadata passed from the player. */
data class TrackMeta(
    val uri: String,
    val file: File?,
    val size: Long,
    val modified: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMillis: Long,
)

/**
 * Media item -> trackRowId resolver.
 *
 * Lookup order: audio hash, tags, relative path. LOCAL_URI is only a local hint used to clean up
 * aliases; it never selects a row. Identity is resolved conservatively: when clues disagree the
 * resolver creates a new row and leaves a temporary split instead of risking a wrong merge. A local
 * marker makes a repeated resolve reuse that split row. Aliases are written with IGNORE so a weaker
 * clue can never overwrite an existing identity.
 */
class TrackResolver(
    private val db: StatsDatabase,
    private val fingerprint: (TrackMeta) -> String?,
    private val mediaRoots: () -> List<String>,
) {
    private val mutex = Mutex()

    suspend fun resolve(meta: TrackMeta): Long {
        // Hashing can read the whole audio file. Do it before taking the write lock so slow
        // media never serializes behind another track's database transaction. The hash is always
        // computed for the current resolve: a MediaStore URI can be reused, so uri, size, and
        // modified time alone cannot prove that a cached hash still describes this audio.
        val hash = withContext(Dispatchers.IO) { runCatching { fingerprint(meta) }.getOrNull() }
        val localUriKey = LocalUriKey.of(meta.uri, meta.size)
        val tagKeys = TagKey.candidates(meta.artist, meta.album, meta.title, meta.durationMillis)
        val relPath = meta.file?.let { RelPathKey.of(it.absolutePath, mediaRoots()) }

        return mutex.withLock {
            db.withTransaction { doResolve(meta, hash, localUriKey, tagKeys, relPath) }
        }
    }

    fun invalidate() {
        // Identity is re-read from the database on every resolve, so there is no memo to drop.
    }

    private suspend fun doResolve(
        meta: TrackMeta,
        hash: String?,
        localUriKey: String,
        tagKeys: List<String>,
        relPath: String?,
    ): Long {
        val tracks = db.trackDao()
        val aliases = db.aliasDao()

        val hashHit = hash?.let { aliases.resolve(AliasKind.AUDIO_HASH, it) }
        val tagHits = tagKeys.mapNotNull { aliases.resolve(AliasKind.TAG_KEY, it) }.distinct()
        val relHit = relPath?.let { aliases.resolve(AliasKind.REL_PATH, it) }
        var localHit = aliases.resolve(AliasKind.LOCAL_URI, localUriKey)
        val ambiguousKey = if (tagHits.size > 1) ambiguousTagKey(tagKeys) else null
        val ambiguousHit = ambiguousKey?.let { aliases.resolve(AliasKind.AMBIGUOUS_TAG, it) }

        // A weak clue may select a row only when that row does not already prove it holds other
        // audio. Otherwise the current hash would be inserted with IGNORE against a row owned by
        // a different audio and silently write the new audio into the old track.
        val weakWinner =
            when {
                tagHits.size == 1 -> tagHits.single()
                // Ambiguous tags are safer as a dedicated row than as a guess between rows. Reuse
                // that row when the same conflict is seen again instead of splitting every time.
                tagHits.size > 1 -> ambiguousHit
                relHit != null -> relHit
                else -> null
            }
        val winner =
            when {
                hashHit != null -> hashHit
                weakWinner != null && !ownsConflictingHash(weakWinner, hash) -> weakWinner
                else -> null
            }

        // Only a hash hit is strong enough to justify merging. Weak clues that disagree keep
        // the rows apart rather than risking a wrong merge.
        if (hashHit != null && tagHits.size <= 1) {
            val weakRows = mutableSetOf<Long>()
            weakRows.addAll(tagHits)
            relHit?.let { weakRows.add(it) }
            weakRows.remove(hashHit)
            val weakRow = weakRows.singleOrNull()
            // A row that already owns another audio hash is a separate track, not a weak duplicate
            // of this one. Merging it would steal that identity on every repeated resolve.
            if (weakRow != null && !ownsConflictingHash(weakRow, hash)) {
                mergeTracks(loser = weakRow, winner = hashHit)
                // The merge repoints the loser's aliases, including a matching local URI hint.
                localHit = aliases.resolve(AliasKind.LOCAL_URI, localUriKey)
            }
        }

        val rowId =
            if (winner != null) {
                tracks.updateMetadata(
                    rowId = winner,
                    title = meta.title.orEmpty(),
                    artist = meta.artist.orEmpty(),
                    album = meta.album.orEmpty(),
                    durationMillis = meta.durationMillis,
                    path = meta.file?.absolutePath.orEmpty(),
                )
                winner
            } else {
                createTrack(meta)
            }

        // IGNORE turns a conflicting alias into a no-op instead of a REPLACE that would steal
        // another row's identity.
        hash?.let { aliases.insertIgnore(TrackAliasEntity(AliasKind.AUDIO_HASH, it, rowId)) }
        tagKeys.forEach { aliases.insertIgnore(TrackAliasEntity(AliasKind.TAG_KEY, it, rowId)) }

        // Remember the row created for an unresolvable tag conflict so a repeated resolve reuses
        // it. The marker never merges this row into the rows that own the conflicting tag keys,
        // and a hash hit still wins over it.
        if (ambiguousKey != null && hashHit == null) {
            aliases.insertIgnore(TrackAliasEntity(AliasKind.AMBIGUOUS_TAG, ambiguousKey, rowId))
        }

        // The local URI is a hint: replace the winner's old URI, but never repoint an alias that
        // still belongs to another row.
        aliases.deleteKind(rowId, AliasKind.LOCAL_URI)
        if (localHit == null || localHit == rowId) {
            aliases.insertIgnore(TrackAliasEntity(AliasKind.LOCAL_URI, localUriKey, rowId))
        }

        relPath?.let {
            aliases.deleteKind(rowId, AliasKind.REL_PATH)
            aliases.insertIgnore(TrackAliasEntity(AliasKind.REL_PATH, it, rowId))
        }
        return rowId
    }

    /** True when [rowId] already owns an AUDIO_HASH alias for audio other than [hash]. */
    private suspend fun ownsConflictingHash(rowId: Long, hash: String?): Boolean {
        if (hash == null) return false
        return db.aliasDao().aliasesOf(rowId).any {
            it.kind == AliasKind.AUDIO_HASH && it.value != hash
        }
    }

    private suspend fun createTrack(meta: TrackMeta): Long =
        db.trackDao()
            .insert(
                TrackEntity(
                    uuid = UUID.randomUUID().toString(),
                    title =
                        meta.title
                            ?: meta.file?.nameWithoutExtension
                            ?: meta.uri.substringAfterLast('/'),
                    artist = meta.artist.orEmpty(),
                    album = meta.album.orEmpty(),
                    durationMillis = meta.durationMillis,
                    path = meta.file?.absolutePath.orEmpty(),
                    createdAt = System.currentTimeMillis(),
                )
            )

    private suspend fun mergeTracks(loser: Long, winner: Long) {
        db.maintenanceDao()
            .merge(
                loser = loser,
                winner = winner,
                events = db.eventDao(),
                daily = db.dailyDao(),
                totals = db.totalsDao(),
                aliases = db.aliasDao(),
                tracks = db.trackDao(),
            )
    }

    /** Stable local lookup key for a row created because TAG_KEY candidates conflicted. */
    private fun ambiguousTagKey(tagKeys: List<String>): String =
        "ambiguous\u0000" + tagKeys.sorted().joinToString("\u0000")
}

/** Convert a Phocid track into the small metadata record used by the resolver. */
fun org.sunsetware.phocid.data.Track.toTrackMeta(): TrackMeta {
    return TrackMeta(
        uri = uri.toString(),
        file = path.takeIf { it.isNotBlank() }?.let(::File),
        size = size,
        modified = version,
        title = title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'),
        artist = artists.joinToString(", ").takeIf { it.isNotBlank() },
        album = album?.takeIf { it.isNotBlank() },
        durationMillis = duration.inWholeMilliseconds.coerceAtLeast(0),
    )
}

/** Candidate roots for the relative-path fallback. */
fun defaultMediaRoots(context: Context): List<String> {
    return context.externalStorageRoots().map { it.absolutePath }
}
