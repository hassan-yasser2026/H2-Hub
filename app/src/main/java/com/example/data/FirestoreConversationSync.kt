package com.example.data

import android.content.Context
import android.util.Log
import com.example.network.parseChatVisionMessage
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentChange
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

    suspend fun signIn(
        uid: String,
        displayName: String,
        email: String,
        photoUrl: String?
    ): Boolean {
        if (uid == activeUserId) return false
        stopListeners()
        activeUserId = uid
        _status.value = ConversationSyncStatus(isSyncing = true)
        val previousOwner = preferences.getString(KEY_CONVERSATION_OWNER, null)
        val accountChanged = previousOwner != null && previousOwner != uid
        if (accountChanged) {
            dao.clearChatHistory()
        } else if (previousOwner == null) {
            migrateLocalHistory(uid)
        }
        preferences.edit().putString(KEY_CONVERSATION_OWNER, uid).apply()

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
        activeUserId = null
        if (preferences.contains(KEY_CONVERSATION_OWNER)) {
            dao.clearChatHistory()
        }
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

    private suspend fun migrateLocalHistory(uid: String) {
        val localSessions = dao.getAllSessionsFlow().first()
        localSessions.forEach { session ->
            enqueue(
                conversations(uid).document(session.id)
                    .set(conversationData(session, dao.getMessagesForSession(session.id).size), SetOptions.merge()),
                "تعذر ترحيل محادثة قديمة."
            )
            dao.getMessagesForSession(session.id).forEach { message ->
                enqueue(
                    conversations(uid).document(session.id)
                    .collection(COLLECTION_MESSAGES).document(message.id)
                        .set(message.toFirestoreData()),
                    "تعذر ترحيل رسالة قديمة."
                )
            }
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
        private const val MAX_CONVERSATIONS = 50L
        private const val MAX_BATCH_WRITES = 450
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
    }
}
