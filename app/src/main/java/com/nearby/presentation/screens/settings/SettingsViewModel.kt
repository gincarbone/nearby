package com.nearby.presentation.screens.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearby.core.crypto.CryptoManager
import com.nearby.core.util.LocaleHelper
import com.nearby.data.nearby.NearbyService
import com.nearby.data.nearby.PreferencesManager
import com.nearby.domain.model.User
import com.nearby.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val user: User? = null,
    val fingerprint: String = "",
    val editingName: Boolean = false,
    val newDisplayName: String = "",
    val isSaving: Boolean = false,
    val backgroundServiceEnabled: Boolean = true,
    val currentLanguage: String = PreferencesManager.LANGUAGE_SYSTEM,
    val showLanguageDialog: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userRepository: UserRepository,
    private val cryptoManager: CryptoManager,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _editingName = MutableStateFlow(false)
    private val _newDisplayName = MutableStateFlow("")
    private val _isSaving = MutableStateFlow(false)
    private val _showLanguageDialog = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        userRepository.getLocalUser(),
        _editingName,
        _newDisplayName,
        _isSaving,
        preferencesManager.backgroundServiceEnabled,
        preferencesManager.language,
        _showLanguageDialog,
        _error
    ) { values ->
        val user = values[0] as User?
        val editing = values[1] as Boolean
        val newName = values[2] as String
        val saving = values[3] as Boolean
        val bgEnabled = values[4] as Boolean
        val language = values[5] as String
        val showLangDialog = values[6] as Boolean
        val error = values[7] as String?

        SettingsUiState(
            user = user,
            fingerprint = user?.let { cryptoManager.generateTextFingerprint(it.publicKey) } ?: "",
            editingName = editing,
            newDisplayName = newName,
            isSaving = saving,
            backgroundServiceEnabled = bgEnabled,
            currentLanguage = language,
            showLanguageDialog = showLangDialog,
            error = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun startEditingName() {
        _newDisplayName.value = uiState.value.user?.displayName ?: ""
        _editingName.value = true
    }

    fun cancelEditingName() {
        _editingName.value = false
        _newDisplayName.value = ""
    }

    fun onNewDisplayNameChanged(name: String) {
        _newDisplayName.value = name
    }

    fun saveDisplayName() {
        val name = _newDisplayName.value.trim()
        if (name.isBlank()) {
            _error.value = "Name cannot be empty"
            return
        }
        if (name.length < 2) {
            _error.value = "Name must be at least 2 characters"
            return
        }
        if (name.length > 30) {
            _error.value = "Name must be less than 30 characters"
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                userRepository.updateDisplayName(name)
                _editingName.value = false
                _newDisplayName.value = ""
            } catch (e: Exception) {
                _error.value = "Failed to save name: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun showLanguageDialog() {
        _showLanguageDialog.value = true
    }

    fun dismissLanguageDialog() {
        _showLanguageDialog.value = false
    }

    fun setLanguage(languageCode: String) {
        viewModelScope.launch {
            preferencesManager.setLanguage(languageCode)
            LocaleHelper.applyLanguage(context, languageCode)
            _showLanguageDialog.value = false
        }
    }

    fun getLanguageDisplayName(languageCode: String): String {
        return LocaleHelper.getLanguageDisplayName(languageCode, context)
    }

    fun toggleBackgroundService(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setBackgroundServiceEnabled(enabled)

            if (enabled) {
                // Start the background service
                val intent = Intent(context, NearbyService::class.java).apply {
                    action = NearbyService.ACTION_START_BACKGROUND
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                // Stop the service
                val intent = Intent(context, NearbyService::class.java).apply {
                    action = NearbyService.ACTION_STOP
                }
                context.startService(intent)
            }
        }
    }
}
