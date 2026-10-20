package com.rtls.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.rtlsDataStore: DataStore<Preferences> by preferencesDataStore(name = "rtls_preferences")

class RtlsPreferences(private val context: Context) {

    val useFakeLocation: Flow<Boolean> = context.rtlsDataStore.data.map { prefs ->
        prefs[KEY_USE_FAKE] ?: false
    }

    val displayName: Flow<String?> = context.rtlsDataStore.data.map { prefs ->
        prefs[KEY_DISPLAY_NAME]
    }

    suspend fun setUseFakeLocation(enabled: Boolean) {
        context.rtlsDataStore.edit { it[KEY_USE_FAKE] = enabled }
    }

    suspend fun setDisplayName(name: String) {
        context.rtlsDataStore.edit { it[KEY_DISPLAY_NAME] = name }
    }

    private companion object {
        val KEY_USE_FAKE = booleanPreferencesKey("use_fake_location")
        val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
    }
}
