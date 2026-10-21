package com.example.ui

data class ClassifiedLearningTopic(val subject: String, val topic: String)

private data class TopicRule(
    val subject: String,
    val topic: String,
    val keywords: List<String>
)

private val topicRules = listOf(
    TopicRule("رياضيات", "المعادلات الجبرية", listOf("معادله", "معادلة", "equation", "solve for x", "مجهول", "الجبر")),
    TopicRule("رياضيات", "الكسور والنسب", listOf("كسر", "كسور", "نسبه", "نسبة", "fraction", "ratio", "proportion")),
    TopicRule("رياضيات", "الهندسة", listOf("هندسه", "هندسة", "geometry", "مثلث", "triangle", "مساحه", "مساحة")),
    TopicRule("رياضيات", "الدوال والرسوم البيانية", listOf("داله", "دالة", "function", "graph", "رسم بياني")),
    TopicRule("رياضيات", "التفاضل والتكامل", listOf("تفاضل", "تكامل", "derivative", "integral", "calculus")),
    TopicRule("فيزياء", "الحركة والقوى", listOf("نيوتن", "newton", "قوه", "قوة", "force", "سرعه", "سرعة", "velocity", "acceleration", "عجله", "عجلة")),
    TopicRule("فيزياء", "الكهرباء والدوائر", listOf("كهرباء", "electric", "دائره", "دائرة", "circuit", "تيار", "current", "مقاومه", "مقاومة")),
    TopicRule("كيمياء", "المعادلات الكيميائية", listOf("كيمياء", "chemical", "اتزان", "موازنه", "موازنة", "ذره", "ذرة", "atom", "molecule")),
    TopicRule("أحياء", "الخلية والطاقة", listOf("خلية", "الخليه", "cell", "ميتوكندريا", "mitochond", "تنفس خلوي", "photosynthesis", "البناء الضوئي")),
    TopicRule("لغة عربية", "النحو والإعراب", listOf("نحو", "اعراب", "إعراب", "فاعل", "مفعول", "مبتدأ", "خبر")),
    TopicRule("لغة إنجليزية", "القواعد والجمل", listOf("grammar", "tense", "sentence", "verb", "noun", "قواعد انجليزي", "قواعد إنجليزي")),
    TopicRule("لغة إنجليزية", "المفردات والترجمة", listOf("ترجم", "ترجمة", "معنى كلمة", "translate", "vocabulary", "meaning of")),
    TopicRule("حاسب آلي", "البرمجة والخوارزميات", listOf("كود", "برمجه", "برمجة", "programming", "code", "algorithm", "خطأ برمجي"))
)

private val acknowledgementPhrases = listOf(
    "فهمت", "تمام فهمت", "عرفت الحل", "حليت", "صح كده", "صح كذا", "شكرا فهمت",
    "understood", "i understand", "got it", "that makes sense", "i solved it"
)

fun isLearningAcknowledgement(text: String): Boolean =
    acknowledgementPhrases.any { text.contains(it, ignoreCase = true) }

fun classifyLearningQuestion(text: String): ClassifiedLearningTopic {
    val normalized = text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    topicRules.firstOrNull { rule ->
        rule.keywords.any { normalized.contains(it.lowercase()) }
    }?.let { return ClassifiedLearningTopic(it.subject, it.topic) }

    val subject = when {
        listOf("رياض", "math", "حساب").any(normalized::contains) -> "رياضيات"
        listOf("فيزياء", "physics").any(normalized::contains) -> "فيزياء"
        listOf("كيمياء", "chemistry").any(normalized::contains) -> "كيمياء"
        listOf("احياء", "أحياء", "biology").any(normalized::contains) -> "أحياء"
        listOf("انجليزي", "إنجليزي", "english").any(normalized::contains) -> "لغة إنجليزية"
        listOf("عربي", "لغة عربية", "arabic").any(normalized::contains) -> "لغة عربية"
        else -> "عام"
    }
    val topic = normalized.split(" ")
        .filter(String::isNotBlank)
        .filterNot { it in setOf("اشرح", "حل", "ازاي", "كيف", "ما", "هو", "هي", "the", "what", "how", "explain", "please") }
        .take(5)
        .joinToString(" ")
        .take(100)
        .ifBlank { "أسئلة عامة" }
    return ClassifiedLearningTopic(subject, topic)
}
