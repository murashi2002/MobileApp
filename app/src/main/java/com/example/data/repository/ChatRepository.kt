package com.example.data.repository

import android.util.Log
import com.example.BuildConfig
import com.example.data.local.ChatDao
import com.example.data.local.ChatMessage
import com.example.data.local.ChatSession
import com.example.data.remote.ApiClient
import com.example.data.remote.Content
import com.example.data.remote.GenerateContentRequest
import com.example.data.remote.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ChatRepository(private val chatDao: ChatDao) {

    val allSessions: Flow<List<ChatSession>> = chatDao.getAllSessions()

    fun getMessagesFlow(sessionId: Long): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForSessionFlow(sessionId)
    }

    suspend fun getSessionById(sessionId: Long): ChatSession? {
        return chatDao.getSessionById(sessionId)
    }

    suspend fun createSession(title: String, systemPrompt: String, personalityName: String): Long {
        return chatDao.insertSession(
            ChatSession(
                title = title,
                systemPrompt = systemPrompt,
                personalityName = personalityName
            )
        )
    }

    suspend fun updateSession(session: ChatSession) {
        chatDao.updateSession(session)
    }

    suspend fun deleteSession(session: ChatSession) {
        chatDao.clearMessagesForSession(session.id)
        chatDao.deleteSession(session)
    }

    suspend fun clearSessionHistory(sessionId: Long) {
        chatDao.clearMessagesForSession(sessionId)
    }

    suspend fun saveMessage(message: ChatMessage): Long {
        return chatDao.insertMessage(message)
    }

    suspend fun updateMessage(message: ChatMessage) {
        chatDao.updateMessage(message)
    }

    suspend fun deleteMessage(id: Long) {
        chatDao.deleteMessageById(id)
    }

    fun searchMessages(query: String): Flow<List<ChatMessage>> {
        return chatDao.searchMessages(query)
    }

    // --- Gemini Api Call Integration ---
    suspend fun sendMessageToGemini(sessionId: Long, userMessageText: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val session = chatDao.getSessionById(sessionId) ?: return@withContext Result.failure(Exception("Session not found"))
            
            // 1. Save user message locally
            val userMsg = ChatMessage(
                sessionId = sessionId,
                role = "user",
                text = userMessageText
            )
            chatDao.insertMessage(userMsg)

            // Update session title dynamically if it's currently generic or empty
            if (session.title == "New Chat" || session.title.isEmpty() || session.title.trim() == "") {
                val words = userMessageText.split("\\s+".toRegex())
                val newTitle = if (words.size > 5) words.take(5).joinToString(" ") + "..." else userMessageText
                chatDao.updateSession(session.copy(title = newTitle))
            }

            // 2. Insert temporary loading model response placeholder
            val botMsgPlaceholder = ChatMessage(
                sessionId = sessionId,
                role = "model",
                text = "" // Empty text indicates loading indicator in UI
            )
            val botMsgId = chatDao.insertMessage(botMsgPlaceholder)

            // 3. Assemble full thread history for conversational context
            val dbMessages = chatDao.getMessagesForSession(sessionId)
            // Alternating user/model role list for Gemini compatibility, filtering out placeholders and failures
            val apiContents = dbMessages
                .filter { it.id != botMsgId && !it.isFailed && it.text.isNotEmpty() }
                .map { msg ->
                    Content(
                        role = if (msg.role == "user") "user" else "model",
                        parts = listOf(Part(text = msg.text))
                    )
                }

            // 4. Incorporate system instructions if set
            val sysInstruction = if (session.systemPrompt.trim().isNotEmpty()) {
                Content(parts = listOf(Part(text = session.systemPrompt)))
            } else null

            val request = GenerateContentRequest(
                contents = apiContents,
                systemInstruction = sysInstruction
            )

            // 5. Query API
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                val errorText = "API Key not configured. Please open AI Studio's Secrets panel, add your GEMINI_API_KEY, and rebuild/restart."
                chatDao.updateMessage(
                    ChatMessage(
                        id = botMsgId,
                        sessionId = sessionId,
                        role = "model",
                        text = errorText,
                        isFailed = true
                    )
                )
                return@withContext Result.failure(Exception("API Key not set"))
            }

            val response = ApiClient.geminiService.generateContent(apiKey = apiKey, request = request)
            val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

            if (responseText != null) {
                // Populate the response
                val updatedBotMsg = ChatMessage(
                    id = botMsgId,
                    sessionId = sessionId,
                    role = "model",
                    text = responseText,
                    isFailed = false
                )
                chatDao.updateMessage(updatedBotMsg)
                Result.success(responseText)
            } else {
                val blockReason = response.promptFeedback?.blockReason ?: "Unknown blocker (potential safety filter)"
                val errorMsg = "Response blocked or failed. Reason: $blockReason"
                chatDao.updateMessage(
                    ChatMessage(
                        id = botMsgId,
                        sessionId = sessionId,
                        role = "model",
                        text = errorMsg,
                        isFailed = true
                    )
                )
                Result.failure(Exception(errorMsg))
            }

        } catch (e: Exception) {
            Log.e("ChatRepository", "Exception in sendMessageToGemini", e)
            Result.failure(e)
        }
    }

    suspend fun retryLastFailedMessage(sessionId: Long): Result<String> = withContext(Dispatchers.IO) {
        val messages = chatDao.getMessagesForSession(sessionId)
        if (messages.isEmpty()) return@withContext Result.failure(Exception("No messages to retry"))

        val lastMsg = messages.last()
        
        if (lastMsg.role == "model" && lastMsg.isFailed) {
            chatDao.deleteMessageById(lastMsg.id)
            val updatedMessages = chatDao.getMessagesForSession(sessionId)
            val lastUserMsg = updatedMessages.lastOrNull { it.role == "user" }
            if (lastUserMsg != null) {
                val textToResend = lastUserMsg.text
                chatDao.deleteMessageById(lastUserMsg.id)
                sendMessageToGemini(sessionId, textToResend)
            } else {
                Result.failure(Exception("No user message found to retry"))
            }
        } else if (lastMsg.role == "user") {
            sendMessageToGemini(sessionId, lastMsg.text)
        } else {
            Result.failure(Exception("Last message did not fail"))
        }
    }

    // --- Social & Followers Operations ---
    val allPosts: Flow<List<com.example.data.local.SocialPost>> = chatDao.getAllPosts()
    val allRelations: Flow<List<com.example.data.local.SocialRelation>> = chatDao.getAllRelations()

    suspend fun insertPost(post: com.example.data.local.SocialPost): Long {
        return chatDao.insertPost(post)
    }

    suspend fun updatePost(post: com.example.data.local.SocialPost) {
        chatDao.updatePost(post)
    }

    suspend fun deletePost(post: com.example.data.local.SocialPost) {
        chatDao.deletePost(post)
    }

    suspend fun insertRelation(relation: com.example.data.local.SocialRelation): Long {
        return chatDao.insertRelation(relation)
    }

    suspend fun updateRelation(relation: com.example.data.local.SocialRelation) {
        chatDao.updateRelation(relation)
    }

    suspend fun deleteRelation(relation: com.example.data.local.SocialRelation) {
        chatDao.deleteRelation(relation)
    }

    suspend fun deleteRelationById(id: Long) {
        chatDao.deleteRelationById(id)
    }
}
