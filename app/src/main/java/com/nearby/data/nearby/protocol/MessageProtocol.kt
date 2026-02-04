package com.nearby.data.nearby.protocol

import org.json.JSONObject
import java.nio.ByteBuffer

object MessageProtocol {
    const val TYPE_HANDSHAKE_INIT = 1
    const val TYPE_HANDSHAKE_RESPONSE = 2
    const val TYPE_MESSAGE = 3
    const val TYPE_DELIVERY_RECEIPT = 4
    const val TYPE_READ_RECEIPT = 5
    const val TYPE_TYPING_INDICATOR = 6
    const val TYPE_DISCONNECT = 7

    fun createHandshakeInit(
        peerId: String,
        displayName: String,
        publicKey: ByteArray
    ): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_HANDSHAKE_INIT)
            put("peerId", peerId)
            put("displayName", displayName)
            put("publicKey", android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP))
            put("timestamp", System.currentTimeMillis())
            put("version", 1)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun createHandshakeResponse(
        peerId: String,
        displayName: String,
        publicKey: ByteArray,
        accepted: Boolean
    ): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_HANDSHAKE_RESPONSE)
            put("peerId", peerId)
            put("displayName", displayName)
            put("publicKey", android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP))
            put("accepted", accepted)
            put("timestamp", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun createMessage(
        messageId: String,
        senderId: String,
        content: String,
        timestamp: Long
    ): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_MESSAGE)
            put("id", messageId)
            put("senderId", senderId)
            put("content", content)
            put("timestamp", timestamp)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun createEncryptedMessage(
        messageId: String,
        senderId: String,
        encryptedContent: ByteArray,
        timestamp: Long
    ): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_MESSAGE)
            put("id", messageId)
            put("senderId", senderId)
            put("encryptedContent", android.util.Base64.encodeToString(encryptedContent, android.util.Base64.NO_WRAP))
            put("encrypted", true)
            put("timestamp", timestamp)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun createDeliveryReceipt(messageId: String): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_DELIVERY_RECEIPT)
            put("messageId", messageId)
            put("timestamp", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun createReadReceipt(messageIds: List<String>): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_READ_RECEIPT)
            put("messageIds", org.json.JSONArray(messageIds))
            put("timestamp", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun createDisconnect(peerId: String): ByteArray {
        val json = JSONObject().apply {
            put("type", TYPE_DISCONNECT)
            put("peerId", peerId)
            put("timestamp", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun parseMessage(bytes: ByteArray): ParsedMessage? {
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val type = json.getInt("type")

            when (type) {
                TYPE_HANDSHAKE_INIT -> ParsedMessage.HandshakeInit(
                    peerId = json.getString("peerId"),
                    displayName = json.getString("displayName"),
                    publicKey = android.util.Base64.decode(json.getString("publicKey"), android.util.Base64.NO_WRAP),
                    timestamp = json.getLong("timestamp"),
                    version = json.optInt("version", 1)
                )
                TYPE_HANDSHAKE_RESPONSE -> ParsedMessage.HandshakeResponse(
                    peerId = json.getString("peerId"),
                    displayName = json.getString("displayName"),
                    publicKey = android.util.Base64.decode(json.getString("publicKey"), android.util.Base64.NO_WRAP),
                    accepted = json.getBoolean("accepted"),
                    timestamp = json.getLong("timestamp")
                )
                TYPE_MESSAGE -> {
                    val isEncrypted = json.optBoolean("encrypted", false)
                    if (isEncrypted) {
                        ParsedMessage.EncryptedMessage(
                            id = json.getString("id"),
                            senderId = json.getString("senderId"),
                            encryptedContent = android.util.Base64.decode(json.getString("encryptedContent"), android.util.Base64.NO_WRAP),
                            timestamp = json.getLong("timestamp")
                        )
                    } else {
                        ParsedMessage.PlainMessage(
                            id = json.getString("id"),
                            senderId = json.getString("senderId"),
                            content = json.getString("content"),
                            timestamp = json.getLong("timestamp")
                        )
                    }
                }
                TYPE_DELIVERY_RECEIPT -> ParsedMessage.DeliveryReceipt(
                    messageId = json.getString("messageId"),
                    timestamp = json.getLong("timestamp")
                )
                TYPE_READ_RECEIPT -> {
                    val messageIds = mutableListOf<String>()
                    val array = json.getJSONArray("messageIds")
                    for (i in 0 until array.length()) {
                        messageIds.add(array.getString(i))
                    }
                    ParsedMessage.ReadReceipt(
                        messageIds = messageIds,
                        timestamp = json.getLong("timestamp")
                    )
                }
                TYPE_DISCONNECT -> ParsedMessage.Disconnect(
                    peerId = json.getString("peerId"),
                    timestamp = json.getLong("timestamp")
                )
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

sealed class ParsedMessage {
    data class HandshakeInit(
        val peerId: String,
        val displayName: String,
        val publicKey: ByteArray,
        val timestamp: Long,
        val version: Int
    ) : ParsedMessage()

    data class HandshakeResponse(
        val peerId: String,
        val displayName: String,
        val publicKey: ByteArray,
        val accepted: Boolean,
        val timestamp: Long
    ) : ParsedMessage()

    data class PlainMessage(
        val id: String,
        val senderId: String,
        val content: String,
        val timestamp: Long
    ) : ParsedMessage()

    data class EncryptedMessage(
        val id: String,
        val senderId: String,
        val encryptedContent: ByteArray,
        val timestamp: Long
    ) : ParsedMessage()

    data class DeliveryReceipt(
        val messageId: String,
        val timestamp: Long
    ) : ParsedMessage()

    data class ReadReceipt(
        val messageIds: List<String>,
        val timestamp: Long
    ) : ParsedMessage()

    data class Disconnect(
        val peerId: String,
        val timestamp: Long
    ) : ParsedMessage()
}
