package com.whisker.data

import kotlinx.coroutines.flow.Flow

class LinkRepository(private val db: AppDatabase) {
    val clientRequestDao = db.clientRequestDao()
    val messageDao = db.messageDao()
    val callLogDao = db.callLogDao()
    val inviteDao = db.inviteDao()
    val homeServerDao = db.homeServerDao()

    val allRequests: Flow<List<ClientRequest>> = clientRequestDao.getAllRequests()
    val pendingRequests: Flow<List<ClientRequest>> = clientRequestDao.getPendingRequests()
    val allMessages: Flow<List<Message>> = messageDao.getAllMessages()
    val allCallLogs: Flow<List<CallLog>> = callLogDao.getAllCallLogs()
    val allInvites: Flow<List<Invite>> = inviteDao.getAllInvites()
    val allHomeServers: Flow<List<HomeServer>> = homeServerDao.getAllServers()

    suspend fun getAllHomeServersSync(): List<HomeServer> = homeServerDao.getAllServersSync()
    suspend fun getHomeServerById(id: String): HomeServer? = homeServerDao.getServerById(id)
    suspend fun getPrimaryHomeServer(): HomeServer? = homeServerDao.getPrimaryServer()
    suspend fun insertHomeServer(server: HomeServer) = homeServerDao.insertServer(server)
    suspend fun updateHomeServer(server: HomeServer) = homeServerDao.updateServer(server)
    suspend fun deleteHomeServer(server: HomeServer) = homeServerDao.deleteServer(server)
    suspend fun deleteHomeServerById(id: String) = homeServerDao.deleteServerById(id)

    suspend fun getAllRequestsSync(): List<ClientRequest> = clientRequestDao.getAllRequestsSync()
    suspend fun getRequestByUuid(uuid: String): ClientRequest? = clientRequestDao.getRequestByUuid(uuid)

    suspend fun insertRequest(request: ClientRequest) = clientRequestDao.insertRequest(request)

    suspend fun updateRequest(request: ClientRequest) = clientRequestDao.updateRequest(request)

    suspend fun deleteRequest(request: ClientRequest) = clientRequestDao.deleteRequest(request)

    suspend fun insertMessage(message: Message) = messageDao.insertMessage(message)
    suspend fun updateMessage(message: Message) = messageDao.updateMessage(message)

    suspend fun getMessagesForClientSync(clientUuid: String): List<Message> = messageDao.getMessagesForClientSync(clientUuid)

    suspend fun getMessageByServerId(serverId: String): Message? = messageDao.getMessageByServerId(serverId)

    suspend fun markAsRead(clientUuid: String) = messageDao.markAsRead(clientUuid)

    suspend fun deleteMessage(message: Message) = messageDao.deleteMessage(message)

    suspend fun insertCallLog(callLog: CallLog) = callLogDao.insertCallLog(callLog)

    suspend fun deleteCallLog(callLog: CallLog) = callLogDao.deleteCallLog(callLog)

    suspend fun insertInvite(invite: Invite) = inviteDao.insertInvite(invite)

    suspend fun markInviteUsed(code: String) = inviteDao.markInviteUsed(code)

    suspend fun deleteInvite(invite: Invite) = inviteDao.deleteInvite(invite)

    suspend fun clearAllData() {
        clientRequestDao.deleteAll()
        messageDao.deleteAll()
        callLogDao.deleteAll()
    }
}
