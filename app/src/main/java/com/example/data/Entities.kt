package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey
    val id: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val personaId: String? = null,
    val updatedAt: Long = timestamp
)

data class ChatSource(
    val title: String,
    val url: String,
    val reference: Int? = null
)

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["sessionId", "timestamp"])]
)
data class ChatMessage(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sources: List<ChatSource> = emptyList(),
    val generatedImageUrl: String? = null,
    val thinkingSeconds: Int? = null
)

data class PendingChatRequest(
    val notificationId: String,
    val sessionId: String,
    val requestJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val userId: String? = null
)

data class SocraticProgress(
    val originalQuestion: String = "",
    val step: Int = 0,
    val totalSteps: Int = 5,
    val correctStreak: Int = 0,
    val wrongStreak: Int = 0,
    val difficultyLevel: Int = 2,
    val awaitingAnswer: Boolean = false,
    val complete: Boolean = false
)

data class ProductivityDoc(
    val id: String,
    val type: String,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class UserSchedule(
    val id: String = "current",
    val age: Int,
    val occupation: String,
    val schoolOrWorkTimings: String,
    val lessonTimings: String,
    val scheduleText: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class QuranRecord(
    val id: String,
    val surah: String,
    val userTranscription: String,
    val aiFeedback: String,
    val score: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val surahNumber: Int = 0,
    val ayahNumber: Int = 0,
    val assessmentJson: String = ""
)
