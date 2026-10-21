package com.example.ui

import com.example.data.ChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

internal fun groupSessionsByTime(
    sessions: List<ChatSession>,
    now: Long = System.currentTimeMillis()
): List<Pair<String, List<ChatSession>>> {
    val oneDayMs = 24 * 60 * 60 * 1000L
    val sevenDaysMs = 7 * oneDayMs
    val thirtyDaysMs = 30 * oneDayMs
    val groups = LinkedHashMap<String, MutableList<ChatSession>>()

    sessions.sortedByDescending { it.updatedAt }.forEach { session ->
        val age = (now - session.updatedAt).coerceAtLeast(0)
        val title = when {
            age < oneDayMs -> "اليوم"
            age < sevenDaysMs -> "آخر 7 أيام"
            age < thirtyDaysMs -> "آخر 30 يوم"
            else -> SimpleDateFormat("MMMM yyyy", Locale.forLanguageTag("ar"))
                .format(Date(session.updatedAt))
        }
        groups.getOrPut(title) { mutableListOf() }.add(session)
    }

    return groups.map { (title, groupedSessions) -> title to groupedSessions.toList() }
}
