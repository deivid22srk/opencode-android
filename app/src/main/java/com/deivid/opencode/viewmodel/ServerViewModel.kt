package com.deivid.opencode.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deivid.opencode.server.BinaryInfo
import com.deivid.opencode.server.BinaryManager
import com.deivid.opencode.server.OpencodeService
import com.deivid.opencode.server.ServerEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class ServerStatus { IDLE, STARTING, RUNNING, STOPPING, ERROR }

data class ServerState(
    val status: ServerStatus = ServerStatus.IDLE,
    val binary: BinaryInfo? = null,
    val port: Int = 4096,
    val hostname: String = "127.0.0.1",
    val password: String = "",
    val url: String? = null,
    val errorMessage: String? = null,
    val logs: String = "",
    val importBusy: Boolean = false,
    val importMessage: String? = null,
)

class ServerViewModel(app: Application) : AndroidViewModel(app) {

    private val binaryManager = BinaryManager(app)
    private var service: OpencodeService? = null

    private val _state = MutableStateFlow(ServerState(binary = binaryManager.currentBinary()))
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val logBuilder = StringBuilder()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? OpencodeService.LocalBinder)?.service ?: return
            service = s
            viewModelScope.launch {
                s.events.collectLatest { e -> handleEvent(e) }
            }
            viewModelScope.launch {
                s.logs.collectLatest { chunk ->
                    synchronized(logBuilder) { logBuilder.append(chunk) }
                    val full = synchronized(logBuilder) { logBuilder.toString() }
                    _state.value = _state.value.copy(logs = full)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun bind(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, OpencodeService::class.java),
        )
        // Start the service without an action first so the binding can attach.
        // The actual START command is sent when the user taps "Start server".
        context.bindService(
            Intent(context, OpencodeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun unbind(context: Context) {
        runCatching { context.unbindService(connection) }
        service = null
    }

    fun updatePort(port: Int) {
        _state.value = _state.value.copy(port = port.coerceIn(1, 65535))
    }

    fun updateHostname(hostname: String) {
        _state.value = _state.value.copy(hostname = hostname.trim())
    }

    fun updatePassword(password: String) {
        _state.value = _state.value.copy(password = password)
    }

    fun importRelease(uri: Uri) {
        _state.value = _state.value.copy(importBusy = true, importMessage = null)
        viewModelScope.launch {
            val result = binaryManager.importFromUri(uri)
            result
                .onSuccess { info ->
                    _state.value = _state.value.copy(
                        importBusy = false,
                        importMessage = "Imported opencode (${info.sizeBytes / 1_000_000} MB)",
                        binary = info,
                        errorMessage = null,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        importBusy = false,
                        importMessage = null,
                        errorMessage = "Import failed: ${e.message ?: e.toString()}",
                    )
                }
        }
    }

    fun deleteBinary() {
        binaryManager.deleteBinary()
        _state.value = _state.value.copy(binary = null)
    }

    fun startServer(context: Context) {
        val s = _state.value
        if (s.binary == null) {
            _state.value = s.copy(errorMessage = "Import the opencode binary first")
            return
        }
        OpencodeService.start(context, s.port, s.hostname, s.password.ifBlank { null })
    }

    fun stopServer(context: Context) {
        OpencodeService.stop(context)
        _state.value = _state.value.copy(status = ServerStatus.STOPPING)
    }

    fun clearLogs() {
        synchronized(logBuilder) { logBuilder.setLength(0) }
        _state.value = _state.value.copy(logs = "")
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun handleEvent(e: ServerEvent) {
        _state.value = when (e) {
            is ServerEvent.Starting -> _state.value.copy(status = ServerStatus.STARTING, errorMessage = null)
            is ServerEvent.Started -> _state.value.copy(
                status = ServerStatus.RUNNING,
                url = e.url,
                errorMessage = null,
            )
            is ServerEvent.Stopped -> _state.value.copy(
                status = ServerStatus.IDLE,
                url = null,
                errorMessage = e.reason,
            )
            is ServerEvent.Failed -> _state.value.copy(
                status = ServerStatus.ERROR,
                url = null,
                errorMessage = e.message,
            )
        }
    }
}
