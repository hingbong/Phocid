package org.sunsetware.phocid.agent;

interface IPhocidQuery {
    String capabilities();

    String searchTracks(String requestJson);

    String getTrack(long libraryId);

    String statsSummary(String range);

    String statsTopTracks(String requestJson);

    String statsDaily(String range);

    String resolveStatsUuid(long libraryId);
}
