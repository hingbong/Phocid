package org.sunsetware.phocid.stats.resolver

import androidx.room.withTransaction
import java.util.Locale
import org.sunsetware.phocid.stats.db.AliasKind
import org.sunsetware.phocid.stats.db.StatsDatabase
import org.sunsetware.phocid.stats.db.TrackAliasEntity
import org.sunsetware.phocid.stats.fingerprint.RelPathKey

/**
 * Scan-time maintenance that upgrades legacy basename-only REL_PATH aliases to genuinely rooted
 * keys.
 *
 * Before commit 55d9139 an unrooted path stored REL_PATH as the lowercased file name. Those stored
 * values are indistinguishable from legitimate root-direct keys, so they can never be classified by
 * shape alone (see StatsRelPathLegacyAliasTest). This pass only strengthens a key when strong
 * identity anchors the row AND a scanned file positively proves it is that audio:
 *
 * 1. the row owns a basename-shaped REL_PATH alias (no '/'), and
 * 2. the row also owns an AUDIO_HASH alias (a hashless row's basename may be its sole identity),
 *    and
 * 3. a scanned file whose computed AUDIO_HASH equals one of the row's hashes is found, and
 * 4. that file's rooted key is non-null and contains '/' (a real improvement over the basename),
 *    and
 * 5. the rooted key is unowned or already owned by this row (never steal another row's identity).
 *
 * Only then is the rooted key inserted FIRST and the single legacy basename claim retired in the
 * same transaction. The AUDIO_HASH anchor always survives, so identity is never stranded. Every
 * other case is skipped. This never touches resolve()/doResolve(), which already upgrades
 * basename->rooted on re-resolve; it proactively covers rows that have not been re-resolved.
 * Running twice is a no-op.
 */
class RelPathAliasRebuild(
    private val db: StatsDatabase,
    private val fingerprint: (TrackMeta) -> String?,
    private val mediaRoots: () -> List<String>,
) {
    suspend fun rebuild(scanned: List<TrackMeta>) {
        val aliases = db.aliasDao()
        val all = aliases.all()

        val legacyByRow =
            all.filter { it.kind == AliasKind.REL_PATH && !it.value.contains('/') }
                .groupBy { it.trackRowId }
        if (legacyByRow.isEmpty()) return

        val hashesByRow = all.filter { it.kind == AliasKind.AUDIO_HASH }.groupBy { it.trackRowId }

        // Cheap basename pre-filter: only files whose name matches a legacy key are ever hashed.
        val scannedByBasename =
            scanned
                .mapNotNull { meta -> meta.file?.let { it.name.lowercase(Locale.ROOT) to meta } }
                .groupBy({ it.first }, { it.second })
        val roots = mediaRoots()

        for ((rowId, legacyAliases) in legacyByRow) {
            // No AUDIO_HASH anchor means the basename could be the row's only portable identity.
            val rowHashes = hashesByRow[rowId] ?: continue
            for (legacy in legacyAliases) {
                val basename = legacy.value
                val candidates = scannedByBasename[basename] ?: continue

                val matchedFile =
                    candidates
                        .asSequence()
                        .mapNotNull { meta ->
                            val hash = runCatching { fingerprint(meta) }.getOrNull()
                            if (hash != null && rowHashes.any { it.value == hash }) meta.file
                            else null
                        }
                        .firstOrNull() ?: continue

                val rooted = RelPathKey.of(matchedFile.absolutePath, roots) ?: continue
                // A root-direct file yields the same basename shape: no gain, and deleting the old
                // key would be indistinguishable from removing a legitimate identity.
                if (!rooted.contains('/')) continue

                val owner = aliases.resolve(AliasKind.REL_PATH, rooted)
                if (owner != null && owner != rowId) continue

                db.withTransaction {
                    aliases.insertIgnore(TrackAliasEntity(AliasKind.REL_PATH, rooted, rowId))
                    aliases.deleteValue(rowId, AliasKind.REL_PATH, basename)
                }
            }
        }
    }
}
