package com.nearby.domain.usecase

import com.nearby.core.util.generateUUID
import com.nearby.data.local.entity.ConnectionState
import com.nearby.data.nearby.DiscoveredEndpoint
import com.nearby.data.nearby.NearbyManager
import com.nearby.domain.model.Peer
import com.nearby.domain.repository.ConversationRepository
import com.nearby.domain.repository.PeerRepository
import com.nearby.domain.repository.UserRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ConnectToPeerUseCase @Inject constructor(
    private val userRepository: UserRepository,
    private val peerRepository: PeerRepository,
    private val conversationRepository: ConversationRepository,
    private val nearbyManager: NearbyManager
) {
    suspend operator fun invoke(endpoint: DiscoveredEndpoint): Result<String> {
        return try {
            val user = userRepository.getLocalUserSync()
                ?: return Result.failure(Exception("User not found"))

            // Request connection
            val connectionResult = nearbyManager.requestConnection(user.displayName, endpoint.endpointId)
                .first()

            connectionResult.getOrThrow()

            // Connection request sent successfully
            // The actual connection handling will be done via callbacks

            Result.success(endpoint.endpointId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createPeerFromConnection(
        endpointId: String,
        endpointName: String
    ): Result<String> {
        return try {
            val user = userRepository.getLocalUserSync()
                ?: return Result.failure(Exception("User not found"))

            val displayName = endpointName.removePrefix("NearBy|")

            // Check if peer already exists
            val existingPeer = peerRepository.getPeerByEndpointId(endpointId)

            val peerId = if (existingPeer != null) {
                peerRepository.updateConnectionState(existingPeer.id, ConnectionState.CONNECTED)
                peerRepository.updateEndpointId(existingPeer.id, endpointId)
                existingPeer.id
            } else {
                val peer = Peer(
                    id = generateUUID(),
                    endpointId = endpointId,
                    displayName = displayName,
                    publicKey = user.publicKey, // Temporary
                    isVerified = false,
                    lastSeen = System.currentTimeMillis(),
                    connectionState = ConnectionState.CONNECTED
                )
                peerRepository.savePeer(peer)
                peer.id
            }

            // Create conversation if needed
            val conversation = conversationRepository.getOrCreateConversation(peerId)

            Result.success(conversation.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
