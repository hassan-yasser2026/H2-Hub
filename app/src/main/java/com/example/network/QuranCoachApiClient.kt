package com.example.network

import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class QuranSurah(
    val number: Int,
    val name: String,
    val englishName: String,
    val ayahCount: Int
)

data class QuranAyah(
    val numberInSurah: Int,
    val text: String
)

data class QuranRecitationResult(
    val transcript: String,
    val mistakes: List<Pair<String, String>>,
    val audioQuality: String,
    val performance: String,
    val tajweedTips: List<String>,
    val summary: String
)

object QuranCoachApiClient {
    private const val QURAN_API_BASE_URL = "https://api.alquran.cloud/v1"
    private val serverBaseUrl = BuildConfig.SERVER_URL.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .addInterceptor(DeviceQuotaInterceptor())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS)
        .build()

    suspend fun getSurahs(): List<QuranSurah> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$QURAN_API_BASE_URL/surah")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("تعذر تحميل قائمة السور (${response.code}).")
            }
            val data = JSONObject(body).optJSONArray("data")
                ?: throw IOException("استجابة مصدر القرآن لا تحتوي على قائمة السور.")
            buildList(data.length()) {
                for (index in 0 until data.length()) {
                    val surah = data.getJSONObject(index)
                    add(
                        QuranSurah(
                            number = surah.getInt("number"),
                            name = surah.getString("name"),
                            englishName = surah.getString("englishName"),
                            ayahCount = surah.getInt("numberOfAyahs")
                        )
                    )
                }
            }.also {
                if (it.size != 114) {
                    throw IOException("مصدر القرآن لم يُرجع السور الـ114 كاملة.")
                }
            }
        }
    }

    suspend fun getAyahs(surahNumber: Int): List<QuranAyah> = withContext(Dispatchers.IO) {
        require(surahNumber in 1..114) { "رقم السورة غير صالح." }
        val request = Request.Builder()
            .url("$QURAN_API_BASE_URL/surah/$surahNumber")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("تعذر تحميل آيات السورة (${response.code}).")
            }
            val data = JSONObject(body).optJSONObject("data")
                ?: throw IOException("استجابة مصدر القرآن غير مكتملة.")
            val ayahs = data.optJSONArray("ayahs")
                ?: throw IOException("استجابة مصدر القرآن لا تحتوي على الآيات.")
            buildList(ayahs.length()) {
                for (index in 0 until ayahs.length()) {
                    val ayah = ayahs.getJSONObject(index)
                    add(
                        QuranAyah(
                            numberInSurah = ayah.getInt("numberInSurah"),
                            text = ayah.getString("text").trim()
                        )
                    )
                }
            }
        }
    }

    suspend fun getRecitationAudioUrl(surahNumber: Int, ayahNumber: Int, recitationId: Int): String =
        withContext(Dispatchers.IO) {
            require(surahNumber in 1..114 && ayahNumber > 0) { "رقم السورة أو الآية غير صالح." }
            require(recitationId == 6 || recitationId == 9) { "القارئ غير مدعوم." }
            val request = Request.Builder()
                .url("https://api.quran.com/api/v4/recitations/$recitationId/by_ayah/$surahNumber:$ayahNumber")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("تعذر تحميل تلاوة القارئ (${response.code}).")
                }
                val audioFiles = JSONObject(body).optJSONArray("audio_files")
                    ?: throw IOException("لم يعثر مصدر التلاوات على الملف الصوتي.")
                val relativeUrl = audioFiles.optJSONObject(0)?.optString("url").orEmpty()
                if (relativeUrl.isBlank()) {
                    throw IOException("التلاوة الصوتية غير متاحة لهذه الآية.")
                }
                if (relativeUrl.startsWith("//")) "https:$relativeUrl"
                else if (relativeUrl.startsWith("http")) relativeUrl
                else "https://verses.quran.com/$relativeUrl"
            }
        }

    suspend fun getRecitationAudioUrls(surahNumber: Int, recitationId: Int): List<String> =
        withContext(Dispatchers.IO) {
            require(surahNumber in 1..114) { "رقم السورة غير صالح." }
            require(recitationId == 6 || recitationId == 9) { "القارئ غير مدعوم." }
            val firstPage = getRecitationAudioPage(recitationId, surahNumber, page = 1)
            val totalPages = firstPage.optJSONObject("pagination")
                ?.optInt("total_pages", 0)
                ?.takeIf { it > 0 }
                ?: throw IOException("استجابة مصدر التلاوات لا تحتوي على صفحات صوت السورة.")
            val totalRecords = firstPage.optJSONObject("pagination")
                ?.optInt("total_records", 0)
                ?.takeIf { it > 0 }
                ?: throw IOException("استجابة مصدر التلاوات لا تحدد عدد آيات السورة.")
            val audioFiles = buildList {
                addAll(parseRecitationAudioFiles(firstPage, surahNumber))
                for (page in 2..totalPages) {
                    addAll(
                        parseRecitationAudioFiles(
                            getRecitationAudioPage(recitationId, surahNumber, page),
                            surahNumber
                        )
                    )
                }
            }
            if (audioFiles.size != totalRecords) {
                throw IOException("مصدر التلاوات لم يُرجع ملفات السورة كاملة.")
            }
            audioFiles.mapIndexed { index, audio ->
                if (audio.first != index + 1) {
                    throw IOException("ترتيب ملفات آيات السورة غير مكتمل.")
                }
                audio.second
            }
        }

    private fun getRecitationAudioPage(
        recitationId: Int,
        surahNumber: Int,
        page: Int
    ): JSONObject {
        val request = Request.Builder()
            .url(
                "https://api.quran.com/api/v4/recitations/$recitationId/by_chapter/" +
                    "$surahNumber?per_page=100&page=$page"
            )
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("تعذر تحميل ملفات السورة الصوتية (${response.code}).")
            }
            JSONObject(body)
        }
    }

    private fun parseRecitationAudioFiles(
        response: JSONObject,
        surahNumber: Int
    ): List<Pair<Int, String>> {
        val audioFiles = response.optJSONArray("audio_files")
            ?: throw IOException("استجابة مصدر التلاوات لا تحتوي على الملفات الصوتية.")
        return buildList(audioFiles.length()) {
            for (index in 0 until audioFiles.length()) {
                val audio = audioFiles.getJSONObject(index)
                val verseKey = audio.optString("verse_key").split(':')
                if (
                    verseKey.size != 2 ||
                    verseKey[0] != surahNumber.toString() ||
                    verseKey[1].toIntOrNull() == null
                ) {
                    throw IOException("مصدر التلاوات أعاد ملفاً صوتياً لسورة غير مطلوبة.")
                }
                val relativeUrl = audio.optString("url").orEmpty()
                if (relativeUrl.isBlank()) {
                    throw IOException("أحد ملفات تلاوة السورة غير متاح.")
                }
                val url = when {
                        relativeUrl.startsWith("//") -> "https:$relativeUrl"
                        relativeUrl.startsWith("http") -> relativeUrl
                        else -> "https://verses.quran.com/$relativeUrl"
                    }
                add(verseKey[1].toInt() to url)
            }
        }
    }

    suspend fun checkRecitation(
        audioBytes: ByteArray,
        surahNumber: Int,
        ayahNumber: Int
    ): QuranRecitationResult = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("surahNumber", surahNumber.toString())
            .addFormDataPart("ayahNumber", ayahNumber.toString())
            .addFormDataPart(
                "audio",
                "recitation.m4a",
                audioBytes.toRequestBody("audio/mp4".toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url("$serverBaseUrl/api/quran/check")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IOException("استجابة الخادم غير صالحة (${response.code}).", it)
            }
            if (!response.isSuccessful) {
                throw IOException(
                    json.optString("error").ifBlank {
                        "تعذر تحليل التلاوة (${response.code})."
                    }
                )
            }

            val mistakesJson = json.optJSONArray("mistakes")
                ?: throw IOException("استجابة التقييم لا تحتوي على الكلمات التي تحتاج مراجعة.")
            val mistakes = buildList(mistakesJson.length()) {
                for (index in 0 until mistakesJson.length()) {
                    val mistake = mistakesJson.getJSONObject(index)
                    add(mistake.optString("heard") to mistake.optString("correct"))
                }
            }
            val tipsJson = json.optJSONArray("tajweedTips")
                ?: throw IOException("استجابة التقييم لا تحتوي على نصائح التجويد.")
            val tips = buildList(tipsJson.length()) {
                for (index in 0 until tipsJson.length()) {
                    tipsJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }.take(3)

            QuranRecitationResult(
                transcript = json.optString("transcript"),
                mistakes = mistakes,
                audioQuality = json.optString("audioQuality"),
                performance = json.optString("performance"),
                tajweedTips = tips,
                summary = json.optString("summary")
            )
        }
    }
}


