package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersistentAppDaoTest {
    private lateinit var context: Context

    @Before
    fun clearStoredData() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("h2hub_data", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun quranRecordPersistsFullAssessmentAcrossDaoInstances() = runBlocking {
        val record = QuranRecord(
            id = "recitation-1",
            surah = "الفاتحة",
            userTranscription = "بسم الله",
            aiFeedback = "الأداء العام: جيد",
            score = 75,
            timestamp = 1_700_000_000_000,
            surahNumber = 1,
            ayahNumber = 1,
            assessmentJson = """{"performance":"جيد","tajweedTips":["مد طبيعي"]}"""
        )

        PersistentAppDao(context).insertQuranRecord(record)
        val restored = PersistentAppDao(context).getAllQuranRecordsFlow().first().single()

        assertEquals(record, restored)
    }

    @Test
    fun deletingQuranRecordRemovesItFromPersistentHistory() = runBlocking {
        val dao = PersistentAppDao(context)
        dao.insertQuranRecord(
            QuranRecord(
                id = "recitation-to-delete",
                surah = "الفاتحة",
                userTranscription = "",
                aiFeedback = "",
                score = 50
            )
        )

        dao.deleteQuranRecord("recitation-to-delete")

        assertTrue(PersistentAppDao(context).getAllQuranRecordsFlow().first().isEmpty())
    }

    @Test
    fun olderQuranHistoryRecordsRemainReadable() = runBlocking {
        context.getSharedPreferences("h2hub_data", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "quran_records",
                """[{"id":"legacy","surah":"الفاتحة","userTranscription":"بسم الله","aiFeedback":"جيد","score":75,"timestamp":1700000000000}]"""
            )
            .commit()

        val restored = PersistentAppDao(context).getAllQuranRecordsFlow().first().single()

        assertEquals("legacy", restored.id)
        assertEquals(0, restored.surahNumber)
        assertEquals(0, restored.ayahNumber)
        assertEquals("", restored.assessmentJson)
    }

    @Test
    fun pendingChatRequestPersistsAndCanBeRemoved() = runBlocking {
        val dao = PersistentAppDao(context)
        val request = PendingChatRequest(
            notificationId = "job-1",
            sessionId = "session-1",
            requestJson = """{"message":"hello"}""",
            createdAt = 1_700_000_000_000,
            userId = "user-1"
        )

        dao.insertPendingChatRequest(request)

        assertEquals(request, PersistentAppDao(context).getPendingChatRequest("job-1"))
        assertEquals(listOf(request), PersistentAppDao(context).getPendingChatRequests())
        dao.deletePendingChatRequest("job-1")
        assertEquals(null, PersistentAppDao(context).getPendingChatRequest("job-1"))
    }

    @Test
    fun personaMessagesPersistInAnIsolatedSessionAcrossDaoInstances() = runBlocking {
        val dao = PersistentAppDao(context)
        val userMessage = ChatMessage(
            id = "persona-user-1",
            sessionId = "persona_designer",
            role = "user",
            content = "ساعديني في اختيار الألوان",
            timestamp = 1_700_000_000_000
        )
        val reply = ChatMessage(
            id = "persona-reply-1",
            sessionId = "persona_designer",
            role = "model",
            content = "اختاري ألواناً متناسقة.",
            timestamp = 1_700_000_000_001,
            sources = listOf(ChatSource("مرجع التصميم", "https://example.com/design", reference = 3)),
            thinkingSeconds = 7
        )

        dao.insertMessage(userMessage)
        dao.insertMessage(reply)

        val restored = PersistentAppDao(context)
            .getMessagesForSessionFlow("persona_designer")
            .first()

        assertEquals(listOf(userMessage, reply), restored)
        assertTrue(
            PersistentAppDao(context)
                .getMessagesForSessionFlow("smart-cat-session")
                .first()
                .isEmpty()
        )
    }

    @Test
    fun multipleChatsForOneAssistantKeepSeparateTitlesAndMessages() = runBlocking {
        val dao = PersistentAppDao(context)
        val first = ChatSession(
            id = "persona-designer-1",
            title = "ألوان الشعار",
            personaId = "designer"
        )
        val second = ChatSession(
            id = "persona-designer-2",
            title = "تصميم بطاقة",
            personaId = "designer"
        )
        dao.insertSession(first)
        dao.insertSession(second)
        dao.insertMessage(
            ChatMessage(
                id = "designer-message-1",
                sessionId = first.id,
                role = "user",
                content = "اقترحي ألواناً"
            )
        )
        dao.insertMessage(
            ChatMessage(
                id = "designer-message-2",
                sessionId = second.id,
                role = "user",
                content = "صممي بطاقة"
            )
        )

        val restoredDao = PersistentAppDao(context)
        val restoredSessions = restoredDao.getAllSessionsFlow().first()
            .filter { it.personaId == "designer" }
        val firstMessages = restoredDao.getMessagesForSessionFlow(first.id).first()
        val secondMessages = restoredDao.getMessagesForSessionFlow(second.id).first()

        assertEquals(setOf(first, second), restoredSessions.toSet())
        assertEquals("اقترحي ألواناً", firstMessages.single().content)
        assertEquals("صممي بطاقة", secondMessages.single().content)
    }

    @Test
    fun clearingChatCacheRemovesSessionsAndMessages() = runBlocking {
        val dao = PersistentAppDao(context)
        val session = ChatSession(id = "cached-session", title = "سجل محلي")
        dao.insertSession(session)
        dao.insertMessage(
            ChatMessage(
                id = "cached-message",
                sessionId = session.id,
                role = "user",
                content = "رسالة خاصة"
            )
        )

        dao.clearChatHistory()

        val restoredDao = PersistentAppDao(context)
        assertTrue(restoredDao.getAllSessionsFlow().first().isEmpty())
        assertTrue(restoredDao.getMessagesForSessionFlow(session.id).first().isEmpty())
    }

    @Test
    fun chatSessionUpdatedAtPersistsAndControlsRecentFirstOrder() = runBlocking {
        val dao = PersistentAppDao(context)
        val older = ChatSession(
            id = "older",
            title = "أقدم",
            timestamp = 100,
            updatedAt = 200
        )
        val recentlyUpdated = ChatSession(
            id = "recent",
            title = "أحدث نشاطاً",
            timestamp = 150,
            updatedAt = 300
        )
        dao.insertSession(older)
        dao.insertSession(recentlyUpdated)

        val restored = PersistentAppDao(context).getAllSessionsFlow().first()

        assertEquals(listOf(recentlyUpdated, older), restored)
    }
}
