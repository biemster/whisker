package com.whisker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "client_requests")
data class ClientRequest(
    @PrimaryKey val uuid: String,
    val name: String,
    val message: String,
    val status: String, // "PENDING", "APPROVED", "REJECTED", "MOBILE_PENDING", "MOBILE_APPROVED"
    val token: String,
    val timestamp: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val isOnline: Boolean = false,
    val autoAcceptCall: Boolean = false,
    val clientType: String = "WEB", // "WEB", "CLI", "MOBILE"
    val serverAddress: String = "local" // "local" or "http://remote-server:3000"
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val serverId: String = "",
    val clientUuid: String,
    val clientName: String,
    val messageText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val filePath: String? = null,
    val isIncoming: Boolean = true,
    val isRead: Boolean = false,
    val previousMessageId: String? = null
)

@Entity(tableName = "call_logs")
data class CallLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val clientUuid: String,
    val clientName: String,
    val status: String, // "MISSED", "CONNECTED", "DECLINED", "OUTGOING"
    val duration: Long = 0, // in seconds
    val isIncoming: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "invites")
data class Invite(
    @PrimaryKey val code: String,
    val name: String,
    val url: String,
    val isUsed: Boolean = false,
    val createdTimestamp: Long = System.currentTimeMillis()
)
