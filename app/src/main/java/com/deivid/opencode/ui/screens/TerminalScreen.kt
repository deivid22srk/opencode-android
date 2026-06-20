package com.deivid.opencode.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid.opencode.terminal.TerminalViewModel
import com.deivid.opencode.ui.theme.MonoTextStyle

private val TerminalBg = Color(0xFF0B1F3A)
private val TerminalFg = Color(0xFFE5E9F0)
private val TerminalAccent = Color(0xFF7BD389)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onBack: () -> Unit,
) {
    val viewModel: TerminalViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Alpine Terminal",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                if (state.alive) "Connected · proot Alpine"
                                else if (state.starting) "Starting…"
                                else "Disconnected",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.alive)
                                    TerminalAccent
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.alive) {
                        IconButton(onClick = { viewModel.sendCtrlC() }) {
                            Text("⌃C", style = MaterialTheme.typography.titleMedium)
                        }
                        IconButton(onClick = { viewModel.stopSession() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    }
                    IconButton(onClick = { viewModel.clearOutput() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // Terminal output area (fills most of the screen)
            TerminalOutput(
                output = state.output,
                alive = state.alive,
                starting = state.starting,
                error = state.error,
                onStart = viewModel::startSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            // Input line (only when alive)
            if (state.alive) {
                TerminalInputBar(
                    onSend = { cmd ->
                        viewModel.sendInput(cmd)
                        viewModel.sendEnter()
                    },
                )
            }
        }
    }
}

@Composable
private fun TerminalOutput(
    output: String,
    alive: Boolean,
    starting: Boolean,
    error: String?,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    val lines = remember(output) { output.split('\n') }

    // Auto-scroll to bottom when new output arrives.
    LaunchedEffect(output) {
        if (lines.isNotEmpty()) {
            state.animateScrollToItem(lines.lastIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = TerminalBg),
        ) {
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)) {

                if (output.isEmpty() && !alive && !starting && error == null) {
                    // First-launch empty state — show a "Connect" button.
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            tint = TerminalAccent.copy(alpha = 0.6f),
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Alpine Linux Terminal",
                            style = MaterialTheme.typography.titleLarge,
                            color = TerminalFg,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tap Connect to launch an interactive shell inside the\n" +
                                "proot'd Alpine rootfs. You'll be able to run real\n" +
                                "Linux commands like ls, apk add python3, etc.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TerminalFg.copy(alpha = 0.7f),
                            modifier = Modifier.alpha(0.85f),
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = onStart,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TerminalAccent,
                                contentColor = Color(0xFF003919),
                            ),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Connect")
                        }
                    }
                } else if (starting) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            color = TerminalAccent,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Starting proot + Alpine…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TerminalFg,
                        )
                        Text(
                            "First launch may take 10-15s to extract the rootfs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TerminalFg.copy(alpha = 0.7f),
                        )
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(lines) { line ->
                                Text(
                                    text = line,
                                    style = MonoTextStyle.copy(
                                        color = TerminalFg,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                    ),
                                )
                            }
                        }
                    }
                    if (error != null) {
                        Text(
                            "\n[error: $error]",
                            style = MonoTextStyle.copy(color = MaterialTheme.colorScheme.error),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalInputBar(
    onSend: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a command…") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            trailingIcon = {
                if (input.isNotEmpty()) {
                    IconButton(onClick = {
                        onSend(input)
                        input = ""
                    }) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
        )
        FilledTonalButton(
            onClick = {
                onSend(input)
                input = ""
            },
            enabled = input.isNotEmpty(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Run")
        }
    }
}
