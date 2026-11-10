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
        Log.d("FCMService", "FCM tickle received from: ${remoteMessage.from}")

        // 1. Pure tickle push: fetch pending events directly from home server on-demand
        val networkManager = CompanionRegistry.getOrInitializeManager(applicationContext)
        val tickleEventId = remoteMessage.data["eventId"] ?: remoteMessage.data["id"] ?: ""
        val tickleServerAddr = remoteMessage.data["server_address"] ?: ""
        try {
            CoroutineScope(Dispatchers.IO).launch {
                networkManager.fetchPendingEventsFromServerSuspended(
                    tickleEventId = tickleEventId.takeIf { it.isNotEmpty() },
                    targetServerUrl = tickleServerAddr.takeIf { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) {
            if (e is java.io.IOException) {
                Log.w("FCMService", "Home Server unreachable after push tickle: ${e.message}")
            } else {
                Log.e("FCMService", "Error pulling tasks from Home Server after push tickle", e)
            }
        }

        // 2. Display explicit FCM notification payload if provided
        val notification = remoteMessage.notification
        if (notification != null) {
            val title = notification.title ?: "Whisker Notification"
            val body = notification.body ?: ""
            showBasicNotification(title, body)
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
