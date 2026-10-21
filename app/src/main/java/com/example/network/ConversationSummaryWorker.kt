package com.example.network

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

class ConversationSummaryWorker(
    context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {
    override suspend fun doWork(): Result {
        val conversationId = inputData.getString(KEY_CONVERSATION_ID) ?: return Result.failure()
        val expectedUid = inputData.getString(KEY_USER_ID) ?: return Result.failure()
        val user = FirebaseAuth.getInstance().currentUser ?: return Result.retry()
        if (user.uid != expectedUid) return Result.success()

        return try {
            val messages = AppDatabase.getDatabase(applicationContext)
                .appDao()
                .getMessagesForSession(conversationId)
            if (messages.isNotEmpty()) {
                GeminiApiClient.generateConversationSummary(conversationId, messages)
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Log.w(TAG, "Cloud summary request failed for conversation $conversationId", error)
            Result.retry()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to summarize conversation $conversationId", error)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "ConversationSummary"
        private const val KEY_CONVERSATION_ID = "conversation_id"
        private const val KEY_USER_ID = "user_id"
        private const val WORK_PREFIX = "conversation_summary_"
        private const val SUMMARY_DELAY_HOURS = 24L

        fun schedule(context: Context, conversationId: String, userId: String) {
            val request = OneTimeWorkRequestBuilder<ConversationSummaryWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_CONVERSATION_ID, conversationId)
                        .putString(KEY_USER_ID, userId)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(SUMMARY_DELAY_HOURS, TimeUnit.HOURS)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_PREFIX + conversationId,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context, conversationId: String) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(WORK_PREFIX + conversationId)
        }
    }
}
