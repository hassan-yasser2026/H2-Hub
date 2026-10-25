package com.example

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ExamReminder(
    val id: String,
    val title: String,
    val examAt: Long
)

object NotificationScheduler {
    const val PREFS_NAME = "h2_notification_settings"
    const val KEY_CHAT_ENABLED = "chat_replies_enabled"
    const val KEY_QURAN_ENABLED = "quran_reminders_enabled"
    const val KEY_STUDY_ENABLED = "study_reminders_enabled"
    const val KEY_QUIZ_ENABLED = "quiz_result_notifications_enabled"
    const val KEY_UPDATES_ENABLED = "app_update_notifications_enabled"
    const val KEY_STUDY_HOUR = "study_reminder_hour"
    const val KEY_STUDY_MINUTE = "study_reminder_minute"
    private const val KEY_EXAMS = "exam_reminders"
    private const val CHAT_REQUESTS = "h2_async_chat_requests"
    private const val QURAN_WORK = "h2_daily_quran_reminder"
    private const val CHAT_WORK_PREFIX = "h2_chat_reply_"

    fun initialize(context: Context) {
        val notificationPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacyPrefs = context.getSharedPreferences("h2hub_prefs", Context.MODE_PRIVATE)
        if (!notificationPrefs.contains(KEY_QURAN_ENABLED)) {
            notificationPrefs.edit()
                .putBoolean(KEY_QURAN_ENABLED, legacyPrefs.getBoolean("quran_reminder_enabled", false))
                .apply()
        }
        scheduleQuran(
            context,
            legacyPrefs.getInt("quran_reminder_hour", 20),
            legacyPrefs.getInt("quran_reminder_minute", 0),
            notificationPrefs.getBoolean(KEY_QURAN_ENABLED, false)
        )
        if (isEnabled(context, KEY_STUDY_ENABLED, false)) rescheduleExams(context)
        rescheduleOrganizer(context)
    }

    fun isEnabled(context: Context, key: String, defaultValue: Boolean = true): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(key, defaultValue)

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(key, enabled).apply()
        when (key) {
            KEY_QURAN_ENABLED -> {
                val prefs = context.getSharedPreferences("h2hub_prefs", Context.MODE_PRIVATE)
                scheduleQuran(
                    context,
                    prefs.getInt("quran_reminder_hour", 20),
                    prefs.getInt("quran_reminder_minute", 0),
                    enabled
                )
            }
            KEY_STUDY_ENABLED -> {
                if (enabled) rescheduleExams(context)
                else WorkManager.getInstance(context).cancelAllWorkByTag("h2_exam_reminder")
                rescheduleOrganizer(context)
            }
        }
    }

    fun scheduleQuran(context: Context, hour: Int, minute: Int, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(QURAN_WORK)
            return
        }
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        val request = PeriodicWorkRequestBuilder<QuranReminderWorker>(
            24, TimeUnit.HOURS, 1, TimeUnit.HOURS
        )
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .addTag("h2_quran_reminder")
            .build()
        workManager.enqueueUniquePeriodicWork(
            QURAN_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun scheduleQuranTest(context: Context, delayMinutes: Long = 2) {
        val request = OneTimeWorkRequestBuilder<QuranReminderWorker>()
            .setInputData(Data.Builder().putBoolean("test", true).build())
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .addTag("h2_quran_reminder_test")
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun setStudyReminderTime(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_STUDY_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_STUDY_MINUTE, minute.coerceIn(0, 59))
            .apply()
        if (isEnabled(context, KEY_STUDY_ENABLED, false)) rescheduleExams(context)
    }

    fun studyReminderTime(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_STUDY_HOUR, 8) to prefs.getInt(KEY_STUDY_MINUTE, 0)
    }

    fun saveExam(context: Context, exam: ExamReminder) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val exams = readExams(context).filterNot { it.id == exam.id } + exam
        prefs.edit().putString(
            KEY_EXAMS,
            JSONArray().apply {
                exams.forEach {
                    put(JSONObject().put("id", it.id).put("title", it.title).put("examAt", it.examAt))
                }
            }.toString()
        ).apply()
        scheduleExam(context, exam)
    }

    fun removeExam(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(
            KEY_EXAMS,
            JSONArray().apply {
                readExams(context).filterNot { it.id == id }.forEach {
                    put(JSONObject().put("id", it.id).put("title", it.title).put("examAt", it.examAt))
                }
            }.toString()
        ).apply()
        WorkManager.getInstance(context).cancelUniqueWork("h2_exam_${id}_before")
        WorkManager.getInstance(context).cancelUniqueWork("h2_exam_${id}_day")
    }

    fun readExams(context: Context): List<ExamReminder> = runCatching {
        val array = JSONArray(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_EXAMS, "[]")
        )
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ExamReminder(item.getString("id"), item.getString("title"), item.getLong("examAt"))
        }
    }.getOrElse { error ->
        throw IllegalStateException("Unable to read saved exam reminders.", error)
    }

    fun saveChatRequest(context: Context, notificationId: String, requestJson: String) {
        context.getSharedPreferences(CHAT_REQUESTS, Context.MODE_PRIVATE).edit()
            .putString(notificationId, requestJson).apply()
    }

    fun chatRequest(context: Context, notificationId: String): String? =
        context.getSharedPreferences(CHAT_REQUESTS, Context.MODE_PRIVATE)
            .getString(notificationId, null)

    fun completeChatRequest(context: Context, notificationId: String) {
        discardChatRequest(context, notificationId)
    }

    fun discardChatRequest(context: Context, notificationId: String) {
        context.getSharedPreferences(CHAT_REQUESTS, Context.MODE_PRIVATE).edit()
            .remove(notificationId).apply()
    }

    fun hasPendingChatRequest(context: Context, sessionId: String): Boolean =
        context.getSharedPreferences(CHAT_REQUESTS, Context.MODE_PRIVATE)
            .all.values.any { value ->
                value is String && runCatching {
                    JSONObject(value).optString("sessionId") == sessionId
                }.getOrDefault(false)
            }

    fun enqueueChatReply(
        context: Context,
        sessionId: String,
        notificationId: String,
        userId: String
    ): UUID {
        val request = OneTimeWorkRequestBuilder<ChatReplyWorker>()
            .setInputData(
                Data.Builder()
                    .putString("sessionId", sessionId)
                    .putString("notificationId", notificationId)
                    .putString("userId", userId)
                    .build()
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .addTag("h2_async_chat")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$CHAT_WORK_PREFIX$notificationId",
            ExistingWorkPolicy.KEEP,
            request
        )
        return request.id
    }

    private fun rescheduleExams(context: Context) {
        readExams(context).forEach { scheduleExam(context, it) }
    }

    private fun rescheduleOrganizer(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                OrganizerReminderScheduler.rescheduleAll(context.applicationContext)
            } catch (error: Exception) {
                Log.e("NotificationScheduler", "Unable to reschedule academic organizer reminders.", error)
            }
        }
    }

    private fun scheduleExam(context: Context, exam: ExamReminder) {
        if (!isEnabled(context, KEY_STUDY_ENABLED, false)) return
        val (hour, minute) = studyReminderTime(context)
        val examCalendar = Calendar.getInstance().apply { timeInMillis = exam.examAt }
        listOf(
            Triple("before", -1, "غداً اختبار ${exam.title}"),
            Triple("day", 0, "موعد اختبار ${exam.title} اليوم")
        ).forEach { (kind, dayOffset, text) ->
            val notifyAt = Calendar.getInstance().apply {
                timeInMillis = examCalendar.timeInMillis
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val delay = notifyAt - System.currentTimeMillis()
            val uniqueName = "h2_exam_${exam.id}_$kind"
            val workManager = WorkManager.getInstance(context)
            if (delay <= 0L) {
                workManager.cancelUniqueWork(uniqueName)
                return@forEach
            }
            val request = OneTimeWorkRequestBuilder<ScheduledNotificationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString("type", "study_reminder")
                        .putString("title", "📚 تذكير الدراسة")
                        .putString("body", text)
                        .putString("target", "organizer")
                        .build()
                )
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag("h2_exam_reminder")
                .build()
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
