package com.example.network

import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class SubscriptionPlan(val apiValue: String, val titleAr: String, val priceEgp: Int, val days: Int) {
    MONTHLY("monthly", "شهري", 50, 30),
    QUARTERLY("quarterly", "ربع سنوي", 130, 90),
    YEARLY("yearly", "سنوي", 400, 365);

    companion object {
        fun fromApi(value: String?): SubscriptionPlan? = entries.firstOrNull { it.apiValue == value }
    }
}

data class SubscriptionUser(
    val userCode: String,
    val vodafoneNumber: String,
    val subscriptionPlan: String,
    val subscriptionEndDate: String
)

data class SubscriptionInfo(
    val status: String,
    val plan: String,
    val startDate: String,
    val endDate: String,
    val daysRemaining: Int
)

data class SubscriptionRequestItem(
    val id: String,
    val plan: String,
    val amount: Int,
    val uniqueCode: String,
    val status: String,
    val rejectionReason: String,
    val createdAt: String
)

data class SubscriptionStatus(
    val user: SubscriptionUser,
    val subscription: SubscriptionInfo,
    val requests: List<SubscriptionRequestItem>
)

data class PaymentCode(val requestId: String, val uniqueCode: String, val amount: Int, val plan: String)

data class ServerSubscriptionPlan(
    val name: String,
    val nameAr: String,
    val price: Int,
    val currency: String,
    val durationDays: Int,
    val messages: Int,
    val features: Map<String, Boolean>
)

private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

fun parseSubscriptionPlans(body: String): List<ServerSubscriptionPlan> {
    val plans = JSONObject(body).optJSONArray("plans")
        ?: throw IOException("استجابة الخطط لا تحتوي على قائمة الخطط.")
    return buildList {
        for (index in 0 until plans.length()) {
            val plan = plans.optJSONObject(index) ?: continue
            val name = plan.str("name")
            val nameAr = plan.str("nameAr")
            if (name.isBlank() || nameAr.isBlank()) continue
            val featureJson = plan.optJSONObject("features") ?: JSONObject()
            val features = buildMap {
                val keys = featureJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, featureJson.optBoolean(key, false))
                }
            }
            add(
                ServerSubscriptionPlan(
                    name = name,
                    nameAr = nameAr,
                    price = plan.optInt("price"),
                    currency = plan.str("currency").ifBlank { "EGP" },
                    durationDays = plan.optInt("durationDays"),
                    messages = plan.optInt("messages"),
                    features = features
                )
            )
        }
    }
}

fun parseSubscriptionStatus(body: String): SubscriptionStatus {
    val root = JSONObject(body)
    val user = root.optJSONObject("user") ?: JSONObject()
    val sub = root.optJSONObject("subscription") ?: JSONObject()
    val arr = root.optJSONArray("requests")
    val requests = buildList {
        if (arr != null) for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            add(
                SubscriptionRequestItem(
                    id = r.str("id"), plan = r.str("plan"), amount = r.optInt("amount"),
                    uniqueCode = r.str("uniqueCode"), status = r.str("status"),
                    rejectionReason = r.str("rejectionReason"), createdAt = r.str("createdAt")
                )
            )
        }
    }
    return SubscriptionStatus(
        user = SubscriptionUser(user.str("userCode"), user.str("vodafoneNumber"), user.str("subscriptionPlan"), user.str("subscriptionEndDate")),
        subscription = SubscriptionInfo(sub.str("status"), sub.str("plan"), sub.str("startDate"), sub.str("endDate"), sub.optInt("daysRemaining")),
        requests = requests
    )
}

fun parsePaymentCode(body: String): PaymentCode {
    val o = JSONObject(body)
    val id = o.str("requestId")
    val code = o.str("uniqueCode")
    if (id.isBlank() || code.isBlank()) throw IOException("استجابة الخادم لا تحتوي على كود الدفع.")
    return PaymentCode(id, code, o.optInt("amount"), o.str("plan"))
}

fun extractSubscriptionError(code: Int, body: String): String {
    val detail = try {
        val o = JSONObject(body)
        o.str("error").ifBlank { o.str("message") }
    } catch (_: JSONException) {
        ""
    }
    return if (detail.isNotBlank()) detail else "فشل الطلب (HTTP $code)."
}

fun isValidVodafoneNumber(number: String): Boolean = Regex("^01[0-9]{9}$").matches(number.trim())

object SubscriptionDeviceId {
    private const val PREFS = "subscription_prefs"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (id.isNullOrBlank()) throw IOException("تعذر قراءة معرف الجهاز.")
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}

class SubscriptionApiClient(
    baseUrl: String = BuildConfig.SERVER_URL,
    private val client: OkHttpClient = defaultClient
) {
    private val base = baseUrl.trimEnd('/')
    private val json = "application/json; charset=utf-8".toMediaType()

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(extractSubscriptionError(response.code, body))
                body
            }
        } catch (e: JSONException) {
            throw IOException("استجابة الخادم غير صالحة.", e)
        }
    }

    private fun post(path: String, payload: JSONObject) = Request.Builder()
        .url("$base/api/subscription/$path")
        .post(payload.toString().toRequestBody(json))
        .build()

    suspend fun register(deviceId: String, vodafoneNumber: String, displayName: String? = null): String {
        val payload = JSONObject().put("deviceId", deviceId).put("vodafoneNumber", vodafoneNumber)
        if (!displayName.isNullOrBlank()) payload.put("displayName", displayName)
        val code = parseOrWrap { JSONObject(execute(post("register", payload))).str("userCode") }
        if (code.isBlank()) throw IOException("استجابة الخادم لا تحتوي على كود المستخدم.")
        return code
    }

    suspend fun status(deviceId: String): SubscriptionStatus {
        val url = "$base/api/subscription/status".toHttpUrlOrThrow().newBuilder()
            .addQueryParameter("deviceId", deviceId).build()
        return parseOrWrap { parseSubscriptionStatus(execute(Request.Builder().url(url).get().build())) }
    }

    suspend fun usage(deviceId: String? = null): UsageSnapshot {
        return fetchUsage(deviceId)
    }

    suspend fun fetchUsage(deviceId: String? = null): UsageSnapshot {
        val urlBuilder = "$base/api/subscription/usage".toHttpUrlOrThrow().newBuilder()
        deviceId?.takeIf(String::isNotBlank)?.let {
            urlBuilder.addQueryParameter("deviceId", it)
        }
        val url = urlBuilder.build()
        return parseOrWrap { parseUsageSnapshot(execute(Request.Builder().url(url).get().build())) }
    }

    suspend fun fetchPlans(): List<ServerSubscriptionPlan> {
        val url = "$base/api/subscription/plans".toHttpUrlOrThrow()
        return parseOrWrap { parseSubscriptionPlans(execute(Request.Builder().url(url).get().build())) }
    }

    suspend fun generateCode(deviceId: String, plan: SubscriptionPlan): PaymentCode =
        generateCode(deviceId, plan.apiValue)

    suspend fun generateCode(deviceId: String, plan: String): PaymentCode =
        parseOrWrap {
            parsePaymentCode(execute(post("generate-code", JSONObject().put("deviceId", deviceId).put("plan", plan))))
        }

    suspend fun uploadScreenshot(deviceId: String, requestId: String, bytes: ByteArray, mimeType: String) {
        val type = mimeType.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val ext = if (type == "image/png") "png" else "jpg"
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("deviceId", deviceId)
            .addFormDataPart("requestId", requestId)
            .addFormDataPart("file", "screenshot.$ext", bytes.toRequestBody(type.toMediaType()))
            .build()
        execute(Request.Builder().url("$base/api/subscription/upload-screenshot").post(multipart).build())
    }

    suspend fun submitRequest(deviceId: String, requestId: String, vodafoneNumber: String) {
        execute(post("request", JSONObject().put("deviceId", deviceId).put("requestId", requestId).put("vodafoneNumber", vodafoneNumber)))
    }

    private fun String.toHttpUrlOrThrow() =
        toHttpUrlOrNull()
            ?: throw IOException("عنوان الخادم غير صالح.")

    private inline fun <T> parseOrWrap(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw IOException("استجابة الخادم غير صالحة.", e)
    }

    companion object {
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

        private val defaultClient = OkHttpClient.Builder()
            .addInterceptor(DeviceQuotaInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        suspend fun readImage(context: Context, uri: Uri): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "image/jpeg"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("تعذر قراءة الصورة المختارة.")
            if (bytes.isEmpty()) throw IOException("الصورة المختارة فارغة.")
            if (bytes.size > MAX_IMAGE_BYTES) throw IOException("حجم الصورة أكبر من 5 ميجابايت.")
            bytes to mime
        }
    }
}


