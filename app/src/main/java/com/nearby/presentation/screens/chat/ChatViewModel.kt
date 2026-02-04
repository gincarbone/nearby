package com.nearby.presentation.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearby.core.crypto.CryptoManager
import com.nearby.core.util.generateUUID
import com.nearby.data.local.entity.MessageStatus
import com.nearby.data.nearby.MessageEvent
import com.nearby.data.nearby.MessageHandler
import com.nearby.data.nearby.NearbyManager
import com.nearby.domain.model.Conversation
import com.nearby.domain.model.Message
import com.nearby.domain.model.User
import com.nearby.domain.repository.ConversationRepository
import com.nearby.domain.repository.MessageRepository
import com.nearby.domain.repository.PeerRepository
import com.nearby.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.nearby.data.nearby.protocol.MessageProtocol
import javax.inject.Inject

data class ChatUiState(
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val currentUser: User? = null,
    val inputText: String = "",
    val isConnected: Boolean = false,
    val isLoading: Boolean = true,
    val isDisconnecting: Boolean = false,
    val showDisconnectDialog: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val nearbyManager: NearbyManager,
    private val cryptoManager: CryptoManager,
    private val messageHandler: MessageHandler
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle.get<String>("conversationId")).also {
        android.util.Log.d("ChatViewModel", "Received conversationId: $it")
    }

    private val _inputText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    private val _isDisconnecting = MutableStateFlow(false)
    private val _showDisconnectDialog = MutableStateFlow(false)
    private val _peerDeleted = MutableStateFlow(false)

    val uiState: StateFlow<ChatUiState> = combine(
        conversationRepository.getConversationByIdFlow(conversationId),
        messageRepository.getMessagesForConversation(conversationId),
        userRepository.getLocalUser(),
        nearbyManager.connectedEndpoints,
        _inputText,
        combine(_error, _isDisconnecting, _showDisconnectDialog) { error, isDisconnecting, showDialog ->
            Triple(error, isDisconnecting, showDialog)
        }
    ) { values ->
        val conversation = values[0] as Conversation?
        @Suppress("UNCHECKED_CAST")
        val messages = values[1] as List<Message>
        val user = values[2] as User?
        @Suppress("UNCHECKED_CAST")
        val connectedEndpoints = values[3] as Set<String>
        val inputText = values[4] as String
        @Suppress("UNCHECKED_CAST")
        val triple = values[5] as Triple<String?, Boolean, Boolean>
        val (error, isDisconnecting, showDisconnectDialog) = triple

        android.util.Log.d("ChatViewModel", "Flow emitted - conversation: ${conversation?.id}, peer: ${conversation?.peer?.displayName}")

        val isConnected = conversation?.peer?.endpointId?.let { it in connectedEndpoints } ?: false

        ChatUiState(
            conversation = conversation,
            messages = messages,
            currentUser = user,
            inputText = inputText,
            isConnected = isConnected,
            isLoading = conversation == null,  // Still loading until conversation is available
            isDisconnecting = isDisconnecting,
            showDisconnectDialog = showDisconnectDialog,
            error = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState()
    )

    init {
        observeMessageEvents()
        markMessagesAsRead()
        verifyConversationExists()
    }

    private fun verifyConversationExists() {
        viewModelScope.launch {
            // Wait a bit for the Flow to emit
            delay(100)

            // Check if conversation is available
            val conversation = conversationRepository.getConversationById(conversationId)
            if (conversation != null) {
                android.util.Log.d("ChatViewModel", "Conversation verified: ${conversation.id}, peer: ${conversation.peer.displayName}")
                return@launch
            }

            android.util.Log.w("ChatViewModel", "Conversation not found immediately, waiting...")

            // If not, wait for it with a timeout
            val conversationReady = withTimeoutOrNull(5000L) {
                conversationRepository.getConversationByIdFlow(conversationId)
                    .filterNotNull()
                    .first()
            }

            if (conversationReady != null) {
                android.util.Log.d("ChatViewModel", "Conversation now available: ${conversationReady.id}")
            } else {
                android.util.Log.e("ChatViewModel", "Conversation still not found after 5s: $conversationId")
                _error.value = "Conversation not found - please go back and try again"
            }
        }
    }

    private fun observeMessageEvents() {
        viewModelScope.launch {
            messageHandler.events.collect { event ->
                when (event) {
                    is MessageEvent.NewMessage -> {
                        if (event.conversationId == conversationId) {
                            markMessagesAsRead()
                        }
                    }
                    is MessageEvent.PeerDeleted -> {
                        val conversation = uiState.value.conversation
                        if (conversation != null && conversation.peer.id == event.peerId) {
                            _peerDeleted.value = true
                        }
                    }
                    is MessageEvent.Error -> {
                        _error.value = event.message
                    }
                    else -> {}
                }
            }
        }
    }

    private fun markMessagesAsRead() {
        viewModelScope.launch {
            messageRepository.markAllAsRead(conversationId)
            conversationRepository.resetUnreadCount(conversationId)
        }
    }

    fun onInputTextChanged(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return

        val conversation = uiState.value.conversation
        val user = uiState.value.currentUser

        if (conversation == null) {
            _error.value = "Conversation not loaded yet"
            return
        }

        if (user == null) {
            _error.value = "User not loaded yet"
            return
        }

        val endpointId = conversation.peer.endpointId

        viewModelScope.launch {
            val messageId = generateUUID()
            val timestamp = System.currentTimeMillis()

            // Create message - always save even if not connected
            val message = Message(
                id = messageId,
                conversationId = conversationId,
                senderId = user.id,
                content = text,
                timestamp = timestamp,
                status = MessageStatus.PENDING,
                isOutgoing = true
            )

            // Save to database
            messageRepository.saveMessage(message)
            conversationRepository.updateLastMessage(conversationId, messageId)

            // Clear input
            _inputText.value = ""

            // Send if connected
            if (endpointId != null && uiState.value.isConnected) {
                sendMessagePayload(message, endpointId)
            } else {
                // Mark as failed if not connected so user knows
                messageRepository.updateMessageStatus(messageId, MessageStatus.FAILED)
                if (endpointId == null) {
                    _error.value = "Peer endpoint not available"
                } else {
                    _error.value = "Not connected to peer - message saved for later"
                }
            }
        }
    }

    private suspend fun sendMessagePayload(message: Message, endpointId: String) {
        val user = uiState.value.currentUser ?: return

        val payload = MessageProtocol.createMessage(
            messageId = message.id,
            senderId = user.id,
            content = message.content,
            timestamp = message.timestamp
        )

        nearbyManager.sendPayload(endpointId, payload)
            .onSuccess {
                messageRepository.updateMessageStatus(message.id, MessageStatus.SENT)
            }.onFailure { e ->
                messageRepository.updateMessageStatus(message.id, MessageStatus.FAILED)
                _error.value = "Failed to send message: ${e.message}"
            }
    }

    fun retryFailedMessages() {
        viewModelScope.launch {
            val conversation = uiState.value.conversation ?: return@launch
            val endpointId = conversation.peer.endpointId ?: return@launch

            if (!uiState.value.isConnected) {
                _error.value = "Not connected to peer"
                return@launch
            }

            val failedMessages = uiState.value.messages.filter {
                it.isOutgoing && it.status == MessageStatus.FAILED
            }

            for (message in failedMessages) {
                messageRepository.updateMessageStatus(message.id, MessageStatus.PENDING)
                sendMessagePayload(message, endpointId)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun showDisconnectDialog() {
        _showDisconnectDialog.value = true
    }

    fun dismissDisconnectDialog() {
        _showDisconnectDialog.value = false
    }

    fun disconnectPeer() {
        val conversation = uiState.value.conversation ?: return
        val peerId = conversation.peer.id

        _showDisconnectDialog.value = false
        _isDisconnecting.value = true

        viewModelScope.launch {
            val success = messageHandler.disconnectAndDeletePeer(peerId)
            _isDisconnecting.value = false
            if (success) {
                _peerDeleted.value = true
            } else {
                _error.value = "Failed to disconnect"
            }
        }
    }

    val peerDeleted: StateFlow<Boolean> = _peerDeleted

}
