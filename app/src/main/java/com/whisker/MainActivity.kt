package com.whisker

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.whisker.data.AppDatabase
import com.whisker.data.CallState
import com.whisker.data.LinkRepository
import com.whisker.ui.GatewayDashboard
import com.whisker.ui.GatewayViewModel
import com.whisker.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    
    private val database by lazy { AppDatabase.getDatabase(this) }
    private val repository by lazy { LinkRepository(database) }
    private val networkManager by lazy { com.whisker.data.CompanionRegistry.getOrInitializeManager(this) }

    private val viewModel: GatewayViewModel by viewModels {
        GatewayViewModel.Factory(repository, networkManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        turnScreenOnAndShowWhenLocked()
        checkAndRequestAppPermissions()
        
        enableEdgeToEdge()
        
        com.whisker.data.CompanionRegistry.networkManager = networkManager
        networkManager.registerFcmToken()
        
        handleCallOrChatIntent(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        turnScreenOnAndShowWhenLocked()
        handleCallOrChatIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        com.whisker.data.CompanionRegistry.isAppInForeground = true
        checkAndRequestAppPermissions()
    }

    override fun onPause() {
        super.onPause()
        com.whisker.data.CompanionRegistry.isAppInForeground = false
    }

    private fun checkAndRequestAppPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), 101)
        }
    }

    private fun turnScreenOnAndShowWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun handleCallOrChatIntent(intent: Intent?) {
        if (intent == null) return
        
        val chatUuid = intent.getStringExtra("chat_uuid")
        if (!chatUuid.isNullOrEmpty()) {
            viewModel.setActiveChatClientId(chatUuid)
        }

        val clientUuid = intent.getStringExtra("client_uuid")
        val clientName = intent.getStringExtra("client_name") ?: "Contact"
        val isAnswer = intent.getBooleanExtra("action_answer_call", false)
        val isDecline = intent.getBooleanExtra("action_decline_call", false)
        val isIncomingCall = intent.getBooleanExtra("incoming_call", false)

        if (isAnswer && !clientUuid.isNullOrEmpty()) {
            networkManager.acceptCall(clientUuid, clientName)
            networkManager.cancelCallNotification()
        } else if (isDecline && !clientUuid.isNullOrEmpty()) {
            networkManager.declineOrEndCall()
            networkManager.cancelCallNotification()
        } else if ((isIncomingCall || !clientUuid.isNullOrEmpty()) && networkManager.activeCall.value is CallState.Idle) {
            if (!clientUuid.isNullOrEmpty()) {
                networkManager.setIncomingCallState(clientUuid, clientName, isVideo = intent.getBooleanExtra("is_video", true))
            }
            networkManager.cancelCallNotification()
        } else if (isIncomingCall) {
            networkManager.cancelCallNotification()
        }
    }
}
