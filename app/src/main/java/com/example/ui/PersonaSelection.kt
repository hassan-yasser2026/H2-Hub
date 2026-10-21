package com.example.ui

internal fun resolvePersonaScreenId(
    personaOnlyId: String?,
    coderOnly: Boolean,
    selectedPersonaId: String
): String = personaOnlyId
    ?: if (coderOnly) "coder" else selectedPersonaId.takeUnless { it == "coder" } ?: "teacher"

internal fun resolvePersonaDisplay(
    personaId: String,
    personas: List<Persona>,
    fallback: Persona
): Persona = personas.firstOrNull { it.id == personaId } ?: fallback
