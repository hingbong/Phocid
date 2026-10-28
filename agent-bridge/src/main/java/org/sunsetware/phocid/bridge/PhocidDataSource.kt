package org.sunsetware.phocid.bridge

/** The narrow data surface the MCP server needs from the main Phocid app. */
interface PhocidDataSource {
    fun capabilities(): String
    fun searchTracks(requestJson: String): String
    fun getTrack(libraryId: Long): String
    fun statsSummary(range: String): String
    fun statsTopTracks(requestJson: String): String
    fun statsDaily(range: String): String
    fun resolveStatsUuid(libraryId: Long): String
}
