package com.nearby.data.nearby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nearby.NearByApp
import com.nearby.R
import com.nearby.data.nearby.mesh.MeshManager
import com.nearby.domain.model.Message
import com.nearby.domain.repository.PeerRepository
import com.nearby.domain.repository.UserRepository
import com.nearby.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NearbyService : LifecycleService() {

    @Inject
    lateinit var nearbyManager: NearbyManager

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var messageHandler: MessageHandler

    @Inject
    lateinit var peerRepository: PeerRepository

    @Inject
    lateinit var meshManager: MeshManager

    private val binder = LocalBinder()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): NearbyService = this@NearbyService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createConnectionNotificationChannel()
        observeConnectionState()
        observeConnectionRequests()
        observeMessageEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopForegroundService()
            ACTION_START_BACKGROUND -> startBackgroundMode()
            ACTION_START_ADVERTISING -> {
                val userName = intent.getStringExtra(EXTRA_USER_NAME) ?: return START_NOT_STICKY
                startAdvertising(userName)
            }
            ACTION_STOP_ADVERTISING -> stopAdvertising()
            ACTION_START_DISCOVERY -> startDiscovery()
            ACTION_STOP_DISCOVERY -> stopDiscovery()
            ACTION_ACCEPT_CONNECTION -> {
                val endpointId = intent.getStringExtra(EXTRA_ENDPOINT_ID) ?: return START_NOT_STICKY
                acceptConnection(endpointId)
            }
            ACTION_REJECT_CONNECTION -> {
                val endpointId = intent.getStringExtra(EXTRA_ENDPOINT_ID) ?: return START_NOT_STICKY
                rejectConnection(endpointId)
            }
        }

        return START_STICKY
    }

    private fun startBackgroundMode() {
        lifecycleScope.launch {
            val user = userRepository.getLocalUserSync()
            if (user != null) {
                startForegroundService()
                startAdvertising(user.displayName)
                startDiscovery()
            }
        }
    }

    private fun acceptConnection(endpointId: String) {
        lifecycleScope.launch {
            nearbyManager.acceptConnection(endpointId).collect { result ->
                result.onSuccess {
                    dismissConnectionNotification(endpointId)
                    // Send handshake after accepting connection
                    messageHandler.sendHandshake(endpointId)
                }.onFailure {
                    dismissConnectionNotification(endpointId)
                }
            }
        }
    }

    private fun rejectConnection(endpointId: String) {
        lifecycleScope.launch {
            nearbyManager.rejectConnection(endpointId).collect {
                dismissConnectionNotification(endpointId)
            }
        }
    }

    private fun startForegroundService() {
        val notification = createNotification("NearBy is ready")
        startForeground(NOTIFICATION_ID, notification)
        _isRunning.value = true

        // Initialize mesh manager
        lifecycleScope.launch {
            meshManager.initialize()
        }
    }

    private fun stopForegroundService() {
        nearbyManager.stopAllEndpoints()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        _isRunning.value = false
    }

    private fun startAdvertising(userName: String) {
        lifecycleScope.launch {
            nearbyManager.startAdvertising(userName).collectLatest { result ->
                result.onSuccess {
                    updateNotification("Advertising as $userName")
                }.onFailure { e ->
                    updateNotification("Advertising failed: ${e.message}")
                }
            }
        }
    }

    private fun stopAdvertising() {
        nearbyManager.stopAdvertising()
        updateNotification("Advertising stopped")
    }

    private fun startDiscovery() {
        lifecycleScope.launch {
            nearbyManager.startDiscovery().collectLatest { result ->
                result.onSuccess {
                    updateNotification("Discovering nearby devices")
                }.onFailure { e ->
                    updateNotification("Discovery failed: ${e.message}")
                }
            }
        }
    }

    private fun stopDiscovery() {
        nearbyManager.stopDiscovery()
        updateNotification("Discovery stopped")
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            nearbyManager.connectionState.collectLatest { state ->
                val connectedCount = nearbyManager.connectedEndpoints.value.size
                val message = when (state) {
                    is NearbyConnectionState.Idle -> "NearBy è pronto"
                    is NearbyConnectionState.Advertising -> "In attesa di connessioni..."
                    is NearbyConnectionState.Discovering -> "Cercando dispositivi..."
                    is NearbyConnectionState.AdvertisingAndDiscovering -> {
                        if (connectedCount > 0) "$connectedCount connessi" else "Cercando..."
                    }
                    is NearbyConnectionState.Connecting -> "Connessione in corso..."
                    is NearbyConnectionState.Connected -> "$connectedCount connessi"
                    is NearbyConnectionState.Error -> "Errore: ${state.message}"
                }
                updateNotification(message)
            }
        }
    }

    private fun observeConnectionRequests() {
        lifecycleScope.launch {
            nearbyManager.connectionRequests.collect { connectionInfo ->
                // Auto-accept all connections - no manual approval needed
                // The first message received will complete the pairing
                acceptConnection(connectionInfo.endpointId)
            }
        }
    }

    private fun observeMessageEvents() {
        lifecycleScope.launch {
            messageHandler.events.collect { event ->
                if (event is MessageEvent.NewMessage) {
                    showMessageNotification(event.message, event.conversationId, event.senderName)
                }
            }
        }
    }

    private fun showMessageNotification(message: Message, conversationId: String, senderName: String) {
        try {
            val intent = Intent(this@NearbyService, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("nearby://chat/$conversationId")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this@NearbyService,
                conversationId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this@NearbyService, NearByApp.MESSAGE_CHANNEL_ID)
                .setContentTitle(senderName)
                .setContentText(message.content)
                .setSmallIcon(R.drawable.ic_nearby)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()

            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(conversationId.hashCode(), notification)
        } catch (e: Exception) {
            // Log error but don't crash
            android.util.Log.e("NearbyService", "Failed to show notification: ${e.message}")
        }
    }

    private fun createConnectionNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CONNECTION_CHANNEL_ID,
                "Richieste di connessione",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifiche per richieste di connessione da altri dispositivi"
                enableVibration(true)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showConnectionRequestNotification(connectionInfo: ConnectionInfo) {
        val displayName = connectionInfo.endpointName.removePrefix("NearBy|")

        // Intent per aprire l'app
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("connection_request", true)
                putExtra("endpoint_id", connectionInfo.endpointId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent per accettare
        val acceptIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NearbyService::class.java).apply {
                action = ACTION_ACCEPT_CONNECTION
                putExtra(EXTRA_ENDPOINT_ID, connectionInfo.endpointId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent per rifiutare
        val rejectIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, NearbyService::class.java).apply {
                action = ACTION_REJECT_CONNECTION
                putExtra(EXTRA_ENDPOINT_ID, connectionInfo.endpointId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setContentTitle("Richiesta di connessione")
            .setContentText("$displayName vuole connettersi")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$displayName vuole connettersi con te.\nCodice di verifica: ${connectionInfo.authenticationDigits}"))
            .setSmallIcon(R.drawable.ic_nearby)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .addAction(R.drawable.ic_nearby, "Accetta", acceptIntent)
            .addAction(R.drawable.ic_nearby, "Rifiuta", rejectIntent)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(connectionInfo.endpointId.hashCode(), notification)
    }

    private fun dismissConnectionNotification(endpointId: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(endpointId.hashCode())
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NearByApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("NearBy")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_nearby)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        nearbyManager.stopAllEndpoints()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CONNECTION_CHANNEL_ID = "nearby_connection_channel"
        const val ACTION_START = "com.nearby.action.START"
        const val ACTION_STOP = "com.nearby.action.STOP"
        const val ACTION_START_BACKGROUND = "com.nearby.action.START_BACKGROUND"
        const val ACTION_START_ADVERTISING = "com.nearby.action.START_ADVERTISING"
        const val ACTION_STOP_ADVERTISING = "com.nearby.action.STOP_ADVERTISING"
        const val ACTION_START_DISCOVERY = "com.nearby.action.START_DISCOVERY"
        const val ACTION_STOP_DISCOVERY = "com.nearby.action.STOP_DISCOVERY"
        const val ACTION_ACCEPT_CONNECTION = "com.nearby.action.ACCEPT_CONNECTION"
        const val ACTION_REJECT_CONNECTION = "com.nearby.action.REJECT_CONNECTION"
        const val EXTRA_USER_NAME = "extra_user_name"
        const val EXTRA_ENDPOINT_ID = "extra_endpoint_id"
    }
}
