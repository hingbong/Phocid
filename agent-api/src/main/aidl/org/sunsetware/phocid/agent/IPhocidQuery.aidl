package org.sunsetware.phocid.agent;

/**
 * Read-mostly query surface for the companion agent bridge.
 *
 * Trust model: the service is exported with a signature-level permission, so only APKs signed
 * with the same certificate can bind. Callers must still validate every argument.
 *
 * The main app's capabilities() always reports writeSortKeys=true: write protection is enforced
 * on the bridge side (its opt-in "Allow agent writes" toggle), not by this interface. Read-only
 * by default therefore describes bridge behaviour, not the main-app capability flag.
 *
 * statsTracks is not a separate AIDL method: on the bridge it is an alias of statsTopTracks,
 * gated on API 6. It returns the same top-tracks payload under a different name.
 *
 * sort-key writes default to overwrite=false on the bridge; an existing value is left untouched
 * (including an attempt to clear it with sortKey=null) unless overwrite is explicitly true.
 *
 * This interface only exposes queries and local sort keys. It does not expose playback control
 * (that lives on the AppFunctions surface) and never returns filesystem paths.
 */
interface IPhocidQuery {
    String capabilities();

    String searchTracks(String requestJson);

    String getTrack(long libraryId);

    String statsSummary(String range);

    String statsTopTracks(String requestJson);

    String statsDaily(String range);

    String resolveStatsUuid(long libraryId);

    String listFacets(String requestJson);

    String playerState();

    String updateSortKeys(String requestJson);
}
