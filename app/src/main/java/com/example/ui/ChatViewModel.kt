package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ChatDatabase
import com.example.data.local.ChatMessage
import com.example.data.local.ChatSession
import com.example.data.repository.ChatRepository
import com.example.ui.models.ChatPersonality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ChatRepository
    
    val allSessions: StateFlow<List<ChatSession>>
    
    private val _selectedSessionId = MutableStateFlow<Long?>(null)
    val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Observe active messages flow for the currently selected chat thread
    val activeMessages: StateFlow<List<ChatMessage>> = _selectedSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                repository.getMessagesFlow(sessionId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Flow for searching text dynamically within database history
    val searchedMessages: StateFlow<List<ChatMessage>> = _searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.trim().length >= 2) {
                repository.searchMessages(query)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Generating status flow
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // -- Social States --
    val allPosts: StateFlow<List<com.example.data.local.SocialPost>>
    val allRelations: StateFlow<List<com.example.data.local.SocialRelation>>

    init {
        val database = ChatDatabase.getDatabase(application)
        repository = ChatRepository(database.chatDao())
        
        allSessions = repository.allSessions
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        allPosts = repository.allPosts
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        allRelations = repository.allRelations
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        // Prepopulate default items to avoid dead or empty layouts
        viewModelScope.launch {
            repository.allPosts.first().let { posts ->
                if (posts.isEmpty()) {
                    repository.insertPost(
                        com.example.data.local.SocialPost(
                            authorName = "Orange Dev",
                            authorEmoji = "🍊",
                            content = "Just launched my first AI workspace! Loving the orange visual accent. 🚀🌅",
                            likesCount = 12,
                            isLikedByMe = false
                        )
                    )
                    repository.insertPost(
                        com.example.data.local.SocialPost(
                            authorName = "Assistant Pro",
                            authorEmoji = "❇️",
                            content = "Hello developers! What artificial intelligence model are you testing today? 🤖 Feel free to ask me anything in the workspace!",
                            likesCount = 28,
                            isLikedByMe = true
                        )
                    )
                    repository.insertPost(
                        com.example.data.local.SocialPost(
                            authorName = "Tech Lead",
                            authorEmoji = "🥑",
                            content = "Kotlin Multiplatform and Compose are absolutely legendary for building cross-platform applications with beautiful, buttery-smooth layouts! 📱💻",
                            likesCount = 42,
                            isLikedByMe = false
                        )
                    )
                }
            }

            repository.allRelations.first().let { relations ->
                if (relations.isEmpty()) {
                    // Prepopulate follow requests
                    repository.insertRelation(
                        com.example.data.local.SocialRelation(
                            userName = "Alex Rivera",
                            userEmoji = "🦊",
                            status = "requested",
                            explanation = "Aspiring Code Craftsman"
                        )
                    )
                    repository.insertRelation(
                        com.example.data.local.SocialRelation(
                            userName = "Jane Doe",
                            userEmoji = "🦄",
                            status = "requested",
                            explanation = "Machine Learning Researcher"
                        )
                    )
                    // Prepopulate existing followers
                    repository.insertRelation(
                        com.example.data.local.SocialRelation(
                            userName = "Bob Builder",
                            userEmoji = "👷",
                            status = "follower",
                            explanation = "Dynamic Mobile Architect"
                        )
                    )
                    repository.insertRelation(
                        com.example.data.local.SocialRelation(
                            userName = "Sarah Connor",
                            userEmoji = "👩‍🎤",
                            status = "follower",
                            explanation = "Cybernetic Systems Specialist"
                        )
                    )
                    // Prepopulate currently following
                    repository.insertRelation(
                        com.example.data.local.SocialRelation(
                            userName = "Gemini AI Engine",
                            userEmoji = "✨",
                            status = "following",
                            explanation = "Native AI Systems Team"
                        )
                    )
                }
            }
        }

        // Automatically load/select the first thread if exists
        viewModelScope.launch {
            allSessions.collect { sessions ->
                if (_selectedSessionId.value == null && sessions.isNotEmpty()) {
                    _selectedSessionId.value = sessions.first().id
                }
            }
        }
    }

    // --- Social Actions ---
    fun submitPost(content: String) {
        if (content.trim().isEmpty()) return
        viewModelScope.launch {
            repository.insertPost(
                com.example.data.local.SocialPost(
                    authorName = "You",
                    authorEmoji = "🦊",
                    content = content,
                    likesCount = 0,
                    isLikedByMe = false
                )
            )
        }
    }

    fun toggleLikePost(post: com.example.data.local.SocialPost) {
        viewModelScope.launch {
            val updated = post.copy(
                isLikedByMe = !post.isLikedByMe,
                likesCount = if (post.isLikedByMe) post.likesCount - 1 else post.likesCount + 1
            )
            repository.updatePost(updated)
        }
    }

    fun deletePost(post: com.example.data.local.SocialPost) {
        viewModelScope.launch {
            repository.deletePost(post)
        }
    }

    fun followUser(userName: String, userEmoji: String, explanation: String = "Suggested Workspace Friend") {
        viewModelScope.launch {
            repository.insertRelation(
                com.example.data.local.SocialRelation(
                    userName = userName,
                    userEmoji = userEmoji,
                    status = "following",
                    explanation = explanation
                )
            )
        }
    }

    fun acceptFollower(relation: com.example.data.local.SocialRelation) {
        viewModelScope.launch {
            repository.updateRelation(relation.copy(status = "follower"))
        }
    }

    fun rejectFollower(relation: com.example.data.local.SocialRelation) {
        viewModelScope.launch {
            repository.deleteRelation(relation)
        }
    }

    fun unfollowUser(relation: com.example.data.local.SocialRelation) {
        viewModelScope.launch {
            repository.deleteRelation(relation)
        }
    }

    fun selectSession(sessionId: Long) {
        _selectedSessionId.value = sessionId
    }

    fun createNewSession(
        title: String = "New Chat",
        personality: ChatPersonality = ChatPersonality.personalities.first(),
        customSystemPrompt: String = ""
    ) {
        viewModelScope.launch {
            val systemPrompt = customSystemPrompt.ifEmpty { personality.defaultSystemPrompt }
            val newId = repository.createSession(
                title = title,
                systemPrompt = systemPrompt,
                personalityName = personality.name
            )
            _selectedSessionId.value = newId
        }
    }

    fun deleteSession(session: ChatSession) {
        viewModelScope.launch {
            repository.deleteSession(session)
            if (_selectedSessionId.value == session.id) {
                _selectedSessionId.value = allSessions.value.firstOrNull { it.id != session.id }?.id
            }
        }
    }

    fun updateSessionTitle(session: ChatSession, newTitle: String) {
        viewModelScope.launch {
            repository.updateSession(session.copy(title = newTitle))
        }
    }

    fun updateSessionInstructions(session: ChatSession, newSystemPrompt: String, customPersona: String = "Custom Instructions") {
        viewModelScope.launch {
            repository.updateSession(
                session.copy(
                    systemPrompt = newSystemPrompt,
                    personalityName = customPersona
                )
            )
        }
    }

    fun clearHistory(sessionId: Long) {
        viewModelScope.launch {
            repository.clearSessionHistory(sessionId)
        }
    }

    fun sendMessage(sessionId: Long, text: String) {
        if (text.trim().isEmpty() || _isGenerating.value) return
        viewModelScope.launch {
            _isGenerating.value = true
            repository.sendMessageToGemini(sessionId, text)
            _isGenerating.value = false
        }
    }

    fun retryLastMessage(sessionId: Long) {
        if (_isGenerating.value) return
        viewModelScope.launch {
            _isGenerating.value = true
            repository.retryLastFailedMessage(sessionId)
            _isGenerating.value = false
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
