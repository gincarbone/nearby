package com.nearby.data.nearby

import com.nearby.core.crypto.CryptoManager
import com.nearby.core.util.generateUUID
import com.nearby.data.local.entity.ConnectionState
import com.nearby.data.local.entity.MessageStatus
import com.nearby.data.nearby.mesh.MeshManager
import com.nearby.data.nearby.mesh.MeshProtocol
import com.nearby.data.nearby.protocol.MessageProtocol
import com.nearby.data.nearby.protocol.ParsedMessage
import com.nearby.domain.model.Message
import com.nearby.domain.model.Peer
import com.nearby.domain.repository.ConversationRepository
import com.nearby.domain.repository.MessageRepository
import com.nearby.domain.repository.PeerRepository
import com.nearby.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

sealed class MessageEvent {
    data class NewMessage(val message: Message, val conversationId: String, val senderName: String) : MessageEvent()
    data class MessageDelivered(val messageId: String) : MessageEvent()
    data class MessageRead(val messageIds: List<String>) : MessageEvent()
    data class PeerConnected(val peer: Peer, val conversationId: String) : MessageEvent()
    data class PeerDisconnected(val peerId: String) : MessageEvent()
    data class PeerDeleted(val peerId: String) : MessageEvent()
    data class HandshakeCompleted(val peerId: String) : MessageEvent()
    data class Error(val message: String) : MessageEvent()
}

@Singleton
class MessageHandler @Inject constructor(
    private val nearbyManager: NearbyManager,
    private val userRepository: UserRepository,
    private val peerRepository: PeerRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val cryptoManager: CryptoManager,
    private val meshManagerProvider: Provider<MeshManager>  // Lazy to break circular dependency
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Lazy access to MeshManager
    private val meshManager: MeshManager by lazy { meshManagerProvider.get() }

    private val _events = MutableSharedFlow<MessageEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<MessageEvent> = _events.asSharedFlow()

    private val sharedSecrets = mutableMapOf<String, ByteArray>()

    init {
        observePayloads()
        observeDisconnections()
    }

    private fun observePayloads() {
        scope.launch {
            nearbyManager.payloadEvents.collect { event ->
                when (event) {
                    is PayloadEvent.Received -> handleReceivedPayload(event)
                    is PayloadEvent.TransferUpdate -> handleTransferUpdate(event)
                }
            }
        }
    }

    private fun observeDisconnections() {
        scope.launch {
            nearbyManager.disconnectionEvents.collect { endpointId ->
                handleDisconnection(endpointId)
            }
        }
    }

    private suspend fun handleReceivedPayload(event: PayloadEvent.Received) {
        val bytes = event.bytes ?: return

        try {
            // First, try to parse as a mesh protocol message (defensive - don't let it break standard flow)
            try {
                if (meshManager.processMessage(event.endpointId, bytes)) {
                    return // Handled by mesh manager
                }
            } catch (e: Exception) {
                android.util.Log.w("MessageHandler", "Mesh processing failed, trying standard: ${e.message}")
                // Continue to standard message processing
            }

            // Parse as standard protocol message
            when (val parsed = MessageProtocol.parseMessage(bytes)) {
                is ParsedMessage.HandshakeInit -> handleHandshakeInit(event.endpointId, parsed)
                is ParsedMessage.HandshakeResponse -> handleHandshakeResponse(event.endpointId, parsed)
                is ParsedMessage.PlainMessage -> handlePlainMessage(event.endpointId, parsed)
                is ParsedMessage.EncryptedMessage -> handleEncryptedMessage(event.endpointId, parsed)
                is ParsedMessage.DeliveryReceipt -> handleDeliveryReceipt(parsed)
                is ParsedMessage.ReadReceipt -> handleReadReceipt(parsed)
                is ParsedMessage.Disconnect -> handleRemoteDisconnect(event.endpointId, parsed)
                null -> {
                    android.util.Log.w("MessageHandler", "Failed to parse message from ${event.endpointId}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageHandler", "Error handling payload: ${e.message}")
            _events.emit(MessageEvent.Error("Error processing message: ${e.message}"))
        }
    }

    private suspend fun handleHandshakeInit(endpointId: String, message: ParsedMessage.HandshakeInit) {
        try {
            val user = userRepository.getLocalUserSync() ?: return

            // Save or update peer
            val existingPeer = peerRepository.getPeerByEndpointId(endpointId)
            val peerPublicKey = cryptoManager.decodePublicKey(message.publicKey)

            val peerId = if (existingPeer != null) {
                // Update existing peer with new public key
                val updatedPeer = Peer(
                    id = existingPeer.id,
                    endpointId = endpointId,
                    displayName = message.displayName,
                    publicKey = peerPublicKey,
                    isVerified = false,
                    lastSeen = System.currentTimeMillis(),
                    connectionState = ConnectionState.CONNECTED
                )
                peerRepository.savePeer(updatedPeer)
                existingPeer.id
            } else {
                val newPeer = Peer(
                    id = message.peerId,
                    endpointId = endpointId,
                    displayName = message.displayName,
                    publicKey = peerPublicKey,
                    isVerified = false,
                    lastSeen = System.currentTimeMillis(),
                    connectionState = ConnectionState.CONNECTED
                )
                peerRepository.savePeer(newPeer)
                message.peerId
            }

            // Register peer-endpoint mapping
            nearbyManager.registerPeerEndpoint(peerId, endpointId)

            // Send handshake response
            val response = MessageProtocol.createHandshakeResponse(
                peerId = user.id,
                displayName = user.displayName,
                publicKey = cryptoManager.encodePublicKey(user.publicKey),
                accepted = true
            )

            nearbyManager.sendPayload(endpointId, response)

            // Create conversation
            val conversation = conversationRepository.getOrCreateConversation(peerId)
            android.util.Log.d("MessageHandler", "HandshakeInit - Created conversation: ${conversation.id} for peer: $peerId")

            // Verify conversation exists before emitting event (prevents race condition)
            val verifiedConversation = conversationRepository.getConversationById(conversation.id)
            if (verifiedConversation == null) {
                android.util.Log.e("MessageHandler", "HandshakeInit - Conversation not found after create: ${conversation.id}")
                return
            }

            // Update lastSyncedAt timestamp
            peerRepository.updateLastSyncedAt(peerId)

            // Notify mesh manager about new peer (non-blocking, don't let it break handshake)
            try {
                meshManager.onPeerConnected(peerId, message.displayName, endpointId)
            } catch (e: Exception) {
                android.util.Log.w("MessageHandler", "Failed to notify mesh manager: ${e.message}")
            }

            _events.emit(MessageEvent.HandshakeCompleted(peerId))

            val peer = peerRepository.getPeerById(peerId)
            if (peer != null) {
                android.util.Log.d("MessageHandler", "HandshakeInit - Emitting PeerConnected with conversationId: ${conversation.id}")
                _events.emit(MessageEvent.PeerConnected(peer, conversation.id))
            } else {
                android.util.Log.e("MessageHandler", "HandshakeInit - Peer not found after save: $peerId")
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageHandler", "Error in handshake init: ${e.message}")
            _events.emit(MessageEvent.Error("Handshake failed: ${e.message}"))
        }
    }

    private suspend fun handleHandshakeResponse(endpointId: String, message: ParsedMessage.HandshakeResponse) {
        try {
            if (!message.accepted) {
                _events.emit(MessageEvent.Error("Connection rejected by peer"))
                return
            }

            val peerPublicKey = cryptoManager.decodePublicKey(message.publicKey)

            // Update peer with public key
            val existingPeer = peerRepository.getPeerByEndpointId(endpointId)
            if (existingPeer != null) {
                val updatedPeer = Peer(
                    id = existingPeer.id,
                    endpointId = endpointId,
                    displayName = message.displayName,
                    publicKey = peerPublicKey,
                    isVerified = false,
                    lastSeen = System.currentTimeMillis(),
                    connectionState = ConnectionState.CONNECTED
                )
                peerRepository.savePeer(updatedPeer)

                // Register peer-endpoint mapping
                nearbyManager.registerPeerEndpoint(existingPeer.id, endpointId)

                val conversation = conversationRepository.getOrCreateConversation(existingPeer.id)
                android.util.Log.d("MessageHandler", "HandshakeResponse - Got conversation: ${conversation.id} for peer: ${existingPeer.id}")

                // Verify conversation exists before emitting event (prevents race condition)
                val verifiedConversation = conversationRepository.getConversationById(conversation.id)
                if (verifiedConversation == null) {
                    android.util.Log.e("MessageHandler", "HandshakeResponse - Conversation not found after create: ${conversation.id}")
                    return
                }

                // Update lastSyncedAt timestamp
                peerRepository.updateLastSyncedAt(existingPeer.id)

                // Notify mesh manager about new peer (non-blocking, don't let it break handshake)
                try {
                    meshManager.onPeerConnected(existingPeer.id, message.displayName, endpointId)
                } catch (e: Exception) {
                    android.util.Log.w("MessageHandler", "Failed to notify mesh manager: ${e.message}")
                }

                _events.emit(MessageEvent.HandshakeCompleted(existingPeer.id))
                android.util.Log.d("MessageHandler", "HandshakeResponse - Emitting PeerConnected with conversationId: ${conversation.id}")
                _events.emit(MessageEvent.PeerConnected(updatedPeer, conversation.id))
            } else {
                android.util.Log.e("MessageHandler", "HandshakeResponse - No existing peer found for endpoint: $endpointId")
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageHandler", "Error in handshake response: ${e.message}")
            _events.emit(MessageEvent.Error("Handshake response failed: ${e.message}"))
        }
    }

    private suspend fun handlePlainMessage(endpointId: String, parsed: ParsedMessage.PlainMessage) {
        try {
            val peer = peerRepository.getPeerByEndpointId(endpointId)
            if (peer == null) {
                android.util.Log.w("MessageHandler", "Received message from unknown endpoint: $endpointId")
                return
            }

            val conversation = conversationRepository.getOrCreateConversation(peer.id)

            val message = Message(
                id = parsed.id,
                conversationId = conversation.id,
                senderId = parsed.senderId,
                content = parsed.content,
                timestamp = parsed.timestamp,
                status = MessageStatus.DELIVERED,
                isOutgoing = false
            )

            messageRepository.saveMessage(message)
            conversationRepository.updateLastMessage(conversation.id, message.id)
            conversationRepository.incrementUnreadCount(conversation.id)

            // Send delivery receipt
            val receipt = MessageProtocol.createDeliveryReceipt(message.id)
            nearbyManager.sendPayload(endpointId, receipt)

            _events.emit(MessageEvent.NewMessage(message, conversation.id, peer.displayName))
        } catch (e: Exception) {
            android.util.Log.e("MessageHandler", "Error handling plain message: ${e.message}")
            _events.emit(MessageEvent.Error("Failed to process message: ${e.message}"))
        }
    }

    private suspend fun handleEncryptedMessage(endpointId: String, parsed: ParsedMessage.EncryptedMessage) {
        val peer = peerRepository.getPeerByEndpointId(endpointId) ?: return
        val sharedSecret = sharedSecrets[peer.id]

        if (sharedSecret == null) {
            _events.emit(MessageEvent.Error("No shared secret for peer"))
            return
        }

        try {
            val decryptedContent = cryptoManager.decrypt(parsed.encryptedContent, sharedSecret)
            val content = String(decryptedContent, Charsets.UTF_8)

            val conversation = conversationRepository.getOrCreateConversation(peer.id)

            val message = Message(
                id = parsed.id,
                conversationId = conversation.id,
                senderId = parsed.senderId,
                content = content,
                timestamp = parsed.timestamp,
                status = MessageStatus.DELIVERED,
                isOutgoing = false
            )

            messageRepository.saveMessage(message)
            conversationRepository.updateLastMessage(conversation.id, message.id)
            conversationRepository.incrementUnreadCount(conversation.id)

            // Send delivery receipt
            val receipt = MessageProtocol.createDeliveryReceipt(message.id)
            nearbyManager.sendPayload(endpointId, receipt)

            _events.emit(MessageEvent.NewMessage(message, conversation.id, peer.displayName))
        } catch (e: Exception) {
            _events.emit(MessageEvent.Error("Failed to decrypt message: ${e.message}"))
        }
    }

    private suspend fun handleDeliveryReceipt(receipt: ParsedMessage.DeliveryReceipt) {
        messageRepository.updateMessageStatus(receipt.messageId, MessageStatus.DELIVERED)
        _events.emit(MessageEvent.MessageDelivered(receipt.messageId))
    }

    private suspend fun handleReadReceipt(receipt: ParsedMessage.ReadReceipt) {
        receipt.messageIds.forEach { messageId ->
            messageRepository.updateMessageStatus(messageId, MessageStatus.READ)
        }
        _events.emit(MessageEvent.MessageRead(receipt.messageIds))
    }

    private suspend fun handleTransferUpdate(event: PayloadEvent.TransferUpdate) {
        if (event.status == PayloadTransferStatus.FAILURE) {
            _events.emit(MessageEvent.Error("Message transfer failed"))
        }
    }

    private suspend fun handleDisconnection(endpointId: String) {
        val peer = peerRepository.getPeerByEndpointId(endpointId)
        if (peer != null) {
            // Unregister mapping
            nearbyManager.unregisterPeerEndpoint(peer.id)

            // Notify mesh manager (non-blocking)
            try {
                meshManager.onPeerDisconnected(peer.id)
            } catch (e: Exception) {
                android.util.Log.w("MessageHandler", "Failed to notify mesh manager of disconnect: ${e.message}")
            }

            peerRepository.updateConnectionState(peer.id, ConnectionState.DISCONNECTED)
            _events.emit(MessageEvent.PeerDisconnected(peer.id))
        }
    }

    suspend fun sendHandshake(endpointId: String) {
        val user = userRepository.getLocalUserSync() ?: return

        val handshake = MessageProtocol.createHandshakeInit(
            peerId = user.id,
            displayName = user.displayName,
            publicKey = cryptoManager.encodePublicKey(user.publicKey)
        )

        nearbyManager.sendPayload(endpointId, handshake)
    }

    fun setSharedSecret(peerId: String, secret: ByteArray) {
        sharedSecrets[peerId] = secret
    }

    fun clearSharedSecret(peerId: String) {
        sharedSecrets.remove(peerId)
    }

    private suspend fun handleRemoteDisconnect(endpointId: String, disconnect: ParsedMessage.Disconnect) {
        try {
            val peer = peerRepository.getPeerByEndpointId(endpointId) ?: return

            // Delete conversation and messages
            val conversation = conversationRepository.getConversationByPeerId(peer.id)
            if (conversation != null) {
                messageRepository.deleteMessagesForConversation(conversation.id)
                conversationRepository.deleteConversation(conversation.id)
            }

            // Delete peer
            peerRepository.deletePeer(peer.id)

            // Disconnect from endpoint
            nearbyManager.disconnectFromEndpoint(endpointId)

            // Clear shared secret
            clearSharedSecret(peer.id)

            _events.emit(MessageEvent.PeerDeleted(peer.id))
        } catch (e: Exception) {
            android.util.Log.e("MessageHandler", "Error handling remote disconnect: ${e.message}")
        }
    }

    suspend fun disconnectAndDeletePeer(peerId: String): Boolean {
        try {
            val user = userRepository.getLocalUserSync() ?: return false
            val peer = peerRepository.getPeerById(peerId) ?: return false
            val endpointId = peer.endpointId

            // Send disconnect message to remote peer first
            if (endpointId != null) {
                val disconnectMsg = MessageProtocol.createDisconnect(user.id)
                nearbyManager.sendPayload(endpointId, disconnectMsg)

                // Disconnect from endpoint
                nearbyManager.disconnectFromEndpoint(endpointId)
            }

            // Delete conversation and messages locally
            val conversation = conversationRepository.getConversationByPeerId(peerId)
            if (conversation != null) {
                messageRepository.deleteMessagesForConversation(conversation.id)
                conversationRepository.deleteConversation(conversation.id)
            }

            // Delete peer locally
            peerRepository.deletePeer(peerId)

            // Clear shared secret
            clearSharedSecret(peerId)

            _events.emit(MessageEvent.PeerDeleted(peerId))
            return true
        } catch (e: Exception) {
            android.util.Log.e("MessageHandler", "Error disconnecting peer: ${e.message}")
            _events.emit(MessageEvent.Error("Failed to disconnect: ${e.message}"))
            return false
        }
    }
}
