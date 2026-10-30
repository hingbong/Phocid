package org.sunsetware.phocid.ui.views.statistics

/**
 * Builds a tag-key candidate to owning track id index for the statistics list.
 *
 * [org.sunsetware.phocid.stats.fingerprint.TagKey.candidates] returns more than one key per track
 * when the duration sits near a bucket boundary. A key can be claimed by more than one distinct
 * track id, so every owner is kept instead of dropping the key: the statistics row has to inspect
 * all of its candidate keys together before deciding whether its track is unambiguous. Showing
 * another track's artwork and title is worse than falling back to the row's stored metadata.
 */
internal fun tagCandidateIndex(
    entries: Iterable<Pair<Long, List<String>>>
): Map<String, Set<Long>> {
    val owners = HashMap<String, MutableSet<Long>>()
    for ((trackId, candidates) in entries) {
        for (key in candidates) {
            owners.getOrPut(key) { HashSet(1) }.add(trackId)
        }
    }
    return owners
}

/**
 * Resolves the track id owning [candidates] only when their combined owners are unambiguous.
 *
 * Different tracks can match different candidate keys of the same statistics row, so looking at a
 * single key can select the wrong track. The owner sets of all candidate keys are unioned and an id
 * is returned only when exactly one distinct id owns at least one candidate. Otherwise the caller
 * should fall back to the row's stored metadata.
 */
internal fun uniqueTagCandidateOwner(
    candidates: Iterable<String>,
    index: Map<String, Set<Long>>,
): Long? {
    var owner: Long? = null
    for (key in candidates) {
        val keyOwners = index[key] ?: continue
        for (trackId in keyOwners) {
            if (owner == null) {
                owner = trackId
            } else if (owner != trackId) {
                return null
            }
        }
    }
    return owner
}
