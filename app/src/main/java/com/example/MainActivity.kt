package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import com.example.data.AppDatabase
import com.example.data.GatewayNetworkManager
import com.example.data.LinkRepository
import com.example.ui.GatewayDashboard
import com.example.ui.GatewayViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    
    // Simple Constructor Injection/Initialization as mandated by architecture guidelines
    private val database by lazy { AppDatabase.getDatabase(this) }
    private val repository by lazy { LinkRepository(database) }
    private val networkManager by lazy { com.example.data.CompanionRegistry.getOrInitializeManager(this) }

    private val viewModel: GatewayViewModel by viewModels {
        GatewayViewModel.Factory(repository, networkManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request Notification, Camera, and Microphone Permissions
        val permissionsToRequest = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsToRequest.add(android.Manifest.permission.CAMERA)
        permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
        requestPermissions(permissionsToRequest.toTypedArray(), 101)
        
        // Supports full-bleed edge-to-edge drawing, safe areas automatically handled inside Compose
        enableEdgeToEdge()
        
        // Register connection manager for BroadcastReceiver push-delivery
        com.example.data.CompanionRegistry.networkManager = networkManager
        networkManager.registerFcmToken()
        
        setContent {
            MyApplicationTheme {
                val chatUuid = intent.getStringExtra("chat_uuid")
                LaunchedEffect(chatUuid) {
                    if (chatUuid != null) {
                        viewModel.setActiveChatClientId(chatUuid)
                    }
                }

                GatewayDashboard(
                    viewModel = viewModel,
                    initialChatUuid = chatUuid,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.example.data.CompanionRegistry.isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        com.example.data.CompanionRegistry.isAppInForeground = false
    }
}
