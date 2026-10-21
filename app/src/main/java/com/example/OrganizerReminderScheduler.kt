package com.example

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.Lesson
import com.example.data.OrganizerDatabase
import com.example.data.StudySession
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

object OrganizerReminderScheduler {
    const val KEY_REMINDER_LEAD_MINUTES = "organizer_reminder_lead_minutes"
    const val KEY_REMINDERS_PAUSED = "organizer_reminders_paused"
    private const val MORNING_SUMMARY_WORK = "h2_lesson_morning_summary"
    private const val SESSION_TAG = "h2_organizer_session"
    private const val LESSON_TAG = "h2_organizer_lesson"

    fun reminderLeadMinutes(context: Context): Int =
        context.getSharedPreferences(NotificationScheduler.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_REMINDER_LEAD_MINUTES, 10)
            .coerceIn(5, 15)

    fun isPaused(context: Context): Boolean =
        context.getSharedPreferences(NotificationScheduler.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REMINDERS_PAUSED, false)

    suspend fun rescheduleAll(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(SESSION_TAG)
        manager.cancelAllWorkByTag(LESSON_TAG)
        manager.cancelUniqueWork(MORNING_SUMMARY_WORK)
        if (!NotificationScheduler.isEnabled(
                context,
                NotificationScheduler.KEY_STUDY_ENABLED,
                false
            ) || isPaused(context)
        ) return

        val dao = OrganizerDatabase.getDatabase(context).organizerDao()
        dao.getStudySessions().forEach { scheduleStudySession(context, it) }
        dao.getLessons().forEach { scheduleLesson(context, it) }
        scheduleMorningLessonSummary(context)
    }

    fun scheduleStudySession(context: Context, session: StudySession) {
        if (!remindersActive(context)) return
        val alertAt = nextAlertTime(
            day = session.day,
            timeMinutes = session.startTime - session.reminderMinutes,
            repeatsWeekly = session.repeatsWeekly,
            date = session.date
        ) ?: return
        enqueue(
            context = context,
            uniqueName = "h2_study_session_${session.id}",
            tag = SESSION_TAG,
            delayMillis = alertAt - System.currentTimeMillis(),
            data = Data.Builder()
                .putString("kind", "study_session")
                .putString("entityId", session.id)
                .putString("name", session.subject)
                .putBoolean("repeatsWeekly", session.repeatsWeekly)
                .putInt("day", session.day)
                .putInt("time", session.startTime)
                .putString("date", session.date.orEmpty())
                .build()
        )
    }

    fun scheduleLesson(context: Context, lesson: Lesson) {
        if (!remindersActive(context)) return
        val alertAt = nextAlertTime(
            day = lesson.dayOfWeek,
            timeMinutes = lesson.time - lesson.reminderMinutes,
            repeatsWeekly = true
        ) ?: return
        enqueue(
            context = context,
            uniqueName = "h2_lesson_${lesson.id}",
            tag = LESSON_TAG,
            delayMillis = alertAt - System.currentTimeMillis(),
            data = Data.Builder()
                .putString("kind", "lesson")
                .putString("entityId", lesson.id)
                .putString("name", lesson.name)
                .putBoolean("repeatsWeekly", true)
                .putInt("day", lesson.dayOfWeek)
                .putInt("time", lesson.time)
                .build()
        )
    }

    fun scheduleMorningLessonSummary(context: Context) {
        if (!remindersActive(context)) return
        val now = System.currentTimeMillis()
        val nextMorning = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        enqueue(
            context,
            MORNING_SUMMARY_WORK,
            LESSON_TAG,
            nextMorning - now,
            Data.Builder().putString("kind", "lesson_morning").build()
        )
    }

    fun rescheduleAfterReminder(context: Context, data: Data) {
        if (!remindersActive(context)) return
        when (data.getString("kind")) {
            "study_session" -> {
                val date = data.getString("date").orEmpty().ifBlank { null }
                val session = StudySession(
                    id = data.getString("entityId").orEmpty(),
                    subject = data.getString("name").orEmpty(),
                    startTime = data.getInt("time", 0),
                    duration = 0,
                    day = data.getInt("day", Calendar.getInstance().get(Calendar.DAY_OF_WEEK)),
                    reminderMinutes = reminderLeadMinutes(context),
                    date = date,
                    repeatsWeekly = data.getBoolean("repeatsWeekly", false)
                )
                if (session.repeatsWeekly) scheduleStudySession(context, session)
            }
            "lesson" -> {
                scheduleLesson(
                    context,
                    Lesson(
                        id = data.getString("entityId").orEmpty(),
                        name = data.getString("name").orEmpty(),
                        dayOfWeek = data.getInt("day", Calendar.getInstance().get(Calendar.DAY_OF_WEEK)),
                        time = data.getInt("time", 0)
                    )
                )
            }
            "lesson_morning" -> scheduleMorningLessonSummary(context)
        }
    }

    private fun remindersActive(context: Context): Boolean =
        NotificationScheduler.isEnabled(context, NotificationScheduler.KEY_STUDY_ENABLED, false) &&
            !isPaused(context)

    private fun nextAlertTime(
        day: Int,
        timeMinutes: Int,
        repeatsWeekly: Boolean,
        date: String? = null
    ): Long? {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        if (repeatsWeekly) {
            calendar.set(Calendar.DAY_OF_WEEK, day.coerceIn(Calendar.SUNDAY, Calendar.SATURDAY))
        } else {
            val dateValue = date ?: return null
            val dateCalendar = try {
                Calendar.getInstance().apply {
                    time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateValue)
                        ?: return null
                }
            } catch (_: ParseException) {
                return null
            }
            calendar.set(
                dateCalendar.get(Calendar.YEAR),
                dateCalendar.get(Calendar.MONTH),
                dateCalendar.get(Calendar.DAY_OF_MONTH)
            )
        }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.MINUTE, timeMinutes)
        if (calendar.timeInMillis <= now) {
            if (!repeatsWeekly) return null
            calendar.add(Calendar.DAY_OF_YEAR, 7)
        }
        return calendar.timeInMillis
    }

    private fun enqueue(
        context: Context,
        uniqueName: String,
        tag: String,
        delayMillis: Long,
        data: Data
    ) {
        val request = OneTimeWorkRequestBuilder<OrganizerReminderWorker>()
            .setInputData(data)
            .setInitialDelay(delayMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            .addTag(tag)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
