package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LearningProfileRoomTest {
    @Test
    fun storesRecentConversationSummariesAndKeepsProfileOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            LearningProfileDatabase::class.java
        ).allowMainThreadQueries().build()
        val repository = LearningProfileRepository(database.learningProfileDao())

        try {
            repeat(3) { index ->
                val id = "conversation-$index"
                database.learningProfileDao().upsertConversation(
                    LearningConversationEntity(
                        id = id,
                        title = "محادثة ${index + 1}",
                        startedAt = index.toLong(),
                        lastActivityAt = index.toLong()
                    )
                )
                val summary = summarizeConversationLocally(
                    listOf(
                        LearningMessageEntity(
                            "question-$index",
                            id,
                            index.toLong(),
                            "user",
                            "ما موضوع المراجعة المحلية رقم ${index + 1}؟"
                        ),
                        LearningMessageEntity(
                            "answer-$index",
                            id,
                            index + 1L,
                            "model",
                            "هذا شرح محفوظ على الجهاز."
                        )
                    )
                )!!
                repository.saveSummary(
                    id = id,
                    summary = summary,
                    summarizedAt = index.toLong(),
                    lastMessageId = "answer-$index"
                )
            }
            val recentSummaries = repository.getRecentSummaries()
            assertEquals(3, recentSummaries.size)
            assertTrue(isLocalMemoryRecallQuestion("فاكر محادثة سابقة؟"))
            assertTrue(
                formatLocalMemoryRecall(recentSummaries.mapNotNull { it.summary })
                    .contains("المراجعة المحلية رقم 3")
            )
            assertTrue(
                formatLocalMemoryRecall(
                    recentSummaries.mapNotNull { it.summary },
                    "فاكر المراجعة المحلية رقم 1؟"
                ).contains("المراجعة المحلية رقم 1")
            )
            assertEquals(
                listOf("conversation-2", "conversation-1"),
                repository.getRecentSummaries(excludeSessionId = "conversation-0").map { it.id }
            )

            repeat(3) { index ->
                repository.addQuestion(
                    LearningQuestionEntity(
                        id = "question-$index",
                        sessionId = "conversation-$index",
                        timestamp = index.toLong(),
                        subject = "رياضيات",
                        topic = "المعادلات الجبرية",
                        question = "اشرح معادلة فيها مجهول"
                    )
                )
            }
            val profile = repository.userProfile.first { it != null }!!
            assertTrue(profile.weakTopicsJson.contains("المعادلات الجبرية"))
            assertTrue(profile.subjectsJson.contains("رياضيات"))
            assertEquals("adaptive", profile.level)
        } finally {
            database.close()
        }
    }
}
