package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "study_sessions")
data class StudySession(
    @PrimaryKey val id: String,
    val subject: String,
    val startTime: Int,
    val duration: Int,
    val day: Int,
    val reminderMinutes: Int,
    val date: String? = null,
    val repeatsWeekly: Boolean = true,
    val completed: Boolean = false
)

@Entity(tableName = "lessons")
data class Lesson(
    @PrimaryKey val id: String,
    val name: String,
    val dayOfWeek: Int,
    val time: Int,
    val reminderMinutes: Int = 15
)

@Dao
interface OrganizerDao {
    @Query("SELECT * FROM study_sessions ORDER BY day, startTime")
    fun observeStudySessions(): Flow<List<StudySession>>

    @Query("SELECT * FROM study_sessions ORDER BY day, startTime")
    suspend fun getStudySessions(): List<StudySession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStudySession(session: StudySession)

    @Query("DELETE FROM study_sessions WHERE id = :id")
    suspend fun deleteStudySession(id: String)

    @Query("DELETE FROM study_sessions")
    suspend fun deleteAllStudySessions()

    @Query("DELETE FROM study_sessions WHERE repeatsWeekly = :repeatsWeekly")
    suspend fun deleteSessionsByRecurrence(repeatsWeekly: Boolean)

    @Query("SELECT * FROM lessons ORDER BY dayOfWeek, time")
    fun observeLessons(): Flow<List<Lesson>>

    @Query("SELECT * FROM lessons ORDER BY dayOfWeek, time")
    suspend fun getLessons(): List<Lesson>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLesson(lesson: Lesson)

    @Query("DELETE FROM lessons WHERE id = :id")
    suspend fun deleteLesson(id: String)
}

@Database(entities = [StudySession::class, Lesson::class], version = 1, exportSchema = false)
abstract class OrganizerDatabase : RoomDatabase() {
    abstract fun organizerDao(): OrganizerDao

    companion object {
        @Volatile
        private var instance: OrganizerDatabase? = null

        fun getDatabase(context: Context): OrganizerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OrganizerDatabase::class.java,
                    "h2_academic_organizer.db"
                ).build().also { instance = it }
            }
    }
}
