package org.sunsetware.phocid.ui.views.playlist

import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sunsetware.phocid.Dialog
import org.sunsetware.phocid.MainViewModel
import org.sunsetware.phocid.R
import org.sunsetware.phocid.data.PlaylistIoSettings
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.globals.Strings
import org.sunsetware.phocid.ui.components.DialogBase
import org.sunsetware.phocid.ui.components.UtilitySwitchListItem
import org.sunsetware.phocid.utils.icuFormat

@Stable
class PlaylistIoSettingsDialog() :
    PlaylistIoSettingsDialogBase(
        R.string.preferences_playlist_io_settings,
        { it.playlistIoSettings },
        { preferences, settings -> preferences.copy(playlistIoSettings = settings) },
    )

@Stable
class PlaylistIoSyncSettingsDialog() :
    PlaylistIoSettingsDialogBase(
        R.string.playlist_io_sync_settings,
        { it.playlistIoSyncSettings },
        { preferences, settings -> preferences.copy(playlistIoSyncSettings = settings) },
    )

@Stable
sealed class PlaylistIoSettingsDialogBase(
    protected val titleId: Int,
    protected val settingsSelector: (Preferences) -> PlaylistIoSettings,
    protected val preferencesTransform: (Preferences, PlaylistIoSettings) -> Preferences,
) : Dialog() {
    @Composable
    override fun Compose(viewModel: MainViewModel) {
        val preferences by viewModel.preferences.collectAsStateWithLifecycle()
        val playlistIoSettings = settingsSelector(preferences)
        val context = LocalContext.current
        val defaultMusicDirectory =
            remember(context) {
                context
                    .getSystemService(StorageManager::class.java)
                    .primaryStorageVolume
                    .directory
                    ?.resolve(Environment.DIRECTORY_MUSIC)
            }
        var relativeBaseBuffer by remember { mutableStateOf(playlistIoSettings.relativeBase) }
        LaunchedEffect(relativeBaseBuffer) {
            viewModel.updatePreferences {
                preferencesTransform(
                    it,
                    settingsSelector(it).copy(relativeBase = relativeBaseBuffer),
                )
            }
        }

        DialogBase(
            title = Strings[titleId],
            onConfirmOrDismiss = { viewModel.uiManager.closeDialog() },
        ) {
            Column {
                UtilitySwitchListItem(
                    Strings[R.string.preferences_playlist_io_settings_ignore_case],
                    playlistIoSettings.ignoreCase,
                    { checked ->
                        viewModel.updatePreferences {
                            preferencesTransform(
                                it,
                                settingsSelector(it).copy(ignoreCase = checked),
                            )
                        }
                    },
                )
                UtilitySwitchListItem(
                    Strings[R.string.preferences_playlist_io_settings_ignore_location],
                    playlistIoSettings.ignoreLocation,
                    { checked ->
                        viewModel.updatePreferences {
                            preferencesTransform(
                                it,
                                settingsSelector(it).copy(ignoreLocation = checked),
                            )
                        }
                    },
                )
                UtilitySwitchListItem(
                    Strings[R.string.preferences_playlist_io_settings_remove_invalid],
                    playlistIoSettings.removeInvalid,
                    { checked ->
                        viewModel.updatePreferences {
                            preferencesTransform(
                                it,
                                settingsSelector(it).copy(removeInvalid = checked),
                            )
                        }
                    },
                )
                UtilitySwitchListItem(
                    Strings[R.string.preferences_playlist_io_settings_export_relative],
                    playlistIoSettings.exportRelative,
                    { checked ->
                        viewModel.updatePreferences {
                            preferencesTransform(
                                it,
                                settingsSelector(it).copy(exportRelative = checked),
                            )
                        }
                    },
                )
                TextField(
                    modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
                    value = relativeBaseBuffer,
                    onValueChange = { relativeBaseBuffer = it },
                    label = {
                        Text(
                            Strings[R.string.preferences_playlist_io_settings_export_relative_base]
                        )
                    },
                    supportingText = {
                        Text(
                            Strings[
                                    R.string
                                        .preferences_playlist_io_settings_export_relative_base_hint]
                                .icuFormat(defaultMusicDirectory ?: Environment.DIRECTORY_MUSIC)
                        )
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                )
            }
        }
    }
}
