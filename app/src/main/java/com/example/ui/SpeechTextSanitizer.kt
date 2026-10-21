package com.example.ui

private val markdownImagePattern = Regex("!\\[[^]]*]\\([^)]*\\)")
private val markdownLinkPattern = Regex("\\[([^]]+)]\\([^)]*\\)")
private val htmlTagPattern = Regex("<[^>]*>")
private val urlPattern = Regex("(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
private val latexBlockPattern = Regex("[$][$][\\s\\S]*?[$][$]|\\\\\\[[\\s\\S]*?\\\\]")
private val latexInlinePattern = Regex("(?<![$])[$](?![$]).*?(?<![$])[$](?![$])|\\\\\\(.*?\\\\\\)")
private val codeBlockPattern = Regex("```[\\s\\S]*?```")
private val markdownPrefixPattern = Regex("(?m)^\\s*(?:#{1,6}\\s*|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)")
private val pausePunctuation = setOf('.', ',', '?', '!', ';', ':', '،', '؛', '؟', '\n', '\t')
private val unicodeMarkTypes = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt()
)

fun sanitizeSpeechText(input: String): String {
    val withoutImages = markdownImagePattern.replace(input, " ")
    val withoutLinks = markdownLinkPattern.replace(withoutImages, "$1")
    val withoutMarkup = codeBlockPattern
        .replace(withoutLinks, " ")
        .let { latexBlockPattern.replace(it, " ") }
        .let { latexInlinePattern.replace(it, " ") }
        .let { htmlTagPattern.replace(it, " ") }
        .let { urlPattern.replace(it, " ") }
    val normalized = markdownPrefixPattern.replace(withoutMarkup, "")
    val cleaned = buildString(normalized.length) {
        normalized.forEach { char ->
            when {
                char.isLetterOrDigit() || char.isWhitespace() ||
                    Character.getType(char) in unicodeMarkTypes -> append(char)
                char in pausePunctuation -> append(char)
                else -> append(' ')
            }
        }
    }
    return cleaned
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex(" *([,.;:!?،؛؟]) *"), "$1 ")
        .replace(Regex("([,.;:!?،؛؟])(?:\\s*\\1)+"), "$1")
        .replace(Regex("\\s*\\n\\s*"), "\n")
        .trim()
}

internal fun personaSpeechPitch(personaId: String?): Float = when (personaId) {
    "jana", "designer", "nanny" -> 1.08f
    null -> 1.0f
    else -> 0.94f
}

internal fun resolveSpeechPersonaId(
    personaId: String?,
    selectedVoiceId: String?
): String? = personaId ?: selectedVoiceId?.takeIf { it == "hasan" || it == "jana" }
