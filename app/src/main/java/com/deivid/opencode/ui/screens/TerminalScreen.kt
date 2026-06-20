package com.deivid.opencode.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

// ReTerminal-inspired color scheme
private val TermBg = Color(0xFF000000)       // pure black background
private val TermFg = Color(0xFF00FF00)       // terminal green
private val TermFgDim = Color(0xFF00AA00)    // dimmer green for secondary text
private val TermAccent = Color(0xFF00FF00)
private val TermWhite = Color(0xFFE0E0E0)
private val TermYellow = Color(0xFFFFFF00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onBack: () -> Unit,
) {
    val viewModel: TerminalViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = TermBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            tint = TermAccent,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Alpine Terminal",
                                style = MaterialTheme.typography.titleLarge,
                                color = TermWhite,
                            )
                            Text(
                                if (state.alive) "● Connected"
                                else if (state.starting) "○ Starting…"
                                else "○ Disconnected",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.alive) TermAccent else TermFgDim,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TermWhite,
                        )
                    }
                },
                actions = {
                    if (state.alive) {
                        IconButton(onClick = { viewModel.sendCtrlC() }) {
                            Text(
                                "^C",
                                style = MaterialTheme.typography.titleMedium,
                                color = TermYellow,
                            )
                        }
                        IconButton(onClick = { viewModel.stopSession() }) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = Color(0xFFFF5555),
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.clearOutput() }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = TermWhite,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A),
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TermBg)
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
            .padding(4.dp),
    ) {
        // Terminal card — pure black with green text, ReTerminal style
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = TermBg),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                Color(0xFF333333),
            ),
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
                        Text(
                            "┌─────────────────────────────────┐",
                            color = TermFg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        )
                        Text(
                            "│     Alpine Linux Terminal       │",
                            color = TermFg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        )
                        Text(
                            "│       (proot container)          │",
                            color = TermFgDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        )
                        Text(
                            "└─────────────────────────────────┘",
                            color = TermFg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Tap Connect to launch an interactive shell",
                            color = TermFgDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Available: ls, cd, apk update, apk add python3, ...",
                            color = TermFgDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = onStart,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TermAccent,
                                contentColor = Color.Black,
                            ),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Connect",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            )
                        }
                    }
                } else if (starting) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            color = TermAccent,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Starting proot + Alpine…",
                            color = TermFg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                        Text(
                            "First launch may take 10-15s",
                            color = TermFgDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
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
                                    color = TermFg,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 17.sp,
                                )
                            }
                        }
                    }
                    if (error != null) {
                        Text(
                            "\n[error: $error]",
                            style = MonoTextStyle.copy(color = Color(0xFFFF5555)),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
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
            .background(Color(0xFF1A1A1A))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Prompt symbol
        Text(
            "$ ",
            color = TermAccent,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    "type a command…",
                    color = TermFgDim,
                    fontFamily = FontFamily.Monospace,
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = TermFg,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TermAccent,
                unfocusedBorderColor = Color(0xFF333333),
                cursorColor = TermAccent,
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
                            tint = TermAccent,
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
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = Color(0xFF1A3A1A),
                contentColor = TermAccent,
            ),
        ) {
            Text(
                "Run",
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
