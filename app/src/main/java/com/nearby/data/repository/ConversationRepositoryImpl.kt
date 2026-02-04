package com.nearby.data.repository

import com.nearby.core.crypto.CryptoManager
import com.nearby.core.util.generateUUID
import com.nearby.data.local.dao.ConversationDao
import com.nearby.data.local.dao.MessageDao
import com.nearby.data.local.dao.PeerDao
import com.nearby.data.local.entity.ConversationEntity
import com.nearby.data.local.entity.MessageEntity
import com.nearby.data.local.entity.PeerEntity
import com.nearby.domain.model.Conversation
import com.nearby.domain.model.Message
import com.nearby.domain.model.Peer
import com.nearby.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val peerDao: PeerDao,
    private val messageDao: MessageDao,
    private val cryptoManager: CryptoManager
) : ConversationRepository {

    override fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations().map { conversations ->
            conversations.mapNotNull { conversation ->
                val peer = peerDao.getPeerById(conversation.peerId)
                val lastMessage = conversation.lastMessageId?.let { messageDao.getMessageById(it) }
                if (peer != null) {
                    conversation.toDomain(peer, lastMessage)
                } else null
            }
        }
    }

    override suspend fun getConversationById(conversationId: String): Conversation? {
        val conversation = conversationDao.getConversationById(conversationId) ?: return null
        val peer = peerDao.getPeerById(conversation.peerId) ?: return null
        val lastMessage = conversation.lastMessageId?.let { messageDao.getMessageById(it) }
        return conversation.toDomain(peer, lastMessage)
    }

    override fun getConversationByIdFlow(conversationId: String): Flow<Conversation?> {
        android.util.Log.d("ConversationRepo", "getConversationByIdFlow called for: $conversationId")
        return conversationDao.getConversationByIdFlow(conversationId).flatMapLatest { conversation ->
            android.util.Log.d("ConversationRepo", "Conversation from DB: ${conversation?.id}, peerId: ${conversation?.peerId}")
            if (conversation == null) {
                android.util.Log.w("ConversationRepo", "Conversation not found in DB: $conversationId")
                flowOf(null)
            } else {
                // Combine with peer flow so updates to peer are reflected
                peerDao.getPeerByIdFlow(conversation.peerId).map { peer ->
                    android.util.Log.d("ConversationRepo", "Peer from DB: ${peer?.id}, name: ${peer?.displayName}")
                    if (peer == null) {
                        android.util.Log.w("ConversationRepo", "Peer not found for conversation: ${conversation.peerId}")
                        null
                    } else {
                        val lastMessage = conversation.lastMessageId?.let { messageDao.getMessageById(it) }
                        conversation.toDomain(peer, lastMessage)
                    }
                }
            }
        }
    }

    override suspend fun getConversationByPeerId(peerId: String): Conversation? {
        val conversation = conversationDao.getConversationByPeerId(peerId) ?: return null
        val peer = peerDao.getPeerById(peerId) ?: return null
        val lastMessage = conversation.lastMessageId?.let { messageDao.getMessageById(it) }
        return conversation.toDomain(peer, lastMessage)
    }

    override suspend fun getOrCreateConversation(peerId: String): Conversation {
        android.util.Log.d("ConversationRepo", "getOrCreateConversation for peerId: $peerId")

        val existing = conversationDao.getConversationByPeerId(peerId)
        if (existing != null) {
            android.util.Log.d("ConversationRepo", "Found existing conversation: ${existing.id}")
            val peer = peerDao.getPeerById(peerId)!!
            val lastMessage = existing.lastMessageId?.let { messageDao.getMessageById(it) }
            return existing.toDomain(peer, lastMessage)
        }

        val peer = peerDao.getPeerById(peerId)
        if (peer == null) {
            android.util.Log.e("ConversationRepo", "Peer not found when creating conversation: $peerId")
            throw IllegalStateException("Peer not found: $peerId")
        }
        android.util.Log.d("ConversationRepo", "Peer found: ${peer.displayName}")

        val conversation = ConversationEntity(
            id = generateUUID(),
            peerId = peerId,
            lastMessageId = null,
            unreadCount = 0,
            updatedAt = System.currentTimeMillis()
        )
        android.util.Log.d("ConversationRepo", "Creating new conversation: ${conversation.id} for peer: $peerId")
        conversationDao.insertConversation(conversation)

        // Verify insertion
        val verified = conversationDao.getConversationById(conversation.id)
        android.util.Log.d("ConversationRepo", "Conversation insert verified: ${verified != null}")

        return conversation.toDomain(peer, null)
    }

    override suspend fun updateLastMessage(conversationId: String, messageId: String) {
        conversationDao.updateLastMessage(conversationId, messageId, System.currentTimeMillis())
    }

    override suspend fun incrementUnreadCount(conversationId: String) {
        conversationDao.incrementUnreadCount(conversationId)
    }

    override suspend fun resetUnreadCount(conversationId: String) {
        conversationDao.resetUnreadCount(conversationId)
    }

    override fun getTotalUnreadCount(): Flow<Int> {
        return conversationDao.getTotalUnreadCount().map { it ?: 0 }
    }

    override suspend fun deleteConversation(conversationId: String) {
        conversationDao.deleteConversation(conversationId)
    }

    private fun ConversationEntity.toDomain(peer: PeerEntity, lastMessage: MessageEntity?): Conversation {
        return Conversation(
            id = id,
            peer = peer.toDomain(),
            lastMessage = lastMessage?.toDomain(),
            unreadCount = unreadCount,
            updatedAt = updatedAt
        )
    }

    private fun PeerEntity.toDomain(): Peer {
        return Peer(
            id = id,
            endpointId = endpointId,
            displayName = displayName,
            publicKey = cryptoManager.decodePublicKey(publicKey),
            isVerified = isVerified,
            lastSeen = lastSeen,
            connectionState = connectionState
        )
    }

    private fun MessageEntity.toDomain(): Message {
        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            content = String(content, Charsets.UTF_8),
            timestamp = timestamp,
            status = status,
            isOutgoing = isOutgoing
        )
    }
}
