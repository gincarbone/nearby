package com.nearby.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for storing messages that need to be delivered to offline nodes.
 * Part of the Store & Forward mechanism in mesh networking.
 */
@Entity(
    tableName = "stored_messages",
    indices = [
        Index(value = ["finalDestination"]),
        Index(value = ["expiresAt"])
    ]
)
data class StoredMessageEntity(
    @PrimaryKey
    val messageId: String,

    // Original sender of the message
    val originalSender: String,

    // Final destination node ID
    val finalDestination: String,

    // Encrypted payload (the actual message content)
    val payload: ByteArray,

    // When the message was originally sent
    val originalTimestamp: Long,

    // When we stored this message
    val storedAt: Long,

    // When this stored message expires
    val expiresAt: Long,

    // Number of delivery attempts
    val deliveryAttempts: Int = 0,

    // Last delivery attempt timestamp
    val lastDeliveryAttempt: Long? = null,

    // Status of the stored message
    val status: StoredMessageStatus = StoredMessageStatus.PENDING
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StoredMessageEntity

        if (messageId != other.messageId) return false
        if (originalSender != other.originalSender) return false
        if (finalDestination != other.finalDestination) return false
        if (!payload.contentEquals(other.payload)) return false
        if (originalTimestamp != other.originalTimestamp) return false
        if (storedAt != other.storedAt) return false
        if (expiresAt != other.expiresAt) return false
        if (deliveryAttempts != other.deliveryAttempts) return false
        if (lastDeliveryAttempt != other.lastDeliveryAttempt) return false
        if (status != other.status) return false

        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + originalSender.hashCode()
        result = 31 * result + finalDestination.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + originalTimestamp.hashCode()
        result = 31 * result + storedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + deliveryAttempts
        result = 31 * result + (lastDeliveryAttempt?.hashCode() ?: 0)
        result = 31 * result + status.hashCode()
        return result
    }
}

enum class StoredMessageStatus {
    PENDING,      // Waiting for destination to come online
    DELIVERING,   // Currently attempting delivery
    DELIVERED,    // Successfully delivered
    EXPIRED,      // Message expired before delivery
    FAILED        // Failed to deliver after max attempts
}
