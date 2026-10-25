package com.example.data

import android.content.Context
import android.util.Log
import com.example.network.parseChatVisionMessage
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ConversationSyncStatus(
    val isSyncing: Boolean = false,
    val error: String? = null
)

class FirestoreConversationSync(
    context: Context,
    private val dao: AppDao,
    private val organizerDao: OrganizerDao,
    private val scope: CoroutineScope,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val preferences = context.getSharedPreferences("h2hub_data", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(ConversationSyncStatus())
    val status: StateFlow<ConversationSyncStatus> = _status.asStateFlow()

    @Volatile
    private var activeUserId: String? = null
    private var conversationsListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null
    private var watchedSessionId: String? = null
    private var settingsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    suspend fun signIn(
        uid: String,
        displayName: String,
        email: String,
        photoUrl: String?
    ): Boolean {
        if (uid == activeUserId) return false
        stopListeners()
        settingsListener?.let(accountPreferences::unregisterOnSharedPreferenceChangeListener)
        settingsListener = null
        activeUserId = uid
        _status.value = ConversationSyncStatus(isSyncing = true)
        val previousOwner = preferences.getString(KEY_CONVERSATION_OWNER, null)
        val accountChanged = previousOwner != null && previousOwner != uid
        if (accountChanged) {
            dao.clearChatHistory()
            clearAccountData()
            accountPreferences.edit().clear().apply()
        } else {
            try {
                migrateLocalHistory(uid)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportError("تعذر رفع بعض المحادثات المحفوظة لهذا الحساب.", error)
            }
        }
        preferences.edit().putString(KEY_CONVERSATION_OWNER, uid).apply()

        try {
            syncAccountData(uid)
            listenForSettings(uid)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportError("تعذرت مزامنة بياناتك المحفوظة مع الحساب.", error)
        }
        try {
            syncProfile(uid, displayName, email, photoUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportError("تعذر تحديث ملف الحساب في السحابة.", error)
        }
        try {
            loadLatestConversations(uid)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportError("تعذرت مزامنة المحادثات؛ ستظل المحادثات الجديدة محفوظة محلياً.", error)
        }
        listenForConversations(uid)
        if (_status.value.error == null) _status.value = ConversationSyncStatus()
        return accountChanged
    }

    suspend fun signOut() {
        stopListeners()
        settingsListener?.let(accountPreferences::unregisterOnSharedPreferenceChangeListener)
        settingsListener = null
        activeUserId = null
        _status.value = ConversationSyncStatus()
    }

    fun watchMessages(sessionId: String) {
        val uid = activeUserId ?: return
        if (sessionId == watchedSessionId) return
        messagesListener?.remove()
        watchedSessionId = sessionId
        messagesListener = conversations(uid)
            .document(sessionId)
            .collection(COLLECTION_MESSAGES)
            .orderBy(FIELD_TIMESTAMP, Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    reportError("تعذر تحديث رسائل هذه المحادثة من السحابة.", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                scope.launch {
                    try {
                        snapshot.documents.mapNotNull { document -> document.toChatMessage() }
                            .forEach { dao.insertMessage(it) }
                        snapshot.documentChanges
                            .filter { it.type == DocumentChange.Type.REMOVED }
                            .forEach { change ->
                                dao.deleteMessage(sessionId, change.document.id)
                            }
                        clearError()
                    } catch (failure: Exception) {
                        reportError("تعذر حفظ رسائل المحادثة على هذا الجهاز.", failure)
                    }
                }
            }
    }

    fun saveSession(session: ChatSession) {
        val uid = activeUserId ?: return
        scope.launch {
            try {
                saveSessionNow(uid, session)
                clearError()
            } catch (error: Exception) {
                reportError("تعذر مزامنة عنوان المحادثة.", error)
            }
        }
    }

    fun saveMessage(session: ChatSession, message: ChatMessage) {
        val uid = activeUserId ?: return
        scope.launch {
            try {
                val conversation = conversationData(
                    session,
                    dao.getMessagesForSession(session.id).size
                )
                val batch = firestore.batch()
                batch.set(conversations(uid).document(session.id), conversation, SetOptions.merge())
                batch.set(
                    conversations(uid).document(session.id)
                        .collection(COLLECTION_MESSAGES).document(message.id),
                    message.toFirestoreData()
                )
                batch.commit().await()
                clearError()
            } catch (error: Exception) {
                reportError(
                    "تعذر مزامنة رسالة؛ احتفظ التطبيق بنسخة محلية وسيحاول مجدداً عند عودة الاتصال.",
                    error
                )
            }
        }
    }

    fun saveOrphanMessage(message: ChatMessage) {
        val uid = activeUserId ?: return
        scope.launch {
            try {
                conversations(uid).document(message.sessionId)
                    .collection(COLLECTION_MESSAGES).document(message.id)
                    .set(message.toFirestoreData()).await()
                clearError()
            } catch (error: Exception) {
                reportError("تعذر مزامنة رسالة المحادثة.", error)
            }
        }
    }

    fun deleteSession(sessionId: String) {
        val uid = activeUserId ?: return
        scope.launch {
            try {
                val conversation = conversations(uid).document(sessionId)
                val messages = conversation.collection(COLLECTION_MESSAGES).get().await()
                messages.documents.chunked(MAX_BATCH_WRITES).forEach { documents ->
                    val batch = firestore.batch()
                    documents.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
                conversation.delete().await()
                clearError()
            } catch (error: Exception) {
                reportError("تعذر حذف المحادثة من السحابة.", error)
            }
        }
    }

    fun deleteMessages(sessionId: String) {
        val uid = activeUserId ?: return
        scope.launch {
            try {
                val messages = conversations(uid).document(sessionId)
                    .collection(COLLECTION_MESSAGES).get().await()
                messages.documents.chunked(MAX_BATCH_WRITES).forEach { documents ->
                    val batch = firestore.batch()
                    documents.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
                clearError()
            } catch (error: Exception) {
                reportError("تعذر حذف رسائل المحادثة من السحابة.", error)
            }
        }
    }

    fun saveProductivityDoc(doc: ProductivityDoc) {
                    val uid = activeUserId ?: return
                    scope.launch {
                        try {
                            accountData(uid).collection(COLLECTION_PRODUCTIVITY_DOCS)
                                .document(doc.id).set(doc.toFirestoreData()).await()
                        } catch (error: Exception) {
                            reportError("تعذر مزامنة الملف التعليمي مع حسابك.", error)
                        }
                    }
                }

                fun deleteProductivityDoc(id: String) {
                    val uid = activeUserId ?: return
                    scope.launch {
                        try {
                            accountData(uid).collection(COLLECTION_PRODUCTIVITY_DOCS)
                                .document(id).delete().await()
                        } catch (error: Exception) {
                            reportError("تعذر حذف الملف التعليمي من حسابك.", error)
                        }
                    }
                }

                fun saveUserSchedule(schedule: UserSchedule) {
                    val uid = activeUserId ?: return
                    scope.launch {
                        try {
                            accountData(uid).collection(COLLECTION_SETTINGS).document(DOCUMENT_SCHEDULE)
                                .set(schedule.toFirestoreData()).await()
                        } catch (error: Exception) {
                            reportError("تعذرت مزامنة جدولك مع حسابك.", error)
                        }
                    }
                }

                fun deleteUserSchedule() {
                    val uid = activeUserId ?: return
                    scope.launch {
                        try {
                            accountData(uid).collection(COLLECTION_SETTINGS).document(DOCUMENT_SCHEDULE)
                                .delete().await()
                        } catch (error: Exception) {
                            reportError("تعذر حذف جدولك من حسابك.", error)
                        }
                    }
                }

                fun saveQuranRecord(record: QuranRecord) {
                    val uid = activeUserId ?: return
                    scope.launch {
                        try {
                            accountData(uid).collection(COLLECTION_QURAN_RECORDS)
                                .document(record.id).set(record.toFirestoreData()).await()
                        } catch (error: Exception) {
                            reportError("تعذر مزامنة سجل التجويد مع حسابك.", error)
                        }
                    }
                }

                fun deleteQuranRecord(id: String) {
                    val uid = activeUserId ?: return
                    scope.launch {
                        try {
                            accountData(uid).collection(COLLECTION_QURAN_RECORDS)
                                .document(id).delete().await()
                        } catch (error: Exception) {
                            reportError("تعذر حذف سجل التجويد من حسابك.", error)
                        }
                    }
                }

    fun saveStudySession(session: StudySession) {
        val uid = activeUserId ?: return
        scope.launch {
            try {
                accountData(uid).collection(COLLECTION_STUDY_SESSIONS)
                    .document(session.id).set(session.toFirestoreData()).await()
            } catch (error: Exception) {
                reportError("تعذر مزامنة جدول المذاكرة مع حسابك.", error)
            }
        }
    }

    fun deleteStudySession(id: String) {
        val uid = activeUserId ?: return
        scope.launch {
            try {
                accountData(uid).collection(COLLECTION_STUDY_SESSIONS).document(id).delete().await()
            } catch (error: Exception) {
                reportError("تعذر حذف جلسة المذاكرة من حسابك.", error)
            }
        }
    }

    fun saveLesson(lesson: Lesson) {
        val uid = activeUserId ?: return
        scope.launch {
            try {
                accountData(uid).collection(COLLECTION_LESSONS)
                    .document(lesson.id).set(lesson.toFirestoreData()).await()
            } catch (error: Exception) {
                reportError("تعذرت مزامنة الدرس مع حسابك.", error)
            }
        }
    }

    fun deleteLesson(id: String) {
        val uid = activeUserId ?: return
        scope.launch {
            try {
                accountData(uid).collection(COLLECTION_LESSONS).document(id).delete().await()
            } catch (error: Exception) {
                reportError("تعذر حذف الدرس من حسابك.", error)
            }
        }
    }

                private val accountPreferences =
                    context.getSharedPreferences(PREFERENCES_ACCOUNT_SETTINGS, Context.MODE_PRIVATE)

                private suspend fun syncAccountData(uid: String) {
                    val root = accountData(uid)
                    val marker = root.collection(COLLECTION_SETTINGS).document(DOCUMENT_DATA_VERSION)
                    val remoteDataExists = marker.get().await().exists()
                    if (remoteDataExists) {
                        loadAccountData(uid)
                    } else {
                        uploadAccountData(uid)
                        marker.set(mapOf(FIELD_DATA_VERSION to 1L)).await()
                    }
                }

                private suspend fun uploadAccountData(uid: String) {
                    val root = accountData(uid)
                    dao.getAllProductivityDocsFlow().first().forEach { doc ->
                        root.collection(COLLECTION_PRODUCTIVITY_DOCS).document(doc.id)
                            .set(doc.toFirestoreData()).await()
                    }
                    dao.getAllQuranRecordsFlow().first().forEach { record ->
                        root.collection(COLLECTION_QURAN_RECORDS).document(record.id)
                            .set(record.toFirestoreData()).await()
                    }
                    dao.getUserSchedule()?.let { schedule ->
                        root.collection(COLLECTION_SETTINGS).document(DOCUMENT_SCHEDULE)
                            .set(schedule.toFirestoreData()).await()
                    }
                    organizerDao.getStudySessions().forEach { session ->
                        root.collection(COLLECTION_STUDY_SESSIONS).document(session.id)
                            .set(session.toFirestoreData()).await()
                    }
                    organizerDao.getLessons().forEach { lesson ->
                        root.collection(COLLECTION_LESSONS).document(lesson.id)
                            .set(lesson.toFirestoreData()).await()
                    }
                    saveSettings(uid)
                }

                private suspend fun loadAccountData(uid: String) {
                    val root = accountData(uid)
                    val localDocs = dao.getAllProductivityDocsFlow().first()
                    val remoteDocs = root.collection(COLLECTION_PRODUCTIVITY_DOCS).get().await()
                        .documents.mapNotNull { it.toProductivityDoc() }
                    remoteDocs.forEach { remote ->
                        if (localDocs.none { it.id == remote.id }) {
                            dao.insertProductivityDoc(remote)
                        }
                    }
                    localDocs.forEach { doc ->
                        root.collection(COLLECTION_PRODUCTIVITY_DOCS).document(doc.id)
                            .set(doc.toFirestoreData()).await()
                    }
                    val localRecords = dao.getAllQuranRecordsFlow().first()
                    val remoteRecords = root.collection(COLLECTION_QURAN_RECORDS).get().await()
                        .documents.mapNotNull { it.toQuranRecord() }
                    remoteRecords.forEach { remote ->
                        if (localRecords.none { it.id == remote.id }) {
                            dao.insertQuranRecord(remote)
                        }
                    }
                    localRecords.forEach { record ->
                        root.collection(COLLECTION_QURAN_RECORDS).document(record.id)
                            .set(record.toFirestoreData()).await()
                    }
                    val scheduleSnapshot = root.collection(COLLECTION_SETTINGS).document(DOCUMENT_SCHEDULE).get().await()
                    val localSchedule = dao.getUserSchedule()
                    if (scheduleSnapshot.exists()) {
                        if (localSchedule == null) {
                            scheduleSnapshot.toUserSchedule()?.let { dao.insertUserSchedule(it) }
                        } else {
                            root.collection(COLLECTION_SETTINGS).document(DOCUMENT_SCHEDULE)
                                .set(localSchedule.toFirestoreData()).await()
                        }
                    }
                    val localStudySessions = organizerDao.getStudySessions()
                    val remoteStudySessions = root.collection(COLLECTION_STUDY_SESSIONS).get().await()
                        .documents.mapNotNull { it.toStudySession() }
                    remoteStudySessions.forEach { remote ->
                        if (localStudySessions.none { it.id == remote.id }) organizerDao.saveStudySession(remote)
                    }
                    localStudySessions.forEach { session ->
                        root.collection(COLLECTION_STUDY_SESSIONS).document(session.id)
                            .set(session.toFirestoreData()).await()
                    }
                    val localLessons = organizerDao.getLessons()
                    val remoteLessons = root.collection(COLLECTION_LESSONS).get().await()
                        .documents.mapNotNull { it.toLesson() }
                    remoteLessons.forEach { remote ->
                        if (localLessons.none { it.id == remote.id }) organizerDao.saveLesson(remote)
                    }
                    localLessons.forEach { lesson ->
                        root.collection(COLLECTION_LESSONS).document(lesson.id)
                            .set(lesson.toFirestoreData()).await()
                    }
                    val settings = root.collection(COLLECTION_SETTINGS).document(DOCUMENT_PREFERENCES).get().await()
                    if (settings.exists()) {
                        val values = settings.get(FIELD_PREFERENCES) as? Map<*, *>
                        if (values != null) {
                            val editor = accountPreferences.edit()
                            values.forEach { (key, value) ->
                                if (key is String && value != null) {
                                    when (value) {
                                        is Boolean -> editor.putBoolean(key, value)
                                        is String -> editor.putString(key, value)
                                        is Long -> editor.putLong(key, value)
                                        is Int -> editor.putInt(key, value)
                                        is Double -> editor.putFloat(key, value.toFloat())
                                    }
                                }
                            }
                            editor.apply()
                        }
                    }
                    uploadAccountData(uid)
                }

                private suspend fun clearAccountData() {
                    dao.getAllProductivityDocsFlow().first().forEach { dao.deleteProductivityDoc(it.id) }
                    dao.getAllQuranRecordsFlow().first().forEach { dao.deleteQuranRecord(it.id) }
                    dao.deleteUserSchedule()
                    organizerDao.deleteAllStudySessions()
                    organizerDao.getLessons().forEach { organizerDao.deleteLesson(it.id) }
                }

                private fun listenForSettings(uid: String) {
                    settingsListener?.let(accountPreferences::unregisterOnSharedPreferenceChangeListener)
                    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                        scope.launch { saveSettings(uid) }
                    }
                    settingsListener = listener
                    accountPreferences.registerOnSharedPreferenceChangeListener(listener)
                }

                private suspend fun saveSettings(uid: String) {
                    val values = accountPreferences.all.mapNotNull { (key, value) ->
                        when (value) {
                            is Boolean, is String, is Int, is Long -> key to value
                            is Float -> key to value.toDouble()
                            else -> null
                        }
                    }.toMap()
                    accountData(uid).collection(COLLECTION_SETTINGS).document(DOCUMENT_PREFERENCES)
                        .set(mapOf(FIELD_PREFERENCES to values)).await()
                }

    private fun accountData(uid: String): DocumentReference =
        firestore.collection(COLLECTION_USERS).document(uid)

                private fun ProductivityDoc.toFirestoreData(): Map<String, Any> = mapOf(
                    FIELD_TYPE to type,
                    FIELD_TITLE to title,
                    FIELD_CONTENT to content,
                    FIELD_TIMESTAMP to timestamp
                )

                private fun UserSchedule.toFirestoreData(): Map<String, Any> = mapOf(
                    FIELD_AGE to age,
                    FIELD_OCCUPATION to occupation,
                    FIELD_SCHOOL_TIMINGS to schoolOrWorkTimings,
                    FIELD_LESSON_TIMINGS to lessonTimings,
                    FIELD_SCHEDULE_TEXT to scheduleText,
                    FIELD_TIMESTAMP to timestamp
                )

                private fun QuranRecord.toFirestoreData(): Map<String, Any> = mapOf(
                    FIELD_SURAH to surah,
                    FIELD_TRANSCRIPTION to userTranscription,
                    FIELD_FEEDBACK to aiFeedback,
                    FIELD_SCORE to score,
                    FIELD_TIMESTAMP to timestamp,
                    FIELD_SURAH_NUMBER to surahNumber,
                    FIELD_AYAH_NUMBER to ayahNumber,
                    FIELD_ASSESSMENT to assessmentJson
                )

                private fun StudySession.toFirestoreData(): Map<String, Any?> = mapOf(
                    "subject" to subject,
                    "startTime" to startTime,
                    "duration" to duration,
                    "day" to day,
                    "reminderMinutes" to reminderMinutes,
                    "date" to date,
                    "repeatsWeekly" to repeatsWeekly,
                    "completed" to completed
                )

                private fun Lesson.toFirestoreData(): Map<String, Any> = mapOf(
                    "name" to name,
                    "dayOfWeek" to dayOfWeek,
                    "time" to time,
                    "reminderMinutes" to reminderMinutes
                )

    private fun DocumentSnapshot.toProductivityDoc(): ProductivityDoc? {
        val type = getString(FIELD_TYPE) ?: return null
        val title = getString(FIELD_TITLE) ?: return null
        val content = getString(FIELD_CONTENT) ?: return null
        val timestamp = getLong(FIELD_TIMESTAMP) ?: return null
        return ProductivityDoc(id, type, title, content, timestamp)
    }

    private fun DocumentSnapshot.toUserSchedule(): UserSchedule? {
        val age = (getLong(FIELD_AGE) ?: return null).toInt()
        val occupation = getString(FIELD_OCCUPATION) ?: return null
        val schoolTimings = getString(FIELD_SCHOOL_TIMINGS) ?: return null
        val lessonTimings = getString(FIELD_LESSON_TIMINGS) ?: return null
        val scheduleText = getString(FIELD_SCHEDULE_TEXT) ?: return null
        val timestamp = getLong(FIELD_TIMESTAMP) ?: return null
        return UserSchedule(
            DOCUMENT_SCHEDULE, age, occupation, schoolTimings, lessonTimings, scheduleText, timestamp
        )
    }

    private fun DocumentSnapshot.toQuranRecord(): QuranRecord? {
        val surah = getString(FIELD_SURAH) ?: return null
        val transcription = getString(FIELD_TRANSCRIPTION) ?: return null
        val feedback = getString(FIELD_FEEDBACK) ?: return null
        val score = (getLong(FIELD_SCORE) ?: return null).toInt()
        val timestamp = getLong(FIELD_TIMESTAMP) ?: return null
        return QuranRecord(
            id,
            surah,
            transcription,
            feedback,
            score,
            timestamp,
            (getLong(FIELD_SURAH_NUMBER) ?: 0).toInt(),
            (getLong(FIELD_AYAH_NUMBER) ?: 0).toInt(),
            getString(FIELD_ASSESSMENT).orEmpty()
        )
    }

    private fun DocumentSnapshot.toStudySession(): StudySession? {
        val subject = getString("subject") ?: return null
        return StudySession(
            id = id,
            subject = subject,
            startTime = (getLong("startTime") ?: return null).toInt(),
            duration = (getLong("duration") ?: return null).toInt(),
            day = (getLong("day") ?: return null).toInt(),
            reminderMinutes = (getLong("reminderMinutes") ?: 15).toInt(),
            date = getString("date"),
            repeatsWeekly = getBoolean("repeatsWeekly") ?: true,
            completed = getBoolean("completed") ?: false
        )
    }

    private fun DocumentSnapshot.toLesson(): Lesson? {
        val name = getString("name") ?: return null
        return Lesson(
            id = id,
            name = name,
            dayOfWeek = (getLong("dayOfWeek") ?: return null).toInt(),
            time = (getLong("time") ?: return null).toInt(),
            reminderMinutes = (getLong("reminderMinutes") ?: 15).toInt()
        )
    }

    private suspend fun migrateLocalHistory(uid: String) {
        val localSessions = dao.getAllSessionsFlow().first()
        localSessions.forEach { session ->
            val conversation = conversations(uid).document(session.id)
            val messages = dao.getMessagesForSession(session.id)
            var batch = firestore.batch()
            var writeCount = 0
            batch.set(
                conversation,
                conversationData(session, messages.size),
                SetOptions.merge()
            )
            writeCount++
            messages.forEach { message ->
                if (writeCount == MAX_BATCH_WRITES) {
                    batch.commit().await()
                    batch = firestore.batch()
                    writeCount = 0
                }
                batch.set(
                    conversation.collection(COLLECTION_MESSAGES).document(message.id),
                    message.toFirestoreData()
                )
                writeCount++
            }
            if (writeCount > 0) batch.commit().await()
        }
    }

    private suspend fun syncProfile(
        uid: String,
        displayName: String,
        email: String,
        photoUrl: String?
    ) {
        val reference = firestore.collection(COLLECTION_USERS).document(uid)
        val existing = reference.get().await()
        val profile = mutableMapOf<String, Any>(
            FIELD_DISPLAY_NAME to displayName,
            FIELD_EMAIL to email,
            FIELD_LAST_SEEN_AT to FieldValue.serverTimestamp()
        )
        if (!existing.exists()) profile[FIELD_CREATED_AT] = FieldValue.serverTimestamp()
        photoUrl?.let { profile[FIELD_PHOTO_URL] = it }
        enqueue(reference.set(profile, SetOptions.merge()), "تعذر حفظ بيانات الملف الشخصي.")
    }

    private suspend fun loadLatestConversations(uid: String) {
        val snapshot = conversations(uid)
            .orderBy(FIELD_UPDATED_AT, Query.Direction.DESCENDING)
            .limit(MAX_CONVERSATIONS)
            .get()
            .await()
        coroutineScope {
            snapshot.documents.map { document ->
                async {
                    val session = document.toChatSession() ?: return@async
                    dao.insertSession(session)
                    val messages = document.reference.collection(COLLECTION_MESSAGES)
                        .orderBy(FIELD_TIMESTAMP, Query.Direction.ASCENDING)
                        .get()
                        .await()
                    messages.documents.mapNotNull { message -> message.toChatMessage() }
                        .forEach { dao.insertMessage(it) }
                }
            }.awaitAll()
        }
    }

    private fun listenForConversations(uid: String) {
        conversationsListener?.remove()
        conversationsListener = conversations(uid)
            .orderBy(FIELD_UPDATED_AT, Query.Direction.DESCENDING)
            .limit(MAX_CONVERSATIONS)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    reportError("تعذر تحديث قائمة المحادثات من السحابة.", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                scope.launch {
                    try {
                        snapshot.documents.forEach { document ->
                            val session = document.toChatSession() ?: return@forEach
                            dao.insertSession(session)
                        }
                        snapshot.documentChanges
                            .filter { it.type == DocumentChange.Type.REMOVED }
                            .forEach { change ->
                                val removedSession = change.document.reference.get().await()
                                if (!removedSession.exists()) {
                                    dao.deleteSession(change.document.id)
                                    dao.deleteMessagesForSession(change.document.id)
                                }
                            }
                        clearError()
                    } catch (failure: Exception) {
                        reportError("تعذر تحديث قائمة المحادثات على هذا الجهاز.", failure)
                    }
                }
            }
    }

    private suspend fun saveSessionNow(uid: String, session: ChatSession) {
        val count = dao.getMessagesForSession(session.id).size
        conversations(uid).document(session.id)
            .set(conversationData(session, count), SetOptions.merge())
            .await()
    }

    private fun conversationData(session: ChatSession, messageCount: Int): Map<String, Any?> =
        mapOf(
            FIELD_TITLE to session.title,
            FIELD_CREATED_AT to session.timestamp,
            FIELD_UPDATED_AT to session.updatedAt,
            FIELD_PERSONA_ID to session.personaId,
            FIELD_MESSAGE_COUNT to messageCount
        )

    private fun ChatMessage.toFirestoreData(): Map<String, Any?> {
        val attachment = parseChatVisionMessage(content)
        val safeContent = if (attachment == null) content else
            "[أُرسلت صورة في هذه الرسالة، لكن الصورة محفوظة على الجهاز الأصلي فقط]\n${attachment.question}"
        return mapOf(
            FIELD_ROLE to role,
            FIELD_CONTENT to safeContent,
            FIELD_TIMESTAMP to timestamp,
            FIELD_SOURCES to sources.map { source ->
                mapOf(
                    "title" to source.title,
                    "url" to source.url,
                    "reference" to source.reference
                ).filterValues { it != null }
            },
            FIELD_GENERATED_IMAGE_URL to generatedImageUrl,
            FIELD_THINKING_SECONDS to thinkingSeconds
        )
    }

    private fun DocumentSnapshot.toChatSession(): ChatSession? {
        val createdAt = getLong(FIELD_CREATED_AT) ?: return null
        val updatedAt = getLong(FIELD_UPDATED_AT) ?: createdAt
        return ChatSession(
            id = id,
            title = getString(FIELD_TITLE).orEmpty().ifBlank { "محادثة جديدة" },
            timestamp = createdAt,
            personaId = getString(FIELD_PERSONA_ID),
            updatedAt = updatedAt
        )
    }

    private fun DocumentSnapshot.toChatMessage(): ChatMessage? {
        val role = getString(FIELD_ROLE) ?: return null
        val timestamp = getLong(FIELD_TIMESTAMP) ?: return null
        val content = getString(FIELD_CONTENT) ?: return null
        val sources = (get(FIELD_SOURCES) as? List<*>).orEmpty().mapNotNull { raw ->
            val source = raw as? Map<*, *> ?: return@mapNotNull null
            val url = source["url"] as? String ?: return@mapNotNull null
            ChatSource(
                title = source["title"] as? String ?: url,
                url = url,
                reference = (source["reference"] as? Number)?.toInt()
            )
        }
        return ChatMessage(
            id = id,
            sessionId = reference.parent.parent?.id ?: return null,
            role = role,
            content = content,
            timestamp = timestamp,
            sources = sources,
            generatedImageUrl = getString(FIELD_GENERATED_IMAGE_URL),
            thinkingSeconds = (get(FIELD_THINKING_SECONDS) as? Number)?.toInt()
        )
    }

    private fun conversations(uid: String) =
        firestore.collection(COLLECTION_USERS).document(uid).collection(COLLECTION_CONVERSATIONS)

    private fun stopListeners() {
        conversationsListener?.remove()
        messagesListener?.remove()
        conversationsListener = null
        messagesListener = null
        watchedSessionId = null
    }

    private fun clearError() {
        if (_status.value.error != null) _status.value = ConversationSyncStatus()
    }

    private fun reportError(message: String, error: Exception) {
        Log.e(TAG, message, error)
        _status.value = ConversationSyncStatus(error = message)
    }

    private fun enqueue(task: Task<*>, message: String) {
        task.addOnSuccessListener { clearError() }
            .addOnFailureListener { error -> reportError(message, error) }
    }

    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            addOnCanceledListener { continuation.cancel(CancellationException("Firestore task cancelled")) }
        }

    companion object {
        private const val TAG = "FirestoreConversationSync"
        private const val KEY_CONVERSATION_OWNER = "conversation_owner_uid"
        private const val COLLECTION_USERS = "users"
        private const val COLLECTION_CONVERSATIONS = "conversations"
        private const val COLLECTION_MESSAGES = "messages"
        private const val COLLECTION_PRODUCTIVITY_DOCS = "productivity_docs"
        private const val COLLECTION_QURAN_RECORDS = "quran_records"
        private const val COLLECTION_SETTINGS = "settings"
        private const val COLLECTION_STUDY_SESSIONS = "study_sessions"
        private const val COLLECTION_LESSONS = "lessons"
        private const val PREFERENCES_ACCOUNT_SETTINGS = "h2hub_prefs"
        private const val MAX_CONVERSATIONS = 50L
        private const val MAX_BATCH_WRITES = 450
        private const val DOCUMENT_DATA_VERSION = "data_version"
        private const val DOCUMENT_SCHEDULE = "user_schedule"
        private const val DOCUMENT_PREFERENCES = "preferences"
        private const val FIELD_DATA_VERSION = "version"
        private const val FIELD_PREFERENCES = "values"
        private const val FIELD_DISPLAY_NAME = "displayName"
        private const val FIELD_EMAIL = "email"
        private const val FIELD_PHOTO_URL = "photoUrl"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_LAST_SEEN_AT = "lastSeenAt"
        private const val FIELD_TITLE = "title"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val FIELD_PERSONA_ID = "personaId"
        private const val FIELD_MESSAGE_COUNT = "messageCount"
        private const val FIELD_ROLE = "role"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_TIMESTAMP = "timestamp"
        private const val FIELD_SOURCES = "sources"
        private const val FIELD_GENERATED_IMAGE_URL = "generatedImageUrl"
        private const val FIELD_THINKING_SECONDS = "thinkingSeconds"
        private const val FIELD_TYPE = "type"
        private const val FIELD_AGE = "age"
        private const val FIELD_OCCUPATION = "occupation"
        private const val FIELD_SCHOOL_TIMINGS = "schoolOrWorkTimings"
        private const val FIELD_LESSON_TIMINGS = "lessonTimings"
        private const val FIELD_SCHEDULE_TEXT = "scheduleText"
        private const val FIELD_SURAH = "surah"
        private const val FIELD_TRANSCRIPTION = "userTranscription"
        private const val FIELD_FEEDBACK = "aiFeedback"
        private const val FIELD_SCORE = "score"
        private const val FIELD_SURAH_NUMBER = "surahNumber"
        private const val FIELD_AYAH_NUMBER = "ayahNumber"
        private const val FIELD_ASSESSMENT = "assessmentJson"
    }
}
