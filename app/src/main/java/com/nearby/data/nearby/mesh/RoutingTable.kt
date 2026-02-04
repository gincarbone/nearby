package com.nearby.data.nearby.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Distributed routing table for mesh networking.
 * Each node maintains its own routing table with information about how to reach other nodes.
 */
class RoutingTable(private val localNodeId: String) {

    // Map: destinationNodeId -> RouteEntry
    private val routes = ConcurrentHashMap<String, RouteEntry>()

    // Map: nodeId -> NodeInfo (known nodes in the network)
    private val knownNodes = ConcurrentHashMap<String, NodeInfo>()

    // Direct neighbors (nodes we're directly connected to)
    private val _directNeighbors = MutableStateFlow<Set<String>>(emptySet())
    val directNeighbors: StateFlow<Set<String>> = _directNeighbors.asStateFlow()

    // Topology change events
    private val _topologyVersion = MutableStateFlow(0L)
    val topologyVersion: StateFlow<Long> = _topologyVersion.asStateFlow()

    /**
     * Add or update a direct neighbor (directly connected peer).
     */
    fun addDirectNeighbor(nodeId: String, nodeInfo: NodeInfo) {
        knownNodes[nodeId] = nodeInfo
        routes[nodeId] = RouteEntry(
            destination = nodeId,
            nextHop = nodeId,
            hopCount = 1,
            lastUpdated = System.currentTimeMillis(),
            capabilities = nodeInfo.capabilities
        )
        _directNeighbors.value = _directNeighbors.value + nodeId
        incrementTopologyVersion()
    }

    /**
     * Remove a direct neighbor (disconnected peer).
     */
    fun removeDirectNeighbor(nodeId: String) {
        _directNeighbors.value = _directNeighbors.value - nodeId

        // Remove routes that go through this neighbor
        val routesToRemove = routes.filter { it.value.nextHop == nodeId }
        routesToRemove.keys.forEach { routes.remove(it) }

        incrementTopologyVersion()
    }

    /**
     * Update routing table based on topology announcement from a neighbor.
     */
    fun processTopologyAnnounce(
        fromNodeId: String,
        announce: ParsedMeshMessage.TopologyAnnounce
    ) {
        // Update info about the announcing node
        knownNodes[announce.nodeId] = NodeInfo(
            nodeId = announce.nodeId,
            displayName = announce.displayName,
            capabilities = announce.capabilities,
            lastSeen = announce.timestamp
        )

        // If this is a direct neighbor, update its info
        if (announce.nodeId == fromNodeId) {
            routes[announce.nodeId] = RouteEntry(
                destination = announce.nodeId,
                nextHop = announce.nodeId,
                hopCount = 1,
                lastUpdated = announce.timestamp,
                capabilities = announce.capabilities
            )
        }

        // Learn about nodes connected to this neighbor (2+ hops away)
        for (peerId in announce.connectedPeers) {
            if (peerId == localNodeId) continue // Skip ourselves

            val existingRoute = routes[peerId]
            val newHopCount = 2 // Through the announcing node

            // Update route if:
            // 1. We don't have a route to this node, OR
            // 2. This route is shorter, OR
            // 3. This route goes through the same next hop (update timestamp)
            if (existingRoute == null ||
                newHopCount < existingRoute.hopCount ||
                existingRoute.nextHop == fromNodeId) {

                routes[peerId] = RouteEntry(
                    destination = peerId,
                    nextHop = fromNodeId,
                    hopCount = newHopCount,
                    lastUpdated = announce.timestamp,
                    capabilities = knownNodes[peerId]?.capabilities
                )
            }
        }

        incrementTopologyVersion()
    }

    /**
     * Find the best route to a destination.
     */
    fun findRoute(destinationId: String): RouteEntry? {
        if (destinationId == localNodeId) return null
        return routes[destinationId]
    }

    /**
     * Find the next hop to reach a destination.
     */
    fun getNextHop(destinationId: String): String? {
        return findRoute(destinationId)?.nextHop
    }

    /**
     * Check if a node is directly connected.
     */
    fun isDirectNeighbor(nodeId: String): Boolean {
        return _directNeighbors.value.contains(nodeId)
    }

    /**
     * Check if we can reach a node (have a route to it).
     */
    fun canReach(nodeId: String): Boolean {
        return routes.containsKey(nodeId)
    }

    /**
     * Get all known nodes in the network.
     */
    fun getAllKnownNodes(): List<NodeInfo> {
        return knownNodes.values.toList()
    }

    /**
     * Get all routes.
     */
    fun getAllRoutes(): List<RouteEntry> {
        return routes.values.toList()
    }

    /**
     * Get nodes that can store messages (for store & forward).
     */
    fun getStoreCapableNodes(): List<NodeInfo> {
        return knownNodes.values.filter {
            it.capabilities?.canStoreForward == true
        }
    }

    /**
     * Find the best node to store a message for an offline destination.
     * Prioritizes:
     * 1. ALWAYS_ON nodes
     * 2. Nodes with more storage
     * 3. Nodes with better connection quality
     */
    fun findBestStoreNode(excludeNodes: Set<String> = emptySet()): NodeInfo? {
        return knownNodes.values
            .filter { it.nodeId !in excludeNodes }
            .filter { it.capabilities?.canStoreForward == true }
            .sortedWith(
                compareBy<NodeInfo> {
                    // ALWAYS_ON = 0, FREQUENT = 1, OCCASIONAL = 2
                    it.capabilities?.uptimeClass?.ordinal ?: 3
                }.thenByDescending {
                    it.capabilities?.availableStorageMB ?: 0
                }.thenBy {
                    it.capabilities?.connectionQuality?.ordinal ?: 3
                }
            )
            .firstOrNull()
    }

    /**
     * Clean up stale routes (not updated in a while).
     */
    fun cleanupStaleRoutes(maxAgeMs: Long = 5 * 60 * 1000) { // 5 minutes default
        val now = System.currentTimeMillis()
        val staleRoutes = routes.filter {
            (now - it.value.lastUpdated) > maxAgeMs && !isDirectNeighbor(it.key)
        }
        staleRoutes.keys.forEach { routes.remove(it) }

        if (staleRoutes.isNotEmpty()) {
            incrementTopologyVersion()
        }
    }

    /**
     * Get routing table summary for topology announcement.
     */
    fun getConnectedPeerIds(): List<String> {
        return _directNeighbors.value.toList()
    }

    private fun incrementTopologyVersion() {
        _topologyVersion.value = System.currentTimeMillis()
    }

    override fun toString(): String {
        return buildString {
            appendLine("RoutingTable for $localNodeId:")
            appendLine("Direct neighbors: ${_directNeighbors.value}")
            appendLine("Routes:")
            routes.values.sortedBy { it.hopCount }.forEach { route ->
                appendLine("  ${route.destination} via ${route.nextHop} (${route.hopCount} hops)")
            }
        }
    }
}

/**
 * Information about a known node in the network.
 */
data class NodeInfo(
    val nodeId: String,
    val displayName: String,
    val capabilities: NodeCapabilities?,
    val lastSeen: Long
)

/**
 * A route entry in the routing table.
 */
data class RouteEntry(
    val destination: String,      // Final destination node ID
    val nextHop: String,          // Next hop to reach destination
    val hopCount: Int,            // Number of hops to destination
    val lastUpdated: Long,        // When this route was last updated
    val capabilities: NodeCapabilities? = null  // Destination's capabilities
)
