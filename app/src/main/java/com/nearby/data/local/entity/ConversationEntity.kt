package com.nearby.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = PeerEntity::class,
            parentColumns = ["id"],
            childColumns = ["peerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("peerId")]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val peerId: String,
    val lastMessageId: String?,
    val unreadCount: Int = 0,
    val updatedAt: Long
)
