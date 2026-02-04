package com.nearby.domain.model

data class Conversation(
    val id: String,
    val peer: Peer,
    val lastMessage: Message?,
    val unreadCount: Int,
    val updatedAt: Long
)
