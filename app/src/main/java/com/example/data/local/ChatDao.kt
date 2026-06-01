package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // --- Sessions ---
    @Query("SELECT * FROM chat_sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): ChatSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession): Long

    @Update
    suspend fun updateSession(session: ChatSession)

    @Delete
    suspend fun deleteSession(session: ChatSession)

    // --- Messages ---
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSessionFlow(sessionId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: Long): List<ChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Update
    suspend fun updateMessage(message: ChatMessage)

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun clearMessagesForSession(sessionId: Long)

    @Query("SELECT * FROM chat_messages WHERE text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMessages(query: String): Flow<List<ChatMessage>>

    // --- Social Posts ---
    @Query("SELECT * FROM social_posts ORDER BY timestamp DESC")
    fun getAllPosts(): Flow<List<SocialPost>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPost(post: SocialPost): Long

    @Update
    suspend fun updatePost(post: SocialPost)

    @Delete
    suspend fun deletePost(post: SocialPost)

    // --- Social Relations (Follow/Pending/Followers) ---
    @Query("SELECT * FROM social_relations ORDER BY id DESC")
    fun getAllRelations(): Flow<List<SocialRelation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelation(relation: SocialRelation): Long

    @Update
    suspend fun updateRelation(relation: SocialRelation)

    @Delete
    suspend fun deleteRelation(relation: SocialRelation)

    @Query("DELETE FROM social_relations WHERE id = :relationId")
    suspend fun deleteRelationById(relationId: Long)
}
