package com.example.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

data class ChatMessage(
    val id: String = "",
    val sender: String = "",
    val text: String = "",
    val timestamp: Long = 0L,
    val replyToId: String? = null,
    val replyToSender: String? = null,
    val replyToText: String? = null,
    val reactions: Map<String, String> = emptyMap() // username -> emoji
)

object ChatService {
    private const val TAG = "ChatService"
    private const val BASE_URL = "https://loop-7e3d9-default-rtdb.firebaseio.com"

    val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Checks if the chat is enabled globally by the developer.
     */
    suspend fun isChatEnabled(): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/settings/chat_enabled.json"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()?.trim() ?: "true"
                    if (bodyStr.isEmpty() || bodyStr == "null") {
                        return@withContext true
                    }
                    return@withContext bodyStr.toBoolean()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching chat enabled status", e)
        }
        return@withContext true
    }

    /**
     * Enables or disables the chat globally.
     */
    suspend fun setChatEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/settings/chat_enabled.json"
        try {
            val body = enabled.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).put(body).build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting chat enabled status", e)
        }
        return@withContext false
    }

    /**
     * Deletes a specific message from a channel.
     */
    suspend fun deleteMessage(channelId: String, messageId: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/chats/$channelId/$messageId.json"
        try {
            val request = Request.Builder().url(url).delete().build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting message $messageId", e)
        }
        return@withContext false
    }

    /**
     * Posts a new message to a specific channel.
     */
    suspend fun postMessage(
        channelId: String,
        sender: String,
        text: String,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToText: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/chats/$channelId.json"
        try {
            val json = JSONObject().apply {
                put("sender", sender)
                put("text", text)
                put("timestamp", System.currentTimeMillis())
                if (replyToId != null) {
                    put("replyToId", replyToId)
                    put("replyToSender", replyToSender)
                    put("replyToText", replyToText)
                }
            }
            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error posting message", e)
        }
        return@withContext false
    }

    /**
     * Adds or updates a reaction on a message.
     */
    suspend fun addReaction(channelId: String, messageId: String, username: String, emoji: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/chats/$channelId/$messageId/reactions/$username.json"
        try {
            val body = "\"$emoji\"".toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).put(body).build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding reaction", e)
        }
        return@withContext false
    }

    /**
     * Removes a reaction from a message.
     */
    suspend fun removeReaction(channelId: String, messageId: String, username: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/chats/$channelId/$messageId/reactions/$username.json"
        try {
            val request = Request.Builder().url(url).delete().build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing reaction", e)
        }
        return@withContext false
    }

    /**
     * Registers a user in the global users directory.
     */
    suspend fun registerUser(username: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/users/$username.json"
        try {
            val json = JSONObject().apply {
                put("username", username)
                put("name", name)
                put("timestamp", System.currentTimeMillis())
            }
            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).put(body).build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering user", e)
        }
        return@withContext false
    }

    /**
     * Retrieves all registered users from the global directory.
     */
    suspend fun getAllUsers(): Map<String, String> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/users.json"
        val usersMap = mutableMapOf<String, String>()
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val userObj = json.optJSONObject(key) ?: continue
                            usersMap[key] = userObj.optString("name", key)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting users", e)
        }
        return@withContext usersMap
    }

    /**
     * Retrieves messages for a specific channel, automatically deletes messages older than 10 minutes,
     * and returns the sorted active messages.
     */
    suspend fun getMessages(channelId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/chats/$channelId.json"
        val messages = mutableListOf<ChatMessage>()
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val msgObj = json.optJSONObject(key) ?: continue

                            val reactionsMap = mutableMapOf<String, String>()
                            val reactionsObj = msgObj.optJSONObject("reactions")
                            if (reactionsObj != null) {
                                val rKeys = reactionsObj.keys()
                                while (rKeys.hasNext()) {
                                    val rKey = rKeys.next()
                                    reactionsMap[rKey] = reactionsObj.getString(rKey)
                                }
                            }

                            messages.add(
                                ChatMessage(
                                    id = key,
                                    sender = msgObj.optString("sender", "مجهول"),
                                    text = msgObj.optString("text", ""),
                                    timestamp = msgObj.optLong("timestamp", 0L),
                                    replyToId = msgObj.optString("replyToId").takeIf { it.isNotEmpty() },
                                    replyToSender = msgObj.optString("replyToSender").takeIf { it.isNotEmpty() },
                                    replyToText = msgObj.optString("replyToText").takeIf { it.isNotEmpty() },
                                    reactions = reactionsMap
                                )
                            )
                        }
                    }
                }
            }

            // Perform automatic deletion of messages older than 24 hours (86,400,000 ms) only for public/general chats
            val isPrivateChat = channelId.startsWith("private_")
            if (!isPrivateChat) {
                val twentyFourHoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
                val oldMessages = messages.filter { it.timestamp < twentyFourHoursAgo }
                for (oldMsg in oldMessages) {
                    deleteMessage(channelId, oldMsg.id)
                }
                return@withContext messages.filter { it.timestamp >= twentyFourHoursAgo }.sortedBy { it.timestamp }
            } else {
                return@withContext messages.sortedBy { it.timestamp }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting messages for channel $channelId", e)
        }
        return@withContext emptyList()
    }

    /**
     * Deletes all messages from all channels.
     */
    suspend fun deleteAllMessages(): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/chats.json"
        try {
            val request = Request.Builder().url(url).delete().build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error wiping chat database", e)
        }
        return@withContext false
    }

    /**
     * Retrieves all active chats for notification checks.
     */
    suspend fun getAllChats(): Map<String, Map<String, Any>>? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/chats.json"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val result = mutableMapOf<String, Map<String, Any>>()
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val innerObj = json.optJSONObject(key) ?: continue
                            val innerMap = mutableMapOf<String, Any>()
                            val innerKeys = innerObj.keys()
                            while (innerKeys.hasNext()) {
                                val innerKey = innerKeys.next()
                                val msgObj = innerObj.optJSONObject(innerKey)
                                if (msgObj != null) {
                                    val msgMap = mutableMapOf<String, Any>()
                                    val msgKeys = msgObj.keys()
                                    while (msgKeys.hasNext()) {
                                        val mKey = msgKeys.next()
                                        val mVal = msgObj.opt(mKey)
                                        if (mVal != null) {
                                            msgMap[mKey] = mVal
                                        }
                                    }
                                    innerMap[innerKey] = msgMap
                                }
                            }
                            result[key] = innerMap
                        }
                        return@withContext result
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all chats", e)
        }
        null
    }

    /**
     * Sets the typing state of a user in a specific channel.
     */
    suspend fun setTypingState(channelId: String, username: String, isTyping: Boolean): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/typing/$channelId/$username.json"
        try {
            val body = "$isTyping".toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).put(body).build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting typing state", e)
        }
        return@withContext false
    }

    /**
     * Gets the typing status of users in a specific channel.
     * Returns a map of username -> isTyping
     */
    suspend fun getTypingStates(channelId: String): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/typing/$channelId.json"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val result = mutableMapOf<String, Boolean>()
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            result[key] = json.optBoolean(key, false)
                        }
                        return@withContext result
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting typing states", e)
        }
        return@withContext emptyMap()
    }

    /**
     * Checks if a username is already registered in the global directory.
     */
    suspend fun isUsernameTaken(username: String): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext false
        val url = "$BASE_URL/users/${username.trim().lowercase()}.json"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()?.trim() ?: ""
                    return@withContext bodyStr.isNotEmpty() && bodyStr != "null"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if username is taken", e)
        }
        return@withContext false
    }

    /**
     * Blocks a user.
     */
    suspend fun blockUser(myUsername: String, otherUsername: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/blocks/$myUsername/$otherUsername.json"
        try {
            val body = "true".toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(url).put(body).build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error blocking user", e)
        }
        return@withContext false
    }

    /**
     * Unblocks a user.
     */
    suspend fun unblockUser(myUsername: String, otherUsername: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/blocks/$myUsername/$otherUsername.json"
        try {
            val request = Request.Builder().url(url).delete().build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unblocking user", e)
        }
        return@withContext false
    }

    /**
     * Gets the list of blocked users.
     */
    suspend fun getBlockedUsers(myUsername: String): List<String> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/blocks/$myUsername.json"
        val list = mutableListOf<String>()
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty() && bodyStr != "null") {
                        val json = JSONObject(bodyStr)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (json.optBoolean(key, false)) {
                                list.add(key)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting blocked users", e)
        }
        return@withContext list
    }

    /**
     * Deletes an entire private chat conversation node.
     */
    suspend fun deleteConversation(channelId: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/chats/$channelId.json"
        try {
            val request = Request.Builder().url(url).delete().build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting conversation $channelId", e)
        }
        return@withContext false
    }
}
