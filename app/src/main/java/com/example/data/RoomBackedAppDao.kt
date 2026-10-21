package com.example.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomBackedAppDao(
    context: Context,
    private val legacyDao: PersistentAppDao
) : AppDao by legacyDao {
    private val preferences = context.getSharedPreferences("h2hub_data", Context.MODE_PRIVATE)
    private val database = ChatCacheDatabase.getDatabase(context)
    private val chatDao = database.chatDao()
    private val migrationMutex = Mutex()

    override fun getAllSessionsFlow(): Flow<List<ChatSession>> = flow {
        migrateLegacyChatCache()
        emitAll(chatDao.observeSessions())
    }

    override suspend fun getSession(sessionId: String): ChatSession? {
        migrateLegacyChatCache()
        return chatDao.getSession(sessionId)
    }

    override suspend fun insertSession(session: ChatSession) {
        migrateLegacyChatCache()
        chatDao.putSession(session)
    }

    override suspend fun deleteSession(sessionId: String) {
        migrateLegacyChatCache()
        chatDao.deleteSession(sessionId)
    }

    override suspend fun clearChatHistory() {
        migrateLegacyChatCache()
        database.withTransaction {
            chatDao.clearMessages()
            chatDao.clearSessions()
        }
    }

    override fun getMessagesForSessionFlow(sessionId: String): Flow<List<ChatMessage>> = flow {
        migrateLegacyChatCache()
        emitAll(chatDao.observeMessages(sessionId))
    }

    override suspend fun getMessagesForSession(sessionId: String): List<ChatMessage> {
        migrateLegacyChatCache()
        return chatDao.getMessages(sessionId)
    }

    override suspend fun insertMessage(message: ChatMessage) {
        migrateLegacyChatCache()
        chatDao.putMessage(message)
    }

    override suspend fun deleteMessage(sessionId: String, messageId: String) {
        migrateLegacyChatCache()
        chatDao.deleteMessage(sessionId, messageId)
    }

    override suspend fun deleteMessagesForSession(sessionId: String) {
        migrateLegacyChatCache()
        chatDao.deleteMessages(sessionId)
    }

    private suspend fun migrateLegacyChatCache() {
        if (preferences.getBoolean(KEY_CHAT_ROOM_MIGRATED, false)) return
        migrationMutex.withLock {
            if (preferences.getBoolean(KEY_CHAT_ROOM_MIGRATED, false)) return
            val sessions = legacyDao.getAllSessionsFlow().first()
            database.withTransaction {
                sessions.forEach { session ->
                    chatDao.putSession(session)
                    legacyDao.getMessagesForSession(session.id)
                        .takeIf { it.isNotEmpty() }
                        ?.let { chatDao.putMessages(it) }
                }
            }
            legacyDao.clearChatHistory()
            check(
                preferences.edit()
                    .putBoolean(KEY_CHAT_ROOM_MIGRATED, true)
                    .commit()
            ) { "تعذر تسجيل اكتمال نقل سجل المحادثات إلى Room." }
        }
    }

    private companion object {
        const val KEY_CHAT_ROOM_MIGRATED = "chat_room_migration_complete"
    }
}
