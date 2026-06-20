package com.deivid.opencode.data.local

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

private val Context.setupStore: DataStore<Preferences> by preferencesDataStore(name = "opencode_setup")

class SetupDataStore(private val context: Context) {

    companion object {
        private val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        private val WORKSPACE_PATH = stringPreferencesKey("workspace_path")
    }

    val setupCompleted: Flow<Boolean> = context.setupStore.data.map { it[SETUP_COMPLETED] ?: false }
    val workspacePath: Flow<String> = context.setupStore.data.map { it[WORKSPACE_PATH] ?: "" }

    suspend fun isSetupCompleted(): Boolean = setupCompleted.first()

    suspend fun completeSetup(workspacePath: String) {
        context.setupStore.edit {
            it[SETUP_COMPLETED] = true
            it[WORKSPACE_PATH] = workspacePath
        }
    }

    suspend fun resetSetup() {
        context.setupStore.edit { it.clear() }
    }

    suspend fun updateWorkspacePath(path: String) {
        context.setupStore.edit {
            it[WORKSPACE_PATH] = path
        }
    }
}
