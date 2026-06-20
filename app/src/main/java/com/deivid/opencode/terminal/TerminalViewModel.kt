package com.deivid.opencode.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class TerminalState(
    val output: String = "",
    val alive: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
)

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val session = TerminalSession(app)
    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val outputBuffer = StringBuilder()

    init {
        // Subscribe to terminal output and append to our buffer.
        viewModelScope.launch {
            session.output.collectLatest { chunk ->
                synchronized(outputBuffer) {
                    outputBuffer.append(chunk)
                    // Cap the buffer at ~64 KB to avoid unbounded memory growth.
                    if (outputBuffer.length > 65_536) {
                        outputBuffer.delete(0, outputBuffer.length - 65_536)
                    }
                }
                _state.value = _state.value.copy(
                    output = synchronized(outputBuffer) { outputBuffer.toString() },
                )
            }
        }
    }

    fun startSession() {
        if (_state.value.alive || _state.value.starting) return
        _state.value = _state.value.copy(starting = true, error = null)
        viewModelScope.launch {
            session.start()
                .onSuccess {
                    _state.value = _state.value.copy(
                        starting = false,
                        alive = true,
                        error = null,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        starting = false,
                        alive = false,
                        error = e.message ?: e.toString(),
                    )
                }
        }
    }

    fun sendInput(text: String) {
        if (session.write(text)) {
            // Echo the user's input back to the terminal display so they
            // can see what they typed. The shell itself will also echo it
            // (unless stty -echo is in effect), so we don't need to do this
            // — but it makes the UX feel more responsive.
        }
    }

    fun sendEnter() {
        sendInput("\r")
    }

    fun sendCtrlC() {
        session.sendCtrlC()
    }

    fun sendCtrlD() {
        session.sendCtrlD()
    }

    fun stopSession() {
        session.stop()
        _state.value = _state.value.copy(alive = false)
    }

    fun clearOutput() {
        synchronized(outputBuffer) { outputBuffer.setLength(0) }
        _state.value = _state.value.copy(output = "")
    }

    override fun onCleared() {
        super.onCleared()
        session.dispose()
    }
}
