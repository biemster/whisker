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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.whisker.data.AppDatabase
import com.whisker.data.CallState
import com.whisker.data.LinkRepository
import com.whisker.ui.GatewayDashboard
import com.whisker.ui.GatewayViewModel
import com.whisker.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private val database by lazy { AppDatabase.getDatabase(this) }
    private val repository by lazy { LinkRepository(database) }
    private val networkManager by lazy { com.whisker.data.CompanionRegistry.getOrInitializeManager(this) }

    private val viewModel: GatewayViewModel by viewModels {
        GatewayViewModel.Factory(repository, networkManager)
    }

    private var wasInCall = false
    private var launchedForCall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkAndRequestAppPermissions()
        enableEdgeToEdge()
        
        com.whisker.data.CompanionRegistry.networkManager = networkManager
        networkManager.registerFcmToken()
        
        handleCallOrChatIntent(intent)
        handleShareIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeCallState.collect { callState ->
                    if (callState !is CallState.Idle) {
                        wasInCall = true
                        turnScreenOnAndShowWhenLocked()
                    } else if (wasInCall) {
                        wasInCall = false
                        handleCallEnded()
                    }
                }
            }
        }

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
        handleCallOrChatIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        com.whisker.data.CompanionRegistry.isAppInForeground = true
        networkManager.cancelAllMessageNotifications()
        networkManager.fetchPendingEventsFromServer()
        checkAndRequestAppPermissions()
    }

    override fun onPause() {
        super.onPause()
        com.whisker.data.CompanionRegistry.isAppInForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        turnScreenOffAndClearLockFlags()
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
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun turnScreenOffAndClearLockFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
        @Suppress("DEPRECATION")
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun handleCallEnded() {
        networkManager.cancelCallNotification()
        turnScreenOffAndClearLockFlags()

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked ?: false

        if (isLocked || launchedForCall) {
            // App was displayed over the lockscreen or launched specifically for this call session.
            // Fully finish and remove task so device immediately sleeps / dozes and returns to lockscreen.
            finishAndRemoveTask()
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
        val isVideo = intent.getBooleanExtra("is_video", true)

        if (isAnswer || isDecline || isIncomingCall || !clientUuid.isNullOrEmpty()) {
            launchedForCall = true
        }

        if (isAnswer && !clientUuid.isNullOrEmpty()) {
            turnScreenOnAndShowWhenLocked()
            networkManager.acceptCall(clientUuid, clientName, isVideo = isVideo)
            networkManager.cancelCallNotification()
        } else if (isDecline && !clientUuid.isNullOrEmpty()) {
            networkManager.declineOrEndCall()
            networkManager.cancelCallNotification()
            handleCallEnded()
        } else if ((isIncomingCall || !clientUuid.isNullOrEmpty()) && networkManager.activeCall.value is CallState.Idle) {
            turnScreenOnAndShowWhenLocked()
            if (!clientUuid.isNullOrEmpty()) {
                networkManager.setIncomingCallState(clientUuid, clientName, isVideo = isVideo)
            }
            networkManager.cancelCallNotification()
        } else if (isIncomingCall) {
            networkManager.cancelCallNotification()
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (Intent.ACTION_SEND == action) {
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            } ?: intent.clipData?.let { cd ->
                if (cd.itemCount > 0) cd.getItemAt(0).uri else null
            }
            if (streamUri != null) {
                viewModel.setIncomingSharedUris(listOf(streamUri))
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            }
            if (!streamUris.isNullOrEmpty()) {
                viewModel.setIncomingSharedUris(streamUris.filterNotNull())
            } else {
                val clipUris = mutableListOf<android.net.Uri>()
                intent.clipData?.let { cd ->
                    for (i in 0 until cd.itemCount) {
                        cd.getItemAt(i).uri?.let { clipUris.add(it) }
                    }
                }
                if (clipUris.isNotEmpty()) {
                    viewModel.setIncomingSharedUris(clipUris)
                }
            }
        }
    }
}
