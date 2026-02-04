package com.nearby

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.crypto.tink.config.TinkConfig
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NearByApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "nearby_service_channel"
        const val MESSAGE_CHANNEL_ID = "nearby_message_channel"
    }

    override fun onCreate() {
        super.onCreate()
        initTink()
        createNotificationChannel()
    }

    private fun initTink() {
        TinkConfig.register()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Service channel (low priority, silent)
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // Message channel (high priority, with vibration and lights)
            val messageChannel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new messages"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(messageChannel)
        }
    }
}
