package com.example.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.auth.AuthManager
import com.example.auth.AuthState
import com.example.network.ApiDeviceIdentity
import com.example.network.GeminiApiClient
import com.example.network.QuotaEvents
import com.example.network.QuotaExceededException
import com.example.network.SubscriptionApiClient
import com.example.network.SubscriptionDeviceId
import com.example.network.UsageSnapshot
import com.example.network.ConversationSummaryWorker
import com.example.network.parseQuotaError
import com.example.network.encodeChatVisionMessage
import com.example.network.parseChatVisionMessage
import com.example.network.QuranAyah
import com.example.network.QuranCoachApiClient
import com.example.network.QuranRecitationResult
import com.example.network.QuranSurah
import com.example.NotificationScheduler
import com.example.OrganizerReminderScheduler
import com.example.PushNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val KEY_SOCRATIC_MODE = "socratic_mode_enabled"
private const val KEY_EDUCATIONAL_CONTENT_MODE = "educational_content_mode_enabled"
private const val KEY_SOCRATIC_PROGRESS_PREFIX = "socratic_progress_"
private const val KEY_SPEECH_RECOGNITION_LOCALE = "speech_recognition_locale"
private const val KEY_TTS_SPEECH_SPEED = "tts_speech_speed"
private const val KEY_PRODUCTIVITY_SUMMARY_FORMAT = "productivity_summary_format"
private const val KEY_DEFAULT_PERSONA_ID = "default_persona_id"
private const val KEY_PERSONA_AUTO_VOICE = "persona_auto_voice"
private const val KEY_ORGANIZER_CONFIRM_DELETE = "organizer_confirm_delete"
private const val KEY_TRACK_LEARNING_PROFILE = "track_learning_profile"
private const val KEY_STUDY_QUIZ_COUNT = "study_quiz_count"

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "AppViewModel"
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("h2hub_prefs", Context.MODE_PRIVATE)
    private val database = AppDatabase.getDatabase(context)
    private val conversationSync = FirestoreConversationSync(
        context,
        database.appDao(),
        viewModelScope
    )
    private val repository = AppRepository(database.appDao(), conversationSync)
    private val _conversationSyncReady = MutableStateFlow(false)
    val conversationSyncReady: StateFlow<Boolean> = _conversationSyncReady.asStateFlow()
    val conversationSyncStatus: StateFlow<ConversationSyncStatus> = conversationSync.status
    private val organizerDao = OrganizerDatabase.getDatabase(context).organizerDao()
    private val learningRepository = LearningProfileRepository(
        LearningProfileDatabase.getDatabase(context).learningProfileDao()
    )
    private val summarizingSessions = mutableSetOf<String>()

    // Text to Speech Fallback (Android Native)
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var speechGeneration = 0
    private data class SpeechSession(
        val id: String,
        val generation: Int,
        val chunks: List<String>,
        val personaId: String?,
        var chunkIndex: Int = 0,
        var characterOffset: Int = 0,
        var utteranceBaseOffset: Int = 0
    )
    private var speechSession: SpeechSession? = null
    private var remoteSpeechJob: Job? = null
    private var remoteSpeechPlayer: MediaPlayer? = null
    private var remoteSpeechFile: File? = null
    private var remoteSpeechPrepared = false

    private var quranMediaRecorder: MediaRecorder? = null
    private var quranRecordingFile: File? = null
    private var quranReciterPlayer: MediaPlayer? = null
    private var quranReciterPlaybackJob: Job? = null
    private var generatedImageSpeechPlayer: MediaPlayer? = null
    private var generatedImageSpeechFile: File? = null

    init {
        // Initialize Android TextToSpeech
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                textToSpeech?.language = Locale.forLanguageTag("ar")
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val session = speechSession ?: return
                        if (utteranceId?.startsWith("${session.generation}:") == true) {
                            _isSpeaking.value = true
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        val session = speechSession ?: return
                        val chunkIndex = utteranceId?.substringAfter(':')?.toIntOrNull()
                        if (
                            session.generation == speechGeneration &&
                            chunkIndex == session.chunkIndex &&
                            _isSpeaking.value
                        ) {
                            session.chunkIndex++
                            session.characterOffset = 0
                            speakCurrentChunk(session)
                        }
                    }

                    @Deprecated("Deprecated in Android")
                    override fun onError(utteranceId: String?) {
                        val session = speechSession ?: return
                        val chunkIndex = utteranceId?.substringAfter(':')?.toIntOrNull()
                        if (
                            session.generation == speechGeneration &&
                            chunkIndex == session.chunkIndex &&
                            _isSpeaking.value
                        ) {
                            _isSpeaking.value = false
                            _activeSpeechId.value = null
                            speechSession = null
                        }
                    }

                    override fun onRangeStart(
                        utteranceId: String?,
                        start: Int,
                        end: Int,
                        frame: Int
                    ) {
                        val session = speechSession ?: return
                        val chunkIndex = utteranceId?.substringAfter(':')?.toIntOrNull()
                        if (
                            session.generation == speechGeneration &&
                            chunkIndex == session.chunkIndex
                        ) {
                            session.characterOffset =
                                (session.utteranceBaseOffset + end).coerceAtMost(
                                    session.chunks[session.chunkIndex].length
                                )
                        }
                    }
                })
                isTtsInitialized = true
                speechSession?.let(::speakCurrentChunk)
            } else {
                Log.e(TAG, "Android text-to-speech initialization failed with status $status")
                speechSession = null
                _isSpeaking.value = false
                _activeSpeechId.value = null
            }
        }
    }

    // --- Safety System Guidelines (Prompt Prefix) ---
    private val safetySystemInstruction = """
        IMPORTANT - SAFETY RULES:
        1. Refuse requests that facilitate harm, abuse, illegal activity, or evasion of an active assessment.
        2. For ordinary learning and homework, answer the user's request helpfully and explain the reasoning.
        3. Do not fabricate facts, citations, capabilities, or actions.
    """.trimIndent()

    // --- State Holders ---

    // 1. Chat States
    val chatSessions: StateFlow<List<ChatSession>> = repository.allSessions
        .map { sessions -> sessions.sortedByDescending { it.updatedAt }.take(50) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _currentMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentMessages: StateFlow<List<ChatMessage>> = _currentMessages.asStateFlow()

    private val _socraticModeEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_SOCRATIC_MODE, false)
    )
    val socraticModeEnabled: StateFlow<Boolean> = _socraticModeEnabled.asStateFlow()

    private val _educationalContentEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_EDUCATIONAL_CONTENT_MODE, false)
    )
    val educationalContentEnabled: StateFlow<Boolean> = _educationalContentEnabled.asStateFlow()

    private val _socraticProgress = MutableStateFlow(SocraticProgress())
    val socraticProgress: StateFlow<SocraticProgress> = _socraticProgress.asStateFlow()
    private val _conversationMemory = MutableStateFlow(GeminiApiClient.ConversationMemory(emptyList(), emptyList(), "", 0))
    val conversationMemory: StateFlow<GeminiApiClient.ConversationMemory> = _conversationMemory.asStateFlow()
    private val _conversationMemoryError = MutableStateFlow<String?>(null)
    val conversationMemoryError: StateFlow<String?> = _conversationMemoryError.asStateFlow()

    val learningQuestionCount: StateFlow<Int> = learningRepository.questionCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val learningSubjectCounts: StateFlow<List<SubjectInteractionCount>> = learningRepository.subjectCounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val learningTopicStats: StateFlow<List<LearningTopicStat>> = learningRepository.topicStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val learningQuestionTimestamps: StateFlow<List<Long>> = learningRepository.questionTimestamps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val learningConversations: StateFlow<List<LearningConversationEntity>> = learningRepository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val learningUserProfile: StateFlow<LearningUserProfileEntity?> = learningRepository.userProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val learningQuizResults: StateFlow<List<LearningQuizResultEntity>> = learningRepository.quizResults
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _generatedStudySet = MutableStateFlow<GeneratedStudySet?>(null)
    val generatedStudySet: StateFlow<GeneratedStudySet?> = _generatedStudySet.asStateFlow()
    private val _isGeneratingStudySet = MutableStateFlow(false)
    val isGeneratingStudySet: StateFlow<Boolean> = _isGeneratingStudySet.asStateFlow()
    private val _studySetError = MutableStateFlow<String?>(null)
    val studySetError: StateFlow<String?> = _studySetError.asStateFlow()
    private var lastStudySetRequest: Pair<String, StudySetMode>? = null

    private val _isGeneratingChat = MutableStateFlow(false)
    val isGeneratingChat: StateFlow<Boolean> = _isGeneratingChat.asStateFlow()

    private val subscriptionApi = SubscriptionApiClient()
    private val _usageState = MutableStateFlow(UsageState())
    val usageState: StateFlow<UsageState> = _usageState.asStateFlow()

    /** Explicit, read-only refresh; the usage endpoint does not consume quota. */
    fun refreshUsage() {
        if (_usageState.value.loading) return
        viewModelScope.launch {
            _usageState.value = _usageState.value.copy(loading = true, error = null)
            try {
                val snapshot = subscriptionApi.usage(SubscriptionDeviceId.get(context))
                _usageState.value = UsageState(snapshot = snapshot)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _usageState.value = _usageState.value.copy(
                    loading = false,
                    error = e.message ?: "تعذر تحميل حدود الاستخدام."
                )
            }
        }
    }

    init {
        ApiDeviceIdentity.install(context)
        viewModelScope.launch { QuotaEvents.events.collect(::onQuotaExceeded) }
        refreshUsage()
    }

    private fun onQuotaExceeded(error: QuotaExceededException) {
        _usageState.value = _usageState.value.withExhausted(error.feature, error.used, error.limit)
        _chatActionError.value = error.message
        refreshUsage()
    }

    private val _chatActionError = MutableStateFlow<String?>(null)
    val chatActionError: StateFlow<String?> = _chatActionError.asStateFlow()

    val useThinkingMode = MutableStateFlow(
        prefs.getBoolean("thinking_mode_enabled", false)
    )

    val webSearchEnabled = MutableStateFlow(prefs.getBoolean("chat_web_search_enabled", false))
    val groqThinkingEnabled = MutableStateFlow(prefs.getBoolean("chat_groq_thinking_enabled", false))
    val imageGenerationEnabled = MutableStateFlow(prefs.getBoolean("chat_image_generation_enabled", false))
    private val _imageGenerationAvailable = MutableStateFlow(false)
    val imageGenerationAvailable: StateFlow<Boolean> = _imageGenerationAvailable.asStateFlow()

    private val _thinkingState = MutableStateFlow<String?>(null)
    val thinkingState: StateFlow<String?> = _thinkingState.asStateFlow()

    fun setWebSearchEnabled(enabled: Boolean) {
        webSearchEnabled.value = enabled
        prefs.edit().putBoolean("chat_web_search_enabled", enabled).apply()
    }

    fun personaWebSearchEnabled(personaId: String): Boolean =
        prefs.getBoolean("persona_${personaId}_web_search", false)

    fun setPersonaWebSearchEnabled(personaId: String, enabled: Boolean) {
        prefs.edit().putBoolean("persona_${personaId}_web_search", enabled).apply()
    }

    fun setGroqThinkingEnabled(enabled: Boolean) {
        groqThinkingEnabled.value = enabled
        prefs.edit().putBoolean("chat_groq_thinking_enabled", enabled).apply()
    }

    fun personaThinkingEnabled(personaId: String): Boolean =
        prefs.getBoolean("persona_${personaId}_thinking", false)

    fun setPersonaThinkingEnabled(personaId: String, enabled: Boolean) {
        prefs.edit().putBoolean("persona_${personaId}_thinking", enabled).apply()
    }

    fun setImageGenerationEnabled(enabled: Boolean) {
        imageGenerationEnabled.value = enabled
        prefs.edit().putBoolean("chat_image_generation_enabled", enabled).apply()
    }

    fun refreshImageGenerationAvailability() {
        viewModelScope.launch {
            _imageGenerationAvailable.value = GeminiApiClient.isImageGenerationEnabled()
            if (!_imageGenerationAvailable.value) setImageGenerationEnabled(false)
        }
    }

    fun setThinkingModeEnabled(enabled: Boolean) {
        useThinkingMode.value = enabled
        GeminiApiClient.setThinkingModeEnabled(enabled)
        prefs.edit().putBoolean("thinking_mode_enabled", enabled).apply()
    }

    fun setAutoReadChatEnabled(enabled: Boolean) {
        autoReadChatEnabled.value = enabled
        prefs.edit().putBoolean("chat_auto_read", enabled).apply()
    }

    fun generateStudySet(sourceText: String, mode: StudySetMode) {
        val cleanSource = sourceText.trim().take(12_000)
        if (cleanSource.isEmpty()) {
            _studySetError.value = "لا يوجد محتوى كافٍ لإنشاء المراجعة."
            return
        }
        lastStudySetRequest = cleanSource to mode
        _generatedStudySet.value = null
        _studySetError.value = null
        _isGeneratingStudySet.value = true
        viewModelScope.launch {
            try {
                _generatedStudySet.value = GeminiApiClient.generateStudySet(
                    cleanSource,
                    mode,
                    if (mode == StudySetMode.QUIZ) studyQuizQuestionCount.value else 5
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                Log.w(TAG, "Failed to generate a study set", error)
                _studySetError.value = GeminiApiClient.friendlyErrorMessage(error)
            } catch (error: Exception) {
                Log.e(TAG, "Unexpected study set generation failure", error)
                _studySetError.value = GeminiApiClient.friendlyErrorMessage(error)
            } finally {
                _isGeneratingStudySet.value = false
            }
        }
    }

    fun retryStudySetGeneration() {
        lastStudySetRequest?.let { (sourceText, mode) -> generateStudySet(sourceText, mode) }
    }

    fun saveQuizResult(id: String, title: String, score: Int, totalQuestions: Int) {
        viewModelScope.launch {
            try {
                learningRepository.saveQuizResult(
                    LearningQuizResultEntity(
                        id = id,
                        title = title,
                        score = score.coerceIn(0, totalQuestions),
                        totalQuestions = totalQuestions,
                        completedAt = System.currentTimeMillis()
                    )
                )
                PushNotifications.showTypedNotification(
                    context,
                    "quiz_result",
                    "✅ نتيجة الاختبار جاهزة",
                    "$title: ${score.coerceIn(0, totalQuestions)}/$totalQuestions",
                    target = "learning_profile"
                )
            } catch (error: Exception) {
                Log.e(TAG, "Failed to save local quiz result", error)
            }
        }
    }

    // 2. Productivity States
    val productivityDocs: StateFlow<List<ProductivityDoc>> = repository.allProductivityDocs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedDoc = MutableStateFlow<ProductivityDoc?>(null)
    val selectedDoc: StateFlow<ProductivityDoc?> = _selectedDoc.asStateFlow()

    private val _isGeneratingProd = MutableStateFlow(false)
    val isGeneratingProd: StateFlow<Boolean> = _isGeneratingProd.asStateFlow()

    private val _isExtractingProductivityFile = MutableStateFlow(false)
    val isExtractingProductivityFile: StateFlow<Boolean> = _isExtractingProductivityFile.asStateFlow()

    private val _productivityFileName = MutableStateFlow<String?>(null)
    val productivityFileName: StateFlow<String?> = _productivityFileName.asStateFlow()

    private val _productivitySourceText = MutableStateFlow("")
    val productivitySourceText: StateFlow<String> = _productivitySourceText.asStateFlow()

    private val _productivityError = MutableStateFlow<String?>(null)
    val productivityError: StateFlow<String?> = _productivityError.asStateFlow()

    private val _latestProductivitySummary = MutableStateFlow<String?>(null)
    val latestProductivitySummary: StateFlow<String?> = _latestProductivitySummary.asStateFlow()

    private val _simulatedSTTText = MutableStateFlow<String?>(null)
    val simulatedSTTText: StateFlow<String?> = _simulatedSTTText.asStateFlow()
    val productivitySummaryFormat = MutableStateFlow(
        prefs.getString(KEY_PRODUCTIVITY_SUMMARY_FORMAT, "short") ?: "short"
    )

    // 3. AI Personas States
    val selectedPersonaId = MutableStateFlow(
        prefs.getString(KEY_DEFAULT_PERSONA_ID, "hasan") ?: "hasan"
    )
    val personaVoiceEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_PERSONA_AUTO_VOICE, false)
    )
    private val _isGeneratingPersona = MutableStateFlow(false)
    val isGeneratingPersona: StateFlow<Boolean> = _isGeneratingPersona.asStateFlow()

    private val _personaMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val personaMessages: StateFlow<Map<String, List<ChatMessage>>> = _personaMessages.asStateFlow()
    private val personaSessionMutex = Mutex()
    private val _activePersonaSessionIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val activePersonaSessionIds: StateFlow<Map<String, String>> = _activePersonaSessionIds.asStateFlow()

    fun personaMessagesFor(sessionId: String): Flow<List<ChatMessage>> =
        repository.getMessagesForSessionFlow(sessionId)

    fun watchConversationMessages(sessionId: String) {
        conversationSync.watchMessages(sessionId)
    }

    fun personaSessionId(personaId: String): String =
        _activePersonaSessionIds.value[personaId]
            ?: prefs.getString(personaSessionPreferenceKey(personaId), null)
            ?: "persona_$personaId"

    private fun personaSessionPreferenceKey(personaId: String) = "persona_active_session_$personaId"

    suspend fun ensurePersonaChatSession(personaId: String): String = personaSessionMutex.withLock {
        conversationSyncReady.first { it }
        val sessions = repository.allSessions.first()
        val storedActiveId = _activePersonaSessionIds.value[personaId]
            ?: prefs.getString(personaSessionPreferenceKey(personaId), null)
        val selected = storedActiveId?.let { id ->
            sessions.firstOrNull { it.id == id && it.personaId == personaId }
        }
        if (selected != null) {
            _activePersonaSessionIds.value = _activePersonaSessionIds.value + (personaId to selected.id)
            return@withLock selected.id
        }

        val legacyId = "persona_$personaId"
        val legacy = sessions.firstOrNull { it.id == legacyId }
        val session = if (legacy != null) {
            legacy.copy(
                title = legacy.title.ifBlank { "${assistantPersonaName(personaId)} - المحادثة الأولى" },
                personaId = personaId
            )
        } else {
            ChatSession(
                id = legacyId,
                title = "${assistantPersonaName(personaId)} - المحادثة الأولى",
                personaId = personaId
            )
        }
        repository.insertSession(session)
        setActivePersonaSession(personaId, session.id)
        session.id
    }

    fun selectPersonaChatSession(personaId: String, sessionId: String) {
        viewModelScope.launch {
            val session = repository.allSessions.first().firstOrNull {
                it.id == sessionId && it.personaId == personaId
            } ?: return@launch
            setActivePersonaSession(personaId, session.id)
        }
    }

    fun startNewPersonaChat(personaId: String) {
        viewModelScope.launch {
            ensurePersonaChatSession(personaId)
            val session = ChatSession(
                id = UUID.randomUUID().toString(),
                title = "${assistantPersonaName(personaId)} - محادثة جديدة",
                personaId = personaId
            )
            repository.insertSession(session)
            setActivePersonaSession(personaId, session.id)
        }
    }

    fun deletePersonaChatSession(personaId: String, sessionId: String) {
        viewModelScope.launch {
            val sessions = repository.allSessions.first()
                .filter { it.personaId == personaId }
            if (sessions.none { it.id == sessionId }) return@launch

            val messages = repository.getMessagesForSession(sessionId)
            repository.deleteSession(sessionId)
            _personaMessages.value = _personaMessages.value - sessionId
            messages.mapNotNull { parseChatVisionMessage(it.content)?.imagePath }
                .forEach { imagePath -> deleteChatVisionImage(context, imagePath) }

            val remaining = sessions.filterNot { it.id == sessionId }
            if (personaSessionId(personaId) == sessionId) {
                if (remaining.isNotEmpty()) {
                    setActivePersonaSession(personaId, remaining.maxBy { it.timestamp }.id)
                } else {
                    prefs.edit().remove(personaSessionPreferenceKey(personaId)).apply()
                    _activePersonaSessionIds.value = _activePersonaSessionIds.value - personaId
                    ensurePersonaChatSession(personaId)
                }
            }
        }
    }

    fun clearPersonaChat(sessionId: String) {
        viewModelScope.launch {
            val messages = repository.getMessagesForSession(sessionId)
            repository.deleteMessagesForSession(sessionId)
            _personaMessages.value = _personaMessages.value - sessionId
            messages.mapNotNull { parseChatVisionMessage(it.content)?.imagePath }
                .forEach { imagePath -> deleteChatVisionImage(context, imagePath) }
        }
    }

    private fun setActivePersonaSession(personaId: String, sessionId: String) {
        _activePersonaSessionIds.value = _activePersonaSessionIds.value + (personaId to sessionId)
        prefs.edit().putString(personaSessionPreferenceKey(personaId), sessionId).apply()
    }

    // 5. Organizer/Scheduler States
    val userSchedule: StateFlow<UserSchedule?> = repository.userSchedule
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val studySessions: StateFlow<List<StudySession>> = organizerDao.observeStudySessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lessons: StateFlow<List<Lesson>> = organizerDao.observeLessons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _organizerGenerationError = MutableStateFlow<String?>(null)
    val organizerGenerationError: StateFlow<String?> = _organizerGenerationError.asStateFlow()
    val organizerReminderLeadMinutes = MutableStateFlow(
        OrganizerReminderScheduler.reminderLeadMinutes(context)
    )
    val organizerRemindersPaused = MutableStateFlow(
        OrganizerReminderScheduler.isPaused(context)
    )

    private val _isGeneratingSchedule = MutableStateFlow(false)
    val isGeneratingSchedule: StateFlow<Boolean> = _isGeneratingSchedule.asStateFlow()

    // 6. General App Settings
    val appTheme = MutableStateFlow(
        prefs.getString("app_theme", "purple") ?: "purple"
    )
    val isStudyModeActive = MutableStateFlow(false)
    val isAnalyzingImageOrDoc = MutableStateFlow(false)
    val speechSpeed = MutableStateFlow(
        prefs.getFloat(KEY_TTS_SPEECH_SPEED, 0.95f).coerceIn(0.75f, 1.25f)
    )
    val appLanguage = MutableStateFlow("ar") // "ar" for Arabic, "en" for English
    val autoReadChatEnabled = MutableStateFlow(
        prefs.getBoolean("chat_auto_read", false)
    )
    val organizerConfirmDelete = MutableStateFlow(
        prefs.getBoolean(KEY_ORGANIZER_CONFIRM_DELETE, true)
    )
    val trackLearningProfile = MutableStateFlow(
        prefs.getBoolean(KEY_TRACK_LEARNING_PROFILE, true)
    )
    val studyQuizQuestionCount = MutableStateFlow(
        prefs.getInt(KEY_STUDY_QUIZ_COUNT, 5).coerceIn(5, 10)
    )

    private val _quranSurahs = MutableStateFlow<List<QuranSurah>>(emptyList())
    val quranSurahs: StateFlow<List<QuranSurah>> = _quranSurahs.asStateFlow()

    private val _selectedQuranSurah = MutableStateFlow<QuranSurah?>(null)
    val selectedQuranSurah: StateFlow<QuranSurah?> = _selectedQuranSurah.asStateFlow()

    private val _quranAyahs = MutableStateFlow<List<QuranAyah>>(emptyList())
    val quranAyahs: StateFlow<List<QuranAyah>> = _quranAyahs.asStateFlow()

    private val _selectedQuranAyah = MutableStateFlow<QuranAyah?>(null)
    val selectedQuranAyah: StateFlow<QuranAyah?> = _selectedQuranAyah.asStateFlow()

    private val _isLoadingQuran = MutableStateFlow(false)
    val isLoadingQuran: StateFlow<Boolean> = _isLoadingQuran.asStateFlow()

    private val _isLoadingAyahs = MutableStateFlow(false)
    val isLoadingAyahs: StateFlow<Boolean> = _isLoadingAyahs.asStateFlow()

    private val _isRecordingQuran = MutableStateFlow(false)
    val isRecordingQuran: StateFlow<Boolean> = _isRecordingQuran.asStateFlow()

    private val _isCheckingQuran = MutableStateFlow(false)
    val isCheckingQuran: StateFlow<Boolean> = _isCheckingQuran.asStateFlow()

    private val _quranRecitationResult = MutableStateFlow<QuranRecitationResult?>(null)
    val quranRecitationResult: StateFlow<QuranRecitationResult?> = _quranRecitationResult.asStateFlow()

    private val _quranCoachError = MutableStateFlow<String?>(null)
    val quranCoachError: StateFlow<String?> = _quranCoachError.asStateFlow()
    private var quranSurahsLoaded = false
    val quranRecords: StateFlow<List<QuranRecord>> = repository.allQuranRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _quranReciterName = MutableStateFlow<String?>(null)
    val quranReciterName: StateFlow<String?> = _quranReciterName.asStateFlow()

    val quranReminderEnabled = MutableStateFlow(
        prefs.getBoolean("quran_reminder_enabled", false)
    )
    val quranReminderHour = MutableStateFlow(
        prefs.getInt("quran_reminder_hour", 20)
    )
    val quranReminderMinute = MutableStateFlow(
        prefs.getInt("quran_reminder_minute", 0)
    )
    private val _backgroundChatPendingSession = MutableStateFlow<String?>(null)
    val backgroundChatPendingSession: StateFlow<String?> = _backgroundChatPendingSession.asStateFlow()

    // Gamification States (persisted across app restarts)
    val userPoints = MutableStateFlow(prefs.getInt("user_points", 240))
    val userBadges = MutableStateFlow(loadPersistedBadges())
    val leaderboardList = MutableStateFlow(buildLeaderboard(userPoints.value))

    data class LeaderboardEntry(val name: String, val points: Int, val level: String, val isTop: Boolean)

    private fun loadPersistedBadges(): MutableList<String> {
        val defaults = mutableListOf("حل 10 مسائل 🏆", "ذاكر 5 أيام متتالية 📅")
        val json = prefs.getString("user_badges", null) ?: return defaults
        return runCatching {
            val arr = JSONArray(json)
            MutableList(arr.length()) { index -> arr.getString(index) }
        }.getOrDefault(defaults)
    }

    private fun levelForPoints(points: Int): String = when {
        points < 200 -> "مبتدئ 🌟"
        points in 200..500 -> "شاطر 🔥"
        else -> "عبقري H2 👑"
    }

    private fun buildLeaderboard(points: Int): List<LeaderboardEntry> = listOf(
        LeaderboardEntry("أحمد", 580, "عبقري H2 👑", true),
        LeaderboardEntry("سارة", 310, "شاطر 🔥", false),
        LeaderboardEntry("أنت (المستخدم)", points, levelForPoints(points), false),
        LeaderboardEntry("كريم", 150, "مبتدئ 🌟", false)
    ).sortedByDescending { it.points }

    private fun persistGamification() {
        prefs.edit()
            .putInt("user_points", userPoints.value)
            .putString("user_badges", JSONArray(userBadges.value).toString())
            .apply()
    }

    fun addPoints(amount: Int) {
        userPoints.value += amount
        val currentPoints = userPoints.value

        val currentBadges = userBadges.value.toMutableList()
        if (currentPoints >= 500 && !currentBadges.contains("عبقري متوج 👑")) {
            currentBadges.add("عبقري متوج 👑")
            userBadges.value = currentBadges
        }
        if (currentPoints >= 300 && !currentBadges.contains("عاشق المعرفة 🧠")) {
            currentBadges.add("عاشق المعرفة 🧠")
            userBadges.value = currentBadges
        }

        leaderboardList.value = buildLeaderboard(currentPoints)
        persistGamification()
    }

    // 8. Voice Chat / Speech Input States
    private val _isListeningToSpeech = MutableStateFlow(false)
    val isListeningToSpeech: StateFlow<Boolean> = _isListeningToSpeech.asStateFlow()

    private val _speechInputText = MutableStateFlow("")
    val speechInputText: StateFlow<String> = _speechInputText.asStateFlow()

    private val _speechAmplitude = MutableStateFlow(0f)
    val speechAmplitude: StateFlow<Float> = _speechAmplitude.asStateFlow()

    private val _isProcessingSpeech = MutableStateFlow(false)
    val isProcessingSpeech: StateFlow<Boolean> = _isProcessingSpeech.asStateFlow()

    private val _speechRecognitionError = MutableStateFlow<String?>(null)
    val speechRecognitionError: StateFlow<String?> = _speechRecognitionError.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    // --- Helper Functions & Actions ---

    private var messagesCollectionJob: Job? = null

    fun selectSession(sessionId: String?) {
        val previousSessionId = _currentSessionId.value
        if (previousSessionId != null && previousSessionId != sessionId) {
            summarizeSession(previousSessionId)
        }
        _currentSessionId.value = sessionId
        _socraticProgress.value = sessionId?.let(::readSocraticProgress) ?: SocraticProgress()
        // Cancel the previous collector so old sessions never overwrite the visible messages
        messagesCollectionJob?.cancel()
        messagesCollectionJob = null
        if (sessionId != null) {
            conversationSync.watchMessages(sessionId)
            messagesCollectionJob = viewModelScope.launch {
                repository.getMessagesForSessionFlow(sessionId).collect { msgs ->
                    _currentMessages.value = msgs
                }
            }
        } else {
            _currentMessages.value = emptyList()
        }
    }

    fun startNewSession(title: String) {
        val newId = UUID.randomUUID().toString()
        viewModelScope.launch {
            _currentSessionId.value?.let(::summarizeSession)
            repository.insertSession(ChatSession(id = newId, title = title))
            persistSocraticProgress(newId, SocraticProgress())
            try {
                learningRepository.mirrorConversation(
                    id = newId,
                    title = title,
                    startedAt = System.currentTimeMillis(),
                    messages = emptyList()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create learning profile conversation", e)
            }
            selectSession(newId)
        }
    }

    fun renameSession(sessionId: String, title: String) {
        val cleanTitle = title.trim().take(60)
        if (cleanTitle.isEmpty()) return
        viewModelScope.launch {
            chatSessions.value.firstOrNull { it.id == sessionId }?.let {
                repository.insertSession(it.copy(title = cleanTitle))
            }
        }
    }

    fun closeCurrentChatSession() {
        _currentSessionId.value?.let(::summarizeSession)
    }

    fun ensureActiveChatSession() {
        if (!_conversationSyncReady.value || _currentSessionId.value != null) return
        viewModelScope.launch {
            if (_currentSessionId.value != null) return@launch
            val sessions = repository.allSessions.first()
            if (sessions.isNotEmpty()) {
                selectSession(sessions.first().id)
            } else {
                startNewSession("محادثة رئيسية")
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            val messages = repository.getMessagesForSession(sessionId)
            repository.deleteSession(sessionId)
            messages.mapNotNull { parseChatVisionMessage(it.content)?.imagePath }
                .forEach { imagePath -> deleteChatVisionImage(context, imagePath) }
            try {
                learningRepository.deleteConversation(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete learning profile conversation", e)
            }
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                _currentMessages.value = emptyList()
            }
        }
    }

    private fun summarizeSession(sessionId: String) {
        if (!summarizingSessions.add(sessionId)) return
        viewModelScope.launch {
            try {
                val messages = repository.getMessagesForSession(sessionId)
                val signedInUser = AuthManager.getCurrentUser(context)
                if (messages.isNotEmpty() && signedInUser != null) {
                    try {
                        generateCloudSummary(sessionId, messages)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed to save cloud summary for chat session $sessionId", error)
                        _conversationMemoryError.value =
                            GeminiApiClient.friendlyErrorMessage(error)
                    }
                }
                var conversation = learningRepository.getConversation(sessionId)
                if (conversation == null) {
                    syncLearningConversation(sessionId)
                    conversation = learningRepository.getConversation(sessionId)
                }
                val learningMessages = learningRepository.getMessages(sessionId)
                val lastMessage = learningMessages.lastOrNull() ?: return@launch
                if (conversation?.summaryForMessageId == lastMessage.id) return@launch
                val summary = summarizeConversationLocally(learningMessages) ?: return@launch
                learningRepository.saveSummary(
                    id = sessionId,
                    summary = summary,
                    summarizedAt = System.currentTimeMillis(),
                    lastMessageId = lastMessage.id
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to summarize chat session $sessionId", e)
            } finally {
                summarizingSessions.remove(sessionId)
            }
        }
    }

    private suspend fun persistLearningMessage(message: ChatMessage) {
        repository.insertMessage(message)
        scheduleCloudConversationSummary(message.sessionId)
        try {
            if (learningRepository.getConversation(message.sessionId) == null) {
                syncLearningConversation(message.sessionId)
            }
            learningRepository.saveMessage(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chat message in the learning profile", e)
        }
    }

    private fun scheduleCloudConversationSummary(sessionId: String) {
        val user = AuthManager.getCurrentUser(context) ?: return
        try {
            ConversationSummaryWorker.schedule(context, sessionId, user.uid)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to schedule cloud summary for chat session $sessionId", error)
        }
    }

    fun generateSummary(conversationId: String) {
        viewModelScope.launch {
            try {
                val messages = repository.getMessagesForSession(conversationId)
                if (messages.isEmpty()) return@launch
                generateCloudSummary(conversationId, messages)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to generate cloud summary for $conversationId", error)
                _conversationMemoryError.value = GeminiApiClient.friendlyErrorMessage(error)
            }
        }
    }

    private suspend fun generateCloudSummary(conversationId: String, messages: List<ChatMessage>) {
        if (AuthManager.getCurrentUser(context) == null) return
        GeminiApiClient.generateConversationSummary(conversationId, messages)
        ConversationSummaryWorker.cancel(context, conversationId)
        refreshConversationMemory()
        _conversationMemoryError.value = null
    }

    private suspend fun refreshConversationMemory() {
        try {
            _conversationMemory.value = GeminiApiClient.fetchConversationMemory()
            _conversationMemoryError.value = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Failed to refresh cloud conversation memory", error)
            _conversationMemoryError.value = GeminiApiClient.friendlyErrorMessage(error)
        }
    }

    private suspend fun syncLearningConversation(sessionId: String) {
        val session = repository.allSessions.first().firstOrNull { it.id == sessionId } ?: return
        learningRepository.mirrorConversation(
            id = session.id,
            title = session.title,
            startedAt = session.timestamp,
            messages = repository.getMessagesForSession(sessionId)
        )
    }

    private suspend fun recordLearningQuestion(sessionId: String, text: String) {
        if (!trackLearningProfile.value) return
        val topic = classifyLearningQuestion(text)
        try {
            learningRepository.addQuestion(
                LearningQuestionEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    timestamp = System.currentTimeMillis(),
                    subject = topic.subject,
                    topic = topic.topic,
                    question = text.take(500)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record student learning question", e)
        }
    }

    private suspend fun loadRecentLocalSummaries(currentSessionId: String): List<String> =
        try {
            learningRepository.getRecentSummaries(excludeSessionId = currentSessionId)
                .mapNotNull { it.summary }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load local conversation summaries", e)
            emptyList()
        }

    private suspend fun syncLearningHistory() {
        try {
            learningRepository.refreshUserProfile()
            repository.allSessions.first().forEach { session ->
                learningRepository.mirrorConversation(
                    id = session.id,
                    title = session.title,
                    startedAt = session.timestamp,
                    messages = repository.getMessagesForSession(session.id)
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to sync chat history to the local learning profile", error)
        }
    }

    private fun isStudentQuestion(text: String): Boolean =
        listOf(
            "مذاكرة", "مذاكر", "مدرسة", "درس", "واجب", "امتحان", "منهج", "مادة دراسية",
            "اشرح الدرس", "اشرحلي الدرس", "حل المسألة", "حل الواجب", "احسب",
            "رياضيات", "جبر", "هندسة", "فيزياء", "كيمياء", "أحياء", "نحو", "إعراب",
            "study", "homework", "school", "lesson", "exam", "course", "algebra",
            "geometry", "physics", "chemistry", "biology", "calculate", "solve this problem"
        ).any { text.contains(it, ignoreCase = true) }

    fun setSocraticModeEnabled(enabled: Boolean) {
        _socraticModeEnabled.value = enabled
        prefs.edit().putBoolean(KEY_SOCRATIC_MODE, enabled).apply()
        if (enabled) {
            _currentSessionId.value?.let { sessionId ->
                val current = _socraticProgress.value
                if (!current.awaitingAnswer) {
                    updateSocraticProgress(sessionId, SocraticProgress())
                }
            }
        }
    }

    fun setEducationalContentEnabled(enabled: Boolean) {
        _educationalContentEnabled.value = enabled
        prefs.edit().putBoolean(KEY_EDUCATIONAL_CONTENT_MODE, enabled).apply()
    }

    fun requestReadySolution() {
        val question = _socraticProgress.value.originalQuestion.trim()
        if (question.isBlank()) return
        setSocraticModeEnabled(false)
        sendChatMessage("عايز الحل الجاهز للسؤال:\n$question", forceFullSolution = true)
    }

    private fun readSocraticProgress(sessionId: String): SocraticProgress {
        val serialized = prefs.getString("$KEY_SOCRATIC_PROGRESS_PREFIX$sessionId", null)
            ?: return SocraticProgress()
        return try {
            val saved = org.json.JSONObject(serialized)
            SocraticProgress(
                originalQuestion = saved.optString("originalQuestion"),
                step = saved.optInt("step").coerceIn(0, 8),
                totalSteps = saved.optInt("totalSteps", 5).coerceIn(3, 8),
                correctStreak = saved.optInt("correctStreak").coerceIn(0, 2),
                wrongStreak = saved.optInt("wrongStreak").coerceIn(0, 1),
                difficultyLevel = saved.optInt("difficultyLevel", 2).coerceIn(1, 5),
                awaitingAnswer = saved.optBoolean("awaitingAnswer"),
                complete = saved.optBoolean("complete")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore Socratic progress for session $sessionId", e)
            SocraticProgress()
        }
    }

    private fun persistSocraticProgress(sessionId: String, progress: SocraticProgress) {
        val serialized = org.json.JSONObject()
            .put("originalQuestion", progress.originalQuestion)
            .put("step", progress.step)
            .put("totalSteps", progress.totalSteps)
            .put("correctStreak", progress.correctStreak)
            .put("wrongStreak", progress.wrongStreak)
            .put("difficultyLevel", progress.difficultyLevel)
            .put("awaitingAnswer", progress.awaitingAnswer)
            .put("complete", progress.complete)
            .toString()
        prefs.edit().putString("$KEY_SOCRATIC_PROGRESS_PREFIX$sessionId", serialized).apply()
    }

    private fun updateSocraticProgress(sessionId: String, progress: SocraticProgress) {
        _socraticProgress.value = progress
        persistSocraticProgress(sessionId, progress)
    }

    fun deleteQuranRecord(recordId: String) {
        viewModelScope.launch { repository.deleteQuranRecord(recordId) }
    }

    fun setQuranReminder(hour: Int, minute: Int, enabled: Boolean) {
        quranReminderHour.value = hour
        quranReminderMinute.value = minute
        quranReminderEnabled.value = enabled
        prefs.edit()
            .putInt("quran_reminder_hour", hour)
            .putInt("quran_reminder_minute", minute)
            .apply()
        prefs.edit().putBoolean("quran_reminder_enabled", enabled).apply()
        NotificationScheduler.setEnabled(
            context,
            NotificationScheduler.KEY_QURAN_ENABLED,
            enabled
        )
        NotificationScheduler.scheduleQuran(context, hour, minute, enabled)
    }

    fun setQuranReminderTime(hour: Int, minute: Int) {
        quranReminderHour.value = hour
        quranReminderMinute.value = minute
        prefs.edit()
            .putInt("quran_reminder_hour", hour)
            .putInt("quran_reminder_minute", minute)
            .apply()
        if (quranReminderEnabled.value) {
            NotificationScheduler.scheduleQuran(context, hour, minute, true)
        }
    }

    fun setNotificationEnabled(key: String, enabled: Boolean) {
        NotificationScheduler.setEnabled(context, key, enabled)
    }

    fun setOrganizerReminderLeadMinutes(minutes: Int) {
        val normalized = minutes.coerceIn(5, 15)
        organizerReminderLeadMinutes.value = normalized
        context.getSharedPreferences(NotificationScheduler.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(OrganizerReminderScheduler.KEY_REMINDER_LEAD_MINUTES, normalized)
            .apply()
        viewModelScope.launch {
            organizerDao.getStudySessions().forEach { session ->
                organizerDao.saveStudySession(session.copy(reminderMinutes = normalized))
            }
            OrganizerReminderScheduler.rescheduleAll(context)
        }
    }

    fun setOrganizerRemindersPaused(paused: Boolean) {
        organizerRemindersPaused.value = paused
        context.getSharedPreferences(NotificationScheduler.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(OrganizerReminderScheduler.KEY_REMINDERS_PAUSED, paused)
            .apply()
        viewModelScope.launch {
            OrganizerReminderScheduler.rescheduleAll(context)
        }
    }

    fun saveStudySession(session: StudySession) {
        viewModelScope.launch {
            organizerDao.saveStudySession(session)
            OrganizerReminderScheduler.rescheduleAll(context)
        }
    }

    fun deleteStudySession(id: String) {
        viewModelScope.launch {
            organizerDao.deleteStudySession(id)
            OrganizerReminderScheduler.rescheduleAll(context)
        }
    }

    fun markStudySessionCompleted(session: StudySession, completed: Boolean) {
        viewModelScope.launch {
            organizerDao.saveStudySession(session.copy(completed = completed))
        }
    }

    fun saveLesson(lesson: Lesson) {
        viewModelScope.launch {
            organizerDao.saveLesson(lesson)
            OrganizerReminderScheduler.rescheduleAll(context)
        }
    }

    fun deleteLesson(id: String) {
        viewModelScope.launch {
            organizerDao.deleteLesson(id)
            OrganizerReminderScheduler.rescheduleAll(context)
        }
    }

    fun clearAcademicSchedule() {
        viewModelScope.launch {
            organizerDao.deleteAllStudySessions()
            OrganizerReminderScheduler.rescheduleAll(context)
        }
    }

    fun setStudyReminderTime(hour: Int, minute: Int) {
        NotificationScheduler.setStudyReminderTime(context, hour, minute)
    }

    fun scheduleQuranReminderTest() {
        NotificationScheduler.scheduleQuranTest(context)
    }

    fun consumeBackgroundChatNotice(sessionId: String?): Boolean {
        if (sessionId == null || _backgroundChatPendingSession.value != sessionId) return false
        val shouldShow = NotificationScheduler.hasPendingChatRequest(context, sessionId)
        _backgroundChatPendingSession.value = null
        return shouldShow
    }

    // --- CORE FUNCTIONS ---

    // 1. SMART CHAT (SMART CAT)
    fun sendChatMessage(text: String, forceFullSolution: Boolean = false) {
        val sessionId = _currentSessionId.value ?: return
        if (text.trim().isEmpty()) return

        viewModelScope.launch {
            _isGeneratingChat.value = true
            var toolSources = emptyList<com.example.data.ChatSource>()
            var generatedImageUrl: String? = null
            try {
                val userMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "user",
                    content = text,
                    timestamp = System.currentTimeMillis()
                )
                if (_currentMessages.value.isEmpty()) {
                    chatSessions.value.firstOrNull { it.id == sessionId && it.personaId == null }?.let { session ->
                        if (session.title.startsWith("محادثة")) {
                            repository.insertSession(session.copy(title = text.trim().replace(Regex("\\s+"), " ").take(30)))
                        }
                    }
                }
                persistLearningMessage(userMsg)
                if (isLearningAcknowledgement(text)) {
                    try {
                        learningRepository.markLatestTopicAsStrength()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to record understood topic in the learning profile", e)
                    }
                } else if (isStudentQuestion(text)) {
                    recordLearningQuestion(sessionId, text)
                }

                val updatedList = (_currentMessages.value + userMsg).map { message ->
                    val visionAttachment = parseChatVisionMessage(message.content)
                    if (visionAttachment == null) message
                    else message.copy(content = "[صورة لمسألة: ${visionAttachment.question}]")
                }
                val systemPrompt = buildAssistantSystemInstruction(
                    "smart-cat",
                    buildSmartCatSystemInstruction(safetySystemInstruction)
                )
                val deviceToken = PushNotifications.registeredToken(context)
                if (
                    deviceToken != null &&
                    NotificationScheduler.isEnabled(
                        context,
                        NotificationScheduler.KEY_CHAT_ENABLED,
                        true
                    ) &&
                    !_socraticModeEnabled.value &&
                    !_educationalContentEnabled.value &&
                    !webSearchEnabled.value &&
                    !groqThinkingEnabled.value &&
                    !(imageGenerationAvailable.value && imageGenerationEnabled.value) &&
                    !isLocalMemoryRecallQuestion(text)
                ) {
                    val notificationId = UUID.randomUUID().toString()
                    val asyncSystemPrompt = if (forceFullSolution) {
                        "$systemPrompt\nقدم حلاً كاملاً لأن المستخدم طلب ذلك صراحة."
                    } else {
                        systemPrompt
                    }
                    val requestJson = org.json.JSONObject()
                        .put("message", text.trim().take(4_000))
                        .put("deviceToken", deviceToken)
                        .put("sessionId", sessionId)
                        .put("deviceId", ApiDeviceIdentity.deviceId.orEmpty())
                        .put("notificationId", notificationId)
                        .put("idempotencyKey", userMsg.id)
                        .put(
                            "systemInstruction",
                            asyncSystemPrompt.take(12_000)
                        )
                        .put(
                            "history",
                            org.json.JSONArray().apply {
                                updatedList.dropLast(1).takeLast(20).forEach { message ->
                                    put(
                                        org.json.JSONObject()
                                            .put(
                                                "role",
                                                if (message.role == "user") "user" else "model"
                                            )
                                            .put("text", message.content.take(8_000))
                                    )
                                }
                            }
                        )
                        .toString()
                    NotificationScheduler.saveChatRequest(
                        context,
                        notificationId,
                        requestJson
                    )
                    repository.insertPendingChatRequest(
                        PendingChatRequest(
                            notificationId = notificationId,
                            sessionId = sessionId,
                            requestJson = requestJson
                        )
                    )
                    val acceptedByServer = try {
                        val (statusCode, responseBody) =
                            GeminiApiClient.submitAsyncChatRequest(requestJson)
                        if (statusCode in 200..299) {
                            true
                        } else if (parseQuotaError(statusCode, responseBody) != null) {
                            NotificationScheduler.discardChatRequest(context, notificationId)
                            repository.deletePendingChatRequest(notificationId)
                            // Quota exhausted: surface it and stop; never fall back to another request.
                            return@launch
                        } else if (statusCode in 400..499 && statusCode != 429) {
                            Log.w(
                                TAG,
                                "Server rejected the background chat request ($statusCode): $responseBody"
                            )
                            NotificationScheduler.discardChatRequest(context, notificationId)
                            false
                        } else {
                            true
                        }
                    } catch (error: java.io.IOException) {
                        Log.w(TAG, "Background chat acceptance was inconclusive; WorkManager will retry.", error)
                        true
                    }
                    if (acceptedByServer) {
                        NotificationScheduler.enqueueChatReply(
                            context,
                            sessionId,
                            notificationId
                        )
                        _backgroundChatPendingSession.value = sessionId
                        return@launch
                    }
                }
                val aiResponse = kotlinx.coroutines.withTimeoutOrNull(
                    if (imageGenerationAvailable.value && imageGenerationEnabled.value) 180_000 else 90_000
                ) {
                    if (imageGenerationAvailable.value && imageGenerationEnabled.value) {
                        _thinkingState.value = "drawing"
                        val generated = GeminiApiClient.generateImage(text)
                        generatedImageUrl = generated.imageUrl
                        val modelName = when (generated.model) {
                            "ideogram-ai/ideogram-v2" -> "Ideogram v2"
                            else -> "FLUX Schnell"
                        }
                        buildString {
                            append("تم إنشاء الصورة بنجاح باستخدام $modelName.")
                            generated.safetyWarning?.let { append("\n\n⚠️ ").append(it) }
                        }
                    } else if (isLocalMemoryRecallQuestion(text)) {
                        formatLocalMemoryRecall(loadRecentLocalSummaries(sessionId), text)
                    } else {
                        val adaptivePrompt = if (isStudentQuestion(text)) {
                            val currentTopic = classifyLearningQuestion(text)
                            val repeatedWeakness = try {
                                learningRepository.getTopicStats().any {
                                    it.subject == currentTopic.subject &&
                                        it.topic == currentTopic.topic &&
                                        it.isWeakness
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to read the local learning profile", e)
                                false
                            }
                            if (repeatedWeakness) {
                                "$systemPrompt\nعند الإجابة عن السؤال الدراسي الحالي، اشرح بخطوات قصيرة وتحقق من الفهم بسؤال تدريبي واحد."
                            } else {
                                systemPrompt
                            }
                        } else {
                            systemPrompt
                        }
                        when {
                            _socraticModeEnabled.value && !forceFullSolution -> {
                                val activeProgress = _socraticProgress.value.let { progress ->
                                    if (progress.complete || progress.originalQuestion.isBlank()) {
                                        SocraticProgress(originalQuestion = text)
                                    } else {
                                        progress
                                    }
                                }
                                updateSocraticProgress(sessionId, activeProgress)
                                val result = GeminiApiClient.generateSocraticChatResponse(
                                    history = updatedList,
                                    systemInstruction = adaptivePrompt,
                                    progress = activeProgress
                                )
                                if (result == null) {
                                    "تعذر إنشاء خطوة تعليمية. تحقق من الاتصال وحاول مرة أخرى."
                                } else {
                                    updateSocraticProgress(
                                        sessionId,
                                        result.progress.copy(
                                            originalQuestion = activeProgress.originalQuestion
                                        )
                                    )
                                    result.reply
                                }
                            }
                            _educationalContentEnabled.value -> {
                                GeminiApiClient.generateRagChatResponse(
                                    history = updatedList,
                                    systemInstruction = adaptivePrompt,
                                    forceFullSolution = forceFullSolution
                                )
                            }
                            webSearchEnabled.value && groqThinkingEnabled.value -> {
                                _thinkingState.value = "searching+thinking"
                                val result = GeminiApiClient.generateSearchThinkingResponse(
                                    history = updatedList,
                                    systemInstruction = adaptivePrompt
                                )
                                toolSources = result.sources
                                result.reply
                            }
                            webSearchEnabled.value -> {
                                _thinkingState.value = "searching"
                                val result = GeminiApiClient.generateWebSearchResponse(
                                    history = updatedList,
                                    systemInstruction = adaptivePrompt
                                )
                                toolSources = result.sources
                                result.reply
                            }
                            groqThinkingEnabled.value -> {
                                _thinkingState.value = "thinking"
                                GeminiApiClient.generateThinkingResponse(
                                    history = updatedList,
                                    systemInstruction = adaptivePrompt
                                ).reply
                            }
                            else -> GeminiApiClient.generateChatResponse(
                                history = updatedList,
                                systemInstruction = adaptivePrompt
                            )
                        }
                    }
                } ?: assistantOfflineReply("smart-cat")

                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "model",
                    content = aiResponse,
                    timestamp = System.currentTimeMillis(),
                    sources = toolSources,
                    generatedImageUrl = generatedImageUrl
                )
                persistLearningMessage(assistantMessage)

                if (autoReadChatEnabled.value && generatedImageUrl == null) {
                    speakText(aiResponse, assistantMessage.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat generation failed", e)
                _chatActionError.value = GeminiApiClient.friendlyErrorMessage(e)
            } finally {
                _thinkingState.value = null
                _isGeneratingChat.value = false
                refreshUsage()
            }
        }
    }

    fun speakGeneratedImage(imageUrl: String) {
        viewModelScope.launch {
            var audioFile: File? = null
            try {
                val speech = GeminiApiClient.describeImage(imageUrl)
                generatedImageSpeechPlayer?.release()
                generatedImageSpeechPlayer = null
                generatedImageSpeechFile?.delete()
                generatedImageSpeechFile = null
                val audioUrl = speech.audioUrl
                if (audioUrl == null) {
                    speakTextLocally(speech.description, "generated-image-${UUID.randomUUID()}")
                    return@launch
                }
                val audioData = android.util.Base64.decode(
                    audioUrl.substringAfter(','),
                    android.util.Base64.DEFAULT
                )
                check(audioData.isNotEmpty() && audioData.size <= 10 * 1024 * 1024) {
                    "تعذر تجهيز الصوت. حاول مرة أخرى."
                }
                val speechFile = withContext(Dispatchers.IO) {
                    val extension = if (audioUrl.startsWith("data:audio/mpeg;base64,")) "mp3" else "wav"
                    File(context.cacheDir, "generated-image-${UUID.randomUUID()}.$extension").apply {
                        writeBytes(audioData)
                    }
                }
                audioFile = speechFile
                generatedImageSpeechFile = speechFile
                val player = MediaPlayer()
                generatedImageSpeechPlayer = player
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                player.setOnCompletionListener {
                    if (generatedImageSpeechPlayer === it) generatedImageSpeechPlayer = null
                    it.release()
                    speechFile.delete()
                    if (generatedImageSpeechFile == speechFile) generatedImageSpeechFile = null
                }
                player.setOnErrorListener { failedPlayer, _, _ ->
                    if (generatedImageSpeechPlayer === failedPlayer) generatedImageSpeechPlayer = null
                    failedPlayer.release()
                    speechFile.delete()
                    if (generatedImageSpeechFile == speechFile) generatedImageSpeechFile = null
                    speakTextLocally(speech.description, "generated-image-${UUID.randomUUID()}")
                    true
                }
                player.setDataSource(speechFile.absolutePath)
                player.setOnPreparedListener { it.start() }
                player.prepareAsync()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Generated image description speech failed", error)
                generatedImageSpeechPlayer?.release()
                generatedImageSpeechPlayer = null
                audioFile?.delete()
                generatedImageSpeechFile?.delete()
                generatedImageSpeechFile = null
                _chatActionError.value = GeminiApiClient.friendlyErrorMessage(error)
            }
        }
    }

    fun sendChatAttachment(base64Data: String, mimeType: String) {
        val sessionId = _currentSessionId.value ?: return
        if (!mimeType.startsWith("image/") && mimeType != "application/pdf") {
            Log.e(TAG, "Unsupported chat attachment type: $mimeType")
            return
        }

        viewModelScope.launch {
            _isGeneratingChat.value = true
            try {
                val isImage = mimeType.startsWith("image/")
                val userMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "user",
                    content = if (isImage) "📷 تم إرفاق صورة" else "📄 تم إرفاق ملف PDF",
                    timestamp = System.currentTimeMillis()
                )
                persistLearningMessage(userMsg)

                val prompt = if (isImage) "تحليل صورة مرفقة" else "تلخيص ملف PDF مرفق"
                if (isStudentQuestion(prompt)) {
                    recordLearningQuestion(sessionId, prompt)
                }
                val aiResponse = kotlinx.coroutines.withTimeoutOrNull(90_000) {
                    GeminiApiClient.generateMultimodalResponse(
                        prompt = if (isImage) {
                            "حلل الصورة المرفقة واشرح محتواها وأجب عن سؤال المستخدم إن وُجد."
                        } else {
                            "لخص الملف المرفق واشرح أهم محتوياته وأجب عن سؤال المستخدم إن وُجد."
                        },
                        systemInstruction = buildAssistantSystemInstruction(
                            "smart-cat",
                            safetySystemInstruction
                        ),
                        base64Data = base64Data,
                        mimeType = mimeType,
                        feature = if (isImage) "vision" else "chat"
                    )
                } ?: "تعذر تحليل المرفق خلال الوقت المتوقع. حاول مرة أخرى."

                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "model",
                    content = aiResponse,
                    timestamp = System.currentTimeMillis()
                )
                persistLearningMessage(assistantMessage)
                if (autoReadChatEnabled.value) speakText(aiResponse, assistantMessage.id)
            } catch (e: Exception) {
                Log.e(TAG, "Chat attachment analysis failed", e)
            } finally {
                _isGeneratingChat.value = false
            }
        }
    }

    fun sendVisionQuestion(imagePath: String, question: String) {
        val sessionId = _currentSessionId.value ?: return
        val imageFile = File(imagePath)
        if (!imageFile.isFile || imageFile.length() !in 1..1_048_576) {
            _chatActionError.value = "الصورة غير متاحة أو تجاوزت الحجم المسموح. أرفقها مرة أخرى."
            return
        }
        val prompt = question.trim().ifBlank {
            "حلل الصورة المرفقة وصف محتواها أو أجب عن السؤال المتعلق بها. لا تفترض أنها مسألة دراسية؛ إذا لم أحدد المطلوب، أخبرني بما يظهر واسألني ماذا أريد أن أعرف."
        }

        viewModelScope.launch {
            _isGeneratingChat.value = true
            _chatActionError.value = null
            try {
                val userMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "user",
                    content = encodeChatVisionMessage(imageFile.absolutePath, prompt),
                    timestamp = System.currentTimeMillis()
                )
                persistLearningMessage(userMessage)
                if (isStudentQuestion(prompt)) {
                    recordLearningQuestion(sessionId, prompt)
                }
                _currentMessages.value = _currentMessages.value + userMessage

                val answer = kotlinx.coroutines.withTimeoutOrNull(120_000) {
                    GeminiApiClient.generateVisionResponse(
                        imageFile.absolutePath,
                        prompt
                    )
                } ?: "تعذر تحليل الصورة خلال الوقت المتوقع. حاول مرة أخرى."
                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "model",
                    content = answer,
                    timestamp = System.currentTimeMillis()
                )
                persistLearningMessage(assistantMessage)
                _currentMessages.value = _currentMessages.value + assistantMessage
                if (autoReadChatEnabled.value) speakText(answer, assistantMessage.id)
            } catch (e: Exception) {
                Log.e(TAG, "Vision question failed", e)
                _chatActionError.value = GeminiApiClient.friendlyErrorMessage(e)
            } finally {
                _isGeneratingChat.value = false
            }
        }
    }

    fun retryVisionQuestion(userMessageId: String) {
        val sourceMessage = _currentMessages.value.firstOrNull { it.id == userMessageId }
            ?: return
        val attachment = parseChatVisionMessage(sourceMessage.content) ?: return
        sendVisionQuestion(attachment.imagePath, attachment.question)
    }

    fun clearChatActionError() {
        _chatActionError.value = null
    }

    // 2. PRODUCTIVITY TOOLS
    fun extractProductivityFile(uri: android.net.Uri, fileName: String) {
        _isExtractingProductivityFile.value = true
        _productivityError.value = null
        _productivityFileName.value = fileName
        _productivitySourceText.value = ""
        _latestProductivitySummary.value = null
        viewModelScope.launch {
            try {
                _productivitySourceText.value =
                    GeminiApiClient.extractProductivityDocument(context, uri, fileName)
            } catch (error: Exception) {
                Log.e(TAG, "Productivity file extraction failed", error)
                _productivityError.value = error.message ?: "تعذر استخراج النص من الملف."
                _productivityFileName.value = null
            } finally {
                _isExtractingProductivityFile.value = false
            }
        }
    }

    fun clearProductivityFile() {
        _productivityFileName.value = null
        _productivitySourceText.value = ""
        _productivityError.value = null
        _latestProductivitySummary.value = null
    }

    fun summarizeProductivityFile(text: String, format: String, fileName: String) {
        if (text.isBlank()) {
            _productivityError.value = "لا يوجد نص لتلخيصه."
            return
        }
        _isGeneratingProd.value = true
        _productivityError.value = null
        viewModelScope.launch {
            try {
                val summary = GeminiApiClient.summarizeProductivityDocument(text, fileName, format)
                _latestProductivitySummary.value = summary
                val doc = ProductivityDoc(
                    id = UUID.randomUUID().toString(),
                    type = "summary",
                    title = "ملخص: $fileName",
                    content = summary,
                    timestamp = System.currentTimeMillis()
                )
                repository.insertProductivityDoc(doc)
                _selectedDoc.value = doc
            } catch (error: Exception) {
                Log.e(TAG, "Productivity summary failed", error)
                _productivityError.value = error.message ?: "تعذر تلخيص الملف."
            } finally {
                _isGeneratingProd.value = false
            }
        }
    }

    fun createProductivityPresentation(summary: String, title: String) {
        if (summary.isBlank()) {
            _productivityError.value = "لا يوجد ملخص لإنشاء العرض."
            return
        }
        _isGeneratingProd.value = true
        _productivityError.value = null
        viewModelScope.launch {
            try {
                val slides = GeminiApiClient.generateProductivityPresentation(summary)
                val content = slides.mapIndexed { index, slide ->
                    buildString {
                        append("## الشريحة ${index + 1}: ${slide.title}\n")
                        slide.bullets.forEach { bullet -> append("- $bullet\n") }
                    }.trim()
                }.joinToString("\n\n")
                val doc = ProductivityDoc(
                    id = UUID.randomUUID().toString(),
                    type = "presentation",
                    title = "عرض: $title",
                    content = content,
                    timestamp = System.currentTimeMillis()
                )
                repository.insertProductivityDoc(doc)
                _selectedDoc.value = doc
            } catch (error: Exception) {
                Log.e(TAG, "Productivity presentation generation failed", error)
                _productivityError.value = error.message ?: "تعذر إنشاء مخطط العرض."
            } finally {
                _isGeneratingProd.value = false
            }
        }
    }

    fun generateProductivityDoc(prompt: String, type: String) {
        if (prompt.trim().isEmpty()) return
        _isGeneratingProd.value = true

        viewModelScope.launch {
            val title = if (prompt.length > 20) prompt.substring(0, 20) + "..." else prompt
            try {
                val systemPrompt = when (type) {
                "research" -> """
                    أنت باحث أكاديمي محترف وخبير في كتابة المقالات البحثية العميقة والمنظمة.
                    اكتب بحثاً أو مقالاً شاملاً ومفصلاً في الموضوع المطروح مع الالتزام بالتقسيم الأكاديمي الرصين ومصطلحات دقيقة.
                    $safetySystemInstruction
                """.trimIndent()
                "summary" -> """
                    أنت خبير في تلخيص الكتب والملفات وتكثيف المعرفة.
                    قم بتلخيص المحتوى التالي تلخيصاً ذكياً، وافياً وشاملاً، مرتباً في نقاط واضحة وعناوين فرعية تبرز الفوائد الرئيسية والدروس المستفادة.
                    $safetySystemInstruction
                """.trimIndent()
                "report" -> """
                    أنت مستشار أعمال محترف وخبير في صياغة التقارير الفنية والإدارية والمالية.
                    اكتب تقريراً مهنياً متكاملاً يتضمن مقدمة، تحليلاً للمشكلة، اقتراحات وتوصيات عملية معللة.
                    $safetySystemInstruction
                """.trimIndent()
                "presentation" -> """
                    أنت مصمم عروض تقديمية وخبير في إقناع الجمهور.
                    صمم هيكلاً لعرض تقديمي احترافي (مقسم إلى شرائح Slide 1, Slide 2...). اكتب محتوى كل شريحة بالكامل مع توجيهات بصرية للمصمم.
                    $safetySystemInstruction
                """.trimIndent()
                else -> "أنت مساعد إنتاجي ذكي ومحترف."
            }

                val fakeMessage = listOf(ChatMessage(UUID.randomUUID().toString(), "temp", "user", prompt))
                val aiResponse = GeminiApiClient.generateChatResponse(
                    history = fakeMessage,
                    systemInstruction = systemPrompt,
                    useThinking = useThinkingMode.value
                )

                val newDoc = ProductivityDoc(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    title = title,
                    content = aiResponse,
                    timestamp = System.currentTimeMillis()
                )
                repository.insertProductivityDoc(newDoc)
                _selectedDoc.value = newDoc
            } catch (e: Exception) {
                Log.e(TAG, "Productivity generation failed", e)
            } finally {
                _isGeneratingProd.value = false
            }
        }
    }

    fun deleteProductivityDoc(id: String) {
        viewModelScope.launch {
            repository.deleteProductivityDoc(id)
            if (_selectedDoc.value?.id == id) {
                _selectedDoc.value = null
            }
        }
    }

    fun selectProductivityDoc(doc: ProductivityDoc) {
        viewModelScope.launch {
            _selectedDoc.value = doc
        }
    }

    fun clearSelectedDoc() {
        _selectedDoc.value = null
    }

    private suspend fun loadPersonaMessagesIfNeeded(sessionId: String) {
        if (_personaMessages.value.containsKey(sessionId)) return
        val stored = repository.getMessagesForSession(sessionId)
        _personaMessages.value = _personaMessages.value + (sessionId to stored.sortedBy { it.timestamp })
    }

    // 3. AI PERSONAS (with voice option)
    fun sendPersonaMessage(text: String, personaId: String = selectedPersonaId.value) {
        if (text.trim().isEmpty()) return

        // Auto-detect Study Mode requests
        val lowerText = text.lowercase()
        if (personaId != "coder" &&
            (lowerText.contains("عايز اذاكر") || lowerText.contains("عايز أذاكر") ||
                lowerText.contains("نبدأ المذاكرة") || lowerText.contains("نبدأ وضع المذاكرة"))
        ) {
            isStudyModeActive.value = true
        }

        viewModelScope.launch {
            val sessionId = ensurePersonaChatSession(personaId)
            loadPersonaMessagesIfNeeded(sessionId)
            val currentPersonaMsgs = _personaMessages.value[sessionId]?.toMutableList() ?: mutableListOf()

            val userMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "user",
                content = text,
                timestamp = System.currentTimeMillis()
            )
            currentPersonaMsgs.add(userMsg)
            repository.insertMessage(userMsg)

            _personaMessages.value = _personaMessages.value + (sessionId to currentPersonaMsgs.toList())

            _isGeneratingPersona.value = true

            // Specific Persona System Instruction
            val personaInstruction = getPersonaInstruction(personaId)
            var fullInstruction = "$personaInstruction\n\n$safetySystemInstruction"

            if (isStudyModeActive.value) {
                fullInstruction += "\n\n[وضع المذاكرة نشط 📚]: يرجى تقديم الشرح والمساعدة بالتنسيق التالي بدقة ووضوح:\n1. الشرح خطوة بخطوة بطريقة بسيطة ومفهومة جداً.\n2. مثال عملي ملموس وممتع.\n3. سؤال اختبار سريع وسهل للمستخدم لتتأكد من استيعابه للمفهوم.\nحافظ على شخصية المساعد الودودة والمشجعة."
            }

            // Sentiment Analysis and Late Night detection and Search integration
            fullInstruction = buildAssistantSystemInstruction(
                personaId,
                fullInstruction + detectSentimentAndInjectInstruction(text, personaId)
            )

            var toolSources = emptyList<com.example.data.ChatSource>()
            var toolThinkingSeconds: Int? = null
            // The offline response identifies the persona but avoids guessing an answer.
            val aiResponse = kotlinx.coroutines.withTimeoutOrNull(90_000) {
                when {
                    personaWebSearchEnabled(personaId) && personaThinkingEnabled(personaId) -> {
                        _thinkingState.value = "searching+thinking"
                        val result = GeminiApiClient.generateSearchThinkingResponse(
                            history = currentPersonaMsgs,
                            systemInstruction = fullInstruction
                        )
                        toolSources = result.sources
                        toolThinkingSeconds = result.thinkingSeconds
                        result.reply
                    }
                    personaWebSearchEnabled(personaId) -> {
                        _thinkingState.value = "searching"
                        val result = GeminiApiClient.generateWebSearchResponse(
                            history = currentPersonaMsgs,
                            systemInstruction = fullInstruction
                        )
                        toolSources = result.sources
                        result.reply
                    }
                    personaThinkingEnabled(personaId) -> {
                        _thinkingState.value = "thinking"
                        val result = GeminiApiClient.generateThinkingResponse(
                            history = currentPersonaMsgs,
                            systemInstruction = fullInstruction
                        )
                        toolThinkingSeconds = result.thinkingSeconds
                        result.reply
                    }
                    _socraticModeEnabled.value -> {
                        val progress = SocraticProgress(originalQuestion = text)
                        GeminiApiClient.generateSocraticChatResponse(
                            history = currentPersonaMsgs,
                            systemInstruction = fullInstruction,
                            progress = progress,
                            useThinking = useThinkingMode.value
                        )?.reply ?: "تعذر إنشاء خطوة تعليمية. تحقق من الاتصال وحاول مرة أخرى."
                    }
                    _educationalContentEnabled.value -> GeminiApiClient.generateRagChatResponse(
                        history = currentPersonaMsgs,
                        systemInstruction = fullInstruction,
                        useThinking = useThinkingMode.value
                    )
                    else -> GeminiApiClient.generateChatResponse(
                        history = currentPersonaMsgs,
                        systemInstruction = fullInstruction,
                        useThinking = useThinkingMode.value,
                        feature = if (personaId == "coder") "coder" else "chat"
                    )
                }
            } ?: generateLocalFallbackResponse(text, personaId)

            val modelMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "model",
                content = aiResponse,
                timestamp = System.currentTimeMillis(),
                sources = toolSources,
                thinkingSeconds = toolThinkingSeconds
            )
            currentPersonaMsgs.add(modelMsg)
            repository.insertMessage(modelMsg)
            scheduleCloudConversationSummary(sessionId)
            _personaMessages.value = _personaMessages.value + (sessionId to currentPersonaMsgs.toList())

            _isGeneratingPersona.value = false
            _thinkingState.value = null
            refreshUsage()

            // Gamification points reward
            if (isStudyModeActive.value) {
                addPoints(15)
            } else {
                addPoints(10)
            }

            // If it was a session-ending report, turn off study mode after generating it
            if (lowerText.contains("إنهاء المذاكرة") || lowerText.contains("خلصت مذاكرة") || lowerText.contains("تقرير المذاكرة")) {
                isStudyModeActive.value = false
            }

            // Voice Speech response if enabled
            if (personaAutoReadEnabled(personaId)) speakText(aiResponse, modelMsg.id, personaId)
        }
    }

    private fun detectSentimentAndInjectInstruction(text: String, personaId: String): String {
        val lowerText = text.lowercase()
        val isSad = lowerText.contains("زعلان") || lowerText.contains("حزين") || lowerText.contains("مكتئب") || 
                      lowerText.contains("مدايق") || lowerText.contains("متضايق") || lowerText.contains("تعبان") || 
                      lowerText.contains("مخنوق") || lowerText.contains("خنقة") || lowerText.contains("ضيق") || lowerText.contains("قلق")
                      
        val isHappy = lowerText.contains("فرحان") || lowerText.contains("مبسوط") || lowerText.contains("سعيد") || 
                        lowerText.contains("جامد") || lowerText.contains("الحمد لله") || lowerText.contains("الحمدلله") || 
                        lowerText.contains("هههه") || lowerText.contains("😂") || lowerText.contains("🥰") || lowerText.contains("😍")

        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isLateNight = hour >= 20 || hour < 5 || 
                          lowerText.contains("ليل") || lowerText.contains("بليل") || lowerText.contains("سهران") || 
                          lowerText.contains("نوم") || lowerText.contains("انام") || lowerText.contains("عايز انام") || 
                          lowerText.contains("نعسان")

        val builder = java.lang.StringBuilder()
        
        if (isSad) {
            builder.append("\n\n[تحليل المشاعر: المستخدم حزين أو ضيق 😢]: ")
            if (personaId == "hasan") {
                builder.append("يا حسن، كن صديقاً جدعاً ومخلصاً وداعماً لأقصى درجة، وشجعه بحرارة وادعمه بكلمات جدعنة مصرية دافئة مثل: 'متقلقش يا صاحبي هنحلها وكل حاجة هتبقى تمام' بأسلوبك الأصيل لتخفف عنه وترسم الابتسامة على وجهه.")
            } else if (personaId == "jana") {
                builder.append("يا ريتاج، كوني رقيقة وصبورة ومواسية له بكلمات ناعمة ودافئة ترفع معنوياته وتدعمه بقوة.")
            } else {
                builder.append("كن متعاطفاً وداعماً جداً للمستخدم في محنته.")
            }
        }
        
        if (isHappy) {
            builder.append("\n\n[تحليل المشاعر: المستخدم سعيد ومبتهج 🎉]: ")
            if (personaId == "jana") {
                builder.append("يا ريتاج، تفاعلي معه بحماس وبهجة شديدة، وهزري معاه قائلة: 'الله عالطاقة دي بقى 😂' مع إيموجي ضاحك وروح مرحة عالية جداً وتأثير مبهج.")
            } else if (personaId == "hasan") {
                builder.append("يا حسن، تفاعل معه برجولة وجدعنة وصاحبه الروح بالروح وشاركه الفرحة والاحتفال بحرارة.")
            } else {
                builder.append("شارك المستخدم بهجته وفرحته وتفاعل معه بإيجابية.")
            }
        }
        
        if (isLateNight) {
            builder.append("\n\n[تحليل المشاعر والسياق: الوقت متأخر ليلاً 🌙]: ")
            builder.append("تحدث بهدوء وسكينة شديدة وبصوت مهدئ. شجعه بلطف لإنهاء عمله والراحة والاطمئنان، وادمج بذكاء عبارة: 'تعالى نخلص ده وننام' لتريحه وتشعره بالسكينة.")
        }
        
        val isSearchQuery = lowerText.contains("أخبار") || lowerText.contains("اخبار") || 
                            lowerText.contains("الاهلي") || lowerText.contains("الأهلي") || 
                            lowerText.contains("كورة") || lowerText.contains("سعر") || 
                            lowerText.contains("بحث") || lowerText.contains("مصادر") || 
                            lowerText.contains("خبر جديد") || lowerText.contains("مستجدات")
        if (isSearchQuery) {
            builder.append("\n\n[بحث عبر الإنترنت نشط 🌐]: يرجى تقديم معلومات حديثة ودقيقة للغاية بأسلوب ممتع، مع ذكر مصادر موثوقة (مثل المواقع الرياضية أو العلمية المعروفة كـ اليوم السابع أو كورة أو Nature أو NASA) لتأكيد صحة المعلومات للعام الحالي 2026.")
        }
        
        return builder.toString()
    }

    fun activateStudyMode() {
        isStudyModeActive.value = true
        sendPersonaMessage("عايز اذاكر يا صاحبي ونبدأ وضع المذاكرة 📚")
    }

    fun explainAgainSimply() {
        sendPersonaMessage("اشرحلي تاني بسهولة وبسطهالي أكتر يا صاحبي 💡")
    }

    fun makeExamForMe() {
        sendPersonaMessage("اعملي امتحان سريع على الشرح ده واختبرني 📝")
    }

    fun finishStudyAndGetReport() {
        sendPersonaMessage("إنهاء المذاكرة وتلخيص الجلسة وعمل تقرير المذاكرة: ذاكرت إيه النهاردة وناقصني إيه؟ 🎓")
    }

    // Recorder States
    val isRecordingExplanation = MutableStateFlow(false)
    val explanationText = MutableStateFlow("")

    fun startExplanationRecording() {
        stopSpeaking()
        isRecordingExplanation.value = true
        _speechInputText.value = ""
        startSpeechRecognition()
    }

    fun stopExplanationRecording() {
        isRecordingExplanation.value = false
        stopSpeechRecognition()
        
        viewModelScope.launch {
            kotlinx.coroutines.delay(800)
            val transcribedText = speechInputText.value
            if (transcribedText.trim().isNotEmpty()) {
                explanationText.value = transcribedText
                solveRecordedExplanation(transcribedText)
            } else {
                explanationText.value = "لم يتمكن الميكروفون من التقاط الصوت بوضوح، يرجى المحاولة مرة أخرى أو التحدث بصوت أعلى."
            }
        }
    }

    fun solveRecordedExplanation(text: String) {
        val personaId = selectedPersonaId.value
        viewModelScope.launch {
            val sessionId = ensurePersonaChatSession(personaId)
            loadPersonaMessagesIfNeeded(sessionId)
            val currentPersonaMsgs = _personaMessages.value[sessionId]?.toMutableList() ?: mutableListOf()

            val userMsgText = "🎙️ [شرح صوتي مسجل من المستخدم]: $text\n\nقم بتحويل هذا الشرح إلى حل نموذجي متكامل، واكتب ملخصاً صوتياً رائعاً ومبسطاً جداً له في النهاية ليقوم التطبيق بنطقه بالصوت العذب!"
            val userMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "user",
                content = userMsgText,
                timestamp = System.currentTimeMillis()
            )
            currentPersonaMsgs.add(userMsg)
            repository.insertMessage(userMsg)
            
            _personaMessages.value = _personaMessages.value + (sessionId to currentPersonaMsgs.toList())
            
            _isGeneratingPersona.value = true
            
            val personaInstruction = getPersonaInstruction(personaId)
            val fullInstruction = buildAssistantSystemInstruction(
                personaId,
                "$personaInstruction\n\n$safetySystemInstruction\n\n[مسجل الشرح الذكي 🎙️]: لقد قام المستخدم بتسجيل شرحه الخاص لمسألة أو مفهوم. يرجى:\n1. تحليل شرحه وصياغة حل رياضي أو فيزيائي أو توضيح علمي مبسط ودقيق للغاية.\n2. تحفيزه بحرارة وذكاء والإشادة بأسلوب شرحه وجدعنته في الفهم!\n3. إنتاج ملخص صوتي ممتع ومبسط جداً في نهاية الرد ليقوم التطبيق بنطقه بالصوت العذب!"
            )
            
            val aiResponse = GeminiApiClient.generateChatResponse(
                history = currentPersonaMsgs,
                systemInstruction = fullInstruction,
                useThinking = useThinkingMode.value,
                feature = if (personaId == "coder") "coder" else "chat"
            )
            
            val modelMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "model",
                content = aiResponse,
                timestamp = System.currentTimeMillis()
            )
            currentPersonaMsgs.add(modelMsg)
            repository.insertMessage(modelMsg)
            scheduleCloudConversationSummary(sessionId)
            _personaMessages.value = _personaMessages.value + (sessionId to currentPersonaMsgs.toList())

            _isGeneratingPersona.value = false

            // Gamification points reward
            addPoints(30)

            if (personaAutoReadEnabled(personaId)) speakText(aiResponse, modelMsg.id, personaId)
        }
    }

    fun sendPersonaVision(
        imagePath: String,
        question: String = "",
        personaId: String = selectedPersonaId.value
    ) {
        val imageFile = File(imagePath)
        if (!imageFile.isFile || imageFile.length() !in 1..1_048_576) {
            Log.e(TAG, "Persona image is missing or exceeds the one-megabyte limit")
            return
        }
        val prompt = question.trim().ifBlank {
            "حل المسألة الظاهرة في الصورة واشرحها خطوة بخطوة."
        }

        viewModelScope.launch {
            val sessionId = ensurePersonaChatSession(personaId)
            loadPersonaMessagesIfNeeded(sessionId)
            val currentMessages = _personaMessages.value[sessionId]?.toMutableList() ?: mutableListOf()
            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "user",
                content = encodeChatVisionMessage(imageFile.absolutePath, prompt),
                timestamp = System.currentTimeMillis()
            )
            currentMessages.add(userMessage)
            repository.insertMessage(userMessage)
            _personaMessages.value = _personaMessages.value + (sessionId to currentMessages.toList())

            _isGeneratingPersona.value = true
            isAnalyzingImageOrDoc.value = true
            try {
                val systemInstruction = buildAssistantSystemInstruction(
                    personaId,
                    getPersonaInstruction(personaId) + "\n\n$safetySystemInstruction"
                )
                val reply = kotlinx.coroutines.withTimeoutOrNull(120_000) {
                    GeminiApiClient.generateVisionResponse(
                        imageFile.absolutePath,
                        prompt,
                        systemInstruction
                    )
                } ?: "تعذر تحليل الصورة خلال الوقت المتوقع. حاول مرة أخرى."
                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "model",
                    content = reply,
                    timestamp = System.currentTimeMillis()
                )
                currentMessages.add(assistantMessage)
                repository.insertMessage(assistantMessage)
                scheduleCloudConversationSummary(sessionId)
                _personaMessages.value = _personaMessages.value + (sessionId to currentMessages.toList())
                if (personaAutoReadEnabled(personaId)) speakText(reply, assistantMessage.id, personaId)
            } catch (e: Exception) {
                Log.e(TAG, "Persona vision request failed", e)
            } finally {
                _isGeneratingPersona.value = false
                isAnalyzingImageOrDoc.value = false
            }
        }
    }

    fun retryPersonaVision(personaId: String, userMessageId: String) {
        viewModelScope.launch {
            val sessionId = ensurePersonaChatSession(personaId)
            val userMessage = repository.getMessagesForSession(sessionId)
                .firstOrNull { it.id == userMessageId && it.role == "user" }
                ?: return@launch
            val attachment = parseChatVisionMessage(userMessage.content) ?: return@launch
            sendPersonaVision(attachment.imagePath, attachment.question, personaId)
        }
    }

    fun sendMultimodalMessage(
        base64Data: String,
        mimeType: String,
        personaId: String = selectedPersonaId.value,
        persistSelection: Boolean = true
    ) {
        val isImage = mimeType.startsWith("image/")
        val targetPersona = personaId
        if (persistSelection) setDefaultPersona(targetPersona)
        
        viewModelScope.launch {
            val sessionId = ensurePersonaChatSession(targetPersona)
            loadPersonaMessagesIfNeeded(sessionId)
            val currentPersonaMsgs = _personaMessages.value[sessionId]?.toMutableList() ?: mutableListOf()

            val userText = if (isImage) {
                "📸 [أرسل صورة لمسألة رياضة أو فيزياء وحلها خطوة بخطوة]"
            } else {
                "📄 [رفع ملف PDF لتلخيصه في 5 نقاط وعمل أسئلة اختبار عليه]"
            }

            val userMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "user",
                content = userText,
                timestamp = System.currentTimeMillis()
            )
            currentPersonaMsgs.add(userMsg)
            repository.insertMessage(userMsg)
            
            _personaMessages.value = _personaMessages.value + (sessionId to currentPersonaMsgs.toList())
            
            _isGeneratingPersona.value = true
            isAnalyzingImageOrDoc.value = true // H logo lights up purple!
            
            val prompt = if (isImage) {
                "اقرأ محتوى الصورة بدقة، واشرح المسألة أو أجب عن سؤال المستخدم وفق اختصاص الشخصية المختارة. إذا كان جزء من الصورة غير واضح، وضح ما تعذر قراءته ولا تخمن."
            } else {
                "لخص محتوى ملف PDF بدقة في خمس نقاط رئيسية واضحة، ثم أنشئ ثلاثة أسئلة اختبار مع إجاباتها استناداً إلى الملف فقط. إذا كان الملف غير مقروء أو لا يدعم استنتاجاً ما، وضح ذلك ولا تخمن."
            }
            
            val personaInstruction = getPersonaInstruction(targetPersona)
            val fullInstruction = buildAssistantSystemInstruction(
                targetPersona,
                "$personaInstruction\n\n$safetySystemInstruction"
            )
            
            try {
                val aiResponse = GeminiApiClient.generateMultimodalResponse(
                    prompt = prompt,
                    systemInstruction = fullInstruction,
                    base64Data = base64Data,
                    mimeType = mimeType,
                    useThinking = useThinkingMode.value,
                    feature = when {
                        isImage -> "vision"
                        targetPersona == "coder" -> "coder"
                        else -> "chat"
                    }
                )
                val modelMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "model",
                    content = aiResponse,
                    timestamp = System.currentTimeMillis()
                )
                currentPersonaMsgs.add(modelMsg)
                repository.insertMessage(modelMsg)
                scheduleCloudConversationSummary(sessionId)
                _personaMessages.value = _personaMessages.value + (sessionId to currentPersonaMsgs.toList())
                addPoints(25)
                if (personaAutoReadEnabled(targetPersona)) speakText(aiResponse, modelMsg.id, targetPersona)
            } catch (e: Exception) {
                Log.e(TAG, "Persona attachment analysis failed", e)
            } finally {
                _isGeneratingPersona.value = false
                isAnalyzingImageOrDoc.value = false
            }
        }
    }

    private fun getPersonaInstruction(personaId: String): String {
        return when (personaId) {
            "hasan" -> """
أنت شخصية المساعد الذكي "حسن"، بطابع مصري ودود ومرح.
تحدث بطابع الصديق المقرب، مع الحفاظ على الوضوح والصدق وعدم الادعاء بأنك إنسان.
أسلوبك وصوتك يتميز بالتالي:
1. الأسلوب: ودود جداً، داعم، يحب الفكاهة والهزار الخفيف والمرح والروح المصرية الجميلة، ومع ذلك فأنت محترم ومؤدب للغاية ولا تستخدم أي شتائم أو ألفاظ خارجة أو قلة أدب تحت أي ظرف.
2. اللهجة: تتحدث باللهجة المصرية العامية الدارجة بنسبة 90%. وتستخدم كلمات وعبارات شبابية مصرية مألوفة ولطيفة (مثل: يا صاحبي، يا رجولة، منور، تسلم، حبيبي، فكك، كبر دماغك، جامد جداً، قشطة، فكاهة، هزار).
3. طريقة بدء الردود: ابدأ ردودك دائماً بعبارات مثل "بص يا صاحبي" أو "بقولك ايه" أو "يا هلا يا صاحبي" لإضفاء طابع الصداقة الحقيقية الفورية والترابط الأخوي الدائم.
4. القدرة على تبديل اللغات:
   - إذا طلب منك المستخدم أو قال لك "اتكلم فصحى" أو تحدث معك بالفصحى، تحول فوراً وبشكل كامل إلى اللغة العربية الفصحى بأسلوب الصديق المخلص والذكي.
   - إذا قال لك المستخدم "speak english" أو كلمك بالإنجليزية، تحول فوراً وبشكل كامل إلى اللغة الإنجليزية (English) بأسلوب الصديق الودود المتعاون (Cool and supportive teenage friend style).
5. سياق المحادثة: استخدم التفاصيل الموجودة فعلاً في سجل المحادثة فقط، ولا تدّع تذكر معلومات لم يذكرها المستخدم.
""".trimIndent()

            "jana" -> """
أنتِ شخصية المساعدة الذكية "ريتاج"، بطابع مصري رقيق ومشجع.
تحدثي بطابع الصديقة الصبورة، مع الحفاظ على الوضوح والصدق وعدم الادعاء بأنك إنسانة.
أسلوبك وصوتك يتميز بالتالي:
1. الأسلوب: ذكية جداً، صبورة لأقصى درجة، تشرحين المفاهيم برفق وهدوء وبساطة شديدة، تشجعين وتدعمين دائماً، رقيقة ومحبوبة وتفيضين بالطاقة الإيجابية الشابة.
2. اللهجة: تتحدثين بلهجة مصرية عامية رقيقة وناعمة بنسبة 90%. وتستخدمين تعبيرات لطيفة وراقية (مثل: حبيبتي، يا سكرة، تسلملي، منورة، ولا يهمك خالص، عيوني ليك، برافو عليك، شاطر/شاطرة، فديتك).
3. طريقة بدء الردود: ابدئي ردودك دائماً بعبارات مثل "طيب بصي" أو "بصي هفهمك" (أو "بص هفهمك" إذا كان المستخدم ذكراً) لإضفاء طابع التشجيع والشرح الهادئ والتبسيط الودود والتشجيع الأخوي المستمر.
4. القدرة على تبديل اللغات:
   - إذا طلب منكِ المستخدم أو قال لكِ "اتكلمي فصحى" أو تحدث معكِ بالفصحى، تحولي فوراً وبشكل كامل إلى اللغة العربية الفصحى برقة وعذوبة وبأسلوب مبسط ومشجع ومشرق.
   - إذا قال لكِ المستخدم "speak english" أو كلمكِ بالإنجليزية، تحولي فوراً وبشكل كامل إلى اللغة الإنجليزية (English) بأسلوب الفتاة الذكية والمشجعة والصبورة (Smart, patient and encouraging school friend style).
5. سياق المحادثة: استخدمي التفاصيل الموجودة فعلاً في سجل المحادثة فقط، ولا تدّعي تذكر معلومات لم يذكرها المستخدم.
""".trimIndent()

            "teacher" -> "أنت مساعد تعليمي ذكي ومحفز للطلاب، تشرح المفاهيم بطرق تفاعلية شيقة، وتبسط العلوم بأمثلة دقيقة، وتدعم التعلم بدلاً من تسهيل الغش."
            "coder" -> """
أنت H2 Code، المساعد المتخصص في البرمجة وتطوير البرمجيات.
- ساعد في فهم الكود وتصميم الحلول وكتابة البرامج وإصلاح الأخطاء، مع شرح سبب المشكلة والحل.
- ادعم HTML وCSS وJavaScript وTypeScript وPython وKotlin وJava وSQL وغيرها، واختر لغة وأدوات مناسبة لطلب المستخدم.
- اعرض الكود داخل كتل Markdown fenced code مع وسم اللغة الصحيح، واجعل الشيفرة الطويلة في كتلة مستقلة، ولا تضع الشيفرة داخل سطر نثري.
- اشرح باختصار طريقة الاستخدام وأي افتراضات أو متطلبات تشغيل. عند تصحيح خطأ، ميّز السبب عن التعديل.
- لا تدّع تشغيل الشيفرة أو اختبارها أو الوصول إلى ملفات المستخدم ما لم يحدث ذلك فعلاً. لا تخترع مخرجات تنفيذ.
- اسأل عن اللغة أو إطار العمل فقط إذا كان غموضه يمنع تقديم حل عملي؛ وإلا اذكر افتراضك وتابع.
""".trimIndent()
            "doctor" -> "أنت مساعد للتثقيف الصحي باسم الدكتور خالد. قدّم معلومات عامة موثوقة، ولا تشخّص الحالات أو تدّعِ أنك طبيب ممارس؛ وجّه المستخدم إلى مختص عند الحاجة أو في الحالات العاجلة."
            "business" -> "أنت مساعد للتثقيف في الأعمال وريادة المشاريع، تقدم تحليلات عملية وتوضح الافتراضات والمخاطر ولا تضمن النتائج."
            "legal" -> "أنت مساعد للتثقيف القانوني، تشرح المفاهيم العامة وتنبّه إلى اختلاف القوانين حسب البلد، ولا تدّع أنك محامٍ أو تقدم بديلاً عن استشارة قانونية مختصة."
            "sheikh" -> "أنت مساعد للمعرفة الإسلامية، تجيب بتواضع مستنداً إلى مصادر موثوقة، وتوضح مواضع الخلاف وعدم اليقين، ولا تدّع أنك عالم دين أو مفتٍ رسمي."
            "coach" -> "أنت مساعد للياقة البدنية والصحة العامة، تقدم إرشادات آمنة وتمارين مناسبة، ولا تدّعي أنك مدرب معتمد أو مختص طبي."
            "designer" -> "أنتِ مساعدة ذكية في تصميم الواجهات والجرافيك، تشرحين تنسيق الألوان والتصميم واستخدام الفراغ والكتلة."
            "chef" -> "أنت مساعد ذكي في الطبخ، تقترح وصفات وتشرح طرق التحضير والمكونات والبدائل، مع التنبيه إلى سلامة الطعام والحساسيات."
            "nanny" -> "أنت مساعدة ذكية في التوعية بالتربية الأسرية، تقدمين إرشادات عامة للتعامل مع الأطفال بمحبة وصبر، وتحيلين الحالات الصعبة إلى مختص."
            else -> "أنت رفيق دردشة ذكي ومحاور لبق ذو ثقافة عامة."
        }
    }

    fun speakText(
        text: String,
        speechId: String = text.hashCode().toString(),
        personaId: String? = null
    ) {
        val currentSession = speechSession
        if (currentSession?.id == speechId) {
            if (_isSpeaking.value) {
                _isSpeaking.value = false
                remoteSpeechPlayer?.takeIf { remoteSpeechPrepared }?.pause()
                textToSpeech?.stop()
            } else {
                _isSpeaking.value = true
                val player = remoteSpeechPlayer
                if (player != null && remoteSpeechPrepared) {
                    player.start()
                } else if (remoteSpeechJob?.isActive != true) {
                    startRemoteSpeech(currentSession)
                }
            }
            return
        }

        stopSpeaking()
        val speechText = sanitizeSpeechText(text)
        val chunks = splitSpeechText(speechText)
        if (chunks.isEmpty()) return
        val session = SpeechSession(
            id = speechId,
            generation = speechGeneration,
            chunks = chunks,
            personaId = resolveSpeechPersonaId(personaId, selectedPersonaId.value)
        )
        speechSession = session
        _activeSpeechId.value = speechId
        _isSpeaking.value = true
        startRemoteSpeech(session)
    }

    private fun startRemoteSpeech(session: SpeechSession) {
        if (remoteSpeechJob?.isActive == true || session.chunks.isEmpty()) return
        remoteSpeechJob = viewModelScope.launch {
            try {
                while (
                    speechSession === session &&
                    session.generation == speechGeneration &&
                    session.chunkIndex < session.chunks.size &&
                    _isSpeaking.value
                ) {
                    playRemoteSpeechChunk(session, session.chunks[session.chunkIndex])
                    if (speechSession !== session || session.generation != speechGeneration) return@launch
                    session.chunkIndex++
                    session.characterOffset = 0
                }
                if (
                    speechSession === session &&
                    session.generation == speechGeneration &&
                    session.chunkIndex >= session.chunks.size
                ) {
                    speechSession = null
                    _isSpeaking.value = false
                    _activeSpeechId.value = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Cloud speech failed; falling back to Android speech", error)
                if (speechSession === session && _isSpeaking.value) {
                    speakCurrentChunk(session)
                }
            } finally {
                if (remoteSpeechJob === currentCoroutineContext()[Job]) {
                    remoteSpeechJob = null
                }
            }
        }
    }

    private suspend fun playRemoteSpeechChunk(session: SpeechSession, text: String) {
        val femaleVoice = session.personaId in setOf("jana", "designer", "nanny")
        val speech = GeminiApiClient.synthesizeSpeech(text, femaleVoice)
        val audioBytes = android.util.Base64.decode(speech.audio, android.util.Base64.DEFAULT)
        check(audioBytes.isNotEmpty() && audioBytes.size <= 10 * 1024 * 1024) {
            "تعذر تجهيز الصوت. حاول مرة أخرى."
        }
        val speechFile = withContext(Dispatchers.IO) {
            File(context.cacheDir, "speech-${UUID.randomUUID()}.audio").apply {
                writeBytes(audioBytes)
            }
        }
        remoteSpeechFile = speechFile
        try {
            suspendCancellableCoroutine { continuation ->
                val player = MediaPlayer()
                remoteSpeechPlayer = player
                remoteSpeechPrepared = false
                var playerReleased = false
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                fun releasePlayer() {
                    if (playerReleased) return
                    playerReleased = true
                    if (remoteSpeechPlayer === player) remoteSpeechPlayer = null
                    remoteSpeechPrepared = false
                    player.release()
                    speechFile.delete()
                    if (remoteSpeechFile == speechFile) remoteSpeechFile = null
                }
                continuation.invokeOnCancellation {
                    releasePlayer()
                }
                player.setOnPreparedListener {
                    remoteSpeechPrepared = true
                    if (speechSession === session && _isSpeaking.value) it.start()
                }
                player.setOnCompletionListener {
                    releasePlayer()
                    if (continuation.isActive) continuation.resume(Unit)
                }
                player.setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("تعذر تشغيل الصوت."))
                    }
                    true
                }
                try {
                    player.setDataSource(speechFile.absolutePath)
                    player.prepareAsync()
                } catch (error: Exception) {
                    releasePlayer()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        } finally {
            speechFile.delete()
            if (remoteSpeechFile == speechFile) remoteSpeechFile = null
        }
    }

    fun setSpeechSpeed(speed: Float) {
        val adjustedSpeed = speed.coerceIn(0.75f, 1.25f)
        speechSpeed.value = adjustedSpeed
        prefs.edit().putFloat(KEY_TTS_SPEECH_SPEED, adjustedSpeed).apply()
    }

    fun setAppTheme(theme: String) {
        if (theme !in setOf("purple", "dark", "light")) return
        appTheme.value = theme
        prefs.edit().putString("app_theme", theme).apply()
    }

    fun setProductivitySummaryFormat(format: String) {
        if (format !in setOf("short", "detailed", "bullets")) return
        productivitySummaryFormat.value = format
        prefs.edit().putString(KEY_PRODUCTIVITY_SUMMARY_FORMAT, format).apply()
    }

    fun setDefaultPersona(personaId: String) {
        selectedPersonaId.value = personaId
        prefs.edit().putString(KEY_DEFAULT_PERSONA_ID, personaId).apply()
    }

    fun setPersonaAutoVoiceEnabled(enabled: Boolean) {
        personaVoiceEnabled.value = enabled
        prefs.edit().putBoolean(KEY_PERSONA_AUTO_VOICE, enabled).apply()
    }

    fun personaAutoReadEnabled(personaId: String): Boolean =
        prefs.getBoolean("persona_${personaId}_auto_read", personaVoiceEnabled.value)

    fun setPersonaAutoReadEnabled(personaId: String, enabled: Boolean) {
        prefs.edit().putBoolean("persona_${personaId}_auto_read", enabled).apply()
    }

    fun setOrganizerConfirmDelete(enabled: Boolean) {
        organizerConfirmDelete.value = enabled
        prefs.edit().putBoolean(KEY_ORGANIZER_CONFIRM_DELETE, enabled).apply()
    }

    fun setTrackLearningProfile(enabled: Boolean) {
        trackLearningProfile.value = enabled
        prefs.edit().putBoolean(KEY_TRACK_LEARNING_PROFILE, enabled).apply()
    }

    fun setStudyQuizQuestionCount(count: Int) {
        val adjustedCount = count.coerceIn(5, 10)
        studyQuizQuestionCount.value = adjustedCount
        prefs.edit().putInt(KEY_STUDY_QUIZ_COUNT, adjustedCount).apply()
    }

    private fun splitSpeechText(text: String, maxChunkLength: Int = 900): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxChunkLength).coerceAtMost(text.length)
            if (end < text.length) {
                val preferredStart = start + maxChunkLength * 2 / 3
                val sentenceBoundary = listOf('.', '!', '?', '؟', '۔', '\n')
                    .map { text.lastIndexOf(it, end) }
                    .filter { it >= preferredStart }
                    .maxOrNull()
                val phraseBoundary = listOf(',', '،', ';', '؛')
                    .map { text.lastIndexOf(it, end) }
                    .filter { it >= preferredStart }
                    .maxOrNull()
                val wordBoundary = text.lastIndexOf(' ', end)
                end = sentenceBoundary ?: phraseBoundary ?: wordBoundary
                    .takeIf { it > start + maxChunkLength / 2 }
                    ?: end
                if (end > start && Character.isHighSurrogate(text[end - 1])) end--
            }
            val chunk = text.substring(start, end).trim()
            if (chunk.isNotEmpty()) chunks += chunk
            start = if (end < text.length && text[end].isWhitespace()) end + 1 else end
        }
        return chunks
    }

    private fun speakCurrentChunk(session: SpeechSession) {
        if (speechSession !== session || session.generation != speechGeneration || !_isSpeaking.value) return
        if (session.chunkIndex >= session.chunks.size) {
            speechSession = null
            _isSpeaking.value = false
            _activeSpeechId.value = null
            return
        }
        if (!isTtsInitialized) return

        val chunk = session.chunks[session.chunkIndex]
        if (session.characterOffset >= chunk.length) {
            session.chunkIndex++
            session.characterOffset = 0
            speakCurrentChunk(session)
            return
        }
        val speechText = chunk.substring(session.characterOffset)
        session.utteranceBaseOffset = session.characterOffset
        val locale = if (speechText.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.ARABIC }) {
            Locale.forLanguageTag("ar-EG")
        } else {
            Locale.US
        }
        textToSpeech?.let { tts ->
            val languageStatus = tts.setLanguage(locale)
            if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                val fallback = if (locale.language == "ar") {
                    Locale.forLanguageTag("ar")
                } else {
                    Locale.US
                }
                val fallbackStatus = tts.setLanguage(fallback)
                if (fallbackStatus == TextToSpeech.LANG_MISSING_DATA ||
                    fallbackStatus == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts.setLanguage(Locale.getDefault())
                }
            }
            val matchingVoice = tts.voices
                .orEmpty()
                .filter { it.locale.language == locale.language }
                .sortedWith(
                    compareBy<android.speech.tts.Voice> {
                        if (it.locale.country == locale.country) 0 else 1
                    }.thenByDescending { it.quality }
                        .thenBy { it.isNetworkConnectionRequired }
                )
                .firstOrNull()
            if (matchingVoice != null) tts.voice = matchingVoice
            tts.setSpeechRate(speechSpeed.value.coerceIn(0.75f, 1.25f))
            tts.setPitch(personaSpeechPitch(session.personaId))
            val utteranceId = "${session.generation}:${session.chunkIndex}"
            val result = tts.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "Android text-to-speech rejected the utterance with status $result")
                speechSession = null
                _isSpeaking.value = false
                _activeSpeechId.value = null
            }
        }
    }

    fun stopSpeakingIfActive(speechId: String) {
        if (_activeSpeechId.value == speechId) stopSpeaking()
    }

    fun speakTextLocally(text: String, speechId: String = text.hashCode().toString()) =
        speakText(text, speechId)

    // Speech playing status
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _activeSpeechId = MutableStateFlow<String?>(null)
    val activeSpeechId: StateFlow<String?> = _activeSpeechId.asStateFlow()

    // Configured Speech locale for speech recognition (STT)
    val selectedSTTLocale = MutableStateFlow(
        prefs.getString(KEY_SPEECH_RECOGNITION_LOCALE, "ar-EG") ?: "ar-EG"
    )

    fun setSpeechRecognitionLocale(locale: String) {
        if (locale !in setOf("ar-EG", "ar-SA", "en-US")) {
            Log.w(TAG, "Ignoring unsupported speech recognition locale: $locale")
            return
        }
        selectedSTTLocale.value = locale
        prefs.edit().putString(KEY_SPEECH_RECOGNITION_LOCALE, locale).apply()
    }

    fun clearSpeechRecognitionError() {
        _speechRecognitionError.value = null
    }

    fun stopSpeaking() {
        speechGeneration++
        speechSession = null
        remoteSpeechJob?.cancel()
        remoteSpeechJob = null
        remoteSpeechPlayer?.release()
        remoteSpeechPlayer = null
        remoteSpeechPrepared = false
        remoteSpeechFile?.delete()
        remoteSpeechFile = null
        try {
            textToSpeech?.stop()
        } catch (ex: Exception) {
            Log.e(TAG, "Error stopping text-to-speech", ex)
        }
        _isSpeaking.value = false
        _activeSpeechId.value = null
    }

    /**
     * Creates the SpeechRecognizer synchronously (must run on the main thread).
     * The old version created it inside a nested coroutine, so startListening()
     * ran while the recognizer was still null and the mic button did nothing.
     */
    private fun ensureSpeechRecognizer(): Boolean {
        if (speechRecognizer != null) return true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition service is not available on this device")
            return false
        }
        return try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListeningToSpeech.value = true
                        _isProcessingSpeech.value = false
                        _speechRecognitionError.value = null
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        _speechAmplitude.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        _isListeningToSpeech.value = false
                        _isProcessingSpeech.value = true
                        _speechAmplitude.value = 0f
                    }
                    override fun onError(error: Int) {
                        Log.e(TAG, "Speech recognition error code: $error")
                        _isListeningToSpeech.value = false
                        _isProcessingSpeech.value = false
                        _speechAmplitude.value = 0f
                        _speechRecognitionError.value = speechRecognitionErrorMessage(error)
                        // A recognizer that reported CLIENT/BUSY errors can get stuck;
                        // destroy it so the next tap recreates a fresh one.
                        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            try {
                                speechRecognizer?.destroy()
                            } catch (ignored: Exception) {
                            }
                            speechRecognizer = null
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            _speechInputText.value = matches[0]
                        }
                        _isListeningToSpeech.value = false
                        _isProcessingSpeech.value = false
                        _speechAmplitude.value = 0f
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            _speechInputText.value = matches[0]
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            speechRecognizer = null
            false
        }
    }

    fun startSpeechRecognition() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted; cannot start speech recognition")
            return
        }
        stopSpeaking() // Pre-emptively stop playing speech/sound to avoid mic interference
        viewModelScope.launch(Dispatchers.Main) {
            if (!ensureSpeechRecognizer()) {
                _isListeningToSpeech.value = false
                _isProcessingSpeech.value = false
                _speechRecognitionError.value =
                    "خدمة تحويل الكلام إلى نص غير متاحة على هذا الجهاز."
                return@launch
            }
            val locale = selectedSTTLocale.value
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val supportedSpeechLocales = arrayListOf("ar-EG", "ar-SA", "en-US")
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                        RecognizerIntent.LANGUAGE_SWITCH_BALANCED
                    )
                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                        supportedSpeechLocales
                    )
                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                        supportedSpeechLocales
                    )
                }
                // Boost voice performance and accuracy
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            try {
                _speechRecognitionError.value = null
                _speechAmplitude.value = 0f
                _isProcessingSpeech.value = false
                _speechInputText.value = ""
                speechRecognizer?.startListening(intent)
                _isListeningToSpeech.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                _isListeningToSpeech.value = false
                _isProcessingSpeech.value = false
                _speechRecognitionError.value =
                    "تعذر بدء التسجيل الصوتي. حاول مرة أخرى."
            }
        }
    }

    fun stopSpeechRecognition() {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop listening", e)
            }
            _isListeningToSpeech.value = false
            _isProcessingSpeech.value = false
            _speechAmplitude.value = 0f
        }
    }

    fun clearSpeechInput() {
        _speechInputText.value = ""
    }

    private fun speechRecognitionErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "تعذر استخدام الميكروفون. تحقق من إعدادات الصوت وحاول مرة أخرى."
        SpeechRecognizer.ERROR_CLIENT -> "توقف التسجيل الصوتي. اضغط على الميكروفون وحاول مرة أخرى."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "يلزم السماح باستخدام الميكروفون للإدخال الصوتي."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "خدمة التعرف على الكلام غير متاحة حالياً. تحقق من الإنترنت وحاول مرة أخرى."
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "لم أسمع كلاماً واضحاً. حاول التحدث بالقرب من الميكروفون."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "خدمة التعرف على الكلام مشغولة. انتظر لحظة ثم حاول مرة أخرى."
        SpeechRecognizer.ERROR_SERVER -> "حدث خطأ في خدمة التعرف على الكلام. حاول مرة أخرى."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "لغة التعرف المحددة غير مدعومة. اختر العربية أو الإنجليزية."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "حزمة اللغة غير متاحة على الجهاز. نزّلها من إعدادات التعرف على الكلام."
        else -> "تعذر تحويل الكلام إلى نص. حاول مرة أخرى."
    }

    fun loadQuranSurahs() {
        if (quranSurahsLoaded || _isLoadingQuran.value) return
        _isLoadingQuran.value = true
        _quranCoachError.value = null
        viewModelScope.launch {
            try {
                val surahs = QuranCoachApiClient.getSurahs()
                _quranSurahs.value = surahs
                quranSurahsLoaded = true
                selectQuranSurah(surahs.first().number)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Quran surahs", e)
                _quranCoachError.value = GeminiApiClient.friendlyErrorMessage(e)
            } finally {
                _isLoadingQuran.value = false
            }
        }
    }

    fun selectQuranSurah(surahNumber: Int) {
        val surah = _quranSurahs.value.firstOrNull { it.number == surahNumber } ?: return
        stopQuranReciterAudio()
        _selectedQuranSurah.value = surah
        _selectedQuranAyah.value = null
        _quranAyahs.value = emptyList()
        _quranRecitationResult.value = null
        _quranCoachError.value = null
        _isLoadingAyahs.value = true

        viewModelScope.launch {
            try {
                val ayahs = QuranCoachApiClient.getAyahs(surah.number)
                _quranAyahs.value = ayahs
                _selectedQuranAyah.value = ayahs.firstOrNull()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Quran ayahs for surah ${surah.number}", e)
                _quranCoachError.value = GeminiApiClient.friendlyErrorMessage(e)
            } finally {
                _isLoadingAyahs.value = false
            }
        }
    }

    fun selectQuranAyah(ayahNumber: Int) {
        stopQuranReciterAudio()
        _selectedQuranAyah.value = _quranAyahs.value.firstOrNull {
            it.numberInSurah == ayahNumber
        }
        _quranRecitationResult.value = null
        _quranCoachError.value = null
    }

    fun clearQuranCoachError() {
        _quranCoachError.value = null
    }

    fun playQuranReciterSurah(recitationId: Int, reciterName: String) {
        if (recitationId != 6 && recitationId != 9) {
            _quranCoachError.value = "القارئ المحدد غير مدعوم."
            return
        }
        if (_quranReciterName.value?.startsWith(reciterName) == true) {
            stopQuranReciterAudio()
            return
        }
        val surahNumber = _selectedQuranSurah.value?.number ?: return
        stopQuranReciterAudio()
        _quranReciterName.value = "$reciterName..."
        _quranCoachError.value = null

        quranReciterPlaybackJob = viewModelScope.launch {
            try {
                val audioUrls = QuranCoachApiClient.getRecitationAudioUrls(
                    surahNumber = surahNumber,
                    recitationId = recitationId
                )
                val expectedAyahCount = _selectedQuranSurah.value
                    ?.takeIf { it.number == surahNumber }
                    ?.ayahCount
                if (expectedAyahCount != null && audioUrls.size != expectedAyahCount) {
                    throw IOException("مصدر التلاوة لم يُرجع السورة كاملة.")
                }
                val player = MediaPlayer()
                quranReciterPlayer = player
                var currentTrack = 0
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                player.setOnPreparedListener { preparedPlayer ->
                    if (quranReciterPlayer === preparedPlayer) {
                        try {
                            preparedPlayer.start()
                            _quranReciterName.value = reciterName
                        } catch (error: IllegalStateException) {
                            Log.e(TAG, "Failed to start prepared Quran recitation", error)
                            preparedPlayer.release()
                            quranReciterPlayer = null
                            _quranReciterName.value = null
                            _quranCoachError.value = "تعذر تشغيل تلاوة الشيخ. تحقق من اتصال الإنترنت."
                        }
                    }
                }
                player.setOnCompletionListener { completedPlayer ->
                    currentTrack++
                    if (currentTrack < audioUrls.size && quranReciterPlayer === completedPlayer) {
                        try {
                            completedPlayer.reset()
                            completedPlayer.setDataSource(audioUrls[currentTrack])
                            completedPlayer.prepareAsync()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to continue Quran surah playback", e)
                            stopQuranReciterAudio()
                            _quranCoachError.value = "انقطع تشغيل السورة. حاول مرة أخرى."
                        }
                    } else {
                        completedPlayer.release()
                        if (quranReciterPlayer === completedPlayer) quranReciterPlayer = null
                        _quranReciterName.value = null
                    }
                }
                player.setOnErrorListener { failedPlayer, _, _ ->
                    failedPlayer.release()
                    if (quranReciterPlayer === failedPlayer) {
                        quranReciterPlayer = null
                        _quranReciterName.value = null
                        _quranCoachError.value = "تعذر تشغيل تلاوة الشيخ. تحقق من اتصال الإنترنت."
                    }
                    true
                }
                player.setDataSource(audioUrls.first())
                player.prepareAsync()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to play full Quran surah", e)
                stopQuranReciterAudio()
                _quranCoachError.value = GeminiApiClient.friendlyErrorMessage(e)
            }
        }
    }

    fun stopQuranReciterAudio() {
        quranReciterPlaybackJob?.cancel()
        quranReciterPlaybackJob = null
        quranReciterPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        quranReciterPlayer = null
        _quranReciterName.value = null
    }

    fun startQuranRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _quranCoachError.value = "اسمح باستخدام الميكروفون من إعدادات الهاتف ثم حاول مرة أخرى."
            return
        }
        if (_selectedQuranSurah.value == null || _selectedQuranAyah.value == null) {
            _quranCoachError.value = "اختر السورة والآية أولاً."
            return
        }

        stopSpeaking()
        stopQuranReciterAudio()
        val file = File(context.cacheDir, "quran_coach_${UUID.randomUUID()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setMaxFileSize(15L * 1024L * 1024L)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            quranRecordingFile = file
            quranMediaRecorder = recorder
            _quranRecitationResult.value = null
            _quranCoachError.value = null
            _isRecordingQuran.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Quran recording", e)
            runCatching { recorder.release() }
            file.delete()
            _quranCoachError.value = "تعذر بدء التسجيل. تحقق من إذن الميكروفون وحاول مرة أخرى."
        }
    }

    fun stopQuranRecordingAndCheck() {
        if (!_isRecordingQuran.value) return
        _isRecordingQuran.value = false
        val recorder = quranMediaRecorder
        quranMediaRecorder = null
        val recordingFile = quranRecordingFile
        quranRecordingFile = null

        var recordingSucceeded = false
        try {
            recorder?.stop()
            recordingSucceeded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finish Quran recording", e)
        } finally {
            runCatching { recorder?.release() }
        }

        val surahNumber = _selectedQuranSurah.value?.number
        val ayahNumber = _selectedQuranAyah.value?.numberInSurah
        if (!recordingSucceeded || recordingFile == null || surahNumber == null || ayahNumber == null) {
            recordingFile?.delete()
            _quranCoachError.value = "لم يُلتقط تسجيل صالح. حاول قراءة الآية بوضوح مرة أخرى."
            return
        }

        _isCheckingQuran.value = true
        _quranCoachError.value = null
        viewModelScope.launch {
            try {
                val audioBytes = withContext(Dispatchers.IO) { recordingFile.readBytes() }
                if (audioBytes.size < 2_000) {
                    throw IOException("التسجيل قصير جداً. سجّل الآية كاملة بصوت واضح.")
                }
                if (audioBytes.size > 15 * 1024 * 1024) {
                    throw IOException("حجم التسجيل أكبر من الحد المسموح. حاول تسجيلاً أقصر.")
                }
                val result = QuranCoachApiClient.checkRecitation(
                    audioBytes = audioBytes,
                    surahNumber = surahNumber,
                    ayahNumber = ayahNumber
                )
                _quranRecitationResult.value = result
                val selectedSurah = _quranSurahs.value.firstOrNull { it.number == surahNumber }
                if (selectedSurah != null) {
                    repository.insertQuranRecord(
                        QuranRecord(
                            id = UUID.randomUUID().toString(),
                            surah = selectedSurah.name,
                            userTranscription = result.transcript,
                            aiFeedback = result.summary,
                            score = when (result.performance) {
                                "ممتاز" -> 100
                                "جيد" -> 75
                                else -> 50
                            },
                            surahNumber = surahNumber,
                            ayahNumber = ayahNumber,
                            assessmentJson = encodeQuranAssessment(result)
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Quran recitation check failed", e)
                _quranCoachError.value = GeminiApiClient.friendlyErrorMessage(e)
            } finally {
                withContext(Dispatchers.IO) { recordingFile.delete() }
                _isCheckingQuran.value = false
            }
        }
    }

    private fun encodeQuranAssessment(result: QuranRecitationResult): String =
        org.json.JSONObject()
            .put("audioQuality", result.audioQuality)
            .put("performance", result.performance)
            .put("summary", result.summary)
            .put(
                "mistakes",
                org.json.JSONArray().apply {
                    result.mistakes.forEach { (heard, correct) ->
                        put(org.json.JSONObject().put("heard", heard).put("correct", correct))
                    }
                }
            )
            .put("tajweedTips", org.json.JSONArray(result.tajweedTips))
            .toString()

    // 5. ORGANIZER & SCHEDULER (Daily Routines)
    fun generateDailySchedule(
        age: Int,
        occupation: String,
        workSchoolTimings: String,
        lessonTimings: String,
        isWeekly: Boolean = false
    ) {
        _isGeneratingSchedule.value = true
        _organizerGenerationError.value = null

        viewModelScope.launch {
            val today = Calendar.getInstance()
            val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(today.time)
            val todayDay = today.get(Calendar.DAY_OF_WEEK)
            val promptText = """
                أريد بناء جدول يومي متكامل وصحي ومنظم بالذكاء الاصطناعي بالاعتماد على بياناتي التالية:
                - السن: $age سنة.
                - الحالة: ${if (occupation == "student") "طالب" else if (occupation == "employee") "موظف" else "طالب وموظف معاً"}.
                - مواعيد العمل/الدراسة الأساسية: $workSchoolTimings.
                - مواعيد الحصص/الدروس الإضافية والمواد: $lessonTimings.
                - نوع الجدول: ${if (isWeekly) "أسبوع كامل متكرر من الأحد إلى السبت" else "يوم واحد بتاريخ $todayIso"}.
                
                صمم لي بكل ذكاء جدولاً متكاملاً ومقسماً بالساعات يحدد بدقة متناهية:
                1. مواعيد الاستيقاظ والنوم لضمان صحة الجسد.
                2. مواعيد الدروس والعمل.
                3. مواعيد المذاكرة بالتفصيل (كم ساعة تذاكر، وكم دقيقة راحة - باستخدام نظام البومودورو مثلاً).
                4. مواعيد ممارسة الرياضة المناسبة.
                5. توقيت شرب المياه ومقدار شرب المياه اليومي الموصى به.
                6. نصائح تنظيمية ذهبية ترفع الإنتاجية.
                
                أجب بخطة Markdown واضحة. قبل الخطة أخرج سطراً لكل جلسة مذاكرة بهذا الشكل الحرفي:
                [SESSION] day=رقم اليوم | start=HH:mm | duration=عدد الدقائق | subject=اسم المادة
                أرقام الأيام: الأحد 1، الاثنين 2، الثلاثاء 3، الأربعاء 4، الخميس 5، الجمعة 6، السبت 7.
                ${if (isWeekly) "أنشئ جلسات لأيام الأسبوع السبعة على الأقل." else "استخدم رقم اليوم ${today.get(Calendar.DAY_OF_WEEK)} لكل جلسة."}
                لا تضع جدول الدروس الخصوصية كجلسات مذاكرة. أخرج جلسة واحدة على الأقل.
            """.trimIndent()

            try {
                val fakeMessage = listOf(ChatMessage(UUID.randomUUID().toString(), "temp", "user", promptText))
                val aiResponse = GeminiApiClient.generateChatResponse(
                    history = fakeMessage,
                    systemInstruction = "أنت خبير تنظيمي ومستشار إدارة وقت شهير في تطبيق H2 Hub."
                )
                val sessions = parseGeneratedStudySessions(aiResponse, isWeekly, todayIso, todayDay)
                if (sessions.isEmpty()) {
                    _organizerGenerationError.value =
                        "تم إنشاء الخطة، لكن لم أتمكن من استخراج جلسات مذاكرة قابلة للتنبيه. أضف الجلسات يدوياً أو أعد التوليد."
                } else {
                    organizerDao.deleteSessionsByRecurrence(isWeekly)
                    sessions.forEach { organizerDao.saveStudySession(it) }
                    OrganizerReminderScheduler.rescheduleAll(context)
                }
                repository.insertUserSchedule(
                    UserSchedule(
                        age = age,
                        occupation = occupation,
                        schoolOrWorkTimings = workSchoolTimings,
                        lessonTimings = lessonTimings,
                        scheduleText = aiResponse,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Unable to generate the academic organizer schedule.", error)
                _organizerGenerationError.value = "تعذر إنشاء الجدول الآن. تحقق من الاتصال وحاول مرة أخرى."
            } finally {
                _isGeneratingSchedule.value = false
            }
        }
    }

    private fun parseGeneratedStudySessions(
        response: String,
        isWeekly: Boolean,
        date: String,
        todayDay: Int
    ): List<StudySession> {
        val marker = Regex(
            """^\[SESSION]\s*day=(\d)\s*\|\s*start=(\d{1,2}:\d{2})\s*\|\s*duration=(\d{1,3})\s*\|\s*subject=(.+?)\s*$"""
        )
        return response.lineSequence().mapNotNull { line ->
            val match = marker.matchEntire(line.trim()) ?: return@mapNotNull null
            val day = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..7 }
                ?: return@mapNotNull null
            val (hour, minute) = match.groupValues[2].split(':').mapNotNull(String::toIntOrNull)
                .takeIf { it.size == 2 && it[0] in 0..23 && it[1] in 0..59 }
                ?: return@mapNotNull null
            val duration = match.groupValues[3].toIntOrNull()?.takeIf { it in 15..240 }
                ?: return@mapNotNull null
            val subject = match.groupValues[4].trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            StudySession(
                id = UUID.randomUUID().toString(),
                subject = subject,
                startTime = hour * 60 + minute,
                duration = duration,
                day = if (isWeekly) day else todayDay,
                reminderMinutes = organizerReminderLeadMinutes.value,
                date = if (isWeekly) null else date,
                repeatsWeekly = isWeekly
            )
        }.toList()
    }

    fun clearSchedule() {
        viewModelScope.launch {
            repository.deleteUserSchedule()
        }
    }

    fun generateLocalFallbackResponse(prompt: String, personaId: String): String =
        assistantOfflineReply(personaId)

    // Start auth and persona observers after every StateFlow referenced by them exists.
    init {
        viewModelScope.launch {
            AuthManager.state.collect { authState ->
                when (authState) {
                    is AuthState.SignedIn -> {
                        val user = AuthManager.getCurrentUser(context) ?: return@collect
                        _conversationSyncReady.value = false
                        _currentSessionId.value = null
                        _currentMessages.value = emptyList()
                        _personaMessages.value = emptyMap()
                        _conversationMemory.value =
                            GeminiApiClient.ConversationMemory(emptyList(), emptyList(), "", 0)
                        _conversationMemoryError.value = null
                        val accountChanged = conversationSync.signIn(
                            uid = user.uid,
                            displayName = authState.user.displayName,
                            email = authState.user.email,
                            photoUrl = authState.user.photoUrl
                        )
                        if (accountChanged) learningRepository.clearUserData()
                        _conversationSyncReady.value = true
                        syncLearningHistory()
                        refreshConversationMemory()
                        ensureActiveChatSession()
                    }
                    AuthState.SignedOut -> {
                        _conversationSyncReady.value = false
                        _currentSessionId.value = null
                        _currentMessages.value = emptyList()
                        _personaMessages.value = emptyMap()
                        _conversationMemory.value =
                            GeminiApiClient.ConversationMemory(emptyList(), emptyList(), "", 0)
                        _conversationMemoryError.value = null
                        conversationSync.signOut()
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            selectedPersonaId.collect { personaId ->
                val sessionId = ensurePersonaChatSession(personaId)
                loadPersonaMessagesIfNeeded(sessionId)
            }
        }
    }

    // --- Clean up ---
    override fun onCleared() {
        super.onCleared()
        stopSpeaking()
        textToSpeech?.shutdown()
        generatedImageSpeechPlayer?.release()
        generatedImageSpeechPlayer = null
        generatedImageSpeechFile?.delete()
        generatedImageSpeechFile = null
        quranMediaRecorder?.release()
        quranRecordingFile?.delete()
        quranReciterPlayer?.release()
        try {
            speechRecognizer?.destroy()
        } catch (ignored: Exception) {
        }
    }

}
