package org.sunsetware.phocid.stats.resolver

import android.content.Context
import android.os.Environment
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.FingerprintCacheEntity
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.db.TrackEntity
import org.sunsetware.phocid.stats.fingerprint.AudioFingerprint
import org.sunsetware.phocid.stats.fingerprint.RelPathKey
import org.sunsetware.phocid.stats.fingerprint.TagKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Track metadata passed from the player. */
data class TrackMeta(
    val file: File,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMillis: Long,
)

/**
 * File -> trackRowId resolver.
 *
 * Lookup order: audio hash, normalized tags + duration bucket, relative path. A hit writes
 * back any missing aliases and refreshes the display metadata. If different layers point at
 * different rows, they are merged in one transaction so the statistics page cannot show the
 * same song twice.
 */
class TrackResolver(
    private val db: StatsDatabase,
    private val mediaRoots: () -> List<String>,
) {
    private val mutex = Mutex()
    private val memo = HashMap<String, Long>()

    suspend fun resolve(meta: TrackMeta): Long =
        mutex.withLock {
            memo[meta.file.absolutePath]?.let {
                return it
            }
            val rowId = withContext(Dispatchers.IO) { doResolve(meta) }
            memo[meta.file.absolutePath] = rowId
            rowId
        }

    fun invalidate() {
        memo.clear()
    }

    private suspend fun doResolve(meta: TrackMeta): Long {
        val tracks = db.trackDao()
        val aliases = db.aliasDao()

        val hash = cachedHash(meta.file)
        val tagKeys =
            TagKey.candidates(meta.artist, meta.album, meta.title, meta.durationMillis)
        val relPath = RelPathKey.of(meta.file.absolutePath, mediaRoots())

        val hits = LinkedHashMap<Int, Long>()
        hash?.let { aliases.resolve(AliasKind.AUDIO_HASH, it) }?.let {
            hits[AliasKind.AUDIO_HASH] = it
        }
        tagKeys.firstNotNullOfOrNull { aliases.resolve(AliasKind.TAG_KEY, it) }?.let {
            hits[AliasKind.TAG_KEY] = it
        }
        aliases.resolve(AliasKind.REL_PATH, relPath)?.let { hits[AliasKind.REL_PATH] = it }

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
                    path = meta.file.absolutePath,
                )
                selected
            }

        hash?.let { aliases.put(TrackAliasEntity(AliasKind.AUDIO_HASH, it, winner)) }
        tagKeys.firstOrNull()?.let {
            aliases.put(TrackAliasEntity(AliasKind.TAG_KEY, it, winner))
        }
        aliases.put(TrackAliasEntity(AliasKind.REL_PATH, relPath, winner))
        return winner
    }

    private suspend fun createTrack(meta: TrackMeta): Long =
        db.trackDao().insert(
            TrackEntity(
                uuid = UUID.randomUUID().toString(),
                title = meta.title ?: meta.file.nameWithoutExtension,
                artist = meta.artist.orEmpty(),
                album = meta.album.orEmpty(),
                durationMillis = meta.durationMillis,
                path = meta.file.absolutePath,
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

    private suspend fun cachedHash(file: File): String? {
        if (!file.isFile) return null
        val dao = db.fingerprintCacheDao()
        val path = file.absolutePath
        val size = file.length()
        val mtime = file.lastModified()

        dao.hit(path, size, mtime)?.let {
            return it
        }

        val hash = AudioFingerprint.compute(file)
        if (hash != null) {
            dao.put(FingerprintCacheEntity(path, size, mtime, hash))
        }
        return hash
    }
}

/** Convert a Phocid track into the small metadata record used by the resolver. */
fun org.sunsetware.phocid.data.Track.toTrackMeta(): TrackMeta {
    return TrackMeta(
        file = File(path),
        title = title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'),
        artist = artists.joinToString(", ").takeIf { it.isNotBlank() },
        album = album?.takeIf { it.isNotBlank() },
        durationMillis = duration.inWholeMilliseconds.coerceAtLeast(0),
    )
}

/** Candidate roots for the relative-path fallback. */
fun defaultMediaRoots(context: Context): List<String> {
    val roots = mutableListOf(Environment.getExternalStorageDirectory().absolutePath)
    context.getExternalFilesDirs(null).forEach { directory ->
        var current = directory
        repeat(4) { current = current?.parentFile }
        current?.absolutePath?.let { roots += it }
    }
    return roots.distinct()
}
