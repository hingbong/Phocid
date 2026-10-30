package org.sunsetware.phocid.ui.views.statistics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.sunsetware.phocid.Dialog
import org.sunsetware.phocid.MainViewModel
import org.sunsetware.phocid.R
import org.sunsetware.phocid.globals.Strings
import org.sunsetware.phocid.stats.backup.StatsBackup
import org.sunsetware.phocid.ui.components.DialogBase
import org.sunsetware.phocid.utils.icuFormat

@Stable
class PlaybackStatisticsImportDialog(private val backup: StatsBackup) : Dialog() {
    @Composable
    override fun Compose(viewModel: MainViewModel) {
        var replace by rememberSaveable { mutableStateOf(false) }
        val trackCount = backup.totals.size
        val playCount = backup.totals.sumOf { it.totalCount.toLong() }

        DialogBase(
            title = Strings[R.string.playback_statistics_import_title],
            onConfirm = {
                viewModel.viewModelScope.launch {
                    val result = runCatching { viewModel.statsManager.restore(backup, replace) }
                    viewModel.uiManager.closeDialog()
                    result
                        .onSuccess {
                            viewModel.uiManager.toast(
                                Strings[R.string.playback_statistics_import_success]
                            )
                        }
                        .onFailure { ex ->
                            viewModel.uiManager.toast(
                                Strings[R.string.playback_statistics_import_failed].icuFormat(
                                    ex.toString()
                                )
                            )
                        }
                }
            },
            onDismiss = { viewModel.uiManager.closeDialog() },
            confirmText = Strings[R.string.commons_import],
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    Strings[R.string.playback_statistics_import_body].icuFormat(
                        trackCount,
                        playCount,
                    )
                )
                ImportModeOption(
                    selected = !replace,
                    title = Strings[R.string.playback_statistics_import_merge],
                    description = Strings[R.string.playback_statistics_import_merge_description],
                    onSelect = { replace = false },
                )
                ImportModeOption(
                    selected = replace,
                    title = Strings[R.string.playback_statistics_import_replace],
                    description = Strings[R.string.playback_statistics_import_replace_description],
                    onSelect = { replace = true },
                )
            }
        }
    }
}

@Stable
class PlaybackStatisticsClearDialog : Dialog() {
    @Composable
    override fun Compose(viewModel: MainViewModel) {
        var acknowledged by rememberSaveable { mutableStateOf(false) }
        DialogBase(
            title = Strings[R.string.playback_statistics_clear_title],
            onConfirm = {
                viewModel.viewModelScope.launch {
                    val result = runCatching { viewModel.statsManager.clear() }
                    viewModel.uiManager.closeDialog()
                    result
                        .onSuccess {
                            viewModel.uiManager.toast(
                                Strings[R.string.playback_statistics_clear_success]
                            )
                        }
                        .onFailure { ex ->
                            viewModel.uiManager.toast(
                                Strings[R.string.playback_statistics_clear_failed].icuFormat(
                                    ex.toString()
                                )
                            )
                        }
                }
            },
            onDismiss = { viewModel.uiManager.closeDialog() },
            confirmText = Strings[R.string.playback_statistics_clear_confirm],
            confirmEnabled = acknowledged,
            confirmColor = MaterialTheme.colorScheme.error,
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(Strings[R.string.playback_statistics_clear_body])
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable { acknowledged = !acknowledged }
                            .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                    )
                    Text(
                        Strings[R.string.playback_statistics_clear_acknowledge],
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
