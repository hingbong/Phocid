package org.sunsetware.phocid.ui.views.statistics

/**
 * Builds a tag-key candidate to owning track id index for the statistics list.
 *
 * [org.sunsetware.phocid.stats.fingerprint.TagKey.candidates] returns more than one key per track
 * when the duration sits near a bucket boundary. A key claimed by more than one distinct track id
 * is ambiguous: the statistics row cannot tell which track is meant, so the key is omitted instead
 * of being overwritten by the last track that happens to claim it. Showing another track's artwork
 * and title is worse than falling back to the row's stored metadata.
 */
internal fun tagCandidateIndex(entries: Iterable<Pair<Long, List<String>>>): Map<String, Long> {
    val owners = HashMap<String, Long>()
    val ambiguous = HashSet<String>()
    for ((trackId, candidates) in entries) {
        for (key in candidates) {
            if (key in ambiguous) continue
            val owner = owners[key]
            when {
                owner == null -> owners[key] = trackId
                owner != trackId -> {
                    owners.remove(key)
                    ambiguous.add(key)
                }
            }
        }
    }
    return owners
}
