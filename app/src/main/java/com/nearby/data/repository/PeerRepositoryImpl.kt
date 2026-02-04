package com.nearby.data.repository

import com.nearby.core.crypto.CryptoManager
import com.nearby.data.local.dao.PeerDao
import com.nearby.data.local.entity.ConnectionState
import com.nearby.data.local.entity.PeerEntity
import com.nearby.domain.model.Peer
import com.nearby.domain.repository.PeerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeerRepositoryImpl @Inject constructor(
    private val peerDao: PeerDao,
    private val cryptoManager: CryptoManager
) : PeerRepository {

    override fun getAllPeers(): Flow<List<Peer>> {
        return peerDao.getAllPeers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getPeersByState(state: ConnectionState): Flow<List<Peer>> {
        return peerDao.getPeersByState(state).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getConnectedPeers(): Flow<List<Peer>> {
        return peerDao.getPeersByState(ConnectionState.CONNECTED).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getSyncedPeers(): Flow<List<Peer>> {
        return peerDao.getSyncedPeers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getPeerById(peerId: String): Peer? {
        return peerDao.getPeerById(peerId)?.toDomain()
    }

    override suspend fun getPeerByEndpointId(endpointId: String): Peer? {
        return peerDao.getPeerByEndpointId(endpointId)?.toDomain()
    }

    override fun getPeerByIdFlow(peerId: String): Flow<Peer?> {
        return peerDao.getPeerByIdFlow(peerId).map { entity ->
            entity?.toDomain()
        }
    }

    override suspend fun savePeer(peer: Peer) {
        android.util.Log.d("PeerRepo", "Saving peer: ${peer.id}, name: ${peer.displayName}, endpoint: ${peer.endpointId}")
        peerDao.insertPeer(peer.toEntity())
        // Verify save
        val saved = peerDao.getPeerById(peer.id)
        android.util.Log.d("PeerRepo", "Peer save verified: ${saved != null}, id: ${saved?.id}")
    }

    override suspend fun updateConnectionState(peerId: String, state: ConnectionState) {
        peerDao.updateConnectionState(peerId, state)
    }

    override suspend fun updateEndpointId(peerId: String, endpointId: String?) {
        peerDao.updateEndpointId(peerId, endpointId)
    }

    override suspend fun updateVerificationStatus(peerId: String, isVerified: Boolean) {
        peerDao.updateVerificationStatus(peerId, isVerified)
    }

    override suspend fun updateLastSyncedAt(peerId: String) {
        peerDao.updateLastSyncedAt(peerId, System.currentTimeMillis())
    }

    override suspend fun deletePeer(peerId: String) {
        peerDao.deletePeer(peerId)
    }

    override suspend fun resetAllConnectionStates() {
        peerDao.resetConnectionStates(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED)
        peerDao.resetConnectionStates(ConnectionState.CONNECTING, ConnectionState.DISCONNECTED)
        peerDao.resetConnectionStates(ConnectionState.DISCOVERED, ConnectionState.UNKNOWN)
    }

    private fun PeerEntity.toDomain(): Peer {
        return Peer(
            id = id,
            endpointId = endpointId,
            displayName = displayName,
            publicKey = cryptoManager.decodePublicKey(publicKey),
            isVerified = isVerified,
            lastSeen = lastSeen,
            connectionState = connectionState,
            lastSyncedAt = lastSyncedAt
        )
    }

    private fun Peer.toEntity(): PeerEntity {
        return PeerEntity(
            id = id,
            endpointId = endpointId,
            displayName = displayName,
            publicKey = cryptoManager.encodePublicKey(publicKey),
            isVerified = isVerified,
            lastSeen = lastSeen,
            connectionState = connectionState,
            lastSyncedAt = lastSyncedAt
        )
    }
}
