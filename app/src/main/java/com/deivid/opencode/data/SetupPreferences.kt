package com.deivid.opencode.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed preferences that track whether the initial setup wizard
 * has been completed and which workspace folder the user selected.
 */
class SetupPreferences(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
            name = "setup_prefs"
        )

        private val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        private val WORKSPACE_URI = stringPreferencesKey("workspace_uri")
        private val WORKSPACE_DISPLAY_NAME = stringPreferencesKey("workspace_display_name")
    }

    /** Whether the user has gone through the initial setup flow. */
    val isSetupComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SETUP_COMPLETE] == true
    }

    /** Persisted workspace tree URI (content:// URI from SAF). */
    val workspaceUri: Flow<String?> = context.dataStore.data.map { it[WORKSPACE_URI] }

    /** Human-readable name of the selected workspace folder. */
    val workspaceDisplayName: Flow<String?> = context.dataStore.data.map {
        it[WORKSPACE_DISPLAY_NAME]
    }

    suspend fun markComplete() {
        context.dataStore.edit { it[SETUP_COMPLETE] = true }
    }

    suspend fun setWorkspace(uri: String, displayName: String) {
        context.dataStore.edit {
            it[WORKSPACE_URI] = uri
            it[WORKSPACE_DISPLAY_NAME] = displayName
        }
    }

    suspend fun isComplete(): Boolean {
        return context.dataStore.data.first()[SETUP_COMPLETE] == true
    }
}