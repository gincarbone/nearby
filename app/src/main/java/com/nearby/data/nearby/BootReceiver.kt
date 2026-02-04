package com.nearby.data.nearby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.nearby.core.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            CoroutineScope(Dispatchers.IO).launch {
                val backgroundServiceEnabled = preferencesManager.isBackgroundServiceEnabled()

                if (backgroundServiceEnabled) {
                    startNearbyService(context)
                }
            }
        }
    }

    private fun startNearbyService(context: Context) {
        val serviceIntent = Intent(context, NearbyService::class.java).apply {
            action = NearbyService.ACTION_START_BACKGROUND
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
