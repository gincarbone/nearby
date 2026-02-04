package com.nearby.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ConnectionState {
    UNKNOWN,
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey
    val id: String,
    val endpointId: String?,
    val displayName: String,
    val publicKey: ByteArray,
    val isVerified: Boolean = false,
    val lastSeen: Long,
    val connectionState: ConnectionState = ConnectionState.UNKNOWN,
    val lastSyncedAt: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PeerEntity

        if (id != other.id) return false
        if (endpointId != other.endpointId) return false
        if (displayName != other.displayName) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (isVerified != other.isVerified) return false
        if (lastSeen != other.lastSeen) return false
        if (connectionState != other.connectionState) return false
        if (lastSyncedAt != other.lastSyncedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (endpointId?.hashCode() ?: 0)
        result = 31 * result + displayName.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + isVerified.hashCode()
        result = 31 * result + lastSeen.hashCode()
        result = 31 * result + connectionState.hashCode()
        result = 31 * result + (lastSyncedAt?.hashCode() ?: 0)
        return result
    }
}
