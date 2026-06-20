package com.deivid.opencode.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deivid.opencode.data.local.SetupDataStore
import com.deivid.opencode.data.remote.ReleaseRepository
import com.deivid.opencode.model.CompatibleRelease
import com.deivid.opencode.server.BinaryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class SetupStep { RELEASES, WORKSPACE }

data class SetupState(
    val currentStep: SetupStep = SetupStep.RELEASES,
    val releases: List<CompatibleRelease> = emptyList(),
    val releasesLoading: Boolean = true,
    val releasesError: String? = null,
    val downloadingRelease: String? = null,
    val downloadProgress: Float = 0f,
    val importBusy: Boolean = false,
    val importMessage: String? = null,
    val importError: String? = null,
    val workspacePath: String = "",
    val completed: Boolean = false,
)

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val binaryManager = BinaryManager(app)
    private val releaseRepository = ReleaseRepository()
    private val setupDataStore = SetupDataStore(app)

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    init {
        loadReleases()
        loadWorkspacePath()
    }

    private fun loadWorkspacePath() {
        viewModelScope.launch {
            val path = setupDataStore.workspacePath.first()
            _state.value = _state.value.copy(workspacePath = path)
        }
    }

    fun loadReleases() {
        _state.value = _state.value.copy(releasesLoading = true, releasesError = null)
        viewModelScope.launch {
            releaseRepository.fetchCompatibleReleases()
                .onSuccess { releases ->
                    _state.value = _state.value.copy(
                        releases = releases,
                        releasesLoading = false,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        releasesLoading = false,
                        releasesError = e.message ?: "Failed to load releases",
                    )
                }
        }
    }

    fun downloadAndImport(release: CompatibleRelease) {
        val current = _state.value
        if (current.downloadingRelease != null) return

        _state.value = current.copy(
            downloadingRelease = release.version,
            downloadProgress = 0f,
            importError = null,
        )
        viewModelScope.launch {
            downloadRelease(release)
                .onSuccess { file ->
                    binaryManager.importFromFile(file)
                        .onSuccess { info ->
                            _state.value = _state.value.copy(
                                downloadingRelease = null,
                                importMessage = "Imported opencode ${info.version} (${info.sizeBytes / 1_000_000} MB)",
                            )
                        }
                        .onFailure { e ->
                            _state.value = _state.value.copy(
                                downloadingRelease = null,
                                importError = "Import failed: ${e.message}",
                            )
                        }
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        downloadingRelease = null,
                        importError = "Download failed: ${e.message}",
                    )
                }
        }
    }

    private suspend fun downloadRelease(release: CompatibleRelease): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getApplication<Application>()
            val dest = File(ctx.cacheDir, "opencode-${release.version}.tar.gz")
            if (dest.exists()) dest.delete()

            val url = URL(release.downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "opencode-android")
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.connect()

            val totalBytes = conn.contentLengthLong
            val input = conn.inputStream
            val output = FileOutputStream(dest)
            val buffer = ByteArray(8192)

            var bytesRead: Int
            var totalRead = 0L
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalBytes > 0) {
                    val p = totalRead.toFloat() / totalBytes
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(downloadProgress = p)
                    }
                }
            }

            output.close()
            input.close()
            dest
        }
    }

    fun importManually(uri: Uri) {
        _state.value = _state.value.copy(importBusy = true, importError = null)
        viewModelScope.launch(Dispatchers.IO) {
            binaryManager.importFromUri(uri)
                .onSuccess { info ->
                    _state.value = _state.value.copy(
                        importBusy = false,
                        importMessage = "Imported opencode (${info.sizeBytes / 1_000_000} MB)",
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        importBusy = false,
                        importError = "Import failed: ${e.message ?: e.toString()}",
                    )
                }
        }
    }

    fun setWorkspacePath(path: String) {
        _state.value = _state.value.copy(workspacePath = path)
    }

    fun nextStep() {
        _state.value = _state.value.copy(currentStep = SetupStep.WORKSPACE)
    }

    fun previousStep() {
        _state.value = _state.value.copy(currentStep = SetupStep.RELEASES)
    }

    fun completeSetup() {
        viewModelScope.launch {
            setupDataStore.completeSetup(_state.value.workspacePath)
            _state.value = _state.value.copy(completed = true)
        }
    }

    fun skipSetup() {
        viewModelScope.launch {
            setupDataStore.completeSetup("")
            _state.value = _state.value.copy(completed = true)
        }
    }

    fun dismissImportMessage() {
        _state.value = _state.value.copy(importMessage = null)
    }

    fun dismissImportError() {
        _state.value = _state.value.copy(importError = null)
    }
}
