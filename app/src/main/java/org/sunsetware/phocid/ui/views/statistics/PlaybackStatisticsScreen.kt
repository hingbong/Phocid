@file:OptIn(ExperimentalMaterial3Api::class)

package org.sunsetware.phocid.ui.views.statistics

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableIntStateOf
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
import android.icu.text.DateFormat
import java.time.LocalDate
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sunsetware.phocid.MainViewModel
import org.sunsetware.phocid.R
import org.sunsetware.phocid.TopLevelScreen
import org.sunsetware.phocid.UNKNOWN
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.globals.Strings
import org.sunsetware.phocid.globals.format
import org.sunsetware.phocid.stats.db.RangeSummary
import org.sunsetware.phocid.stats.db.TrackAggregate
import org.sunsetware.phocid.stats.domain.StatsRange
import org.sunsetware.phocid.stats.fingerprint.LocalUriKey
import org.sunsetware.phocid.stats.fingerprint.TagKey
import org.sunsetware.phocid.stats.domain.StatsSort
import org.sunsetware.phocid.ui.components.Artwork
import org.sunsetware.phocid.ui.components.ArtworkImage
import org.sunsetware.phocid.ui.components.LibraryListItemHorizontal
import org.sunsetware.phocid.ui.components.OverflowMenu
import org.sunsetware.phocid.ui.views.MenuItem
import org.sunsetware.phocid.utils.icuFormat

private fun StatsRange.labelRes(): Int =
    when (this) {
        StatsRange.DAYS_7 -> R.string.playback_statistics_range_7d
        StatsRange.DAYS_30 -> R.string.playback_statistics_range_30d
        StatsRange.CALENDAR_MONTH -> R.string.playback_statistics_range_month
        StatsRange.DAYS_90 -> R.string.playback_statistics_range_90d
        StatsRange.DAYS_365 -> R.string.playback_statistics_range_365d
        StatsRange.CALENDAR_YEAR -> R.string.playback_statistics_range_year
        StatsRange.YEARS_3 -> R.string.playback_statistics_range_3y
        StatsRange.ALL -> R.string.playback_statistics_range_all
    }

private fun StatsSort.labelRes(): Int =
    when (this) {
        StatsSort.PLAYS -> R.string.playback_statistics_sort_most_played
        StatsSort.TIME -> R.string.playback_statistics_sort_most_time
    }

object PlaybackStatisticsScreen : TopLevelScreen() {
    @Composable
    override fun Compose(viewModel: MainViewModel) {
        val context = LocalContext.current
        val preferences by viewModel.preferences.collectAsStateWithLifecycle()
        val libraryIndex by viewModel.libraryIndex.collectAsStateWithLifecycle()
        val uiManager = viewModel.uiManager
        val statsManager = viewModel.statsManager
        var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
        var sortIndex by rememberSaveable { mutableIntStateOf(0) }
        val range = StatsRange.entries[rangeIndex]
        val sort = StatsSort.entries[sortIndex]

        val entries by
            remember(statsManager, range, sort) {
                    statsManager.repository.topTracks(range, sort)
                }
                .collectAsStateWithLifecycle(emptyList<TrackAggregate>())
        val summary by
            remember(statsManager, range) { statsManager.repository.summary(range) }
                .collectAsStateWithLifecycle(RangeSummary(0, 0, 0, 0))
        val tracksByPath =
            remember(libraryIndex) { libraryIndex.tracks.values.associateBy { it.path } }
        val tracksByLocalUri =
            remember(libraryIndex) {
                libraryIndex.tracks.values.associateBy {
                    LocalUriKey.of(it.uri.toString(), it.size)
                }
            }
        val tracksByTagKey =
            remember(libraryIndex) {
                buildMap {
                    libraryIndex.tracks.values.forEach { track ->
                        TagKey.of(
                                track.artists.joinToString(", "),
                                track.album,
                                track.title ?: track.fileName.substringBeforeLast('.'),
                                track.duration.inWholeMilliseconds,
                            )
                            ?.let { put(it, track) }
                    }
                }
            }
        val dateFormatter = remember { DateFormat.getInstance() }

        fun exportStatistics() {
            val date = LocalDate.now().toString()
            uiManager.intentLauncher.get()?.createBinaryDocument(
                "phocid-playback-statistics-$date.mstats"
            ) { uri ->
                if (uri == null) return@createBinaryDocument
                viewModel.viewModelScope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openOutputStream(uri)?.use { stream ->
                                    statsManager.backupManager.export(stream)
                                } ?: error("Unable to open output stream")
                            }
                        }
                    result
                        .onSuccess {
                            uiManager.toast(Strings[R.string.playback_statistics_export_success])
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
            uiManager.intentLauncher.get()?.openBinaryDocument { uri ->
                if (uri == null) return@openBinaryDocument
                viewModel.viewModelScope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    statsManager.backupManager.read(stream)
                                } ?: error("Unable to open input stream")
                            }
                        }
                    result
                        .onSuccess { backup ->
                            uiManager.openDialog(PlaybackStatisticsImportDialog(backup))
                        }
                        .onFailure { ex ->
                            Log.e("Phocid", "Error reading playback statistics", ex)
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
                        OverflowMenu(
                            buildList {
                                add(
                                    MenuItem.Button(
                                        Strings[R.string.playback_statistics_import],
                                        Icons.Filled.UploadFile,
                                    ) {
                                        importStatistics()
                                    }
                                )
                                if (summary.plays > 0) {
                                    add(MenuItem.Divider)
                                    add(
                                        MenuItem.Button(
                                            Strings[R.string.playback_statistics_clear],
                                            Icons.Filled.DeleteSweep,
                                            dangerous = true,
                                        ) {
                                            uiManager.openDialog(
                                                PlaybackStatisticsClearDialog()
                                            )
                                        }
                                    )
                                }
                            }
                        )
                    },
                )
            }
        ) { scaffoldPadding ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
                color = MaterialTheme.colorScheme.background,
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        PlaybackStatisticsSummary(
                            totalPlays = summary.plays.toLong(),
                            trackCount = summary.uniqueTracks,
                            totalPlayedMillis = summary.millis,
                        )
                    }
                    item {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(StatsRange.entries) { index, item ->
                                FilterChip(
                                    selected = range == item,
                                    onClick = { rangeIndex = index },
                                    label = { Text(Strings[item.labelRes()]) },
                                )
                            }
                        }
                    }
                    item {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            StatsSort.entries.forEachIndexed { index, item ->
                                SegmentedButton(
                                    selected = sort == item,
                                    onClick = { sortIndex = index },
                                    shape =
                                        SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = StatsSort.entries.size,
                                        ),
                                ) {
                                    Text(Strings[item.labelRes()])
                                }
                            }
                        }
                    }
                    if (entries.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    Strings[R.string.playback_statistics_empty],
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(entries, key = { it.trackRowId }) { entry ->
                            val track =
                                entry.localUriKey?.let { tracksByLocalUri[it] }
                                    ?: tracksByPath[entry.path]
                                    ?: TagKey.of(
                                            entry.artist,
                                            entry.album,
                                            entry.title,
                                            entry.durationMillis,
                                        )
                                        ?.let { tracksByTagKey[it] }
                            PlaybackStatisticsListItem(
                                entry = entry,
                                track = track,
                                preferences = preferences,
                                dateFormatter = dateFormatter,
                            )
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
        entry: TrackAggregate,
        track: Track?,
        preferences: Preferences,
        dateFormatter: DateFormat,
    ) {
        val artistOrAlbum =
            if (track != null) {
                track.displayArtistWithAlbum
            } else {
                Strings.separate(
                    entry.artist.takeIf { it.isNotBlank() },
                    entry.album.takeIf { it.isNotBlank() },
                )
            }
        val lastPlayed =
            if (entry.lastPlayedAt > 0)
                Strings[R.string.playback_statistics_last_played].icuFormat(
                    dateFormatter.format(Date(entry.lastPlayedAt))
                )
            else null
        val unavailable =
            if (track == null) Strings[R.string.playback_statistics_not_in_library] else null
        val subtitle =
            Strings.separate(artistOrAlbum.takeIf { it.isNotEmpty() }, lastPlayed, unavailable)

        LibraryListItemHorizontal(
            title = track?.displayTitle ?: entry.title.ifBlank { UNKNOWN },
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
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Strings[R.string.playback_statistics_play_count].icuFormat(entry.plays),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        entry.millis.milliseconds.format(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            deemphasized = track == null,
        )
    }
}
