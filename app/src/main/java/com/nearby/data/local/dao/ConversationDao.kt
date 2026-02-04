package com.nearby.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.nearby.data.local.entity.ConversationEntity
import com.nearby.data.local.entity.PeerEntity
import kotlinx.coroutines.flow.Flow

data class ConversationWithPeer(
    @Embedded val conversation: ConversationEntity,
    @Embedded(prefix = "peer_") val peer: PeerEntity
)

@Dao
interface ConversationDao {

    @Query("""
        SELECT c.id, c.peerId, c.lastMessageId, c.unreadCount, c.updatedAt,
               p.id AS peer_id, p.endpointId AS peer_endpointId, p.displayName AS peer_displayName,
               p.publicKey AS peer_publicKey, p.isVerified AS peer_isVerified,
               p.lastSeen AS peer_lastSeen, p.connectionState AS peer_connectionState
        FROM conversations c
        INNER JOIN peers p ON c.peerId = p.id
        ORDER BY c.updatedAt DESC
    """)
    fun getAllConversationsWithPeers(): Flow<List<ConversationWithPeer>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversationById(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun getConversationByIdFlow(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE peerId = :peerId")
    suspend fun getConversationByPeerId(peerId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE peerId = :peerId")
    fun getConversationByPeerIdFlow(peerId: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET lastMessageId = :messageId, updatedAt = :updatedAt WHERE id = :conversationId")
    suspend fun updateLastMessage(conversationId: String, messageId: String, updatedAt: Long)

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE id = :conversationId")
    suspend fun incrementUnreadCount(conversationId: String)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun resetUnreadCount(conversationId: String)

    @Query("SELECT SUM(unreadCount) FROM conversations")
    fun getTotalUnreadCount(): Flow<Int?>

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}
