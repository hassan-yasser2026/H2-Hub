package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextSanitizerTest {
    @Test
    fun usesDistinctNaturalPitchForFemaleAndMalePersonas() {
        assertEquals(1.08f, personaSpeechPitch("jana"))
        assertEquals(1.08f, personaSpeechPitch("designer"))
        assertEquals(0.94f, personaSpeechPitch("hasan"))
        assertEquals(1.0f, personaSpeechPitch(null))
    }

    @Test
    fun usesTheSelectedVoiceForDefaultSpeechButKeepsExplicitPersonaVoice() {
        assertEquals("jana", resolveSpeechPersonaId(null, "jana"))
        assertEquals("hasan", resolveSpeechPersonaId(null, "hasan"))
        assertEquals("teacher", resolveSpeechPersonaId("teacher", "jana"))
        assertEquals(null, resolveSpeechPersonaId(null, "teacher"))
    }

    @Test
    fun removesEmojiStickersLinksAndMarkdownFormatting() {
        val text = "## مرحباً 👋\n![ملصق](https://example.com/sticker.png)\nاقرأ [الدرس](https://example.com)"

        assertEquals("مرحباً\nاقرأ الدرس", sanitizeSpeechText(text))
    }

    @Test
    fun omitsLatexAndCodeButKeepsTheSurroundingExplanation() {
        val text = "الحل هو \$\$x^2 + 3x = 0\$\$ ثم نبدأ.\n```kotlin\nval x = 3\n```"

        assertEquals("الحل هو ثم نبدأ.", sanitizeSpeechText(text))
    }

    @Test
    fun removesSymbolRunsAndKeepsWordsAndNaturalPauses() {
        assertEquals(
            "افتح الرابط ثم تابع، من فضلك.",
            sanitizeSpeechText("افتح @#$% الرابط 😀 ثم تابع، من فضلك.")
        )
    }

    @Test
    fun keepsArabicDiacriticsAndMeaningfulNumbersWhileRemovingListMarkers() {
        assertEquals(
            "السَّلَامُ عَلَيْكُمْ.\nالدرجة ٩ من ١٠.",
            sanitizeSpeechText("1. السَّلَامُ عَلَيْكُمْ.\nالدرجة ٩ من ١٠.")
        )
    }

    @Test
    fun selectsTheExactPersonaIdForEveryPersonaChat() {
        listOf(
            "business", "doctor", "teacher", "nanny", "chef", "designer",
            "coach", "sheikh", "legal"
        ).forEach { personaId ->
            assertEquals(personaId, resolvePersonaScreenId(personaId, false, "teacher"))
        }
        assertEquals("coder", resolvePersonaScreenId(null, true, "teacher"))
        assertEquals("teacher", resolvePersonaScreenId(null, false, "coder"))
    }
}
