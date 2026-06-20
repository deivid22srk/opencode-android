package com.deivid.opencode.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Singleton DataStore — there can be only one per process. */
private val Context.setupDataStore: DataStore<Preferences> by preferencesDataStore(name = "opencode_setup")

/**
 * Persists the result of the initial setup wizard across app launches.
 *
 * Stored fields:
 *   - setup_completed: has the user finished the wizard?
 *   - workspace_uri:   SAF tree URI the user picked (or null for default)
 *   - workspace_path:  Resolved real filesystem path of the workspace
 *   - binary_version:  Version tag of the imported opencode binary
 *   - binary_imported_at: Epoch ms when the binary was imported
 */
class SetupPreferences(private val context: Context) {

    companion object {
        private val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        private val WORKSPACE_URI = stringPreferencesKey("workspace_uri")
        private val WORKSPACE_PATH = stringPreferencesKey("workspace_path")
        private val BINARY_VERSION = stringPreferencesKey("binary_version")
        private val BINARY_IMPORTED_AT = stringPreferencesKey("binary_imported_at")
    }

    val data: Flow<SetupData> = context.setupDataStore.data.map { p ->
        SetupData(
            completed = p[SETUP_COMPLETED] ?: false,
            workspaceUri = p[WORKSPACE_URI]?.let(Uri::parse),
            workspacePath = p[WORKSPACE_PATH],
            binaryVersion = p[BINARY_VERSION],
            binaryImportedAt = p[BINARY_IMPORTED_AT] ?: 0L,
        )
    }

    suspend fun setWorkspace(uri: Uri?, path: String?) {
        context.setupDataStore.edit {
            if (uri != null) it[WORKSPACE_URI] = uri.toString() else it.remove(WORKSPACE_URI)
            if (path != null) it[WORKSPACE_PATH] = path else it.remove(WORKSPACE_PATH)
        }
    }

    suspend fun setBinaryInfo(version: String?, importedAt: Long) {
        context.setupDataStore.edit {
            if (version != null) it[BINARY_VERSION] = version else it.remove(BINARY_VERSION)
            it[BINARY_IMPORTED_AT] = importedAt
        }
    }

    suspend fun setCompleted(value: Boolean) {
        context.setupDataStore.edit { it[SETUP_COMPLETED] = value }
    }

    /** Clears every stored field. Used by the "Run setup again" action. */
    suspend fun reset() {
        context.setupDataStore.edit { it.clear() }
    }
}

data class SetupData(
    val completed: Boolean = false,
    val workspaceUri: Uri? = null,
    val workspacePath: String? = null,
    val binaryVersion: String? = null,
    val binaryImportedAt: Long = 0L,
)
