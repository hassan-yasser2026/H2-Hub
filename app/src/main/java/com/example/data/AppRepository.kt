package com.example.data

import kotlinx.coroutines.flow.Flow

class AppRepository(
    private val appDao: AppDao,
    private val conversationSync: FirestoreConversationSync? = null
) {
    // Sessions
    val allSessions: Flow<List<ChatSession>> = appDao.getAllSessionsFlow()

    suspend fun getSession(sessionId: String): ChatSession? = appDao.getSession(sessionId)

    suspend fun insertSession(session: ChatSession) {
        appDao.insertSession(session)
        conversationSync?.saveSession(session)
    }

    suspend fun deleteSession(sessionId: String) {
        appDao.deleteSession(sessionId)
        appDao.deleteMessagesForSession(sessionId)
        conversationSync?.deleteSession(sessionId)
    }

    suspend fun clearChatHistory() = appDao.clearChatHistory()

    // Messages
    fun getMessagesForSessionFlow(sessionId: String): Flow<List<ChatMessage>> {
        return appDao.getMessagesForSessionFlow(sessionId)
    }

    suspend fun getMessagesForSession(sessionId: String): List<ChatMessage> {
        return appDao.getMessagesForSession(sessionId)
    }

    suspend fun insertMessage(message: ChatMessage) {
        appDao.insertMessage(message)
        val session = appDao.getSession(message.sessionId)
        if (session != null) {
            val updatedSession = session.copy(updatedAt = maxOf(session.updatedAt, message.timestamp))
            appDao.insertSession(updatedSession)
            conversationSync?.saveMessage(updatedSession, message)
        } else {
            conversationSync?.saveOrphanMessage(message)
        }
    }

    suspend fun deleteMessagesForSession(sessionId: String) {
        appDao.deleteMessagesForSession(sessionId)
        conversationSync?.deleteMessages(sessionId)
    }

    suspend fun insertPendingChatRequest(request: PendingChatRequest) {
        appDao.insertPendingChatRequest(request)
    }

    suspend fun getPendingChatRequest(notificationId: String): PendingChatRequest? =
        appDao.getPendingChatRequest(notificationId)

    suspend fun deletePendingChatRequest(notificationId: String) {
        appDao.deletePendingChatRequest(notificationId)
    }

    // Productivity Documents
    val allProductivityDocs: Flow<List<ProductivityDoc>> = appDao.getAllProductivityDocsFlow()

    suspend fun insertProductivityDoc(doc: ProductivityDoc) {
        appDao.insertProductivityDoc(doc)
        conversationSync?.saveProductivityDoc(doc)
    }

    suspend fun deleteProductivityDoc(id: String) {
        appDao.deleteProductivityDoc(id)
        conversationSync?.deleteProductivityDoc(id)
    }

    // User Schedules
    val userSchedule: Flow<UserSchedule?> = appDao.getUserScheduleFlow()

    suspend fun getUserSchedule(): UserSchedule? {
        return appDao.getUserSchedule()
    }

    suspend fun insertUserSchedule(schedule: UserSchedule) {
        appDao.insertUserSchedule(schedule)
        conversationSync?.saveUserSchedule(schedule)
    }

    suspend fun deleteUserSchedule() {
        appDao.deleteUserSchedule()
        conversationSync?.deleteUserSchedule()
    }

    // Quran Records
    val allQuranRecords: Flow<List<QuranRecord>> = appDao.getAllQuranRecordsFlow()

    suspend fun insertQuranRecord(record: QuranRecord) {
        appDao.insertQuranRecord(record)
        conversationSync?.saveQuranRecord(record)
    }

    suspend fun deleteQuranRecord(id: String) {
        appDao.deleteQuranRecord(id)
        conversationSync?.deleteQuranRecord(id)
    }
}
