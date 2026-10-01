package com.example.network

import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.ChatMessage
import com.example.data.SocraticProgress
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

object GeminiApiClient {
    data class ChatSummaryTurn(val role: String, val text: String)
    data class SocraticChatResult(val reply: String, val progress: SocraticProgress)

    enum class FeedbackRating(val wireValue: String) {
        POSITIVE("positive"),
        NEGATIVE("negative"),
        INCORRECT("incorrect")
    }

    private const val TAG = "GeminiApiClient"
    private val baseUrl: String = BuildConfig.SERVER_URL.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    // --- 429 (quota) handling helpers ---

    private const val DAILY_QUOTA_MESSAGE =
        "استُنفدت الحصة المجانية اليومية لمفتاح Gemini. راجع حدود الاستخدام في Google AI Studio، أو انتظر تجدد الحصة. تفعيل الفوترة قد يتيح استخداماً مدفوعاً، لكنه لا يلغي جميع الحدود وقد يترتب عليه رسوم."
    private const val RATE_LIMIT_MESSAGE =
        "الخادم مشغول مؤقتاً (تم تجاوز حد الطلبات في الدقيقة). انتظر لحظات ثم أعد المحاولة."
    private const val CONNECTION_ERROR_MESSAGE =
        "تعذر الاتصال بخدمة الذكاء الاصطناعي. تحقق من اتصال الإنترنت وحاول مرة أخرى."
    private const val BUSY_MESSAGE =
        "خدمة الذكاء الاصطناعي مشغولة مؤقتاً. حاول مرة أخرى بعد قليل."
    private const val MAX_NETWORK_ATTEMPTS = 3

    /** True when the 429 is the per-day free-tier quota — retrying is pointless. */
    private fun isDailyQuotaError(body: String): Boolean {
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
    private fun postJson(url: String, bodyJson: JSONObject): Pair<Int, String> {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = bodyJson.toString().toRequestBody(mediaType)
        val request = Request.Builder().url(url).post(requestBody).build()
        client.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
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

    private suspend fun postJsonWithNetworkRetries(
        url: String,
        bodyJson: JSONObject,
    ): Pair<Int, String>? {
        for (attempt in 1..MAX_NETWORK_ATTEMPTS) {
            try {
                return postJson(url, bodyJson)
            } catch (e: IOException) {
                Log.w(TAG, "Temporary network failure on attempt $attempt/$MAX_NETWORK_ATTEMPTS", e)
                if (attempt == MAX_NETWORK_ATTEMPTS) return null
                delay(attempt * 1_000L)
            }
        }
        return null
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

    /**
     * Generates a chat response from Gemini.
     * Uses the Gemini model endpoint verified for the deployed server.
     */
    suspend fun generateChatResponse(
        history: List<ChatMessage>,
        systemInstruction: String,
        @Suppress("UNUSED_PARAMETER") useThinking: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            return@withContext "خطأ: لم يتم ضبط رابط الخادم."
        }

        val modelsToTry = listOf("gemini-3.8-flash")
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
                requestBodyJson.put("generationConfig", generationConfig)

                // Retry transient provider overloads, but never retry exhausted daily quota.
                var attempt = 0
                attemptsLoop@ while (attempt < 3) {
                    attempt++
                    val response = postJsonWithNetworkRetries(url, requestBodyJson)
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
                            lastError = DAILY_QUOTA_MESSAGE
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
                        lastError = "خطأ في الاتصال بالخادم ($code). الرجاء المحاولة لاحقاً."
                    }
                    break@attemptsLoop
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception during chat generate ($model)", e)
                lastError = CONNECTION_ERROR_MESSAGE
            }
        }
        return@withContext lastError
    }

    suspend fun generateRagChatResponse(
        history: List<ChatMessage>,
        systemInstruction: String,
        recentSummaries: List<String> = emptyList(),
        weakTopics: List<String> = emptyList(),
        forceFullSolution: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            return@withContext "خطأ: لم يتم ضبط رابط الخادم."
        }
        val latestQuestion = history.lastOrNull { it.role == "user" }?.content?.trim()
        if (latestQuestion.isNullOrEmpty()) {
            return@withContext "اكتب سؤالك أولاً."
        }

        val body = JSONObject()
            .put("message", latestQuestion)
            .put("systemInstruction", systemInstruction)
            .put("forceFullSolution", forceFullSolution)
            .put(
                "studentMemory",
                JSONObject()
                    .put(
                        "summaries",
                        JSONArray().apply {
                            var remainingWords = 500
                            recentSummaries.take(3).forEach { summary ->
                                val words = summary.trim().split(Regex("\\s+"))
                                    .filter(String::isNotBlank)
                                if (remainingWords <= 0) return@forEach
                                val limited = words.take(remainingWords).joinToString(" ")
                                if (limited.isNotBlank()) {
                                    put(limited)
                                    remainingWords -= limited.split(Regex("\\s+")).size
                                }
                            }
                        }
                    )
                    .put("weakTopics", JSONArray().apply {
                        weakTopics.take(10).forEach { put(it.take(120)) }
                    })
            )
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
            return@withContext responseJson.optString("error")
                .takeIf(String::isNotBlank)
                ?: BUSY_MESSAGE
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

    suspend fun generateSocraticChatResponse(
        history: List<ChatMessage>,
        systemInstruction: String,
        recentSummaries: List<String>,
        weakTopics: List<String>,
        progress: SocraticProgress
    ): SocraticChatResult? = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            return@withContext SocraticChatResult("خطأ: لم يتم ضبط رابط الخادم.", progress)
        }
        val latestQuestion = history.lastOrNull { it.role == "user" }?.content?.trim()
        if (latestQuestion.isNullOrEmpty()) {
            return@withContext SocraticChatResult("اكتب سؤالك أولاً.", progress)
        }
        val body = JSONObject()
            .put("message", latestQuestion)
            .put("systemInstruction", systemInstruction)
            .put(
                "studentMemory",
                JSONObject()
                    .put("summaries", JSONArray().apply {
                        var remainingWords = 500
                        recentSummaries.take(3).forEach { summary ->
                            if (remainingWords <= 0) return@forEach
                            val words = summary.trim().split(Regex("\\s+")).filter(String::isNotBlank)
                            val limited = words.take(remainingWords).joinToString(" ")
                            if (limited.isNotBlank()) {
                                put(limited)
                                remainingWords -= limited.split(Regex("\\s+")).size
                            }
                        }
                    })
                    .put("weakTopics", JSONArray().apply {
                        weakTopics.take(10).forEach { put(it.take(120)) }
                    })
            )
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
        val responseJson = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Socratic response", e)
            return@withContext SocraticChatResult(BUSY_MESSAGE, progress)
        }
        if (code !in 200..299) {
            Log.e(TAG, "Socratic request failed: Code $code, Body: $responseBody")
            return@withContext SocraticChatResult(
                responseJson.optString("error").takeIf(String::isNotBlank) ?: BUSY_MESSAGE,
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

    suspend fun generateChatSummary(turns: List<ChatSummaryTurn>): String? =
        withContext(Dispatchers.IO) {
            if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
                Log.e(TAG, "Cannot summarize chat: server URL is not configured")
                return@withContext null
            }
            val body = JSONObject().put(
                "history",
                JSONArray().apply {
                    var remainingCharacters = 30_000
                    val boundedTurns = turns.takeLast(80).asReversed().mapNotNull { turn ->
                        if (remainingCharacters <= 0) return@mapNotNull null
                        val text = turn.text.take(minOf(6_000, remainingCharacters))
                        remainingCharacters -= text.length
                        ChatSummaryTurn(turn.role, text)
                    }.asReversed()
                    boundedTurns.forEach { turn ->
                        put(JSONObject()
                            .put("role", turn.role)
                            .put("text", turn.text))
                    }
                }
            )
            val response = postJsonWithNetworkRetries("$baseUrl/api/chat-summary", body)
                ?: return@withContext null
            val (code, responseBody) = response
            val responseJson = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse chat summary response", e)
                return@withContext null
            }
            if (code !in 200..299) {
                Log.e(TAG, "Chat summary request failed: Code $code, Body: $responseBody")
                return@withContext null
            }
            responseJson.optString("summary").trim().takeIf(String::isNotBlank)
        }

    suspend fun generateVisionResponse(imagePath: String, question: String): String =
        withContext(Dispatchers.IO) {
            if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
                return@withContext "خطأ: لم يتم ضبط رابط الخادم."
            }
            val imageFile = java.io.File(imagePath)
            if (!imageFile.isFile || imageFile.length() !in 1..1_048_576) {
                return@withContext "الصورة غير متاحة أو تجاوزت الحجم المسموح (1 ميجابايت). أرفقها مرة أخرى."
            }

            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("question", question.take(4_000))
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
                        return@withContext json.optString("error")
                            .takeIf(String::isNotBlank)
                            ?: BUSY_MESSAGE
                    }
                    json.optString("reply").takeIf(String::isNotBlank) ?: BUSY_MESSAGE
                }
            } catch (e: IOException) {
                Log.e(TAG, "Vision request failed", e)
                CONNECTION_ERROR_MESSAGE
            }
        }

    suspend fun generateMultimodalResponse(
        prompt: String,
        systemInstruction: String,
        base64Data: String,
        mimeType: String,
        useThinking: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty() || baseUrl == "https://YOUR_RAILWAY_DOMAIN") {
            return@withContext "خطأ: لم يتم ضبط رابط الخادم."
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
                val response = postJsonWithNetworkRetries(url, requestBodyJson)
                    ?: return@withContext CONNECTION_ERROR_MESSAGE
                val (code, bodyStr) = response

                if (code in 200..299) {
                    val text = parseCandidateText(bodyStr)
                    return@withContext text ?: "لم نتمكن من الحصول على رد من الذكاء الاصطناعي."
                }

                Log.e(TAG, "Multimodal request failed: Code $code, Body: $bodyStr")
                if (code == 429) {
                    if (isDailyQuotaError(bodyStr)) {
                        return@withContext DAILY_QUOTA_MESSAGE
                    }
                    // Thinking model unavailable (quota/limit) -> retry once with flash
                    if (useThinking) {
                        return@withContext generateMultimodalResponse(
                            prompt = prompt,
                            systemInstruction = systemInstruction,
                            base64Data = base64Data,
                            mimeType = mimeType,
                            useThinking = false
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
                return@withContext "خطأ في الاتصال بالخادم ($code). الرجاء المحاولة لاحقاً."
            }
            return@withContext "لم نتمكن من الحصول على رد من الذكاء الاصطناعي."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exception during multimodal generate", e)
            return@withContext CONNECTION_ERROR_MESSAGE
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
