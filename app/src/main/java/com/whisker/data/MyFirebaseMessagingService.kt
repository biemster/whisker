package com.whisker.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.whisker.MainActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCMService", "Refreshed token: $token")
        
        // Store the token and send it to our companion server
        val prefs = getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
        
        // Notify GatewayNetworkManager to upload the new token
        val networkManager = CompanionRegistry.getOrInitializeManager(applicationContext)
        networkManager.updatePushToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("FCMService", "From: ${remoteMessage.from}")

        // 1. Wake up the device!
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Whisker:FCMWakeLock"
        )
        try {
            wakeLock.acquire(10000) // Acquire for 10 seconds max
        } catch (e: Exception) {
            Log.e("FCMService", "Failed to acquire wake lock", e)
        }

        // 2. Fetch data from the home server in the background
        val networkManager = CompanionRegistry.getOrInitializeManager(applicationContext)
        
        // 3. Extract payload as JSON if available
        val dataMap = remoteMessage.data
        val dataPayload = if (dataMap.isNotEmpty()) {
            try {
                org.json.JSONObject(dataMap as Map<*, *>).toString()
            } catch (e: Exception) {
                null
            }
        } else null
        Log.d("FCMService", "Message data payload JSON: $dataPayload")

        // 4. Force fetch pending events from the home server to get the latest messages, calls, or visitor requests
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Always contact home server to fetch actual events
                networkManager.fetchPendingEventsFromServerSuspended(fallbackTickle = dataPayload)
            } catch (e: Exception) {
                if (e is java.io.IOException) {
                    Log.w("FCMService", "Home Server unreachable after push: ${e.message}")
                } else {
                    Log.e("FCMService", "Error pulling tasks from Home Server after push", e)
                }
            } finally {
                try {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                } catch (e: Exception) {
                    Log.e("FCMService", "Error releasing wake lock", e)
                }
            }
        }

        // 5. Display a notification directly ONLY if an explicit FCM notification payload or custom title was provided.
        // For standard background tickle pushes, fetchPendingEventsFromServerSuspended will fetch and display the real event notification.
        val notification = remoteMessage.notification
        val customTitle = remoteMessage.data["title"]
        val customBody = remoteMessage.data["body"]

        if (notification != null) {
            val title = notification.title ?: "Whisker Notification"
            val body = notification.body ?: ""
            showBasicNotification(title, body)
        } else if (!customTitle.isNullOrEmpty() && customTitle != "New Signaling Alert") {
            showBasicNotification(customTitle, customBody ?: "")
        }
    }

    private fun showBasicNotification(title: String, body: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "fcm_alerts_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Whisker FCM Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "All incoming Whisker alerts and notifications"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(com.whisker.R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSound(soundUri)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
