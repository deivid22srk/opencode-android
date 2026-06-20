package com.deivid.opencode.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deivid.opencode.data.SetupPreferences
import com.deivid.opencode.server.BinaryInfo
import com.deivid.opencode.server.BinaryManager
import com.deivid.opencode.server.GithubRelease
import com.deivid.opencode.server.Paths
import com.deivid.opencode.server.ReleaseFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ------------------------------------------------------------------
// Setup wizard state
// ------------------------------------------------------------------

enum class SetupStep { RELEASE, WORKSPACE }

enum class SetupAction { NEXT, BACK, FINISH, NONE }

data class SetupState(
    val currentStep: SetupStep = SetupStep.RELEASE,
    val action: SetupAction = SetupAction.NONE,
    // --- Step 1: releases ---
    val releases: List<GithubRelease> = emptyList(),
    val releasesLoading: Boolean = false,
    val releasesError: String? = null,
    val downloadingRelease: String? = null,        // tag name being downloaded
    val downloadProgress: Float = 0f,               // 0..1
    val downloadBytes: Long = 0L,
    val downloadTotal: Long = -1L,
    val importingBusy: Boolean = false,
    val importedBinary: BinaryInfo? = null,
    val importError: String? = null,
    val importSuccessMessage: String? = null,
    // --- Step 2: workspace ---
    val workspaceUri: String? = null,
    val workspaceDisplayName: String? = null,
    val workspacePath: String = "",
    // --- General ---
    val errorMessage: String? = null,
    val setupComplete: Boolean = false,
)

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val binaryManager = BinaryManager(app)
    private val releaseFetcher = ReleaseFetcher()
    private val setupPrefs = SetupPreferences(app)

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    init {
        // Start loading releases immediately
        loadReleases()

        // Restore saved workspace if any
        viewModelScope.launch {
            val uri = setupPrefs.workspaceUri.first() as? String
            val name = setupPrefs.workspaceDisplayName.first() as? String
            if (uri != null && name != null) {
                val path = resolveTreeUriPath(uri)
                _state.value = _state.value.copy(
                    workspaceUri = uri,
                    workspaceDisplayName = name,
                    workspacePath = path ?: Paths.workspaceDir(getApplication()).absolutePath,
                )
            } else {
                _state.value = _state.value.copy(
                    workspacePath = Paths.workspaceDir(getApplication()).absolutePath,
                )
            }
        }
    }

    private fun loadReleases() {
        _state.value = _state.value.copy(releasesLoading = true, releasesError = null)
        viewModelScope.launch {
            releaseFetcher.fetchReleases()
                .onSuccess { releases ->
                    _state.value = _state.value.copy(
                        releases = releases,
                        releasesLoading = false,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        releasesLoading = false,
                        releasesError = e.message ?: "Failed to fetch releases",
                    )
                }
        }
    }

    fun retryLoadReleases() = loadReleases()

    /**
     * Download a release asset and then import it.
     */
    fun downloadAndImport(tagName: String, assetName: String, downloadUrl: String) {
        val s = _state.value
        if (s.downloadingRelease != null || s.importingBusy) return

        _state.value = s.copy(
            downloadingRelease = tagName,
            downloadProgress = 0f,
            downloadBytes = 0L,
            downloadTotal = -1L,
            importError = null,
            importSuccessMessage = null,
        )

        viewModelScope.launch {
            try {
                // Download to temp file
                val tmpFile = File(getApplication<Application>().cacheDir, "release-$tagName.tar.gz")
                releaseFetcher.downloadRelease(downloadUrl, tmpFile) { downloaded, total ->
                    _state.value = _state.value.copy(
                        downloadBytes = downloaded,
                        downloadTotal = total,
                        downloadProgress = if (total > 0) downloaded.toFloat() / total else 0f,
                    )
                }.getOrThrow()

                // Import the downloaded file
                _state.value = _state.value.copy(
                    downloadingRelease = null,
                    importingBusy = true,
                )
                importFromFile(tmpFile)
                tmpFile.delete()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    downloadingRelease = null,
                    importingBusy = false,
                    importError = "Download failed: ${e.message}",
                )
            }
        }
    }

    /**
     * Import from a user-picked URI (manual import).
     * This runs the heavy I/O on [Dispatchers.IO] to avoid freezing the UI.
     */
    fun importFromUri(uri: Uri) {
        val s = _state.value
        if (s.importingBusy || s.downloadingRelease != null) return

        _state.value = s.copy(
            importingBusy = true,
            importError = null,
            importSuccessMessage = null,
        )

        viewModelScope.launch {
            try {
                // Stage into a temp file on IO thread (this is the fix for the freeze bug)
                val tmp = withContext(Dispatchers.IO) {
                    val file = File(
                        getApplication<Application>().cacheDir,
                        "import-${System.currentTimeMillis()}.bin"
                    )
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { input ->
                            file.outputStream().buffered(8192).use { output ->
                                input.copyTo(output)
                            }
                        } ?: error("Cannot open selected file")
                    file
                }

                importFromFile(tmp)
                tmp.delete()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    importingBusy = false,
                    importError = "Import failed: ${e.message}",
                )
            }
        }
    }

    private suspend fun importFromFile(file: File) {
        withContext(Dispatchers.IO) {
            binaryManager.importFromFile(file)
        }.onSuccess { info ->
            _state.value = _state.value.copy(
                importingBusy = false,
                importedBinary = info,
                importSuccessMessage = "Imported opencode ${info.version} (${info.sizeBytes / 1_000_000} MB)",
                importError = null,
            )
        }.onFailure { e ->
            _state.value = _state.value.copy(
                importingBusy = false,
                importError = e.message ?: "Import failed",
            )
        }
    }

    fun setWorkspace(uri: String, displayName: String) {
        val path = resolveTreeUriPath(uri)
        _state.value = _state.value.copy(
            workspaceUri = uri,
            workspaceDisplayName = displayName,
            workspacePath = path ?: Paths.workspaceDir(getApplication()).absolutePath,
        )
    }

    fun useDefaultWorkspace() {
        val defaultPath = Paths.workspaceDir(getApplication()).absolutePath
        _state.value = _state.value.copy(
            workspaceUri = null,
            workspaceDisplayName = "App internal storage",
            workspacePath = defaultPath,
        )
    }

    fun clearImportError() {
        _state.value = _state.value.copy(importError = null, importSuccessMessage = null)
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    fun nextStep() {
        val s = _state.value
        when (s.currentStep) {
            SetupStep.RELEASE -> {
                if (s.importedBinary != null) {
                    _state.value = s.copy(currentStep = SetupStep.WORKSPACE, action = SetupAction.NEXT)
                }
            }
            SetupStep.WORKSPACE -> {
                // Finish setup
                viewModelScope.launch {
                    val ws = _state.value
                    if (ws.workspaceUri != null && ws.workspaceDisplayName != null) {
                        setupPrefs.setWorkspace(ws.workspaceUri!!, ws.workspaceDisplayName!!)
                    }
                    setupPrefs.markComplete()
                    _state.value = _state.value.copy(setupComplete = true, action = SetupAction.FINISH)
                }
            }
        }
    }

    fun previousStep() {
        val s = _state.value
        when (s.currentStep) {
            SetupStep.RELEASE -> {} // Can't go back from step 1
            SetupStep.WORKSPACE -> {
                _state.value = s.copy(currentStep = SetupStep.RELEASE, action = SetupAction.BACK)
            }
        }
    }

    fun consumeAction() {
        _state.value = _state.value.copy(action = SetupAction.NONE)
    }

    // ------------------------------------------------------------------
    // Utility: resolve a SAF tree URI to a filesystem path
    // ------------------------------------------------------------------

    private fun resolveTreeUriPath(treeUriString: String): String? {
        return try {
            val treeUri = Uri.parse(treeUriString)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val split = docId.split(":", limit = 2)
            if (split.size < 2) return null
            val type = split[0]
            val path = split[1]
            if (type.equals("primary", ignoreCase = true)) {
                "/storage/emulated/0/$path"
            } else {
                // Try to match against mounted storage volumes
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}