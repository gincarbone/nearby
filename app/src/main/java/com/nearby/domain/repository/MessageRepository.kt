package com.nearby.domain.repository

import com.nearby.data.local.entity.MessageStatus
import com.nearby.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>
    suspend fun getMessageById(messageId: String): Message?
    suspend fun getPendingMessages(): List<Message>
    suspend fun saveMessage(message: Message)
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)
    suspend fun markAllAsRead(conversationId: String)
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteMessagesForConversation(conversationId: String)
}
