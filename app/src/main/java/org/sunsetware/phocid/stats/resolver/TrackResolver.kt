package org.sunsetware.phocid.stats.resolver

import android.content.Context
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.FingerprintCacheEntity
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.fingerprint.LocalUriKey
import org.sunsetware.phocid.stats.fingerprint.RelPathKey
import org.sunsetware.phocid.stats.fingerprint.TagKey
import org.sunsetware.phocid.utils.externalStorageRoots
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * Lookup order: audio hash, normalized tags + duration bucket, local content URI, relative path. A hit writes
 * back any missing aliases and refreshes the display metadata. If different layers point at
 * different rows, they are merged in one transaction so the statistics page cannot show the
 * same song twice.
 */
class TrackResolver(
    private val db: StatsDatabase,
    private val fingerprint: (TrackMeta) -> String?,
    private val mediaRoots: () -> List<String>,
) {
    private val mutex = Mutex()
    private val memo = HashMap<String, Long>()

    suspend fun resolve(meta: TrackMeta): Long {
        val key = LocalUriKey.of(meta.uri, meta.size)
        return mutex.withLock {
            memo[key]?.let { return@withLock it }
            val rowId = withContext(Dispatchers.IO) { doResolve(meta) }
            memo[key] = rowId
            rowId
        }
    }

    fun invalidate() {
        memo.clear()
    }

    private suspend fun doResolve(meta: TrackMeta): Long {
        val tracks = db.trackDao()
        val aliases = db.aliasDao()

        val localUriKey = LocalUriKey.of(meta.uri, meta.size)
        val hash = cachedHash(meta)
        val tagKeys =
            TagKey.candidates(meta.artist, meta.album, meta.title, meta.durationMillis)
        val relPath = meta.file?.let { RelPathKey.of(it.absolutePath, mediaRoots()) }

        val hits = LinkedHashMap<Int, Long>()
        hash?.let { aliases.resolve(AliasKind.AUDIO_HASH, it) }?.let {
            hits[AliasKind.AUDIO_HASH] = it
        }
        tagKeys.firstNotNullOfOrNull { aliases.resolve(AliasKind.TAG_KEY, it) }?.let {
            hits[AliasKind.TAG_KEY] = it
        }
        aliases.resolve(AliasKind.LOCAL_URI, localUriKey)?.let { hits[AliasKind.LOCAL_URI] = it }
        relPath?.let { aliases.resolve(AliasKind.REL_PATH, it) }?.let {
            hits[AliasKind.REL_PATH] = it
        }

        val winner =
            if (hits.isEmpty()) {
                createTrack(meta)
            } else {
                val selected =
                    AliasKind.PRIORITY.first { hits.containsKey(it) }.let { hits.getValue(it) }
                (hits.values.toSet() - selected).forEach {
                    mergeTracks(loser = it, winner = selected)
                }
                tracks.updateMetadata(
                    rowId = selected,
                    title = meta.title.orEmpty(),
                    artist = meta.artist.orEmpty(),
                    album = meta.album.orEmpty(),
                    durationMillis = meta.durationMillis,
                    path = meta.file?.absolutePath.orEmpty(),
                )
                selected
            }

        hash?.let { aliases.put(TrackAliasEntity(AliasKind.AUDIO_HASH, it, winner)) }
        tagKeys.firstOrNull()?.let {
            aliases.put(TrackAliasEntity(AliasKind.TAG_KEY, it, winner))
        }
        aliases.deleteKind(winner, AliasKind.LOCAL_URI)
        aliases.put(TrackAliasEntity(AliasKind.LOCAL_URI, localUriKey, winner))
        relPath?.let { aliases.put(TrackAliasEntity(AliasKind.REL_PATH, it, winner)) }
        return winner
    }

    private suspend fun createTrack(meta: TrackMeta): Long =
        db.trackDao().insert(
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
        db.maintenanceDao().merge(
            loser = loser,
            winner = winner,
            events = db.eventDao(),
            daily = db.dailyDao(),
            totals = db.totalsDao(),
            aliases = db.aliasDao(),
            tracks = db.trackDao(),
        )
        memo.entries.removeAll { it.value == loser }
    }

    private suspend fun cachedHash(meta: TrackMeta): String? {
        val dao = db.fingerprintCacheDao()
        val localKey = LocalUriKey.of(meta.uri, meta.size)
        dao.hit(localKey, meta.size, meta.modified)?.let {
            return it
        }

        // Keep reading the legacy path-keyed cache so upgrading does not re-hash the library.
        meta.file?.let { file ->
            if (file.isFile) {
                dao.hit(file.absolutePath, file.length(), file.lastModified())?.let {
                    return it
                }
            }
        }

        val hash = runCatching { fingerprint(meta) }.getOrNull() ?: return null
        dao.put(FingerprintCacheEntity(localKey, meta.size, meta.modified, hash))
        return hash
    }
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
