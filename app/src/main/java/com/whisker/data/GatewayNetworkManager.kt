package com.whisker.data

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
import com.whisker.MainActivity
import com.whisker.R
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
    val sdp: String = "",
    val callId: String = ""
)

sealed class CallState {
    object Idle : CallState()
    data class Incoming(
        val clientUuid: String,
        val clientName: String,
        val isVideo: Boolean,
        val sdp: String = "",
        val isDirectCall: Boolean = false,
        val reason: String = "",
        val callId: String = ""
    ) : CallState()
    data class Outgoing(
        val clientUuid: String,
        val clientName: String,
        val isVideo: Boolean = false,
        val callId: String = ""
    ) : CallState()
    data class Active(
        val clientUuid: String,
        val clientName: String,
        val isVideo: Boolean = false,
        var durationSeconds: Int = 0,
        val sdp: String = "",
        val callId: String = ""
    ) : CallState()
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

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                "google_sdk" == Build.PRODUCT
    }

    private fun getDefaultServerUrl(): String {
        return ""
    }

    // Configuration Settings
    val serverUrl = MutableStateFlow("")
    val apiKey = MutableStateFlow("")
    val hostUsername = MutableStateFlow("")
    val autoApproveAllowed = MutableStateFlow(false)
    val allowDirectCalls = MutableStateFlow(false)
    val pushToken = MutableStateFlow("Fetching FCM push token...")
    val serverReachable = MutableStateFlow(false)
    val activeChatClientId = MutableStateFlow<String?>(null)

    // Realtime States
    private val _connectionStatus = MutableStateFlow("Initializing...")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _activeCall = MutableStateFlow<CallState>(CallState.Idle)
    val activeCall: StateFlow<CallState> = _activeCall.asStateFlow()

    private val _callDeclinedEvent = MutableStateFlow<String?>(null)
    val callDeclinedEvent: StateFlow<String?> = _callDeclinedEvent.asStateFlow()

    fun dismissCallDeclined() {
        _callDeclinedEvent.value = null
    }

    private val _onInviteClaimed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onInviteClaimed = _onInviteClaimed.asSharedFlow()

    private val _incomingSignal = MutableSharedFlow<WebRtcSignal>(replay = 16, extraBufferCapacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val incomingSignal: SharedFlow<WebRtcSignal> = _incomingSignal.asSharedFlow()

    private var lastRegisteredToken: String? = null
    private var lastRegisteredServerUrl: String? = null
    private var lastRegisteredApiKey: String? = null

    val allHomeServers = repository.allHomeServers
    val homeServers = MutableStateFlow<List<HomeServer>>(emptyList())

    private val fetchMutex = Mutex()

    fun getCleanServerUrl(): String = serverUrl.value.trim().removeSuffix("/")

    init {
        val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
        val savedEventIds = prefs.getStringSet("processed_event_ids", emptySet()) ?: emptySet()
        processedEventIds.addAll(savedEventIds)
        
        // Restore serverUrl (ignoring any legacy dummy localhost / 10.0.2.2 URLs)
        val storedServerUrl = prefs.getString("server_url", "") ?: ""
        if (storedServerUrl.contains("10.0.2.2") || storedServerUrl.contains("localhost")) {
            prefs.edit().remove("server_url").apply()
            serverUrl.value = ""
        } else {
            serverUrl.value = storedServerUrl
        }

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

        // Restore or initialize hostUsername
        val savedUsername = prefs.getString("host_username", "") ?: ""
        val defaultName = if (savedUsername.isNotEmpty()) savedUsername else (Build.MODEL ?: "Mobile Device")
        hostUsername.value = defaultName
        if (savedUsername.isEmpty()) {
            prefs.edit().putString("host_username", defaultName).apply()
        }

        // Restore allowDirectCalls
        val savedAllowDirectCalls = prefs.getBoolean("allow_direct_calls", false)
        allowDirectCalls.value = savedAllowDirectCalls

        createNotificationChannel()
        registerFcmToken()
        
        scope.launch(Dispatchers.IO) {
            val initialList = repository.getAllHomeServersSync()
            for (s in initialList) {
                val cleanUrl = s.url.trim().lowercase()
                if (cleanUrl.isBlank() || cleanUrl.contains("10.0.2.2") || cleanUrl.contains("localhost") || cleanUrl == "http://" || cleanUrl == "https://" || !cleanUrl.startsWith("http")) {
                    repository.deleteHomeServer(s)
                }
            }
            repository.allHomeServers.collect { list ->
                val validList = list.filter { s ->
                    val u = s.url.trim().lowercase()
                    u.isNotBlank() && (u.startsWith("http://") || u.startsWith("https://")) && !u.contains("10.0.2.2") && !u.contains("localhost") && u != "http://" && u != "https://"
                }
                for (s in list) {
                    if (s !in validList) {
                        repository.deleteHomeServer(s)
                    }
                }
                homeServers.value = validList
                val active = validList.firstOrNull { it.isEnabled } ?: validList.firstOrNull()
                if (active != null) {
                    serverUrl.value = active.url
                    apiKey.value = active.apiKey
                } else {
                    serverUrl.value = ""
                }
                updateAggregateConnectionStatus(validList)
            }
        }

        fetchPendingEventsFromServer()
        startForegroundPollingLoop()
    }

    fun updateAggregateConnectionStatus(list: List<HomeServer>) {
        val enabled = list.filter { it.isEnabled }
        if (enabled.isEmpty()) {
            serverReachable.value = false
            _connectionStatus.value = if (list.isEmpty()) "Disconnected" else "All Servers Disabled"
            return
        }
        val reachableCount = enabled.count { it.isReachable }
        serverReachable.value = reachableCount > 0
        if (reachableCount == enabled.size) {
            _connectionStatus.value = if (enabled.size > 1) "$reachableCount/${enabled.size} Online" else "Connected"
        } else if (reachableCount > 0) {
            _connectionStatus.value = "$reachableCount/${enabled.size} Online"
        } else {
            val anyConnecting = enabled.any { it.statusMessage == "Connecting..." }
            _connectionStatus.value = if (anyConnecting) "Connecting..." else "Disconnected"
        }
    }

    @Volatile private var isPollingLoopRunning = false

    private fun startForegroundPollingLoop() {
        synchronized(GatewayNetworkManager::class.java) {
            if (isPollingLoopRunning) return
            isPollingLoopRunning = true
        }
        scope.launch(Dispatchers.IO) {
            Log.d("GatewayNetworkManager", "Foreground polling loop activated.")
            while (true) {
                try {
                    val inForeground = CompanionRegistry.isAppInForeground
                    if (inForeground) {
                        fetchPendingEventsFromServerSuspended()
                    }
                } catch (e: Exception) {
                    Log.d("GatewayNetworkManager", "Polling loop cycle error: ${e.message}")
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

    suspend fun fetchPendingEventsFromServerSuspended(tickleEventId: String? = null, targetServerUrl: String? = null) {
        val servers = homeServers.value.ifEmpty {
            repository.getAllHomeServersSync()
        }
        val enabledServers = servers.filter { it.isEnabled }
        if (enabledServers.isEmpty()) {
            serverReachable.value = false
            _connectionStatus.value = if (servers.isEmpty()) "Disconnected" else "All Servers Disabled"
            return
        }

        val sortedServers = if (!targetServerUrl.isNullOrEmpty()) {
            val targetClean = targetServerUrl.trimEnd('/')
            enabledServers.sortedByDescending { it.url.trimEnd('/') == targetClean }
        } else {
            enabledServers
        }

        fetchMutex.withLock {
            var anyReachable = false
            for (server in sortedServers) {
                val ok = syncServerEventsAndClients(server, tickleEventId)
                if (ok) anyReachable = true
            }

            val updatedServers = repository.getAllHomeServersSync()
            updateAggregateConnectionStatus(updatedServers)

            // Remote peer federation polling
            try {
                val localClients = repository.getAllRequestsSync()
                for (remotePeer in localClients.filter { it.serverAddress != "local" && !servers.any { s -> s.url == it.serverAddress } && it.status == "APPROVED" && it.token.isNotEmpty() }) {
                    try {
                        val remoteUrl = remotePeer.serverAddress.trimEnd('/')
                        val remoteReq = Request.Builder()
                            .url("$remoteUrl/api/guest/events?uuid=${remotePeer.uuid}&token=${remotePeer.token}")
                            .get()
                            .build()
                        okHttpClient.newCall(remoteReq).execute().use { remoteResp ->
                            if (remoteResp.isSuccessful) {
                                val remoteBody = remoteResp.body?.string() ?: "[]"
                                val remoteEvents = JSONArray(remoteBody)
                                for (j in 0 until remoteEvents.length()) {
                                    val rEvt = remoteEvents.getJSONObject(j)
                                    val payload = if (rEvt.has("payload")) JSONObject(rEvt.getString("payload")) else rEvt
                                    payload.put("server_address", remoteUrl)
                                    payload.put("client_type", "MOBILE")
                                    processIncomingPayload(payload.toString(), fromServerPoll = true, originServerUrl = remoteUrl)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("GatewayNetworkManager", "Remote peer poll skipped (${remotePeer.name}): ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.d("GatewayNetworkManager", "Remote peer federation poll error: ${e.message}")
            }
        }
    }

    suspend fun syncServerEventsAndClients(server: HomeServer, tickleEventId: String? = null): Boolean {
        val cleanUrl = server.url.trim().removeSuffix("/")
        val pinVal = server.apiKey

        if (cleanUrl.isEmpty() || !cleanUrl.startsWith("http")) {
            repository.updateHomeServer(server.copy(isReachable = false, statusMessage = "Invalid URL"))
            return false
        }

        var currentServer = repository.getHomeServerById(server.id) ?: server
        if (currentServer.isHost && currentServer.registeredUuid.isEmpty()) {
            val (regOk, regUuid) = registerMobileWithServer(currentServer)
            if (regUuid.isNotEmpty()) {
                currentServer = repository.getHomeServerById(server.id) ?: currentServer.copy(registeredUuid = regUuid)
            }
        }

        // Authoritative admin & identity check via /api/host/me
        try {
            val meReq = Request.Builder()
                .url("$cleanUrl/api/host/me")
                .header("Authorization", "Bearer $pinVal")
                .get()
                .build()
            okHttpClient.newCall(meReq).execute().use { meResp ->
                if (meResp.isSuccessful) {
                    val meBody = meResp.body?.string() ?: ""
                    val meJson = JSONObject(meBody)
                    val meAdmin = meJson.optInt("is_admin", 0) == 1 || meJson.optBoolean("is_admin", false)
                    val meRole = meJson.optString("role", if (meAdmin) "ADMIN" else (if (currentServer.isMember) "MEMBER" else "COMPANION"))
                    val meUuid = meJson.optString("uuid", "")
                    val sName = meJson.optString("server_name", meJson.optString("servername", "")).ifBlank { currentServer.name }
                    val latest = repository.getHomeServerById(server.id) ?: currentServer
                    if (latest.isAdmin != meAdmin || latest.serverRole != meRole || (meUuid.isNotEmpty() && latest.registeredUuid != meUuid) || latest.name != sName) {
                        val updatedHost = latest.copy(
                            registeredUuid = if (meUuid.isNotEmpty()) meUuid else latest.registeredUuid,
                            isAdmin = meAdmin,
                            serverRole = meRole,
                            name = sName
                        )
                        repository.updateHomeServer(updatedHost)
                        currentServer = updatedHost
                    }
                } else if (meResp.code == 401 && currentServer.isHost) {
                    registerMobileWithServer(currentServer)
                }
            }
        } catch (_: Exception) {}

        var fetchSuccess = false

        try {
            val eventsUrl = if (!tickleEventId.isNullOrEmpty()) "$cleanUrl/api/host/events?eventId=$tickleEventId" else "$cleanUrl/api/host/events"
            Log.d("GatewayNetworkManager", "Polling Home Server (${server.name}): $eventsUrl")
            val request = Request.Builder()
                .url(eventsUrl)
                .header("Authorization", "Bearer $pinVal")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    fetchSuccess = true

                    val body = response.body?.string() ?: ""
                    val eventsArray = JSONArray(body)
                    for (i in 0 until eventsArray.length()) {
                        val eventObj = eventsArray.getJSONObject(i)
                        val id = eventObj.getString("id")
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
                            val normType = when (type) {
                                "chat_message" -> "message"
                                "webrtc_call_initiate", "call_initiate" -> "call"
                                "webrtc_ice_candidate", "webrtc_call_answer", "webrtc_call_hangup" -> "signal"
                                else -> if (payload.has("text") && !payload.has("sdp") && !payload.has("candidate")) "message" else type
                            }
                            payload.put("event", normType)
                            if (!payload.has("server_address") || payload.optString("server_address").isEmpty()) {
                                payload.put("server_address", cleanUrl)
                            }
                            processIncomingPayload(payload.toString(), fromServerPoll = true, originServerUrl = cleanUrl, originPin = pinVal)
                        }

                        ackEventOnServer(cleanUrl, pinVal, id)
                    }

                    syncFcmForServer(server)
                } else if ((response.code == 401 || response.code == 403) && currentServer.isHost) {
                    val (registered, regUuid) = registerMobileWithServer(currentServer)
                    if (registered) {
                        fetchSuccess = true
                        syncFcmForServer(currentServer)
                    }
                }
            }

            // Sync client presences
            val presenceReq = Request.Builder()
                .url("$cleanUrl/api/host/clients")
                .header("Authorization", "Bearer $pinVal")
                .get()
                .build()

            okHttpClient.newCall(presenceReq).execute().use { response ->
                if (response.isSuccessful) {
                    fetchSuccess = true
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

                    for (existing in localClients) {
                        val serverHasOnline = onlineUuids.contains(existing.uuid)
                        val serverObj = serverClients[existing.uuid]
                        if (serverObj != null) {
                            val serverStatus = serverObj.optString("req_status", existing.status)
                            val serverToken = serverObj.optString("token", existing.token)
                            val serverName = serverObj.optString("name", existing.name)
                            val serverClientType = serverObj.optString("client_type", if (serverObj.optBoolean("is_mobile_host", false) || serverStatus.startsWith("MOBILE_")) "MOBILE" else existing.clientType)
                            val srvAddrFromObj = serverObj.optString("server_address", "").trimEnd('/')
                            val serverAddressVal = if (srvAddrFromObj.isNotEmpty() && srvAddrFromObj != "local") {
                                srvAddrFromObj
                            } else if (existing.serverAddress == "local" || existing.serverAddress.isEmpty()) {
                                cleanUrl
                            } else {
                                existing.serverAddress
                            }
                            if (existing.isOnline != serverHasOnline || existing.status != serverStatus || existing.token != serverToken || existing.name != serverName || existing.clientType != serverClientType || existing.serverAddress != serverAddressVal) {
                                repository.updateRequest(existing.copy(
                                    isOnline = serverHasOnline,
                                    status = serverStatus,
                                    token = serverToken,
                                    name = serverName,
                                    clientType = serverClientType,
                                    serverAddress = serverAddressVal,
                                    lastSeen = System.currentTimeMillis()
                                ))
                            }
                        } else {
                            val belongsToThisServer = (existing.serverAddress == cleanUrl || (server.isPrimary && existing.serverAddress == "local"))
                            if (belongsToThisServer) {
                                if (existing.status == "APPROVED" || existing.status == "MOBILE_APPROVED" || existing.status == "MOBILE_REJECTED" || existing.status == "MOBILE_PENDING") {
                                    repository.deleteRequest(existing)
                                } else if (existing.isOnline != serverHasOnline) {
                                    repository.updateRequest(existing.copy(isOnline = serverHasOnline, lastSeen = System.currentTimeMillis()))
                                }
                            }
                        }
                    }

                    val localUuids = localClients.map { it.uuid }.toSet()
                    for ((uuid, clientObj) in serverClients) {
                        if (!localUuids.contains(uuid)) {
                            val reqStatus = clientObj.optString("req_status", "PENDING")
                            val name = clientObj.optString("name", "Guest")
                            val reason = clientObj.optString("reason", "")
                            val token = clientObj.optString("token", "")
                            val createdAt = clientObj.optLong("created_at", System.currentTimeMillis())
                            val isOnline = onlineUuids.contains(uuid)
                            val clientType = clientObj.optString("client_type", if (clientObj.optBoolean("is_mobile_host", false) || reqStatus.startsWith("MOBILE_")) "MOBILE" else "WEB")
                            val srvAddrFromObj = clientObj.optString("server_address", "").trimEnd('/')
                            val serverAddressVal = if (srvAddrFromObj.isNotEmpty() && srvAddrFromObj != "local") srvAddrFromObj else cleanUrl
                            val newRequest = ClientRequest(
                                uuid = uuid,
                                name = name,
                                message = reason,
                                status = reqStatus,
                                token = token,
                                timestamp = createdAt,
                                lastSeen = System.currentTimeMillis(),
                                isOnline = isOnline,
                                clientType = clientType,
                                serverAddress = serverAddressVal
                            )
                            repository.insertRequest(newRequest)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is java.io.IOException) {
                Log.w("GatewayNetworkManager", "Home Server unreachable (${server.name}, $cleanUrl): ${e.message}")
                if (server.isPrimary) {
                    tryProbeFallbackCandidates(pinVal)
                }
            } else {
                Log.e("GatewayNetworkManager", "Error syncing with server ${server.name} ($cleanUrl)", e)
            }
        }

        val latest = repository.getHomeServerById(server.id) ?: currentServer
        val updated = latest.copy(
            isReachable = fetchSuccess,
            lastSeen = if (fetchSuccess) System.currentTimeMillis() else latest.lastSeen,
            statusMessage = if (fetchSuccess) "Connected" else "Unreachable",
            registeredUuid = if (latest.registeredUuid.isNotEmpty()) latest.registeredUuid else currentServer.registeredUuid
        )
        repository.updateHomeServer(updated)
        return fetchSuccess
    }

    private fun tryProbeFallbackCandidates(pinVal: String) {
        val current = serverUrl.value.removeSuffix("/")
        val defaultUrls = setOf("http://10.0.2.2:3000", "http://10.0.2.2:8000", "http://localhost:3000", "http://localhost:8000")
        if (!defaultUrls.contains(current)) {
            // Never overwrite a user's configured custom domain / IP address
            return
        }
        val candidates = if (isEmulator()) {
            listOf("http://10.0.2.2:3000", "http://10.0.2.2:8000", "http://localhost:3000", "http://localhost:8000")
        } else {
            listOf("http://localhost:3000", "http://localhost:8000", "http://10.0.2.2:3000", "http://10.0.2.2:8000")
        }
        for (candidate in candidates) {
            if (candidate == current) continue
            try {
                val testReq = Request.Builder()
                    .url("$candidate/api/host/events")
                    .header("Authorization", "Bearer $pinVal")
                    .get()
                    .build()
                okHttpClient.newCall(testReq).execute().use { res ->
                    if (res.isSuccessful) {
                        Log.i("GatewayNetworkManager", "Found active Home Server at fallback candidate: $candidate")
                        val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("server_url", candidate).apply()
                        serverUrl.value = candidate
                        serverReachable.value = true
                        _connectionStatus.value = "Connected"
                        return
                    }
                }
            } catch (ignored: Exception) {}
        }
    }

    suspend fun fetchFcmConfigFromServer(serverUrlVal: String, pinVal: String) {
        val cleanUrl = serverUrlVal.trim().removeSuffix("/")
        if (cleanUrl.isEmpty() || !cleanUrl.startsWith("http")) return
        try {
            val request = Request.Builder()
                .url("$cleanUrl/api/host/fcm_config")
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
                Log.w("FCMInit", "Home Server not reachable at $cleanUrl: ${e.message}")
            } else {
                Log.e("FCMInit", "Failed to fetch FCM config from Home Server: ${e.message}")
            }
        }
    }

    suspend fun syncFcmForServer(server: HomeServer) {
        val cleanUrl = server.url.trim().removeSuffix("/")
        val pinVal = server.apiKey
        if (cleanUrl.isEmpty() || !cleanUrl.startsWith("http")) return
        try {
            val request = Request.Builder()
                .url("$cleanUrl/api/host/fcm_config")
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

                        val sName = json.optString("server_name", "").ifEmpty { json.optString("servername", "") }
                        val nameToUse = if (sName.isNotEmpty() && (server.name == "Home Server" || server.name.isEmpty())) sName else server.name

                        if (senderId.isNotEmpty() && (senderId != server.senderId || apiKey != server.fcmApiKey || nameToUse != server.name)) {
                            Log.d("FCMInit", "[FCM Config Sync] Received FCM config for server $nameToUse ($cleanUrl): Sender ID: $senderId")
                            val updated = server.copy(
                                name = nameToUse,
                                senderId = senderId,
                                projectId = projectId,
                                appId = appId,
                                fcmApiKey = apiKey
                            )
                            repository.updateHomeServer(updated)
                            registerPushTokenForServer(updated)
                            return
                        } else if (nameToUse != server.name) {
                            repository.updateHomeServer(server.copy(name = nameToUse))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is java.io.IOException) {
                Log.e("FCMInit", "Failed to fetch FCM config for ${server.name}: ${e.message}")
            }
        }
        registerPushTokenForServer(server)
    }

    fun registerPushTokenForServer(server: HomeServer) {
        val cleanUrl = server.url.trim().removeSuffix("/")
        val pinVal = server.apiKey
        if (cleanUrl.isEmpty() || pinVal.isEmpty()) return

        if (server.senderId.isNotEmpty() && server.senderId.all { it.isDigit() }) {
            try {
                val appName = "fcm_srv_${server.id.take(8)}"
                val senderId = server.senderId
                val projectId = if (server.projectId.isNotEmpty()) server.projectId else "whisker-project"
                val appId = if (server.appId.isNotEmpty()) server.appId else "1:$senderId:android:whisker"
                val apiKey = if (server.fcmApiKey.isNotEmpty()) server.fcmApiKey else "AIzaSyWhiskerAutoConfiguredKey"

                val existingApps = com.google.firebase.FirebaseApp.getApps(context.applicationContext)
                val targetApp = existingApps.find { it.name == appName } ?: run {
                    val options = com.google.firebase.FirebaseOptions.Builder()
                        .setApplicationId(appId)
                        .setApiKey(apiKey)
                        .setProjectId(projectId)
                        .setGcmSenderId(senderId)
                        .build()
                    com.google.firebase.FirebaseApp.initializeApp(context.applicationContext, options, appName)
                }

                targetApp.get(com.google.firebase.messaging.FirebaseMessaging::class.java).token.addOnCompleteListener { task ->
                    if (task.isSuccessful && !task.result.isNullOrEmpty()) {
                        val token = task.result
                        Log.d("FCMInit", "Obtained server-specific FCM token for ${server.name}: ${token.take(12)}...")
                        registerPushTokenOnServer(cleanUrl, pinVal, token)
                        if (token != server.fcmToken) {
                            scope.launch {
                                repository.updateHomeServer(server.copy(fcmToken = token))
                            }
                        }
                    } else {
                        registerPushTokenOnServer(cleanUrl, pinVal, pushToken.value)
                    }
                }
                return
            } catch (e: Exception) {
                Log.w("FCMInit", "Dedicated FirebaseApp initialization for ${server.name} fallback: ${e.message}")
            }
        }

        registerPushTokenOnServer(cleanUrl, pinVal, pushToken.value)
    }

    suspend fun registerMobileWithServer(server: HomeServer): Pair<Boolean, String> {
        val cleanUrl = server.url.trim().removeSuffix("/")
        val pinVal = server.apiKey
        if (cleanUrl.isEmpty() || !cleanUrl.startsWith("http")) return Pair(false, "")
        try {
            fetchFcmConfigFromServer(cleanUrl, pinVal)
            val cleanUser = server.clientIdentity.trim().ifEmpty { hostUsername.value.ifEmpty { Build.MODEL ?: "Mobile Device" } }
            val token = if (server.fcmToken.isNotEmpty()) server.fcmToken else pushToken.value
            val body = JSONObject().apply {
                put("username", cleanUser)
                put("pin_token", pinVal)
                put("push_token", token)
            }
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = RequestBody.create(mediaType, body.toString())
            val request = Request.Builder()
                .url("$cleanUrl/api/register_mobile")
                .post(requestBody)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respBody = response.body?.string() ?: ""
                    val json = JSONObject(respBody)
                    val status = json.optString("status", "")
                    val hostUuid = json.optString("host_uuid", "")
                    val serverIsAdmin = json.optInt("is_admin", 0) == 1 ||
                        json.optBoolean("is_admin", false) ||
                        json.optInt("admin", 0) == 1 ||
                        json.optBoolean("admin", false) ||
                        json.optString("role", "").equals("admin", ignoreCase = true)
                    val roleStr = json.optString("role", "").uppercase()
                    val calculatedRole = when {
                        roleStr.isNotEmpty() -> roleStr
                        serverIsAdmin -> "ADMIN"
                        else -> "COMPANION"
                    }
                    if (hostUuid.isNotEmpty()) {
                        val latest = repository.getHomeServerById(server.id) ?: server
                        val resolvedName = json.optString("server_name", json.optString("servername", latest.name)).ifBlank { latest.name }
                        if (latest.registeredUuid != hostUuid || latest.isAdmin != serverIsAdmin || latest.serverRole != calculatedRole || latest.name != resolvedName) {
                            repository.updateHomeServer(latest.copy(registeredUuid = hostUuid, isAdmin = serverIsAdmin, serverRole = calculatedRole, name = resolvedName))
                        }
                    }
                    if (server.allowDirectCalls) {
                        try {
                            val payload = JSONObject().apply { put("allow_direct_calls", true) }
                            val directBody = RequestBody.create(mediaType, payload.toString())
                            val dcReq = Request.Builder()
                                .url("$cleanUrl/api/host/direct_calls")
                                .header("Authorization", "Bearer $pinVal")
                                .post(directBody)
                                .build()
                            okHttpClient.newCall(dcReq).execute().close()
                        } catch (_: Exception) {}
                    }
                    return Pair(status == "APPROVED", hostUuid)
                }
            }
        } catch (e: Exception) {
            Log.d("GatewayNetworkManager", "registerMobileWithServer error for ${server.name}: ${e.message}")
        }
        return Pair(false, server.registeredUuid)
    }

    suspend fun addHomeServer(
        name: String,
        url: String,
        apiKey: String,
        isPrimary: Boolean = false,
        clientIdentity: String = "",
        allowDirectCalls: Boolean = false,
        isAdmin: Boolean = true
    ): HomeServer {
        val cleanUrl = url.trim().removeSuffix("/")
        val cleanKey = apiKey.trim().ifEmpty { this.apiKey.value.ifEmpty { UUID.randomUUID().toString() } }
        val cleanName = name.trim().ifEmpty { "Home Server" }
        val effectiveIdentity = clientIdentity.trim().ifEmpty { hostUsername.value.ifEmpty { Build.MODEL ?: "Mobile Device" } }

        val role = if (isAdmin) "ADMIN" else "COMPANION"
        val server = HomeServer(
            name = cleanName,
            url = cleanUrl,
            apiKey = cleanKey,
            isPrimary = false,
            isEnabled = true,
            isAdmin = isAdmin,
            serverRole = role,
            fcmToken = pushToken.value,
            isReachable = false,
            statusMessage = "Connecting...",
            registeredUuid = "",
            clientIdentity = effectiveIdentity,
            allowDirectCalls = allowDirectCalls
        )
        repository.insertHomeServer(server)
        if (serverUrl.value.isEmpty()) {
            serverUrl.value = cleanUrl
            this.apiKey.value = cleanKey
        }
        scope.launch(Dispatchers.IO) {
            registerMobileWithServer(server)
            syncServerEventsAndClients(server)
        }
        return server
    }

    suspend fun updateHomeServer(server: HomeServer) {
        val updated = server.copy(isPrimary = false)
        repository.updateHomeServer(updated)
        if (serverUrl.value.isEmpty() || serverUrl.value == server.url) {
            serverUrl.value = server.url.trim().removeSuffix("/")
            apiKey.value = server.apiKey.trim()
        }
        scope.launch(Dispatchers.IO) {
            syncServerEventsAndClients(updated)
        }
    }

    suspend fun deleteHomeServer(server: HomeServer) {
        repository.deleteHomeServer(server)
        val remaining = repository.getAllHomeServersSync()
        if (remaining.isNotEmpty()) {
            val nextActive = remaining.firstOrNull { it.isEnabled } ?: remaining.first()
            serverUrl.value = nextActive.url
            apiKey.value = nextActive.apiKey
        }
    }

    suspend fun toggleServerEnabled(server: HomeServer) {
        val nextEnabled = !server.isEnabled
        val updated = server.copy(
            isEnabled = nextEnabled,
            isReachable = if (nextEnabled) server.isReachable else false,
            statusMessage = if (nextEnabled) "Connecting..." else "Disabled"
        )
        repository.updateHomeServer(updated)
        if (nextEnabled) {
            scope.launch(Dispatchers.IO) {
                syncServerEventsAndClients(updated)
            }
        }
    }

    suspend fun setPrimaryServer(server: HomeServer) {
        // Equal status for all servers: update server without primary distinction
        repository.updateHomeServer(server.copy(isPrimary = false))
        serverUrl.value = server.url.trim().removeSuffix("/")
        apiKey.value = server.apiKey.trim()
    }

    suspend fun refreshAllServers() {
        val servers = repository.getAllHomeServersSync().filter { it.isEnabled }
        for (server in servers) {
            syncServerEventsAndClients(server)
        }
        val all = repository.getAllHomeServersSync()
        updateAggregateConnectionStatus(all)
    }

    suspend fun refreshServer(server: HomeServer) {
        syncServerEventsAndClients(server)
        val all = repository.getAllHomeServersSync()
        updateAggregateConnectionStatus(all)
    }

    fun updateHostUsername(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) {
            hostUsername.value = clean
            context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
                .edit().putString("host_username", clean).apply()
        }
    }

    private fun registerPushTokenOnServer(serverUrlVal: String, pinVal: String, pushTokenVal: String) {
        val cleanUrl = serverUrlVal.trim().removeSuffix("/")
        if (cleanUrl.isEmpty() || !cleanUrl.startsWith("http")) return
        val activeToken = if (pushTokenVal.isNotEmpty()) pushTokenVal else pushToken.value
        if (activeToken.isEmpty() || activeToken == "undefined" || activeToken == "null" || activeToken.startsWith("Fetching") || activeToken.startsWith("Searching") || activeToken.startsWith("device-push-token-")) return

        try {
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = JSONObject().apply {
                put("pushToken", activeToken)
                put("fcmToken", activeToken)
            }
            val requestBody = RequestBody.create(mediaType, body.toString())
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
                    Log.d("GatewayNetworkManager", "Successfully registered FCM push token on server ($cleanUrl): ${activeToken.take(12)}...")
                } else {
                    Log.w("GatewayNetworkManager", "Server $cleanUrl reject push-binding: ${response.code}")
                }
            }
        } catch (e: Exception) {
            if (e is java.io.IOException) {
                Log.w("GatewayNetworkManager", "Home Server $cleanUrl not reachable to save push settings: ${e.message}")
            } else {
                Log.e("GatewayNetworkManager", "Failed to save push settings on server $cleanUrl", e)
            }
        }
    }

    private fun ackEventOnServer(serverUrlVal: String, pinVal: String, eventId: String) {
        val cleanUrl = serverUrlVal.trim().removeSuffix("/")
        try {
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = JSONObject().apply {
                put("eventId", eventId)
            }
            val requestBody = RequestBody.create(mediaType, body.toString())
            val request = Request.Builder()
                .url("$cleanUrl/api/host/ack")
                .header("Authorization", "Bearer $pinVal")
                .post(requestBody)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("GatewayNetworkManager", "Ack error on server $cleanUrl: ${response.code}")
                }
            }
        } catch (e: Exception) {
            if (e is java.io.IOException) {
                Log.w("GatewayNetworkManager", "Home Server $cleanUrl not reachable to clear task: ${e.message}")
            } else {
                Log.e("GatewayNetworkManager", "Failed to clear task on server $cleanUrl", e)
            }
        }
    }

    fun processIncomingPayload(payloadStr: String, fromServerPoll: Boolean = false, originServerUrl: String? = null, originPin: String? = null) {
        try {
            val json = JSONObject(payloadStr)
            val rawEventType = json.optString("event", json.optString("type", ""))
            val eventType = when (rawEventType) {
                "chat_message" -> "message"
                "webrtc_call_initiate", "call_initiate" -> "call"
                "webrtc_ice_candidate", "webrtc_call_answer", "webrtc_call_hangup" -> "signal"
                else -> if (json.has("text") && !json.has("sdp") && !json.has("candidate")) "message" else rawEventType
            }
            val uuid = json.optString("uuid", json.optString("clientUuid", ""))
            val eventId = json.optString("eventId", json.optString("id", ""))
            val messageId = json.optString("messageId", "")

            val payloadServerAddr = json.optString("server_address", "").trimEnd('/')
            val effectiveServerUrl = when {
                !originServerUrl.isNullOrEmpty() -> originServerUrl.trimEnd('/')
                payloadServerAddr.isNotEmpty() && payloadServerAddr != "local" -> payloadServerAddr
                else -> getCleanServerUrl()
            }
            val effectivePin = originPin ?: (homeServers.value.find { it.url.trimEnd('/') == effectiveServerUrl }?.apiKey ?: apiKey.value)

            if (eventId.isNotEmpty() && !fromServerPoll) {
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

                "mobile_pairing_request" -> {
                    val username = json.optString("username", "Unknown Mobile App")
                    val hostUuid = json.optString("host_uuid", "")
                    if (hostUuid.isNotEmpty()) {
                        scope.launch {
                            val req = com.whisker.data.ClientRequest(
                                uuid = hostUuid,
                                name = username,
                                message = "Companion Mobile App / Tablet pairing request ($username)",
                                status = "MOBILE_PENDING",
                                token = "",
                                serverAddress = effectiveServerUrl
                            )
                            repository.insertRequest(req)
                            showNotification(
                                "📱 Mobile App Pairing Request",
                                "Companion mobile device '$username' is requesting pairing.",
                                1005
                            )
                        }
                    }
                }

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
                                    put("hostName", hostUsername.value)
                                    put("host_name", hostUsername.value)
                                }
                                publishToClient(existing.uuid, response)
                            }
                        } else {
                            val request = ClientRequest(
                                uuid = uuid,
                                name = name,
                                message = reason,
                                status = "PENDING",
                                token = "",
                                serverAddress = effectiveServerUrl
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
                "message", "chat_message" -> {
                    val token = json.optString("token", "")
                    val msgIdStr = json.optString("messageId", json.optString("id", ""))
                    val prevMsgIdStr = if (json.has("previousMessageId") && !json.isNull("previousMessageId")) json.optString("previousMessageId").takeIf { it.isNotEmpty() && it != "null" } else null

                    scope.launch(Dispatchers.IO) {
                        var request = repository.getRequestByUuid(uuid)
                        val name = json.optString("name", "Guest")
                        if (request == null) {
                            val newReq = ClientRequest(
                                uuid = uuid,
                                name = name,
                                message = "Visitor / Chat Partner",
                                status = "APPROVED",
                                token = token,
                                timestamp = System.currentTimeMillis(),
                                lastSeen = System.currentTimeMillis(),
                                isOnline = true,
                                serverAddress = effectiveServerUrl
                            )
                            repository.insertRequest(newReq)
                            request = newReq
                        } else if (request.status != "APPROVED" || (token.isNotEmpty() && request.token.isEmpty()) || request.serverAddress == "local" || request.serverAddress.isEmpty()) {
                            val updatedReq = request.copy(
                                status = "APPROVED",
                                token = if (token.isNotEmpty()) token else request.token,
                                name = if (name != "Guest" && request.name == "Guest") name else request.name,
                                serverAddress = if (request.serverAddress == "local" || request.serverAddress.isEmpty()) effectiveServerUrl else request.serverAddress,
                                lastSeen = System.currentTimeMillis()
                            )
                            repository.updateRequest(updatedReq)
                            request = updatedReq
                        }

                        val rawText = if (json.isNull("text")) "" else json.optString("text", "")
                        val text = if (rawText == "null") "" else rawText
                        val file = if (json.isNull("file")) (if (json.isNull("filePath")) null else json.optString("filePath").takeIf { it.isNotEmpty() && it != "null" }) else json.optString("file").takeIf { it.isNotEmpty() && it != "null" }
                        val effectiveText = if (text.isEmpty() && file != null) "Attachment" else text
                        val msgTimestamp = json.optLong("timestamp", System.currentTimeMillis())

                        val existingMsgs = repository.getMessagesForClientSync(uuid)
                        val isDuplicate = existingMsgs.any { m ->
                            (msgIdStr.isNotEmpty() && m.serverId == msgIdStr) ||
                            (m.messageText == effectiveText && m.filePath == file && kotlin.math.abs(m.timestamp - msgTimestamp) < 10000)
                        }

                        if (!isDuplicate) {
                            repository.insertMessage(
                                Message(
                                    serverId = msgIdStr,
                                    clientUuid = uuid,
                                    clientName = request.name,
                                    messageText = effectiveText,
                                    filePath = file,
                                    isIncoming = true,
                                    timestamp = msgTimestamp,
                                    previousMessageId = prevMsgIdStr
                                )
                            )
                            val cleanMsg = if (file != null) "🔗 File: [$effectiveText]" else (if (effectiveText.isNotEmpty()) effectiveText else "New message")
                            showNotification(
                                "Message from ${request.name}",
                                cleanMsg,
                                1003,
                                uuid
                            )
                        }

                        if (!prevMsgIdStr.isNullOrEmpty() && effectiveServerUrl.isNotEmpty()) {
                            fetchAndSyncMessageChain(effectiveServerUrl, effectivePin, uuid, prevMsgIdStr)
                        }
                    }
                }
                "call" -> {
                    val token = json.optString("token", "")
                    val name = json.optString("name", "Caller")
                    val isVideo = json.optBoolean("video", true)
                    val sdp = json.optString("sdp", "")
                    val isDirectCall = json.optBoolean("is_direct_call", false)
                    val reason = json.optString("reason", "")
                    val callId = json.optString("call_id", json.optString("callId", ""))

                    scope.launch {
                        val existing = repository.getRequestByUuid(uuid)
                        val callerName = if (!existing?.name.isNullOrEmpty() && existing?.name != "Guest") existing!!.name else name
                        val isAutoAccept = if (!isDirectCall) (existing?.autoAcceptCall == true) else false

                        if (existing == null && !isDirectCall) {
                            repository.insertRequest(
                                ClientRequest(
                                    uuid = uuid,
                                    name = callerName,
                                    message = "Approved Contact",
                                    status = "APPROVED",
                                    token = token,
                                    timestamp = System.currentTimeMillis(),
                                    lastSeen = System.currentTimeMillis(),
                                    isOnline = true,
                                    autoAcceptCall = false,
                                    serverAddress = effectiveServerUrl
                                )
                            )
                        } else if (existing != null && (existing.status != "APPROVED" || (token.isNotEmpty() && existing.token != token) || existing.serverAddress == "local" || existing.serverAddress.isEmpty()) && !isDirectCall) {
                            repository.updateRequest(
                                existing.copy(
                                    status = "APPROVED",
                                    token = if (token.isNotEmpty()) token else existing.token,
                                    name = callerName,
                                    serverAddress = if (existing.serverAddress == "local" || existing.serverAddress.isEmpty()) effectiveServerUrl else existing.serverAddress,
                                    lastSeen = System.currentTimeMillis()
                                )
                            )
                        }

                        setIncomingCallState(uuid, callerName, isVideo, sdp, isDirectCall, reason, callId)

                        if (isAutoAccept) {
                            Log.d("GatewayNetworkManager", "Auto-accepting incoming call from $callerName ($uuid), callId: $callId")
                            showIncomingCallNotification(uuid, callerName, isVideo, autoAccept = true)
                            if (CompanionRegistry.isAppInForeground) {
                                acceptCall(uuid, callerName, isVideo, sdp, callId)
                            }
                        } else {
                            showIncomingCallNotification(uuid, callerName, isVideo, autoAccept = false)
                        }
                    }
                }
                "signal" -> {
                    val signalType = json.optString("type", "webrtc_ice_candidate")
                    val candidate = json.opt("candidate")?.toString() ?: ""
                    val sdp = json.optString("sdp", "")
                    val status = json.optString("status", "")
                    val reason = json.optString("reason", "")
                    val callId = json.optString("call_id", json.optString("callId", ""))
                    _incomingSignal.tryEmit(WebRtcSignal(uuid, signalType, candidate, sdp, callId))

                    if (signalType in listOf("webrtc_call_answer", "call_answered")) {
                        scope.launch {
                            val current = _activeCall.value
                            if (current is CallState.Outgoing && (current.clientUuid == uuid || uuid.isEmpty())) {
                                if (callId.isEmpty() || current.callId.isEmpty() || current.callId == callId) {
                                    _activeCall.value = CallState.Active(
                                        clientUuid = current.clientUuid,
                                        clientName = current.clientName,
                                        isVideo = current.isVideo,
                                        durationSeconds = 0,
                                        sdp = sdp,
                                        callId = if (callId.isNotEmpty()) callId else current.callId
                                    )
                                    updateAudioModeForCall(true)
                                }
                            }
                        }
                    } else if (signalType in listOf("webrtc_call_hangup", "call_ended", "call_declined", "call_rejected") || status == "declined" || reason == "declined") {
                        scope.launch {
                            val current = _activeCall.value
                            val currentCallId = when (current) {
                                is CallState.Outgoing -> current.callId
                                is CallState.Active -> current.callId
                                is CallState.Incoming -> current.callId
                                else -> ""
                            }
                            val matchesCall = (callId.isEmpty() || currentCallId.isEmpty() || currentCallId == callId)
                            if (current is CallState.Outgoing && (current.clientUuid == uuid || uuid.isEmpty()) && matchesCall) {
                                val peerName = current.clientName.ifEmpty { "The recipient" }
                                _activeCall.value = CallState.Idle
                                cancelCallNotification()
                                updateAudioModeForCall(false)
                                _callDeclinedEvent.value = "$peerName declined the call."
                            } else if (current !is CallState.Idle && matchesCall) {
                                _activeCall.value = CallState.Idle
                                cancelCallNotification()
                                updateAudioModeForCall(false)
                            }
                        }
                    }
                }
                "call_answered" -> {
                    val sdp = json.optString("sdp", "")
                    val callId = json.optString("call_id", json.optString("callId", ""))
                    _incomingSignal.tryEmit(WebRtcSignal(uuid, "webrtc_call_answer", "", sdp, callId))
                    scope.launch {
                        val current = _activeCall.value
                        if (current is CallState.Outgoing && (current.clientUuid == uuid || uuid.isEmpty())) {
                            if (callId.isEmpty() || current.callId.isEmpty() || current.callId == callId) {
                                _activeCall.value = CallState.Active(
                                    clientUuid = current.clientUuid,
                                    clientName = current.clientName,
                                    isVideo = current.isVideo,
                                    durationSeconds = 0,
                                    sdp = sdp,
                                    callId = if (callId.isNotEmpty()) callId else current.callId
                                )
                                updateAudioModeForCall(true)
                            }
                        }
                    }
                }
                "hangup", "call_declined", "call_rejected" -> {
                    val status = json.optString("status", "")
                    val reason = json.optString("reason", "")
                    val callId = json.optString("call_id", json.optString("callId", ""))
                    scope.launch {
                        val current = _activeCall.value
                        val currentCallId = when (current) {
                            is CallState.Outgoing -> current.callId
                            is CallState.Active -> current.callId
                            is CallState.Incoming -> current.callId
                            else -> ""
                        }
                        val matchesCall = (callId.isEmpty() || currentCallId.isEmpty() || currentCallId == callId)
                        if (current is CallState.Outgoing && (current.clientUuid == uuid || uuid.isEmpty()) && matchesCall) {
                            val peerName = current.clientName.ifEmpty { "The recipient" }
                            _activeCall.value = CallState.Idle
                            cancelCallNotification()
                            updateAudioModeForCall(false)
                            _callDeclinedEvent.value = "$peerName declined the call."
                        } else if (current !is CallState.Idle && matchesCall) {
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
                                isOnline = true,
                                serverAddress = effectiveServerUrl
                            )
                            repository.insertRequest(newRequest)
                        } else {
                            repository.updateRequest(existing.copy(
                                status = "APPROVED",
                                token = token,
                                name = name,
                                serverAddress = if (existing.serverAddress == "local" || existing.serverAddress.isEmpty()) effectiveServerUrl else existing.serverAddress
                            ))
                        }

                        showNotification(
                            "Invite Claimed",
                            "Guest '$name' has claimed your invite link.",
                            1004
                        )
                    }
                }
            }

            if (eventId.isNotEmpty() && !fromServerPoll) {
                if (effectiveServerUrl.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        ackEventOnServer(effectiveServerUrl, effectivePin, eventId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GatewayNetworkManager", "Error parsing inner payload: $payloadStr", e)
        }
    }

    private suspend fun fetchAndSyncMessageChain(cleanUrl: String, pinVal: String, uuid: String, startMsgId: String) {
        var currentId: String? = startMsgId
        val visited = mutableSetOf<String>()
        while (!currentId.isNullOrEmpty() && !visited.contains(currentId)) {
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
            val rawText = if (msgObj!!.isNull("text")) "" else msgObj!!.optString("text", "")
            val text = if (rawText == "null") "" else rawText
            val filePath = if (msgObj!!.has("filePath") && !msgObj!!.isNull("filePath")) msgObj!!.optString("filePath").takeIf { it.isNotEmpty() && it != "null" } else null
            val effectiveText = if (text.isEmpty() && filePath != null) "Attachment" else text
            val createdAt = msgObj!!.optLong("createdAt", System.currentTimeMillis())
            val prevId = if (msgObj!!.has("previousMessageId") && !msgObj!!.isNull("previousMessageId")) msgObj!!.optString("previousMessageId").takeIf { it.isNotEmpty() && it != "null" } else null

            var req = repository.getRequestByUuid(msgUuid)
            if (req == null) {
                val newReq = ClientRequest(
                    uuid = msgUuid,
                    name = "Guest",
                    message = "Visitor / Chat Partner",
                    status = "APPROVED",
                    token = "",
                    timestamp = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis(),
                    isOnline = true
                )
                repository.insertRequest(newReq)
                req = newReq
            }
            val clientName = req.name
            val isIncoming = (sender != "phone")

            val existingLocal = repository.getMessagesForClientSync(msgUuid).find { 
                it.serverId == currentId || 
                (it.serverId.isEmpty() && it.messageText == effectiveText && it.isIncoming == isIncoming && kotlin.math.abs(it.timestamp - createdAt) < 15000) 
            }

            if (existingLocal != null) {
                if (existingLocal.serverId.isEmpty()) {
                    repository.updateMessage(existingLocal.copy(serverId = currentId, previousMessageId = prevId))
                }
            } else {
                repository.insertMessage(
                    Message(
                        serverId = currentId,
                        clientUuid = msgUuid,
                        clientName = clientName,
                        messageText = effectiveText,
                        timestamp = createdAt,
                        filePath = filePath,
                        isIncoming = isIncoming,
                        previousMessageId = prevId
                    )
                )

                if (isIncoming) {
                    val cleanMsg = if (filePath != null) "🔗 File: [$effectiveText]" else (if (effectiveText.isNotEmpty()) effectiveText else "New message")
                    showNotification("Message from $clientName", cleanMsg, 1003, msgUuid)
                }
            }

            currentId = prevId
        }
    }

    fun publishToClient(clientUuid: String, payload: JSONObject) {
        scope.launch(Dispatchers.IO) {
            try {
                payload.put("clientUuid", clientUuid)

                val peer = repository.getRequestByUuid(clientUuid)
                val peerServerAddress = peer?.serverAddress?.trimEnd('/') ?: ""
                val currentServers = homeServers.value.ifEmpty { repository.getAllHomeServersSync() }
                val registeredHomeServer = currentServers.find { it.url.trimEnd('/') == peerServerAddress }

                if (peer != null && peer.serverAddress != "local" && peer.serverAddress.startsWith("http") && registeredHomeServer == null) {
                    val remoteUrl = peer.serverAddress.trimEnd('/')
                    val pType = payload.optString("type", payload.optString("event", ""))
                    val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

                    val targetEndpoint = when {
                        pType in listOf("chat_message", "message") -> "$remoteUrl/api/message"
                        pType in listOf("webrtc_call_initiate", "call") -> "$remoteUrl/api/call"
                        pType in listOf("webrtc_call_hangup", "call_ended", "hangup") -> "$remoteUrl/api/hangup"
                        else -> "$remoteUrl/api/signal"
                    }

                    val remotePayload = JSONObject().apply {
                        put("uuid", peer.uuid)
                        put("token", peer.token)
                        put("type", pType)
                        put("event", payload.optString("event", pType))
                        if (payload.has("sdp")) put("sdp", payload.getString("sdp"))
                        if (payload.has("candidate")) put("candidate", payload.opt("candidate"))
                        if (payload.has("text")) put("text", payload.optString("text"))
                        if (payload.has("file")) put("file", payload.optString("file"))
                        if (payload.has("filePath")) put("filePath", payload.optString("filePath"))
                        if (payload.has("call_id")) put("call_id", payload.getString("call_id"))
                        if (payload.has("callId")) put("callId", payload.getString("callId"))
                        if (payload.has("status")) put("status", payload.getString("status"))
                        if (payload.has("reason")) put("reason", payload.getString("reason"))
                        put("name", hostUsername.value)
                        put("video", payload.optBoolean("video", true))
                    }

                    val request = Request.Builder()
                        .url(targetEndpoint)
                        .post(RequestBody.create(mediaType, remotePayload.toString()))
                        .build()

                    okHttpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.w("GatewayNetworkManager", "Remote endpoint $targetEndpoint error: ${resp.code}")
                        }
                    }
                    return@launch
                }

                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val requestBody = RequestBody.create(mediaType, payload.toString())
                val targetServer = registeredHomeServer
                    ?: currentServers.find { it.url.trimEnd('/') == peerServerAddress }
                    ?: currentServers.find { it.isEnabled }
                    ?: currentServers.firstOrNull()
                val cleanUrl = targetServer?.url?.trimEnd('/') ?: getCleanServerUrl()
                val pinVal = targetServer?.apiKey ?: apiKey.value

                Log.d("GatewayNetworkManager", "Posting host reply signal to server (${targetServer?.name ?: "default"}): $cleanUrl/api/host/response")
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

    fun claimInviteUrl(inviteUrl: String, onResult: (Boolean, String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val cleanInvite = inviteUrl.trim()
            if (cleanInvite.isEmpty()) {
                withContext(Dispatchers.Main) { onResult(false, "Please enter an invite URL or code") }
                return@launch
            }

            val cleanUrl = getCleanServerUrl()

            try {
                var targetBase = cleanUrl
                var code = cleanInvite
                if (cleanInvite.contains("/invite/")) {
                    val parts = cleanInvite.split("/invite/")
                    targetBase = parts[0].trimEnd('/')
                    code = parts[1].substringBefore('?').substringBefore('#').trim()
                }

                if (targetBase.isEmpty() || !targetBase.startsWith("http")) {
                    withContext(Dispatchers.Main) { onResult(false, "Please enter a full invite URL (e.g. http://server:8000/invite/code)") }
                    return@launch
                }

                val allServers = repository.getAllHomeServersSync()
                val existingMemberServer = allServers.find { it.url.removeSuffix("/") == targetBase.removeSuffix("/") && it.isMember }

                val json = JSONObject().apply {
                    put("code", code)
                    put("name", hostUsername.value)
                    put("client_type", "MOBILE")
                    if (existingMemberServer != null && existingMemberServer.registeredUuid.isNotEmpty()) {
                        put("existing_uuid", existingMemberServer.registeredUuid)
                        put("existing_token", existingMemberServer.apiKey)
                    }
                    if (pushToken.value.isNotEmpty()) {
                        put("push_token", pushToken.value)
                    }
                }
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val req = Request.Builder()
                    .url("$targetBase/api/claim_invite")
                    .post(RequestBody.create(mediaType, json.toString()))
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val respBody = JSONObject(resp.body?.string() ?: "{}")
                        if (respBody.optBoolean("success", false)) {
                            val clientUuid = respBody.getString("uuid")
                            val clientToken = respBody.getString("token")
                            val hostUuid = respBody.optString("host_uuid", "").ifEmpty { respBody.optString("hostId", "") }
                            val hostName = respBody.optString("host_name", respBody.optString("name", "Mobile App"))
                            val sName = respBody.optString("server_name", respBody.optString("servername", hostName))
                            val serverAddressStr = targetBase

                            registerInviteHomeServer(serverAddressStr, sName, clientUuid, clientToken)

                            val contactUuid = if (hostUuid.isNotEmpty()) hostUuid else clientUuid
                            val newReq = ClientRequest(
                                uuid = contactUuid,
                                name = hostName,
                                message = "Connected via invite ($serverAddressStr)",
                                status = "APPROVED",
                                token = clientToken,
                                clientType = "MOBILE",
                                serverAddress = serverAddressStr,
                                isOnline = false
                            )
                            repository.insertRequest(newReq)
                            withContext(Dispatchers.Main) {
                                onResult(true, "Connected to $hostName ($serverAddressStr)")
                            }
                            return@launch
                        } else {
                            val err = respBody.optString("error", "Invite claim failed")
                            withContext(Dispatchers.Main) { onResult(false, err) }
                        }
                    } else {
                        withContext(Dispatchers.Main) { onResult(false, "Server returned HTTP ${resp.code}") }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, "Error: ${e.localizedMessage ?: e.message}") }
            }
        }
    }

    private suspend fun registerInviteHomeServer(
        serverUrlStr: String,
        serverNameStr: String,
        clientUuid: String,
        clientToken: String
    ) {
        val cleanUrl = serverUrlStr.trim().removeSuffix("/")
        if (cleanUrl.isBlank() || !cleanUrl.startsWith("http")) return
        val displayName = serverNameStr.trim().ifBlank { "Home Server" }
        val allServers = repository.getAllHomeServersSync()
        val match = allServers.find { it.url.removeSuffix("/") == cleanUrl }
        val serverEntity = if (match != null) {
            val updated = match.copy(
                name = if (displayName != "Home Server") displayName else match.name,
                apiKey = clientToken,
                registeredUuid = clientUuid,
                isAdmin = false,
                serverRole = "MEMBER",
                isEnabled = true,
                isReachable = true,
                statusMessage = "Connected as Member"
            )
            repository.updateHomeServer(updated)
            updated
        } else {
            val newServer = HomeServer(
                name = displayName,
                url = cleanUrl,
                apiKey = clientToken,
                isPrimary = false,
                isEnabled = true,
                isAdmin = false,
                serverRole = "MEMBER",
                registeredUuid = clientUuid,
                statusMessage = "Connected as Member",
                isReachable = true,
                clientIdentity = hostUsername.value
            )
            repository.insertHomeServer(newServer)
            newServer
        }
        val updatedList = repository.getAllHomeServersSync()
        updateAggregateConnectionStatus(updatedList)
        scope.launch(Dispatchers.IO) {
            syncServerEventsAndClients(serverEntity)
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
        
        scope.launch(Dispatchers.IO) {
            val servers = repository.getAllHomeServersSync()
            for (srv in servers) {
                registerPushTokenForServer(srv)
            }
        }
    }

    suspend fun checkAndRegisterMobileStatus(serverUrlVal: String, pinVal: String): Boolean {
        if (serverUrlVal.isEmpty() || !serverUrlVal.startsWith("http")) return false
        try {
            fetchFcmConfigFromServer(serverUrlVal, pinVal)
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val cleanUser = if (hostUsername.value.isNotEmpty()) hostUsername.value else (Build.MODEL ?: "Mobile Device")
            val token = pushToken.value
            val body = JSONObject().apply {
                put("username", cleanUser)
                put("pin_token", pinVal)
                put("push_token", token)
            }
            val requestBody = RequestBody.create(mediaType, body.toString())
            val request = Request.Builder()
                .url("$serverUrlVal/api/register_mobile")
                .post(requestBody)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respBody = response.body?.string() ?: ""
                    val json = JSONObject(respBody)
                    val status = json.optString("status", "")
                    val hostUuid = json.optString("host_uuid", "")
                    val isAdminVal = json.optInt("is_admin", 0) == 1 || json.optBoolean("is_admin", false)
                    if (hostUuid.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            val matching = repository.getAllHomeServersSync().find {
                                it.url.removeSuffix("/") == serverUrlVal.removeSuffix("/")
                            }
                            if (matching != null && (matching.registeredUuid != hostUuid || matching.isAdmin != isAdminVal)) {
                                repository.updateHomeServer(matching.copy(registeredUuid = hostUuid, isAdmin = isAdminVal))
                            }
                        }
                    }
                    if (status == "APPROVED") {
                        serverReachable.value = true
                        _connectionStatus.value = "Connected"
                        lastRegisteredServerUrl = serverUrlVal
                        lastRegisteredApiKey = pinVal
                        return true
                    } else if (status == "PENDING") {
                        serverReachable.value = false
                        _connectionStatus.value = "Pairing pending admin approval..."
                    } else if (status == "REJECTED") {
                        serverReachable.value = false
                        _connectionStatus.value = "Pairing rejected by admin"
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("GatewayNetworkManager", "checkAndRegisterMobileStatus error: ${e.message}")
        }
        return false
    }

    fun updateSettings(url: String, username: String) {
        val cleanUrl = url.trim().removeSuffix("/")
        val cleanUser = username.trim().ifEmpty { Build.MODEL ?: "Mobile Device" }
        serverUrl.value = cleanUrl
        hostUsername.value = cleanUser
        val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("server_url", cleanUrl)
            .putString("host_username", cleanUser)
            .apply()

        _connectionStatus.value = "Connecting..."
        registerFcmToken()
        val pinVal = apiKey.value

        scope.launch(Dispatchers.IO) {
            fetchFcmConfigFromServer(cleanUrl, pinVal)
            var isSuccess = false
            var errorMsg: String? = null
            try {
                // Register mobile app (Pairing request)
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val body = JSONObject().apply {
                    put("username", cleanUser)
                    put("pin_token", pinVal)
                    put("push_token", pushToken.value)
                }
                val requestBody = RequestBody.create(mediaType, body.toString())
                val request = Request.Builder()
                    .url("$cleanUrl/api/register_mobile")
                    .post(requestBody)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val respBody = response.body?.string()
                        if (respBody != null) {
                            val json = JSONObject(respBody)
                            val status = json.optString("status", "")
                            if (status == "APPROVED") {
                                isSuccess = true
                                lastRegisteredServerUrl = cleanUrl
                                lastRegisteredApiKey = pinVal
                                val hostUuid = json.optString("host_uuid", "")
                                val isAdminVal = json.optInt("is_admin", 0) == 1 || json.optBoolean("is_admin", false)
                                val sName = json.optString("server_name", json.optString("servername", "Home Server")).ifBlank { "Home Server" }
                                val allServers = repository.getAllHomeServersSync()
                                val match = allServers.find { it.url.removeSuffix("/") == cleanUrl.removeSuffix("/") }
                                if (match != null) {
                                    repository.updateHomeServer(match.copy(
                                        apiKey = pinVal,
                                        registeredUuid = if (hostUuid.isNotEmpty()) hostUuid else match.registeredUuid,
                                        isAdmin = isAdminVal,
                                        name = sName
                                    ))
                                } else {
                                    repository.insertHomeServer(HomeServer(
                                        name = sName,
                                        url = cleanUrl,
                                        apiKey = pinVal,
                                        isPrimary = allServers.isEmpty(),
                                        registeredUuid = hostUuid,
                                        isAdmin = isAdminVal,
                                        clientIdentity = cleanUser,
                                        isEnabled = true
                                    ))
                                }
                            } else if (status == "PENDING") {
                                errorMsg = "Pairing request pending admin approval."
                            } else if (status == "REJECTED") {
                                errorMsg = "Pairing request was rejected."
                            }
                        }
                    } else {
                        errorMsg = "HTTP Error ${response.code}: ${response.message}"
                    }
                }
            } catch (e: Exception) {
                errorMsg = "Connection failed: ${e.message}"
            }

            withContext(Dispatchers.Main) {
                if (isSuccess) {
                    serverReachable.value = true
                    _connectionStatus.value = "Connected"
                    fetchPendingEventsFromServerSuspended()
                } else {
                    serverReachable.value = false
                    _connectionStatus.value = errorMsg ?: "Connection Failed"
                }
            }
        }
    }

    fun updateServerUrl(url: String) {
        val cleanUrl = url.trim().removeSuffix("/")
        updateSettings(cleanUrl, hostUsername.value)
    }

    suspend fun fetchInvitesFromServer(): List<Invite> {
        return withContext(Dispatchers.IO) {
            val currentServers = homeServers.value.ifEmpty { repository.getAllHomeServersSync() }
            val serversToQuery = currentServers.filter { it.isEnabled }.ifEmpty {
                if (serverUrl.value.isNotEmpty()) listOf(HomeServer(name = "Home Server", url = serverUrl.value, apiKey = apiKey.value)) else emptyList()
            }
            val allInvites = mutableListOf<Invite>()
            val seenCodes = mutableSetOf<String>()

            for (s in serversToQuery) {
                val sUrl = s.url.trimEnd('/')
                val sPin = s.apiKey.ifEmpty { apiKey.value }
                if (sUrl.isEmpty() || sPin.isEmpty()) continue
                try {
                    val request = Request.Builder()
                        .url("$sUrl/api/host/invites")
                        .header("Authorization", "Bearer $sPin")
                        .get()
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        val responseStr = response.body?.string()
                        if (response.isSuccessful && responseStr != null) {
                            val jsonArray = org.json.JSONArray(responseStr)
                            for (i in 0 until jsonArray.length()) {
                                val obj = jsonArray.getJSONObject(i)
                                val code = obj.optString("code")
                                if (code.isNotEmpty() && !seenCodes.contains(code)) {
                                    seenCodes.add(code)
                                    allInvites.add(
                                        Invite(
                                            code = code,
                                            name = obj.optString("name", "Guest"),
                                            isUsed = obj.optInt("used", 0) == 1,
                                            createdTimestamp = obj.optLong("created_at", System.currentTimeMillis()),
                                            url = "${sUrl}/invite/$code"
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is java.io.IOException) {
                        Log.w("GatewayNetworkManager", "Home Server not reachable for invites at $sUrl: ${e.message}")
                    } else {
                        Log.e("GatewayNetworkManager", "fetchInvites error on $sUrl", e)
                    }
                }
            }
            allInvites
        }
    }

    suspend fun createInviteOnServer(name: String, code: String, targetServer: HomeServer? = null) {
        withContext(Dispatchers.IO) {
            val serverUrlVal = targetServer?.url?.trimEnd('/') ?: serverUrl.value.trimEnd('/')
            val pinVal = targetServer?.apiKey?.ifEmpty { apiKey.value } ?: apiKey.value
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
                    Log.d("GatewayNetworkManager", "createInvite on $serverUrlVal response: ${response.code}")
                }
            } catch (e: Exception) {
                if (e is java.io.IOException) {
                    Log.w("GatewayNetworkManager", "Home Server not reachable to create invite at $serverUrlVal: ${e.message}")
                } else {
                    Log.e("GatewayNetworkManager", "createInvite error at $serverUrlVal", e)
                }
            }
        }
    }

    suspend fun deleteInviteOnServer(code: String, targetUrl: String? = null) {
        withContext(Dispatchers.IO) {
            val currentServers = homeServers.value.ifEmpty { repository.getAllHomeServersSync() }
            val serversToTry = if (!targetUrl.isNullOrBlank()) {
                val clean = targetUrl.trimEnd('/')
                currentServers.filter { clean.startsWith(it.url.trimEnd('/')) || it.url.trimEnd('/') == clean }.ifEmpty { currentServers }
            } else {
                currentServers.ifEmpty {
                    if (serverUrl.value.isNotEmpty()) listOf(HomeServer(name = "Home Server", url = serverUrl.value, apiKey = apiKey.value)) else emptyList()
                }
            }

            for (s in serversToTry) {
                val sUrl = s.url.trimEnd('/')
                val sPin = s.apiKey.ifEmpty { apiKey.value }
                if (sUrl.isEmpty() || sPin.isEmpty()) continue
                try {
                    val body = JSONObject().apply {
                        put("code", code)
                    }
                    val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), body.toString())
                    val request = Request.Builder()
                        .url("$sUrl/api/host/delete_invite")
                        .header("Authorization", "Bearer $sPin")
                        .post(requestBody)
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        Log.d("GatewayNetworkManager", "deleteInvite on $sUrl response: ${response.code}")
                    }
                } catch (e: Exception) {
                    if (e is java.io.IOException) {
                        Log.w("GatewayNetworkManager", "Home Server not reachable to delete invite at $sUrl: ${e.message}")
                    } else {
                        Log.e("GatewayNetworkManager", "deleteInvite error on $sUrl", e)
                    }
                }
            }
        }
    }

    suspend fun deleteClientOnServer(uuid: String) {
        withContext(Dispatchers.IO) {
            val peer = repository.getRequestByUuid(uuid)
            val currentServers = homeServers.value.ifEmpty { repository.getAllHomeServersSync() }
            val peerServerAddress = peer?.serverAddress?.trimEnd('/') ?: ""
            val matchedServer = currentServers.find { it.url.trimEnd('/') == peerServerAddress }
            val targetServers = if (matchedServer != null) {
                listOf(matchedServer)
            } else {
                currentServers.filter { it.isEnabled }.ifEmpty { currentServers }
            }

            for (server in targetServers) {
                val serverUrlVal = server.url.trimEnd('/')
                val pinVal = server.apiKey
                if (serverUrlVal.isEmpty() || pinVal.isEmpty()) continue
                try {
                    val body = JSONObject().apply {
                        put("uuid", uuid)
                        put("clientUuid", uuid)
                    }
                    val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), body.toString())
                    val request = Request.Builder()
                        .url("$serverUrlVal/api/host/delete_client")
                        .header("Authorization", "Bearer $pinVal")
                        .post(requestBody)
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        Log.d("GatewayNetworkManager", "deleteClientOnServer (${server.name}) response: ${response.code}")
                    }
                } catch (e: Exception) {
                    if (e is java.io.IOException) {
                        Log.w("GatewayNetworkManager", "Home Server ${server.name} not reachable to delete client: ${e.message}")
                    } else {
                        Log.e("GatewayNetworkManager", "deleteClientOnServer error for ${server.name}", e)
                    }
                }
            }
        }
    }

    companion object {
        const val NOTIFICATION_GROUP_MESSAGES = "com.whisker.MESSAGES_GROUP"
        const val NOTIFICATION_SUMMARY_ID = 9000

        private val processedEventIds = Collections.synchronizedSet(LinkedHashSet<String>())
        private val processedMessageIds = Collections.synchronizedSet(LinkedHashSet<String>())

        fun getInstance(context: Context): GatewayNetworkManager {
            return CompanionRegistry.getOrInitializeManager(context)
        }
    }

    fun updateServerDirectCalls(server: HomeServer, allowed: Boolean) {
        scope.launch(Dispatchers.IO) {
            val latest = repository.getHomeServerById(server.id) ?: server
            val updated = latest.copy(allowDirectCalls = allowed)
            repository.updateHomeServer(updated)
            val cleanUrl = server.url.trim().removeSuffix("/")
            val pinVal = server.apiKey
            if (cleanUrl.isNotEmpty() && cleanUrl.startsWith("http")) {
                try {
                    val payload = JSONObject().apply {
                        put("allow_direct_calls", allowed)
                    }
                    val body = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), payload.toString())
                    val request = Request.Builder()
                        .url("$cleanUrl/api/host/direct_calls")
                        .header("Authorization", "Bearer $pinVal")
                        .post(body)
                        .build()
                    okHttpClient.newCall(request).execute().close()
                    Log.d("GatewayNetworkManager", "Updated allow_direct_calls on ${server.name} to $allowed")
                } catch (e: Exception) {
                    Log.e("GatewayNetworkManager", "Error updating direct calls setting on ${server.name}", e)
                }
            }
        }
    }

    fun updateDirectCallsAllowed(allowed: Boolean) {
        allowDirectCalls.value = allowed
        val prefs = context.getSharedPreferences("whisker_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("allow_direct_calls", allowed).apply()
        val cleanUrl = getCleanServerUrl()
        val pinVal = apiKey.value
        if (cleanUrl.isNotEmpty() && cleanUrl.startsWith("http")) {
            scope.launch(Dispatchers.IO) {
                try {
                    val payload = JSONObject().apply {
                        put("allow_direct_calls", allowed)
                    }
                    val body = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), payload.toString())
                    val request = Request.Builder()
                        .url("$cleanUrl/api/host/direct_calls")
                        .header("Authorization", "Bearer $pinVal")
                        .post(body)
                        .build()
                    okHttpClient.newCall(request).execute().close()
                } catch (e: Exception) {
                    Log.e("GatewayNetworkManager", "Error updating direct calls setting on Home Server", e)
                }
            }
        }
    }

    fun setIncomingCallState(
        clientUuid: String,
        clientName: String,
        isVideo: Boolean = true,
        sdp: String = "",
        isDirectCall: Boolean = false,
        reason: String = "",
        callId: String = ""
    ) {
        val current = _activeCall.value
        if (current is CallState.Idle) {
            _activeCall.value = CallState.Incoming(clientUuid, clientName, isVideo, sdp, isDirectCall, reason, callId)
        } else if (current is CallState.Incoming && current.clientUuid == clientUuid) {
            val updatedSdp = if (sdp.isNotEmpty()) sdp else current.sdp
            val effectiveCallId = if (callId.isNotEmpty()) callId else current.callId
            _activeCall.value = CallState.Incoming(clientUuid, clientName, isVideo, updatedSdp, isDirectCall, reason, effectiveCallId)
        }
    }

    fun acceptCall(clientUuid: String, clientName: String, isVideo: Boolean = true, sdpOffer: String = "", callId: String = "") {
        val current = _activeCall.value
        if (current is CallState.Active && current.clientUuid == clientUuid) return

        val currentIncoming = current as? CallState.Incoming
        val effectiveSdpOffer = if (sdpOffer.isNotEmpty()) sdpOffer else (currentIncoming?.sdp ?: "")
        val effectiveIsVideo = if (currentIncoming != null) currentIncoming.isVideo else isVideo
        val effectiveCallId = if (callId.isNotEmpty()) callId else (currentIncoming?.callId ?: "")

        cancelCallNotification()
        updateAudioModeForCall(true)
        _activeCall.value = CallState.Active(clientUuid, clientName, effectiveIsVideo, 0, effectiveSdpOffer, effectiveCallId)
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

    fun publishSdpAnswer(clientUuid: String, answerSdp: String, callId: String = "") {
        scope.launch {
            val current = _activeCall.value
            val effectiveCallId = if (callId.isNotEmpty()) callId else {
                when (current) {
                    is CallState.Active -> current.callId
                    is CallState.Incoming -> current.callId
                    is CallState.Outgoing -> current.callId
                    else -> ""
                }
            }
            val payload = JSONObject().apply {
                put("type", "webrtc_call_answer")
                put("event", "call_answered")
                put("clientUuid", clientUuid)
                put("sdp", answerSdp)
                if (effectiveCallId.isNotEmpty()) {
                    put("call_id", effectiveCallId)
                    put("callId", effectiveCallId)
                }
            }
            publishToClient(clientUuid, payload)
        }
    }

    fun publishOutgoingSdpOffer(clientUuid: String, offerSdp: String, callId: String = "") {
        scope.launch {
            val myName = hostUsername.value.ifEmpty { "Mobile App" }
            val current = _activeCall.value
            val effectiveCallId = if (callId.isNotEmpty()) callId else {
                when (current) {
                    is CallState.Outgoing -> current.callId
                    is CallState.Active -> current.callId
                    else -> ""
                }
            }
            val payload = JSONObject().apply {
                put("type", "webrtc_call_initiate")
                put("event", "call")
                put("clientUuid", clientUuid)
                put("name", myName)
                put("video", true)
                put("sdp", offerSdp)
                if (effectiveCallId.isNotEmpty()) {
                    put("call_id", effectiveCallId)
                    put("callId", effectiveCallId)
                }
            }
            Log.d("GatewayNetworkManager", "Publishing outgoing SDP offer to client: $clientUuid, name: $myName, callId: $effectiveCallId, sdpLen: ${offerSdp.length}")
            publishToClient(clientUuid, payload)
        }
    }

    fun publishIceCandidate(clientUuid: String, candidateJson: String, callId: String = "") {
        scope.launch {
            val current = _activeCall.value
            val effectiveCallId = if (callId.isNotEmpty()) callId else {
                when (current) {
                    is CallState.Active -> current.callId
                    is CallState.Outgoing -> current.callId
                    is CallState.Incoming -> current.callId
                    else -> ""
                }
            }
            val payload = JSONObject().apply {
                put("type", "webrtc_ice_candidate")
                put("event", "ice_candidate")
                put("clientUuid", clientUuid)
                put("candidate", candidateJson)
                if (effectiveCallId.isNotEmpty()) {
                    put("call_id", effectiveCallId)
                    put("callId", effectiveCallId)
                }
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
                    if (current.callId.isNotEmpty()) {
                        put("call_id", current.callId)
                        put("callId", current.callId)
                    }
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
                    put("event", "call_declined")
                    put("status", "declined")
                    put("reason", "declined")
                    put("clientUuid", current.clientUuid)
                    if (current.callId.isNotEmpty()) {
                        put("call_id", current.callId)
                        put("callId", current.callId)
                    }
                }
                publishToClient(current.clientUuid, payload)
            } else if (current is CallState.Outgoing) {
                repository.insertCallLog(
                    CallLog(
                        clientUuid = current.clientUuid,
                        clientName = current.clientName,
                        status = "CANCELLED",
                        isIncoming = false
                    )
                )
                // Notify client of hangup / cancel
                val payload = JSONObject().apply {
                    put("type", "webrtc_call_hangup")
                    put("event", "call_ended")
                    put("clientUuid", current.clientUuid)
                    if (current.callId.isNotEmpty()) {
                        put("call_id", current.callId)
                        put("callId", current.callId)
                    }
                }
                publishToClient(current.clientUuid, payload)
            }
        }
    }

    fun startOutgoingCall(clientUuid: String, clientName: String, isVideo: Boolean = true, callId: String = "") {
        cancelCallNotification()
        updateAudioModeForCall(true)
        val effectiveCallId = if (callId.isNotEmpty()) callId else "call_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        _activeCall.value = CallState.Outgoing(clientUuid, clientName, isVideo, effectiveCallId)
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

    private var incomingCallWakeLock: android.os.PowerManager.WakeLock? = null

    fun cancelCallNotification() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(1002)
        } catch (e: Exception) {
            Log.e("GatewayNetworkManager", "Error cancelling call notification", e)
        }
        try {
            incomingCallWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            incomingCallWakeLock = null
        } catch (e: Exception) {
            Log.e("GatewayNetworkManager", "Error releasing incoming call wake lock", e)
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

    fun showIncomingCallNotification(
        clientUuid: String,
        clientName: String,
        isVideo: Boolean = true,
        autoAccept: Boolean = false
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "incoming_calls_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(
                if (autoAccept) RingtoneManager.TYPE_NOTIFICATION else RingtoneManager.TYPE_RINGTONE
            )
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
                vibrationPattern = if (autoAccept) longArrayOf(0, 300, 200, 300) else longArrayOf(0, 1000, 500, 1000)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Full-screen intent launching MainActivity
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (autoAccept) {
                putExtra("action_answer_call", true)
                putExtra("auto_accepted", true)
            } else {
                putExtra("incoming_call", true)
            }
            putExtra("client_uuid", clientUuid)
            putExtra("client_name", clientName)
            putExtra("is_video", isVideo)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            if (autoAccept) 1005 else 1002,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(
            if (autoAccept) RingtoneManager.TYPE_NOTIFICATION else RingtoneManager.TYPE_RINGTONE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.whisker.R.drawable.ic_launcher_foreground)
            .setContentTitle(if (autoAccept) "📹 IP Camera Auto-Answering" else "Incoming ${if (isVideo) "Video" else "Audio"} Call")
            .setContentText(if (autoAccept) "Connecting stream with $clientName..." else "$clientName is calling...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setOngoing(true)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (!autoAccept) {
            // Answer Action Intent
            val answerIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("action_answer_call", true)
                putExtra("client_uuid", clientUuid)
                putExtra("client_name", clientName)
                putExtra("is_video", isVideo)
            }
            val answerPendingIntent = PendingIntent.getActivity(
                context,
                1003,
                answerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Decline Action Intent
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

            builder.addAction(com.whisker.R.drawable.ic_launcher_foreground, "Answer", answerPendingIntent)
            builder.addAction(com.whisker.R.drawable.ic_launcher_foreground, "Decline", declinePendingIntent)
        } else {
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
            builder.addAction(com.whisker.R.drawable.ic_launcher_foreground, "End Call", declinePendingIntent)
        }

        notificationManager.notify(1002, builder.build())

        // Wake screen up immediately for incoming call
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Whisker:IncomingCallWakeLock"
            )
            wakeLock.acquire(25000)
            incomingCallWakeLock?.let { if (it.isHeld) it.release() }
            incomingCallWakeLock = wakeLock
        } catch (e: Exception) {
            Log.e("GatewayNetworkManager", "Error acquiring full screen wake lock for incoming call", e)
        }

        // Launch MainActivity directly so the incoming call screen displays immediately even when the app was closed
        try {
            context.startActivity(fullScreenIntent)
        } catch (e: Exception) {
            try {
                fullScreenPendingIntent.send()
            } catch (e2: Exception) {
                Log.d("GatewayNetworkManager", "PendingIntent dispatched via notification")
            }
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

    private val recentNotificationLines = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    fun clearNotificationGroupSummary() {
        recentNotificationLines.clear()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_SUMMARY_ID)
    }

    fun cancelAllMessageNotifications() {
        recentNotificationLines.clear()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_SUMMARY_ID)
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
        
        val uniqueNotificationId = if (isCall) notificationId else ((System.currentTimeMillis() % 1000000).toInt() + 100)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.whisker.R.drawable.ic_launcher_foreground)
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
            builder.setGroup(NOTIFICATION_GROUP_MESSAGES)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(uniqueNotificationId, builder.build())

        if (!isCall) {
            // Manage combined incremental notification group
            synchronized(recentNotificationLines) {
                recentNotificationLines.add(Pair(title, body))
                if (recentNotificationLines.size > 20) {
                    recentNotificationLines.removeAt(0)
                }
            }

            val dismissIntent = Intent(context, CallActionReceiver::class.java).apply {
                action = "ACTION_DISMISS_NOTIFICATIONS_GROUP"
            }
            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                9001,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val count = recentNotificationLines.size
            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle("Whisker ($count new)")
                .setSummaryText("Whisker")

            synchronized(recentNotificationLines) {
                for (item in recentNotificationLines.takeLast(7)) {
                    inboxStyle.addLine("${item.first}: ${item.second}")
                }
            }

            val summaryBuilder = NotificationCompat.Builder(context, "messages_channel")
                .setSmallIcon(com.whisker.R.drawable.ic_launcher_foreground)
                .setContentTitle("Whisker")
                .setContentText("$count new notification${if (count > 1) "s" else ""}")
                .setStyle(inboxStyle)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setDeleteIntent(dismissPendingIntent)
                .setGroup(NOTIFICATION_GROUP_MESSAGES)
                .setGroupSummary(true)
                .setAutoCancel(true)

            notificationManager.notify(NOTIFICATION_SUMMARY_ID, summaryBuilder.build())
        }

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
