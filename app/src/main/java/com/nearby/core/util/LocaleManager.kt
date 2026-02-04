package com.nearby.core.util

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import com.nearby.data.nearby.PreferencesManager
import java.util.Locale

/**
 * Manages app locale/language settings.
 * Uses the modern LocaleManager API on Android 13+.
 * On older versions, the app will follow system locale.
 */
object LocaleHelper {

    /**
     * Apply the saved language preference.
     * Call this in Application.onCreate() or MainActivity.onCreate()
     */
    fun applyLanguage(context: Context, languageCode: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            when (languageCode) {
                PreferencesManager.LANGUAGE_SYSTEM -> {
                    // Reset to system default
                    localeManager?.applicationLocales = LocaleList.getEmptyLocaleList()
                }
                else -> {
                    // Set specific language
                    val locale = Locale(languageCode)
                    localeManager?.applicationLocales = LocaleList(locale)
                }
            }
        }
        // On older Android versions, language change requires app restart
        // The system will use the app's default locale based on resources
    }

    /**
     * Get current language code.
     */
    fun getCurrentLanguage(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val locales = localeManager?.applicationLocales
            if (locales != null && !locales.isEmpty) {
                return locales[0]?.language ?: PreferencesManager.LANGUAGE_SYSTEM
            }
        }
        return PreferencesManager.LANGUAGE_SYSTEM
    }

    /**
     * Get display name for a language code.
     */
    fun getLanguageDisplayName(languageCode: String, context: Context): String {
        return when (languageCode) {
            PreferencesManager.LANGUAGE_ENGLISH -> "English"
            PreferencesManager.LANGUAGE_ITALIAN -> "Italiano"
            PreferencesManager.LANGUAGE_SYSTEM -> {
                // Return localized "System default" based on current locale
                val currentLocale = context.resources.configuration.locales[0]
                if (currentLocale.language == "it") "Predefinito di sistema" else "System default"
            }
            else -> languageCode
        }
    }
}
