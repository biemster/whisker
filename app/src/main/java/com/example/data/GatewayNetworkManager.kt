package com.example.data

import org.json.JSONArray

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
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.LinkedHashSet
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.util.UUID

data class WebRtcSignal(
    val clientUuid: String,
    val type: String,
    val candidateJson: String = "",
    val sdp: String = ""
)

sealed class CallState {
    object Idle : CallState()
    data class Incoming(val clientUuid: String, val clientName: String, val isVideo: Boolean, val sdp: String = "") : CallState()
    data class Outgoing(val clientUuid: String, val clientName: String, val isVideo: Boolean = false) : CallState()
    data class Active(val clientUuid: String, val clientName: String, val isVideo: Boolean = false, var durationSeconds: Int = 0, val sdp: String = "") : CallState()
}

class GatewayNetworkManager(private val context: Context, private val repository: LinkRepository) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val baseOkHttpClientBuilder: OkHttpClient.Builder = try {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
        )
        val sslContext = javax.net.ssl.SSLContext.getInstance("SSL").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
            .hostnameVerifier { _, _ -> true }
    } catch (e: Exception) {
        OkHttpClient.Builder()
    }

    private val okHttpClient: OkHttpClient = baseOkHttpClientBuilder
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Configuration Settings
    val serverUrl = MutableStateFlow("https://whisker-tunnel.local")
    val apiKey = MutableStateFlow("")
    val autoApproveAllowed = MutableStateFlow(false)
    val pushToken = MutableStateFlow("Fetching FCM push token...")
    val serverReachable = MutableStateFlow(false)
    val activeChatClientId = MutableStateFlow<String?>(null)

    // Realtime States
    private val _connectionStatus = MutableStateFlow("Initializing...")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _activeCall = MutableStateFlow<CallState>(CallState.Idle)
    val activeCall: StateFlow<CallState> = _activeCall.asStateFlow()

    private val _onInviteClaimed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onInviteClaimed = _onInviteClaimed.asSharedFlow()

    private val _incomingSignal = MutableSharedFlow<WebRtcSignal>(replay = 16, extraBufferCapacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val incomingSignal: SharedFlow<WebRtcSignal> = _incomingSignal.asSharedFlow()

    private var lastRegisteredToken: String? = null
    private var lastRegisteredServerUrl: String? = null
    private var lastRegisteredApiKey: String? = null

    private val fetchMutex = Mutex()

    fun getCleanServerUrl(): String = serverUrl.value.trim().removeSuffix("/")

    init {
        val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
        val savedEventIds = prefs.getStringSet("processed_event_ids", emptySet()) ?: emptySet()
        processedEventIds.addAll(savedEventIds)
        
        // Restore serverUrl
        val storedServerUrl = prefs.getString("server_url", "https://whisker-tunnel.local") ?: "https://whisker-tunnel.local"
        serverUrl.value = storedServerUrl

        // Restore or Generate apiKey as UUID4
        var storedApiKey = prefs.getString("local_private_access_key", "") ?: ""
        if (storedApiKey.isEmpty()) {
            storedApiKey = UUID.randomUUID().toString()
            prefs.edit().putString("local_private_access_key", storedApiKey).apply()
        }
        apiKey.value = storedApiKey

        // Restore cached push token if available
        val savedFcmToken = prefs.getString("fcm_token", null)
        if (!savedFcmToken.isNullOrEmpty() && !savedFcmToken.startsWith("device-push-token-")) {
            pushToken.value = savedFcmToken
        } else {
            pushToken.value = ""
            prefs.edit().remove("fallback_device_push_token").remove("fcm_token").apply()
        }

        createNotificationChannel()
        registerFcmToken()
        
        scope.launch(Dispatchers.IO) {
            if (storedServerUrl.isNotEmpty() && storedServerUrl.startsWith("http")) {
                fetchFcmConfigFromServer(storedServerUrl, storedApiKey)
                registerPushTokenOnServer(storedServerUrl, storedApiKey, pushToken.value)
            }
        }

        fetchPendingEventsFromServer()
        startRealtimeSseStream()
        startForegroundPollingLoop()
    }

    @Volatile private var isSseRunning = false

    fun startRealtimeSseStream() {
        synchronized(GatewayNetworkManager::class.java) {
            if (isSseRunning) return
            isSseRunning = true
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val cleanUrl = getCleanServerUrl()
                val pinVal = apiKey.value
                if (cleanUrl.isNotEmpty() && cleanUrl.startsWith("http")) {
                    try {
                        val sseClient = okHttpClient.newBuilder()
                            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .build()

                        val request = Request.Builder()
                            .url("$cleanUrl/api/host/stream")
                            .header("Authorization", "Bearer $pinVal")
                            .header("Accept", "text/event-stream")
                            .get()
                            .build()

                        sseClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                serverReachable.value = true
                                _connectionStatus.value = "Connected"
                                val source = response.body?.source()
                                if (source != null) {
                                    val reader = BufferedReader(InputStreamReader(source.inputStream(), Charsets.UTF_8))
                                    var line: String? = reader.readLine()
                                    while (isActive && line != null) {
                                        if (line.startsWith("data: ")) {
                                            val dataStr = line.substring(6).trim()
                                            if (dataStr.isNotEmpty() && dataStr != "keep-alive") {
                                                serverReachable.value = true
                                                _connectionStatus.value = "Connected"
                                                processIncomingPayload(dataStr)
                                            }
                                        }
                                        line = reader.readLine()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("GatewayNetworkManager", "SSE stream disconnect: ${e.message}")
                    }
                }
                delay(3000)
            }
        }
    }

    private fun startForegroundPollingLoop() {
        synchronized(GatewayNetworkManager::class.java) {
            if (isPollingLoopRunning) return
            isPollingLoopRunning = true
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    if (CompanionRegistry.isAppInForeground) {
                        val serverUrlVal = serverUrl.value.removeSuffix("/")
                        if (serverUrlVal.isNotEmpty() && serverUrlVal.startsWith("http")) {
                            fetchPendingEventsFromServerSuspended()
                        }
                    }
                } catch (e: Exception) {
                    // ignore transient polling exceptions
                }
                delay(3000)
            }
        }
    }

    fun fetchPendingEventsFromServer() {
        scope.launch(Dispatchers.IO) {
            fetchPendingEventsFromServerSuspended()
        }
    }

    suspend fun fetchPendingEventsFromServerSuspended(fallbackTickle: String? = null) {
        val serverUrlVal = serverUrl.value.removeSuffix("/")
        val pinVal = apiKey.value
        val pushTokenVal = pushToken.value

        if (serverUrlVal.isEmpty() || !serverUrlVal.startsWith("http")) {
            serverReachable.value = false
            _connectionStatus.value = "Disconnected"
            return
        }

        var fetchSuccess = false
        val fetchedEventIds = mutableSetOf<String>()

        fetchMutex.withLock {
            try {
                // Sync client presences first
                val presenceReq = Request.Builder()
                    .url("$serverUrlVal/api/host/clients")
                    .header("Authorization", "Bearer $pinVal")
                    .get()
                    .build()
                    
                okHttpClient.newCall(presenceReq).execute().use { response ->
                    if (response.isSuccessful) {
                        serverReachable.value = true
                        _connectionStatus.value = "Connected"
                        val body = response.body?.string() ?: "[]"
                        val clientsArray = JSONArray(body)
                        
                        val onlineUuids = mutableSetOf<String>()
                        val serverClients = mutableMapOf<String, JSONObject>()
                        
                        for (i in 0 until clientsArray.length()) {
                            val clientObj = clientsArray.getJSONObject(i)
                            val uuid = clientObj.getString("uuid")
                            serverClients[uuid] = clientObj
                            val isOnline = clientObj.optBoolean("is_online", false) || clientObj.optInt("is_online", 0) == 1
                            if (isOnline) onlineUuids.add(uuid)
                        }
                        
                        val localClients = repository.getAllRequestsSync()
                        
                        // Mark all existing clients appropriately
                        for (existing in localClients) {
                            val serverHasOnline = onlineUuids.contains(existing.uuid)
                            val serverObj = serverClients[existing.uuid]
                            if (serverObj != null) {
                                val serverStatus = serverObj.optString("req_status", existing.status)
                                val serverToken = serverObj.optString("token", existing.token)
                                val serverName = serverObj.optString("name", existing.name)
                                if (existing.isOnline != serverHasOnline || existing.status != serverStatus || existing.token != serverToken || existing.name != serverName) {
                                    repository.updateRequest(existing.copy(
                                        isOnline = serverHasOnline,
                                        status = serverStatus,
                                        token = serverToken,
                                        name = serverName,
                                        lastSeen = System.currentTimeMillis()
                                    ))
                                }
                            } else if (existing.isOnline != serverHasOnline) {
                                repository.updateRequest(existing.copy(isOnline = serverHasOnline, lastSeen = System.currentTimeMillis()))
                            }
                        }
                        
                        // Add new clients that aren't in local DB yet
                        val localUuids = localClients.map { it.uuid }.toSet()
                        for ((uuid, clientObj) in serverClients) {
                            if (!localUuids.contains(uuid)) {
                                val reqStatus = clientObj.optString("req_status", "PENDING")
                                val name = clientObj.optString("name", "Guest")
                                val reason = clientObj.optString("reason", "")
                                val token = clientObj.optString("token", "")
                                val createdAt = clientObj.optLong("created_at", System.currentTimeMillis())
                                val isOnline = onlineUuids.contains(uuid)
                                val newRequest = ClientRequest(
                                    uuid = uuid,
                                    name = name,
                                    message = reason,
                                    status = reqStatus,
                                    token = token,
                                    timestamp = createdAt,
                                    lastSeen = System.currentTimeMillis(),
                                    isOnline = isOnline
                                )
                                repository.insertRequest(newRequest)
                            }
                        }
                    } else {
                        serverReachable.value = false
                        _connectionStatus.value = "Connecting..."
                    }
                }

                val request = Request.Builder()
                    .url("$serverUrlVal/api/host/events")
                    .header("Authorization", "Bearer $pinVal")
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        fetchSuccess = true
                        serverReachable.value = true
                        _connectionStatus.value = "Connected"
                        val body = response.body?.string() ?: ""
                        val eventsArray = JSONArray(body)
                        for (i in 0 until eventsArray.length()) {
                            val eventObj = eventsArray.getJSONObject(i)
                            val id = eventObj.getString("id")
                            fetchedEventIds.add(id)
                            val type = eventObj.getString("type")
                            val payload = eventObj.getJSONObject("payload")

                            val alreadyProcessed = synchronized(processedEventIds) {
                                if (processedEventIds.contains(id)) {
                                    true
                                } else {
                                    processedEventIds.add(id)
                                    if (processedEventIds.size > 200) {
                                        val iterator = processedEventIds.iterator()
                                        if (iterator.hasNext()) {
                                            iterator.next()
                                            iterator.remove()
                                        }
                                    }
                                    val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
                                    prefs.edit().putStringSet("processed_event_ids", processedEventIds.toSet()).apply()
                                    false
                                }
                            }

                            if (!alreadyProcessed) {
                                // Adapts the server-fetched payload back to original processor format
                                payload.put("event", type)
                                processIncomingPayload(payload.toString())
                            }

                            // Clear event queue item on server
                            ackEventOnServer(serverUrlVal, pinVal, id)
                        }
                    } else {
                        serverReachable.value = false
                        _connectionStatus.value = "Connecting..."
                        Log.e("GatewayNetworkManager", "Failed to fetch events from server: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                serverReachable.value = false
                _connectionStatus.value = "Connecting..."
                if (e is java.io.IOException) {
                    Log.w("GatewayNetworkManager", "Home Server unreachable ($serverUrlVal): ${e.localizedMessage ?: e.message}")
                } else {
                    Log.e("GatewayNetworkManager", "Error pulling tasks from Home Server", e)
                }
            }

            // Process fallback tickle if provided and the event was not successfully fetched
            if (fallbackTickle != null) {
                try {
                    val tickleJson = JSONObject(fallbackTickle)
                    val eventId = tickleJson.optString("eventId")
                    val type = tickleJson.optString("type")
                    
                    if (eventId.isNotEmpty() && type.isNotEmpty()) {
                        val alreadyProcessed = synchronized(processedEventIds) {
                            processedEventIds.contains(eventId)
                        }
                        
                        if (!alreadyProcessed && !fetchedEventIds.contains(eventId)) {
                            synchronized(processedEventIds) {
                                processedEventIds.add(eventId)
                                if (processedEventIds.size > 200) {
                                    val iterator = processedEventIds.iterator()
                                    if (iterator.hasNext()) {
                                        iterator.next()
                                        iterator.remove()
                                    }
                                }
                                val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
                                prefs.edit().putStringSet("processed_event_ids", processedEventIds.toSet()).apply()
                            }
                            
                            // Show fallback notification!
                            when (type) {
                                "message" -> {
                                    showNotification(
                                        "New Message",
                                        "You have a new message. Tap to view.",
                                        1003
                                    )
                                }
                                "call" -> {
                                    val name = tickleJson.optString("name", "Incoming Call")
                                    val clientUuid = tickleJson.optString("clientUuid", tickleJson.optString("uuid", ""))
                                    if (clientUuid.isNotEmpty()) {
                                        setIncomingCallState(clientUuid, name, isVideo = true, sdp = "")
                                        showIncomingCallNotification(clientUuid, name, true)
                                    }
                                }
                                "visitor" -> {
                                    showNotification(
                                        "Access Request",
                                        "New visitor is requesting direct calling access.",
                                        1001
                                    )
                                }
                            }
                        }
                    }
                } catch (ex: Exception) {
                    Log.e("GatewayNetworkManager", "Error processing fallback tickle", ex)
                }
            }
        }
    }

    suspend fun fetchFcmConfigFromServer(serverUrlVal: String, pinVal: String) {
        if (serverUrlVal.isEmpty() || !serverUrlVal.startsWith("http")) return
        try {
            val request = Request.Builder()
                .url("$serverUrlVal/api/host/fcm_config")
                .header("Authorization", "Bearer $pinVal")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val configured = json.optBoolean("configured", false)
                    if (configured) {
                        val senderId = json.optString("sender_id", "")
                        val projectId = json.optString("project_id", "")
                        val appId = json.optString("app_id", "")
                        val apiKey = json.optString("api_key", "")

                        val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
                        val currentSenderId = prefs.getString("fcm_sender_id", null)
                        val currentApiKey = prefs.getString("fcm_api_key", null)

                        val needsUpdate = !senderId.isNullOrEmpty() && (senderId != currentSenderId || (!apiKey.isNullOrEmpty() && apiKey != currentApiKey) || pushToken.value.isEmpty())
                        if (needsUpdate) {
                            Log.d("FCMInit", "[FCM Config Sync] Received FCM configuration from Home Server! Sender ID: $senderId, Project ID: $projectId, API Key: ${apiKey.take(6)}...")
                            saveFcmConfig(senderId, projectId, appId, apiKey)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is java.io.IOException) {
                Log.w("FCMInit", "Home Server not reachable at $serverUrlVal: ${e.message}")
            } else {
                Log.e("FCMInit", "Failed to fetch FCM config from Home Server: ${e.message}")
            }
        }
    }

    private fun registerPushTokenOnServer(serverUrlVal: String, pinVal: String, pushTokenVal: String) {
        if (serverUrlVal.isEmpty() || !serverUrlVal.startsWith("http")) return
        val activeToken = if (pushTokenVal.isNotEmpty()) pushTokenVal else pushToken.value
        if (activeToken.isEmpty() || activeToken == "undefined" || activeToken == "null" || activeToken.startsWith("Fetching") || activeToken.startsWith("Searching") || activeToken.startsWith("device-push-token-")) return

        if (serverUrlVal == lastRegisteredServerUrl && pinVal == lastRegisteredApiKey && activeToken == lastRegisteredToken) {
            return
        }

        try {
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = JSONObject().apply {
                put("pushToken", activeToken)
                put("fcmToken", activeToken)
            }
            val requestBody = RequestBody.create(mediaType, body.toString())
            val cleanUrl = getCleanServerUrl()
            val request = Request.Builder()
                .url("$cleanUrl/api/host/register_push")
                .header("Authorization", "Bearer $pinVal")
                .header("X-Push-Token", activeToken)
                .post(requestBody)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    lastRegisteredServerUrl = cleanUrl
                    lastRegisteredApiKey = pinVal
                    lastRegisteredToken = activeToken
                    Log.d("GatewayNetworkManager", "Successfully registered FCM push token on server: $activeToken")
                } else {
                    Log.w("GatewayNetworkManager", "Server reject push-binding: ${response.code}")
                }
            }
        } catch (e: Exception) {
            if (e is java.io.IOException) {
                Log.w("GatewayNetworkManager", "Home Server not reachable to save push settings: ${e.message}")
            } else {
                Log.e("GatewayNetworkManager", "Failed to save push settings on server", e)
            }
        }
    }

    private fun ackEventOnServer(serverUrlVal: String, pinVal: String, eventId: String) {
        try {
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = JSONObject().apply {
                put("eventId", eventId)
            }
            val requestBody = RequestBody.create(mediaType, body.toString())
            val cleanUrl = getCleanServerUrl()
            val request = Request.Builder()
                .url("$cleanUrl/api/host/ack")
                .header("Authorization", "Bearer $pinVal")
                .post(requestBody)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("GatewayNetworkManager", "Ack error on server: ${response.code}")
                }
            }
        } catch (e: Exception) {
            if (e is java.io.IOException) {
                Log.w("GatewayNetworkManager", "Home Server not reachable to clear task: ${e.message}")
            } else {
                Log.e("GatewayNetworkManager", "Failed to clear task on server", e)
            }
        }
    }

    fun processIncomingPayload(payloadStr: String) {
        try {
            val json = JSONObject(payloadStr)
            val eventType = json.optString("event", json.optString("type", ""))
            val uuid = json.optString("uuid", json.optString("clientUuid", ""))
            val eventId = json.optString("eventId", json.optString("id", ""))
            val messageId = json.optString("messageId", "")

            if (eventId.isNotEmpty()) {
                val isDup = synchronized(processedEventIds) {
                    if (processedEventIds.contains(eventId)) {
                        true
                    } else {
                        processedEventIds.add(eventId)
                        if (processedEventIds.size > 300) {
                            val iter = processedEventIds.iterator()
                            if (iter.hasNext()) { iter.next(); iter.remove() }
                        }
                        val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putStringSet("processed_event_ids", processedEventIds.toSet()).apply()
                        false
                    }
                }
                if (isDup) return
            }

            if (messageId.isNotEmpty()) {
                val isMsgDup = synchronized(processedMessageIds) {
                    if (processedMessageIds.contains(messageId)) {
                        true
                    } else {
                        processedMessageIds.add(messageId)
                        if (processedMessageIds.size > 300) {
                            val iter = processedMessageIds.iterator()
                            if (iter.hasNext()) { iter.next(); iter.remove() }
                        }
                        false
                    }
                }
                if (isMsgDup) return
            }

            when (eventType) {
                "visitor" -> {
                    val name = json.optString("name", "Incoming Visitor")
                    val reason = json.optString("reason", "Local call request")
                    
                    scope.launch {
                        val existing = repository.getRequestByUuid(uuid)
                        if (existing != null) {
                            if (existing.status == "APPROVED") {
                                // Auto republish approval for client browser reconnects
                                val response = JSONObject().apply {
                                    put("type", "registration_response")
                                    put("event", "approved")
                                    put("status", "APPROVED")
                                    put("token", existing.token)
                                    put("clientUuid", existing.uuid)
                                }
                                publishToClient(existing.uuid, response)
                            }
                        } else {
                            val request = ClientRequest(
                                uuid = uuid,
                                name = name,
                                message = reason,
                                status = "PENDING",
                                token = ""
                            )
                            repository.insertRequest(request)
                            showNotification(
                                "Access Request",
                                "New visitor '$name' is requesting direct calling access.",
                                1001
                            )
                        }
                    }
                }
                "message" -> {
                    val token = json.optString("token", "")
                    val msgIdLong = json.optLong("messageId", json.optLong("id", 0L))
                    val cleanUrl = getCleanServerUrl()
                    val pinVal = apiKey.value

                    scope.launch(Dispatchers.IO) {
                        val request = repository.getRequestByUuid(uuid)
                        if (request != null && request.status == "APPROVED" && (token.isEmpty() || request.token == token)) {
                            if (msgIdLong > 0 && cleanUrl.isNotEmpty()) {
                                fetchAndSyncMessageChain(cleanUrl, pinVal, uuid, msgIdLong)
                            } else {
                                val name = json.optString("name", "Approved Caller")
                                val text = json.optString("text", "")
                                val file = if (json.isNull("file")) null else json.optString("file").takeIf { it.isNotEmpty() && it != "null" }
                                val existingMsgs = repository.getMessagesForClientSync(uuid)
                                val isDuplicate = existingMsgs.any { m ->
                                    (messageId.isNotEmpty() && m.serverId.toString() == messageId) ||
                                    (m.messageText == text && m.filePath == file && kotlin.math.abs(m.timestamp - System.currentTimeMillis()) < 10000)
                                }
                                if (!isDuplicate) {
                                    repository.insertMessage(
                                        Message(
                                            clientUuid = uuid,
                                            clientName = name,
                                            messageText = text,
                                            filePath = file,
                                            isIncoming = true
                                        )
                                    )
                                    val cleanMsg = if (file != null) "🔗 File: [$text]" else text
                                    showNotification(
                                        "Message from $name",
                                        cleanMsg,
                                        1003,
                                        uuid
                                    )
                                }
                            }
                        }
                    }
                }
                "call" -> {
                    val token = json.optString("token", "")
                    val name = json.optString("name", "Caller")
                    val isVideo = json.optBoolean("video", true)
                    val sdp = json.optString("sdp", "")

                    scope.launch {
                        val existing = repository.getRequestByUuid(uuid)
                        val callerName = if (!existing?.name.isNullOrEmpty() && existing?.name != "Guest") existing!!.name else name
                        if (existing == null) {
                            repository.insertRequest(
                                ClientRequest(
                                    uuid = uuid,
                                    name = callerName,
                                    message = "Approved Contact",
                                    status = "APPROVED",
                                    token = token,
                                    timestamp = System.currentTimeMillis(),
                                    lastSeen = System.currentTimeMillis(),
                                    isOnline = true
                                )
                            )
                        } else if (existing.status != "APPROVED" || (token.isNotEmpty() && existing.token != token)) {
                            repository.updateRequest(
                                existing.copy(
                                    status = "APPROVED",
                                    token = if (token.isNotEmpty()) token else existing.token,
                                    name = callerName,
                                    lastSeen = System.currentTimeMillis()
                                )
                            )
                        }

                        setIncomingCallState(uuid, callerName, isVideo, sdp)
                        showIncomingCallNotification(uuid, callerName, isVideo)
                    }
                }
                "signal" -> {
                    val signalType = json.optString("type", "webrtc_ice_candidate")
                    val candidate = json.opt("candidate")?.toString() ?: ""
                    val sdp = json.optString("sdp", "")
                    _incomingSignal.tryEmit(WebRtcSignal(uuid, signalType, candidate, sdp))
                }
                "hangup" -> {
                    scope.launch {
                        if (_activeCall.value !is CallState.Idle) {
                            _activeCall.value = CallState.Idle
                            cancelCallNotification()
                            updateAudioModeForCall(false)
                        }
                    }
                }
                "invite_claimed" -> {
                    val name = json.optString("name", "Invited Guest")
                    val code = json.optString("code", "")
                    val clientUuid = json.optString("uuid", "")
                    val token = json.optString("token", "")

                    scope.launch {
                        _onInviteClaimed.tryEmit(Unit)

                        val existing = repository.getRequestByUuid(clientUuid)
                        if (existing == null) {
                            val newRequest = ClientRequest(
                                uuid = clientUuid,
                                name = name,
                                message = "Invited via link ($code)",
                                status = "APPROVED",
                                token = token,
                                timestamp = System.currentTimeMillis(),
                                lastSeen = System.currentTimeMillis(),
                                isOnline = true
                            )
                            repository.insertRequest(newRequest)
                        } else {
                            repository.updateRequest(existing.copy(status = "APPROVED", token = token, name = name))
                        }

                        showNotification(
                            "Invite Claimed",
                            "Guest '$name' has claimed your invite link.",
                            1004
                        )
                    }
                }
            }

            if (eventId.isNotEmpty()) {
                val cleanUrl = getCleanServerUrl()
                val pinVal = apiKey.value
                if (cleanUrl.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        ackEventOnServer(cleanUrl, pinVal, eventId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GatewayNetworkManager", "Error parsing inner payload: $payloadStr", e)
        }
    }

    private suspend fun fetchAndSyncMessageChain(cleanUrl: String, pinVal: String, uuid: String, startMsgId: Long) {
        var currentId: Long? = startMsgId
        val visited = mutableSetOf<Long>()
        while (currentId != null && currentId > 0 && !visited.contains(currentId)) {
            visited.add(currentId)
            val existing = repository.getMessageByServerId(currentId)
            if (existing != null) {
                // Local DB already has this message and all its predecessors
                break
            }

            val request = Request.Builder()
                .url("$cleanUrl/api/host/message?id=$currentId")
                .header("Authorization", "Bearer $pinVal")
                .get()
                .build()

            var msgObj: JSONObject? = null
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string()
                        if (!bodyStr.isNullOrEmpty()) {
                            msgObj = JSONObject(bodyStr)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GatewayNetworkManager", "Failed to fetch message $currentId from server", e)
                break
            }

            if (msgObj == null) break

            val msgUuid = msgObj!!.optString("clientUuid", uuid)
            val sender = msgObj!!.optString("sender", "guest")
            val text = msgObj!!.optString("text", "")
            val filePath = if (msgObj!!.has("filePath") && !msgObj!!.isNull("filePath")) msgObj!!.optString("filePath").takeIf { it.isNotEmpty() && it != "null" } else null
            val createdAt = msgObj!!.optLong("createdAt", System.currentTimeMillis())
            val prevId = if (msgObj!!.has("previousMessageId") && !msgObj!!.isNull("previousMessageId")) msgObj!!.optLong("previousMessageId") else null

            val req = repository.getRequestByUuid(msgUuid)
            val clientName = req?.name ?: "Guest"
            val isIncoming = (sender != "phone")

            val existingLocal = repository.getMessagesForClientSync(msgUuid).find { 
                it.serverId == currentId || 
                (it.serverId == 0L && it.messageText == text && it.isIncoming == isIncoming && kotlin.math.abs(it.timestamp - createdAt) < 15000) 
            }

            if (existingLocal != null) {
                if (existingLocal.serverId == 0L) {
                    repository.updateMessage(existingLocal.copy(serverId = currentId, previousMessageId = prevId))
                }
            } else {
                repository.insertMessage(
                    Message(
                        serverId = currentId,
                        clientUuid = msgUuid,
                        clientName = clientName,
                        messageText = text,
                        timestamp = createdAt,
                        filePath = filePath,
                        isIncoming = isIncoming,
                        previousMessageId = prevId
                    )
                )

                if (isIncoming) {
                    val cleanMsg = if (filePath != null) "🔗 File: [$text]" else text
                    showNotification("Message from $clientName", cleanMsg, 1003, msgUuid)
                }
            }

            currentId = if (prevId != null && prevId > 0) prevId else null
        }
    }

    fun publishToClient(clientUuid: String, payload: JSONObject) {
        scope.launch(Dispatchers.IO) {
            try {
                payload.put("clientUuid", clientUuid)

                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val requestBody = RequestBody.create(mediaType, payload.toString())
                val cleanUrl = getCleanServerUrl()
                val pinVal = apiKey.value

                Log.d("GatewayNetworkManager", "Posting host reply signal to server: $cleanUrl/api/host/response")
                val request = Request.Builder()
                    .url("$cleanUrl/api/host/response")
                    .header("Authorization", "Bearer $pinVal")
                    .post(requestBody)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w("GatewayNetworkManager", "Server reject reply routing: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                if (e is java.io.IOException) {
                    Log.w("GatewayNetworkManager", "Home Server not reachable to publish to client: ${e.message}")
                } else {
                    Log.e("GatewayNetworkManager", "Error publishing to client", e)
                }
            }
        }
    }

    fun initializeFirebase() {
        try {
            val savedPrefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
            val customSenderId = savedPrefs.getString("fcm_sender_id", null)
            val customProjectId = savedPrefs.getString("fcm_project_id", null)
            val customAppId = savedPrefs.getString("fcm_app_id", null)
            val customApiKey = savedPrefs.getString("fcm_api_key", null)

            if (customSenderId.isNullOrEmpty()) {
                Log.d("FCMInit", "No custom FCM sender ID set yet. Skipping Firebase initialization until server config is received.")
                return
            }

            if (!customSenderId.isNullOrEmpty() && !customSenderId.all { it.isDigit() }) {
                Log.e("FCMInit", "[FCM Config Warning] customSenderId ('$customSenderId') is NOT numeric. GCM/microG requires a numeric Sender ID (Project Number). Please re-run python fcm_setup.py on your Home Server to generate a whisker-push-sa.json with 'project_number'.")
            }

            val senderId = customSenderId
            val projectId = if (!customProjectId.isNullOrEmpty()) customProjectId else "whisker-project"
            val appId = if (!customAppId.isNullOrEmpty()) customAppId else "1:$customSenderId:android:whisker"
            val apiKey = if (!customApiKey.isNullOrEmpty()) customApiKey else "AIzaSyWhiskerAutoConfiguredKey"

            val existingApps = com.google.firebase.FirebaseApp.getApps(context.applicationContext)
            if (existingApps.isNotEmpty()) {
                val currentApp = existingApps[0]
                if (currentApp.options.gcmSenderId != senderId || currentApp.options.apiKey != apiKey) {
                    Log.d("FCMInit", "Re-initializing Firebase with updated Sender ID: $senderId, API Key: ${apiKey.take(6)}...")
                    try {
                        currentApp.delete()
                    } catch (e: Exception) {
                        Log.w("FCMInit", "Error deleting Firebase app: ${e.message}")
                    }
                } else {
                    Log.d("FCMInit", "Firebase already initialized with matching configuration.")
                    return
                }
            }

            val options = com.google.firebase.FirebaseOptions.Builder()
                .setApplicationId(appId)
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .setGcmSenderId(senderId)
                .build()

            com.google.firebase.FirebaseApp.initializeApp(context.applicationContext, options)
            Log.d("FCMInit", "Firebase App initialized with senderId: $senderId, projectId: $projectId, apiKey: ${apiKey.take(6)}...")
        } catch (e: Exception) {
            Log.e("FCMInit", "Error initializing Firebase App", e)
        }
    }

    fun updateCustomApiKey(apiKeyVal: String) {
        val cleanKey = apiKeyVal.trim()
        if (cleanKey.isEmpty()) return
        val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_api_key", cleanKey).apply()

        Log.d("FCMInit", "Manual FCM API key updated: ${cleanKey.take(6)}... Re-registering token...")
        registerFcmToken(forceRefresh = true)

        val serverUrlVal = serverUrl.value
        val pinVal = apiKey.value
        if (serverUrlVal.isNotEmpty() && pinVal.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                try {
                    val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                    val body = JSONObject().apply {
                        put("api_key", cleanKey)
                    }
                    val requestBody = RequestBody.create(mediaType, body.toString())
                    val request = Request.Builder()
                        .url("$serverUrlVal/api/host/fcm_config")
                        .header("Authorization", "Bearer $pinVal")
                        .post(requestBody)
                        .build()
                    okHttpClient.newCall(request).execute().close()
                } catch (e: Exception) {
                    Log.e("GatewayNetworkManager", "Failed to upload manual API key to Home Server: ${e.message}")
                }
            }
        }
    }

    fun saveFcmConfig(senderId: String, projectId: String, appId: String, apiKey: String) {
        val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("fcm_sender_id", senderId.trim())
            .putString("fcm_project_id", projectId.trim())
            .putString("fcm_app_id", appId.trim())
            .putString("fcm_api_key", apiKey.trim())
            .remove("fcm_token")
            .remove("fallback_device_push_token")
            .apply()

        Log.d("FCMInit", "Saved custom FCM configuration. Re-registering FCM token...")
        registerFcmToken(forceRefresh = true)
    }

    fun registerFcmToken(forceRefresh: Boolean = false) {
        val savedPrefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
        val customSenderId = savedPrefs.getString("fcm_sender_id", null)

        if (customSenderId.isNullOrEmpty()) {
            Log.d("FCMInit", "FCM Sender ID is missing in prefs. Waiting for Home Server config...")
            pushToken.value = ""
            return
        }

        val cachedToken = savedPrefs.getString("fcm_token", null)

        if (forceRefresh) {
            savedPrefs.edit().remove("fcm_token").apply()
        } else if (!cachedToken.isNullOrEmpty() && !cachedToken.startsWith("device-push-token-")) {
            pushToken.value = cachedToken
            updatePushToken(cachedToken)
            return
        }

        try {
            initializeFirebase()

            if (forceRefresh) {
                try {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener {
                        fetchFreshToken()
                    }
                } catch (e: Exception) {
                    Log.w("FCMInit", "deleteToken failed or ignored: ${e.message}")
                    fetchFreshToken()
                }
            } else {
                fetchFreshToken()
            }
        } catch (e: Exception) {
            Log.e("FCMInit", "[FCM Exception] Calling FirebaseMessaging failed: ${e.message}", e)
            pushToken.value = ""
            savedPrefs.edit().remove("fcm_token").remove("fallback_device_push_token").apply()
        }
    }

    private fun fetchFreshToken(retryAttempt: Int = 0) {
        val savedPrefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful && !task.result.isNullOrEmpty()) {
                    val token = task.result
                    Log.d("FCMInit", "[FCM Success] Active FCM registration token obtained from Google Play Services / microG: $token")
                    updatePushToken(token)
                } else {
                    val ex = task.exception
                    Log.e("FCMInit", "[FCM Registration Failed] Fetching FCM token failed from Google Play Services / microG (Attempt ${retryAttempt + 1}/3). " +
                            "Underlying error: ${ex?.message}", ex)
                    if (retryAttempt < 2) {
                        scope.launch {
                            kotlinx.coroutines.delay(4000L * (retryAttempt + 1))
                            Log.d("FCMInit", "Retrying FCM token retrieval (Attempt ${retryAttempt + 2}/3)...")
                            fetchFreshToken(retryAttempt + 1)
                        }
                    } else {
                        pushToken.value = ""
                        savedPrefs.edit().remove("fcm_token").remove("fallback_device_push_token").apply()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FCMInit", "[FCM Exception] Calling FirebaseMessaging.getInstance().token failed: ${e.message}", e)
            if (retryAttempt < 2) {
                scope.launch {
                    kotlinx.coroutines.delay(4000L * (retryAttempt + 1))
                    fetchFreshToken(retryAttempt + 1)
                }
            } else {
                pushToken.value = ""
                savedPrefs.edit().remove("fcm_token").remove("fallback_device_push_token").apply()
            }
        }
    }

    fun updatePushToken(token: String) {
        if (token.isEmpty()) return
        pushToken.value = token
        val savedPrefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
        savedPrefs.edit().putString("fcm_token", token).apply()
        
        // Post the updated token to the server if URL is configured asynchronously on IO thread
        val cleanUrl = serverUrl.value
        val pinVal = apiKey.value
        scope.launch(Dispatchers.IO) {
            registerPushTokenOnServer(cleanUrl, pinVal, token)
        }
    }

    fun updateServerUrl(url: String) {
        val cleanUrl = url.trim().removeSuffix("/")
        serverUrl.value = cleanUrl
        val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("server_url", cleanUrl).apply()
        _connectionStatus.value = "Connecting..."
        
        registerFcmToken()
        
        val pinVal = apiKey.value
        scope.launch(Dispatchers.IO) {
            fetchFcmConfigFromServer(cleanUrl, pinVal)
            var isSuccess = false
            var errorMsg: String? = null
            try {
                // Test connection to home server via host clients endpoint
                val request = Request.Builder()
                    .url("$cleanUrl/api/host/clients")
                    .header("Authorization", "Bearer $pinVal")
                    .get()
                    .build()
                    
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        isSuccess = true
                        lastRegisteredServerUrl = cleanUrl
                        lastRegisteredApiKey = pinVal
                    } else {
                        errorMsg = "HTTP Error ${response.code}: ${response.message}"
                    }
                }
            } catch (e: Exception) {
                errorMsg = e.localizedMessage ?: "Connection timed out"
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (isSuccess) {
                    android.widget.Toast.makeText(context, "Successfully initialized & linked with Home Server!", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(context, "Server link error: $errorMsg", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            
            fetchPendingEventsFromServerSuspended()
        }
    }

    suspend fun fetchInvitesFromServer(): List<Invite> {
        return withContext(Dispatchers.IO) {
            val serverUrlVal = serverUrl.value
            val pinVal = apiKey.value
            try {
                val request = Request.Builder()
                    .url("$serverUrlVal/api/host/invites")
                    .header("Authorization", "Bearer $pinVal")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val responseStr = response.body?.string()
                    if (response.isSuccessful && responseStr != null) {
                        val jsonArray = org.json.JSONArray(responseStr)
                        val invites = mutableListOf<Invite>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val code = obj.optString("code")
                            invites.add(
                                Invite(
                                    code = code,
                                    name = obj.optString("name", "Guest"),
                                    isUsed = obj.optInt("used", 0) == 1,
                                    createdTimestamp = obj.optLong("created_at", System.currentTimeMillis()),
                                    url = "${serverUrlVal.removeSuffix("/")}/invite/$code"
                                )
                            )
                        }
                        return@withContext invites
                    }
                }
            } catch (e: Exception) {
                if (e is java.io.IOException) {
                    Log.w("GatewayNetworkManager", "Home Server not reachable for invites at $serverUrlVal: ${e.message}")
                } else {
                    Log.e("GatewayNetworkManager", "fetchInvites error", e)
                }
            }
            return@withContext emptyList()
        }
    }

    suspend fun createInviteOnServer(name: String, code: String) {
        withContext(Dispatchers.IO) {
            val serverUrlVal = serverUrl.value
            val pinVal = apiKey.value
            try {
                val body = JSONObject().apply {
                    put("name", name)
                    put("code", code)
                }
                val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), body.toString())
                val request = Request.Builder()
                    .url("$serverUrlVal/api/host/create_invite")
                    .header("Authorization", "Bearer $pinVal")
                    .post(requestBody)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    Log.d("GatewayNetworkManager", "createInvite response: ${response.code}")
                }
            } catch (e: Exception) {
                if (e is java.io.IOException) {
                    Log.w("GatewayNetworkManager", "Home Server not reachable to create invite: ${e.message}")
                } else {
                    Log.e("GatewayNetworkManager", "createInvite error", e)
                }
            }
        }
    }

    suspend fun deleteInviteOnServer(code: String) {
        withContext(Dispatchers.IO) {
            val serverUrlVal = serverUrl.value
            val pinVal = apiKey.value
            try {
                val body = JSONObject().apply {
                    put("code", code)
                }
                val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), body.toString())
                val request = Request.Builder()
                    .url("$serverUrlVal/api/host/delete_invite")
                    .header("Authorization", "Bearer $pinVal")
                    .post(requestBody)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    Log.d("GatewayNetworkManager", "deleteInvite response: ${response.code}")
                }
            } catch (e: Exception) {
                if (e is java.io.IOException) {
                    Log.w("GatewayNetworkManager", "Home Server not reachable to delete invite: ${e.message}")
                } else {
                    Log.e("GatewayNetworkManager", "deleteInvite error", e)
                }
            }
        }
    }

    companion object {
        private val processedEventIds = Collections.synchronizedSet(LinkedHashSet<String>())
        private val processedMessageIds = Collections.synchronizedSet(LinkedHashSet<String>())
        @Volatile private var isPollingLoopRunning = false

        fun getInstance(context: Context): GatewayNetworkManager {
            return CompanionRegistry.getOrInitializeManager(context)
        }
    }

    fun setIncomingCallState(clientUuid: String, clientName: String, isVideo: Boolean = true, sdp: String = "") {
        val current = _activeCall.value
        if (current is CallState.Idle) {
            _activeCall.value = CallState.Incoming(clientUuid, clientName, isVideo, sdp)
        } else if (current is CallState.Incoming && current.clientUuid == clientUuid) {
            val updatedSdp = if (sdp.isNotEmpty()) sdp else current.sdp
            _activeCall.value = CallState.Incoming(clientUuid, clientName, isVideo, updatedSdp)
        }
    }

    fun acceptCall(clientUuid: String, clientName: String) {
        val current = _activeCall.value
        if (current is CallState.Active && current.clientUuid == clientUuid) return

        val currentIncoming = current as? CallState.Incoming
        val sdpOffer = currentIncoming?.sdp ?: ""
        val isVideo = currentIncoming?.isVideo ?: true

        cancelCallNotification()
        updateAudioModeForCall(true)
        _activeCall.value = CallState.Active(clientUuid, clientName, isVideo, 0, sdpOffer)
        scope.launch {
            repository.insertCallLog(
                CallLog(
                    clientUuid = clientUuid,
                    clientName = clientName,
                    status = "CONNECTED",
                    isIncoming = true
                )
            )
        }
    }

    fun publishSdpAnswer(clientUuid: String, answerSdp: String) {
        scope.launch {
            val payload = JSONObject().apply {
                put("type", "webrtc_call_answer")
                put("event", "call_answered")
                put("clientUuid", clientUuid)
                put("sdp", answerSdp)
            }
            publishToClient(clientUuid, payload)
        }
    }

    fun publishOutgoingSdpOffer(clientUuid: String, offerSdp: String) {
        scope.launch {
            val payload = JSONObject().apply {
                put("type", "webrtc_call_initiate")
                put("event", "call")
                put("clientUuid", clientUuid)
                put("name", "Phone Portal")
                put("video", true)
                put("sdp", offerSdp)
            }
            publishToClient(clientUuid, payload)
        }
    }

    fun publishIceCandidate(clientUuid: String, candidateJson: String) {
        scope.launch {
            val payload = JSONObject().apply {
                put("type", "webrtc_ice_candidate")
                put("event", "ice_candidate")
                put("clientUuid", clientUuid)
                put("candidate", candidateJson)
            }
            publishToClient(clientUuid, payload)
        }
    }

    fun declineOrEndCall() {
        val current = _activeCall.value
        cancelCallNotification()
        updateAudioModeForCall(false)
        _activeCall.value = CallState.Idle
        scope.launch {
            if (current is CallState.Active) {
                repository.insertCallLog(
                    CallLog(
                        clientUuid = current.clientUuid,
                        clientName = current.clientName,
                        status = "COMPLETED",
                        duration = current.durationSeconds.toLong(),
                        isIncoming = true
                    )
                )
                // Notify client of hangup
                val payload = JSONObject().apply {
                    put("type", "webrtc_call_hangup")
                    put("event", "call_ended")
                    put("clientUuid", current.clientUuid)
                }
                publishToClient(current.clientUuid, payload)
            } else if (current is CallState.Incoming) {
                repository.insertCallLog(
                    CallLog(
                        clientUuid = current.clientUuid,
                        clientName = current.clientName,
                        status = "DECLINED",
                        isIncoming = true
                    )
                )
                // Notify client of hangup / decline
                val payload = JSONObject().apply {
                    put("type", "webrtc_call_hangup")
                    put("event", "call_ended")
                    put("clientUuid", current.clientUuid)
                }
                publishToClient(current.clientUuid, payload)
            }
        }
    }

    fun startOutgoingCall(clientUuid: String, clientName: String, isVideo: Boolean = true) {
        cancelCallNotification()
        updateAudioModeForCall(true)
        _activeCall.value = CallState.Outgoing(clientUuid, clientName, isVideo)
        scope.launch {
            repository.insertCallLog(
                CallLog(
                    clientUuid = clientUuid,
                    clientName = clientName,
                    status = "OUTGOING",
                    isIncoming = false
                )
            )
        }
    }

    fun incrementCallDuration() {
        val current = _activeCall.value
        if (current is CallState.Active) {
            _activeCall.value = current.copy(durationSeconds = current.durationSeconds + 1)
        }
    }

    fun sendLocalMessage(clientUuid: String, clientName: String, text: String, filePath: String? = null) {
        scope.launch {
            repository.insertMessage(
                Message(
                    clientUuid = clientUuid,
                    clientName = clientName,
                    messageText = text,
                    filePath = filePath,
                    isIncoming = false,
                    isRead = true
                )
            )
            // Publish messaging backchannel to browser client
            val payload = JSONObject().apply {
                put("type", "chat_message")
                put("event", "message")
                put("sender", "phone")
                put("clientUuid", clientUuid)
                put("text", text)
                put("filePath", filePath)
                put("timestamp", System.currentTimeMillis())
            }
            publishToClient(clientUuid, payload)
        }
    }

    fun cancelCallNotification() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(1002)
        } catch (e: Exception) {
            Log.e("GatewayNetworkManager", "Error cancelling call notification", e)
        }
    }

    private fun updateAudioModeForCall(inCall: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (audioManager != null) {
                if (inCall) {
                    audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = true
                } else {
                    audioManager.mode = android.media.AudioManager.MODE_NORMAL
                }
            }
        } catch (e: Exception) {
            Log.e("GatewayNetworkManager", "Error updating audio mode", e)
        }
    }

    fun showIncomingCallNotification(clientUuid: String, clientName: String, isVideo: Boolean = true) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "incoming_calls_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val channel = NotificationChannel(
                channelId,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full screen incoming call alerts"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        // 1. Full-screen intent launching MainActivity
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("incoming_call", true)
            putExtra("client_uuid", clientUuid)
            putExtra("client_name", clientName)
            putExtra("is_video", isVideo)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            1002,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Answer Action Intent
        val answerIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("action_answer_call", true)
            putExtra("client_uuid", clientUuid)
            putExtra("client_name", clientName)
        }
        val answerPendingIntent = PendingIntent.getActivity(
            context,
            1003,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Decline Action Intent
        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = "ACTION_DECLINE_CALL"
            putExtra("client_uuid", clientUuid)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            1004,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.example.R.drawable.ic_launcher_foreground)
            .setContentTitle("Incoming ${if (isVideo) "Video" else "Audio"} Call")
            .setContentText("$clientName is calling...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(com.example.R.drawable.ic_launcher_foreground, "Answer", answerPendingIntent)
            .addAction(com.example.R.drawable.ic_launcher_foreground, "Decline", declinePendingIntent)

        notificationManager.notify(1002, builder.build())

        // Wake screen up immediately
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Whisker:IncomingCallWakeLock"
            )
            wakeLock.acquire(10000)
        } catch (e: Exception) {
            Log.e("GatewayNetworkManager", "Error acquiring full screen wake lock for incoming call", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Calls Channel (High importance, vibrates, wakes up for calls)
            val callChannel = NotificationChannel(
                "calls_channel",
                "Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming direct call alerts"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(callChannel)

            // Messages and Approvals Channel (High importance, does not trigger biometric bypass)
            val msgChannel = NotificationChannel(
                "messages_channel",
                "Messages and Approvals",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New message notifications and connection requests"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(msgChannel)
        }
    }

    private fun showNotification(title: String, body: String, notificationId: Int, chatUuid: String? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (chatUuid != null) {
                putExtra("chat_uuid", chatUuid)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isCall = title.contains("Call", ignoreCase = true)
        val channelId = if (isCall) "calls_channel" else "messages_channel"

        val soundUri = RingtoneManager.getDefaultUri(
            if (isCall) RingtoneManager.TYPE_RINGTONE
            else RingtoneManager.TYPE_NOTIFICATION
        )
        
        val uniqueNotificationId = if (isCall) notificationId else System.currentTimeMillis().toInt()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.example.R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSound(soundUri)

        if (isCall) {
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
        } else {
            builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(uniqueNotificationId, builder.build())

        // Play sound programmatically to ensure immediate reaction in foreground as well!
        if (CompanionRegistry.isAppInForeground) {
            try {
                val r = RingtoneManager.getRingtone(context, soundUri)
                r.play()
            } catch (e: Exception) {
                Log.e("GatewayNetworkManager", "Error playing ringtone programmatically", e)
            }
        }
    }
}
