package com.nearby.data.nearby

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        val KEY_BACKGROUND_SERVICE_ENABLED = booleanPreferencesKey("background_service_enabled")
        val KEY_USER_DISPLAY_NAME = stringPreferencesKey("user_display_name")
        val KEY_LANGUAGE = stringPreferencesKey("app_language")

        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_ITALIAN = "it"
    }

    val backgroundServiceEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_BACKGROUND_SERVICE_ENABLED] ?: true // Default: enabled
    }

    suspend fun isBackgroundServiceEnabled(): Boolean {
        return dataStore.data.first()[KEY_BACKGROUND_SERVICE_ENABLED] ?: true
    }

    suspend fun setBackgroundServiceEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_BACKGROUND_SERVICE_ENABLED] = enabled
        }
    }

    val userDisplayName: Flow<String?> = dataStore.data.map { preferences ->
        preferences[KEY_USER_DISPLAY_NAME]
    }

    suspend fun getUserDisplayName(): String? {
        return dataStore.data.first()[KEY_USER_DISPLAY_NAME]
    }

    suspend fun setUserDisplayName(name: String) {
        dataStore.edit { preferences ->
            preferences[KEY_USER_DISPLAY_NAME] = name
        }
    }

    val language: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_LANGUAGE] ?: LANGUAGE_SYSTEM
    }

    suspend fun getLanguage(): String {
        return dataStore.data.first()[KEY_LANGUAGE] ?: LANGUAGE_SYSTEM
    }

    suspend fun setLanguage(languageCode: String) {
        dataStore.edit { preferences ->
            preferences[KEY_LANGUAGE] = languageCode
        }
    }
}
