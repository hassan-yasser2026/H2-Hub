package com.example.network

import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.ChatMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GeminiApiClient {
    private const val TAG = "GeminiApiClient"
    private val baseUrl: String = BuildConfig.SERVER_URL.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    // --- 429 (quota) handling helpers ---

    private const val DAILY_QUOTA_MESSAGE =
        "انتهت الحصة المجانية اليومية لمفتاح Gemini. لتشغيل التطبيق بدون حدود يجب تفعيل الفوترة (Billing) للمفتاح من Google AI Studio."
    private const val RATE_LIMIT_MESSAGE =
        "الخادم مشغول مؤقتاً (تم تجاوز حد الطلبات في الدقيقة). انتظر لحظات ثم أعد المحاولة."

    /** True when the 429 is the per-day free-tier quota — retrying is pointless. */
    private fun isDailyQuotaError(body: String): Boolean =
        body.contains("PerDay", ignoreCase = true)

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

                // Up to 2 attempts per model: transient per-minute 429s are retried
                // after the wait Google suggests; daily-quota 429s are not retried.
                var attempt = 0
                attemptsLoop@ while (attempt < 2) {
                    attempt++
                    val (code, bodyStr) = postJson(url, requestBodyJson)

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
                        if (attempt < 2) {
                            kotlinx.coroutines.delay((delaySec * 1000).toLong() + 500)
                            continue@attemptsLoop
                        }
                        lastError = RATE_LIMIT_MESSAGE
                    } else {
                        lastError = "خطأ في الاتصال بالخادم ($code). الرجاء المحاولة لاحقاً."
                    }
                    break@attemptsLoop
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during chat generate ($model)", e)
                lastError = "حدث خطأ غير متوقع: ${e.localizedMessage ?: e.message}"
            }
        }
        return@withContext lastError
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
                val (code, bodyStr) = postJson(url, requestBodyJson)

                if (code in 200..299) {
                    val text = parseCandidateText(bodyStr)
                    return@withContext text ?: "لم نتمكن من الحصول على رد من الذكاء الاصطناعي."
                }

                Log.e(TAG, "Multimodal request failed: Code $code, Body: $bodyStr")
                if (code == 429) {
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
                    if (isDailyQuotaError(bodyStr)) {
                        return@withContext DAILY_QUOTA_MESSAGE
                    }
                    val delaySec = parseRetryDelaySeconds(bodyStr)?.coerceAtMost(25.0) ?: 12.0
                    if (attempt < 2) {
                        kotlinx.coroutines.delay((delaySec * 1000).toLong() + 500)
                        continue
                    }
                    return@withContext RATE_LIMIT_MESSAGE
                }
                return@withContext "خطأ في الاتصال بالخادم ($code). الرجاء المحاولة لاحقاً."
            }
            return@withContext "لم نتمكن من الحصول على رد من الذكاء الاصطناعي."
        } catch (e: Exception) {
            Log.e(TAG, "Exception during multimodal generate", e)
            return@withContext "حدث خطأ غير متوقع: ${e.localizedMessage ?: e.message}"
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
