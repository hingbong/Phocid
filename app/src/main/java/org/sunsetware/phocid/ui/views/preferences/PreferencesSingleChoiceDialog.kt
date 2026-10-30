package org.sunsetware.phocid.ui.views.preferences

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sunsetware.phocid.Dialog
import org.sunsetware.phocid.MainViewModel
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.ui.components.DialogBase
import org.sunsetware.phocid.ui.components.UtilityRadioButtonListItem

@Stable
class PreferencesSingleChoiceDialog<T>(
    val title: String,
    val options: List<Pair<T, String>>,
    val activeOption: (Preferences) -> T,
    val updatePreferences: (Preferences, T) -> Preferences,
) : Dialog() {
    @Composable
    override fun Compose(viewModel: MainViewModel) {
        val preferences by viewModel.preferences.collectAsStateWithLifecycle()
        DialogBase(title = title, onConfirmOrDismiss = { viewModel.uiManager.closeDialog() }) {
            LazyColumn {
                items(options) { (option, name) ->
                    UtilityRadioButtonListItem(
                        text = name,
                        selected = activeOption(preferences) == option,
                        onSelect = {
                            viewModel.updatePreferences { updatePreferences(it, option) }
                        },
                    )
                }
            }
        }
    }
}
