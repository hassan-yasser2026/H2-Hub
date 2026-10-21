package com.example.network

import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.ChatMessage
import com.example.data.GeneratedStudySet
import com.example.data.StudyFlashcard
import com.example.data.StudyQuestion
import com.example.data.StudySetMode
import com.example.data.SocraticProgress
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.google.firebase.auth.FirebaseAuth
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object GeminiApiClient {
    data class SocraticChatResult(val reply: String, val progress: SocraticProgress)
    data class ProductivitySlide(val title: String, val bullets: List<String>)
    data class AsyncChatStatus(val status: String, val reply: String?, val error: String?)
    data class DailyQuranVerse(val text: String, val reference: String)
    data class MemorySummary(
        val subject: String,
        val summary: String,
        val date: String
    )
    data class ConversationMemory(
        val recentSummaries: List<MemorySummary>,
        val weakPoints: List<String>,
        val longTermSummary: String,
        val summaryCount: Int
    )

    enum class FeedbackRating(val wireValue: String) {
        POSITIVE("positive"),
        NEGATIVE("negative"),
        INCORRECT("incorrect")
    }

    private const val TAG = "GeminiApiClient"
    private const val FEATURE_HEADER = "X-H2-Feature"
    private val baseUrl: String = BuildConfig.SERVER_URL.trimEnd('/')
    @Volatile
    private var thinkingModeEnabled = false

    fun setThinkingModeEnabled(enabled: Boolean) {
        thinkingModeEnabled = enabled
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(DeviceQuotaInterceptor())
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun generateConversationSummary(
        conversationId: String,
        messages: List<ChatMessage>
    ): JSONObject = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IOException("سجّل الدخول لحفظ ذاكرة محادثاتك.")
        val messageArray = JSONArray()
        val latestMessages = messages.takeLast(40)
        latestMessages.forEach { message ->
            if (message.role != "user" && message.role != "model") return@forEach
            val parsedVision = parseChatVisionMessage(message.content)
            val content = (parsedVision?.question ?: message.content).trim().take(3_000)
            if (content.isNotEmpty()) {
                messageArray.put(
                    JSONObject()
                        .put("role", message.role)
                        .put("content", content)
                )
            }
        }
        val body = JSONObject()
            .put("conversationId", conversationId)
            .put("lastMessageId", latestMessages.lastOrNull()?.id.orEmpty())
            .put("messages", messageArray)
        val (status, responseBody) = postJson("$baseUrl/api/memory/summarize", body)
        val response = runCatching { JSONObject(responseBody) }.getOrElse {
            throw IOException("خادم الذاكرة أعاد استجابة غير صالحة.", it)
        }
        if (status !in 200..299) {
            val error = response.optString("error").ifBlank { "تعذر تلخيص المحادثة." }
            throw IOException("HTTP $status: $error")
        }
        response
    }

    suspend fun fetchConversationMemory(): ConversationMemory = withContext(Dispatchers.IO) {
        if (FirebaseAuth.getInstance().currentUser == null) {
            return@withContext ConversationMemory(emptyList(), emptyList(), "", 0)
        }
        val request = Request.Builder()
            .url("$baseUrl/api/memory/context")
            .get()
            .build()
        val (status, responseBody) = client.newCall(request).execute().use { response ->
            response.code to (response.body?.string().orEmpty())
        }
        val response = runCatching { JSONObject(responseBody) }.getOrElse {
            throw IOException("خادم الذاكرة أعاد استجابة غير صالحة.", it)
        }
        if (status !in 200..299) {
            throw IOException("HTTP $status: ${response.optString("error", "تعذر تحميل الذاكرة.")}")
        }
        val summaries = response.optJSONArray("recentSummaries")
        val recentSummaries = buildList {
            if (summaries != null) {
                for (index in 0 until summaries.length()) {
                    val summary = summaries.optJSONObject(index) ?: continue
                    add(
                        MemorySummary(
                            subject = summary.optString("subject"),
                            summary = summary.optString("summary"),
                            date = summary.optString("date")
                        )
                    )
                }
            }
        }
        val weakPointsJson = response.optJSONArray("weakPoints")
        val weakPoints = buildList {
            if (weakPointsJson != null) {
                for (index in 0 until weakPointsJson.length()) {
                    weakPointsJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
        ConversationMemory(
            recentSummaries = recentSummaries,
            weakPoints = weakPoints,
            longTermSummary = response.optString("longTermSummary"),
            summaryCount = response.optInt("summaryCount")
        )
    }

    // --- 429 (quota) handling helpers ---

    private const val DAILY_QUOTA_MESSAGE =
        "انتهت الحصة المجانية لهذا اليوم. حاول مرة أخرى غداً أو تحقق من اشتراكك."
    private const val RATE_LIMIT_MESSAGE =
        "🕐 انتظر قليلاً ثم حاول مرة أخرى."
    private const val CONNECTION_ERROR_MESSAGE =
        "📶 تحقق من اتصالك بالإنترنت وحاول مرة أخرى."
    private const val BUSY_MESSAGE =
        "😅 حدثت مشكلة مؤقتة. حاول مرة أخرى بعد قليل."
    private const val MAX_NETWORK_ATTEMPTS = 3

    fun friendlyErrorMessage(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is SocketTimeoutException || it is java.util.concurrent.TimeoutException }) {
            return "⏱️ تأخرت الاستجابة. حاول مرة أخرى."
        }
        if (chain.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is NoRouteToHostException
            }) {
            return CONNECTION_ERROR_MESSAGE
        }

        val message = chain.mapNotNull { it.message }.firstOrNull().orEmpty()
        val status = Regex("""(?:HTTP\s*)?(\d{3})""", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.get(1)?.toIntOrNull()
        return friendlyErrorMessage(status, message)
    }

    internal fun friendlyErrorMessage(statusCode: Int?, serverMessage: String?): String {
        val message = serverMessage.orEmpty().trim()
        if (statusCode == 429) return RATE_LIMIT_MESSAGE
        if (statusCode == 404) return "😅 حدثت مشكلة بسيطة. حاول مرة أخرى."
        if (statusCode == 503) {
            if (message.contains("توليد الصور متوقفة")) return message
            return "🔧 نجري صيانة سريعة. حاول مرة أخرى بعد قليل."
        }
        if (statusCode != null && statusCode >= 500) {
            return "😅 هذه الميزة غير متاحة حالياً. حاول بعد قليل."
        }
        if (statusCode == 408 || message.contains("timeout", ignoreCase = true)) {
            return "⏱️ تأخرت الاستجابة. حاول مرة أخرى."
        }
        if (
            message.contains("failed to connect", ignoreCase = true) ||
            message.contains("unable to resolve host", ignoreCase = true) ||
            message.contains("network is unreachable", ignoreCase = true)
        ) {
            return CONNECTION_ERROR_MESSAGE
        }
        val technical = Regex(
            """\b(?:api|server|route|http|timeout|exception|failed|not found|internal error)\b""",
            RegexOption.IGNORE_CASE
        )
        return if (
            message.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.ARABIC } &&
            !technical.containsMatchIn(message) &&
            !Regex("""\b\d{3}\b""").containsMatchIn(message)
        ) {
            message
        } else {
            "😅 حدثت مشكلة. حاول مرة أخرى، أو تواصل معنا إذا استمرت."
        }
    }

    internal fun parseServerErrorMessage(body: String): String? {
        return try {
            JSONObject(body).optString("error").takeIf(String::isNotBlank)
        } catch (_: JSONException) {
            null
        }
    }

    /** True when the 429 is the per-day free-tier quota — retrying is pointless. */
    private fun isDailyQuotaError(body: String): Boolean {
        if (parseQuotaError(429, body) != null) return true
        val normalizedBody = body.lowercase()
        return normalizedBody.contains("perday") ||
            normalizedBody.contains("per_day") ||
            normalizedBody.contains("per day") ||
            normalizedBody.contains("per-day") ||
            normalizedBody.contains("daily quota") ||
            normalizedBody.contains("free tier") && normalizedBody.contains("day")
    }

    /** Parses Google's "Please retry in 12.3s" hint from the 429 error body. */
    private fun parseRetryDelaySeconds(body: String): Double? =
        "retry in ([0-9.]+)s".toRegex(RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Executes one POST and returns HTTP code + body. */
    private fun postJson(url: String, bodyJson: JSONObject, feature: String? = null): Pair<Int, String> {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = bodyJson.toString().toRequestBody(mediaType)
        val request = Request.Builder().url(url).post(requestBody)
            .apply { if (feature != null) header(FEATURE_HEADER, feature) }
            .build()
        client.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

        suspend fun extractProductivityDocument(
            context: android.content.Context,
            uri: android.net.Uri,
            fileName: String
        ): String = withContext(Dispatchers.IO) {
            if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
                throw IOException("عنوان خادم Smart Cat غير مضبوط.")
            }
            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 15 * 1024 * 1024) {
                        throw IOException("حجم الملف أكبر من الحد المسموح (15 ميجابايت).")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IOException("تعذر قراءة الملف المحدد.")
            if (bytes.isEmpty()) throw IOException("الملف المحدد فارغ.")

            val mediaType = resolver.getType(uri)?.toMediaType()
                ?: "application/octet-stream".toMediaType()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, bytes.toRequestBody(mediaType))
                .build()
            val request = Request.Builder()
                .url("$baseUrl/api/productivity/extract")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(responseBody)
                } catch (error: org.json.JSONException) {
                    Log.e(TAG, "Invalid document extraction response: HTTP ${response.code}", error)
                    throw IOException(BUSY_MESSAGE)
                }
                if (!response.isSuccessful) {
                    throw IOException(friendlyErrorMessage(
                        response.code,
                        json.optString("error").takeIf(String::isNotBlank)
                    ))
                }
                json.optString("text").takeIf(String::isNotBlank)
                    ?: throw IOException("لم نعثر على نص قابل للاستخدام في الملف.")
            }
        }

        suspend fun summarizeProductivityDocument(
            text: String,
            fileName: String,
            format: String
        ): String = withContext(Dispatchers.IO) {
            if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
                throw IOException("عنوان خادم Smart Cat غير مضبوط.")
            }
            if (text.isBlank()) throw IOException("لا يوجد نص لتلخيصه.")
            val payload = JSONObject()
                .put("documentText", text.take(120_000))
                .put("fileName", fileName.take(200))
                .put("format", format)
            val (status, body) = postJsonWithNetworkRetries("$baseUrl/api/summarize", payload)
                ?: throw IOException(CONNECTION_ERROR_MESSAGE)
            val json = try {
                JSONObject(body)
            } catch (error: org.json.JSONException) {
                Log.e(TAG, "Invalid summary response: HTTP $status", error)
                throw IOException(BUSY_MESSAGE)
            }
            if (status !in 200..299) {
                throw IOException(json.optString("error").takeIf(String::isNotBlank) ?: BUSY_MESSAGE)
            }
            json.optString("summary").takeIf(String::isNotBlank)
                ?: throw IOException("تعذر إنشاء ملخص صالح. حاول مرة أخرى.")
        }

        suspend fun generateProductivityPresentation(summary: String): List<ProductivitySlide> =
            withContext(Dispatchers.IO) {
                if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
                    throw IOException("عنوان خادم Smart Cat غير مضبوط.")
                }
                if (summary.isBlank()) throw IOException("أنشئ ملخصاً أولاً قبل إعداد العرض.")
                val payload = JSONObject()
                    .put("topic", summary.take(12_000))
                    .put("slidesCount", 5)
                val (status, body) = postJsonWithNetworkRetries("$baseUrl/api/presentation", payload)
                    ?: throw IOException(CONNECTION_ERROR_MESSAGE)
                val json = try {
                    JSONObject(body)
                } catch (error: org.json.JSONException) {
                    Log.e(TAG, "Invalid presentation response: HTTP $status", error)
                    throw IOException(BUSY_MESSAGE)
                }
                if (status !in 200..299) {
                    throw IOException(json.optString("error").takeIf(String::isNotBlank) ?: BUSY_MESSAGE)
                }
                val slidesJson = json.optJSONArray("slides")
                    ?: throw IOException("استجابة مخطط العرض غير مكتملة.")
                if (slidesJson.length() !in 1..12) throw IOException("عدد شرائح العرض غير صالح.")
                (0 until slidesJson.length()).map { index ->
                    val slide = slidesJson.getJSONObject(index)
                    val title = slide.optString("title").trim()
                    val bulletsJson = slide.optJSONArray("bullets")
                        ?: throw IOException("إحدى الشرائح لا تحتوي نقاطاً.")
                    val bullets = (0 until bulletsJson.length()).map { bulletsJson.getString(it).trim() }
                    if (title.isBlank() || bullets.size !in 3..5 || bullets.any(String::isBlank)) {
                        throw IOException("بيانات إحدى الشرائح غير مكتملة.")
                    }
                    ProductivitySlide(title, bullets)
                }
            }

    suspend fun submitChatFeedback(
        responseId: String,
        question: String,
        reply: String,
        rating: FeedbackRating,
        note: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty()) return@withContext false
        val payload = JSONObject()
            .put("responseId", responseId)
            .put("question", question)
            .put("reply", reply)
            .put("rating", rating.wireValue)
            .put("note", note)
        try {
            val (status, body) = postJson("$baseUrl/api/feedback", payload)
            if (status in 200..299) {
                true
            } else {
                Log.w(TAG, "Feedback submission failed with HTTP $status: $body")
                false
            }
        } catch (error: IOException) {
            Log.w(TAG, "Feedback submission failed due to a network error", error)
            false
        }
    }

    suspend fun generateStudySet(
        sourceText: String,
        mode: StudySetMode,
        count: Int = 5
    ): GeneratedStudySet = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty()) throw IOException("عنوان خادم Smart Cat غير مضبوط.")
        if (sourceText.isBlank()) throw IOException("لا يوجد محتوى كافٍ لإنشاء المراجعة.")
        if (count !in 5..10) throw IOException("عدد الأسئلة أو الكروت يجب أن يكون من 5 إلى 10.")

        val payload = JSONObject()
            .put("sourceText", sourceText.take(12_000))
            .put("mode", mode.wireValue)
            .put("count", count)
        val (status, body) = postJsonWithNetworkRetries(
            "$baseUrl/api/generate-quiz",
            payload
        ) ?: throw IOException(CONNECTION_ERROR_MESSAGE)
        if (status !in 200..299) {
            val errorMessage = try {
                JSONObject(body).optString("error").takeIf(String::isNotBlank)
            } catch (_: org.json.JSONException) {
                null
            }
            Log.w(TAG, "Study set generation failed with HTTP $status: $body")
            throw IOException(errorMessage ?: BUSY_MESSAGE)
        }

        try {
            val response = JSONObject(body)
            val title = response.getString("title").trim()
            if (title.isEmpty()) throw IOException("عنوان المحتوى المولد فارغ.")
            val questions = mutableListOf<StudyQuestion>()
            val cards = mutableListOf<StudyFlashcard>()
            if (mode == StudySetMode.QUIZ) {
                val items = response.getJSONArray("questions")
                if (items.length() !in 5..10) throw IOException("عدد أسئلة الاختبار غير صالح.")
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val optionsJson = item.getJSONArray("options")
                    if (optionsJson.length() != 4) throw IOException("يجب أن يحتوي كل سؤال على أربعة اختيارات.")
                    val options = (0 until optionsJson.length()).map { optionsJson.getString(it).trim() }
                    val answerIndex = item.getInt("answerIndex")
                    val question = item.getString("question").trim()
                    val explanation = item.getString("explanation").trim()
                    if (
                        question.isEmpty() || options.any(String::isEmpty) ||
                        answerIndex !in options.indices || explanation.isEmpty()
                    ) {
                        throw IOException("بيانات أحد أسئلة الاختبار غير مكتملة.")
                    }
                    questions += StudyQuestion(question, options, answerIndex, explanation)
                }
            } else {
                val items = response.getJSONArray("cards")
                if (items.length() !in 5..10) throw IOException("عدد كروت المراجعة غير صالح.")
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val front = item.getString("front").trim()
                    val back = item.getString("back").trim()
                    if (front.isEmpty() || back.isEmpty()) {
                        throw IOException("بيانات أحد كروت المراجعة غير مكتملة.")
                    }
                    cards += StudyFlashcard(front, back)
                }
            }
            GeneratedStudySet(
                title = title,
                sourceText = sourceText.take(12_000),
                mode = mode,
                questions = questions,
                cards = cards
            )
        } catch (error: org.json.JSONException) {
            Log.e(TAG, "Invalid study set response: $body", error)
            throw IOException("استجابة إنشاء المراجعة غير صالحة.")
        }
    }

    private suspend fun postJsonWithNetworkRetries(
        url: String,
        bodyJson: JSONObject,
        feature: String? = null,
    ): Pair<Int, String>? {
        for (attempt in 1..MAX_NETWORK_ATTEMPTS) {
            try {
                return postJson(url, bodyJson, feature)
            } catch (e: QuotaExceededException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Temporary network failure on attempt $attempt/$MAX_NETWORK_ATTEMPTS", e)
                if (attempt == MAX_NETWORK_ATTEMPTS) {
                    throw IOException(friendlyErrorMessage(e), e)
                }
                delay(attempt * 1_000L)
            }
        }
        return null
    }

    suspend fun submitAsyncChatRequest(requestJson: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
                throw IOException("عنوان خادم Smart Cat غير مضبوط.")
            }
            val payload = try {
                JSONObject(requestJson)
            } catch (error: org.json.JSONException) {
                throw IOException("بيانات طلب المحادثة غير صالحة.", error)
            }
            postJsonWithNetworkRetries("$baseUrl/api/chat-async", payload)
                ?: throw IOException(CONNECTION_ERROR_MESSAGE)
        }

    suspend fun getAsyncChatStatus(jobId: String, deviceToken: String): AsyncChatStatus =
        withContext(Dispatchers.IO) {
            val response = postJsonWithNetworkRetries(
                "$baseUrl/api/chat-async/$jobId/result",
                JSONObject().put("deviceToken", deviceToken)
            ) ?: throw IOException(CONNECTION_ERROR_MESSAGE)
            val (statusCode, body) = response
            val json = try {
                JSONObject(body)
            } catch (error: org.json.JSONException) {
                throw IOException("استجابة حالة المحادثة غير صالحة.", error)
            }
            if (statusCode !in 200..299) {
                throw IOException(
                    friendlyErrorMessage(
                        statusCode,
                        json.optString("error").takeIf(String::isNotBlank)
                    )
                )
            }
            AsyncChatStatus(
                status = json.optString("status"),
                reply = json.optString("reply").takeIf(String::isNotBlank),
                error = json.optString("error").takeIf(String::isNotBlank)?.let {
                    friendlyErrorMessage(IOException(it))
                }
            )
        }

    suspend fun getDailyQuranVerse(): DailyQuranVerse = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            throw IOException("عنوان خادم Smart Cat غير مضبوط.")
        }
        val request = Request.Builder().url("$baseUrl/api/quran/daily-verse").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("تعذر جلب آية اليوم (${response.code}).")
            }
            val json = try {
                JSONObject(body)
            } catch (error: org.json.JSONException) {
                throw IOException("استجابة آية اليوم غير صالحة.", error)
            }
            val text = json.optString("text").trim()
            val reference = json.optString("reference").trim()
            if (text.isBlank() || reference.isBlank()) {
                throw IOException("بيانات آية اليوم غير مكتملة.")
            }
            DailyQuranVerse(text, reference)
        }
    }

    /**
     * Extracts the answer text from a generateContent response body,
     * skipping "thought" parts produced by thinking models and
     * concatenating all remaining text parts.
     */
    private fun parseCandidateText(bodyStr: String): String? {
        return try {
            val responseJson = JSONObject(bodyStr)
            val candidates = responseJson.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val contentObj = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = contentObj.optJSONArray("parts") ?: return null
            val builder = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optBoolean("thought", false)) continue
                val text = part.optString("text")
                if (text.isNotEmpty()) builder.append(text)
            }
            builder.toString().trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse candidate text", e)
            null
        }
    }

    private fun isMissingApiRoute(statusCode: Int, responseBody: String): Boolean {
        if (statusCode != 404) return false
        return try {
            JSONObject(responseBody).optString("error")
                .contains("API route not found", ignoreCase = true)
        } catch (_: org.json.JSONException) {
            responseBody.contains("API route not found", ignoreCase = true)
        }
    }

    private suspend fun generateLegacyChatFallback(
        history: List<ChatMessage>,
        systemInstruction: String,
        reason: String,
        useThinking: Boolean = thinkingModeEnabled,
        feature: String = "chat"
    ): String {
        Log.w(TAG, "$reason endpoint is missing on the server; using the legacy server-side Gemini proxy")
        val fallbackInstruction = """
            $systemInstruction

            IMPORTANT SERVICE LIMITATION: The server's course-retrieval endpoint is unavailable.
            Do not claim that you searched H2 Hub course files or cite course sources.
            Help the student using general knowledge, clearly noting uncertainty when appropriate.
        """.trimIndent()
        return generateChatResponse(history, fallbackInstruction, useThinking, feature)
    }

    /**
     * Generates a chat response from Gemini.
     * Uses the Gemini model endpoint verified for the deployed server.
     */
    suspend fun generateChatResponse(
        history: List<ChatMessage>,
        systemInstruction: String,
        useThinking: Boolean = thinkingModeEnabled,
        feature: String = "chat"
    ): String = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            return@withContext "الخدمة غير مهيأة حالياً. حاول مرة أخرى لاحقاً."
        }

        val modelsToTry = if (useThinking) {
            listOf("gemini-3.1-pro-preview", "gemini-3.8-flash")
        } else {
            listOf("gemini-3.8-flash")
        }
        var lastError = "لم نتمكن من الحصول على رد من الذكاء الاصطناعي."

        for (model in modelsToTry) {
            val url = "$baseUrl/v1beta/models/$model:generateContent"
            try {
                val requestBodyJson = JSONObject()

                // Contents array
                val contentsArray = JSONArray()
                history.takeLast(30).forEach { msg ->
                    val contentObj = JSONObject()
                    contentObj.put("role", if (msg.role == "user") "user" else "model")

                    val partsArray = JSONArray()
                    val partObj = JSONObject()
                    partObj.put("text", msg.content)
                    partsArray.put(partObj)

                    contentObj.put("parts", partsArray)
                    contentsArray.put(contentObj)
                }
                requestBodyJson.put("contents", contentsArray)

                // System Instruction
                if (systemInstruction.isNotEmpty()) {
                    val sysInstructObj = JSONObject()
                    val partsArray = JSONArray()
                    val partObj = JSONObject()
                    partObj.put("text", systemInstruction)
                    partsArray.put(partObj)
                    sysInstructObj.put("parts", partsArray)
                    requestBodyJson.put("systemInstruction", sysInstructObj)
                }

                // Generation Config
                val generationConfig = JSONObject()
                generationConfig.put("maxOutputTokens", 4096)
                if (useThinking && model == "gemini-3.1-pro-preview") {
                    generationConfig.put(
                        "thinkingConfig",
                        JSONObject().put("thinkingLevel", "HIGH")
                    )
                }
                requestBodyJson.put("generationConfig", generationConfig)

                // Retry transient provider overloads, but never retry exhausted daily quota.
                var attempt = 0
                attemptsLoop@ while (attempt < 3) {
                    attempt++
                    val response = postJsonWithNetworkRetries(url, requestBodyJson, feature)
                    if (response == null) {
                        lastError = CONNECTION_ERROR_MESSAGE
                        break@attemptsLoop
                    }
                    val (code, bodyStr) = response

                    if (code in 200..299) {
                        val text = parseCandidateText(bodyStr)
                        if (text != null) {
                            return@withContext text
                        }
                        lastError = "لم نتمكن من الحصول على رد من الذكاء الاصطناعي."
                        break@attemptsLoop
                    }

                    Log.e(TAG, "Request failed for $model: Code $code, Body: $bodyStr")
                    if (code == 429) {
                        if (isDailyQuotaError(bodyStr)) {
                            lastError = parseQuotaError(429, bodyStr)?.message ?: DAILY_QUOTA_MESSAGE
                            break@attemptsLoop // try fallback model if any
                        }
                        val delaySec = parseRetryDelaySeconds(bodyStr)?.coerceAtMost(25.0) ?: 12.0
                        if (attempt < 3) {
                            kotlinx.coroutines.delay((delaySec * 1000).toLong() + 500)
                            continue@attemptsLoop
                        }
                        lastError = RATE_LIMIT_MESSAGE
                    } else if (code == 408 || code == 500 || code == 502 || code == 503 || code == 504) {
                        if (attempt < 3) {
                            delay(attempt * 2_000L)
                            continue@attemptsLoop
                        }
                        lastError = BUSY_MESSAGE
                    } else {
                        lastError = friendlyErrorMessage(code, parseServerErrorMessage(bodyStr))
                    }
                    break@attemptsLoop
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception during chat generate ($model)", e)
                lastError = friendlyErrorMessage(e)
            }
        }
        return@withContext lastError
    }

    suspend fun generateRagChatResponse(
        history: List<ChatMessage>,
        systemInstruction: String,
        forceFullSolution: Boolean = false,
        useThinking: Boolean = thinkingModeEnabled
    ): String = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            return@withContext "الخدمة غير مهيأة حالياً. حاول مرة أخرى لاحقاً."
        }
        val latestQuestion = history.lastOrNull { it.role == "user" }?.content?.trim()
        if (latestQuestion.isNullOrEmpty()) {
            return@withContext "اكتب سؤالك أولاً."
        }

        val body = JSONObject()
            .put("message", latestQuestion)
            .put("systemInstruction", systemInstruction)
            .put("forceFullSolution", forceFullSolution)
            .put("useThinking", useThinking)
            .put(
                "history",
                JSONArray().apply {
                    history.takeLast(8).forEach { message ->
                        put(
                            JSONObject()
                                .put("role", message.role)
                                .put("text", message.content)
                        )
                    }
                }
            )
        val response = postJsonWithNetworkRetries("$baseUrl/api/chat-rag", body)
            ?: return@withContext CONNECTION_ERROR_MESSAGE
        val (code, responseBody) = response
        val responseJson = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse RAG response", e)
            return@withContext BUSY_MESSAGE
        }
        if (code !in 200..299) {
            Log.e(TAG, "RAG request failed: Code $code, Body: $responseBody")
            return@withContext friendlyErrorMessage(
                code,
                responseJson.optString("error").takeIf(String::isNotBlank)
            )
        }

        val reply = responseJson.optString("reply").trim()
        if (reply.isBlank()) return@withContext BUSY_MESSAGE
        val sources = responseJson.optJSONArray("sources") ?: return@withContext reply
        if (sources.length() == 0) return@withContext reply

        val sourceLines = buildList {
            for (index in 0 until sources.length()) {
                val source = sources.optJSONObject(index) ?: continue
                val reference = source.optInt("reference", index + 1)
                val label = source.optString("label").takeIf(String::isNotBlank)
                    ?: source.optString("fileName").takeIf(String::isNotBlank)
                    ?: continue
                add("[$reference] $label")
            }
        }
        if (sourceLines.isEmpty()) reply else "$reply\n\nالمصادر:\n${sourceLines.joinToString("\n")}"
    }

    private fun chatToolBody(history: List<ChatMessage>, systemInstruction: String): JSONObject? {
        val latestQuestion = history.lastOrNull { it.role == "user" }?.content?.trim()
        if (latestQuestion.isNullOrEmpty()) return null
        return JSONObject()
            .put("message", latestQuestion)
            .put("systemInstruction", systemInstruction)
            .put(
                "history",
                JSONArray().apply {
                    history.takeLast(8).forEach { message ->
                        put(JSONObject().put("role", message.role).put("text", message.content))
                    }
                }
            )
    }

    private suspend fun postChatTool(path: String, body: JSONObject): JSONObject {
        val (code, responseBody) = postJsonWithNetworkRetries("$baseUrl$path", body)
            ?: return JSONObject().put("error", CONNECTION_ERROR_MESSAGE)
        val json = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $path response", e)
            return JSONObject().put("error", BUSY_MESSAGE)
        }
        if (code !in 200..299) {
            Log.e(TAG, "$path failed: Code $code, Body: $responseBody")
            val message = friendlyErrorMessage(
                code,
                json.optString("error").takeIf(String::isNotBlank)
            )
            return JSONObject().put("error", message)
        }
        return json
    }

    data class ToolChatResult(
        val reply: String,
        val sources: List<com.example.data.ChatSource> = emptyList(),
        val thinkingSeconds: Int? = null
    )
    data class GeneratedImageResult(
        val imageUrl: String,
        val model: String,
        val safetyWarning: String?,
        val used: Int,
        val limit: Int,
        val paid: Boolean
    )
    data class ImageDescriptionSpeech(
        val description: String,
        val audioUrl: String?
    )
    data class SynthesizedSpeech(
        val audio: String,
        val mimeType: String
    )

    suspend fun isImageGenerationEnabled(): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            return@withContext false
        }
        val request = Request.Builder()
            .url("$baseUrl/api/feature-flags")
            .get()
            .build()
        val flagsClient = client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        try {
            flagsClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Feature-flag request failed: HTTP ${response.code}")
                    return@withContext false
                }
                JSONObject(body).optBoolean("imageGenerationEnabled", false)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not read image-generation feature flag; keeping it disabled.", error)
            false
        }
    }

    suspend fun generateImage(prompt: String): GeneratedImageResult = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            throw IOException("عنوان خادم Smart Cat غير مضبوط.")
        }
        val deviceId = ApiDeviceIdentity.deviceId
            ?: throw IOException("تعذر تحديد جهازك. أعد فتح التطبيق وحاول مرة أخرى.")
        val body = JSONObject()
            .put("prompt", prompt.trim().take(1_000))
            .put("deviceId", deviceId)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/generate-image")
            .post(body)
            .build()
        val imageClient = client.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(190, TimeUnit.SECONDS)
            .build()
        imageClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val json = try {
                JSONObject(responseBody)
            } catch (error: org.json.JSONException) {
                Log.e(TAG, "Invalid image-generation response: HTTP ${response.code}", error)
                throw IOException(BUSY_MESSAGE)
            }
            if (!response.isSuccessful) {
                parseQuotaError(response.code, responseBody)?.let(QuotaEvents::publish)
                throw IOException(friendlyErrorMessage(
                    response.code,
                    json.optString("error").takeIf(String::isNotBlank)
                ))
            }
            val imageUrl = json.optString("imageUrl")
            if (!imageUrl.startsWith("https://")) {
                throw IOException("تعذر تجهيز الصورة بأمان. حاول مرة أخرى.")
            }
            val model = json.optString("model").ifBlank { "black-forest-labs/flux-schnell" }
            if (model !in setOf("black-forest-labs/flux-schnell", "ideogram-ai/ideogram-v2")) {
                throw IOException("تعذر تجهيز الصورة. حاول مرة أخرى.")
            }
            val usage = json.optJSONObject("usage")
            GeneratedImageResult(
                imageUrl = imageUrl,
                model = model,
                safetyWarning = json.optString("safetyWarning").takeIf(String::isNotBlank),
                used = usage?.optInt("used") ?: 0,
                limit = usage?.optInt("limit") ?: 0,
                paid = usage?.optBoolean("paid") ?: false
            )
        }
    }

    suspend fun describeImage(imageUrl: String): ImageDescriptionSpeech = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            throw IOException("الخدمة غير متاحة حالياً. حاول مرة أخرى بعد قليل.")
        }
        val body = JSONObject()
            .put("imageUrl", imageUrl)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/describe-image")
            .post(body)
            .build()
        val speechClient = client.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(100, TimeUnit.SECONDS)
            .build()
        try {
            speechClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(responseBody)
                } catch (error: org.json.JSONException) {
                    Log.e(TAG, "Invalid image-description response: HTTP ${response.code}", error)
                    throw IOException(BUSY_MESSAGE, error)
                }
                if (!response.isSuccessful) {
                    throw IOException(friendlyErrorMessage(
                        response.code,
                        json.optString("error").takeIf(String::isNotBlank)
                    ))
                }
                val description = json.optString("description").trim()
                if (description.isBlank()) throw IOException(BUSY_MESSAGE)
                ImageDescriptionSpeech(
                    description = description,
                    audioUrl = json.optString("audioUrl").takeIf {
                        it.startsWith("data:audio/wav;base64,") ||
                            it.startsWith("data:audio/mpeg;base64,")
                    }
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw IOException(friendlyErrorMessage(error), error)
        }
    }

    suspend fun synthesizeSpeech(text: String, femaleVoice: Boolean): SynthesizedSpeech =
        withContext(Dispatchers.IO) {
            if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
                throw IOException("خدمة الصوت غير متاحة حالياً.")
            }
            val body = JSONObject()
                .put("text", text)
                .put("voice", if (femaleVoice) "ar-XA-Wavenet-C" else "ar-XA-Wavenet-B")
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/tts")
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    val json = try {
                        JSONObject(responseBody)
                    } catch (error: org.json.JSONException) {
                        throw IOException(BUSY_MESSAGE, error)
                    }
                    if (!response.isSuccessful) {
                        throw IOException(friendlyErrorMessage(
                            response.code,
                            json.optString("error").takeIf(String::isNotBlank)
                        ))
                    }
                    val audio = json.optString("audio")
                    val mimeType = json.optString("mimeType")
                    if (audio.isBlank() || mimeType !in setOf("audio/wav", "audio/mpeg")) {
                        throw IOException(BUSY_MESSAGE)
                    }
                    SynthesizedSpeech(audio, mimeType)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                throw IOException(friendlyErrorMessage(error), error)
            }
        }

    private suspend fun runChatTool(
        path: String,
        history: List<ChatMessage>,
        systemInstruction: String
    ): ToolChatResult = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            return@withContext ToolChatResult("الخدمة غير مهيأة حالياً. حاول مرة أخرى لاحقاً.")
        }
        val body = chatToolBody(history, systemInstruction)
            ?: return@withContext ToolChatResult("اكتب سؤالك أولاً.")
        val json = postChatTool(path, body)
        json.optString("error").takeIf(String::isNotBlank)?.let { return@withContext ToolChatResult(it) }
        val reply = json.optString("reply").trim()
        if (reply.isBlank()) return@withContext ToolChatResult(BUSY_MESSAGE)
        val array = json.optJSONArray("sources")
        val sources = buildList {
            if (array != null) {
                for (index in 0 until array.length()) {
                    val source = array.optJSONObject(index) ?: continue
                    val url = source.optString("url").takeIf(String::isNotBlank) ?: continue
                    add(
                        com.example.data.ChatSource(
                            source.optString("title").ifBlank { url },
                            url,
                            source.optInt("reference").takeIf {
                                source.has("reference") && it > 0
                            }
                        )
                    )
                }
            }
        }
        ToolChatResult(
            reply = reply,
            sources = sources,
            thinkingSeconds = json.optInt("thinkingSeconds").takeIf {
                json.has("thinkingSeconds") && it > 0
            }
        )
    }

    suspend fun generateWebSearchResponse(history: List<ChatMessage>, systemInstruction: String) =
        runChatTool("/api/chat-web-search", history, systemInstruction)

    suspend fun generateSearchThinkingResponse(history: List<ChatMessage>, systemInstruction: String) =
        runChatTool("/api/chat-search-thinking", history, systemInstruction)

    suspend fun generateThinkingResponse(history: List<ChatMessage>, systemInstruction: String) =
        runChatTool("/api/chat-thinking", history, systemInstruction)

    suspend fun generateSocraticChatResponse(
        history: List<ChatMessage>,
        systemInstruction: String,
        progress: SocraticProgress,
        useThinking: Boolean = thinkingModeEnabled
    ): SocraticChatResult? = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            return@withContext SocraticChatResult("الخدمة غير مهيأة حالياً. حاول مرة أخرى لاحقاً.", progress)
        }
        val latestQuestion = history.lastOrNull { it.role == "user" }?.content?.trim()
        if (latestQuestion.isNullOrEmpty()) {
            return@withContext SocraticChatResult("اكتب سؤالك أولاً.", progress)
        }
        val body = JSONObject()
            .put("message", latestQuestion)
            .put("systemInstruction", systemInstruction)
            .put("useThinking", useThinking)
            .put(
                "progress",
                JSONObject()
                    .put("step", progress.step)
                    .put("totalSteps", progress.totalSteps)
                    .put("correctStreak", progress.correctStreak)
                    .put("wrongStreak", progress.wrongStreak)
                    .put("difficultyLevel", progress.difficultyLevel)
                    .put("awaitingAnswer", progress.awaitingAnswer)
                    .put("complete", progress.complete)
                    .put("originalQuestion", progress.originalQuestion.take(8_000))
            )
            .put(
                "history",
                JSONArray().apply {
                    history.takeLast(12).forEach { message ->
                        put(JSONObject()
                            .put("role", message.role)
                            .put("text", message.content.take(8_000)))
                    }
                }
            )

        val response = postJsonWithNetworkRetries("$baseUrl/api/chat-socratic", body)
            ?: return@withContext SocraticChatResult(CONNECTION_ERROR_MESSAGE, progress)
        val (code, responseBody) = response
        if (isMissingApiRoute(code, responseBody)) {
            val fallbackInstruction = """
                $systemInstruction

                The dedicated Socratic server endpoint is unavailable. Continue tutoring Socratically:
                ask one short guiding question at a time, do not reveal the complete solution unless
                the student explicitly asks for it, and use the conversation history to assess answers.
            """.trimIndent()
            val fallbackReply = generateLegacyChatFallback(
                history,
                fallbackInstruction,
                "Socratic chat",
                useThinking
            )
            return@withContext SocraticChatResult(fallbackReply, progress)
        }
        val responseJson = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Socratic response", e)
            return@withContext SocraticChatResult(BUSY_MESSAGE, progress)
        }
        if (code !in 200..299) {
            Log.e(TAG, "Socratic request failed: Code $code, Body: $responseBody")
            return@withContext SocraticChatResult(
                friendlyErrorMessage(
                    code,
                    responseJson.optString("error").takeIf(String::isNotBlank)
                ),
                progress
            )
        }
        val reply = responseJson.optString("reply").trim()
        if (reply.isBlank()) return@withContext SocraticChatResult(BUSY_MESSAGE, progress)
        val progressJson = responseJson.optJSONObject("progress")
            ?: return@withContext SocraticChatResult(reply, progress)
        val updatedProgress = progress.copy(
            step = progressJson.optInt("step", progress.step).coerceIn(0, 20),
            totalSteps = progressJson.optInt("totalSteps", progress.totalSteps).coerceIn(1, 20),
            correctStreak = progressJson.optInt("correctStreak", progress.correctStreak).coerceIn(0, 2),
            wrongStreak = progressJson.optInt("wrongStreak", progress.wrongStreak).coerceIn(0, 1),
            difficultyLevel = progressJson.optInt("difficultyLevel", progress.difficultyLevel).coerceIn(1, 5),
            awaitingAnswer = progressJson.optBoolean("awaitingAnswer", progress.awaitingAnswer),
            complete = progressJson.optBoolean("complete", progress.complete)
        )
        val sources = responseJson.optJSONArray("sources")
        val sourceLines = buildList {
            if (sources != null) {
                for (index in 0 until sources.length()) {
                    val source = sources.optJSONObject(index) ?: continue
                    val reference = source.optInt("reference", index + 1)
                    val label = source.optString("label").takeIf(String::isNotBlank)
                        ?: source.optString("fileName").takeIf(String::isNotBlank)
                        ?: continue
                    add("[$reference] $label")
                }
            }
        }
        val replyWithSources = if (sourceLines.isEmpty()) reply
        else "$reply\n\nالمصادر:\n${sourceLines.joinToString("\n")}"
        SocraticChatResult(replyWithSources, updatedProgress)
    }

    suspend fun generateVisionResponse(
        imagePath: String,
        question: String,
        systemInstruction: String = ""
    ): String =
        withContext(Dispatchers.IO) {
            if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
                return@withContext "الخدمة غير مهيأة حالياً. حاول مرة أخرى لاحقاً."
            }
            val imageFile = java.io.File(imagePath)
            if (!imageFile.isFile || imageFile.length() !in 1..1_048_576) {
                return@withContext "الصورة غير متاحة أو تجاوزت الحجم المسموح (1 ميجابايت). أرفقها مرة أخرى."
            }

            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("question", question.take(1_000))
                    .apply {
                        if (systemInstruction.isNotBlank()) {
                            addFormDataPart("systemInstruction", systemInstruction.take(2_500))
                        }
                    }
                    .addFormDataPart(
                        "image",
                        imageFile.name,
                        imageFile.readBytes().toRequestBody("image/jpeg".toMediaType())
                    )
                    .build()
                val request = Request.Builder()
                    .url("$baseUrl/api/chat-vision")
                    .post(requestBody)
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val json = try {
                        JSONObject(body)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse vision response", e)
                        return@withContext BUSY_MESSAGE
                    }
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Vision request failed: ${response.code} $body")
                        return@withContext friendlyErrorMessage(
                            response.code,
                            json.optString("error").takeIf(String::isNotBlank)
                        )
                    }
                    json.optString("reply").takeIf(String::isNotBlank) ?: BUSY_MESSAGE
                }
            } catch (e: IOException) {
                Log.e(TAG, "Vision request failed", e)
                friendlyErrorMessage(e)
            }
        }

    suspend fun generateMultimodalResponse(
        prompt: String,
        systemInstruction: String,
        base64Data: String,
        mimeType: String,
        useThinking: Boolean = false,
        feature: String = "chat"
    ): String = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            return@withContext "الخدمة غير مهيأة حالياً. حاول مرة أخرى لاحقاً."
        }

        // Determine Model
        val model = if (useThinking) "gemini-3.1-pro-preview" else "gemini-3.8-flash"
        val url = "$baseUrl/v1beta/models/$model:generateContent"

        try {
            val requestBodyJson = JSONObject()

            // Contents array
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            contentObj.put("role", "user")

            val partsArray = JSONArray()
            
            // Text Part
            val textPart = JSONObject()
            textPart.put("text", prompt)
            partsArray.put(textPart)

            // Inline Data Part
            val inlineDataPart = JSONObject()
            val inlineData = JSONObject()
            inlineData.put("mimeType", mimeType)
            inlineData.put("data", base64Data)
            inlineDataPart.put("inlineData", inlineData)
            partsArray.put(inlineDataPart)

            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            requestBodyJson.put("contents", contentsArray)

            // System Instruction
            if (systemInstruction.isNotEmpty()) {
                val sysInstructObj = JSONObject()
                val sysPartsArray = JSONArray()
                val sysPartObj = JSONObject()
                sysPartObj.put("text", systemInstruction)
                sysPartsArray.put(sysPartObj)
                sysInstructObj.put("parts", sysPartsArray)
                requestBodyJson.put("systemInstruction", sysInstructObj)
            }

            // Generation Config
            val generationConfig = JSONObject()
            generationConfig.put("maxOutputTokens", 4096)
            if (useThinking) {
                val thinkingConfig = JSONObject()
                thinkingConfig.put("thinkingLevel", "HIGH")
                generationConfig.put("thinkingConfig", thinkingConfig)
            }
            requestBodyJson.put("generationConfig", generationConfig)

            var attempt = 0
            while (attempt < 2) {
                attempt++
                val response = postJsonWithNetworkRetries(url, requestBodyJson, feature)
                    ?: return@withContext CONNECTION_ERROR_MESSAGE
                val (code, bodyStr) = response

                if (code in 200..299) {
                    val text = parseCandidateText(bodyStr)
                    return@withContext text ?: "لم نتمكن من الحصول على رد من الذكاء الاصطناعي."
                }

                Log.e(TAG, "Multimodal request failed: Code $code, Body: $bodyStr")
                if (code == 429) {
                    if (isDailyQuotaError(bodyStr)) {
                        return@withContext parseQuotaError(429, bodyStr)?.message ?: DAILY_QUOTA_MESSAGE
                    }
                    // Thinking model unavailable (quota/limit) -> retry once with flash
                    if (useThinking) {
                        return@withContext generateMultimodalResponse(
                            prompt = prompt,
                            systemInstruction = systemInstruction,
                            base64Data = base64Data,
                            mimeType = mimeType,
                            useThinking = false,
                            feature = feature
                        )
                    }
                    val delaySec = parseRetryDelaySeconds(bodyStr)?.coerceAtMost(25.0) ?: 12.0
                    if (attempt < 2) {
                        kotlinx.coroutines.delay((delaySec * 1000).toLong() + 500)
                        continue
                    }
                    return@withContext RATE_LIMIT_MESSAGE
                }
                if (code == 408 || code == 500 || code == 502 || code == 503 || code == 504) {
                    return@withContext BUSY_MESSAGE
                }
                return@withContext friendlyErrorMessage(
                    code,
                    runCatching { JSONObject(bodyStr).optString("error") }.getOrNull()
                )
            }
            return@withContext "لم نتمكن من الحصول على رد من الذكاء الاصطناعي."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exception during multimodal generate", e)
            return@withContext friendlyErrorMessage(e)
        }
    }

    /**
     * Text to Speech using the server-side ElevenLabs proxy.
     */
    suspend fun generateElevenLabsSpeech(text: String, voiceId: String): String? = withContext(Dispatchers.IO) {
        try {
            val requestBodyJson = JSONObject()
            requestBodyJson.put("text", text)

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestBodyJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$baseUrl/api/tts/elevenlabs?voiceId=$voiceId")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val audio = JSONObject(response.body?.string().orEmpty()).optString("audio")
                    if (audio.isNotBlank()) return@withContext audio
                } else {
                    Log.e(TAG, "ElevenLabs TTS request failed: ${response.code} ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs TTS error", e)
        }
        return@withContext null
    }

    /**
     * Text to Speech using gemini-2.5-flash-preview-tts with ElevenLabs high-fidelity voice pre-emption
     */
    suspend fun generateSpeech(text: String, voiceName: String = "Kore"): String? = withContext(Dispatchers.IO) {
        // Pre-emptively try ElevenLabs if the voice is configured for Hasan or Jana and key exists
        val elevenLabsVoiceId = when (voiceName.lowercase()) {
            "hasan", "puck" -> "pNInz6obpgDQGcFmaJgB" // Adam's deep masculine voice ID
            "jana", "aoede" -> "21m00Tcm4TlvDq8ikWAM" // Rachel's sweet feminine voice ID
            else -> null
        }

        if (elevenLabsVoiceId != null) {
            val elevenAudio = generateElevenLabsSpeech(text, elevenLabsVoiceId)
            if (elevenAudio != null) {
                return@withContext elevenAudio
            }
        }

        // Fallback to Gemini High-Fi speech API if ElevenLabs is not set up or rate limited
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") return@withContext null

        val fallbackVoice = when (voiceName.lowercase()) {
            "hasan" -> "Puck"
            "jana" -> "Aoede"
            else -> voiceName
        }

        val url = "$baseUrl/v1beta/models/gemini-2.5-flash-preview-tts:generateContent"

        try {
            val requestBodyJson = JSONObject()

            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            val partsArray = JSONArray()
            val partObj = JSONObject()
            partObj.put("text", text)
            partsArray.put(partObj)
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            requestBodyJson.put("contents", contentsArray)

            val generationConfig = JSONObject()
            generationConfig.put("responseModalities", JSONArray(listOf("AUDIO")))

            val speechConfig = JSONObject()
            val voiceConfigObj = JSONObject()
            val prebuiltVoiceConfigObj = JSONObject()
            prebuiltVoiceConfigObj.put("voiceName", fallbackVoice)
            voiceConfigObj.put("prebuiltVoiceConfig", prebuiltVoiceConfigObj)
            speechConfig.put("voiceConfig", voiceConfigObj)
            generationConfig.put("speechConfig", speechConfig)

            requestBodyJson.put("generationConfig", generationConfig)

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestBodyJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val responseJson = JSONObject(bodyStr)
                    val candidates = responseJson.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val contentObjRes = firstCandidate.optJSONObject("content")
                        val parts = contentObjRes?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val inlineData = parts.getJSONObject(0).optJSONObject("inlineData")
                            if (inlineData != null) {
                                return@withContext inlineData.optString("data") // returns base64 string of the MP3 audio
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS generation error", e)
        }
        return@withContext null
    }
}
