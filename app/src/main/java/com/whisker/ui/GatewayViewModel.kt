package com.whisker.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.whisker.data.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class GatewayViewModel(
    private val repository: LinkRepository,
    val networkManager: GatewayNetworkManager
) : ViewModel() {

    private val _serverInvites = MutableStateFlow<List<Invite>>(emptyList())
    val allInvites = _serverInvites.asStateFlow()

    init {
        fetchInvites()
        viewModelScope.launch {
            networkManager.onInviteClaimed.collect {
                fetchInvites()
            }
        }
    }

    // Local DB reactive flows
    val allRequests = repository.allRequests.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val pendingRequests = repository.pendingRequests.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allMessages = repository.allMessages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allCallLogs = repository.allCallLogs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun fetchInvites() {
        viewModelScope.launch {
            _serverInvites.value = networkManager.fetchInvitesFromServer()
        }
    }

    // Current state indicators
    val activeCallState = networkManager.activeCall
    val connectionStatus = networkManager.connectionStatus
    val serverUrl = networkManager.serverUrl
    val apiKey = networkManager.apiKey
    val pushToken = networkManager.pushToken
    val serverReachable = networkManager.serverReachable

    val selectedTab = MutableStateFlow(1)
    val selectedChatUuid = MutableStateFlow<String?>(null)

    fun setSelectedTab(tab: Int) {
        selectedTab.value = tab
        updateActiveChatClientId()
    }

    fun setSelectedChatUuid(uuid: String?) {
        selectedChatUuid.value = uuid
        updateActiveChatClientId()
    }

    private fun updateActiveChatClientId() {
        networkManager.activeChatClientId.value = if (selectedTab.value == 1) {
            selectedChatUuid.value
        } else {
            null
        }
    }

    fun setActiveChatClientId(clientUuid: String?) {
        setSelectedChatUuid(clientUuid)
    }

    // Call controls
    fun acceptCall(clientUuid: String, clientName: String) {
        networkManager.acceptCall(clientUuid, clientName)
    }

    fun declineOrEndCall() {
        networkManager.declineOrEndCall()
    }

    fun redialClient(clientUuid: String, clientName: String) {
        networkManager.startOutgoingCall(clientUuid, clientName)
    }

    fun onTimeTick() {
        networkManager.incrementCallDuration()
    }

    // Client Request Approval Flows
    fun approveClient(request: ClientRequest) {
        viewModelScope.launch {
            if (request.status == "MOBILE_PENDING") {
                val updated = request.copy(status = "MOBILE_APPROVED", lastSeen = System.currentTimeMillis())
                repository.updateRequest(updated)
                val payload = org.json.JSONObject().apply {
                    put("type", "mobile_pairing_response")
                    put("event", "approved")
                    put("uuid", request.uuid)
                }
                networkManager.publishToClient(request.uuid, payload)
                return@launch
            }

            val approvedToken = "auth_tok_" + java.util.UUID.randomUUID().toString().substring(0, 12)
            val updated = request.copy(status = "APPROVED", token = approvedToken, lastSeen = System.currentTimeMillis())
            repository.updateRequest(updated)

            val payload = org.json.JSONObject().apply {
                put("type", "registration_response")
                put("event", "approved")
                put("status", "APPROVED")
                put("token", approvedToken)
                put("clientUuid", request.uuid)
                put("hostName", networkManager.hostUsername.value)
                put("host_name", networkManager.hostUsername.value)
            }
            networkManager.publishToClient(request.uuid, payload)
        }
    }

    fun rejectClient(request: ClientRequest) {
        viewModelScope.launch {
            if (request.status == "MOBILE_PENDING") {
                val updated = request.copy(status = "MOBILE_REJECTED")
                repository.updateRequest(updated)
                val payload = org.json.JSONObject().apply {
                    put("type", "mobile_pairing_response")
                    put("event", "rejected")
                    put("uuid", request.uuid)
                }
                networkManager.publishToClient(request.uuid, payload)
                return@launch
            }

            val updated = request.copy(status = "REJECTED")
            repository.updateRequest(updated)
        }
    }

    fun deleteClient(request: ClientRequest) {
        viewModelScope.launch {
            repository.deleteRequest(request)
        }
    }

    fun renameClient(request: ClientRequest, newName: String) {
        viewModelScope.launch {
            val updated = request.copy(name = newName)
            repository.updateRequest(updated)
        }
    }

    fun toggleAutoAccept(request: ClientRequest, autoAccept: Boolean) {
        viewModelScope.launch {
            val updated = request.copy(autoAcceptCall = autoAccept)
            repository.updateRequest(updated)
        }
    }

    // Message controls
    fun sendMessage(clientUuid: String, clientName: String, text: String, filePath: String? = null) {
        networkManager.sendLocalMessage(clientUuid, clientName, text, filePath)
    }

    fun markMessagesRead(clientUuid: String) {
        viewModelScope.launch {
            repository.markAsRead(clientUuid)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllData()
        }
    }

    fun createInvite(name: String): Invite {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        var code = ""
        for (i in 0 until 5) {
            code += chars[java.util.Random().nextInt(chars.length)]
        }
        val timestamp = System.currentTimeMillis()
        val inviteUrl = "${networkManager.serverUrl.value.removeSuffix("/")}/invite/$code"
        val invite = Invite(
            code = code,
            name = name,
            url = inviteUrl,
            isUsed = false,
            createdTimestamp = timestamp
        )
        viewModelScope.launch {
            networkManager.createInviteOnServer(name, code)
            fetchInvites()
        }
        return invite
    }

    fun deleteInvite(invite: Invite) {
        viewModelScope.launch {
            networkManager.deleteInviteOnServer(invite.code)
            fetchInvites()
        }
    }

    fun registerFcmToken() {
        networkManager.registerFcmToken(forceRefresh = true)
    }

    fun refreshConnectionAndEvents() {
        viewModelScope.launch {
            networkManager.fetchPendingEventsFromServerSuspended()
        }
    }

    fun saveFcmConfig(senderId: String, projectId: String, appId: String, apiKey: String) {
        networkManager.saveFcmConfig(senderId, projectId, appId, apiKey)
    }

    class Factory(
        private val repository: LinkRepository,
        private val networkManager: GatewayNetworkManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GatewayViewModel::class.java)) {
                return GatewayViewModel(repository, networkManager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
