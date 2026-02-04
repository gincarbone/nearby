package com.nearby.data.repository

import com.nearby.data.local.dao.MessageDao
import com.nearby.data.local.entity.MessageEntity
import com.nearby.data.local.entity.MessageStatus
import com.nearby.domain.model.Message
import com.nearby.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao
) : MessageRepository {

    override fun getMessagesForConversation(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getMessageById(messageId: String): Message? {
        return messageDao.getMessageById(messageId)?.toDomain()
    }

    override suspend fun getPendingMessages(): List<Message> {
        return messageDao.getMessagesByStatus(MessageStatus.PENDING).map { it.toDomain() }
    }

    override suspend fun saveMessage(message: Message) {
        messageDao.insertMessage(message.toEntity())
    }

    override suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateMessageStatus(messageId, status)
    }

    override suspend fun markAllAsRead(conversationId: String) {
        messageDao.markAllAsRead(conversationId)
    }

    override suspend fun deleteMessage(messageId: String) {
        messageDao.deleteMessage(messageId)
    }

    override suspend fun deleteMessagesForConversation(conversationId: String) {
        messageDao.deleteMessagesForConversation(conversationId)
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

    private fun Message.toEntity(): MessageEntity {
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            content = content.toByteArray(Charsets.UTF_8),
            timestamp = timestamp,
            status = status,
            isOutgoing = isOutgoing
        )
    }
}
