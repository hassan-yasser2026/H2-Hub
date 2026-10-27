package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

interface AppDao {
    fun getAllSessionsFlow(): Flow<List<ChatSession>>
    suspend fun getSession(sessionId: String): ChatSession?
    suspend fun insertSession(session: ChatSession)
    suspend fun deleteSession(sessionId: String)
    suspend fun clearChatHistory()

    fun getMessagesForSessionFlow(sessionId: String): Flow<List<ChatMessage>>
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessage>
    suspend fun insertMessage(message: ChatMessage)
    suspend fun deleteMessage(sessionId: String, messageId: String)
    suspend fun deleteMessagesForSession(sessionId: String)
    suspend fun insertPendingChatRequest(request: PendingChatRequest)
    suspend fun getPendingChatRequest(notificationId: String): PendingChatRequest?
    suspend fun getPendingChatRequests(): List<PendingChatRequest>
    suspend fun deletePendingChatRequest(notificationId: String)

    fun getAllProductivityDocsFlow(): Flow<List<ProductivityDoc>>
    suspend fun insertProductivityDoc(doc: ProductivityDoc)
    suspend fun deleteProductivityDoc(id: String)

    fun getUserScheduleFlow(): Flow<UserSchedule?>
    suspend fun getUserSchedule(): UserSchedule?
    suspend fun insertUserSchedule(schedule: UserSchedule)
    suspend fun deleteUserSchedule()

    fun getAllQuranRecordsFlow(): Flow<List<QuranRecord>>
    suspend fun insertQuranRecord(record: QuranRecord)
    suspend fun deleteQuranRecord(id: String)
}

class PersistentAppDao(context: Context) : AppDao {
    private val preferences = context.getSharedPreferences("h2hub_data", Context.MODE_PRIVATE)
    private val sessions = MutableStateFlow(readSessions())
    private val messages = MutableStateFlow(readMessages())
    private val pendingChatRequests = MutableStateFlow(readPendingChatRequests())
    private val productivityDocs = MutableStateFlow(readProductivityDocs())
    private val userSchedule = MutableStateFlow(readUserSchedule())
    private val quranRecords = MutableStateFlow(readQuranRecords())

    override fun getAllSessionsFlow(): Flow<List<ChatSession>> = sessions

    override suspend fun getSession(sessionId: String): ChatSession? =
        sessions.value.firstOrNull { it.id == sessionId }

    override suspend fun insertSession(session: ChatSession) {
        sessions.value = (sessions.value.filter { it.id != session.id } + session)
            .sortedByDescending { it.updatedAt }
        saveSessions()
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.value = sessions.value.filter { it.id != sessionId }
        messages.value = messages.value.filterKeys { it != sessionId }
        saveSessions()
        saveMessages()
    }

    override suspend fun clearChatHistory() {
        sessions.value = emptyList()
        messages.value = emptyMap()
        saveSessions()
        saveMessages()
    }

    override fun getMessagesForSessionFlow(sessionId: String): Flow<List<ChatMessage>> =
        messages.map { it[sessionId].orEmpty() }

    override suspend fun getMessagesForSession(sessionId: String): List<ChatMessage> =
        messages.value[sessionId].orEmpty()

    override suspend fun insertMessage(message: ChatMessage) {
        val currentMap = messages.value.toMutableMap()
        val currentList = currentMap[message.sessionId].orEmpty().toMutableList()
        if (currentList.any { it.id == message.id }) return
        currentList.add(message)
        currentMap[message.sessionId] = currentList
        messages.value = currentMap
        saveMessages()
    }

    override suspend fun deleteMessage(sessionId: String, messageId: String) {
        val currentMap = messages.value.toMutableMap()
        currentMap[sessionId] = currentMap[sessionId].orEmpty().filterNot { it.id == messageId }
        messages.value = currentMap
        saveMessages()
    }

    override suspend fun deleteMessagesForSession(sessionId: String) {
        val currentMap = messages.value.toMutableMap()
        currentMap.remove(sessionId)
        messages.value = currentMap
        saveMessages()
    }

    override suspend fun insertPendingChatRequest(request: PendingChatRequest) {
        pendingChatRequests.value =
            listOf(request) + pendingChatRequests.value.filterNot { it.notificationId == request.notificationId }
        savePendingChatRequests()
    }

    override suspend fun getPendingChatRequest(notificationId: String): PendingChatRequest? =
        pendingChatRequests.value.firstOrNull { it.notificationId == notificationId }

    override suspend fun getPendingChatRequests(): List<PendingChatRequest> =
        pendingChatRequests.value

    override suspend fun deletePendingChatRequest(notificationId: String) {
        pendingChatRequests.value =
            pendingChatRequests.value.filterNot { it.notificationId == notificationId }
        savePendingChatRequests()
    }

    override fun getAllProductivityDocsFlow(): Flow<List<ProductivityDoc>> = productivityDocs

    override suspend fun insertProductivityDoc(doc: ProductivityDoc) {
        productivityDocs.value = listOf(doc) + productivityDocs.value.filter { it.id != doc.id }
        saveProductivityDocs()
    }

    override suspend fun deleteProductivityDoc(id: String) {
        productivityDocs.value = productivityDocs.value.filter { it.id != id }
        saveProductivityDocs()
    }

    override fun getUserScheduleFlow(): Flow<UserSchedule?> = userSchedule.asStateFlow()

    override suspend fun getUserSchedule(): UserSchedule? = userSchedule.value

    override suspend fun insertUserSchedule(schedule: UserSchedule) {
        userSchedule.value = schedule
        saveUserSchedule()
    }

    override suspend fun deleteUserSchedule() {
        userSchedule.value = null
        preferences.edit().remove(KEY_USER_SCHEDULE).apply()
    }

    override fun getAllQuranRecordsFlow(): Flow<List<QuranRecord>> = quranRecords

    override suspend fun insertQuranRecord(record: QuranRecord) {
        quranRecords.value = listOf(record) + quranRecords.value.filter { it.id != record.id }
        saveQuranRecords()
    }

    override suspend fun deleteQuranRecord(id: String) {
        quranRecords.value = quranRecords.value.filter { it.id != id }
        saveQuranRecords()
    }

    private fun readSessions(): List<ChatSession> = readArray(KEY_SESSIONS) { item ->
        ChatSession(
            id = item.getString("id"),
            title = item.getString("title"),
            timestamp = item.getLong("timestamp"),
            personaId = item.optString("personaId").takeIf { it.isNotEmpty() },
            updatedAt = item.optLong("updatedAt", item.getLong("timestamp"))
        )
    }

    private fun readMessages(): Map<String, List<ChatMessage>> = readArray(KEY_MESSAGES) { item ->
        ChatMessage(
            item.getString("id"), item.getString("sessionId"), item.getString("role"),
            item.getString("content"), item.getLong("timestamp"),
            item.optJSONArray("sources")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val source = array.optJSONObject(index) ?: return@mapNotNull null
                    val url = source.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ChatSource(
                        source.optString("title").ifBlank { url },
                        url,
                        source.optInt("reference").takeIf { source.has("reference") && it > 0 }
                    )
                }
            }.orEmpty(),
            item.optString("generatedImageUrl").takeIf { it.isNotBlank() },
            item.optInt("thinkingSeconds").takeIf { item.has("thinkingSeconds") && !item.isNull("thinkingSeconds") }
        )
    }.groupBy { it.sessionId }

    private fun readPendingChatRequests(): List<PendingChatRequest> =
        readArray(KEY_PENDING_CHAT_REQUESTS) { item ->
            PendingChatRequest(
                notificationId = item.getString("notificationId"),
                sessionId = item.getString("sessionId"),
                requestJson = item.getString("requestJson"),
                createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                userId = item.optString("userId").takeIf(String::isNotBlank)
            )
        }

    private fun readProductivityDocs(): List<ProductivityDoc> = readArray(KEY_PRODUCTIVITY_DOCS) { item ->
        ProductivityDoc(item.getString("id"), item.getString("type"), item.getString("title"), item.getString("content"), item.getLong("timestamp"))
    }

    private fun readUserSchedule(): UserSchedule? = preferences.getString(KEY_USER_SCHEDULE, null)?.let { value ->
        runCatching {
            val item = JSONObject(value)
            UserSchedule(item.getString("id"), item.getInt("age"), item.getString("occupation"), item.getString("schoolOrWorkTimings"), item.getString("lessonTimings"), item.getString("scheduleText"), item.getLong("timestamp"))
        }.getOrNull()
    }

    private fun readQuranRecords(): List<QuranRecord> = readArray(KEY_QURAN_RECORDS) { item ->
        QuranRecord(
            id = item.getString("id"),
            surah = item.getString("surah"),
            userTranscription = item.getString("userTranscription"),
            aiFeedback = item.getString("aiFeedback"),
            score = item.getInt("score"),
            timestamp = item.getLong("timestamp"),
            surahNumber = item.optInt("surahNumber"),
            ayahNumber = item.optInt("ayahNumber"),
            assessmentJson = item.optString("assessmentJson")
        )
    }

    private fun <T> readArray(key: String, converter: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        List(array.length()) { index -> converter(array.getJSONObject(index)) }
    }.getOrDefault(emptyList())

    private fun saveSessions() = saveArray(KEY_SESSIONS, sessions.value.map { item ->
        JSONObject().put("id", item.id).put("title", item.title).put("timestamp", item.timestamp)
            .put("personaId", item.personaId).put("updatedAt", item.updatedAt)
    })

    private fun saveMessages() = saveArray(KEY_MESSAGES, messages.value.values.flatten().map { item ->
        JSONObject().put("id", item.id).put("sessionId", item.sessionId).put("role", item.role).put("content", item.content).put("timestamp", item.timestamp).apply {
            if (item.sources.isNotEmpty()) {
                put("sources", JSONArray().apply {
                    item.sources.forEach {
                        put(JSONObject().put("title", it.title).put("url", it.url).apply {
                            it.reference?.let { reference -> put("reference", reference) }
                        })
                    }
                })
            }
            item.generatedImageUrl?.let { put("generatedImageUrl", it) }
            item.thinkingSeconds?.let { put("thinkingSeconds", it) }
        }
    })

    private fun savePendingChatRequests() = saveArray(
        KEY_PENDING_CHAT_REQUESTS,
        pendingChatRequests.value.map { item ->
            JSONObject()
                .put("notificationId", item.notificationId)
                .put("sessionId", item.sessionId)
                .put("requestJson", item.requestJson)
                .put("createdAt", item.createdAt)
                .put("userId", item.userId)
        }
    )

    private fun saveProductivityDocs() = saveArray(KEY_PRODUCTIVITY_DOCS, productivityDocs.value.map { item ->
        JSONObject().put("id", item.id).put("type", item.type).put("title", item.title).put("content", item.content).put("timestamp", item.timestamp)
    })

    private fun saveUserSchedule() {
        userSchedule.value?.let { item ->
            preferences.edit().putString(KEY_USER_SCHEDULE, JSONObject().put("id", item.id).put("age", item.age).put("occupation", item.occupation).put("schoolOrWorkTimings", item.schoolOrWorkTimings).put("lessonTimings", item.lessonTimings).put("scheduleText", item.scheduleText).put("timestamp", item.timestamp).toString()).apply()
        }
    }

    private fun saveQuranRecords() = saveArray(KEY_QURAN_RECORDS, quranRecords.value.map { item ->
        JSONObject()
            .put("id", item.id)
            .put("surah", item.surah)
            .put("userTranscription", item.userTranscription)
            .put("aiFeedback", item.aiFeedback)
            .put("score", item.score)
            .put("timestamp", item.timestamp)
            .put("surahNumber", item.surahNumber)
            .put("ayahNumber", item.ayahNumber)
            .put("assessmentJson", item.assessmentJson)
    })

    private fun saveArray(key: String, items: List<JSONObject>) {
        preferences.edit().putString(key, JSONArray(items).toString()).apply()
    }

    private companion object {
        const val KEY_SESSIONS = "sessions"
        const val KEY_MESSAGES = "messages"
        const val KEY_PENDING_CHAT_REQUESTS = "pending_chat_requests"
        const val KEY_PRODUCTIVITY_DOCS = "productivity_docs"
        const val KEY_USER_SCHEDULE = "user_schedule"
        const val KEY_QURAN_RECORDS = "quran_records"
    }
}
