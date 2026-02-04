package com.nearby.data.nearby

import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.nearby.core.util.Constants
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NearbyManager @Inject constructor(
    private val connectionsClient: ConnectionsClient
) {
    private val strategy = Strategy.P2P_CLUSTER

    private val _connectionState = MutableStateFlow<NearbyConnectionState>(NearbyConnectionState.Idle)
    val connectionState: StateFlow<NearbyConnectionState> = _connectionState.asStateFlow()

    private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
    val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = _discoveredEndpoints.asStateFlow()

    private val _connectedEndpoints = MutableStateFlow<Set<String>>(emptySet())
    val connectedEndpoints: StateFlow<Set<String>> = _connectedEndpoints.asStateFlow()

    private val _connectionRequests = MutableSharedFlow<com.nearby.data.nearby.ConnectionInfo>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val connectionRequests: Flow<com.nearby.data.nearby.ConnectionInfo> = _connectionRequests.asSharedFlow()

    private val _payloadEvents = MutableSharedFlow<PayloadEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val payloadEvents: Flow<PayloadEvent> = _payloadEvents.asSharedFlow()

    private val _disconnectionEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val disconnectionEvents: Flow<String> = _disconnectionEvents.asSharedFlow()

    private var isAdvertising = false
    private var isDiscovering = false

    // Mapping between peer IDs (from handshake) and endpoint IDs (from Nearby Connections)
    private val peerToEndpoint = mutableMapOf<String, String>()
    private val endpointToPeer = mutableMapOf<String, String>()

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val endpoint = DiscoveredEndpoint(
                endpointId = endpointId,
                endpointName = info.endpointName,
                serviceId = info.serviceId
            )
            _discoveredEndpoints.value = _discoveredEndpoints.value + endpoint
        }

        override fun onEndpointLost(endpointId: String) {
            _discoveredEndpoints.value = _discoveredEndpoints.value.filter { it.endpointId != endpointId }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            val info = com.nearby.data.nearby.ConnectionInfo(
                endpointId = endpointId,
                endpointName = connectionInfo.endpointName,
                authenticationDigits = connectionInfo.authenticationDigits,
                isIncomingConnection = connectionInfo.isIncomingConnection
            )
            _connectionState.value = NearbyConnectionState.Connecting(endpointId)
            _connectionRequests.tryEmit(info)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    _connectedEndpoints.value = _connectedEndpoints.value + endpointId
                    _discoveredEndpoints.value = _discoveredEndpoints.value.filter { it.endpointId != endpointId }
                    _connectionState.value = NearbyConnectionState.Connected(endpointId)
                    updateConnectionState()
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    _connectionState.value = NearbyConnectionState.Error("Connection rejected")
                    updateConnectionState()
                }
                else -> {
                    _connectionState.value = NearbyConnectionState.Error("Connection failed: ${result.status.statusMessage}")
                    updateConnectionState()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            _connectedEndpoints.value = _connectedEndpoints.value - endpointId
            _disconnectionEvents.tryEmit(endpointId)
            updateConnectionState()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val event = PayloadEvent.Received(
                endpointId = endpointId,
                payloadId = payload.id,
                bytes = payload.asBytes()
            )
            _payloadEvents.tryEmit(event)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val status = when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> PayloadTransferStatus.IN_PROGRESS
                PayloadTransferUpdate.Status.SUCCESS -> PayloadTransferStatus.SUCCESS
                PayloadTransferUpdate.Status.FAILURE -> PayloadTransferStatus.FAILURE
                PayloadTransferUpdate.Status.CANCELED -> PayloadTransferStatus.CANCELED
                else -> PayloadTransferStatus.FAILURE
            }
            val event = PayloadEvent.TransferUpdate(
                endpointId = endpointId,
                payloadId = update.payloadId,
                status = status,
                bytesTransferred = update.bytesTransferred,
                totalBytes = update.totalBytes
            )
            _payloadEvents.tryEmit(event)
        }
    }

    fun startAdvertising(userName: String): Flow<Result<Unit>> = callbackFlow {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()

        connectionsClient.startAdvertising(
            "${Constants.NEARBY_ADVERTISING_NAME_PREFIX}$userName",
            Constants.SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            isAdvertising = true
            updateConnectionState()
            trySend(Result.success(Unit))
        }.addOnFailureListener { e ->
            trySend(Result.failure(e))
        }

        awaitClose {
            // Flow closed - advertising continues independently
        }
    }

    fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        isAdvertising = false
        updateConnectionState()
    }

    fun startDiscovery(): Flow<Result<Unit>> = callbackFlow {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        connectionsClient.startDiscovery(
            Constants.SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            isDiscovering = true
            updateConnectionState()
            trySend(Result.success(Unit))
        }.addOnFailureListener { e ->
            trySend(Result.failure(e))
        }

        awaitClose {
            // Flow closed - discovery continues independently
        }
    }

    fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        isDiscovering = false
        _discoveredEndpoints.value = emptyList()
        updateConnectionState()
    }

    fun requestConnection(userName: String, endpointId: String): Flow<Result<Unit>> = callbackFlow {
        connectionsClient.requestConnection(
            userName,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            trySend(Result.success(Unit))
        }.addOnFailureListener { e ->
            trySend(Result.failure(e))
        }

        awaitClose { }
    }

    fun acceptConnection(endpointId: String): Flow<Result<Unit>> = callbackFlow {
        connectionsClient.acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener {
                trySend(Result.success(Unit))
            }
            .addOnFailureListener { e ->
                trySend(Result.failure(e))
            }

        awaitClose { }
    }

    fun rejectConnection(endpointId: String): Flow<Result<Unit>> = callbackFlow {
        connectionsClient.rejectConnection(endpointId)
            .addOnSuccessListener {
                trySend(Result.success(Unit))
            }
            .addOnFailureListener { e ->
                trySend(Result.failure(e))
            }

        awaitClose { }
    }

    suspend fun sendPayload(endpointId: String, bytes: ByteArray): Result<Long> =
        suspendCancellableCoroutine { continuation ->
            val payload = Payload.fromBytes(bytes)
            connectionsClient.sendPayload(endpointId, payload)
                .addOnSuccessListener {
                    if (continuation.isActive) {
                        continuation.resume(Result.success(payload.id))
                    }
                }
                .addOnFailureListener { e ->
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(e))
                    }
                }
        }

    suspend fun sendPayloadToMultiple(endpointIds: List<String>, bytes: ByteArray): Result<Long> =
        suspendCancellableCoroutine { continuation ->
            val payload = Payload.fromBytes(bytes)
            connectionsClient.sendPayload(endpointIds, payload)
                .addOnSuccessListener {
                    if (continuation.isActive) {
                        continuation.resume(Result.success(payload.id))
                    }
                }
                .addOnFailureListener { e ->
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(e))
                    }
                }
        }

    fun disconnectFromEndpoint(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        _connectedEndpoints.value = _connectedEndpoints.value - endpointId
    }

    fun stopAllEndpoints() {
        connectionsClient.stopAllEndpoints()
        _connectedEndpoints.value = emptySet()
        _discoveredEndpoints.value = emptyList()
        peerToEndpoint.clear()
        endpointToPeer.clear()
        isAdvertising = false
        isDiscovering = false
        _connectionState.value = NearbyConnectionState.Idle
    }

    /**
     * Register a mapping between peer ID and endpoint ID.
     * Called after handshake when we know the peer's ID.
     */
    fun registerPeerEndpoint(peerId: String, endpointId: String) {
        peerToEndpoint[peerId] = endpointId
        endpointToPeer[endpointId] = peerId
    }

    /**
     * Remove mapping when peer disconnects.
     */
    fun unregisterPeerEndpoint(peerId: String) {
        val endpointId = peerToEndpoint.remove(peerId)
        if (endpointId != null) {
            endpointToPeer.remove(endpointId)
        }
    }

    /**
     * Get endpoint ID for a peer ID.
     */
    fun getEndpointIdForPeer(peerId: String): String? {
        return peerToEndpoint[peerId]
    }

    /**
     * Get peer ID for an endpoint ID.
     */
    fun getPeerIdForEndpoint(endpointId: String): String? {
        return endpointToPeer[endpointId]
    }

    /**
     * Check if we're connected to a peer.
     */
    fun isConnectedToPeer(peerId: String): Boolean {
        val endpointId = peerToEndpoint[peerId] ?: return false
        return endpointId in _connectedEndpoints.value
    }

    private fun updateConnectionState() {
        _connectionState.value = when {
            isAdvertising && isDiscovering -> NearbyConnectionState.AdvertisingAndDiscovering
            isAdvertising -> NearbyConnectionState.Advertising
            isDiscovering -> NearbyConnectionState.Discovering
            else -> NearbyConnectionState.Idle
        }
    }
}
