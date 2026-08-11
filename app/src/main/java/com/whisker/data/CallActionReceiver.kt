package com.whisker.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.whisker.MainActivity

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("CallActionReceiver", "Received action: $action")
        val networkManager = CompanionRegistry.getOrInitializeManager(context)

        when (action) {
            "ACTION_DECLINE_CALL" -> {
                networkManager.declineOrEndCall()
                networkManager.cancelCallNotification()
            }
            "ACTION_ANSWER_CALL" -> {
                val clientUuid = intent.getStringExtra("client_uuid") ?: ""
                val clientName = intent.getStringExtra("client_name") ?: "Caller"
                if (clientUuid.isNotEmpty()) {
                    networkManager.acceptCall(clientUuid, clientName)
                }
                networkManager.cancelCallNotification()

                // Launch MainActivity to bring call UI to foreground
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("client_uuid", clientUuid)
                    putExtra("client_name", clientName)
                    putExtra("action_answer_call", true)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
