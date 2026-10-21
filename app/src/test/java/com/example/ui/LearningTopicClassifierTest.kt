package com.example.ui

import com.example.data.LearningTopicStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningTopicClassifierTest {
    @Test
    fun classifiesRepeatedAlgebraQuestionsIntoOneTopic() {
        val first = classifyLearningQuestion("اشرح حل المعادلة من الدرجة الأولى")
        val second = classifyLearningQuestion("كيف أحل معادلة فيها مجهول؟")

        assertEquals("رياضيات", first.subject)
        assertEquals("المعادلات الجبرية", first.topic)
        assertEquals(first, second)
    }

    @Test
    fun flagsWeaknessOnlyAfterThreeQuestions() {
        assertFalse(LearningTopicStat("المعادلات", "رياضيات", 2, 0).isWeakness)
        assertTrue(LearningTopicStat("المعادلات", "رياضيات", 3, 0).isWeakness)
    }

    @Test
    fun acknowledgementIsRecordedAsStrengthSignal() {
        assertTrue(isLearningAcknowledgement("تمام فهمت الفكرة"))
        assertFalse(isLearningAcknowledgement("اشرح الفكرة مرة أخرى"))
    }
}
