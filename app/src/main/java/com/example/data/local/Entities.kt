package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val systemPrompt: String,
    val personalityName: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user" or "model"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFailed: Boolean = false
)

@Entity(tableName = "social_posts")
data class SocialPost(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val authorName: String,
    val authorEmoji: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val likesCount: Int = 0,
    val isLikedByMe: Boolean = false
)

@Entity(tableName = "social_relations")
data class SocialRelation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userName: String,
    val userEmoji: String,
    val status: String, // "following", "requested" (meaning pending follower request), or "follower" (accepted/existing follower)
    val explanation: String = "Interactive Peer"
)

