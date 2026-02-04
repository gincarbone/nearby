package com.nearby.domain.repository

import com.nearby.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getAllConversations(): Flow<List<Conversation>>
    suspend fun getConversationById(conversationId: String): Conversation?
    fun getConversationByIdFlow(conversationId: String): Flow<Conversation?>
    suspend fun getConversationByPeerId(peerId: String): Conversation?
    suspend fun getOrCreateConversation(peerId: String): Conversation
    suspend fun updateLastMessage(conversationId: String, messageId: String)
    suspend fun incrementUnreadCount(conversationId: String)
    suspend fun resetUnreadCount(conversationId: String)
    fun getTotalUnreadCount(): Flow<Int>
    suspend fun deleteConversation(conversationId: String)
}
