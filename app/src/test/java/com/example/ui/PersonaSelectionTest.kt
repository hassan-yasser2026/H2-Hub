package com.example.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonaSelectionTest {
    @Test
    fun displaysTheSelectedPersonasNameAndRoleInsteadOfTheFirstPersona() {
        val teacher = Persona(
            "teacher",
            "أ. أحمد",
            "معلم ومبسط العلوم",
            Icons.Default.School,
            Color.Green
        )
        val designer = Persona(
            "designer",
            "المصممة آية",
            "واجهات التصميم الجرافيكي",
            Icons.Default.Palette,
            Color.Magenta
        )

        val selected = resolvePersonaDisplay("designer", listOf(teacher, designer), teacher)

        assertEquals("المصممة آية", selected.name)
        assertEquals("واجهات التصميم الجرافيكي", selected.role)
    }
}
