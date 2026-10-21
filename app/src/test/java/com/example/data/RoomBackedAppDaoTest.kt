package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomBackedAppDaoTest {
    private lateinit var context: Context
    private lateinit var legacyDao: PersistentAppDao
    private lateinit var roomDao: RoomBackedAppDao

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("h2hub_data", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ChatCacheDatabase.closeForTests()
        context.deleteDatabase("h2_chat_cache.db")
        legacyDao = PersistentAppDao(context)
        roomDao = RoomBackedAppDao(context, legacyDao)
    }

    @After
    fun tearDown() {
        ChatCacheDatabase.closeForTests()
        context.deleteDatabase("h2_chat_cache.db")
    }

    @Test
    fun migratesLegacyChatsAndMessagesIntoRoom() = runBlocking {
        val session = ChatSession(
            id = "legacy-room-session",
            title = "محادثة قديمة",
            timestamp = 100,
            updatedAt = 300
        )
        val message = ChatMessage(
            id = "legacy-room-message",
            sessionId = session.id,
            role = "model",
            content = "الإجابة القديمة",
            timestamp = 200,
            sources = listOf(ChatSource("مرجع", "https://example.com", 1))
        )
        legacyDao.insertSession(session)
        legacyDao.insertMessage(message)

        assertEquals(listOf(session), roomDao.getAllSessionsFlow().first())
        assertEquals(listOf(message), roomDao.getMessagesForSession(session.id))
        assertEquals(emptyList<ChatSession>(), legacyDao.getAllSessionsFlow().first())
    }

    @Test
    fun clearingAccountCacheDoesNotReimportLegacyConversations() = runBlocking {
        val session = ChatSession(id = "old-account", title = "حساب سابق")
        legacyDao.insertSession(session)

        roomDao.getAllSessionsFlow().first()
        roomDao.clearChatHistory()
        roomDao.insertSession(ChatSession(id = "new-account", title = "الحساب الحالي"))

        assertEquals(listOf("new-account"), roomDao.getAllSessionsFlow().first().map { it.id })
    }
}
