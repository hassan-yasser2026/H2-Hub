package com.example

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object PushNotifications {
    const val CHANNEL_ID = "h2_push_notifications"
    const val CHAT_CHANNEL_ID = "chat_replies"
    const val QURAN_CHANNEL_ID = "quran_reminders"
    const val STUDY_CHANNEL_ID = "study_reminders"
    const val UPDATE_CHANNEL_ID = "app_updates"

    private const val PREFS_NAME = "h2_push_notifications"
    private const val KEY_REGISTERED_TOKEN = "registered_token"
    private const val KEY_PERMISSION_ASKED = "notification_permission_asked"
    private const val TAG = "H2Push"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val _navigationRequests =
        kotlinx.coroutines.flow.MutableStateFlow<NotificationNavigation?>(null)
    val navigationRequests: kotlinx.coroutines.flow.StateFlow<NotificationNavigation?> =
        _navigationRequests

    fun openNotificationIntent(intent: Intent?) {
        val target = intent?.getStringExtra(MainActivity.EXTRA_NOTIFICATION_TARGET)
            ?: intent?.data?.host
        if (target.isNullOrBlank()) return
        _navigationRequests.value = NotificationNavigation(
            eventId = System.nanoTime(),
            target = target,
            sessionId = intent?.getStringExtra(MainActivity.EXTRA_CHAT_SESSION_ID),
            speakText = intent?.getStringExtra(MainActivity.EXTRA_SPEAK_TEXT),
            notificationId = intent?.getStringExtra(MainActivity.EXTRA_CHAT_JOB_ID)
        )
    }

    fun clearNavigationRequest(eventId: Long) {
        if (_navigationRequests.value?.eventId == eventId) _navigationRequests.value = null
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            listOf(
                NotificationChannel(CHANNEL_ID, "إشعارات H2 Hub", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHAT_CHANNEL_ID, "ردود Smart Cat", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(QURAN_CHANNEL_ID, "تذكيرات القرآن", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(STUDY_CHANNEL_ID, "تذكيرات الدراسة", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(UPDATE_CHANNEL_ID, "تحديثات التطبيق", NotificationManager.IMPORTANCE_LOW)
            ).forEach { channel ->
                channel.description = "إشعارات H2 Hub"
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun isFirebaseConfigured(context: Context): Boolean =
        FirebaseApp.getApps(context).isNotEmpty()

    fun wasPermissionRequested(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PERMISSION_ASKED, false)

    fun markPermissionRequested(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PERMISSION_ASKED, true)
            .apply()
    }

    @Suppress("DEPRECATION")
    fun registerCurrentToken(context: Context) {
        if (!isFirebaseConfigured(context) || !hasNotificationPermission(context)) return
        try {
            FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener { token -> registerToken(context.applicationContext, token) }
                .addOnFailureListener {
                    Log.w(TAG, "Unable to retrieve the Firebase registration token.")
                }
        } catch (_: IllegalStateException) {
            Log.w(TAG, "Firebase is not configured for this Android build.")
        }
    }

    fun registerToken(context: Context, token: String) {
        if (!hasNotificationPermission(context) || token.isBlank()) return
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_REGISTERED_TOKEN, null) == token) return

        Thread {
            val endpoint = "${BuildConfig.SERVER_URL.trimEnd('/')}/api/push/token"
            try {
                val body = JSONObject()
                    .put("token", token)
                    .put("platform", "android")
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(endpoint).post(body).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "The server rejected device token registration (${response.code}).")
                        return@Thread
                    }
                }
                preferences.edit().putString(KEY_REGISTERED_TOKEN, token).apply()
            } catch (_: IllegalArgumentException) {
                Log.e(TAG, "The configured API URL is invalid.")
            } catch (_: IOException) {
                Log.w(TAG, "Device token registration failed because the API is unavailable.")
            }
        }.start()
    }

    fun registeredToken(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REGISTERED_TOKEN, null)
            ?.takeIf(String::isNotBlank)

    fun showNotification(context: Context, title: String, body: String) {
        showTypedNotification(context, "general", title, body)
    }

    fun showTypedNotification(
        context: Context,
        type: String,
        title: String,
        body: String,
        target: String = "",
        sessionId: String = "",
        speakText: String? = null,
        jobId: String = "",
        ignorePreference: Boolean = false,
    ) {
        if (!hasNotificationPermission(context)) return
        val preferenceKey = when (type) {
            "chat_reply" -> NotificationScheduler.KEY_CHAT_ENABLED
            "quran_reminder" -> NotificationScheduler.KEY_QURAN_ENABLED
            "study_reminder" -> NotificationScheduler.KEY_STUDY_ENABLED
            "quiz_result" -> NotificationScheduler.KEY_QUIZ_ENABLED
            "app_update" -> NotificationScheduler.KEY_UPDATES_ENABLED
            else -> null
        }
        if (!ignorePreference && preferenceKey != null && !NotificationScheduler.isEnabled(
                context,
                preferenceKey,
                defaultValue = type == "chat_reply" || type == "app_update"
            )
        ) {
            return
        }
        createChannel(context)
        val notificationId = when (type) {
            "chat_reply" -> (sessionId.hashCode() xor System.currentTimeMillis().toInt()) and 0x7fffffff
            "quran_reminder" -> 743
            else -> (type.hashCode() xor (System.currentTimeMillis() / 60_000L).toInt()) and 0x7fffffff
        }
        val deepLink = when (target) {
            "chat" -> "h2hub://chat/$sessionId"
            "quran" -> "h2hub://quran"
            "organizer" -> "h2hub://organizer"
            "learning_profile" -> "h2hub://learning-profile"
            else -> "h2hub://home"
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse(deepLink)
            putExtra(MainActivity.EXTRA_NOTIFICATION_TARGET, target)
            if (sessionId.isNotBlank()) putExtra(MainActivity.EXTRA_CHAT_SESSION_ID, sessionId)
            if (!speakText.isNullOrBlank()) putExtra(MainActivity.EXTRA_SPEAK_TEXT, speakText)
            if (jobId.isNotBlank()) putExtra(MainActivity.EXTRA_CHAT_JOB_ID, jobId)
        }
        val openApp = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channelId = when (type) {
            "chat_reply" -> CHAT_CHANNEL_ID
            "quran_reminder" -> QURAN_CHANNEL_ID
            "study_reminder" -> STUDY_CHANNEL_ID
            "app_update", "quiz_result" -> UPDATE_CHANNEL_ID
            else -> CHANNEL_ID
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_h2hub)
            .setContentTitle(title.ifBlank { "H2 Hub" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setNumber(NotificationManagerCompat.from(context).activeNotifications.size + 1)

        when (type) {
            "chat_reply" -> {
                builder.addAction(0, "افتح", openApp)
                val dismiss = PendingIntent.getBroadcast(
                    context,
                    notificationId + 1,
                    Intent(context, NotificationActionReceiver::class.java)
                        .setAction(NotificationActionReceiver.ACTION_DISMISS)
                        .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "تجاهل", dismiss)
            }
            "quran_reminder" -> {
                if (!speakText.isNullOrBlank()) builder.addAction(0, "اسمع الآية", openApp)
                val snooze = PendingIntent.getBroadcast(
                    context,
                    notificationId + 2,
                    Intent(context, NotificationActionReceiver::class.java)
                        .setAction(NotificationActionReceiver.ACTION_SNOOZE_QURAN)
                        .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "تأجيل 10 دقائق", snooze)
            }
            "study_reminder" -> builder.addAction(0, "افتح المنظم", openApp)
        }
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            Log.w(TAG, "Notification permission is not granted.")
        }
    }
}

data class NotificationNavigation(
    val eventId: Long,
    val target: String,
    val sessionId: String?,
    val speakText: String?,
    val notificationId: String?,
)

class H2FirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushNotifications.registerToken(applicationContext, token)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"].orEmpty()
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        if (title.isNotBlank() || body.isNotBlank()) {
            PushNotifications.showTypedNotification(
                applicationContext,
                message.data["type"].orEmpty(),
                title.ifBlank { "H2 Hub" },
                body,
                target = message.data["target"].orEmpty(),
                sessionId = message.data["sessionId"].orEmpty(),
                jobId = message.data["jobId"].orEmpty()
            )
        }

    }
}

class NotificationActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_DISMISS -> {
                val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (id >= 0) NotificationManagerCompat.from(context).cancel(id)
            }
            ACTION_SNOOZE_QURAN -> {
                NotificationManagerCompat.from(context).cancel(
                    intent.getIntExtra(EXTRA_NOTIFICATION_ID, 743)
                )
                NotificationScheduler.scheduleQuranTest(context, delayMinutes = 10)
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.example.action.DISMISS_NOTIFICATION"
        const val ACTION_SNOOZE_QURAN = "com.example.action.SNOOZE_QURAN"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
