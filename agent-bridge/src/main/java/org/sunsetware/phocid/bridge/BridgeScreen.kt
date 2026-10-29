package org.sunsetware.phocid.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.launch

@Composable
fun BridgeScreen() {
    val context = LocalContext.current
    val state by BridgeService.state.collectAsStateWithLifecycle()
    var savedConfig by remember { mutableStateOf(BridgeSettings.load(context)) }
    var hostInput by rememberSaveable { mutableStateOf(savedConfig.host) }
    var portInput by rememberSaveable { mutableStateOf(savedConfig.port.toString()) }
    var allowWritesInput by rememberSaveable { mutableStateOf(savedConfig.allowWrites) }
    var token by remember { mutableStateOf(TokenStore.getOrCreate(context)) }
    var showRegenerateDialog by remember { mutableStateOf(false) }
    var batteryUnrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()

    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            batteryUnrestricted = isBatteryUnrestricted(context)
        }
    }

    val hostError = BridgeEndpoint.hostError(hostInput)
    val portError = BridgeEndpoint.portError(portInput)
    val normalizedHost = BridgeEndpoint.normalizeHost(hostInput)
    val parsedPort = portInput.trim().toIntOrNull()
    val canSubmit = hostError == null && portError == null
    val draftConfig =
        if (canSubmit) BridgeConfig(normalizedHost, parsedPort!!, allowWritesInput) else null
    val dirty = draftConfig != null && draftConfig != savedConfig
    val displayedConfig =
        if (state.running) BridgeConfig(state.host, state.port, savedConfig.allowWrites)
        else savedConfig
    val endpoint = BridgeEndpoint.httpUrl(displayedConfig.host, displayedConfig.port)

    fun copy(label: String, value: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, value))
        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
    }

    fun startServer() {
        val config = draftConfig ?: return
        BridgeSettings.save(context, config)
        savedConfig = config
        hostInput = config.host
        portInput = config.port.toString()
        BridgeService.start(context)
    }

    fun restartServer() {
        val config = draftConfig ?: return
        BridgeSettings.save(context, config)
        savedConfig = config
        hostInput = config.host
        portInput = config.port.toString()
        BridgeService.restart(context)
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            StatusCard(
                state = state,
                endpoint = endpoint,
                displayedConfig = displayedConfig,
                onCopyUrl = { copy("Phocid MCP URL", endpoint) },
            )
            ServerCard(
                state = state,
                hostInput = hostInput,
                portInput = portInput,
                hostError = hostError,
                portError = portError,
                dirty = dirty,
                allowWrites = allowWritesInput,
                onHostChange = { hostInput = it },
                onPortChange = { portInput = it },
                onAllowWritesChange = { allowWritesInput = it },
                onStart = { startServer() },
                onStop = { BridgeService.stop(context) },
                onRestart = { restartServer() },
            )
            TokenCard(
                token = token,
                onCopy = { copy("Phocid MCP token", token) },
                onRegenerate = { showRegenerateDialog = true },
            )
            BatteryCard(
                unrestricted = batteryUnrestricted,
                onOpenSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    }.onFailure {
                        scope.launch {
                            snackbarHostState.showSnackbar("Could not open battery settings")
                        }
                    }
                },
            )
            ConnectCard(endpoint = endpoint, config = displayedConfig)
            AboutCard()
            Text(
                text =
                    "Read-only by default; optional local sort-key writes. " +
                        "Audio files, tags, and playback are never modified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }

    if (showRegenerateDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateDialog = false },
            title = { Text("Generate a new token?") },
            text = {
                Text(
                    "OpenMinis and any other MCP client using the current token will stop " +
                        "working until you update them."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        token = TokenStore.regenerate(context)
                        showRegenerateDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "Token regenerated; update your MCP client"
                            )
                        }
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text("Generate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Header() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFF0B2C2A),
            modifier = Modifier.size(56.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_bridge_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = "Phocid Agent Bridge",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME} · Local MCP server",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(
    state: BridgeState,
    endpoint: String,
    displayedConfig: BridgeConfig,
    onCopyUrl: () -> Unit,
) {
    val container =
        when {
            state.error != null -> MaterialTheme.colorScheme.errorContainer
            state.starting -> MaterialTheme.colorScheme.secondaryContainer
            state.running -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val content =
        when {
            state.error != null -> MaterialTheme.colorScheme.onErrorContainer
            state.starting -> MaterialTheme.colorScheme.onSecondaryContainer
            state.running -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
    val statusText =
        when {
            state.starting -> "Starting…"
            state.running -> "Running"
            state.error != null -> "Error"
            else -> "Stopped"
        }
    val dotColor =
        when {
            state.error != null -> MaterialTheme.colorScheme.error
            state.starting -> MaterialTheme.colorScheme.tertiary
            state.running -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        }

    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "MCP endpoint",
                style = MaterialTheme.typography.labelMedium,
                color = content.copy(alpha = 0.75f),
            )
            SelectionContainer {
                Text(
                    text = endpoint,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
            }
            if (BridgeEndpoint.isWildcard(displayedConfig.host)) {
                Text(
                    text =
                        "Listening on all interfaces. Other devices can connect using this " +
                            "phone's LAN IP; keep the token secret.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                )
            }
            Button(onClick = onCopyUrl) { Text("Copy URL") }
        }
    }
}

@Composable
private fun ServerCard(
    state: BridgeState,
    hostInput: String,
    portInput: String,
    hostError: String?,
    portError: String?,
    dirty: Boolean,
    allowWrites: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onAllowWritesChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Server",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = hostInput,
                onValueChange = onHostChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Listen address") },
                placeholder = { Text("127.0.0.1") },
                singleLine = true,
                isError = hostError != null,
                supportingText = {
                    Text(
                        hostError
                            ?: "IPv4 or IPv6. 127.0.0.1 = this device only; " +
                                "0.0.0.0 = all IPv4 interfaces."
                    )
                },
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
                value = portInput,
                onValueChange = onPortChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Port") },
                placeholder = { Text("8765") },
                singleLine = true,
                isError = portError != null,
                supportingText = { Text(portError ?: "1024-65535. Default 8765.") },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                shape = RoundedCornerShape(16.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Allow agent writes",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Only local sort keys. Audio files and tags are never modified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = allowWrites, onCheckedChange = onAllowWritesChange)
            }
            if (BridgeEndpoint.isWildcard(BridgeEndpoint.normalizeHost(hostInput))) {
                Text(
                    text =
                        "This address is reachable from other devices on the network. " +
                            "Use it only on trusted networks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.running || state.starting) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
                    if (dirty) {
                        Button(
                            onClick = onRestart,
                            enabled = hostError == null && portError == null && !state.starting,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Apply & restart")
                        }
                    }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = hostError == null && portError == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Start server")
                    }
                }
            }
            Text(
                text =
                    if (state.running) {
                        "Changing the address or port restarts the server and disconnects " +
                            "current clients."
                    } else {
                        "Starting saves these settings for next time."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TokenCard(
    token: String,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Bearer token",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRegenerate) { Text("New token") }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = token,
                        modifier = Modifier.padding(12.dp),
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                    )
                }
            }
            OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                Text("Copy token")
            }
            Text(
                text =
                    "Required in the Authorization header. Regenerating invalidates " +
                        "current clients.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BatteryCard(
    unrestricted: Boolean,
    onOpenSettings: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Background reliability",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    if (unrestricted) {
                        "Battery optimization is unrestricted."
                    } else {
                        "Optimized — Android may freeze the bridge in the background."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (unrestricted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onOpenSettings) { Text("Battery settings") }
        }
    }
}

@Composable
private fun ConnectCard(
    endpoint: String,
    config: BridgeConfig,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Connect OpenMinis",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Add MCP server → HTTP",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "URL",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = endpoint,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Text(
                text = "Header: Authorization: Bearer <token>",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Authorization: None",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (BridgeEndpoint.isWildcard(config.host)) {
                Text(
                    text = "From another device use http://<phone-ip>:${config.port}/mcp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            InfoRow("Version", BuildConfig.VERSION_NAME)
            InfoRow("Built", BuildConfig.BUILD_TIME)
            InfoRow("Commit", BuildConfig.BUILD_COMMIT.take(12), monospace = true)
            InfoRow("Package", BuildConfig.APPLICATION_ID, monospace = true)
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        SelectionContainer(modifier = Modifier.weight(2f)) {
            Text(
                text = value,
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
                    ),
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun isBatteryUnrestricted(context: Context): Boolean =
    context
        .getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)
