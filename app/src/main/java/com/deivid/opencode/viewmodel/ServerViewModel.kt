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
import com.deivid.opencode.data.SetupPreferences
import com.deivid.opencode.server.BinaryInfo
import com.deivid.opencode.server.BinaryManager
import com.deivid.opencode.server.OpencodeService
import com.deivid.opencode.server.Paths
import com.deivid.opencode.server.ServerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ServerStatus { IDLE, STARTING, RUNNING, STOPPING, ERROR }

data class ServerState(
    val status: ServerStatus = ServerStatus.IDLE,
    val binary: BinaryInfo? = null,
    val port: Int = 4096,
    val hostname: String = "127.0.0.1",
    val password: String = "",
    val workspacePath: String = "",
    val url: String? = null,
    val errorMessage: String? = null,
    val logs: String = "",
    val importBusy: Boolean = false,
    val importMessage: String? = null,
)

class ServerViewModel(app: Application) : AndroidViewModel(app) {

    private val binaryManager = BinaryManager(app)
    private val preferences = SetupPreferences(app)
    private var service: OpencodeService? = null

    private val _state = MutableStateFlow(
        ServerState(
            binary = binaryManager.currentBinary(),
            // Default workspace is the app-private sandbox folder; will be
            // overridden by the persisted setup preference if available.
            workspacePath = Paths.workspaceDir(app).absolutePath,
        )
    )
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val logBuilder = StringBuilder()

    init {
        // Sync workspace path from preferences → state, so the HomeScreen
        // shows the folder the user picked during setup.
        viewModelScope.launch {
            preferences.data.collectLatest { data ->
                data.workspacePath?.let { p ->
                    _state.value = _state.value.copy(workspacePath = p)
                }
                data.binaryVersion?.let { v ->
                    // Refresh binary info in case it changed
                    val b = binaryManager.currentBinary()
                    if (b != null) {
                        _state.value = _state.value.copy(binary = b.copy(version = v))
                    }
                }
            }
        }
    }

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
        // Bind without an action so the binding attaches but the server
        // isn't started yet. The START command is sent when the user taps
        // "Start server".
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

    fun updateWorkspacePath(path: String) {
        _state.value = _state.value.copy(workspacePath = path.trim())
    }

    /**
     * Imports a release file the user picked via the system file picker.
     * All file IO is dispatched to Dispatchers.IO so the UI thread is never
     * blocked — this fixes the freeze the user observed during manual import.
     */
    fun importRelease(uri: Uri) {
        if (_state.value.importBusy) return
        _state.value = _state.value.copy(importBusy = true, importMessage = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                binaryManager.importFromUri(uri)
            }
            result
                .onSuccess { info ->
                    _state.value = _state.value.copy(
                        importBusy = false,
                        importMessage = "Imported opencode (${info.sizeBytes / 1_000_000} MB)",
                        binary = info,
                        errorMessage = null,
                    )
                    // Persist the new binary version so setup state stays in sync.
                    viewModelScope.launch {
                        preferences.setBinaryInfo(info.version, info.importedAt)
                    }
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
        viewModelScope.launch {
            preferences.setBinaryInfo(null, 0L)
        }
    }

    /**
     * Resets the setup state and (typically) takes the user back to the
     * SetupScreen so they can re-pick the release and the workspace folder.
     */
    fun rerunSetup() {
        viewModelScope.launch {
            preferences.setCompleted(false)
        }
    }

    fun startServer(context: Context) {
        val s = _state.value
        if (s.binary == null) {
            _state.value = s.copy(errorMessage = "Import the opencode binary first")
            return
        }
        OpencodeService.start(
            ctx = context,
            port = s.port,
            hostname = s.hostname,
            password = s.password.ifBlank { null },
            workspace = s.workspacePath.ifBlank { null },
        )
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
