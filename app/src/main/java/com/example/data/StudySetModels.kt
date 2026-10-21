package com.example.data

import java.util.UUID

enum class StudySetMode(val wireValue: String) {
    QUIZ("quiz"),
    FLASHCARDS("flashcards")
}

data class StudyQuestion(
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String
)

data class StudyFlashcard(
    val front: String,
    val back: String
)

data class GeneratedStudySet(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sourceText: String,
    val mode: StudySetMode,
    val questions: List<StudyQuestion> = emptyList(),
    val cards: List<StudyFlashcard> = emptyList()
)
