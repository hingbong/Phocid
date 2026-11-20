package org.sunsetware.phocid.ui.views.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.sunsetware.phocid.Dialog
import org.sunsetware.phocid.MainViewModel
import org.sunsetware.phocid.R
import org.sunsetware.phocid.data.listDependencies
import org.sunsetware.phocid.globals.Strings
import org.sunsetware.phocid.ui.components.DialogBase
import org.sunsetware.phocid.ui.components.UtilityListItem

class PreferencesThirdPartyLicensesDialog : Dialog() {
    @Composable
    override fun Compose(viewModel: MainViewModel) {
        val context = LocalContext.current
        val dependencies = remember { listDependencies(context) }
        var expandedIndex by remember { mutableStateOf(null as Int?) }
        DialogBase(
            Strings[R.string.preferences_third_party_licenses],
            onConfirmOrDismiss = { viewModel.uiManager.closeDialog() },
        ) {
            // Don't use LazyColumn here unless you want a "Compose internal error" in release build
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                dependencies.forEachIndexed { index, (dependency, licenseTexts) ->
                    with(dependency) {
                        Expander(
                            project,
                            version,
                            expandedIndex == index,
                            { expandedIndex = if (expandedIndex != index) index else null },
                        ) {
                            Column(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .padding(24.dp),
                            ) {
                                Text("© " + developers.joinToString(", "))
                                if (url != null) Text(url)
                                licenseTexts.forEach { Text(it) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Expander(
        title: String,
        subtitle: String?,
        expanded: Boolean,
        onToggleExpansion: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        Column {
            UtilityListItem(
                title = title,
                subtitle = subtitle,
                modifier = Modifier.clickable(onClick = onToggleExpansion),
                actions = {
                    Icon(
                        if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            if (expanded) {
                content()
            }
        }
    }
}
