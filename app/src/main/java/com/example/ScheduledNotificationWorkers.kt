package com.example

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.data.ChatMessage
import com.example.data.OrganizerDatabase
import com.example.network.ApiDeviceIdentity
import com.example.network.GeminiApiClient
import com.example.network.parseQuotaError
import java.io.IOException
import java.util.Calendar
import kotlinx.coroutines.delay
import org.json.JSONObject

class QuranReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val isTest = inputData.getBoolean("test", false)
        if (!isTest && !NotificationScheduler.isEnabled(
                applicationContext,
                NotificationScheduler.KEY_QURAN_ENABLED,
                false
            )
        ) {
            return Result.success()
        }
        val verse = try {
            GeminiApiClient.getDailyQuranVerse()
        } catch (error: IOException) {
            Log.w(TAG, "Unable to retrieve the daily verse; showing the reminder without it.", error)
            null
        }
        val body = verse?.let { "${it.text}\n${it.reference}" }
            ?: "خذ دقائق لتلاوة القرآن والتدرّب على التجويد."
        PushNotifications.showTypedNotification(
            applicationContext,
            "quran_reminder",
            "🕌 وقت تلاوة القرآن",
            body,
            target = "quran",
            speakText = verse?.text,
            ignorePreference = isTest
        )
        return Result.success()
    }

    companion object {
        private const val TAG = "QuranReminderWorker"
    }
}

class ScheduledNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!NotificationScheduler.isEnabled(
                applicationContext,
                NotificationScheduler.KEY_STUDY_ENABLED,
                false
            )
        ) {
            return Result.success()
        }
        PushNotifications.showTypedNotification(
            applicationContext,
            inputData.getString("type").orEmpty(),
            inputData.getString("title").orEmpty(),
            inputData.getString("body").orEmpty(),
            target = inputData.getString("target").orEmpty()
        )
        return Result.success()
    }
}

class OrganizerReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!NotificationScheduler.isEnabled(
                applicationContext,
                NotificationScheduler.KEY_STUDY_ENABLED,
                false
            ) || OrganizerReminderScheduler.isPaused(applicationContext)
        ) {
            return Result.success()
        }

        when (inputData.getString("kind")) {
            "study_session" -> PushNotifications.showTypedNotification(
                applicationContext,
                "study_reminder",
                "حان وقت المذاكرة 📚",
                inputData.getString("name").orEmpty(),
                target = "organizer"
            )
            "lesson" -> PushNotifications.showTypedNotification(
                applicationContext,
                "study_reminder",
                "باقي 15 دقيقة على درس ${inputData.getString("name").orEmpty()}",
                "جهّز نفسك للدرس.",
                target = "organizer"
            )
            "lesson_morning" -> {
                val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                val count = OrganizerDatabase.getDatabase(applicationContext)
                    .organizerDao()
                    .getLessons()
                    .count { it.dayOfWeek == today }
                if (count > 0) {
                    PushNotifications.showTypedNotification(
                        applicationContext,
                        "study_reminder",
                        "النهاردة عندك $count دروس",
                        "نتمنى لك يوماً دراسياً موفقاً.",
                        target = "organizer"
                    )
                }
            }
        }
        OrganizerReminderScheduler.rescheduleAfterReminder(applicationContext, inputData)
        return Result.success()
    }
}

class ChatReplyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString("sessionId")
            ?: return Result.failure()
        val notificationId = inputData.getString("notificationId")
            ?: return Result.failure()
        val repository = AppRepository(AppDatabase.getDatabase(applicationContext).appDao())
        val pendingRequest = repository.getPendingChatRequest(notificationId)
        val requestJson = pendingRequest?.requestJson
            ?: NotificationScheduler.chatRequest(applicationContext, notificationId)
            ?: return Result.failure()
        ApiDeviceIdentity.install(applicationContext)
        val token = PushNotifications.registeredToken(applicationContext)
            ?: return Result.retry()

        try {
            val (statusCode, body) = GeminiApiClient.submitAsyncChatRequest(requestJson)
            if (statusCode !in 200..299) {
                val message = try {
                    JSONObject(body).optString("error")
                } catch (_: org.json.JSONException) {
                    ""
                }
                if (parseQuotaError(statusCode, body) != null ||
                    (statusCode in 400..499 && statusCode != 429)
                ) {
                    NotificationScheduler.discardChatRequest(applicationContext, notificationId)
                    repository.deletePendingChatRequest(notificationId)
                    return Result.failure(
                        Data.Builder().putString("error", message.ifBlank { "تعذر بدء معالجة الرد." }).build()
                    )
                }
                return Result.retry()
            }
            val jobId = JSONObject(body).optString("jobId")
            if (jobId.isBlank()) return Result.retry()

            val deadline = System.currentTimeMillis() + MAX_POLLING_MILLIS
            while (System.currentTimeMillis() < deadline) {
                val state = GeminiApiClient.getAsyncChatStatus(jobId, token)
                when (state.status) {
                    "completed" -> {
                        val reply = state.reply?.takeIf(String::isNotBlank)
                            ?: return Result.failure(
                                Data.Builder().putString("error", "وصل رد فارغ من الخادم.").build()
                            )
                        repository.insertMessage(
                            ChatMessage(
                                id = "async-reply-$notificationId",
                                sessionId = sessionId,
                                role = "model",
                                content = reply
                            )
                        )
                        repository.deletePendingChatRequest(notificationId)
                        NotificationScheduler.completeChatRequest(applicationContext, notificationId)
                        return Result.success()
                    }
                    "failed" -> {
                        NotificationScheduler.discardChatRequest(applicationContext, notificationId)
                        repository.deletePendingChatRequest(notificationId)
                        return Result.failure(
                            Data.Builder()
                                .putString("error", state.error ?: "تعذر إنشاء رد Smart Cat.")
                                .build()
                        )
                    }
                    "queued", "processing" -> delay(POLL_INTERVAL_MILLIS)
                    else -> return Result.retry()
                }
            }
            return Result.retry()
        } catch (error: IOException) {
            Log.w(TAG, "Chat reply worker will retry after a network failure.", error)
            return Result.retry()
        } catch (error: org.json.JSONException) {
            Log.e(TAG, "The chat server returned invalid job data.", error)
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "ChatReplyWorker"
        private const val POLL_INTERVAL_MILLIS = 5_000L
        private const val MAX_POLLING_MILLIS = 7 * 60 * 1_000L
    }
}
