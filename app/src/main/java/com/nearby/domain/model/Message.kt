package com.nearby.domain.model

import com.nearby.data.local.entity.MessageStatus

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    val timestamp: Long,
    val status: MessageStatus,
    val isOutgoing: Boolean
)
