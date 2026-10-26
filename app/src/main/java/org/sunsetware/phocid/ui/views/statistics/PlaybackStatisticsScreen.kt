@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalSerializationApi::class)

package org.sunsetware.phocid.ui.views.statistics

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ibm.icu.text.DateFormat
import java.time.LocalDate
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.sunsetware.phocid.MainViewModel
import org.sunsetware.phocid.R
import org.sunsetware.phocid.TopLevelScreen
import org.sunsetware.phocid.UNKNOWN
import org.sunsetware.phocid.data.LibraryIndex
import org.sunsetware.phocid.data.PLAYBACK_STATISTICS_FORMAT_VERSION
import org.sunsetware.phocid.data.PlaybackStatistics
import org.sunsetware.phocid.data.PlaybackStatisticsBackup
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.data.TrackStatistics
import org.sunsetware.phocid.data.statisticsKey
import org.sunsetware.phocid.data.toBackup
import org.sunsetware.phocid.globals.Strings
import org.sunsetware.phocid.globals.format
import org.sunsetware.phocid.ui.components.Artwork
import org.sunsetware.phocid.ui.components.ArtworkImage
import org.sunsetware.phocid.ui.components.LibraryListItemHorizontal
import org.sunsetware.phocid.ui.components.OverflowMenu
import org.sunsetware.phocid.ui.views.MenuItem
import org.sunsetware.phocid.utils.icuFormat

private enum class PlaybackStatisticsSort {
    MOST_PLAYED,
    RECENTLY_PLAYED,
}

private data class RealizedPlaybackStatistics(
    val key: String,
    val statistics: TrackStatistics,
    val track: Track?,
)

@Composable
private fun PlaybackStatistics.realize(libraryIndex: LibraryIndex): List<RealizedPlaybackStatistics> {
    val tracksByKey =
        remember(libraryIndex) {
            libraryIndex.tracks.values.associateBy { it.statisticsKey() }
        }
    return remember(this, tracksByKey) {
        entries.mapNotNull { (key, statistics) ->
            if (statistics.playCount <= 0) null
            else RealizedPlaybackStatistics(key, statistics, tracksByKey[key])
        }
    }
}

object PlaybackStatisticsScreen : TopLevelScreen() {
    @Composable
    override fun Compose(viewModel: MainViewModel) {
        val context = LocalContext.current
        val preferences by viewModel.preferences.collectAsStateWithLifecycle()
        val statistics by viewModel.playbackStatistics.collectAsStateWithLifecycle()
        val libraryIndex by viewModel.libraryIndex.collectAsStateWithLifecycle()
        val uiManager = viewModel.uiManager
        val entries = statistics.realize(libraryIndex)
        var sortIndex by rememberSaveable { mutableStateOf(0) }
        val sort = PlaybackStatisticsSort.entries[sortIndex]
        val sortedEntries =
            remember(entries, sort) {
                when (sort) {
                    PlaybackStatisticsSort.MOST_PLAYED ->
                        entries.sortedWith(
                            compareByDescending<RealizedPlaybackStatistics> {
                                it.statistics.playCount
                            }.thenByDescending { it.statistics.lastPlayedAt }
                        )
                    PlaybackStatisticsSort.RECENTLY_PLAYED ->
                        entries.sortedByDescending { it.statistics.lastPlayedAt }
                }
            }
        val totalPlays = remember(entries) { entries.sumOf { it.statistics.playCount.toLong() } }
        val totalPlayedMillis =
            remember(entries) { entries.sumOf { it.statistics.totalPlayedMillis } }
        val dateFormatter = remember { DateFormat.getInstance() }

        fun exportStatistics() {
            val date = LocalDate.now().toString()
            uiManager.intentLauncher.get()?.createJsonDocument("phocid-playback-statistics-" + date + ".json") { uri ->
                if (uri == null) return@createJsonDocument
                val backup =
                    viewModel.playbackStatisticsManager.statistics.value.toBackup(
                        System.currentTimeMillis()
                    )
                viewModel.viewModelScope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openOutputStream(uri)?.use { stream ->
                                    Json {
                                            prettyPrint = true
                                            encodeDefaults = true
                                        }
                                        .encodeToStream(backup, stream)
                                } ?: error("Unable to open output stream")
                            }
                        }
                    result
                        .onSuccess {
                            uiManager.toast(
                                Strings[R.string.playback_statistics_export_success]
                            )
                        }
                        .onFailure { ex ->
                            Log.e("Phocid", "Error exporting playback statistics", ex)
                            uiManager.toast(
                                Strings[R.string.playback_statistics_export_failed].icuFormat(
                                    ex.toString()
                                )
                            )
                        }
                }
            }
        }

        fun importStatistics() {
            uiManager.intentLauncher.get()?.openJsonDocument { uri ->
                if (uri == null) return@openJsonDocument
                viewModel.viewModelScope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    Json { ignoreUnknownKeys = true }
                                        .decodeFromStream<PlaybackStatisticsBackup>(stream)
                                } ?: error("Unable to open input stream")
                            }
                        }
                    result
                        .onSuccess { backup ->
                            if (backup.formatVersion > PLAYBACK_STATISTICS_FORMAT_VERSION) {
                                uiManager.toast(
                                    Strings[R.string.playback_statistics_import_incompatible]
                                )
                            } else {
                                uiManager.openDialog(
                                    PlaybackStatisticsImportDialog(backup)
                                )
                            }
                        }
                        .onFailure { ex ->
                            Log.e("Phocid", "Error importing playback statistics", ex)
                            uiManager.toast(
                                Strings[R.string.playback_statistics_import_failed].icuFormat(
                                    ex.toString()
                                )
                            )
                        }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(Strings[R.string.playback_statistics]) },
                    navigationIcon = {
                        IconButton(onClick = { uiManager.back() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = Strings[R.string.commons_back],
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { exportStatistics() }) {
                            Icon(
                                Icons.Filled.Save,
                                contentDescription =
                                    Strings[R.string.playback_statistics_export],
                            )
                        }
                        IconButton(onClick = { importStatistics() }) {
                            Icon(
                                Icons.Filled.UploadFile,
                                contentDescription =
                                    Strings[R.string.playback_statistics_import],
                            )
                        }
                        if (statistics.entries.isNotEmpty()) {
                            OverflowMenu(
                                listOf(
                                    MenuItem.Button(
                                        Strings[R.string.playback_statistics_clear],
                                        Icons.Filled.DeleteSweep,
                                        dangerous = true,
                                    ) {
                                        uiManager.openDialog(PlaybackStatisticsClearDialog())
                                    }
                                )
                            )
                        }
                    },
                )
            }
        ) { scaffoldPadding ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
                color = MaterialTheme.colorScheme.background,
            ) {
                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            Strings[R.string.playback_statistics_empty],
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            PlaybackStatisticsSummary(
                                totalPlays = totalPlays,
                                trackCount = entries.size,
                                totalPlayedMillis = totalPlayedMillis,
                            )
                        }
                        item {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            ) {
                                SegmentedButton(
                                    selected = sort == PlaybackStatisticsSort.MOST_PLAYED,
                                    onClick = { sortIndex = 0 },
                                    shape =
                                        SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                ) {
                                    Text(Strings[R.string.playback_statistics_sort_most_played])
                                }
                                SegmentedButton(
                                    selected = sort == PlaybackStatisticsSort.RECENTLY_PLAYED,
                                    onClick = { sortIndex = 1 },
                                    shape =
                                        SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                ) {
                                    Text(Strings[R.string.playback_statistics_sort_recent])
                                }
                            }
                        }
                        items(sortedEntries, key = { it.key }) { entry ->
                            PlaybackStatisticsListItem(entry, preferences, dateFormatter)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PlaybackStatisticsSummary(
        totalPlays: Long,
        trackCount: Int,
        totalPlayedMillis: Long,
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                PlaybackStatisticsSummaryItem(
                    label = Strings[R.string.playback_statistics_total_plays],
                    value = totalPlays.toString(),
                    modifier = Modifier.weight(1f),
                )
                PlaybackStatisticsSummaryItem(
                    label = Strings[R.string.playback_statistics_total_tracks],
                    value = trackCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                PlaybackStatisticsSummaryItem(
                    label = Strings[R.string.playback_statistics_listening_time],
                    value = totalPlayedMillis.milliseconds.format(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    @Composable
    private fun PlaybackStatisticsSummaryItem(
        label: String,
        value: String,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    @Composable
    private fun PlaybackStatisticsListItem(
        entry: RealizedPlaybackStatistics,
        preferences: Preferences,
        dateFormatter: DateFormat,
    ) {
        val statistics = entry.statistics
        val track = entry.track
        val artistOrAlbum =
            if (track != null) {
                track.displayArtistWithAlbum
            } else {
                Strings.separate(
                    if (statistics.artists.isEmpty()) null
                    else Strings.conjoin(statistics.artists),
                    statistics.album,
                )
            }
        val lastPlayed =
            if (statistics.lastPlayedAt > 0)
                Strings[R.string.playback_statistics_last_played].icuFormat(
                    dateFormatter.format(Date(statistics.lastPlayedAt))
                )
            else null
        val unavailable =
            if (track == null) Strings[R.string.playback_statistics_not_in_library] else null
        val subtitle =
            Strings.separate(artistOrAlbum.takeIf { it.isNotEmpty() }, lastPlayed, unavailable)

        LibraryListItemHorizontal(
            title = track?.displayTitle ?: statistics.title.ifBlank { UNKNOWN },
            subtitle = subtitle,
            lead = {
                ArtworkImage(
                    artwork =
                        track?.let { Artwork.Track(it) }
                            ?: Artwork.Icon(
                                Icons.Outlined.MusicNote,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    artworkColorPreference = preferences.artworkColorPreference,
                    shape = preferences.shapePreference.artworkShape,
                    highRes = preferences.highResArtworkPreference.small,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            actions = {
                Text(
                    Strings[R.string.playback_statistics_play_count].icuFormat(
                        statistics.playCount
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
            deemphasized = track == null,
        )
    }
}
