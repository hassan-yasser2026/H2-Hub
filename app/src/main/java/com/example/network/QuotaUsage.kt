package com.example.network

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.example.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val DEVICE_ID_HEADER = "X-H2-Device-Id"
private const val APP_CHECK_HEADER = "X-Firebase-AppCheck"

data class FeatureUsage(val used: Int, val limit: Int) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
    val exhausted: Boolean get() = used >= limit
}

data class MeteredSubscriptionUsage(
    val plan: String,
    val nameAr: String,
    val used: Int,
    val limit: Int,
    val remaining: Int,
    val expiresAt: String,
    val features: Map<String, Boolean>
)

data class UsageSnapshot(
    val paid: Boolean,
    val features: Map<String, FeatureUsage>,
    val legacy: Boolean = false,
    val subscription: MeteredSubscriptionUsage? = null
) {
    fun feature(name: String): FeatureUsage? = features[name]
}

class QuotaExceededException(
    message: String,
    val feature: String,
    val used: Int,
    val limit: Int,
    val resetAt: String
) : IOException(message)

private val USAGE_FEATURES = listOf("chat", "coder", "quran", "vision", "rag")

fun parseUsageSnapshot(body: String): UsageSnapshot {
    val root = JSONObject(body)
    val usage = root.optJSONObject("usage") ?: JSONObject()
    val features = USAGE_FEATURES.mapNotNull { name ->
        usage.optJSONObject(name)?.let { name to FeatureUsage(it.optInt("used"), it.optInt("limit")) }
    }.toMap()
    val subscriptionJson = root.optJSONObject("subscription")
    val subscription = subscriptionJson?.takeUnless { it.isNull("plan") }?.let { value ->
        val plan = value.optString("plan").takeIf(String::isNotBlank) ?: return@let null
        val planFeatures = value.optJSONObject("features") ?: JSONObject()
        val parsedFeatures = buildMap {
            val keys = planFeatures.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, planFeatures.optBoolean(key, false))
            }
        }
        MeteredSubscriptionUsage(
            plan = plan,
            nameAr = value.optString("nameAr"),
            used = value.optInt("used"),
            limit = value.optInt("limit"),
            remaining = value.optInt("remaining", (value.optInt("limit") - value.optInt("used")).coerceAtLeast(0)),
            expiresAt = value.optString("expiresAt"),
            features = parsedFeatures
        )
    }
    val legacy = root.optBoolean("legacy", false)
    return UsageSnapshot(
        paid = root.optBoolean("paid", false) || legacy || subscription != null,
        features = features,
        legacy = legacy,
        subscription = subscription
    )
}

/** Returns a quota error only for a 429 whose body matches the server quota contract. */
fun parseQuotaError(code: Int, body: String): QuotaExceededException? {
    if (code != 429) return null
    return try {
        val o = JSONObject(body)
        val feature = o.optString("feature", "")
        if (feature.isBlank() || !o.has("limit")) return null
        QuotaExceededException(
            o.optString("error", "").ifBlank { "انتهت الحصة المجانية." },
            feature, o.optInt("used"), o.optInt("limit"), o.optString("resetAt", "")
        )
    } catch (_: JSONException) {
        null
    }
}

object QuotaEvents {
    private val _events = MutableSharedFlow<QuotaExceededException>(extraBufferCapacity = 8)
    val events: SharedFlow<QuotaExceededException> = _events.asSharedFlow()
    fun publish(error: QuotaExceededException) {
        _events.tryEmit(error)
    }
}

object ApiDeviceIdentity {
    @Volatile
    var deviceId: String? = null

    fun install(context: Context) {
        if (deviceId.isNullOrBlank()) {
            deviceId = try {
                SubscriptionDeviceId.get(context)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Adds the device header and reports server quota 429s; it never retries them. */
class DeviceQuotaInterceptor(
    serverUrl: String = BuildConfig.SERVER_URL
) : Interceptor {
    private val serverHost = serverUrl.toHttpUrlOrNull()?.host

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.url.host == serverHost) {
            val builder = request.newBuilder()
            ApiDeviceIdentity.deviceId?.takeIf(String::isNotBlank)?.let {
                builder.header(DEVICE_ID_HEADER, it)
            }
            if (!BuildConfig.DEBUG) {
                val appCheckToken = try {
                    Tasks.await(
                        FirebaseAppCheck.getInstance().getAppCheckToken(false),
                        25,
                        TimeUnit.SECONDS
                    ).token
                } catch (error: Exception) {
                    throw IOException("تعذر توثيق التطبيق. تحقق من Google Play Services ثم أعد المحاولة.", error)
                }
                if (appCheckToken.isNullOrBlank()) throw IOException("تعذر توثيق التطبيق.")
                builder.header(APP_CHECK_HEADER, appCheckToken)
            }

            val user = try {
                FirebaseAuth.getInstance().currentUser
            } catch (error: IllegalStateException) {
                throw IOException("تعذر تهيئة التحقق من الحساب.", error)
            }
            if (user != null) {
                val idToken = try {
                    Tasks.await(user.getIdToken(false), 20, TimeUnit.SECONDS).token
                } catch (error: Exception) {
                    throw IOException("تعذر التحقق من جلسة حسابك. سجّل الدخول مجدداً.", error)
                }
                if (idToken.isNullOrBlank()) throw IOException("تعذر التحقق من جلسة حسابك.")
                builder.header("Authorization", "Bearer $idToken")
            }
            request = builder.build()
        }
        val response = chain.proceed(request)
        if (response.code == 429) {
            parseQuotaError(429, response.peekBody(8_192).string())?.let(QuotaEvents::publish)
        }
        return response
    }
}
