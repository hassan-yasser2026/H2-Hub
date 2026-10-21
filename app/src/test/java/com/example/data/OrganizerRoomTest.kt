package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OrganizerRoomTest {
    @Test
    fun storesStudySessionsAndLessonsAndAllowsEditsAndDeletes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, OrganizerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = database.organizerDao()

        try {
            val session = StudySession(
                id = "math-monday",
                subject = "رياضيات",
                startTime = 17 * 60,
                duration = 50,
                day = 2,
                reminderMinutes = 10,
                repeatsWeekly = true
            )
            val lesson = Lesson(
                id = "arabic-sunday",
                name = "عربي",
                dayOfWeek = 1,
                time = 19 * 60
            )
            dao.saveStudySession(session)
            dao.saveLesson(lesson)

            assertEquals(listOf(session), dao.observeStudySessions().first())
            assertEquals(listOf(lesson), dao.observeLessons().first())

            dao.saveStudySession(session.copy(startTime = 18 * 60, completed = true))
            assertEquals(18 * 60, dao.getStudySessions().single().startTime)
            assertTrue(dao.getStudySessions().single().completed)

            dao.deleteStudySession(session.id)
            dao.deleteLesson(lesson.id)
            assertTrue(dao.getStudySessions().isEmpty())
            assertFalse(dao.getLessons().any { it.id == lesson.id })
        } finally {
            database.close()
        }
    }
}
