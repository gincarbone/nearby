package com.nearby.domain.usecase

import com.nearby.core.util.generateUUID
import com.nearby.data.local.entity.MessageStatus
import com.nearby.data.nearby.NearbyManager
import com.nearby.data.nearby.protocol.MessageProtocol
import com.nearby.domain.model.Message
import com.nearby.domain.repository.ConversationRepository
import com.nearby.domain.repository.MessageRepository
import com.nearby.domain.repository.PeerRepository
import com.nearby.domain.repository.UserRepository
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val userRepository: UserRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val nearbyManager: NearbyManager
) {
    suspend operator fun invoke(
        conversationId: String,
        content: String
    ): Result<Message> {
        return try {
            val user = userRepository.getLocalUserSync()
                ?: return Result.failure(Exception("User not found"))

            val conversation = conversationRepository.getConversationById(conversationId)
                ?: return Result.failure(Exception("Conversation not found"))

            val peer = peerRepository.getPeerById(conversation.peer.id)
                ?: return Result.failure(Exception("Peer not found"))

            val messageId = generateUUID()
            val timestamp = System.currentTimeMillis()

            val message = Message(
                id = messageId,
                conversationId = conversationId,
                senderId = user.id,
                content = content,
                timestamp = timestamp,
                status = MessageStatus.PENDING,
                isOutgoing = true
            )

            // Save message
            messageRepository.saveMessage(message)
            conversationRepository.updateLastMessage(conversationId, messageId)

            // Try to send if connected
            val endpointId = peer.endpointId
            if (endpointId != null && nearbyManager.connectedEndpoints.value.contains(endpointId)) {
                val payload = MessageProtocol.createMessage(
                    messageId = messageId,
                    senderId = user.id,
                    content = content,
                    timestamp = timestamp
                )

                nearbyManager.sendPayload(endpointId, payload)
                    .onSuccess {
                        messageRepository.updateMessageStatus(messageId, MessageStatus.SENT)
                    }.onFailure {
                        messageRepository.updateMessageStatus(messageId, MessageStatus.FAILED)
                    }
            }

            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
