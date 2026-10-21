package com.example.data

private const val MAX_SUMMARY_BULLETS = 5

fun summarizeConversationLocally(messages: List<LearningMessageEntity>): String? {
    val orderedMessages = messages.sortedBy(LearningMessageEntity::timestamp)
    val questions = orderedMessages
        .filter { it.role == "user" }
        .map { it.content.trim() }
        .filter(String::isNotBlank)
    if (questions.isEmpty()) return null

    val latestQuestion = questions.last().take(180)
    val latestAnswer = orderedMessages.lastOrNull { it.role == "model" }
        ?.content
        ?.trim()
        ?.split(Regex("(?<=[.!?؟])\\s+"), limit = 2)
        ?.firstOrNull()
        ?.take(220)
        ?.takeIf(String::isNotBlank)
    val earlierQuestion = questions.dropLast(1).lastOrNull()?.take(160)

    val bullets = buildList {
        add("الموضوع الرئيسي: $latestQuestion")
        if (latestAnswer != null) {
            add("شرح المساعد: $latestAnswer")
        } else {
            add("نقطة للمراجعة: متابعة السؤال الرئيسي خطوة بخطوة.")
        }
        if (earlierQuestion != null) {
            add("سؤال سابق في المحادثة: $earlierQuestion")
        } else {
            add("المحادثة محفوظة محلياً للرجوع إليها لاحقاً.")
        }
    }.distinct().take(MAX_SUMMARY_BULLETS)

    return bullets.joinToString(separator = "\n") { "• $it" }
}

fun isLocalMemoryRecallQuestion(text: String): Boolean =
    listOf(
        "فاكر", "تفتكر", "المحادثة السابقة", "المحادثة اللي فاتت", "قبل كده",
        "سألتك قبل", "قلتهولي قبل", "من محادثة سابقة", "what did i ask",
        "do you remember", "previous conversation", "earlier conversation"
    ).any { text.contains(it, ignoreCase = true) }

fun formatLocalMemoryRecall(summaries: List<String>, question: String = ""): String =
    summaries.maxByOrNull { summary ->
        val queryWords = question
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { (it.length > 2 || it.any(Char::isDigit)) && it !in localRecallStopWords }
            .toSet()
        val summaryWords = summary
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .toSet()
        queryWords.intersect(summaryWords).size
    }?.takeIf(String::isNotBlank)?.let {
        "من ملخص محادثة سابقة محفوظ على جهازك:\n$it"
    } ?: "لا يوجد ملخص لمحادثة سابقة محفوظ على هذا الجهاز حتى الآن."

private val localRecallStopWords = setOf(
    "فاكر", "تفتكر", "سألتك", "محادثة", "محادثة", "سابقة", "المحادثة",
    "قبل", "كده", "what", "did", "ask", "about", "remember", "previous"
)
