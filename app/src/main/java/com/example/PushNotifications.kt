package com.example

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    const val CHANNEL_NAME = "إشعارات H2 Hub"

    private const val PREFS_NAME = "h2_push_notifications"
    private const val KEY_REGISTERED_TOKEN = "registered_token"
    private const val KEY_PERMISSION_ASKED = "notification_permission_asked"
    private const val TAG = "H2Push"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "تنبيهات وإعلانات H2 Hub"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
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

    fun showNotification(context: Context, title: String, body: String) {
        if (!hasNotificationPermission(context)) return
        createChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_h2hub)
            .setContentTitle(title.ifBlank { "H2 Hub" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(
                System.currentTimeMillis().toInt(),
                notification
            )
        } catch (_: SecurityException) {
            Log.w(TAG, "Notification permission is not granted.")
        }
    }
}

class H2FirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushNotifications.registerToken(applicationContext, token)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"].orEmpty()
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        if (title.isNotBlank() || body.isNotBlank()) {
            PushNotifications.showNotification(
                applicationContext,
                title.ifBlank { "H2 Hub" },
                body
            )
        }
    }
}
