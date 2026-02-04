package com.nearby.data.nearby.mesh

import org.json.JSONArray
import org.json.JSONObject

/**
 * Protocol extensions for mesh networking.
 * Handles topology discovery, message routing, and store & forward.
 */
object MeshProtocol {
    // Mesh message types (starting from 20 to avoid conflicts)
    const val TYPE_TOPOLOGY_ANNOUNCE = 20    // Node announces itself and neighbors
    const val TYPE_TOPOLOGY_REQUEST = 21     // Request topology from a neighbor
    const val TYPE_ROUTED_MESSAGE = 22       // Message being routed through mesh
    const val TYPE_ROUTE_ACK = 23            // Acknowledgment for routed message
    const val TYPE_STORE_CONFIRM = 24        // Confirm message stored for offline node
    const val TYPE_NODE_CAPABILITIES = 25    // Announce node capabilities change

    /**
     * Create a topology announcement message.
     * Sent periodically and when topology changes.
     */
    fun createTopologyAnnounce(
        nodeId: String,
        displayName: String,
        connectedPeers: List<String>,
        capabilities: NodeCapabilities,
        ttl: Int = 3
    ): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_TOPOLOGY_ANNOUNCE)
            put("nodeId", nodeId)
            put("displayName", displayName)
            put("connectedPeers", JSONArray(connectedPeers))
            put("capabilities", capabilities.toJson())
            put("timestamp", System.currentTimeMillis())
            put("ttl", ttl)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Create a topology request message.
     * Ask a neighbor for its routing table.
     */
    fun createTopologyRequest(nodeId: String): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_TOPOLOGY_REQUEST)
            put("nodeId", nodeId)
            put("timestamp", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Create a routed message that travels through the mesh.
     */
    fun createRoutedMessage(
        messageId: String,
        originalSender: String,
        finalDestination: String,
        payload: ByteArray,
        ttl: Int = 10,
        path: List<String> = emptyList(),
        requiresStoreForward: Boolean = false
    ): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_ROUTED_MESSAGE)
            put("messageId", messageId)
            put("originalSender", originalSender)
            put("finalDestination", finalDestination)
            put("payload", android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP))
            put("timestamp", System.currentTimeMillis())
            put("ttl", ttl)
            put("path", JSONArray(path))
            put("requiresStoreForward", requiresStoreForward)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Create an acknowledgment for a routed message.
     */
    fun createRouteAck(
        messageId: String,
        originalSender: String,
        status: RouteAckStatus,
        storedBy: String? = null
    ): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_ROUTE_ACK)
            put("messageId", messageId)
            put("originalSender", originalSender)
            put("status", status.name)
            put("timestamp", System.currentTimeMillis())
            storedBy?.let { put("storedBy", it) }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Create a store confirmation message.
     */
    fun createStoreConfirm(
        messageId: String,
        storedBy: String,
        finalDestination: String,
        expiresAt: Long
    ): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_STORE_CONFIRM)
            put("messageId", messageId)
            put("storedBy", storedBy)
            put("finalDestination", finalDestination)
            put("expiresAt", expiresAt)
            put("timestamp", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Create a capabilities update message.
     */
    fun createCapabilitiesUpdate(
        nodeId: String,
        capabilities: NodeCapabilities
    ): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_NODE_CAPABILITIES)
            put("nodeId", nodeId)
            put("capabilities", capabilities.toJson())
            put("timestamp", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Parse a mesh protocol message.
     */
    fun parseMessage(bytes: ByteArray): ParsedMeshMessage? {
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val type = json.optInt("type", -1)

            when (type) {
                TYPE_TOPOLOGY_ANNOUNCE -> {
                    val peers = mutableListOf<String>()
                    val peersArray = json.getJSONArray("connectedPeers")
                    for (i in 0 until peersArray.length()) {
                        peers.add(peersArray.getString(i))
                    }
                    ParsedMeshMessage.TopologyAnnounce(
                        nodeId = json.getString("nodeId"),
                        displayName = json.getString("displayName"),
                        connectedPeers = peers,
                        capabilities = NodeCapabilities.fromJson(json.getJSONObject("capabilities")),
                        timestamp = json.getLong("timestamp"),
                        ttl = json.getInt("ttl")
                    )
                }
                TYPE_TOPOLOGY_REQUEST -> ParsedMeshMessage.TopologyRequest(
                    nodeId = json.getString("nodeId"),
                    timestamp = json.getLong("timestamp")
                )
                TYPE_ROUTED_MESSAGE -> {
                    val path = mutableListOf<String>()
                    val pathArray = json.getJSONArray("path")
                    for (i in 0 until pathArray.length()) {
                        path.add(pathArray.getString(i))
                    }
                    ParsedMeshMessage.RoutedMessage(
                        messageId = json.getString("messageId"),
                        originalSender = json.getString("originalSender"),
                        finalDestination = json.getString("finalDestination"),
                        payload = android.util.Base64.decode(json.getString("payload"), android.util.Base64.NO_WRAP),
                        timestamp = json.getLong("timestamp"),
                        ttl = json.getInt("ttl"),
                        path = path,
                        requiresStoreForward = json.optBoolean("requiresStoreForward", false)
                    )
                }
                TYPE_ROUTE_ACK -> ParsedMeshMessage.RouteAck(
                    messageId = json.getString("messageId"),
                    originalSender = json.getString("originalSender"),
                    status = RouteAckStatus.valueOf(json.getString("status")),
                    storedBy = json.optString("storedBy", null),
                    timestamp = json.getLong("timestamp")
                )
                TYPE_STORE_CONFIRM -> ParsedMeshMessage.StoreConfirm(
                    messageId = json.getString("messageId"),
                    storedBy = json.getString("storedBy"),
                    finalDestination = json.getString("finalDestination"),
                    expiresAt = json.getLong("expiresAt"),
                    timestamp = json.getLong("timestamp")
                )
                TYPE_NODE_CAPABILITIES -> ParsedMeshMessage.CapabilitiesUpdate(
                    nodeId = json.getString("nodeId"),
                    capabilities = NodeCapabilities.fromJson(json.getJSONObject("capabilities")),
                    timestamp = json.getLong("timestamp")
                )
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Node capabilities that determine relay behavior.
 */
data class NodeCapabilities(
    val canForward: Boolean = true,
    val canStoreForward: Boolean = true,
    val availableStorageMB: Int = 50,
    val messageRetentionHours: Int = 168, // 7 days
    val uptimeClass: UptimeClass = UptimeClass.FREQUENT,
    val connectionQuality: ConnectionQuality = ConnectionQuality.MEDIUM
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("canForward", canForward)
        put("canStoreForward", canStoreForward)
        put("availableStorageMB", availableStorageMB)
        put("messageRetentionHours", messageRetentionHours)
        put("uptimeClass", uptimeClass.name)
        put("connectionQuality", connectionQuality.name)
    }

    companion object {
        fun fromJson(json: JSONObject): NodeCapabilities = NodeCapabilities(
            canForward = json.optBoolean("canForward", true),
            canStoreForward = json.optBoolean("canStoreForward", true),
            availableStorageMB = json.optInt("availableStorageMB", 50),
            messageRetentionHours = json.optInt("messageRetentionHours", 168),
            uptimeClass = try {
                UptimeClass.valueOf(json.optString("uptimeClass", "FREQUENT"))
            } catch (e: Exception) {
                UptimeClass.FREQUENT
            },
            connectionQuality = try {
                ConnectionQuality.valueOf(json.optString("connectionQuality", "MEDIUM"))
            } catch (e: Exception) {
                ConnectionQuality.MEDIUM
            }
        )
    }
}

enum class UptimeClass {
    ALWAYS_ON,    // Dedicated relay, always available
    FREQUENT,     // Usually online (main phone)
    OCCASIONAL    // Sometimes online
}

enum class ConnectionQuality {
    HIGH,         // WiFi, stable
    MEDIUM,       // Mobile data, decent
    LOW           // Unstable connection
}

enum class RouteAckStatus {
    DELIVERED,    // Message delivered to final destination
    STORED,       // Message stored for offline delivery
    FORWARDED,    // Message forwarded to next hop
    FAILED        // Failed to route/deliver
}

/**
 * Parsed mesh protocol messages.
 */
sealed class ParsedMeshMessage {
    data class TopologyAnnounce(
        val nodeId: String,
        val displayName: String,
        val connectedPeers: List<String>,
        val capabilities: NodeCapabilities,
        val timestamp: Long,
        val ttl: Int
    ) : ParsedMeshMessage()

    data class TopologyRequest(
        val nodeId: String,
        val timestamp: Long
    ) : ParsedMeshMessage()

    data class RoutedMessage(
        val messageId: String,
        val originalSender: String,
        val finalDestination: String,
        val payload: ByteArray,
        val timestamp: Long,
        val ttl: Int,
        val path: List<String>,
        val requiresStoreForward: Boolean
    ) : ParsedMeshMessage()

    data class RouteAck(
        val messageId: String,
        val originalSender: String,
        val status: RouteAckStatus,
        val storedBy: String?,
        val timestamp: Long
    ) : ParsedMeshMessage()

    data class StoreConfirm(
        val messageId: String,
        val storedBy: String,
        val finalDestination: String,
        val expiresAt: Long,
        val timestamp: Long
    ) : ParsedMeshMessage()

    data class CapabilitiesUpdate(
        val nodeId: String,
        val capabilities: NodeCapabilities,
        val timestamp: Long
    ) : ParsedMeshMessage()
}
