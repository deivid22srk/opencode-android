package com.deivid.opencode.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deivid.opencode.data.DownloadManager
import com.deivid.opencode.data.GithubAsset
import com.deivid.opencode.data.GithubRelease
import com.deivid.opencode.data.ReleasesRepository
import com.deivid.opencode.data.SetupPreferences
import com.deivid.opencode.data.WorkspaceResolver
import com.deivid.opencode.server.BinaryInfo
import com.deivid.opencode.server.BinaryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface SetupStep {
    data object Releases : SetupStep
    data object Workspace : SetupStep
}

data class SetupState(
    val step: SetupStep = SetupStep.Releases,
    val releases: List<GithubRelease> = emptyList(),
    val loadingReleases: Boolean = true,
    val releasesError: String? = null,
    val downloadingAsset: GithubAsset? = null,
    val downloadProgress: Float? = null,
    val importBusy: Boolean = false,
    val importMessage: String? = null,
    val importedBinary: BinaryInfo? = null,
    val selectedWorkspaceUri: Uri? = null,
    val workspacePath: String? = null,
    val workspaceError: String? = null,
) {
    /** The user can advance to step 2 only after a binary is imported. */
    val canAdvanceFromReleases: Boolean get() = importedBinary != null
    val canFinish: Boolean get() = importedBinary != null && workspacePath != null
}

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val releasesRepo = ReleasesRepository()
    private val downloader = DownloadManager(app)
    private val binaryManager = BinaryManager(app)
    private val preferences = SetupPreferences(app)

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    init {
        loadReleases()
    }

    private fun loadReleases() {
        _state.value = _state.value.copy(loadingReleases = true, releasesError = null)
        viewModelScope.launch {
            releasesRepo.fetchReleases()
                .onSuccess { releases ->
                    val compatible = releases.filter { it.compatibleAsset != null }
                    _state.value = _state.value.copy(
                        loadingReleases = false,
                        releases = compatible,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loadingReleases = false,
                        releasesError = e.message ?: e.toString(),
                    )
                }
        }
    }

    fun retryReleases() = loadReleases()

    /**
     * Downloads [asset] to cacheDir then extracts the opencode binary.
     * Runs entirely on Dispatchers.IO so the UI never freezes.
     */
    fun downloadRelease(asset: GithubAsset) {
        if (_state.value.downloadingAsset != null) return
        _state.value = _state.value.copy(
            downloadingAsset = asset,
            downloadProgress = 0f,
            importMessage = null,
        )
        viewModelScope.launch {
            val target = File(
                getApplication<Application>().cacheDir,
                "release-${System.currentTimeMillis()}-${asset.name}",
            )
            downloader.download(asset.downloadUrl, target) { pct ->
                _state.value = _state.value.copy(downloadProgress = pct)
            }.fold(
                onSuccess = { file ->
                    // Extraction is also CPU/IO-heavy — keep it on IO.
                    withContext(Dispatchers.IO) {
                        binaryManager.importFromFile(file)
                    }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        downloadingAsset = null,
                        downloadProgress = null,
                        importMessage = "Download failed: ${e.message}",
                    )
                    null
                },
            ).let { result ->
                result?.onSuccess { info ->
                    _state.value = _state.value.copy(
                        downloadingAsset = null,
                        downloadProgress = null,
                        importedBinary = info,
                        importMessage = "Imported opencode (${info.sizeBytes / 1_000_000} MB)",
                    )
                }?.onFailure { e ->
                    _state.value = _state.value.copy(
                        downloadingAsset = null,
                        downloadProgress = null,
                        importMessage = "Import failed: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Imports a release file the user picked via the system file picker.
     * All file IO is dispatched to Dispatchers.IO so the UI thread is never
     * blocked — this fixes the freeze the user observed.
     */
    fun importFromFile(uri: Uri) {
        if (_state.value.importBusy) return
        _state.value = _state.value.copy(
            importBusy = true,
            importMessage = null,
            downloadingAsset = null,
            downloadProgress = null,
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                binaryManager.importFromUri(uri)
            }
            result
                .onSuccess { info ->
                    _state.value = _state.value.copy(
                        importBusy = false,
                        importedBinary = info,
                        importMessage = "Imported opencode (${info.sizeBytes / 1_000_000} MB)",
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        importBusy = false,
                        importMessage = "Import failed: ${e.message}",
                    )
                }
        }
    }

    fun selectWorkspace(uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                WorkspaceResolver.resolve(getApplication(), uri)
            }
            if (path != null) {
                _state.value = _state.value.copy(
                    selectedWorkspaceUri = uri,
                    workspacePath = path,
                    workspaceError = null,
                )
            } else {
                _state.value = _state.value.copy(
                    workspaceError = "Could not resolve that folder to a real path. " +
                        "Please pick a folder on internal storage (not an SD card or cloud provider).",
                )
            }
        }
    }

    fun goToStep(step: SetupStep) {
        _state.value = _state.value.copy(step = step)
    }

    /**
     * Persists the setup result and signals completion. The activity is
     * observing [SetupPreferences.data] and will swap to the HomeScreen
     * automatically.
     */
    fun completeSetup() {
        viewModelScope.launch {
            val s = _state.value
            preferences.setWorkspace(s.selectedWorkspaceUri, s.workspacePath)
            s.importedBinary?.let {
                preferences.setBinaryInfo(it.version, it.importedAt)
            }
            preferences.setCompleted(true)
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(importMessage = null)
    }
}
