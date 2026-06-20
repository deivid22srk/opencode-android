package com.deivid.opencode.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid.opencode.viewmodel.ServerStatus
import com.deivid.opencode.viewmodel.ServerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: ServerViewModel,
    contentPadding: PaddingValues,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Persist permission so we can re-open later if needed
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importRelease(uri)
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            scope.launch { snackbarHostState.showSnackbar(msg) }
        }
    }

    LaunchedEffect(state.importMessage) {
        state.importMessage?.let { msg ->
            scope.launch { snackbarHostState.showSnackbar(msg) }
        }
    }

    val scrollState = rememberScrollState()
    val barState = rememberTopAppBarState()
    val collapsedFraction = barState.collapsedFraction

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            "OpenCode",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Server on Android",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                state = barState,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Hero status card
            HeroCard(state = state)

            // Import / binary section
            BinarySection(
                state = state,
                onPickFile = {
                    pickFile.launch(arrayOf(
                        "application/gzip",
                        "application/x-gzip",
                        "application/x-tar",
                        "application/zip",
                        "application/octet-stream",
                    ))
                },
                onDelete = viewModel::deleteBinary,
            )

            // Settings + start/stop
            SettingsSection(
                state = state,
                onPortChange = viewModel::updatePort,
                onHostnameChange = viewModel::updateHostname,
                onPasswordChange = viewModel::updatePassword,
                onStart = { viewModel.startServer(context) },
                onStop = { viewModel.stopServer(context) },
            )

            // URL bar (visible when running)
            AnimatedVisibility(
                visible = state.url != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                UrlCard(
                    url = state.url ?: "",
                    onCopy = {
                        clipboard.setText(AnnotatedString(state.url ?: ""))
                        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                    },
                    onOpen = {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.url))
                            context.startActivity(intent)
                        }.onFailure {
                            scope.launch { snackbarHostState.showSnackbar("No browser app installed") }
                        }
                    },
                )
            }

            // Console
            ConsoleSection(
                state = state,
                onClear = viewModel::clearLogs,
                onShare = {
                    val log = state.logs
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, log)
                    }
                    context.startActivity(Intent.createChooser(send, "Share opencode log"))
                },
            )

            // Help card
            HelpCard()

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HeroCard(state: com.deivid.opencode.viewmodel.ServerState) {
    val running = state.status == ServerStatus.RUNNING
    val accent = if (running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(
        targetValue = if (state.status == ServerStatus.STARTING) 1.05f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "hero-scale",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                )
                .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = if (running) "Server listening"
                            else if (state.status == ServerStatus.STARTING) "Starting…"
                            else if (state.status == ServerStatus.ERROR) "Server not running"
                            else "Ready to launch",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "localhost AI coding agent",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (running && state.url != null) {
                    Text(
                        text = state.url ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("ARM64 musl") },
                        icon = { Icon(Icons.Outlined.Sensors, null, Modifier.size(16.dp)) },
                    )
                    SuggestionChip(
                        onClick = {},
                        label = { Text(state.binary?.let { "${it.sizeBytes / 1_000_000} MB" } ?: "no binary") },
                    )
                }
            }
        }
    }
}

@Composable
private fun BinarySection(
    state: com.deivid.opencode.viewmodel.ServerState,
    onPickFile: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard(title = "Binary") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Import the opencode ARM64 release you downloaded from GitHub.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.binary != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.FileOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "opencode (ARM64)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                "${state.binary.sizeBytes / 1_000_000} MB • imported",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete binary",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onPickFile,
                enabled = !state.importBusy,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (state.importBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Importing…")
                } else {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.binary == null) "Import release file" else "Replace release")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSection(
    state: com.deivid.opencode.viewmodel.ServerState,
    onPortChange: (Int) -> Unit,
    onHostnameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val running = state.status == ServerStatus.RUNNING ||
        state.status == ServerStatus.STARTING
    val canStart = state.binary != null && !running

    SectionCard(title = "Server settings") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.port.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let(onPortChange) },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !running,
                )
                OutlinedTextField(
                    value = state.hostname,
                    onValueChange = onHostnameChange,
                    label = { Text("Hostname") },
                    modifier = Modifier.weight(1.4f),
                    singleLine = true,
                    enabled = !running,
                )
            }
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text("Server password (optional)") },
                supportingText = { Text("Secures the HTTP API — recommended") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !running,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )

            Spacer(Modifier.height(4.dp))

            if (running) {
                FilledTonalButton(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop server", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.status == ServerStatus.STARTING) "Starting…" else "Start server")
                }
                if (state.binary == null) {
                    Text(
                        "Import the opencode binary first to enable start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun UrlCard(
    url: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    SectionCard(title = "Local URL") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = url,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCopy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy")
                }
                Button(
                    onClick = onOpen,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open")
                }
            }
        }
    }
}

@Composable
private fun ConsoleSection(
    state: com.deivid.opencode.viewmodel.ServerState,
    onClear: () -> Unit,
    onShare: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Console",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 480.dp),
        ) {
            com.deivid.opencode.ui.components.LogViewer(
                log = state.logs,
                onClear = onClear,
                onShare = onShare,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun HelpCard() {
    SectionCard(title = "Where to download opencode") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "On your phone, open:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "github.com/anomalyco/opencode/releases",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Then download one of these ARM64 files:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "• opencode-linux-arm64-musl.tar.gz (recommended)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "• opencode-linux-arm64.tar.gz",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Come back here and tap “Import release file”. The app extracts the binary, bundles the musl runtime it needs, and runs `opencode serve` on the port you chose.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}
