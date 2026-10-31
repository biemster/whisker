package com.whisker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ClientRequestDao {
    @Query("SELECT * FROM client_requests ORDER BY timestamp DESC")
    fun getAllRequests(): Flow<List<ClientRequest>>

    @Query("SELECT * FROM client_requests ORDER BY timestamp DESC")
    suspend fun getAllRequestsSync(): List<ClientRequest>

    @Query("SELECT * FROM client_requests WHERE status IN ('PENDING', 'MOBILE_PENDING') ORDER BY timestamp DESC")
    fun getPendingRequests(): Flow<List<ClientRequest>>

    @Query("SELECT * FROM client_requests WHERE uuid = :uuid")
    suspend fun getRequestByUuid(uuid: String): ClientRequest?

    @Query("SELECT * FROM client_requests WHERE token = :token AND status = 'APPROVED'")
    suspend fun getApprovedRequestByToken(token: String): ClientRequest?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequest(request: ClientRequest)

    @Update
    suspend fun updateRequest(request: ClientRequest)

    @Delete
    suspend fun deleteRequest(request: ClientRequest)

    @Query("DELETE FROM client_requests")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE clientUuid = :clientUuid ORDER BY timestamp ASC")
    fun getMessagesForClient(clientUuid: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE clientUuid = :clientUuid ORDER BY timestamp ASC")
    suspend fun getMessagesForClientSync(clientUuid: String): List<Message>

    @Query("SELECT * FROM messages WHERE serverId = :serverId LIMIT 1")
    suspend fun getMessageByServerId(serverId: String): Message?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Update
    suspend fun updateMessage(message: Message)

    @Query("UPDATE messages SET isRead = 1 WHERE clientUuid = :clientUuid")
    suspend fun markAsRead(clientUuid: String)

    @Delete
    suspend fun deleteMessage(message: Message)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface CallLogDao {
    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC")
    fun getAllCallLogs(): Flow<List<CallLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLog(callLog: CallLog)

    @Delete
    suspend fun deleteCallLog(callLog: CallLog)

    @Query("DELETE FROM call_logs")
    suspend fun deleteAll()
}

@Dao
interface InviteDao {
    @Query("SELECT * FROM invites ORDER BY createdTimestamp DESC")
    fun getAllInvites(): Flow<List<Invite>>

    @Query("SELECT * FROM invites WHERE code = :code")
    suspend fun getInviteByCode(code: String): Invite?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvite(invite: Invite)

    @Query("UPDATE invites SET isUsed = 1 WHERE code = :code")
    suspend fun markInviteUsed(code: String)

    @Delete
    suspend fun deleteInvite(invite: Invite)
}

@Dao
interface HomeServerDao {
    @Query("SELECT * FROM home_servers ORDER BY isPrimary DESC, name ASC")
    fun getAllServers(): Flow<List<HomeServer>>

    @Query("SELECT * FROM home_servers ORDER BY isPrimary DESC, name ASC")
    suspend fun getAllServersSync(): List<HomeServer>

    @Query("SELECT * FROM home_servers WHERE id = :id LIMIT 1")
    suspend fun getServerById(id: String): HomeServer?

    @Query("SELECT * FROM home_servers WHERE isPrimary = 1 LIMIT 1")
    suspend fun getPrimaryServer(): HomeServer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: HomeServer)

    @Update
    suspend fun updateServer(server: HomeServer)

    @Delete
    suspend fun deleteServer(server: HomeServer)

    @Query("DELETE FROM home_servers WHERE id = :id")
    suspend fun deleteServerById(id: String)
}
