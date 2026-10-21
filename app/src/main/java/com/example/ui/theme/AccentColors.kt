package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

object AccentColors {
    val SmartCat = Color(0xFF8B5CF6)
    val Quran = Color(0xFF10B981)
    val VoiceFriend = Color(0xFF06B6D4)
    val Personas = Color(0xFF3B82F6)
    val Productivity = Color(0xFFF59E0B)
    val Organizer = Color(0xFFEC4899)
    val Subscription = Color(0xFFEAB308)
    val Coder = Color(0xFF14B8A6)

    val PersonaAhmed = Color(0xFF10B981)
    val PersonaDina = Color(0xFFEF4444)
    val PersonaSamir = Color(0xFF92400E)
    val PersonaFatma = Color(0xFFA78BFA)
    val PersonaAya = Color(0xFFEC4899)
    val PersonaMorad = Color(0xFF3B82F6)
    val PersonaAbdulrahman = Color(0xFF06B6D4)
    val PersonaFahd = Color(0xFFF59E0B)
    val PersonaAdel = Color(0xFF64748B)

    fun forPersona(personaId: String): Color = when (personaId) {
        "teacher" -> PersonaAhmed
        "doctor" -> PersonaDina
        "business" -> PersonaSamir
        "nanny" -> PersonaFatma
        "designer" -> PersonaAya
        "chef" -> PersonaMorad
        "sheikh" -> PersonaAbdulrahman
        "coach" -> PersonaFahd
        "legal" -> PersonaAdel
        "hasan", "jana" -> VoiceFriend
        "coder" -> Coder
        else -> SmartCat
    }
}

fun ColorScheme.withAccent(accent: Color): ColorScheme {
    val accentContent = if (accent.luminance() > 0.22f) {
        Color(0xFF111827)
    } else {
        Color.White
    }
    return copy(
        primary = accent,
        onPrimary = accentContent,
        primaryContainer = accent.copy(alpha = 0.22f),
        onPrimaryContainer = onSurface,
        secondary = accent,
        onSecondary = accentContent,
        secondaryContainer = accent.copy(alpha = 0.22f),
        onSecondaryContainer = onSurface,
        tertiary = accent,
        onTertiary = accentContent
    )
}
