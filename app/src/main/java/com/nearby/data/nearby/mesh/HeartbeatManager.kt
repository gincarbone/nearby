package com.nearby.data.nearby.mesh

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.nearby.data.nearby.DiscoveredEndpoint
import com.nearby.data.nearby.NearbyManager
import com.nearby.data.nearby.NearbyService
import com.nearby.domain.repository.PeerRepository
import com.nearby.domain.repository.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HeartbeatManager handles automatic periodic discovery for mesh networking.
 *
 * Instead of continuous discovery (battery drain), it performs short "heartbeat"
 * discovery bursts at intervals that adapt to device context:
 *
 * - WiFi + Charging: 15 sec every 2 min (aggressive, device is powered)
 * - WiFi only: 6 sec every 2 min (balanced)
 * - Battery > 50%: 6 sec every 5 min (conservative)
 * - Battery 30-50%: 6 sec every 10 min (very conservative)
 * - Battery < 30%: disabled (preserve battery)
 *
 * When peers are discovered, it auto-connects for mesh relay (message exchange),
 * then disconnects. This is invisible to the user - they only see peers they
 * manually add as contacts.
 */
@Singleton
class HeartbeatManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nearbyManager: NearbyManager,
    private val userRepository: UserRepository,
    private val peerRepository: PeerRepository,
    private val meshManager: MeshManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var heartbeatJob: Job? = null
    private var isRunning = false

    // Current heartbeat configuration
    private val _currentConfig = MutableStateFlow(HeartbeatConfig.DISABLED)
    val currentConfig: StateFlow<HeartbeatConfig> = _currentConfig.asStateFlow()

    // Statistics for debugging/UI
    private val _stats = MutableStateFlow(HeartbeatStats())
    val stats: StateFlow<HeartbeatStats> = _stats.asStateFlow()

    // Track endpoints we've already synced with in this session to avoid redundant connections
    private val syncedEndpointsThisSession = mutableSetOf<String>()

    // Track ongoing auto-connections to prevent duplicates
    private val pendingAutoConnections = mutableSetOf<String>()

    /**
     * Start the heartbeat system.
     * Should be called when the app starts or user logs in.
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        android.util.Log.d("HeartbeatManager", "Starting heartbeat system")

        heartbeatJob = scope.launch {
            while (isRunning) {
                // Update config based on current device state
                val newConfig = calculateConfig()
                _currentConfig.value = newConfig

                if (newConfig != HeartbeatConfig.DISABLED) {
                    performHeartbeat(newConfig)
                    delay(newConfig.intervalMs)
                } else {
                    // Check again in 1 minute if conditions change
                    delay(60_000)
                }
            }
        }
    }

    /**
     * Stop the heartbeat system.
     * Should be called when the app is closed or user logs out.
     */
    fun stop() {
        android.util.Log.d("HeartbeatManager", "Stopping heartbeat system")
        isRunning = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        syncedEndpointsThisSession.clear()
        pendingAutoConnections.clear()
    }

    /**
     * Perform a single heartbeat: discover, auto-connect, sync, disconnect.
     */
    private suspend fun performHeartbeat(config: HeartbeatConfig) {
        val user = userRepository.getLocalUserSync() ?: return

        android.util.Log.d("HeartbeatManager", "Starting heartbeat - duration: ${config.discoveryDurationMs}ms")

        _stats.value = _stats.value.copy(
            lastHeartbeatTime = System.currentTimeMillis(),
            totalHeartbeats = _stats.value.totalHeartbeats + 1
        )

        try {
            // Start advertising and discovery
            startNearbyServices(user.displayName)

            // Wait for discovery duration
            delay(config.discoveryDurationMs)

            // Get discovered endpoints
            val discovered = nearbyManager.discoveredEndpoints.value
            android.util.Log.d("HeartbeatManager", "Discovered ${discovered.size} endpoints")

            // Filter out endpoints we've already synced with
            val newEndpoints = discovered.filter { endpoint ->
                endpoint.endpointId !in syncedEndpointsThisSession &&
                endpoint.endpointId !in pendingAutoConnections
            }

            if (newEndpoints.isNotEmpty()) {
                android.util.Log.d("HeartbeatManager", "New endpoints to sync: ${newEndpoints.size}")

                // Auto-connect to new endpoints for mesh relay
                for (endpoint in newEndpoints) {
                    autoConnectForRelay(endpoint, user.displayName)
                }

                _stats.value = _stats.value.copy(
                    peersDiscovered = _stats.value.peersDiscovered + newEndpoints.size
                )
            }

            // Stop discovery (but keep advertising for others to find us)
            stopDiscovery()

        } catch (e: Exception) {
            android.util.Log.e("HeartbeatManager", "Heartbeat error: ${e.message}")
        }
    }

    /**
     * Auto-connect to a discovered endpoint for mesh relay.
     * This connection is temporary - just for exchanging mesh messages.
     */
    private suspend fun autoConnectForRelay(endpoint: DiscoveredEndpoint, userName: String) {
        // Check if this is already a known peer (trusted contact)
        val existingPeer = peerRepository.getPeerByEndpointId(endpoint.endpointId)

        // Mark as pending
        pendingAutoConnections.add(endpoint.endpointId)

        try {
            android.util.Log.d("HeartbeatManager", "Auto-connecting to ${endpoint.endpointName} for relay")

            // Request connection
            nearbyManager.requestConnection(userName, endpoint.endpointId)
                .collect { result ->
                    result.onSuccess {
                        android.util.Log.d("HeartbeatManager", "Auto-connection successful: ${endpoint.endpointId}")
                        syncedEndpointsThisSession.add(endpoint.endpointId)

                        _stats.value = _stats.value.copy(
                            successfulSyncs = _stats.value.successfulSyncs + 1
                        )

                        // The handshake and mesh sync will happen automatically via MessageHandler
                        // After sync, we could disconnect if it's not a trusted peer
                        // For now, we keep the connection as it helps the mesh
                    }.onFailure { e ->
                        android.util.Log.w("HeartbeatManager", "Auto-connection failed: ${e.message}")
                    }
                }
        } finally {
            pendingAutoConnections.remove(endpoint.endpointId)
        }
    }

    /**
     * Calculate the appropriate heartbeat config based on device state.
     */
    private fun calculateConfig(): HeartbeatConfig {
        val batteryLevel = getBatteryLevel()
        val isCharging = isDeviceCharging()
        val isOnWifi = isOnWifi()

        return when {
            batteryLevel < 30 -> {
                android.util.Log.d("HeartbeatManager", "Battery < 30%, heartbeat disabled")
                HeartbeatConfig.DISABLED
            }
            isOnWifi && isCharging -> {
                android.util.Log.d("HeartbeatManager", "WiFi + Charging: aggressive heartbeat")
                HeartbeatConfig.WIFI_CHARGING
            }
            isOnWifi -> {
                android.util.Log.d("HeartbeatManager", "WiFi only: balanced heartbeat")
                HeartbeatConfig.WIFI_ONLY
            }
            batteryLevel > 50 -> {
                android.util.Log.d("HeartbeatManager", "Battery > 50%: conservative heartbeat")
                HeartbeatConfig.BATTERY_HIGH
            }
            else -> {
                android.util.Log.d("HeartbeatManager", "Battery 30-50%: very conservative heartbeat")
                HeartbeatConfig.BATTERY_MEDIUM
            }
        }
    }

    private fun startNearbyServices(userName: String) {
        // Start service
        val serviceIntent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // Start advertising
        val advertisingIntent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_START_ADVERTISING
            putExtra(NearbyService.EXTRA_USER_NAME, userName)
        }
        context.startService(advertisingIntent)

        // Start discovery
        val discoveryIntent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_START_DISCOVERY
        }
        context.startService(discoveryIntent)
    }

    private fun stopDiscovery() {
        val intent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_STOP_DISCOVERY
        }
        context.startService(intent)
    }

    private fun isDeviceCharging(): Boolean {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun getBatteryLevel(): Int {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 50
    }

    /**
     * Clear sync history - useful when user wants to force re-sync with all peers.
     */
    fun clearSyncHistory() {
        syncedEndpointsThisSession.clear()
    }
}

/**
 * Heartbeat configuration based on device context.
 */
enum class HeartbeatConfig(
    val discoveryDurationMs: Long,
    val intervalMs: Long,
    val description: String
) {
    WIFI_CHARGING(
        discoveryDurationMs = 15_000,  // 15 seconds
        intervalMs = 2 * 60 * 1000,     // 2 minutes
        description = "WiFi + Charging"
    ),
    WIFI_ONLY(
        discoveryDurationMs = 6_000,   // 6 seconds
        intervalMs = 2 * 60 * 1000,     // 2 minutes
        description = "WiFi"
    ),
    BATTERY_HIGH(
        discoveryDurationMs = 6_000,   // 6 seconds
        intervalMs = 5 * 60 * 1000,     // 5 minutes
        description = "Battery > 50%"
    ),
    BATTERY_MEDIUM(
        discoveryDurationMs = 6_000,   // 6 seconds
        intervalMs = 10 * 60 * 1000,    // 10 minutes
        description = "Battery 30-50%"
    ),
    DISABLED(
        discoveryDurationMs = 0,
        intervalMs = 0,
        description = "Disabled (Battery < 30%)"
    )
}

/**
 * Statistics for monitoring heartbeat performance.
 */
data class HeartbeatStats(
    val totalHeartbeats: Int = 0,
    val peersDiscovered: Int = 0,
    val successfulSyncs: Int = 0,
    val lastHeartbeatTime: Long = 0
)
