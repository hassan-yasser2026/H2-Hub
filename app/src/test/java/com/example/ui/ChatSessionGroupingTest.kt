package com.example.ui

import com.example.data.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatSessionGroupingTest {
    @Test
    fun groupsSessionsByRecentActivityAndKeepsNewestFirst() {
        val now = 1_800_000_000_000L
        val day = 24 * 60 * 60 * 1000L
        val today = ChatSession("today", "اليوم", timestamp = now - 90 * day, updatedAt = now - day / 2)
        val lastWeek = ChatSession("week", "الأسبوع", timestamp = now, updatedAt = now - 3 * day)
        val lastMonth = ChatSession("month", "الشهر", timestamp = now, updatedAt = now - 10 * day)
        val older = ChatSession("older", "أقدم", timestamp = now, updatedAt = now - 45 * day)

        val grouped = groupSessionsByTime(listOf(older, lastMonth, today, lastWeek), now)

        assertEquals(
            listOf("اليوم", "آخر 7 أيام", "آخر 30 يوم",
                SimpleDateFormat("MMMM yyyy", Locale.forLanguageTag("ar")).format(Date(older.updatedAt))),
            grouped.map { it.first }
        )
        assertEquals(listOf(today), grouped[0].second)
        assertEquals(listOf(lastWeek), grouped[1].second)
        assertEquals(listOf(lastMonth), grouped[2].second)
        assertEquals(listOf(older), grouped[3].second)
    }

    @Test
    fun placesFutureActivityInTodayAndLeavesEmptyListsEmpty() {
        val now = 1_800_000_000_000L
        val future = ChatSession("future", "نشاط مستقبلي", updatedAt = now + 5_000)

        assertTrue(groupSessionsByTime(emptyList(), now).isEmpty())
        assertEquals("اليوم", groupSessionsByTime(listOf(future), now).single().first)
    }
}
