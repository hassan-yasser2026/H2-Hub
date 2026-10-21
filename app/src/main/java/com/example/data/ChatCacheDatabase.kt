package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Dao
interface ChatCacheDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): ChatSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSession(session: ChatSession)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_sessions")
    suspend fun clearSessions()

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessages(sessionId: String): List<ChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMessage(message: ChatMessage)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND id = :messageId")
    suspend fun deleteMessage(sessionId: String, messageId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMessages(messages: List<ChatMessage>)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun clearMessages()
}

class ChatCacheConverters {
    @TypeConverter
    fun sourcesToJson(sources: List<ChatSource>): String = JSONArray().apply {
        sources.forEach { source ->
            put(
                JSONObject()
                    .put("title", source.title)
                    .put("url", source.url)
                    .apply { source.reference?.let { put("reference", it) } }
            )
        }
    }.toString()

    @TypeConverter
    fun jsonToSources(json: String): List<ChatSource> = runCatching {
        val array = JSONArray(json)
        List(array.length()) { index ->
            val source = array.getJSONObject(index)
            ChatSource(
                title = source.getString("title"),
                url = source.getString("url"),
                reference = source.optInt("reference").takeIf { source.has("reference") }
            )
        }
    }.getOrDefault(emptyList())
}

@Database(entities = [ChatSession::class, ChatMessage::class], version = 1, exportSchema = false)
@TypeConverters(ChatCacheConverters::class)
abstract class ChatCacheDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatCacheDao

    companion object {
        @Volatile
        private var instance: ChatCacheDatabase? = null

        fun getDatabase(context: Context): ChatCacheDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatCacheDatabase::class.java,
                    "h2_chat_cache.db"
                ).build().also { instance = it }
            }

        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
