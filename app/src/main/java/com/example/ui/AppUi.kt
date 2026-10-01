package com.example.ui

import android.graphics.BitmapFactory
import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.*
import com.mikepenz.markdown.m3.Markdown
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.example.network.parseChatVisionMessage

// Main app destinations
enum class AppScreen(val titleAr: String, val icon: ImageVector) {
    CHAT("Smart Cat", Icons.Default.ChatBubble),
    QURAN("تجويد القرآن", Icons.Default.Mic),
    PRODUCTIVITY("الإنتاجية", Icons.Default.Analytics),
    PERSONAS("شخصيات AI", Icons.Default.People),
    ORGANIZER("المنظم اليومي", Icons.Default.CalendarMonth),
    LEARNING_PROFILE("ملفي التعليمي", Icons.Default.School),
    MORE("المزيد", Icons.Default.Dashboard)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppUi(viewModel: AppViewModel) {
    var currentScreen by remember { mutableStateOf(AppScreen.CHAT) }
    var previousScreen by remember { mutableStateOf(AppScreen.CHAT) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val isKeyboardVisible = WindowInsets.isImeVisible

    // Navigation back handling
    if (currentScreen != AppScreen.CHAT) {
        BackHandler {
            currentScreen = if (currentScreen == AppScreen.MORE) {
                AppScreen.CHAT
            } else {
                previousScreen
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        topBar = if (currentScreen == AppScreen.CHAT) {
            {}
        } else {
            {
                TopAppBar(
                    title = {},
                    actions = {
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        bottomBar = {
            if (!isKeyboardVisible) {
                NavigationBar {
                    listOf(AppScreen.CHAT, AppScreen.QURAN, AppScreen.MORE).forEach { screen ->
                        NavigationBarItem(
                            selected = currentScreen == screen,
                            onClick = {
                                if (screen != AppScreen.MORE) {
                                    previousScreen = AppScreen.CHAT
                                }
                                currentScreen = screen
                            },
                            icon = { Icon(screen.icon, contentDescription = screen.titleAr) },
                            label = { Text(screen.titleAr) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    slideInHorizontally { width -> if (targetState.ordinal > initialState.ordinal) width else -width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> if (targetState.ordinal > initialState.ordinal) -width else width } + fadeOut()
                },
                label = "ScreenTransition"
            ) { screen ->
                when (screen) {
                    AppScreen.CHAT -> ChatScreen(
                        viewModel = viewModel,
                        onOpenSettings = { showSettingsDialog = true }
                    )
                    AppScreen.QURAN -> QuranCoachScreen(viewModel)
                    AppScreen.PRODUCTIVITY -> ProductivityScreen(viewModel)
                    AppScreen.PERSONAS -> PersonasScreen(viewModel)
                    AppScreen.ORGANIZER -> OrganizerScreen(viewModel)
                    AppScreen.LEARNING_PROFILE -> LearningProfileScreen(viewModel)
                    AppScreen.MORE -> MoreScreen(
                        onNavigate = {
                            previousScreen = AppScreen.MORE
                            currentScreen = it
                        }
                    )
                }
            }

            if (showSettingsDialog) {
                SettingsDialog(
                    viewModel = viewModel,
                    onDismiss = { showSettingsDialog = false }
                )
            }
        }
    }
}

@Composable
private fun MoreScreen(onNavigate: (AppScreen) -> Unit) {
    val destinations = listOf(
        AppScreen.CHAT,
        AppScreen.QURAN,
        AppScreen.PRODUCTIVITY,
        AppScreen.PERSONAS,
        AppScreen.ORGANIZER,
        AppScreen.LEARNING_PROFILE
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "خصائص التطبيق",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "اختر الخاصية التي تريد استخدامها",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        destinations.chunked(2).forEach { rowDestinations ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowDestinations.forEach { destination ->
                    Card(
                        onClick = { onNavigate(destination) },
                        modifier = Modifier
                            .weight(1f)
                            .height(148.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = destination.titleAr,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                if (rowDestinations.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LearningProfileScreen(viewModel: AppViewModel) {
    val questionCount by viewModel.learningQuestionCount.collectAsState()
    val subjects by viewModel.learningSubjectCounts.collectAsState()
    val topics by viewModel.learningTopicStats.collectAsState()
    val timestamps by viewModel.learningQuestionTimestamps.collectAsState()
    val conversations by viewModel.learningConversations.collectAsState()
    val chartLineColor = MaterialTheme.colorScheme.primary
    val chartAxisColor = MaterialTheme.colorScheme.outlineVariant
    val weakTopics = topics.filter { it.isWeakness }
    val strongTopics = topics.filter { it.isStrength }

    val week = remember(timestamps) {
        val countsByDay = timestamps.groupingBy { timestamp ->
            Calendar.getInstance().apply {
                timeInMillis = timestamp
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }.eachCount()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        (6 downTo 0).map { daysAgo ->
            val day = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
            val dayKey = day.timeInMillis
            SimpleDateFormat("EEE", Locale.forLanguageTag("ar")).format(day.time) to
                (countsByDay[dayKey] ?: 0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "ملفي التعليمي",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Text(
                "تُحفظ المحادثات والملخصات على هذا الجهاز. لإجابة Smart Cat تُرسل آخر 8 رسائل، " +
                    "وآخر 3 ملخصات (بحد أقصى 500 كلمة) ونقاط الضعف إلى الخادم. عند إغلاق محادثة " +
                    "يُرسل نصها للخادم لإنشاء ملخص يُحفظ محلياً.",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("إجمالي الأسئلة", style = MaterialTheme.typography.titleMedium)
                Text(
                    questionCount.toString(),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("تقدم آخر 7 أيام", style = MaterialTheme.typography.titleMedium)
                Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                    val horizontalPadding = 10.dp.toPx()
                    val verticalPadding = 16.dp.toPx()
                    val chartHeight = size.height - verticalPadding * 2
                    val chartWidth = size.width - horizontalPadding * 2
                    val peak = week.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
                    val points = week.mapIndexed { index, item ->
                        val x = horizontalPadding +
                            chartWidth * index / (week.size - 1).coerceAtLeast(1)
                        val y = verticalPadding + chartHeight -
                            chartHeight * item.second / peak
                        Offset(x, y)
                    }
                    drawLine(
                        color = chartAxisColor,
                        start = Offset(horizontalPadding, size.height - verticalPadding),
                        end = Offset(size.width - horizontalPadding, size.height - verticalPadding),
                        strokeWidth = 2.dp.toPx()
                    )
                    points.zipWithNext().forEach { (start, end) ->
                        drawLine(
                            color = chartLineColor,
                            start = start,
                            end = end,
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                    points.forEach { point ->
                        drawCircle(
                            color = chartLineColor,
                            radius = 5.dp.toPx(),
                            center = point
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    week.forEach { (label, count) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                            Text(count.toString(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("المواد الأكثر تفاعلاً", style = MaterialTheme.typography.titleMedium)
                if (subjects.isEmpty()) {
                    Text("ابدأ بطرح أسئلتك ليظهر نشاطك هنا.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    subjects.take(5).forEach { subject ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(subject.subject)
                            Text("${subject.interactions} سؤال")
                        }
                    }
                }
            }
        }
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("نقاط الضعف المتكررة", style = MaterialTheme.typography.titleMedium)
                if (weakTopics.isEmpty()) {
                    Text("لا توجد نقاط ضعف متكررة بعد.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    weakTopics.forEach { topic ->
                        Text("• ${topic.subject}: ${topic.topic} (${topic.interactions} أسئلة)")
                    }
                }
            }
        }
        if (strongTopics.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("نقاط القوة", style = MaterialTheme.typography.titleMedium)
                    strongTopics.forEach { topic ->
                        Text("• ${topic.subject}: ${topic.topic}")
                    }
                }
            }
        }
        val recentSummaries = conversations.filter { !it.summary.isNullOrBlank() }.take(3)
        if (recentSummaries.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("ملخصات المحادثات الأخيرة", style = MaterialTheme.typography.titleMedium)
                    recentSummaries.forEach { conversation ->
                        Text(conversation.title, fontWeight = FontWeight.SemiBold)
                        Text(conversation.summary.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ==================== SMART CHAT (SMART CAT) SCREEN ====================

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun ChatScreen(viewModel: AppViewModel, onOpenSettings: () -> Unit = {}) {
    val messages by viewModel.currentMessages.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val sessions by viewModel.chatSessions.collectAsState()
    val isGenerating by viewModel.isGeneratingChat.collectAsState()
    val chatActionError by viewModel.chatActionError.collectAsState()
    val socraticModeEnabled by viewModel.socraticModeEnabled.collectAsState()
    val socraticProgress by viewModel.socraticProgress.collectAsState()
    val thinkingModeEnabled by viewModel.useThinkingMode.collectAsState()
    val isListeningToSpeech by viewModel.isListeningToSpeech.collectAsState()
    val speechInputText by viewModel.speechInputText.collectAsState()

    var inputText by rememberSaveable { mutableStateOf("") }
    var pendingVisionImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var showComposerSheet by remember { mutableStateOf(false) }
    var showComposerPlugins by remember { mutableStateOf(false) }
    var showChatHistory by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val chatScope = rememberCoroutineScope()
    val composerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    DisposableEffect(Unit) {
        onDispose { viewModel.closeCurrentChatSession() }
    }
    fun prepareVisionImage(uri: Uri) {
        chatScope.launch {
            try {
                val imageFile = withContext(Dispatchers.IO) {
                    compressChatImage(context, uri)
                }
                pendingVisionImagePath = imageFile.absolutePath
                viewModel.clearChatActionError()
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "Failed to prepare vision image", e)
                Toast.makeText(
                    context,
                    e.localizedMessage ?: "تعذر تجهيز الصورة.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (uri.toString() == pendingCameraUri) {
                    pendingCameraFilePath?.let(::File)?.delete()
                    pendingCameraFilePath = null
                    pendingCameraUri = null
                }
            }
        }
    }
    val visionGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) prepareVisionImage(uri)
    }
    val visionCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val capturedUri = pendingCameraUri?.let(Uri::parse)
        if (success && capturedUri != null) {
            prepareVisionImage(capturedUri)
        } else {
            pendingCameraFilePath?.let(::File)?.delete()
            pendingCameraFilePath = null
            pendingCameraUri = null
        }
    }
    val speechPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startSpeechRecognition()
        else Toast.makeText(context, "يلزم السماح بالميكروفون للإدخال الصوتي.", Toast.LENGTH_SHORT).show()
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to read selected PDF")
                viewModel.sendChatAttachment(
                    Base64.encodeToString(bytes, Base64.NO_WRAP),
                    "application/pdf"
                )
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "Failed to attach PDF", e)
            }
        }
    }

    fun openCameraCapture() {
        try {
            val captureDirectory = File(context.cacheDir, "camera-captures").apply {
                check(mkdirs() || isDirectory) { "تعذر تجهيز مساحة الكاميرا." }
            }
            val captureFile = File.createTempFile("smart-cat-", ".jpg", captureDirectory)
            val captureUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                captureFile
            )
            pendingCameraUri = captureUri.toString()
            pendingCameraFilePath = captureFile.absolutePath
            visionCameraLauncher.launch(captureUri)
        } catch (e: Exception) {
            android.util.Log.e("ChatScreen", "Failed to open camera", e)
            Toast.makeText(context, "تعذر فتح الكاميرا.", Toast.LENGTH_SHORT).show()
        }
    }

    fun closeComposerSheet(action: () -> Unit) {
        chatScope.launch {
            composerSheetState.hide()
            showComposerSheet = false
            showComposerPlugins = false
            action()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.ensureActiveChatSession()
    }

    // Sync speech input text to user text field
    LaunchedEffect(speechInputText) {
        if (speechInputText.isNotEmpty()) {
            inputText = speechInputText
        }
    }

    // Scroll to bottom when messages list size changes
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val sendCurrentMessage = {
        val message = inputText.trim()
        val imagePath = pendingVisionImagePath
        if (imagePath != null) {
            viewModel.sendVisionQuestion(imagePath, message)
            pendingVisionImagePath = null
            inputText = ""
            viewModel.clearChatActionError()
        } else if (message.isNotEmpty()) {
            viewModel.sendChatMessage(message)
            inputText = ""
            viewModel.clearSpeechInput()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Keep the ad as the first content below the settings bar.
        if (!WindowInsets.isImeVisible) {
            BannerAdView(modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Smart Cat", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { viewModel.startNewSession("محادثة جديدة ${sessions.size + 1}") }) {
                Icon(Icons.Default.Add, contentDescription = "محادثة جديدة")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { showChatHistory = true }) {
                Icon(Icons.Default.History, contentDescription = "المحادثات السابقة")
            }
        }

        // Messages List
        Box(modifier = Modifier.weight(1f)) {
            if (currentSessionId == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "ابدأ دردشة فائقة الذكاء مع Smart Cat",
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "اسأل أي سؤال، ابحث عن أفكار، أو تعلّم مهارات جديدة مع حفظ محادثاتك على الجهاز.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    itemsIndexed(messages) { index, msg ->
                        val precedingUserMessage = if (msg.role == "model" && index > 0) {
                            messages.subList(0, index).lastOrNull { it.role == "user" }
                        } else {
                            null
                        }
                        val canRetryVision = precedingUserMessage?.let {
                            parseChatVisionMessage(it.content) != null
                        } == true
                        ChatBubble(
                            message = msg,
                            onSpeakClick = { viewModel.speakText(msg.content) },
                            feedbackQuestion = precedingUserMessage?.let { userMessage ->
                                val imageQuestion = parseChatVisionMessage(userMessage.content)?.question
                                if (imageQuestion != null) {
                                    imageQuestion.ifBlank { "سؤال مرفق بصورة" }
                                } else {
                                    userMessage.content
                                }
                            },
                            onRetryVision = if (canRetryVision) {
                                { precedingUserMessage?.let { userMessage ->
                                    viewModel.retryVisionQuestion(userMessage.id)
                                } }
                            } else null,
                            onFeedbackSubmit = { responseId, question, reply, rating, note ->
                                com.example.network.GeminiApiClient.submitChatFeedback(
                                    responseId = responseId,
                                    question = question,
                                    reply = reply,
                                    rating = rating,
                                    note = note
                                )
                            }
                        )
                    }

                    if (isGenerating) {
                        item {
                            TypingIndicator()
                        }
                    }
                }
            }
        }

        if (socraticModeEnabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (socraticProgress.complete) "أحسنت! أكملت خطوات المسألة"
                        else if (socraticProgress.step == 0) "ابدأ بسؤالك، وسنحلّه معاً خطوة خطوة"
                        else "الخطوة ${socraticProgress.step} من ${socraticProgress.totalSteps} • مستوى ${socraticProgress.difficultyLevel}/5",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (socraticProgress.originalQuestion.isNotBlank()) {
                        TextButton(
                            onClick = viewModel::requestReadySolution,
                            enabled = !isGenerating
                        ) {
                            Text("عايز الحل الجاهز")
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = {
                        if (socraticProgress.complete) 1f
                        else socraticProgress.step.toFloat() /
                            socraticProgress.totalSteps.coerceAtLeast(1)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        chatActionError?.let { errorMessage ->
            Text(
                text = errorMessage,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        pendingVisionImagePath?.let { imagePath ->
            val imageBitmap = remember(imagePath) {
                BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                imageBitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "الصورة المرفقة للمسألة",
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Text(
                    "أضف سؤالك أو اضغط إرسال لحل المسألة بالصورة",
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                IconButton(onClick = { pendingVisionImagePath = null }) {
                    Icon(Icons.Default.Close, contentDescription = "إزالة الصورة")
                }
            }
        }

        // Chat Input row
        Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Microphone/Voice Chat button
                IconButton(
                    onClick = {
                        if (isListeningToSpeech) {
                            viewModel.stopSpeechRecognition()
                        } else {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.startSpeechRecognition()
                            } else {
                                speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .background(
                            if (isListeningToSpeech) Color.Red.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer,
                            CircleShape
                        )
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isListeningToSpeech) Icons.Default.MicNone else Icons.Default.Mic,
                        contentDescription = "التحدث بالصوت",
                        tint = if (isListeningToSpeech) Color.Red else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(
                    onClick = {
                        showComposerPlugins = false
                        showComposerSheet = true
                    },
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            CircleShape
                        )
                        .size(48.dp),
                    enabled = !isGenerating
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "فتح أدوات المحادثة",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { 
                        Text(
                            if (isListeningToSpeech) "جاري الاستماع لصوتك..." else "اكتب رسالتك لـ Smart Cat..."
                        ) 
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input"),
                    shape = RoundedCornerShape(24.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendCurrentMessage() }),
                        maxLines = 4,
                        trailingIcon = {
                            if (inputText.isNotEmpty() || pendingVisionImagePath != null) {
                                IconButton(
                                    onClick = sendCurrentMessage,
                                    enabled = !isGenerating
                                ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                )
            }

        if (showComposerSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    showComposerSheet = false
                    showComposerPlugins = false
                },
                sheetState = composerSheetState,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                containerColor = Color(0xFF17171D),
                contentColor = Color(0xFFF3F0F8),
                dragHandle = {
                    BottomSheetDefaults.DragHandle(color = Color(0xFF777681))
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showComposerPlugins) {
                            IconButton(onClick = { showComposerPlugins = false }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                            }
                        }
                        Text(
                            if (showComposerPlugins) "المكونات الإضافية"
                            else "إضافة إلى المحادثة",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                chatScope.launch {
                                    composerSheetState.hide()
                                    showComposerSheet = false
                                    showComposerPlugins = false
                                }
                            }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق القائمة")
                        }
                    }
                    if (showComposerPlugins) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    viewModel.setSocraticModeEnabled(!socraticModeEnabled)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.School, contentDescription = null)
                            Column(
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("التدريس السقراطي", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "تعلّم خطوة بخطوة عبر الأسئلة",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB9B6C2)
                                )
                            }
                            Switch(
                                checked = socraticModeEnabled,
                                onCheckedChange = viewModel::setSocraticModeEnabled
                            )
                        }
                        ChatComposerSheetAction(
                            icon = Icons.Default.AddComment,
                            title = "محادثة جديدة",
                            subtitle = "ابدأ محادثة منفصلة",
                            onClick = {
                                closeComposerSheet {
                                    viewModel.startNewSession("محادثة جديدة ${sessions.size + 1}")
                                }
                            }
                        )
                        ChatComposerSheetAction(
                            icon = Icons.Default.Settings,
                            title = "إعدادات Smart Cat",
                            subtitle = "تفضيلات المساعد",
                            onClick = { closeComposerSheet(onOpenSettings) }
                        )
                    } else {
                        ChatComposerSheetAction(
                            icon = Icons.Default.CameraAlt,
                            title = "الكاميرا",
                            subtitle = "صوّر مسألة أو صفحة",
                            onClick = { closeComposerSheet(::openCameraCapture) }
                        )
                        ChatComposerSheetAction(
                            icon = Icons.Default.Image,
                            title = "الصور",
                            subtitle = "اختر صورة من المعرض",
                            onClick = {
                                closeComposerSheet { visionGalleryLauncher.launch("image/*") }
                            }
                        )
                        ChatComposerSheetAction(
                            icon = Icons.Default.AttachFile,
                            title = "الملفات",
                            subtitle = "إرفاق ملف PDF",
                            onClick = {
                                closeComposerSheet { pdfLauncher.launch("application/pdf") }
                            }
                        )
                        ChatComposerSheetAction(
                            icon = Icons.Default.Extension,
                            title = "المكونات الإضافية",
                            subtitle = "التدريس السقراطي وأدوات إضافية",
                            onClick = { showComposerPlugins = true }
                        )
                        ChatComposerSheetAction(
                            icon = Icons.Default.Psychology,
                            title = if (thinkingModeEnabled) "إيقاف التفكير الأعمق"
                            else "فكّر بعمق أكبر",
                            subtitle = if (thinkingModeEnabled) {
                                "مفعّل — يستخدم Gemini Pro عند الإرسال"
                            } else {
                                "استخدم نموذج Gemini Pro للأسئلة الصعبة"
                            },
                            selected = thinkingModeEnabled,
                            onClick = {
                                val enabled = !thinkingModeEnabled
                                viewModel.useThinkingMode.value = enabled
                                com.example.network.GeminiApiClient.setThinkingModeEnabled(enabled)
                                closeComposerSheet {}
                            }
                        )
                    }
                }
            }
        }

        if (showChatHistory) {
            AlertDialog(
                onDismissRequest = { showChatHistory = false },
                title = { Text("المحادثات السابقة") },
                text = {
                    if (sessions.isEmpty()) {
                        Text("لا توجد محادثات محفوظة حتى الآن.")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            items(sessions, key = { it.id }) { session ->
                                TextButton(
                                    onClick = {
                                        viewModel.selectSession(session.id)
                                        showChatHistory = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        session.title,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Start,
                                        maxLines = 2
                                    )
                                    Text(
                                        SimpleDateFormat("dd/MM", Locale.getDefault())
                                            .format(Date(session.timestamp)),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showChatHistory = false }) { Text("إغلاق") }
                }
            )
        }
    }
}

@Composable
private fun ChatComposerSheetAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFF302746) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Color(0xFFD1B8FF) else Color(0xFFE4E1EA))
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB9B6C2))
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "مفعّل", tint = Color(0xFFD1B8FF))
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    onSpeakClick: (() -> Unit)? = null,
    onRetryVision: (() -> Unit)? = null,
    feedbackQuestion: String? = null,
    onFeedbackSubmit: suspend (
        String,
        String,
        String,
        com.example.network.GeminiApiClient.FeedbackRating,
        String
    ) -> Boolean = { _, _, _, _, _ -> false }
) {
    val context = LocalContext.current
    val feedbackScope = rememberCoroutineScope()
    var showFeedbackNote by rememberSaveable(message.id) { mutableStateOf(false) }
    var feedbackNote by rememberSaveable(message.id) { mutableStateOf("") }
    var feedbackSubmitted by rememberSaveable(message.id) { mutableStateOf(false) }
    var feedbackSending by rememberSaveable(message.id) { mutableStateOf(false) }
    var feedbackStatus by rememberSaveable(message.id) { mutableStateOf("") }
    var pendingFeedbackRating by remember(message.id) {
        mutableStateOf<com.example.network.GeminiApiClient.FeedbackRating?>(null)
    }
    val isUser = message.role == "user"
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val visionAttachment = remember(message.content) {
        if (isUser) parseChatVisionMessage(message.content) else null
    }
    val attachedImage = remember(visionAttachment?.imagePath) {
        visionAttachment?.imagePath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 0.dp,
                bottomEnd = if (isUser) 0.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isUser) {
                    if (visionAttachment != null) {
                        attachedImage?.let { image ->
                            Image(
                                bitmap = image,
                                contentDescription = "صورة المسألة المرسلة",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                        if (visionAttachment.question.isNotBlank()) {
                            Text(
                                text = visionAttachment.question,
                                modifier = Modifier.padding(top = 8.dp),
                                color = contentColor,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        Text(text = message.content, color = contentColor, fontSize = 14.sp)
                    }
                } else {
                    RichChatContent(
                        content = message.content,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!feedbackQuestion.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("قيّم الإجابة:", fontSize = 11.sp, color = contentColor.copy(alpha = 0.75f))
                            TextButton(
                                onClick = {
                                    if (!feedbackSubmitted && !feedbackSending) {
                                        pendingFeedbackRating =
                                            com.example.network.GeminiApiClient.FeedbackRating.POSITIVE
                                        feedbackSending = true
                                        feedbackScope.launch {
                                            val success = onFeedbackSubmit(
                                                message.id,
                                                feedbackQuestion,
                                                message.content,
                                                com.example.network.GeminiApiClient.FeedbackRating.POSITIVE,
                                                ""
                                            )
                                            feedbackSending = false
                                            feedbackSubmitted = success
                                            feedbackStatus = if (success) "شكراً لتقييمك!" else "تعذر إرسال التقييم. حاول مرة أخرى."
                                            if (!success) pendingFeedbackRating = null
                                        }
                                    }
                                },
                                enabled = !feedbackSubmitted && !feedbackSending,
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) { Text("👍", fontSize = 16.sp) }
                            TextButton(
                                onClick = {
                                    pendingFeedbackRating =
                                        com.example.network.GeminiApiClient.FeedbackRating.NEGATIVE
                                    feedbackNote = ""
                                    showFeedbackNote = true
                                },
                                enabled = !feedbackSubmitted && !feedbackSending,
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) { Text("👎", fontSize = 16.sp) }
                            TextButton(
                                onClick = {
                                    pendingFeedbackRating =
                                        com.example.network.GeminiApiClient.FeedbackRating.INCORRECT
                                    feedbackNote = ""
                                    showFeedbackNote = true
                                },
                                enabled = !feedbackSubmitted && !feedbackSending,
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) { Text("🚩", fontSize = 16.sp) }
                        }
                        if (feedbackStatus.isNotBlank()) {
                            Text(
                                feedbackStatus,
                                fontSize = 11.sp,
                                color = if (feedbackSubmitted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (onRetryVision != null) {
                        TextButton(onClick = onRetryVision) {
                            Icon(Icons.Default.Replay, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("حل تاني")
                        }
                    }

                    if (showFeedbackNote) {
                        AlertDialog(
                            onDismissRequest = { showFeedbackNote = false },
                            title = {
                                Text(
                                    if (pendingFeedbackRating == com.example.network.GeminiApiClient.FeedbackRating.INCORRECT) {
                                        "الإبلاغ عن إجابة خاطئة"
                                    } else {
                                        "ملاحظتك على الإجابة"
                                    }
                                )
                            },
                            text = {
                                OutlinedTextField(
                                    value = feedbackNote,
                                    onValueChange = { feedbackNote = it.take(1_000) },
                                    label = { Text("اكتب ملاحظة (اختياري)") },
                                    maxLines = 4
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = !feedbackSending,
                                    onClick = {
                                        val rating = pendingFeedbackRating ?: return@TextButton
                                        feedbackSending = true
                                        feedbackScope.launch {
                                            val success = onFeedbackSubmit(
                                                message.id,
                                                feedbackQuestion.orEmpty(),
                                                message.content,
                                                rating,
                                                feedbackNote.trim()
                                            )
                                            feedbackSending = false
                                            feedbackSubmitted = success
                                            feedbackStatus = if (success) "شكراً لتقييمك!" else "تعذر إرسال التقييم. حاول مرة أخرى."
                                            if (success) showFeedbackNote = false
                                        }
                                    }
                                ) { Text(if (feedbackSending) "جارٍ الإرسال..." else "إرسال") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showFeedbackNote = false }) { Text("إلغاء") }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isUser) {
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    clipboard.setPrimaryClip(ClipData.newPlainText("رد Smart Cat", message.content))
                                    Toast.makeText(context, "تم نسخ الرد.", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, "نسخ الرد", tint = contentColor, modifier = Modifier.size(16.dp))
                            }
                            if (onSpeakClick != null) {
                                IconButton(onClick = onSpeakClick, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = "قراءة النص بصوت عالٍ",
                                        tint = contentColor.copy(alpha = 0.8f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }
                    }
                    Text(
                        text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(message.timestamp)),
                        fontSize = 9.sp,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val animatedY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -10f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotsY"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("جاري التفكير والكتابة", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (0..2).forEach { index ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .graphicsLayer {
                                this.translationY = if (index == 0) animatedY else animatedY * 0.5f
                            }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

// ==================== PRODUCTIVITY HUBS SCREEN ====================

@Composable
fun ProductivityScreen(viewModel: AppViewModel) {
    val docs by viewModel.productivityDocs.collectAsState()
    val selectedDoc by viewModel.selectedDoc.collectAsState()
    val isGenerating by viewModel.isGeneratingProd.collectAsState()

    var activeTab by remember { mutableStateOf("write") } // "write", "summarize", "saved"
    var promptInput by remember { mutableStateOf("") }
    var prodType by remember { mutableStateOf("research") } // "research", "report", "presentation"

    var simulatedFileContent by remember { mutableStateOf("") }
    var fileTypeSelected by remember { mutableStateOf("PDF") }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sub Tabs
        TabRow(
            selectedTabIndex = when (activeTab) {
                "write" -> 0
                "summarize" -> 1
                "saved" -> 2
                else -> 0
            }
        ) {
            Tab(selected = activeTab == "write", onClick = { activeTab = "write" }) {
                Text("صناعة المحتوى", modifier = Modifier.padding(12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Tab(selected = activeTab == "summarize", onClick = { activeTab = "summarize" }) {
                Text("التلخيص والأبحاث", modifier = Modifier.padding(12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Tab(selected = activeTab == "saved", onClick = { activeTab = "saved" }) {
                Text("المستندات المحفوظة", modifier = Modifier.padding(12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp)
        ) {
            when (activeTab) {
                "write" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("اكتب أبحاثاً، مقالات، تقارير أو خطط عروض تقديمي بالذكاء الاصطناعي:", fontSize = 13.sp, fontWeight = FontWeight.Bold)

                        // Selector
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("research" to "بحث / مقال", "report" to "تقرير إداري", "presentation" to "عروض (Slides)").forEach { (type, name) ->
                                FilterChip(
                                    selected = prodType == type,
                                    onClick = { prodType = type },
                                    label = { Text(name) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            placeholder = { Text("مثال: اكتب مقالاً وافياً عن مستقبل الحوسبة الكمية ودورها في الذكاء الاصطناعي...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            maxLines = 5
                        )

                        Button(
                            onClick = {
                                viewModel.generateProductivityDoc(promptInput, prodType)
                                activeTab = "saved"
                                promptInput = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isGenerating && promptInput.isNotEmpty()
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Text("توليد المستند الآن")
                            }
                        }
                    }
                }
                "summarize" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("تلخيص الكتب والملفات الذكي:", fontSize = 13.sp, fontWeight = FontWeight.Bold)

                        // Simulation of File Upload
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("PDF", "Word", "Excel").forEach { format ->
                                InputChip(
                                    selected = fileTypeSelected == format,
                                    onClick = {
                                        fileTypeSelected = format
                                        simulatedFileContent = getSimulatedFileContent(format)
                                    },
                                    label = { Text(format) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = simulatedFileContent,
                            onValueChange = { simulatedFileContent = it },
                            placeholder = { Text("الصق هنا نصوص الكتاب أو الملف الذي تود تلخيصه...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            maxLines = 10
                        )

                        Button(
                            onClick = {
                                viewModel.generateProductivityDoc("قم بتلخيص هذا الملف ($fileTypeSelected):\n$simulatedFileContent", "summary")
                                activeTab = "saved"
                                simulatedFileContent = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isGenerating && simulatedFileContent.isNotEmpty()
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Text("تلخيص الملف بالكامل")
                            }
                        }
                    }
                }
                "saved" -> {
                    if (selectedDoc != null) {
                        val doc = selectedDoc
                        // Display Single Document
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { doc?.id?.let(viewModel::deleteProductivityDoc) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                                }
                                Text(
                                    doc?.title.orEmpty(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.End
                                )
                                Button(onClick = { doc?.content?.let { viewModel.generateProductivityDoc("تحويل النص إلى صوت: $it", "speech") } }, modifier = Modifier.padding(start = 8.dp)) {
                                    Icon(Icons.Default.VolumeUp, contentDescription = "Listen")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("استمع")
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                    .padding(16.dp)
                            ) {
                                Text(doc?.content.orEmpty(), fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { viewModel.clearSelectedDoc() }, modifier = Modifier.fillMaxWidth()) {
                                Text("العودة للقائمة")
                            }
                        }
                    } else {
                        // Display Saved Documents list
                        if (docs.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("لا يوجد مستندات حالياً", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(docs) { doc ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.selectProductivityDoc(doc) },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(16.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(doc.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                Text(
                                                    text = when (doc.type) {
                                                        "research" -> "بحث / مقال"
                                                        "summary" -> "تلخيص ملف"
                                                        "report" -> "تقرير أعمال"
                                                        "presentation" -> "عرض تقديمي"
                                                        else -> "مستند إنتاجي"
                                                    },
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Icon(Icons.Default.ArrowForward, contentDescription = "Open")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getSimulatedFileContent(format: String): String {
    return when (format) {
        "PDF" -> "كتاب 'مقدمة ابن خلدون': الفصل الثاني في العمران البدوي والأمم الوحشية والقبائل، وفيه بيان أن أهل البدو أقرب إلى الخير من أهل الحضر، وأنهم أشجع من الحضر لبعدهم عن القوانين المفسدة للبأس والمضعفة للنفوس..."
        "Word" -> "تقرير مشروع تطوير تطبيقات الذكاء الاصطناعي للمؤسسات لعام 2026. الأهداف تشمل: تقليل تكاليف خدمة العملاء بنسبة 40%، تسريع وتيرة إنتاج المقالات، وتوفير أدوات مساعدة آمنة بالكامل للطلاب..."
        "Excel" -> "الجدول المالي للمبيعات والأرباح الربع سنوية: الربع الأول: المبيعات 120,000 ريال، الأرباح 45,000 ريال. الربع الثاني: المبيعات 145,000 ريال، الأرباح 55,000 ريال. الربع الثالث: المبيعات 170,000 ريال، الأرباح 68,000 ريال..."
        else -> ""
    }
}

// ==================== AI PERSONAS SCREEN ====================

data class Persona(
    val id: String,
    val name: String,
    val role: String,
    val icon: ImageVector,
    val color: Color
)

@Composable
fun PersonasScreen(viewModel: AppViewModel) {
    val selectedPersonaId by viewModel.selectedPersonaId.collectAsState()
    val personaVoiceEnabled by viewModel.personaVoiceEnabled.collectAsState()
    val isGenerating by viewModel.isGeneratingPersona.collectAsState()
    val messagesMap by viewModel.personaMessages.collectAsState()
    val isListeningToSpeech by viewModel.isListeningToSpeech.collectAsState()
    val speechInputText by viewModel.speechInputText.collectAsState()

    // Gamification and Recorder States
    val userPoints by viewModel.userPoints.collectAsState()
    val userBadges by viewModel.userBadges.collectAsState()
    val leaderboardList by viewModel.leaderboardList.collectAsState()
    val isRecordingExplanation by viewModel.isRecordingExplanation.collectAsState()
    val explanationText by viewModel.explanationText.collectAsState()

    val personasList = listOf(
        Persona("hasan", "حسن (صوت ولد)", "صاحبك الجدع - صوت ولد تفاعلي", Icons.Default.RecordVoiceOver, Color(0xFF2563EB)),
        Persona("jana", "ريتاج (صوت بنت)", "صديقتك الذكية - صوت بنت رقيق", Icons.Default.Face, Color(0xFFEC4899)),
        Persona("teacher", "أ. أحمد", "معلم ومبسط العلوم", Icons.Default.School, Color(0xFF10B981)),
        Persona("coder", "Coder AI", "خبير البرمجة والأكواد", Icons.Default.Code, Color(0xFF14B8A6)),
        Persona("doctor", "د. خالد", "طبيب العائلة التوعوي", Icons.Default.LocalHospital, Color(0xFFEF4444)),
        Persona("business", "أ. سمير", "مستشار دراسات الجدوى", Icons.Default.BusinessCenter, Color(0xFFF59E0B)),
        Persona("legal", "المستشار عادل", "استشاري الشؤون القانونية", Icons.Default.Gavel, Color(0xFF8B5CF6)),
        Persona("sheikh", "الشيخ عبد الرحمن", "فقيه الشريعة المعتدل", Icons.Default.SelfImprovement, Color(0xFF06B6D4)),
        Persona("coach", "الكابتن فهد", "مدرب اللياقة والصحة", Icons.Default.FitnessCenter, Color(0xFFF97316)),
        Persona("designer", "المصمم فنان", "واجهات التصميم الجرافيكي", Icons.Default.Palette, Color(0xFFEC4899)),
        Persona("chef", "الشيف مراد", "وصفات الطهي والحلويات", Icons.Default.Restaurant, Color(0xFF06B6D4)),
        Persona("nanny", "المربية فاطمة", "تربية الأطفال والأسرة", Icons.Default.BabyChangingStation, Color(0xFF6366F1))
    )

    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val speechPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startSpeechRecognition()
        else Toast.makeText(context, "يلزم السماح بالميكروفون للإدخال الصوتي.", Toast.LENGTH_SHORT).show()
    }

    // Image selection launcher
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                if (bytes != null) {
                    val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                    viewModel.sendMultimodalMessage(base64, "image/jpeg")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // PDF selection launcher
    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                if (bytes != null) {
                    val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                    viewModel.sendMultimodalMessage(base64, "application/pdf")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    var textInput by remember { mutableStateOf("") }
    var showVoiceSetupDialog by remember { mutableStateOf(false) }
    val currentMessages = messagesMap[selectedPersonaId] ?: emptyList()
    val activePersona = personasList.first { it.id == selectedPersonaId }

    val listState = rememberLazyListState()

    // Sync speech input text to user text field
    LaunchedEffect(speechInputText) {
        if (speechInputText.isNotEmpty()) {
            textInput = speechInputText
        }
    }

    LaunchedEffect(currentMessages.size) {
        if (currentMessages.isNotEmpty()) {
            listState.animateScrollToItem(currentMessages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Personas Horizontal List Selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            personasList.forEach { p ->
                val isSelected = p.id == selectedPersonaId
                Column(
                    modifier = Modifier
                        .clickable { viewModel.selectedPersonaId.value = p.id }
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) p.color else p.color.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = p.icon,
                            contentDescription = p.name,
                            tint = if (isSelected) Color.White else p.color,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        p.name,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Divider()

        // Banner Ad (took the place of the old large voice card)
        BannerAdView(modifier = Modifier.fillMaxWidth())

        // Study Mode Active Banner
        val isStudyModeActive by viewModel.isStudyModeActive.collectAsState()
        if (isStudyModeActive) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.School,
                            contentDescription = "وضع المذاكرة",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                "وضع المذاكرة نشط 📚",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "حسن وريتاج هيشرحوا بالخطوات والأمثلة مع أسئلة اختبار",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Button(
                        onClick = { viewModel.finishStudyAndGetReport() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("إنهاء والتقرير 🎓", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Persona Chat History
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (currentMessages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(activePersona.color.copy(alpha = 0.15f))
                            .border(2.dp, activePersona.color.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "H",
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Black,
                            color = activePersona.color
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(currentMessages) { msg ->
                        PersonaBubble(msg = msg, p = activePersona, onSpeakClick = { viewModel.speakText(msg.content) })
                    }
                    if (isGenerating) {
                        item {
                            TypingIndicator()
                        }
                    }
                }
            }
        }

        // Study Mode quick action buttons
        if (isStudyModeActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.explainAgainSimply() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("اشرحلي تاني بسهولة 💡", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { viewModel.makeExamForMe() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Quiz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("اعملي امتحان على ده 📝", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Input Box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Camera Button
            IconButton(
                onClick = { imageLauncher.launch("image/*") },
                modifier = Modifier
                    .padding(end = 4.dp)
                    .background(activePersona.color.copy(alpha = 0.12f), CircleShape)
                    .size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = "صورلي وحلهالي",
                    tint = activePersona.color,
                    modifier = Modifier.size(20.dp)
                )
            }

            // PDF Button
            IconButton(
                onClick = { pdfLauncher.launch("application/pdf") },
                modifier = Modifier
                    .padding(end = 4.dp)
                    .background(activePersona.color.copy(alpha = 0.12f), CircleShape)
                    .size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = "رفع ملف PDF",
                    tint = activePersona.color,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Microphone/Voice Chat button
            IconButton(
                onClick = {
                    if (isListeningToSpeech) {
                        viewModel.stopSpeechRecognition()
                    } else {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            showVoiceSetupDialog = true
                        } else {
                            speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                modifier = Modifier
                    .padding(end = 4.dp)
                    .background(
                        if (isListeningToSpeech) Color.Red.copy(alpha = 0.2f) else activePersona.color.copy(alpha = 0.15f),
                        CircleShape
                    )
                    .size(44.dp)
            ) {
                Icon(
                    imageVector = if (isListeningToSpeech) Icons.Default.MicNone else Icons.Default.Mic,
                    contentDescription = "التحدث بالصوت",
                    tint = if (isListeningToSpeech) Color.Red else activePersona.color,
                    modifier = Modifier.size(20.dp)
                )
            }

            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { 
                    Text(
                        if (isListeningToSpeech) "جاري الاستماع لصوتك..." else "اطرح سؤالاً على ${activePersona.name}..."
                    ) 
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = activePersona.color,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    focusedBorderColor = activePersona.color,
                    unfocusedBorderColor = activePersona.color.copy(alpha = 0.7f)
                ),
                trailingIcon = {
                    if (textInput.isNotEmpty()) {
                        IconButton(onClick = {
                            viewModel.sendPersonaMessage(textInput)
                            textInput = ""
                            viewModel.clearSpeechInput()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = activePersona.color
                            )
                        }
                    }
                }
            )
        }
    }

    if (showVoiceSetupDialog) {
        VoiceSetupDialog(
            viewModel = viewModel,
            onDismiss = { showVoiceSetupDialog = false },
            onStartVoice = {
                showVoiceSetupDialog = false
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    viewModel.startSpeechRecognition()
                } else {
                    speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        )
    }
}

@Composable
fun PersonaBubble(msg: ChatMessage, p: Persona, onSpeakClick: (() -> Unit)? = null) {
    val isUser = msg.role == "user"
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val containerColor = if (isUser) p.color else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isUser) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isUser) {
                    Text(msg.content, color = contentColor, fontSize = 13.sp)
                } else {
                    Markdown(content = msg.content, modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isUser) {
                            val context = LocalContext.current
                            IconButton(
                                onClick = {
                                    context.getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("رد المساعد", msg.content))
                                    Toast.makeText(context, "تم نسخ الرد.", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, "نسخ الرد", tint = contentColor, modifier = Modifier.size(16.dp))
                            }
                            if (onSpeakClick != null) {
                                IconButton(onClick = onSpeakClick, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        Icons.Default.VolumeUp,
                                        contentDescription = "قراءة النص بصوت عالٍ",
                                        tint = contentColor.copy(alpha = 0.8f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }
                    }
                    Text(
                        text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(msg.timestamp)),
                        fontSize = 8.sp,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

// ==================== ORGANIZER & SCHEDULER SCREEN ====================

@Composable
fun OrganizerScreen(viewModel: AppViewModel) {
    val schedule by viewModel.userSchedule.collectAsState()
    val isGenerating by viewModel.isGeneratingSchedule.collectAsState()

    var ageInput by remember { mutableStateOf("") }
    var occupation by remember { mutableStateOf("student") } // "student", "employee", "both"
    var workSchoolTimings by remember { mutableStateOf("") }
    var lessonTimings by remember { mutableStateOf("") }

    var glassesOfWater by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (schedule == null) {
            // Schedule builder form
            Text("ابنِ جدولك اليومي المتكامل والصحي بالذكاء الاصطناعي:", fontWeight = FontWeight.Bold, fontSize = 16.sp)

            OutlinedTextField(
                value = ageInput,
                onValueChange = { ageInput = it },
                label = { Text("كم عمرك؟") },
                modifier = Modifier.fillMaxWidth()
            )

            Text("ما هي طبيعة عملك أو دراستك؟", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("student" to "طالب", "employee" to "موظف", "both" to "طالب وموظف").forEach { (occ, label) ->
                    FilterChip(
                        selected = occupation == occ,
                        onClick = { occupation = occ },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            OutlinedTextField(
                value = workSchoolTimings,
                onValueChange = { workSchoolTimings = it },
                label = { Text("مواعيد العمل أو المدرسة والمحاضرات اليومية") },
                placeholder = { Text("مثال: من 8 صباحاً إلى 2 ظهراً") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = lessonTimings,
                onValueChange = { lessonTimings = it },
                label = { Text("مواعيد الدروس والمواد الإضافية بالملّي") },
                placeholder = { Text("مثال: فيزياء السبت والثلاثاء 4 عصراً") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val age = ageInput.toIntOrNull() ?: 20
                    viewModel.generateDailySchedule(age, occupation, workSchoolTimings, lessonTimings)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isGenerating && ageInput.isNotEmpty()
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text("بناء جدول اليوم المتكامل ذكياً")
                }
            }
        } else {
            // Schedule View + health trackers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.clearSchedule() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Schedule", tint = Color.Red)
                }
                Text("جدولك اليومي المولد بالكامل:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            // Health & Water Trackers
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("متابع شرب المياه اليومي 💧", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("أكواب اليوم: $glassesOfWater / 8 أكواب", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { if (glassesOfWater < 12) glassesOfWater++ },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("+ كوب ماء")
                    }
                }
            }

            // Generated Schedule details
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Text(schedule?.scheduleText.orEmpty(), fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuranCoachScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val surahs by viewModel.quranSurahs.collectAsState()
    val selectedSurah by viewModel.selectedQuranSurah.collectAsState()
    val ayahs by viewModel.quranAyahs.collectAsState()
    val selectedAyah by viewModel.selectedQuranAyah.collectAsState()
    val isLoadingQuran by viewModel.isLoadingQuran.collectAsState()
    val isLoadingAyahs by viewModel.isLoadingAyahs.collectAsState()
    val isRecording by viewModel.isRecordingQuran.collectAsState()
    val isChecking by viewModel.isCheckingQuran.collectAsState()
    val result by viewModel.quranRecitationResult.collectAsState()
    val error by viewModel.quranCoachError.collectAsState()
    val records by viewModel.quranRecords.collectAsState()
    val reciterName by viewModel.quranReciterName.collectAsState()

    var isSurahMenuExpanded by remember { mutableStateOf(false) }
    var surahSearchQuery by remember { mutableStateOf("") }
    var isAyahMenuExpanded by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf(false) }
    var showQuranHistory by remember { mutableStateOf(false) }
    var selectedQuranRecord by remember { mutableStateOf<QuranRecord?>(null) }
    val weekStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -6)
        }.timeInMillis
    }
    val weeklyRecords = records.filter { it.timestamp >= weekStart }
    val averageScore = records.takeIf { it.isNotEmpty() }?.map { it.score }?.average()
    val monthStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val monthlyRecords = records.count { it.timestamp >= monthStart }
    val filteredSurahs = remember(surahs, surahSearchQuery) {
        val query = normalizeSurahSearchText(surahSearchQuery)
        if (query.isBlank()) {
            surahs
        } else {
            surahs.filter { surah ->
                surah.number.toString().contains(query) ||
                    normalizeSurahSearchText(surah.name).contains(query) ||
                    normalizeSurahSearchText(surah.englishName).contains(query)
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionError = !granted
        if (granted) viewModel.startQuranRecording()
    }

    LaunchedEffect(Unit) {
        viewModel.loadQuranSurahs()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "تجويد القرآن",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "اختر السورة والآية، ثم سجّل تلاوتك لتحصل على ملاحظات مختصرة.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("إحصائيات التلاوة", fontWeight = FontWeight.Bold)
                        Text(
                            "${records.size} تلاوة • متوسط ${averageScore?.let { "%.0f".format(Locale.getDefault(), it) } ?: "—"}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(onClick = { showQuranHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("السجل")
                    }
                }
                Text("تلاوات آخر 7 أيام: ${weeklyRecords.size}", style = MaterialTheme.typography.labelMedium)
                Text("تلاوات هذا الشهر: $monthlyRecords", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    (6 downTo 0).forEach { daysAgo ->
                        val day = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_YEAR, -daysAgo)
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val count = weeklyRecords.count { record ->
                            val recordDay = Calendar.getInstance().apply { timeInMillis = record.timestamp }
                            recordDay.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
                                recordDay.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height((10 + count.coerceAtMost(6) * 9).dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                SimpleDateFormat("EE", Locale("ar")).format(day.time),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = surahSearchQuery,
            onValueChange = { surahSearchQuery = it },
            label = { Text("ابحث عن السورة") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { isSurahMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoadingQuran
            ) {
                Text(
                    selectedSurah?.let { "${it.number}. ${it.name}" }
                        ?: if (isLoadingQuran) "جاري تحميل السور..." else "اختر السورة"
                )
            }
            DropdownMenu(
                expanded = isSurahMenuExpanded,
                onDismissRequest = { isSurahMenuExpanded = false },
                modifier = Modifier.heightIn(max = 360.dp)
            ) {
                filteredSurahs.forEach { surah ->
                    DropdownMenuItem(
                        text = { Text("${surah.number}. ${surah.name} (${surah.englishName})") },
                        onClick = {
                            isSurahMenuExpanded = false
                            surahSearchQuery = ""
                            viewModel.selectQuranSurah(surah.number)
                        }
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { isAyahMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedSurah != null && !isLoadingAyahs && ayahs.isNotEmpty()
            ) {
                Text(
                    selectedAyah?.let { "الآية ${it.numberInSurah}" }
                        ?: if (isLoadingAyahs) "جاري تحميل الآيات..." else "اختر الآية"
                )
            }
            DropdownMenu(
                expanded = isAyahMenuExpanded,
                onDismissRequest = { isAyahMenuExpanded = false },
                modifier = Modifier.heightIn(max = 360.dp)
            ) {
                ayahs.forEach { ayah ->
                    DropdownMenuItem(
                        text = { Text("الآية ${ayah.numberInSurah}: ${ayah.text.take(70)}") },
                        onClick = {
                            isAyahMenuExpanded = false
                            viewModel.selectQuranAyah(ayah.numberInSurah)
                        }
                    )
                }
            }
        }
        selectedAyah?.let { ayah ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    ayah.text,
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 24.sp,
                    lineHeight = 42.sp
                )
            }
        }
        if (isLoadingQuran || isLoadingAyahs) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text("استمع إلى السورة كاملة بصوت الشيخ:", fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.playQuranReciterSurah(6, "محمود خليل الحصري") },
                modifier = Modifier.weight(1f),
                enabled = selectedSurah != null
            ) {
                Text(if (reciterName?.startsWith("محمود خليل الحصري") == true) "إيقاف الحصري" else "الحصري")
            }
            OutlinedButton(
                onClick = { viewModel.playQuranReciterSurah(9, "محمد صديق المنشاوي") },
                modifier = Modifier.weight(1f),
                enabled = selectedSurah != null
            ) {
                Text(if (reciterName?.startsWith("محمد صديق المنشاوي") == true) "إيقاف المنشاوي" else "المنشاوي")
            }
        }
        reciterName?.let { Text("يقرأ الآن: $it", color = MaterialTheme.colorScheme.primary) }

        val hasMicrophonePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Button(
            onClick = {
                permissionError = false
                if (hasMicrophonePermission) viewModel.startQuranRecording()
                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            modifier = Modifier.fillMaxWidth().height(58.dp),
            enabled = selectedSurah != null && selectedAyah != null && !isRecording && !isChecking
        ) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isRecording) "جاري التسجيل..." else "سجّل تلاوتك للآية")
        }
        if (isRecording) {
            OutlinedButton(
                onClick = { viewModel.stopQuranRecordingAndCheck() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("إيقاف التسجيل ومراجعة التلاوة")
            }
        }
        if (permissionError) {
            Text("يلزم السماح باستخدام الميكروفون لتسجيل التلاوة.", color = MaterialTheme.colorScheme.error)
        }

        error?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        message,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    IconButton(onClick = { viewModel.clearQuranCoachError() }) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق رسالة الخطأ")
                    }
                }
            }
        }
        if (isChecking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("جاري تفريغ الصوت ومراجعة الآية...")
        }

        result?.let { assessment ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("نتيجة التلاوة", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(assessment.summary, lineHeight = 24.sp)
                    HorizontalDivider()
                    Text("النص المسموع:", fontWeight = FontWeight.Bold)
                    Text(assessment.transcript, textAlign = TextAlign.End)
                    if (assessment.mistakes.isNotEmpty()) {
                        Text("الكلمات التي تحتاج مراجعة:", fontWeight = FontWeight.Bold)
                        assessment.mistakes.forEach { (heard, correct) ->
                            Text("• «$heard» — الصواب «$correct»")
                        }
                    }
                    if (assessment.tajweedTips.isNotEmpty()) {
                        Text("نصائح التجويد:", fontWeight = FontWeight.Bold)
                        assessment.tajweedTips.forEach { tip -> Text("• $tip") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "نتيجة تجويد القرآن - ${selectedSurah?.name.orEmpty()}، الآية ${selectedAyah?.numberInSurah ?: ""}\n${assessment.summary}\nالنص المسموع: ${assessment.transcript}"
                                    )
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "مشاركة نتيجة التلاوة"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("مشاركة")
                        }
                        Button(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    viewModel.startQuranRecording()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("جرب تاني")
                        }
                    }
                }
            }
        }
    }

    if (showQuranHistory) {
        AlertDialog(
            onDismissRequest = { showQuranHistory = false },
            title = { Text("سجل التلاوات") },
            text = {
                if (records.isEmpty()) {
                    Text("ستظهر تلاواتك السابقة هنا بعد أول تقييم.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                        items(records, key = { it.id }) { record ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        selectedQuranRecord = record
                                        showQuranHistory = false
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${record.surah} ${if (record.ayahNumber > 0) "• الآية ${record.ayahNumber}" else ""}",
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Start
                                        )
                                        Text(
                                            SimpleDateFormat("yyyy/MM/dd  hh:mm a", Locale.getDefault())
                                                .format(Date(record.timestamp)),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Text("${record.score}%")
                                }
                                IconButton(onClick = { viewModel.deleteQuranRecord(record.id) }) {
                                    Icon(Icons.Default.DeleteOutline, "حذف التلاوة", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQuranHistory = false }) { Text("إغلاق") }
            }
        )
    }

    selectedQuranRecord?.let { record ->
        val assessment = remember(record.assessmentJson) {
            runCatching { org.json.JSONObject(record.assessmentJson) }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { selectedQuranRecord = null },
            title = { Text("${record.surah}${if (record.ayahNumber > 0) " • الآية ${record.ayahNumber}" else ""}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(SimpleDateFormat("yyyy/MM/dd  hh:mm a", Locale.getDefault()).format(Date(record.timestamp)))
                    Text("التقييم: ${record.score}%")
                    Text("التفريغ: ${record.userTranscription}")
                    Text(record.aiFeedback)
                    assessment?.optString("audioQuality")?.takeIf(String::isNotBlank)?.let { Text("جودة الصوت: $it") }
                    assessment?.optJSONArray("mistakes")?.let { mistakes ->
                        for (index in 0 until mistakes.length()) {
                            val mistake = mistakes.optJSONObject(index) ?: continue
                            Text("• ${mistake.optString("heard")} ← ${mistake.optString("correct")}")
                        }
                    }
                    assessment?.optJSONArray("tajweedTips")?.let { tips ->
                        for (index in 0 until tips.length()) Text("• ${tips.optString(index)}")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedQuranRecord = null }) { Text("إغلاق") }
            }
        )
    }
}

private fun normalizeSurahSearchText(value: String): String = buildString {
    value.lowercase(Locale.ROOT).forEach { character ->
        when (character) {
            in '\u0660'..'\u0669' -> append(('0'.code + character.code - '\u0660'.code).toChar())
            in '\u06F0'..'\u06F9' -> append(('0'.code + character.code - '\u06F0'.code).toChar())
            '\u0640' -> Unit
            else -> if (character.code !in 0x064B..0x065F && character.code != 0x0670) {
                append(character)
            }
        }
    }
}.trim()

// ==================== SETTINGS DIALOG ====================

@Composable
fun SettingsDialog(
    viewModel: AppViewModel,
    onDismiss: () -> Unit
) {
    val sessions by viewModel.chatSessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val socraticModeEnabled by viewModel.socraticModeEnabled.collectAsState()
    val reminderEnabled by viewModel.quranReminderEnabled.collectAsState()
    val reminderHour by viewModel.quranReminderHour.collectAsState()
    val reminderMinute by viewModel.quranReminderMinute.collectAsState()
    val context = LocalContext.current
    val reminderPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setQuranReminder(reminderHour, reminderMinute, true)
        } else {
            Toast.makeText(context, "اسمح بالإشعارات لتفعيل التذكير اليومي.", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "إعدادات التطبيق",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("وضع التدريس السقراطي", fontWeight = FontWeight.Bold)
                                Text(
                                    "يقودك بتلميحات وأسئلة، ويمكنك طلب الحل الكامل في أي وقت.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = socraticModeEnabled,
                                onCheckedChange = viewModel::setSocraticModeEnabled
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("تذكير يومي بالقرآن", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "موعد التذكير: %02d:%02d".format(Locale.getDefault(), reminderHour, reminderMinute),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = {
                                    TimePickerDialog(
                                        context,
                                        { _, hour, minute ->
                                            val shouldEnable = reminderEnabled
                                            if (shouldEnable) {
                                                viewModel.setQuranReminder(hour, minute, true)
                                            } else {
                                                viewModel.setQuranReminder(hour, minute, false)
                                                viewModel.setQuranReminderTime(hour, minute)
                                            }
                                        },
                                        reminderHour,
                                        reminderMinute,
                                        true
                                    ).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("اختيار الوقت")
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = reminderEnabled,
                                onCheckedChange = { enabled ->
                                    if (!enabled) {
                                        viewModel.setQuranReminder(reminderHour, reminderMinute, false)
                                    } else if (
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        reminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        viewModel.setQuranReminder(reminderHour, reminderMinute, true)
                                    }
                                }
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "المحادثات",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val mainSession = sessions.firstOrNull {
                                        it.title == "محادثة رئيسية"
                                    }
                                    if (mainSession != null) {
                                        viewModel.selectSession(mainSession.id)
                                    } else {
                                        viewModel.startNewSession("محادثة رئيسية")
                                    }
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("المحادثة الرئيسية")
                            }
                            Button(
                                onClick = {
                                    viewModel.startNewSession("محادثة جديدة ${sessions.size + 1}")
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("محادثة جديدة")
                            }
                        }
                        if (sessions.isEmpty()) {
                            Text("لا توجد محادثات محفوظة.", fontSize = 12.sp)
                        } else {
                            sessions.forEach { session ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.selectSession(session.id)
                                            onDismiss()
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.ChatBubbleOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        session.title,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp),
                                        maxLines = 1
                                    )
                                    if (session.id == currentSessionId) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "المحادثة الحالية",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.deleteSession(session.id)
                                            if (session.id == currentSessionId) {
                                                sessions.firstOrNull { it.id != session.id }
                                                    ?.let { viewModel.selectSession(it.id) }
                                                    ?: viewModel.startNewSession("محادثة رئيسية")
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "حذف المحادثة",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "السمات (Themes)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "purple" to "بنفسجي 💜",
                                "dark" to "داكن 🌙",
                                "light" to "فاتح ☀️"
                            ).forEach { (id, label) ->
                                val isSelected = appTheme == id
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surface
                                        )
                                        .clickable { viewModel.appTheme.value = id }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        color = if (isSelected) Color.White
                                        else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.Bold
                                        else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("تم")
                }
            }
        }
    }
}

@Composable
private fun LegacySettingsDialog(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    onNavigate: (AppScreen) -> Unit
) {
    val sessions by viewModel.chatSessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val speed by viewModel.speechSpeed.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val autoReadChat by viewModel.autoReadChatEnabled.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("إعدادات تطبيق H2 Hub", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق الإعدادات")
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("المحادثات", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Button(
                                onClick = {
                                    viewModel.startNewSession("محادثة جديدة ${sessions.size + 1}")
                                    onDismiss()
                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("محادثة جديدة")
                            }
                        }
                        if (sessions.isEmpty()) {
                            Text("لا توجد محادثات محفوظة.", fontSize = 12.sp)
                        } else {
                            sessions.forEach { session ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.selectSession(session.id)
                                            onDismiss()
                                        }
                                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.ChatBubbleOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        session.title,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp),
                                        maxLines = 1
                                    )
                                    if (session.id == currentSessionId) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "المحادثة الحالية",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.deleteSession(session.id)
                                            if (session.id == currentSessionId) {
                                                sessions.firstOrNull { it.id != session.id }
                                                    ?.let { viewModel.selectSession(it.id) }
                                                    ?: viewModel.startNewSession("محادثة رئيسية")
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "حذف المحادثة",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("خصائص التطبيق", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        listOf(
                            AppScreen.CHAT,
                            AppScreen.QURAN,
                            AppScreen.PRODUCTIVITY,
                            AppScreen.PERSONAS,
                            AppScreen.ORGANIZER
                        ).forEach { screen ->
                            TextButton(
                                onClick = { onNavigate(screen) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(screen.icon, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(screen.titleAr, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ChevronRight, contentDescription = null)
                            }
                        }
                    }

                // Theme selector
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("سمات التطبيق (Themes):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    val themes = listOf(
                        "purple" to "البنفسجي 💜",
                        "dark" to "داكن 🌙",
                        "light" to "فاتح ☀️"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        themes.forEach { (id, label) ->
                            val isSelected = appTheme == id
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                    .clickable { viewModel.appTheme.value = id }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // Audio speed setting
                Column {
                    Text("سرعة نطق الصوت: ${String.format(Locale.US, "%.1f", speed)}x", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Slider(
                        value = speed,
                        onValueChange = { viewModel.speechSpeed.value = it },
                        valueRange = 0.5f..2.0f
                    )
                }

                // Auto read toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("قراءة الردود تلقائياً 🔊", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("نطق ردود الذكاء الاصطناعي فور كتابتها بأعلى جودة ممكنة", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    Switch(
                        checked = autoReadChat,
                        onCheckedChange = { viewModel.autoReadChatEnabled.value = it }
                    )
                }

                // Voice selector
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("صوت المعلق الذكي (Gemini High-Fi):", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    val voices = listOf(
                        "Kore" to "كور (نسائي نقي)",
                        "Aoede" to "أويدي (نسائي ناعم)",
                        "Charon" to "شارون (رجالي دافئ)",
                        "Puck" to "بوك (رجالي حيوي)",
                        "Fenrir" to "فينرير (رجالي معبر)"
                    )
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        voices.forEach { (id, label) ->
                            val isSelected = selectedVoice == id
                            FilterChip(
                                selected = isSelected,
                                onClick = { 
                                    viewModel.selectedVoice.value = id
                                    viewModel.speakText("تم اختيار صوت المعلق بنجاح")
                                },
                                label = { Text(label, fontSize = 10.sp) }
                            )
                        }
                    }
                }

                // Location simulator setting
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("مشاركة الموقع الآمن", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("مشاركة إحداثيات موقعك لحساب مواقيت الصلاة والمطاعم المجاورة", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    Button(onClick = { /* Simulated safe share */ }) {
                        Text("تشير الموقع", fontSize = 11.sp)
                    }
                }

                // Update setting
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("التحديث والترقيات", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("الإصدار الحالي: 1.0.0 Global", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    Button(onClick = { /* check for update */ }) {
                        Text("تحديث", fontSize = 11.sp)
                    }
                }

                // App Info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text("معلومات الاستخدام:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("التطبيق يعمل بنظام حماية البيانات والخصوصية الكامل للأفراد، ومزود بالذكاء الاصطناعي من Google Gemini وVeo.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("تم")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSetupDialog(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    onStartVoice: () -> Unit
) {
    var selectedVoice by remember { mutableStateOf(viewModel.selectedPersonaId.value) }
    val currentLocale by viewModel.selectedSTTLocale.collectAsState()
    var selectedLang by remember { mutableStateOf(currentLocale) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "المحادثة الصوتية الذكية 🎙️",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "اضبط إعدادات المحادثة الصوتية الفورية مع الصديق الذكي لتبدأ الحوار الصوتي المباشر:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 1. Voice selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. اختر معلقك المفضل:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Hasan Option
                        Card(
                            onClick = { selectedVoice = "hasan" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = if (selectedVoice == "hasan") 2.dp else 1.dp,
                                color = if (selectedVoice == "hasan") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedVoice == "hasan") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = if (selectedVoice == "hasan") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.size(32.dp)
                                )
                                Text("حسن (صوت ولد)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("صوت ذكوري مصري عميق", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }

                        // Jana Option
                        Card(
                            onClick = { selectedVoice = "jana" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = if (selectedVoice == "jana") 2.dp else 1.dp,
                                color = if (selectedVoice == "jana") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedVoice == "jana") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Face,
                                    contentDescription = null,
                                    tint = if (selectedVoice == "jana") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.size(32.dp)
                                )
                                Text("ريتاج (صوت بنت)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("صوت أنثوي مصري رقيق", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }

                // 2. Language selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. لغة التحدث المفضلة:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val languages = listOf(
                            Triple("ar-EG", "مصري عامية", "🇪🇬"),
                            Triple("ar-SA", "عربي فصحى", "🇸🇦"),
                            Triple("en-US", "English", "🇺🇸")
                        )
                        languages.forEach { (locale, label, flag) ->
                            val isSelected = selectedLang == locale
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedLang = locale }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(flag, fontSize = 16.sp)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        label,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.selectedPersonaId.value = selectedVoice
                    viewModel.selectedSTTLocale.value = selectedLang
                    onStartVoice()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("ابدأ التحدث بالصوت 🎙️", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("إلغاء", color = MaterialTheme.colorScheme.outline)
            }
        }
    )
}
