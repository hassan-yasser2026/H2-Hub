package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLearningMemoryTest {
    @Test
    fun summarizesConversationLocallyIntoThreeUsefulPoints() {
        val summary = summarizeConversationLocally(
            listOf(
                LearningMessageEntity("1", "c1", 1L, "user", "اشرح قانون نيوتن الثاني"),
                LearningMessageEntity("2", "c1", 2L, "model", "القوة تساوي الكتلة مضروبة في العجلة."),
                LearningMessageEntity("3", "c1", 3L, "user", "وماذا عن مثال السيارة؟")
            )
        )

        assertEquals(3, summary?.lines()?.size)
        assertTrue(summary.orEmpty().contains("وماذا عن مثال السيارة؟"))
        assertTrue(summary.orEmpty().contains("القوة تساوي الكتلة"))
    }

    @Test
    fun doesNotCreateSummaryWithoutStudentMessages() {
        assertNull(
            summarizeConversationLocally(
                listOf(LearningMessageEntity("1", "c1", 1L, "model", "أهلاً"))
            )
        )
    }

    @Test
    fun recognizesRecallRequestsAndUsesOnlyLocalSummaries() {
        assertTrue(isLocalMemoryRecallQuestion("فاكر سألتك إيه قبل كده؟"))
        assertFalse(isLocalMemoryRecallQuestion("اشرح قانون نيوتن"))
        assertTrue(
            formatLocalMemoryRecall(listOf("• موضوع محفوظ"))
                .contains("موضوع محفوظ")
        )
        assertTrue(formatLocalMemoryRecall(emptyList()).contains("لا يوجد ملخص"))
    }
}
