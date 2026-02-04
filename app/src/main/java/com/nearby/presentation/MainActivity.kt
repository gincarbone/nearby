package com.nearby.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.nearby.data.nearby.MessageEvent
import com.nearby.data.nearby.MessageHandler
import com.nearby.data.nearby.NearbyService
import com.nearby.data.nearby.PreferencesManager
import com.nearby.data.nearby.mesh.HeartbeatManager
import com.nearby.data.nearby.mesh.MeshManager
import com.nearby.domain.repository.UserRepository
import com.nearby.presentation.navigation.NavGraph
import com.nearby.presentation.navigation.Screen
import com.nearby.presentation.theme.NearByTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var messageHandler: MessageHandler

    @Inject
    lateinit var meshManager: MeshManager

    @Inject
    lateinit var heartbeatManager: HeartbeatManager

    private var pendingDeepLink: String? = null
    private var navController: NavHostController? = null
    private val pendingNavigation = MutableStateFlow<String?>(null)

    private val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            val deniedPermissions = permissions.filter { !it.value }.keys
            android.util.Log.w("MainActivity", "Permissions denied: $deniedPermissions")
            // App will continue with limited functionality
            // Core features require these permissions for Nearby Connections
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestPermissionsIfNeeded()
        startBackgroundServiceIfEnabled()
        handleIntent(intent)
        observeMessageEvents()

        setContent {
            NearByTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var startDestination by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        val isOnboardingComplete = userRepository.isOnboardingComplete()
                        startDestination = if (isOnboardingComplete) {
                            Screen.Home.route
                        } else {
                            Screen.Onboarding.route
                        }
                    }

                    startDestination?.let { destination ->
                        val localNavController = rememberNavController()

                        LaunchedEffect(localNavController) {
                            navController = localNavController

                            // Handle pending deep link
                            pendingDeepLink?.let { conversationId ->
                                localNavController.navigate(Screen.Chat.createRoute(conversationId))
                                pendingDeepLink = null
                            }
                        }

                        // Observe navigation requests from message events
                        LaunchedEffect(localNavController) {
                            pendingNavigation.collect { conversationId ->
                                conversationId?.let {
                                    // Check if not already in this chat
                                    val currentRoute = localNavController.currentDestination?.route
                                    if (currentRoute != Screen.Chat.route ||
                                        localNavController.currentBackStackEntry?.arguments?.getString("conversationId") != it) {
                                        localNavController.navigate(Screen.Chat.createRoute(it))
                                    }
                                    pendingNavigation.value = null
                                }
                            }
                        }

                        NavGraph(
                            navController = localNavController,
                            startDestination = destination
                        )
                    }
                }
            }
        }
    }

    private fun observeMessageEvents() {
        // Disabled auto-navigation to prevent freeze issues
        // Messages will still be received and stored,
        // users can see unread count badges and navigate manually
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startBackgroundServiceIfEnabled() {
        lifecycleScope.launch {
            val isEnabled = preferencesManager.isBackgroundServiceEnabled()
            val isOnboardingComplete = userRepository.isOnboardingComplete()

            if (isOnboardingComplete) {
                // Initialize mesh networking
                meshManager.initialize()

                // Start heartbeat for automatic peer discovery
                heartbeatManager.start()

                if (isEnabled) {
                    val intent = Intent(this@MainActivity, NearbyService::class.java).apply {
                        action = NearbyService.ACTION_START_BACKGROUND
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop heartbeat when app is destroyed
        heartbeatManager.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == "nearby" && uri.host == "chat") {
                pendingDeepLink = uri.pathSegments.firstOrNull()
            }
        }
    }
}
