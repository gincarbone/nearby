package com.nearby.domain.repository

import com.nearby.data.local.entity.ConnectionState
import com.nearby.domain.model.Peer
import kotlinx.coroutines.flow.Flow

interface PeerRepository {
    fun getAllPeers(): Flow<List<Peer>>
    fun getPeersByState(state: ConnectionState): Flow<List<Peer>>
    fun getConnectedPeers(): Flow<List<Peer>>
    fun getSyncedPeers(): Flow<List<Peer>>
    suspend fun getPeerById(peerId: String): Peer?
    suspend fun getPeerByEndpointId(endpointId: String): Peer?
    fun getPeerByIdFlow(peerId: String): Flow<Peer?>
    suspend fun savePeer(peer: Peer)
    suspend fun updateConnectionState(peerId: String, state: ConnectionState)
    suspend fun updateEndpointId(peerId: String, endpointId: String?)
    suspend fun updateVerificationStatus(peerId: String, isVerified: Boolean)
    suspend fun updateLastSyncedAt(peerId: String)
    suspend fun deletePeer(peerId: String)
    suspend fun resetAllConnectionStates()
}
