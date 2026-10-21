package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPersonaInstructionsTest {
    @Test
    fun everyVisiblePersonaHasItsOwnNameAndTheCorrectDeveloper() {
        val personas = mapOf(
            "hasan" to "حسن صديق",
            "jana" to "ريتاج صديقة",
            "teacher" to "أ. أحمد",
            "coder" to "H2 Code",
            "doctor" to "د. خالد",
            "business" to "أ. سمير",
            "legal" to "المستشار عادل",
            "sheikh" to "الشيخ عبد الرحمن",
            "coach" to "الكابتن فهد",
            "designer" to "المصممة آية",
            "chef" to "الشيف مراد",
            "nanny" to "المربية أسماء"
        )

        personas.forEach { (id, expectedName) ->
            val instruction = buildAssistantSystemInstruction(id, "تعليمات المهمة")

            assertEquals(expectedName, assistantPersonaName(id))
            assertTrue(instruction.contains("اسم الشخصية التي تتحدث بها هو \"$expectedName\""))
            assertTrue(instruction.contains("المهندس حسن"))
            assertTrue(instruction.contains("لا تنسب تصميم الشخصية أو التطبيق إلى Google"))
            assertTrue(instruction.contains("لا تقل إن اسمك Gemini"))
        }
    }

    @Test
    fun aliasesAndUnknownIdsUseStablePublicIdentities() {
        assertEquals("أ. سمير", assistantPersonaName("advisor"))
        assertEquals("Smart Cat", assistantPersonaName(null))
        assertEquals("Smart Cat", assistantPersonaName("unknown"))
        assertTrue(assistantOfflineReply("designer").contains("المصممة آية"))
        assertTrue(assistantOfflineReply("designer").contains("المهندس حسن"))
    }

    @Test
    fun smartCatIsGeneralPurposeAndDoesNotForceStudyOnEveryRequest() {
        val instruction = buildSmartCatSystemInstruction("قواعد الأمان")

        assertTrue(instruction.contains("المساعد العام متعدد الاستخدامات"))
        assertTrue(instruction.contains("الكتابة والتحرير والتلخيص والترجمة"))
        assertTrue(instruction.contains("لا تفترض أن السؤال دراسي"))
        assertTrue(instruction.contains("عندما يطلب"))
        assertTrue(instruction.contains("قواعد الأمان"))
    }
}
