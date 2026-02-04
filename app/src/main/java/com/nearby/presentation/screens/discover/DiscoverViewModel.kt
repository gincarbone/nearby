package com.nearby.presentation.screens.discover

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearby.core.crypto.CryptoManager
import com.nearby.core.util.generateUUID
import com.nearby.data.local.entity.ConnectionState
import com.nearby.data.nearby.ConnectionInfo
import com.nearby.data.nearby.DiscoveredEndpoint
import com.nearby.data.nearby.NearbyConnectionState
import com.nearby.data.nearby.NearbyManager
import com.nearby.data.nearby.NearbyService
import com.nearby.domain.model.Peer
import com.nearby.data.nearby.MessageHandler
import com.nearby.domain.repository.ConversationRepository
import com.nearby.domain.repository.PeerRepository
import com.nearby.domain.repository.UserRepository
import com.nearby.data.nearby.MessageEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class DiscoverUiState(
    val isDiscovering: Boolean = false,
    val isAdvertising: Boolean = false,
    val discoveredEndpoints: List<DiscoveredEndpoint> = emptyList(),
    val pendingConnection: ConnectionInfo? = null,
    val connectionState: NearbyConnectionState = NearbyConnectionState.Idle,
    val error: String? = null
)

sealed class DiscoverEvent {
    data class NavigateToChat(val conversationId: String) : DiscoverEvent()
    data class ShowError(val message: String) : DiscoverEvent()
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nearbyManager: NearbyManager,
    private val userRepository: UserRepository,
    private val peerRepository: PeerRepository,
    private val conversationRepository: ConversationRepository,
    private val cryptoManager: CryptoManager,
    private val messageHandler: MessageHandler
) : ViewModel() {

    private val _pendingConnection = MutableStateFlow<ConnectionInfo?>(null)
    private val _outgoingConnection = MutableStateFlow<ConnectionInfo?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _pendingHandshakes = mutableMapOf<String, String>() // peerId -> conversationId
    private val _navigatedPeers = mutableSetOf<String>() // Track peers we've already navigated to

    val uiState: StateFlow<DiscoverUiState> = combine(
        nearbyManager.connectionState,
        nearbyManager.discoveredEndpoints,
        _pendingConnection,
        _error
    ) { connectionState, endpoints, pending, error ->
        DiscoverUiState(
            isDiscovering = connectionState is NearbyConnectionState.Discovering ||
                           connectionState is NearbyConnectionState.AdvertisingAndDiscovering,
            isAdvertising = connectionState is NearbyConnectionState.Advertising ||
                           connectionState is NearbyConnectionState.AdvertisingAndDiscovering,
            discoveredEndpoints = endpoints,
            pendingConnection = pending,
            connectionState = connectionState,
            error = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DiscoverUiState()
    )

    private val _events = MutableSharedFlow<DiscoverEvent>()
    val events = _events.asSharedFlow()

    init {
        observeConnectionRequests()
        observeConnectionState()
        observeHandshakeEvents()
    }

    private fun observeConnectionRequests() {
        viewModelScope.launch {
            nearbyManager.connectionRequests.collect { connectionInfo ->
                // Auto-accept all connections - no manual approval needed
                if (!connectionInfo.isIncomingConnection) {
                    // Store outgoing connection info to create peer when connection is established
                    _outgoingConnection.value = connectionInfo
                }
                // Connection is auto-accepted by NearbyService
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            nearbyManager.connectionState.collect { state ->
                when (state) {
                    is NearbyConnectionState.Connected -> {
                        // Check if this was an outgoing connection we initiated
                        val outgoing = _outgoingConnection.value
                        if (outgoing != null && outgoing.endpointId == state.endpointId) {
                            _outgoingConnection.value = null
                            createPeerAndConversation(outgoing)
                        }
                    }
                    is NearbyConnectionState.Error -> {
                        _outgoingConnection.value = null
                    }
                    else -> { }
                }
            }
        }
    }

    private fun observeHandshakeEvents() {
        viewModelScope.launch {
            messageHandler.events.collect { event ->
                when (event) {
                    is MessageEvent.PeerConnected -> {
                        // Navigate to chat when peer is fully connected
                        // Only navigate once per peer (both sides send handshakes)
                        val peerId = event.peer.id
                        val conversationId = event.conversationId
                        android.util.Log.d("DiscoverViewModel", "PeerConnected received - peerId: $peerId, conversationId: $conversationId")
                        _pendingHandshakes.remove(peerId)
                        if (!_navigatedPeers.contains(peerId)) {
                            _navigatedPeers.add(peerId)

                            // Wait for conversation to be available in DB before navigating
                            // This prevents race condition where Flow hasn't emitted yet
                            val conversationReady = withTimeoutOrNull(3000L) {
                                conversationRepository.getConversationByIdFlow(conversationId)
                                    .filterNotNull()
                                    .first()
                            }

                            if (conversationReady != null) {
                                android.util.Log.d("DiscoverViewModel", "Conversation ready, navigating to chat: $conversationId")
                                _events.emit(DiscoverEvent.NavigateToChat(conversationId))
                            } else {
                                android.util.Log.e("DiscoverViewModel", "Timeout waiting for conversation: $conversationId")
                                // Try to navigate anyway - ChatViewModel will show loading
                                _events.emit(DiscoverEvent.NavigateToChat(conversationId))
                            }
                        } else {
                            android.util.Log.d("DiscoverViewModel", "Already navigated for peer: $peerId")
                        }
                    }
                    is MessageEvent.PeerDeleted -> {
                        // Clean up when peer is deleted
                        _navigatedPeers.remove(event.peerId)
                    }
                    is MessageEvent.Error -> {
                        _error.value = event.message
                    }
                    else -> {}
                }
            }
        }
    }

    fun startDiscovery() {
        viewModelScope.launch {
            val user = userRepository.getLocalUserSync() ?: return@launch

            // Start service first
            val serviceIntent = Intent(context, NearbyService::class.java).apply {
                action = NearbyService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            // Start advertising
            val advertisingIntent = Intent(context, NearbyService::class.java).apply {
                action = NearbyService.ACTION_START_ADVERTISING
                putExtra(NearbyService.EXTRA_USER_NAME, user.displayName)
            }
            context.startService(advertisingIntent)

            // Start discovery
            val discoveryIntent = Intent(context, NearbyService::class.java).apply {
                action = NearbyService.ACTION_START_DISCOVERY
            }
            context.startService(discoveryIntent)
        }
    }

    fun stopDiscovery() {
        val stopDiscoveryIntent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_STOP_DISCOVERY
        }
        context.startService(stopDiscoveryIntent)

        val stopAdvertisingIntent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_STOP_ADVERTISING
        }
        context.startService(stopAdvertisingIntent)
    }

    fun connectToEndpoint(endpoint: DiscoveredEndpoint) {
        viewModelScope.launch {
            val user = userRepository.getLocalUserSync() ?: return@launch

            nearbyManager.requestConnection(user.displayName, endpoint.endpointId)
                .collect { result ->
                    result.onFailure { e ->
                        _error.value = "Connection request failed: ${e.message}"
                    }
                }
        }
    }

    fun acceptConnection() {
        val connection = _pendingConnection.value ?: return

        viewModelScope.launch {
            nearbyManager.acceptConnection(connection.endpointId)
                .collect { result ->
                    result.onSuccess {
                        _pendingConnection.value = null
                        createPeerAndConversation(connection)
                    }.onFailure { e ->
                        _error.value = "Failed to accept connection: ${e.message}"
                    }
                }
        }
    }

    fun rejectConnection() {
        val connection = _pendingConnection.value ?: return

        viewModelScope.launch {
            nearbyManager.rejectConnection(connection.endpointId)
                .collect { result ->
                    _pendingConnection.value = null
                }
        }
    }

    private suspend fun createPeerAndConversation(connection: ConnectionInfo) {
        val user = userRepository.getLocalUserSync() ?: return

        // Extract display name from endpoint name (format: "NearBy|DisplayName")
        val displayName = connection.endpointName.removePrefix("NearBy|")

        // Check if peer already exists by endpoint
        val existingPeer = peerRepository.getPeerByEndpointId(connection.endpointId)

        val peerId = if (existingPeer != null) {
            peerRepository.updateConnectionState(existingPeer.id, ConnectionState.CONNECTED)
            existingPeer.id
        } else {
            // Create new peer with temporary public key (will be replaced after handshake)
            val peer = Peer(
                id = generateUUID(),
                endpointId = connection.endpointId,
                displayName = displayName,
                publicKey = user.publicKey, // Temporary, will be updated after handshake
                isVerified = false,
                lastSeen = System.currentTimeMillis(),
                connectionState = ConnectionState.CONNECTED
            )
            peerRepository.savePeer(peer)
            peer.id
        }

        // Create or get conversation
        val conversation = conversationRepository.getOrCreateConversation(peerId)

        // Store pending handshake - navigation will happen after handshake completes
        _pendingHandshakes[peerId] = conversation.id

        // Send handshake to exchange public keys
        messageHandler.sendHandshake(connection.endpointId)
    }

    fun clearError() {
        _error.value = null
    }
}
