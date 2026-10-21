package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.ui.AppScreen
import com.example.ui.appScreenAccent
import org.junit.Assert.assertEquals
import org.junit.Test

class AccentColorsTest {
    @Test
    fun sectionAccentsMatchTheRequestedPalette() {
        assertEquals(Color(0xFF8B5CF6), AccentColors.SmartCat)
        assertEquals(Color(0xFF10B981), AccentColors.Quran)
        assertEquals(Color(0xFF06B6D4), AccentColors.VoiceFriend)
        assertEquals(Color(0xFF3B82F6), AccentColors.Personas)
        assertEquals(Color(0xFFF59E0B), AccentColors.Productivity)
        assertEquals(Color(0xFFEC4899), AccentColors.Organizer)
        assertEquals(Color(0xFFEAB308), AccentColors.Subscription)
        assertEquals(Color(0xFF14B8A6), AccentColors.Coder)
    }

    @Test
    fun personaAccentsMatchIdsAndFriendColorsAreConsistent() {
        assertEquals(AccentColors.PersonaAhmed, AccentColors.forPersona("teacher"))
        assertEquals(AccentColors.PersonaDina, AccentColors.forPersona("doctor"))
        assertEquals(AccentColors.PersonaSamir, AccentColors.forPersona("business"))
        assertEquals(AccentColors.PersonaFatma, AccentColors.forPersona("nanny"))
        assertEquals(AccentColors.PersonaAya, AccentColors.forPersona("designer"))
        assertEquals(AccentColors.PersonaMorad, AccentColors.forPersona("chef"))
        assertEquals(AccentColors.PersonaAbdulrahman, AccentColors.forPersona("sheikh"))
        assertEquals(AccentColors.PersonaFahd, AccentColors.forPersona("coach"))
        assertEquals(AccentColors.PersonaAdel, AccentColors.forPersona("legal"))
        assertEquals(AccentColors.VoiceFriend, AccentColors.forPersona("hasan"))
        assertEquals(AccentColors.VoiceFriend, AccentColors.forPersona("jana"))
        assertEquals(AccentColors.SmartCat, AccentColors.forPersona("unknown"))
    }

    @Test
    fun appSectionsResolveToTheirAccent() {
        assertEquals(AccentColors.SmartCat, appScreenAccent(AppScreen.CHAT))
        assertEquals(AccentColors.Quran, appScreenAccent(AppScreen.QURAN))
        assertEquals(AccentColors.VoiceFriend, appScreenAccent(AppScreen.FRIEND_CHAT))
        assertEquals(AccentColors.Personas, appScreenAccent(AppScreen.PERSONAS))
        assertEquals(AccentColors.Productivity, appScreenAccent(AppScreen.PRODUCTIVITY))
        assertEquals(AccentColors.Organizer, appScreenAccent(AppScreen.ORGANIZER))
        assertEquals(AccentColors.Coder, appScreenAccent(AppScreen.CODER))
        assertEquals(AccentColors.Subscription, appScreenAccent(AppScreen.SUBSCRIPTION))
        assertEquals(AccentColors.PersonaAya, appScreenAccent(AppScreen.PERSONA_CHAT, "designer"))
    }
}
