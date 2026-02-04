package com.nearby.domain.model

import com.nearby.data.local.entity.ConnectionState
import java.security.PublicKey

data class Peer(
    val id: String,
    val endpointId: String?,
    val displayName: String,
    val publicKey: PublicKey,
    val isVerified: Boolean,
    val lastSeen: Long,
    val connectionState: ConnectionState,
    val lastSyncedAt: Long? = null
)
