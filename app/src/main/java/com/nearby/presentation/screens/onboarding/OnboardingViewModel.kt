package com.nearby.presentation.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearby.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val displayName: String = "",
    val isLoading: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onDisplayNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(displayName = name, error = null)
    }

    fun createUser() {
        val name = _uiState.value.displayName.trim()
        if (name.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Please enter a display name")
            return
        }
        if (name.length < 2) {
            _uiState.value = _uiState.value.copy(error = "Name must be at least 2 characters")
            return
        }
        if (name.length > 30) {
            _uiState.value = _uiState.value.copy(error = "Name must be less than 30 characters")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                userRepository.createUser(name)
                userRepository.setOnboardingComplete()
                _uiState.value = _uiState.value.copy(isLoading = false, isComplete = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to create user: ${e.message}"
                )
            }
        }
    }
}
