package com.nearby.data.nearby

sealed class NearbyConnectionState {
    object Idle : NearbyConnectionState()
    object Advertising : NearbyConnectionState()
    object Discovering : NearbyConnectionState()
    object AdvertisingAndDiscovering : NearbyConnectionState()
    data class Connecting(val endpointId: String) : NearbyConnectionState()
    data class Connected(val endpointId: String) : NearbyConnectionState()
    data class Error(val message: String) : NearbyConnectionState()
}

data class DiscoveredEndpoint(
    val endpointId: String,
    val endpointName: String,
    val serviceId: String
)

data class ConnectionInfo(
    val endpointId: String,
    val endpointName: String,
    val authenticationDigits: String,
    val isIncomingConnection: Boolean
)

sealed class PayloadEvent {
    data class Received(
        val endpointId: String,
        val payloadId: Long,
        val bytes: ByteArray?
    ) : PayloadEvent()

    data class TransferUpdate(
        val endpointId: String,
        val payloadId: Long,
        val status: PayloadTransferStatus,
        val bytesTransferred: Long,
        val totalBytes: Long
    ) : PayloadEvent()
}

enum class PayloadTransferStatus {
    IN_PROGRESS,
    SUCCESS,
    FAILURE,
    CANCELED
}
