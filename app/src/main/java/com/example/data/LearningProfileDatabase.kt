package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "learning_conversations")
data class LearningConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startedAt: Long,
    val summary: String? = null,
    val summarizedAt: Long? = null,
    val summaryForMessageId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val lastActivityAt: Long = startedAt
)

@Entity(tableName = "learning_messages", primaryKeys = ["id"])
data class LearningMessageEntity(
    val id: String,
    val sessionId: String,
    val timestamp: Long,
    val role: String,
    val content: String,
    val generatedImageUrl: String? = null
)

@Entity(tableName = "learning_questions", primaryKeys = ["id"])
data class LearningQuestionEntity(
    val id: String,
    @ColumnInfo(defaultValue = "''")
    val sessionId: String,
    val timestamp: Long,
    val subject: String,
    val topic: String,
    val question: String,
    val markedAsStrength: Boolean = false
)

@Entity(tableName = "learning_user_profile")
data class LearningUserProfileEntity(
    @PrimaryKey val id: String = "local",
    val weakTopicsJson: String = "[]",
    val subjectsJson: String = "[]",
    val level: String = "adaptive",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "learning_quiz_results")
data class LearningQuizResultEntity(
    @PrimaryKey val id: String,
    val title: String,
    val score: Int,
    val totalQuestions: Int,
    val completedAt: Long
)

data class LearningTopicStat(
    val topic: String,
    val subject: String,
    val interactions: Int,
    val strengthCount: Int
) {
    val isWeakness: Boolean get() = interactions >= 3
    val isStrength: Boolean get() = strengthCount > 0
}

@Dao
interface LearningProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: LearningConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: LearningMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<LearningMessageEntity>)

    @Query("SELECT * FROM learning_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessages(sessionId: String): List<LearningMessageEntity>

    @Query("SELECT * FROM learning_conversations WHERE id = :sessionId LIMIT 1")
    suspend fun getConversation(sessionId: String): LearningConversationEntity?

    @Query("DELETE FROM learning_conversations WHERE id = :sessionId")
    suspend fun deleteConversation(sessionId: String)

    @Query("DELETE FROM learning_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM learning_questions WHERE sessionId = :sessionId")
    suspend fun deleteQuestions(sessionId: String)

    @Query("UPDATE learning_conversations SET lastActivityAt = MAX(lastActivityAt, :timestamp) WHERE id = :sessionId")
    suspend fun updateLastActivity(sessionId: String, timestamp: Long)

    @Query("SELECT * FROM learning_conversations WHERE summary IS NOT NULL AND (:excludeSessionId IS NULL OR id != :excludeSessionId) ORDER BY lastActivityAt DESC LIMIT :limit")
    suspend fun getRecentSummaries(limit: Int, excludeSessionId: String?): List<LearningConversationEntity>

    @Query("SELECT * FROM learning_conversations ORDER BY lastActivityAt DESC")
    fun observeConversations(): Flow<List<LearningConversationEntity>>

    @Query("SELECT COUNT(*) FROM learning_questions")
    fun observeQuestionCount(): Flow<Int>

    @Query("SELECT subject, COUNT(*) AS interactions FROM learning_questions GROUP BY subject ORDER BY interactions DESC")
    fun observeSubjectCounts(): Flow<List<SubjectInteractionCount>>

    @Query("SELECT subject, COUNT(*) AS interactions FROM learning_questions GROUP BY subject ORDER BY interactions DESC")
    suspend fun getSubjectCounts(): List<SubjectInteractionCount>

    @Query("SELECT topic, subject, COUNT(*) AS interactions, SUM(CASE WHEN markedAsStrength = 1 THEN 1 ELSE 0 END) AS strengthCount FROM learning_questions GROUP BY topic, subject ORDER BY interactions DESC")
    fun observeTopicStats(): Flow<List<LearningTopicStat>>

    @Query("SELECT topic, subject, COUNT(*) AS interactions, SUM(CASE WHEN markedAsStrength = 1 THEN 1 ELSE 0 END) AS strengthCount FROM learning_questions GROUP BY topic, subject ORDER BY interactions DESC")
    suspend fun getTopicStats(): List<LearningTopicStat>

    @Query("SELECT timestamp FROM learning_questions ORDER BY timestamp ASC")
    fun observeQuestionTimestamps(): Flow<List<Long>>

    @Query("SELECT * FROM learning_questions ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestQuestion(): LearningQuestionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestion(question: LearningQuestionEntity)

    @Query("UPDATE learning_questions SET markedAsStrength = 1 WHERE topic = :topic")
    suspend fun markTopicAsStrength(topic: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserProfile(profile: LearningUserProfileEntity)

    @Query("SELECT * FROM learning_user_profile WHERE id = 'local' LIMIT 1")
    fun observeUserProfile(): Flow<LearningUserProfileEntity?>

    @Query("SELECT * FROM learning_quiz_results ORDER BY completedAt DESC")
    fun observeQuizResults(): Flow<List<LearningQuizResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuizResult(result: LearningQuizResultEntity)

    @Query("DELETE FROM learning_conversations")
    suspend fun clearConversations()

    @Query("DELETE FROM learning_messages")
    suspend fun clearMessages()

    @Query("DELETE FROM learning_questions")
    suspend fun clearQuestions()

    @Query("DELETE FROM learning_user_profile")
    suspend fun clearUserProfile()

    @Query("DELETE FROM learning_quiz_results")
    suspend fun clearQuizResults()
}

data class SubjectInteractionCount(
    val subject: String,
    val interactions: Int
)

@Database(
    entities = [
        LearningConversationEntity::class,
        LearningMessageEntity::class,
        LearningQuestionEntity::class,
        LearningUserProfileEntity::class,
        LearningQuizResultEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class LearningProfileDatabase : RoomDatabase() {
    abstract fun learningProfileDao(): LearningProfileDao

    companion object {
        @Volatile
        private var instance: LearningProfileDatabase? = null

        fun getDatabase(context: Context): LearningProfileDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LearningProfileDatabase::class.java,
                    "h2_learning_profile.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5)
                    .build().also { instance = it }
            }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS learning_quiz_results (" +
                        "id TEXT NOT NULL, title TEXT NOT NULL, score INTEGER NOT NULL, " +
                        "totalQuestions INTEGER NOT NULL, completedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE learning_messages ADD COLUMN generatedImageUrl TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS learning_user_profile (" +
                        "id TEXT NOT NULL, weakTopicsJson TEXT NOT NULL, subjectsJson TEXT NOT NULL, " +
                        "level TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE learning_conversations ADD COLUMN lastActivityAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE learning_conversations SET lastActivityAt = startedAt"
                )
                db.execSQL(
                    "ALTER TABLE learning_questions ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}
