package com.example.data

import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

class LearningProfileRepository(private val dao: LearningProfileDao) {
    val conversations: Flow<List<LearningConversationEntity>> = dao.observeConversations()
    val questionCount: Flow<Int> = dao.observeQuestionCount()
    val subjectCounts: Flow<List<SubjectInteractionCount>> = dao.observeSubjectCounts()
    val topicStats: Flow<List<LearningTopicStat>> = dao.observeTopicStats()
    val questionTimestamps: Flow<List<Long>> = dao.observeQuestionTimestamps()
    val userProfile: Flow<LearningUserProfileEntity?> = dao.observeUserProfile()
    val quizResults: Flow<List<LearningQuizResultEntity>> = dao.observeQuizResults()

    suspend fun mirrorConversation(
        id: String,
        title: String,
        startedAt: Long,
        messages: List<ChatMessage>
    ) {
        val existing = dao.getConversation(id)
        val latestMessageTime = messages.maxOfOrNull { it.timestamp } ?: startedAt
        dao.upsertConversation(
            existing?.copy(
                title = title,
                lastActivityAt = maxOf(existing.lastActivityAt, latestMessageTime)
            ) ?: LearningConversationEntity(
                id = id,
                title = title,
                startedAt = startedAt,
                lastActivityAt = latestMessageTime
            )
        )
        if (messages.isNotEmpty()) {
            dao.upsertMessages(
                messages.map { message ->
                    LearningMessageEntity(
                        id = message.id,
                        sessionId = message.sessionId,
                        timestamp = message.timestamp,
                        role = message.role,
                        content = message.content,
                        generatedImageUrl = message.generatedImageUrl
                    )
                }
            )
        }
    }

    suspend fun saveMessage(message: ChatMessage) {
        dao.upsertMessage(
            LearningMessageEntity(
                id = message.id,
                sessionId = message.sessionId,
                timestamp = message.timestamp,
                role = message.role,
                content = message.content,
                generatedImageUrl = message.generatedImageUrl
            )
        )
        dao.updateLastActivity(message.sessionId, message.timestamp)
    }

    suspend fun getConversation(id: String): LearningConversationEntity? =
        dao.getConversation(id)

    suspend fun getMessages(id: String): List<LearningMessageEntity> =
        dao.getMessages(id)

    suspend fun getTopicStats(): List<LearningTopicStat> = dao.getTopicStats()

    suspend fun getRecentSummaries(
        limit: Int = 3,
        excludeSessionId: String? = null
    ): List<LearningConversationEntity> =
        dao.getRecentSummaries(limit.coerceIn(1, 3), excludeSessionId)

    suspend fun saveSummary(
        id: String,
        summary: String,
        summarizedAt: Long,
        lastMessageId: String
    ) {
        val conversation = dao.getConversation(id) ?: return
        dao.upsertConversation(
            conversation.copy(
                summary = summary,
                summarizedAt = summarizedAt,
                summaryForMessageId = lastMessageId
            )
        )
    }

    suspend fun deleteConversation(id: String) {
        dao.deleteMessages(id)
        dao.deleteQuestions(id)
        dao.deleteConversation(id)
        refreshUserProfile()
    }

    suspend fun addQuestion(question: LearningQuestionEntity) {
        dao.insertQuestion(question)
        refreshUserProfile()
    }

    suspend fun markLatestTopicAsStrength() {
        dao.getLatestQuestion()?.let { dao.markTopicAsStrength(it.topic) }
        refreshUserProfile()
    }

    suspend fun saveQuizResult(result: LearningQuizResultEntity) {
        dao.insertQuizResult(result)
    }

    suspend fun refreshUserProfile() {
        val weaknesses = dao.getTopicStats()
            .filter { it.isWeakness }
            .map { "${it.subject}: ${it.topic}" }
        val subjects = dao.getSubjectCounts().map { it.subject }
        dao.upsertUserProfile(
            LearningUserProfileEntity(
                weakTopicsJson = JSONArray(weaknesses).toString(),
                subjectsJson = JSONArray(subjects).toString()
            )
        )
    }

    suspend fun clearUserData() {
        dao.clearMessages()
        dao.clearQuestions()
        dao.clearConversations()
        dao.clearUserProfile()
        dao.clearQuizResults()
    }
}
