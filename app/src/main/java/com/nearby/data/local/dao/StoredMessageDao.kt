package com.nearby.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nearby.data.local.entity.StoredMessageEntity
import com.nearby.data.local.entity.StoredMessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface StoredMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStoredMessage(message: StoredMessageEntity)

    @Update
    suspend fun updateStoredMessage(message: StoredMessageEntity)

    @Query("SELECT * FROM stored_messages WHERE messageId = :messageId")
    suspend fun getStoredMessageById(messageId: String): StoredMessageEntity?

    @Query("SELECT * FROM stored_messages WHERE finalDestination = :destinationId AND status = :status")
    suspend fun getStoredMessagesForDestination(
        destinationId: String,
        status: StoredMessageStatus = StoredMessageStatus.PENDING
    ): List<StoredMessageEntity>

    @Query("SELECT * FROM stored_messages WHERE finalDestination = :destinationId AND status = 'PENDING' ORDER BY originalTimestamp ASC")
    suspend fun getPendingMessagesForDestination(destinationId: String): List<StoredMessageEntity>

    @Query("SELECT * FROM stored_messages WHERE status = 'PENDING'")
    fun observePendingMessages(): Flow<List<StoredMessageEntity>>

    @Query("SELECT DISTINCT finalDestination FROM stored_messages WHERE status = 'PENDING'")
    suspend fun getDestinationsWithPendingMessages(): List<String>

    @Query("UPDATE stored_messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: StoredMessageStatus)

    @Query("UPDATE stored_messages SET deliveryAttempts = deliveryAttempts + 1, lastDeliveryAttempt = :timestamp WHERE messageId = :messageId")
    suspend fun incrementDeliveryAttempts(messageId: String, timestamp: Long)

    @Query("DELETE FROM stored_messages WHERE messageId = :messageId")
    suspend fun deleteStoredMessage(messageId: String)

    @Query("DELETE FROM stored_messages WHERE finalDestination = :destinationId")
    suspend fun deleteStoredMessagesForDestination(destinationId: String)

    @Query("DELETE FROM stored_messages WHERE expiresAt < :currentTime")
    suspend fun deleteExpiredMessages(currentTime: Long = System.currentTimeMillis())

    @Query("UPDATE stored_messages SET status = 'EXPIRED' WHERE expiresAt < :currentTime AND status = 'PENDING'")
    suspend fun markExpiredMessages(currentTime: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM stored_messages WHERE status = 'PENDING'")
    suspend fun getPendingMessageCount(): Int

    @Query("SELECT SUM(LENGTH(payload)) FROM stored_messages WHERE status = 'PENDING'")
    suspend fun getTotalStoredSize(): Long?

    @Query("SELECT * FROM stored_messages WHERE status = 'PENDING' ORDER BY expiresAt ASC LIMIT :limit")
    suspend fun getOldestPendingMessages(limit: Int): List<StoredMessageEntity>

    @Query("DELETE FROM stored_messages WHERE status IN ('DELIVERED', 'EXPIRED', 'FAILED')")
    suspend fun cleanupCompletedMessages()
}
