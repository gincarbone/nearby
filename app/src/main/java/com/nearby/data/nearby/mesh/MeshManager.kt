package com.nearby.data.nearby.mesh

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.nearby.data.local.dao.StoredMessageDao
import com.nearby.data.local.entity.StoredMessageEntity
import com.nearby.data.local.entity.StoredMessageStatus
import com.nearby.data.nearby.NearbyManager
import com.nearby.domain.repository.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MeshManager coordinates all mesh networking functionality:
 * - Topology discovery and routing table management
 * - Message routing through the mesh
 * - Store and forward for offline nodes
 * - Adaptive relay behavior based on device context
 *
 * Every node in the network runs this manager, making it simultaneously:
 * - A USER (sends/receives own messages)
 * - A RELAY (forwards messages for others)
 * - A STORE & FORWARD node (stores messages for offline nodes)
 */
@Singleton
class MeshManager
@Inject
constructor(
        @ApplicationContext private val context: Context,
        private val nearbyManager: NearbyManager,
        private val userRepository: UserRepository,
        private val storedMessageDao: StoredMessageDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Local node ID (user's ID)
    private var localNodeId: String? = null

    // Routing table for this node
    private var routingTable: RoutingTable? = null

    // Current node capabilities (adaptive based on context)
    private val _capabilities = MutableStateFlow(NodeCapabilities())
    val capabilities: StateFlow<NodeCapabilities> = _capabilities.asStateFlow()

    // Events emitted by the mesh manager
    private val _meshEvents = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)
    val meshEvents: SharedFlow<MeshEvent> = _meshEvents.asSharedFlow()

    // Set of message IDs we've already seen (to prevent loops)
    private val seenMessageIds = mutableSetOf<String>()
    private val maxSeenMessages = 1000

    // Topology announcement interval
    private val topologyAnnounceIntervalMs = 60_000L // 30 seconds

    // Store & forward settings
    private val defaultRetentionHours = 168 // 7 days
    private val maxDeliveryAttempts = 10

    /**
     * Initialize the mesh manager. Called when the user is set up and nearby connections are ready.
     */
    suspend fun initialize() {
        val user = userRepository.getLocalUserSync() ?: return
        localNodeId = user.id
        routingTable = RoutingTable(user.id)

        // Update capabilities based on current device context
        updateCapabilities()

        // Start periodic tasks
        startTopologyAnnouncements()
        startStoreForwardDelivery()
        startCapabilitiesMonitor()
        startRouteCleanup()

        android.util.Log.d("MeshManager", "Initialized for node: ${user.id}")
    }

    /** Process an incoming mesh protocol message. */
    suspend fun processMessage(fromEndpointId: String, bytes: ByteArray): Boolean {
        val parsed = MeshProtocol.parseMessage(bytes) ?: return false
        val fromNodeId =
                routingTable?.let { table ->
                    table.getAllKnownNodes()
                            .find {
                                nearbyManager.getEndpointIdForPeer(it.nodeId) == fromEndpointId
                            }
                            ?.nodeId
                }
                        ?: fromEndpointId

        when (parsed) {
            is ParsedMeshMessage.TopologyAnnounce -> handleTopologyAnnounce(fromNodeId, parsed)
            is ParsedMeshMessage.TopologyRequest -> handleTopologyRequest(fromEndpointId)
            is ParsedMeshMessage.RoutedMessage -> handleRoutedMessage(fromNodeId, parsed)
            is ParsedMeshMessage.RouteAck -> handleRouteAck(parsed)
            is ParsedMeshMessage.StoreConfirm -> handleStoreConfirm(parsed)
            is ParsedMeshMessage.CapabilitiesUpdate -> handleCapabilitiesUpdate(parsed)
        }

        return true
    }

    /**
     * Send a message through the mesh network. This is the main entry point for sending messages.
     */
    suspend fun sendMeshMessage(
            destinationNodeId: String,
            payload: ByteArray,
            messageId: String
    ): MeshSendResult {
        val table = routingTable ?: return MeshSendResult.NotInitialized
        val myNodeId = localNodeId ?: return MeshSendResult.NotInitialized

        // Check if we've already processed this message
        if (messageId in seenMessageIds) {
            return MeshSendResult.AlreadyProcessed
        }
        markMessageSeen(messageId)

        // Case 1: Direct neighbor - send directly
        if (table.isDirectNeighbor(destinationNodeId)) {
            val endpointId = nearbyManager.getEndpointIdForPeer(destinationNodeId)
            if (endpointId != null) {
                val routedMsg =
                        MeshProtocol.createRoutedMessage(
                                messageId = messageId,
                                originalSender = myNodeId,
                                finalDestination = destinationNodeId,
                                payload = payload,
                                path = listOf(myNodeId)
                        )
                nearbyManager.sendPayload(endpointId, routedMsg)
                return MeshSendResult.SentDirect
            }
        }

        // Case 2: Not direct neighbor - find route
        val route = table.findRoute(destinationNodeId)
        if (route != null) {
            val nextHopEndpoint = nearbyManager.getEndpointIdForPeer(route.nextHop)
            if (nextHopEndpoint != null) {
                val routedMsg =
                        MeshProtocol.createRoutedMessage(
                                messageId = messageId,
                                originalSender = myNodeId,
                                finalDestination = destinationNodeId,
                                payload = payload,
                                path = listOf(myNodeId),
                                requiresStoreForward = true
                        )
                nearbyManager.sendPayload(nextHopEndpoint, routedMsg)
                return MeshSendResult.Routed(route.nextHop, route.hopCount)
            }
        }

        // Case 3: No route - try to store for later delivery
        if (_capabilities.value.canStoreForward) {
            storeMessageForDelivery(messageId, myNodeId, destinationNodeId, payload)
            return MeshSendResult.StoredLocally
        }

        // Case 4: Find another node to store the message
        val storeNode = table.findBestStoreNode(excludeNodes = setOf(myNodeId))
        if (storeNode != null) {
            val storeEndpoint = nearbyManager.getEndpointIdForPeer(storeNode.nodeId)
            if (storeEndpoint != null) {
                val routedMsg =
                        MeshProtocol.createRoutedMessage(
                                messageId = messageId,
                                originalSender = myNodeId,
                                finalDestination = destinationNodeId,
                                payload = payload,
                                path = listOf(myNodeId),
                                requiresStoreForward = true
                        )
                nearbyManager.sendPayload(storeEndpoint, routedMsg)
                return MeshSendResult.SentToStore(storeNode.nodeId)
            }
        }

        return MeshSendResult.NoRoute
    }

    /** Called when a new peer connects. */
    fun onPeerConnected(peerId: String, displayName: String, endpointId: String) {
        val table = routingTable ?: return

        table.addDirectNeighbor(
                nodeId = peerId,
                nodeInfo =
                        NodeInfo(
                                nodeId = peerId,
                                displayName = displayName,
                                capabilities =
                                        null, // Will be updated when we receive their topology
                                // announce
                                lastSeen = System.currentTimeMillis()
                        )
        )

        // Send our topology to the new neighbor
        scope.launch {
            delay(500) // Small delay to let connection stabilize
            sendTopologyAnnounce(endpointId)

            // Check if we have stored messages for this peer
            deliverStoredMessagesTo(peerId)
        }

        android.util.Log.d("MeshManager", "Peer connected: $peerId, routing table updated")
    }

    /** Called when a peer disconnects. */
    fun onPeerDisconnected(peerId: String) {
        routingTable?.removeDirectNeighbor(peerId)
        android.util.Log.d("MeshManager", "Peer disconnected: $peerId, routing table updated")
    }

    // ==================== Private Methods ====================

    private suspend fun handleTopologyAnnounce(
            fromNodeId: String,
            announce: ParsedMeshMessage.TopologyAnnounce
    ) {
        val table = routingTable ?: return

        // Update our routing table
        table.processTopologyAnnounce(fromNodeId, announce)

        // Propagate if TTL > 0
        if (announce.ttl > 1) {
            val newAnnounce =
                    MeshProtocol.createTopologyAnnounce(
                            nodeId = announce.nodeId,
                            displayName = announce.displayName,
                            connectedPeers = announce.connectedPeers,
                            capabilities = announce.capabilities,
                            ttl = announce.ttl - 1
                    )

            // Forward to all neighbors except the one who sent it
            table.directNeighbors.value
                    .filter { it != fromNodeId && it != announce.nodeId }
                    .forEach { neighborId ->
                        nearbyManager.getEndpointIdForPeer(neighborId)?.let { endpoint ->
                            nearbyManager.sendPayload(endpoint, newAnnounce)
                        }
                    }
        }

        _meshEvents.emit(MeshEvent.TopologyUpdated(table.getAllKnownNodes().size))
    }

    private suspend fun handleTopologyRequest(fromEndpointId: String) {
        sendTopologyAnnounce(fromEndpointId)
    }

    private suspend fun handleRoutedMessage(
            fromNodeId: String,
            message: ParsedMeshMessage.RoutedMessage
    ) {
        val table = routingTable ?: return
        val myNodeId = localNodeId ?: return

        // Check for loops
        if (message.messageId in seenMessageIds) {
            android.util.Log.d("MeshManager", "Dropping duplicate message: ${message.messageId}")
            return
        }
        markMessageSeen(message.messageId)

        // Check TTL
        if (message.ttl <= 0) {
            android.util.Log.d("MeshManager", "Message TTL expired: ${message.messageId}")
            sendRouteAck(message, RouteAckStatus.FAILED)
            return
        }

        // Am I the final destination?
        if (message.finalDestination == myNodeId) {
            // Deliver to local user
            _meshEvents.emit(
                    MeshEvent.MessageReceived(
                            messageId = message.messageId,
                            senderId = message.originalSender,
                            payload = message.payload
                    )
            )
            sendRouteAck(message, RouteAckStatus.DELIVERED)
            return
        }

        // I'm a relay - forward the message
        val newPath = message.path + myNodeId

        // Case 1: Destination is my direct neighbor
        if (table.isDirectNeighbor(message.finalDestination)) {
            val endpointId = nearbyManager.getEndpointIdForPeer(message.finalDestination)
            if (endpointId != null) {
                val forwardMsg =
                        MeshProtocol.createRoutedMessage(
                                messageId = message.messageId,
                                originalSender = message.originalSender,
                                finalDestination = message.finalDestination,
                                payload = message.payload,
                                ttl = message.ttl - 1,
                                path = newPath,
                                requiresStoreForward = message.requiresStoreForward
                        )
                nearbyManager.sendPayload(endpointId, forwardMsg)
                sendRouteAck(message, RouteAckStatus.FORWARDED)
                return
            }
        }

        // Case 2: Find next hop from routing table
        val nextHop = table.getNextHop(message.finalDestination)
        if (nextHop != null && nextHop !in message.path) {
            val endpointId = nearbyManager.getEndpointIdForPeer(nextHop)
            if (endpointId != null) {
                val forwardMsg =
                        MeshProtocol.createRoutedMessage(
                                messageId = message.messageId,
                                originalSender = message.originalSender,
                                finalDestination = message.finalDestination,
                                payload = message.payload,
                                ttl = message.ttl - 1,
                                path = newPath,
                                requiresStoreForward = message.requiresStoreForward
                        )
                nearbyManager.sendPayload(endpointId, forwardMsg)
                sendRouteAck(message, RouteAckStatus.FORWARDED)
                return
            }
        }

        // Case 3: No route available - store for later if requested
        if (message.requiresStoreForward && _capabilities.value.canStoreForward) {
            storeMessageForDelivery(
                    message.messageId,
                    message.originalSender,
                    message.finalDestination,
                    message.payload
            )
            sendRouteAck(message, RouteAckStatus.STORED, storedBy = myNodeId)
            return
        }

        // Failed to route
        sendRouteAck(message, RouteAckStatus.FAILED)
    }

    private suspend fun handleRouteAck(ack: ParsedMeshMessage.RouteAck) {
        _meshEvents.emit(
                MeshEvent.MessageAcknowledged(
                        messageId = ack.messageId,
                        status = ack.status,
                        storedBy = ack.storedBy
                )
        )
    }

    private suspend fun handleStoreConfirm(confirm: ParsedMeshMessage.StoreConfirm) {
        _meshEvents.emit(
                MeshEvent.MessageStoredRemotely(
                        messageId = confirm.messageId,
                        storedBy = confirm.storedBy,
                        expiresAt = confirm.expiresAt
                )
        )
    }

    private suspend fun handleCapabilitiesUpdate(update: ParsedMeshMessage.CapabilitiesUpdate) {
        routingTable?.let { table ->
            val existingNode = table.getAllKnownNodes().find { it.nodeId == update.nodeId }
            if (existingNode != null) {
                // Re-announce with updated capabilities would be handled by topology announce
            }
        }
    }

    private suspend fun sendTopologyAnnounce(toEndpointId: String? = null) {
        val table = routingTable ?: return
        val user = userRepository.getLocalUserSync() ?: return

        val announce =
                MeshProtocol.createTopologyAnnounce(
                        nodeId = user.id,
                        displayName = user.displayName,
                        connectedPeers = table.getConnectedPeerIds(),
                        capabilities = _capabilities.value
                )

        if (toEndpointId != null) {
            nearbyManager.sendPayload(toEndpointId, announce)
        } else {
            // Broadcast to all neighbors
            table.directNeighbors.value.forEach { neighborId ->
                nearbyManager.getEndpointIdForPeer(neighborId)?.let { endpoint ->
                    nearbyManager.sendPayload(endpoint, announce)
                }
            }
        }
    }

    private suspend fun sendRouteAck(
            message: ParsedMeshMessage.RoutedMessage,
            status: RouteAckStatus,
            storedBy: String? = null
    ) {
        val myNodeId = localNodeId ?: return

        // Send ACK back through the path
        if (message.path.isNotEmpty()) {
            val previousHop = message.path.last()
            val endpointId = nearbyManager.getEndpointIdForPeer(previousHop)
            if (endpointId != null) {
                val ack =
                        MeshProtocol.createRouteAck(
                                messageId = message.messageId,
                                originalSender = message.originalSender,
                                status = status,
                                storedBy = storedBy
                        )
                nearbyManager.sendPayload(endpointId, ack)
            }
        }
    }

    private suspend fun storeMessageForDelivery(
            messageId: String,
            originalSender: String,
            finalDestination: String,
            payload: ByteArray
    ) {
        val now = System.currentTimeMillis()
        val retentionMs = _capabilities.value.messageRetentionHours * 60 * 60 * 1000L

        val storedMessage =
                StoredMessageEntity(
                        messageId = messageId,
                        originalSender = originalSender,
                        finalDestination = finalDestination,
                        payload = payload,
                        originalTimestamp = now,
                        storedAt = now,
                        expiresAt = now + retentionMs
                )

        storedMessageDao.insertStoredMessage(storedMessage)
        _meshEvents.emit(MeshEvent.MessageStoredLocally(messageId, finalDestination))

        android.util.Log.d("MeshManager", "Stored message $messageId for $finalDestination")
    }

    private suspend fun deliverStoredMessagesTo(destinationId: String) {
        val pendingMessages = storedMessageDao.getPendingMessagesForDestination(destinationId)

        for (stored in pendingMessages) {
            if (stored.deliveryAttempts >= maxDeliveryAttempts) {
                storedMessageDao.updateStatus(stored.messageId, StoredMessageStatus.FAILED)
                continue
            }

            storedMessageDao.incrementDeliveryAttempts(stored.messageId, System.currentTimeMillis())

            val result =
                    sendMeshMessage(
                            destinationNodeId = stored.finalDestination,
                            payload = stored.payload,
                            messageId = stored.messageId
                    )

            when (result) {
                is MeshSendResult.SentDirect, is MeshSendResult.Routed -> {
                    storedMessageDao.updateStatus(stored.messageId, StoredMessageStatus.DELIVERED)
                    android.util.Log.d(
                            "MeshManager",
                            "Delivered stored message: ${stored.messageId}"
                    )
                }
                else -> {
                    android.util.Log.d(
                            "MeshManager",
                            "Failed to deliver stored message: ${stored.messageId}"
                    )
                }
            }
        }
    }

    private fun startTopologyAnnouncements() {
        scope.launch {
            while (true) {
                delay(topologyAnnounceIntervalMs)
                sendTopologyAnnounce()
            }
        }
    }

    private fun startStoreForwardDelivery() {
        scope.launch {
            while (true) {
                delay(60_000) // Check every minute

                // Clean up expired messages
                storedMessageDao.markExpiredMessages()
                storedMessageDao.cleanupCompletedMessages()

                // Try to deliver pending messages
                val destinations = storedMessageDao.getDestinationsWithPendingMessages()
                for (destId in destinations) {
                    if (routingTable?.canReach(destId) == true) {
                        deliverStoredMessagesTo(destId)
                    }
                }
            }
        }
    }

    private fun startCapabilitiesMonitor() {
        scope.launch {
            while (true) {
                delay(30_000) // Check every 30 seconds
                updateCapabilities()
            }
        }
    }

    private fun startRouteCleanup() {
        scope.launch {
            while (true) {
                delay(5 * 60 * 1000) // Every 5 minutes
                routingTable?.cleanupStaleRoutes()
            }
        }
    }

    private fun updateCapabilities() {
        val isCharging = isDeviceCharging()
        val isOnWifi = isOnWifi()
        val batteryLevel = getBatteryLevel()

        val newCapabilities =
                when {
                    isOnWifi && isCharging ->
                            NodeCapabilities(
                                    canForward = true,
                                    canStoreForward = true,
                                    availableStorageMB = 100,
                                    messageRetentionHours = 168,
                                    uptimeClass = UptimeClass.ALWAYS_ON,
                                    connectionQuality = ConnectionQuality.HIGH
                            )
                    isOnWifi ->
                            NodeCapabilities(
                                    canForward = true,
                                    canStoreForward = true,
                                    availableStorageMB = 50,
                                    messageRetentionHours =
                                            36, // Reduced from 48 for battery optimization
                                    uptimeClass = UptimeClass.FREQUENT,
                                    connectionQuality = ConnectionQuality.HIGH
                            )
                    batteryLevel > 30 ->
                            NodeCapabilities(
                                    canForward = true,
                                    canStoreForward = true,
                                    availableStorageMB = 20,
                                    messageRetentionHours =
                                            18, // Reduced from 24 for battery optimization
                                    uptimeClass = UptimeClass.FREQUENT,
                                    connectionQuality = ConnectionQuality.MEDIUM
                            )
                    batteryLevel > 15 ->
                            NodeCapabilities(
                                    canForward = true,
                                    canStoreForward = false,
                                    availableStorageMB = 0,
                                    messageRetentionHours = 0,
                                    uptimeClass = UptimeClass.OCCASIONAL,
                                    connectionQuality = ConnectionQuality.LOW
                            )
                    else ->
                            NodeCapabilities(
                                    canForward = false,
                                    canStoreForward = false,
                                    availableStorageMB = 0,
                                    messageRetentionHours = 0,
                                    uptimeClass = UptimeClass.OCCASIONAL,
                                    connectionQuality = ConnectionQuality.LOW
                            )
                }

        if (newCapabilities != _capabilities.value) {
            _capabilities.value = newCapabilities
            scope.launch {
                // Announce capability change to neighbors
                val myNodeId = localNodeId ?: return@launch
                val update = MeshProtocol.createCapabilitiesUpdate(myNodeId, newCapabilities)
                routingTable?.directNeighbors?.value?.forEach { neighborId ->
                    nearbyManager.getEndpointIdForPeer(neighborId)?.let { endpoint ->
                        nearbyManager.sendPayload(endpoint, update)
                    }
                }
            }
        }
    }

    private fun isDeviceCharging(): Boolean {
        val batteryStatus =
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun isOnWifi(): Boolean {
        val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun getBatteryLevel(): Int {
        val batteryStatus =
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 50
    }

    private fun markMessageSeen(messageId: String) {
        seenMessageIds.add(messageId)
        // Keep the set from growing too large
        if (seenMessageIds.size > maxSeenMessages) {
            val toRemove = seenMessageIds.take(maxSeenMessages / 2)
            seenMessageIds.removeAll(toRemove.toSet())
        }
    }
}

/** Result of sending a message through the mesh. */
sealed class MeshSendResult {
    object NotInitialized : MeshSendResult()
    object AlreadyProcessed : MeshSendResult()
    object SentDirect : MeshSendResult()
    data class Routed(val nextHop: String, val totalHops: Int) : MeshSendResult()
    object StoredLocally : MeshSendResult()
    data class SentToStore(val storeNodeId: String) : MeshSendResult()
    object NoRoute : MeshSendResult()
}

/** Events emitted by the mesh manager. */
sealed class MeshEvent {
    data class TopologyUpdated(val knownNodes: Int) : MeshEvent()
    data class MessageReceived(
            val messageId: String,
            val senderId: String,
            val payload: ByteArray
    ) : MeshEvent()
    data class MessageAcknowledged(
            val messageId: String,
            val status: RouteAckStatus,
            val storedBy: String?
    ) : MeshEvent()
    data class MessageStoredLocally(val messageId: String, val forDestination: String) :
            MeshEvent()
    data class MessageStoredRemotely(
            val messageId: String,
            val storedBy: String,
            val expiresAt: Long
    ) : MeshEvent()
}
