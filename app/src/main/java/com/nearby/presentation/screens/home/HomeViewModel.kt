package com.nearby.presentation.screens.home

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearby.data.nearby.NearbyConnectionState
import com.nearby.data.nearby.NearbyManager
import com.nearby.data.nearby.NearbyService
import com.nearby.domain.model.Conversation
import com.nearby.domain.model.User
import com.nearby.domain.repository.ConversationRepository
import com.nearby.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val user: User? = null,
    val conversations: List<Conversation> = emptyList(),
    val totalUnreadCount: Int = 0,
    val connectionState: NearbyConnectionState = NearbyConnectionState.Idle,
    val connectedPeersCount: Int = 0,
    val isServiceRunning: Boolean = false,
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userRepository: UserRepository,
    private val conversationRepository: ConversationRepository,
    private val nearbyManager: NearbyManager
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        userRepository.getLocalUser(),
        conversationRepository.getAllConversations(),
        conversationRepository.getTotalUnreadCount(),
        nearbyManager.connectionState,
        nearbyManager.connectedEndpoints
    ) { user, conversations, unreadCount, connectionState, connectedEndpoints ->
        HomeUiState(
            user = user,
            conversations = conversations,
            totalUnreadCount = unreadCount,
            connectionState = connectionState,
            connectedPeersCount = connectedEndpoints.size,
            isServiceRunning = connectionState != NearbyConnectionState.Idle,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun startService() {
        val intent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopService() {
        val intent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun startAdvertisingAndDiscovery() {
        val user = uiState.value.user ?: return

        val advertisingIntent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_START_ADVERTISING
            putExtra(NearbyService.EXTRA_USER_NAME, user.displayName)
        }
        context.startService(advertisingIntent)

        val discoveryIntent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_START_DISCOVERY
        }
        context.startService(discoveryIntent)
    }

    fun stopAdvertisingAndDiscovery() {
        val stopAdvertisingIntent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_STOP_ADVERTISING
        }
        context.startService(stopAdvertisingIntent)

        val stopDiscoveryIntent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_STOP_DISCOVERY
        }
        context.startService(stopDiscoveryIntent)
    }
}
