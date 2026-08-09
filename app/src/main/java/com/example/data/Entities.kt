package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "client_requests")
data class ClientRequest(
    @PrimaryKey val uuid: String,
    val name: String,
    val message: String,
    val status: String, // "PENDING", "APPROVED", "REJECTED"
    val token: String,
    val timestamp: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val isOnline: Boolean = false
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val serverId: Long = 0,
    val clientUuid: String,
    val clientName: String,
    val messageText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val filePath: String? = null,
    val isIncoming: Boolean = true,
    val isRead: Boolean = false,
    val previousMessageId: Long? = null
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
