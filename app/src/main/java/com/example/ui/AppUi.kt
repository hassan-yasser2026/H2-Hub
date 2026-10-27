package com.example.ui

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.Manifest
import android.app.TimePickerDialog
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.*
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.auth.AuthManager
import com.example.auth.AuthState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
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
import com.example.ExamReminder
import com.example.NotificationScheduler
import com.example.PushNotifications
import com.mikepenz.markdown.m3.Markdown
import java.text.SimpleDateFormat
import java.util.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException
import com.example.network.parseChatVisionMessage
import com.example.network.GeminiApiClient
import com.example.network.GeminiApiClient.FeedbackRating
import com.example.ui.theme.AccentColors
import com.example.ui.theme.withAccent
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

// Main app destinations
enum class AppScreen(val titleAr: String, val icon: ImageVector) {
    CHAT("Smart Cat", Icons.Default.ChatBubble),
    CODER("H2 Code", Icons.Default.Code),
    VOICE_FRIENDS("تحدث مع صديقك", Icons.Default.Mic),
    FRIEND_CHAT("محادثة صديقك", Icons.Default.ChatBubble),
    PERSONA_CHAT("محادثة شخصية", Icons.Default.ChatBubble),
    QURAN("تجويد القرآن", Icons.Default.Mic),
    PRODUCTIVITY("الإنتاجية", Icons.Default.Analytics),
    PERSONAS("شخصيات AI", Icons.Default.People),
    ORGANIZER("المنظم اليومي", Icons.Default.CalendarMonth),
    LEARNING_PROFILE("ملفي التعليمي", Icons.Default.School),
    STUDY_SET("المراجعة", Icons.Default.Quiz),
    NOTIFICATIONS("الإشعارات", Icons.Default.Notifications),
    SUBSCRIPTION("الاشتراك", Icons.Default.Star),
    MORE("الأقسام", Icons.Default.Dashboard)
}

internal fun appScreenAccent(screen: AppScreen, personaId: String? = null): Color =
    when (screen) {
        AppScreen.CHAT -> AccentColors.SmartCat
        AppScreen.QURAN -> AccentColors.Quran
        AppScreen.VOICE_FRIENDS, AppScreen.FRIEND_CHAT -> AccentColors.VoiceFriend
        AppScreen.PERSONAS -> AccentColors.Personas
        AppScreen.PERSONA_CHAT -> personaId?.let { AccentColors.forPersona(it) } ?: AccentColors.SmartCat
        AppScreen.CODER -> AccentColors.Coder
        AppScreen.PRODUCTIVITY -> AccentColors.Productivity
        AppScreen.ORGANIZER -> AccentColors.Organizer
        AppScreen.SUBSCRIPTION -> AccentColors.Subscription
        else -> AccentColors.SmartCat
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppUi(viewModel: AppViewModel) {
    var currentScreen by remember { mutableStateOf(AppScreen.CHAT) }
    var previousScreen by remember { mutableStateOf(AppScreen.CHAT) }
    var selectedVoiceFriendId by rememberSaveable { mutableStateOf("hasan") }
    var selectedPersonaChatId by rememberSaveable { mutableStateOf("teacher") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showQuickNavigationMenu by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf(AppScreen.CHAT) }
    val isKeyboardVisible = WindowInsets.isImeVisible
    val context = LocalContext.current
    val navigationRequest by PushNotifications.navigationRequests.collectAsState()
    val backgroundChatPendingSession by viewModel.backgroundChatPendingSession.collectAsState()

    LaunchedEffect(navigationRequest?.eventId) {
        val request = navigationRequest ?: return@LaunchedEffect
        when (request.target) {
            "chat" -> {
                request.sessionId?.let(viewModel::selectSession) ?: viewModel.ensureActiveChatSession()
                currentScreen = AppScreen.CHAT
            }
            "quran" -> currentScreen = AppScreen.QURAN
            "organizer" -> currentScreen = AppScreen.ORGANIZER
            "learning_profile" -> currentScreen = AppScreen.LEARNING_PROFILE
            else -> currentScreen = AppScreen.CHAT
        }
        request.speakText?.let(viewModel::speakTextLocally)
        PushNotifications.clearNavigationRequest(request.eventId)
    }

    LaunchedEffect(currentScreen, backgroundChatPendingSession) {
        if (currentScreen != AppScreen.CHAT &&
            viewModel.consumeBackgroundChatNotice(backgroundChatPendingSession)
        ) {
            Toast.makeText(context, "هنبعتلك إشعار لما الرد يجي", Toast.LENGTH_SHORT).show()
        }
    }

    // Navigation back handling
    fun navigateBack() {
        currentScreen = when (currentScreen) {
            AppScreen.MORE -> AppScreen.CHAT
            AppScreen.VOICE_FRIENDS -> AppScreen.MORE
            AppScreen.FRIEND_CHAT -> AppScreen.VOICE_FRIENDS
            AppScreen.PERSONAS -> AppScreen.MORE
            AppScreen.PERSONA_CHAT -> AppScreen.PERSONAS
            else -> previousScreen
        }
    }

    if (currentScreen != AppScreen.CHAT) {
        BackHandler(onBack = ::navigateBack)
    }

    val sectionAccent = appScreenAccent(
        currentScreen,
        personaId = when (currentScreen) {
            AppScreen.PERSONA_CHAT -> selectedPersonaChatId
            AppScreen.FRIEND_CHAT -> selectedVoiceFriendId
            else -> null
        }
    )
    val sectionColorScheme = MaterialTheme.colorScheme.withAccent(sectionAccent)

    MaterialTheme(
        colorScheme = sectionColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
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
                    title = {
                        Text(
                            currentScreen.titleAr,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        if (currentScreen != AppScreen.CHAT &&
                            currentScreen != AppScreen.QURAN &&
                            currentScreen != AppScreen.MORE
                        ) {
                            IconButton(onClick = ::navigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showQuickNavigationMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            }
                            DropdownMenu(
                                expanded = showQuickNavigationMenu,
                                onDismissRequest = { showQuickNavigationMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("الإعدادات") },
                                    onClick = {
                                        showQuickNavigationMenu = false
                                        settingsSection = currentScreen
                                        showSettingsDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(AppScreen.LEARNING_PROFILE.titleAr) },
                                    onClick = {
                                        showQuickNavigationMenu = false
                                        previousScreen = currentScreen
                                        currentScreen = AppScreen.LEARNING_PROFILE
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(AppScreen.NOTIFICATIONS.titleAr) },
                                    onClick = {
                                        showQuickNavigationMenu = false
                                        previousScreen = currentScreen
                                        currentScreen = AppScreen.NOTIFICATIONS
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = sectionAccent.copy(alpha = 0.12f),
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = sectionAccent,
                        actionIconContentColor = sectionAccent
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
                val screenContent: @Composable () -> Unit = {
                    when (screen) {
                    AppScreen.CHAT -> ChatScreen(
                        viewModel = viewModel,
                        onOpenSubscription = {
                            previousScreen = currentScreen
                            currentScreen = AppScreen.SUBSCRIPTION
                        },
                        onOpenSettings = {
                            settingsSection = AppScreen.CHAT
                            showSettingsDialog = true
                        },
                        onCreateStudySet = { source, mode ->
                            previousScreen = currentScreen
                            viewModel.generateStudySet(source, mode)
                            currentScreen = AppScreen.STUDY_SET
                        }
                    )
                    AppScreen.CODER -> PersonasScreen(
                        viewModel,
                        coderOnly = true,
                        onOpenSubscription = {
                            previousScreen = currentScreen
                            currentScreen = AppScreen.SUBSCRIPTION
                        },
                        onPersonaSelected = { personaId ->
                            if (personaId == "hasan" || personaId == "jana") {
                                selectedVoiceFriendId = personaId
                                currentScreen = AppScreen.FRIEND_CHAT
                            } else if (personaId != "coder") {
                                selectedPersonaChatId = personaId
                                currentScreen = AppScreen.PERSONA_CHAT
                            }
                            viewModel.setDefaultPersona(personaId)
                        },
                        onOpenSettings = {
                            settingsSection = AppScreen.PERSONAS
                            showSettingsDialog = true
                        }
                    )
                    AppScreen.VOICE_FRIENDS -> VoiceFriendSelectionScreen(
                        onBack = { currentScreen = AppScreen.MORE },
                        onSelectFriend = { personaId ->
                            selectedVoiceFriendId = personaId
                            viewModel.setDefaultPersona(personaId)
                            previousScreen = AppScreen.VOICE_FRIENDS
                            currentScreen = AppScreen.FRIEND_CHAT
                        }
                    )
                    AppScreen.FRIEND_CHAT -> PersonasScreen(
                        viewModel,
                        personaOnlyId = selectedVoiceFriendId,
                        onBack = { currentScreen = AppScreen.VOICE_FRIENDS },
                        onOpenSubscription = {
                            previousScreen = currentScreen
                            currentScreen = AppScreen.SUBSCRIPTION
                        },
                        onPersonaSelected = { personaId ->
                            if (personaId == "hasan" || personaId == "jana") {
                                selectedVoiceFriendId = personaId
                                viewModel.setDefaultPersona(personaId)
                            } else {
                                selectedPersonaChatId = personaId
                                currentScreen = AppScreen.PERSONA_CHAT
                                viewModel.setDefaultPersona(personaId)
                            }
                        },
                        onOpenSettings = {
                            settingsSection = AppScreen.PERSONAS
                            showSettingsDialog = true
                        }
                    )
                    AppScreen.PERSONA_CHAT -> key(selectedPersonaChatId) {
                        PersonasScreen(
                            viewModel,
                            personaOnlyId = selectedPersonaChatId,
                            onBack = { currentScreen = AppScreen.PERSONAS },
                            onOpenSubscription = {
                                previousScreen = currentScreen
                                currentScreen = AppScreen.SUBSCRIPTION
                            },
                            onPersonaSelected = { personaId ->
                                if (personaId == "hasan" || personaId == "jana") {
                                    selectedVoiceFriendId = personaId
                                    currentScreen = AppScreen.FRIEND_CHAT
                                } else {
                                    selectedPersonaChatId = personaId
                                }
                                viewModel.setDefaultPersona(personaId)
                            },
                            onOpenSettings = {
                                settingsSection = AppScreen.PERSONAS
                                showSettingsDialog = true
                            }
                        )
                    }
                    AppScreen.QURAN -> QuranCoachScreen(viewModel)
                    AppScreen.PRODUCTIVITY -> ProductivityScreen(viewModel)
                    AppScreen.PERSONAS -> PersonasGridScreen(
                        onSelectPersona = { personaId ->
                            selectedPersonaChatId = personaId
                            viewModel.setDefaultPersona(personaId)
                            previousScreen = AppScreen.PERSONAS
                            currentScreen = AppScreen.PERSONA_CHAT
                        }
                    )
                    AppScreen.ORGANIZER -> OrganizerScreen(viewModel)
                    AppScreen.LEARNING_PROFILE -> LearningProfileScreen(viewModel)
                    AppScreen.STUDY_SET -> StudySetScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = previousScreen }
                    )
                    AppScreen.NOTIFICATIONS -> NotificationsSettingsScreen(viewModel)
                    AppScreen.SUBSCRIPTION -> SubscriptionScreen(AccentColors.Subscription)
                    AppScreen.MORE -> MoreScreen(
                        onNavigate = {
                            previousScreen = AppScreen.MORE
                            currentScreen = it
                        }
                    )
                    }
                }
                val isChatDestination = screen in setOf(
                    AppScreen.CHAT,
                    AppScreen.CODER,
                    AppScreen.PERSONA_CHAT,
                    AppScreen.FRIEND_CHAT
                )
                Column(Modifier.fillMaxSize()) {
                    if (isChatDestination && BuildConfig.ADS_ENABLED) {
                        BannerAdView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        if (screen == AppScreen.CHAT) {
                            screenContent()
                        } else {
                            SmartCatScreenStyle(content = screenContent)
                        }
                    }
                }
            }

            if (showSettingsDialog) {
                SmartCatScreenStyle {
                    SettingsDialog(
                        viewModel = viewModel,
                        section = settingsSection,
                        onDismiss = { showSettingsDialog = false }
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun MoreScreen(onNavigate: (AppScreen) -> Unit) {
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600
    val isShortScreen = configuration.screenHeightDp < 600
    val cardHeight = when {
        isShortScreen -> 96.dp
        isWideScreen -> 128.dp
        else -> 112.dp
    }
    val iconSize = if (isWideScreen) 52.dp else 44.dp
    val iconGlyphSize = if (isWideScreen) 30.dp else 27.dp
    val gridState = rememberLazyGridState()
    LaunchedEffect(Unit) {
        gridState.scrollToItem(0)
    }
    val destinations = listOf(
        AppScreen.QURAN,
        AppScreen.CHAT,
        AppScreen.PERSONAS,
        AppScreen.VOICE_FRIENDS,
        AppScreen.PRODUCTIVITY,
        AppScreen.ORGANIZER,
        AppScreen.CODER,
        AppScreen.SUBSCRIPTION
    )

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (isWideScreen) 190.dp else 148.dp),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HubSectionHeading(
                title = "أقسام التطبيق",
                description = "كل أداة في قسمها — افتح القسم المناسب لمهمتك."
            )
        }
        gridItems(destinations, key = { it.name }) { destination ->
            val accentColor = appScreenAccent(destination)
            HubCard(
                onClick = { onNavigate(destination) },
                modifier = Modifier.fillMaxWidth().height(cardHeight),
                accentColor = accentColor
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.size(iconSize)
                            .background(accentColor.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(destination.icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(iconGlyphSize))
                    }
                    Spacer(Modifier.height(6.dp))
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
    }
}

@Composable
private fun PersonasGridScreen(onSelectPersona: (String) -> Unit) {
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600
    val isShortScreen = configuration.screenHeightDp < 600
    val avatarSize = when {
        isShortScreen -> 44.dp
        isWideScreen -> 64.dp
        else -> 54.dp
    }
    val cardMinHeight = when {
        isShortScreen -> 104.dp
        isWideScreen -> 148.dp
        else -> 132.dp
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(Unit) {
        gridState.scrollToItem(0)
    }
    val personas = listOf(
        Persona("business", "أ. سمير", "مساعد التثقيف في الأعمال", Icons.Default.BusinessCenter, AccentColors.forPersona("business")),
        Persona("doctor", "د. خالد", "مساعدة التوعية الصحية", Icons.Default.LocalHospital, AccentColors.forPersona("doctor")),
        Persona("teacher", "أ. أحمد", "معلم ومبسط العلوم", Icons.Default.School, AccentColors.forPersona("teacher")),
        Persona("nanny", "المربية أسماء", "التوعية بالتربية الأسرية", Icons.Default.BabyChangingStation, AccentColors.forPersona("nanny")),
        Persona("chef", "الشيف مراد", "وصفات الطهي والحلويات", Icons.Default.Restaurant, AccentColors.forPersona("chef")),
        Persona("designer", "المصممة آية", "واجهات التصميم الجرافيكي", Icons.Default.Palette, AccentColors.forPersona("designer")),
        Persona("coach", "الكابتن فهد", "مساعد اللياقة البدنية", Icons.Default.FitnessCenter, AccentColors.forPersona("coach")),
        Persona("sheikh", "الشيخ عبد الرحمن", "مساعد المعرفة الإسلامية", Icons.Default.SelfImprovement, AccentColors.forPersona("sheikh")),
        Persona("legal", "المستشار عادل", "مساعد التثقيف القانوني", Icons.Default.Gavel, AccentColors.forPersona("legal"))
    )

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (isWideScreen) 190.dp else 145.dp),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HubSectionHeading(
                title = "شخصيات AI",
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        gridItems(personas, key = { it.id }) { persona ->
            HubCard(
                onClick = { onSelectPersona(persona.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = cardMinHeight),
                accentColor = persona.color
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.size(avatarSize).clip(CircleShape)
                            .background(persona.color.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            persona.icon,
                            contentDescription = null,
                            tint = persona.color,
                            modifier = Modifier.size(if (isWideScreen) 32.dp else 28.dp)
                        )
                    }
                    Spacer(Modifier.height(if (isShortScreen) 6.dp else 10.dp))
                    Text(
                        persona.name,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceFriendSelectionScreen(
    onBack: () -> Unit,
    onSelectFriend: (String) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600
    val isShortScreen = configuration.screenHeightDp < 600
    val friendIconSize = when {
        isShortScreen -> 60.dp
        isWideScreen -> 96.dp
        else -> 82.dp
    }
    val friends = listOf(
        Persona(
            id = "hasan",
            name = "حسن صديق",
            role = "صديقك الذكي",
            icon = Icons.Default.RecordVoiceOver,
            color = AccentColors.VoiceFriend
        ),
        Persona(
            id = "jana",
            name = "ريتاج صديقة",
            role = "صديقتك الذكية",
            icon = Icons.Default.Face,
            color = AccentColors.VoiceFriend
        )
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع للأقسام")
            }
            HubSectionHeading(
                title = "اختار صديقك",
                description = "اختار الصديق اللي تحب تتكلم معاه.",
                modifier = Modifier.weight(1f)
            )
        }
        friends.forEach { friend ->
            HubCard(
                onClick = { onSelectFriend(friend.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = if (isShortScreen) 120.dp else 152.dp),
                accentColor = friend.color
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(if (isWideScreen) 28.dp else 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Box(
                        modifier = Modifier.size(friendIconSize).clip(CircleShape)
                            .background(friend.color.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = friend.icon,
                            contentDescription = null,
                            tint = friend.color,
                            modifier = Modifier.size(if (isWideScreen) 60.dp else 52.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            friend.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = friend.color
                        )
                        Text(friend.role, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsSettingsScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val reminderEnabled by viewModel.quranReminderEnabled.collectAsState()
    val reminderHour by viewModel.quranReminderHour.collectAsState()
    val reminderMinute by viewModel.quranReminderMinute.collectAsState()
    var chatEnabled by remember {
        mutableStateOf(NotificationScheduler.isEnabled(context, NotificationScheduler.KEY_CHAT_ENABLED, true))
    }
    var studyEnabled by remember {
        mutableStateOf(NotificationScheduler.isEnabled(context, NotificationScheduler.KEY_STUDY_ENABLED, false))
    }
    var quizEnabled by remember {
        mutableStateOf(NotificationScheduler.isEnabled(context, NotificationScheduler.KEY_QUIZ_ENABLED, true))
    }
    var updatesEnabled by remember {
        mutableStateOf(NotificationScheduler.isEnabled(context, NotificationScheduler.KEY_UPDATES_ENABLED, false))
    }
    val (initialStudyHour, initialStudyMinute) = remember { NotificationScheduler.studyReminderTime(context) }
    var studyHour by remember { mutableIntStateOf(initialStudyHour) }
    var studyMinute by remember { mutableIntStateOf(initialStudyMinute) }
    var examTitle by remember { mutableStateOf("") }
    val exams = remember {
        androidx.compose.runtime.mutableStateListOf<ExamReminder>().apply {
            addAll(NotificationScheduler.readExams(context))
        }
    }
    val reminderPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.setQuranReminder(reminderHour, reminderMinute, true)
        else Toast.makeText(context, "اسمح بالإشعارات لتفعيل تذكير القرآن.", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "إعدادات الإشعارات",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        NotificationSettingRow(
            "إشعارات الشات",
            "إشعار عند اكتمال رد Smart Cat في الخلفية.",
            chatEnabled
        ) {
            chatEnabled = it
            viewModel.setNotificationEnabled(NotificationScheduler.KEY_CHAT_ENABLED, it)
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("تذكيرات القرآن", fontWeight = FontWeight.Bold)
                        Text("يومياً في %02d:%02d".format(Locale.getDefault(), reminderHour, reminderMinute))
                    }
                    Switch(
                        checked = reminderEnabled,
                        onCheckedChange = { enabled ->
                            if (
                                enabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                reminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setQuranReminder(reminderHour, reminderMinute, enabled)
                            }
                        }
                    )
                }
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> viewModel.setQuranReminderTime(hour, minute) },
                            reminderHour,
                            reminderMinute,
                            true
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth().smartCatActionHeight()
                ) { Text("تغيير موعد القرآن") }
                TextButton(onClick = viewModel::scheduleQuranReminderTest) {
                    Text("اختبار تذكير القرآن بعد دقيقتين")
                }
            }
        }
        NotificationSettingRow(
            "تذكيرات الدراسة",
            "تنبيه قبل الاختبار بيوم وصباح يوم الاختبار.",
            studyEnabled
        ) {
            studyEnabled = it
            viewModel.setNotificationEnabled(NotificationScheduler.KEY_STUDY_ENABLED, it)
        }
        OutlinedButton(
            enabled = studyEnabled,
            onClick = {
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        studyHour = hour
                        studyMinute = minute
                        viewModel.setStudyReminderTime(hour, minute)
                    },
                    studyHour,
                    studyMinute,
                    true
                ).show()
            },
            modifier = Modifier.fillMaxWidth().smartCatActionHeight()
        ) {
            Text("وقت تذكير الاختبار: %02d:%02d".format(Locale.getDefault(), studyHour, studyMinute))
        }
        OutlinedTextField(
            value = examTitle,
            onValueChange = { examTitle = it.take(80) },
            label = { Text("اسم الاختبار") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            onClick = {
                val now = Calendar.getInstance()
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        val examCalendar = Calendar.getInstance().apply {
                            set(year, month, day, 8, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                examCalendar.set(Calendar.HOUR_OF_DAY, hour)
                                examCalendar.set(Calendar.MINUTE, minute)
                                val exam = ExamReminder(
                                    id = java.util.UUID.randomUUID().toString(),
                                    title = examTitle.trim(),
                                    examAt = examCalendar.timeInMillis
                                )
                                NotificationScheduler.saveExam(context, exam)
                                exams.add(exam)
                                examTitle = ""
                            },
                            8,
                            0,
                            true
                        ).show()
                    },
                    now.get(Calendar.YEAR),
                    now.get(Calendar.MONTH),
                    now.get(Calendar.DAY_OF_MONTH)
                ).apply { datePicker.minDate = System.currentTimeMillis() }.show()
            },
            enabled = studyEnabled && examTitle.isNotBlank(),
            modifier = Modifier.fillMaxWidth().smartCatActionHeight()
        ) { Text("إضافة موعد اختبار") }
        exams.forEach { exam ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(exam.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(exam.examAt)),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = {
                        NotificationScheduler.removeExam(context, exam.id)
                        exams.remove(exam)
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف الاختبار")
                    }
                }
            }
        }
        NotificationSettingRow(
            "نتيجة الاختبار جاهزة",
            "إشعار عند اكتمال التحليل المتقدم.",
            quizEnabled
        ) {
            quizEnabled = it
            viewModel.setNotificationEnabled(NotificationScheduler.KEY_QUIZ_ENABLED, it)
        }
        NotificationSettingRow(
            "تحديثات التطبيق",
            "إشعار اختياري عند توفر تحديث جديد.",
            updatesEnabled
        ) {
            updatesEnabled = it
            viewModel.setNotificationEnabled(NotificationScheduler.KEY_UPDATES_ENABLED, it)
        }
    }
}

@Composable
private fun NotificationSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    val profile by viewModel.learningUserProfile.collectAsState()
    val quizResults by viewModel.learningQuizResults.collectAsState()
    val cloudMemory by viewModel.conversationMemory.collectAsState()
    val cloudMemoryError by viewModel.conversationMemoryError.collectAsState()
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
                "تُحفظ عناوين المحادثات ورسائلها في Firestore مرتبطة بحساب Google، وتُزامن بين أجهزتك. " +
                    "تُمسح نسخة المحادثات المحلية عند تسجيل الخروج أو تبديل الحساب. تُرسل رسائل المحادثة " +
                    "ومرفقات الصور إلى خدمة الذكاء الاصطناعي للإجابة؛ صورة التحليل نفسها لا تُزامن إلى أجهزتك الأخرى. " +
                    "تُحفظ ملخصات المحادثات ونقاط التعلّم في Firestore لحسابك وتُستخدم لتخصيص الإجابات. " +
                    "التقييمات التي ترسلها تُحفظ على الخادم لتحسين الجودة.",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (cloudMemoryError != null) {
            Text(
                cloudMemoryError.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("مستوى التعلّم", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (profile?.level == "adaptive" || profile == null) {
                        "يتكيف تلقائياً حسب تكرار الأسئلة ونقاط المراجعة."
                    } else {
                        profile?.level.orEmpty()
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("المحادثات المتذكّرة", style = MaterialTheme.typography.titleMedium)
                Text(
                    cloudMemory.summaryCount.toString(),
                    style = MaterialTheme.typography.headlineMedium,
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
                val allWeakTopics = (weakTopics.map { "${it.subject}: ${it.topic}" } +
                    cloudMemory.weakPoints).distinct()
                if (allWeakTopics.isEmpty()) {
                    Text("لا توجد نقاط ضعف متكررة بعد.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    allWeakTopics.forEach { topic -> Text("• $topic") }
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
        if (cloudMemory.longTermSummary.isNotBlank()) {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("ملخص تعلّمك طويل المدى", style = MaterialTheme.typography.titleMedium)
                    Text(cloudMemory.longTermSummary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val recentSummaries = cloudMemory.recentSummaries
        if (recentSummaries.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("ملخصات المحادثات الأخيرة", style = MaterialTheme.typography.titleMedium)
                    recentSummaries.forEach { summary ->
                        Text(summary.subject, fontWeight = FontWeight.SemiBold)
                        Text(summary.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        val recentLocalSummaries = conversations.filter { !it.summary.isNullOrBlank() }.take(3)
        if (recentLocalSummaries.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("ملخصات التعلّم على هذا الجهاز", style = MaterialTheme.typography.titleMedium)
                    recentLocalSummaries.forEach { conversation ->
                        Text(conversation.title, fontWeight = FontWeight.SemiBold)
                        Text(conversation.summary.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("اختباراتي", style = MaterialTheme.typography.titleMedium)
                if (quizResults.isEmpty()) {
                    Text(
                        "نتائج الاختبارات التي تكملها ستظهر هنا.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    quizResults.take(20).forEach { result ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(result.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                        .format(Date(result.completedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "${result.score}/${result.totalQuestions}",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== SMART CHAT (SMART CAT) SCREEN ====================

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun ChatScreen(
    viewModel: AppViewModel,
    onOpenSettings: () -> Unit = {},
    onCreateStudySet: (String, StudySetMode) -> Unit = { _, _ -> },
    onOpenSubscription: () -> Unit = {}
) {
    val messages by viewModel.currentMessages.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val sessions by viewModel.chatSessions.collectAsState()
    val isGenerating by viewModel.isGeneratingChat.collectAsState()
    val thinkingState by viewModel.thinkingState.collectAsState()
    val chatActionError by viewModel.chatActionError.collectAsState()
    val quotaExceeded by viewModel.quotaExceeded.collectAsState()
    val usageState by viewModel.usageState.collectAsState()
    val conversationSyncStatus by viewModel.conversationSyncStatus.collectAsState()
    val socraticModeEnabled by viewModel.socraticModeEnabled.collectAsState()
    val socraticProgress by viewModel.socraticProgress.collectAsState()
    val thinkingModeEnabled by viewModel.useThinkingMode.collectAsState()
    val isListeningToSpeech by viewModel.isListeningToSpeech.collectAsState()
    val isProcessingSpeech by viewModel.isProcessingSpeech.collectAsState()
    val speechAmplitude by viewModel.speechAmplitude.collectAsState()
    val speechRecognitionError by viewModel.speechRecognitionError.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val activeSpeechId by viewModel.activeSpeechId.collectAsState()
    val speechInputText by viewModel.speechInputText.collectAsState()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsState()
    val groqThinkingEnabled by viewModel.groqThinkingEnabled.collectAsState()
    val imageGenerationEnabled by viewModel.imageGenerationEnabled.collectAsState()
    val imageGenerationAvailable by viewModel.imageGenerationAvailable.collectAsState()
    val chatRemaining = usageState.snapshot?.let { snapshot ->
        if (snapshot.legacy) Int.MAX_VALUE
        else snapshot.subscription?.remaining ?: snapshot.feature("chat")?.remaining
    }
    val chatExhausted = usageBanner(usageState, "chat")?.exhausted == true
    val canGenerateImages = imageGenerationAvailable &&
        (usageState.snapshot?.let { it.legacy || it.subscription?.features?.get("images") == true } == true) &&
        (chatRemaining ?: 0) >= 20

    var inputText by rememberSaveable { mutableStateOf("") }
    var pendingVisionImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val chatRequiredQuota = requiredMessageQuota(
        webSearch = webSearchEnabled,
        thinking = groqThinkingEnabled,
        vision = pendingVisionImagePath != null,
        imageGeneration = imageGenerationEnabled
    )
    val chatHasSufficientQuota = chatRemaining == null || chatRemaining >= chatRequiredQuota
    var showComposerSheet by remember { mutableStateOf(false) }
    var showComposerPlugins by remember { mutableStateOf(false) }
    var showChatHistory by remember { mutableStateOf(false) }
    var showVoiceSetupDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val context = LocalContext.current
    val chatScope = rememberCoroutineScope()
    val composerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    DisposableEffect(Unit) {
        onDispose {
            activeSpeechId?.let(viewModel::stopSpeakingIfActive)
            viewModel.closeCurrentChatSession()
        }
    }
    fun prepareVisionImage(uri: Uri) {
        if (chatRemaining != null && chatRemaining < 3) {
            Toast.makeText(context, "الرصيد لا يكفي لتحليل الصورة؛ تحتاج إلى 3 رسائل على الأقل.", Toast.LENGTH_SHORT).show()
            return
        }
        chatScope.launch {
            try {
                val imageFile = withContext(Dispatchers.IO) {
                    compressChatImage(context, uri)
                }
                pendingVisionImagePath?.let { deleteChatVisionImage(context, it) }
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
        contract = ActivityResultContracts.PickVisualMedia()
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

    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) chatScope.launch {
            try {
                val mimeType = context.contentResolver.getType(uri).orEmpty()
                if (mimeType == "application/pdf") {
                    val encodedPdf = encodePdfAttachment(context, uri)
                    viewModel.sendChatAttachment(encodedPdf, mimeType)
                } else if (
                    mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                    mimeType == "application/msword"
                ) {
                    val fileName = context.contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(0)?.takeIf(String::isNotBlank)
                        } else null
                    } ?: "document.docx"
                    val documentText = GeminiApiClient.extractProductivityDocument(
                        context,
                        uri,
                        fileName
                    )
                    viewModel.sendChatMessage(
                        "حلل محتوى ملف $fileName وأجب عن أي سؤال متعلق به:\n\n${documentText.take(7_000)}"
                    )
                } else {
                    Toast.makeText(context, "اختر ملف PDF أو Word بصيغة DOCX أو DOC.", Toast.LENGTH_LONG).show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "Failed to attach document", e)
                Toast.makeText(
                    context,
                    e.localizedMessage ?: "تعذر إرفاق الملف.",
                    Toast.LENGTH_LONG
                ).show()
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
        viewModel.refreshImageGenerationAvailability()
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

    val haptics = LocalHapticFeedback.current
    var wasGenerating by remember { mutableStateOf(isGenerating) }
    LaunchedEffect(isGenerating) {
        if (wasGenerating && !isGenerating && messages.lastOrNull()?.role == "model") {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        wasGenerating = isGenerating
    }

    val sendCurrentMessage = {
        if (chatExhausted) {
            Toast.makeText(context, "استهلكت كل رسائلك. جدّد اشتراكك أو ارتقِ لخطة أعلى.", Toast.LENGTH_SHORT).show()
        } else if (!chatHasSufficientQuota) {
            Toast.makeText(context, "الرصيد لا يكفي لهذه العملية. اختر ميزة أقل تكلفة أو رقِّ اشتراكك.", Toast.LENGTH_SHORT).show()
        } else {
            val message = inputText.trim()
            val imagePath = pendingVisionImagePath
            if (imagePath != null || message.isNotEmpty()) playSendSound()
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
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val parentDirection = LocalLayoutDirection.current
    val chatSessionsOnly = remember(sessions) { sessions.filter { it.personaId == null } }
    val currentSession = chatSessionsOnly.firstOrNull { it.id == currentSessionId }
    var showTitleMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    CompositionLocalProvider(LocalLayoutDirection provides parentDirection) {
                        ChatSidebar(
                            sessions = chatSessionsOnly,
                            currentSessionId = currentSessionId,
                            onNewChat = {
                                viewModel.startNewSession("محادثة جديدة")
                                chatScope.launch { drawerState.close() }
                            },
                            onSelect = {
                                viewModel.selectSession(it)
                                chatScope.launch { drawerState.close() }
                            },
                            onRename = viewModel::renameSession,
                            onDelete = viewModel::deleteSession,
                            onOpenSettings = {
                                chatScope.launch { drawerState.close() }
                                onOpenSettings()
                            },
                            syncIssue = conversationSyncStatus.error
                        )
                    }
                }
            }
        ) {
        CompositionLocalProvider(LocalLayoutDirection provides parentDirection) {
    Column(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { chatScope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, contentDescription = "قائمة المحادثات")
                }
                CompositionLocalProvider(LocalLayoutDirection provides parentDirection) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Smart Cat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                UsageIndicatorCompact(
                    state = usageState,
                    onRefresh = viewModel::refreshUsage,
                    onUpgrade = onOpenSubscription,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                Box {
                    IconButton(
                        onClick = { showTitleMenu = true },
                        enabled = currentSession != null
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "خيارات المحادثة")
                    }
                    DropdownMenu(expanded = showTitleMenu, onDismissRequest = { showTitleMenu = false }) {
                        CompositionLocalProvider(LocalLayoutDirection provides parentDirection) {
                            DropdownMenuItem(
                                text = { Text("تغيير الاسم") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = { showTitleMenu = false; showRenameDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("مشاركة") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    showTitleMenu = false
                                    val transcript = messages.joinToString("\n\n") { m ->
                                        val who = if (m.role == "user") "أنا" else "Smart Cat"
                                        val body = parseChatVisionMessage(m.content)?.question ?: m.content
                                        "$who: $body"
                                    }
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, transcript)
                                    }
                                    context.startActivity(Intent.createChooser(send, "مشاركة المحادثة"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("حذف", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = { showTitleMenu = false; showDeleteDialog = true }
                            )
                        }
                    }
                }
            }
        }
        if (usageBanner(usageState, "chat")?.level?.let { it != UsageLevel.NORMAL } == true) {
            UsageBannerCard(
                state = usageState,
                onRefresh = viewModel::refreshUsage,
                feature = "chat",
                onUpgrade = onOpenSubscription,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        QuotaExceededDialog(
            error = quotaExceeded,
            onDismiss = viewModel::clearQuotaExceeded,
            onSubscribe = {
                viewModel.clearQuotaExceeded()
                onOpenSubscription()
            }
        )
        if (showRenameDialog && currentSession != null) {
            var newTitle by remember { mutableStateOf(currentSession.title) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("تغيير اسم المحادثة") },
                text = {
                    OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, singleLine = true)
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.renameSession(currentSession.id, newTitle)
                        showRenameDialog = false
                    }) { Text("حفظ") }
                },
                dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("إلغاء") } }
            )
        }
        if (showDeleteDialog && currentSession != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("حذف المحادثة؟") },
                text = { Text("سيتم حذف هذه المحادثة نهائياً.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteSession(currentSession.id)
                        showDeleteDialog = false
                    }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("إلغاء") } }
            )
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
                        "اكتب ما تحتاجه: إجابة، كتابة، ترجمة، تلخيص، تخطيط، أفكار، برمجة، أو مساعدة دراسية عند طلبك.",
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
                            onSpeakClick = { viewModel.speakText(msg.content, msg.id) },
                            onSpeakImageClick = {
                                msg.generatedImageUrl?.let(viewModel::speakGeneratedImage)
                            },
                            isSpeaking = isSpeaking,
                            isActiveSpeech = activeSpeechId == msg.id,
                            feedbackQuestion = precedingUserMessage?.let { userMessage ->
                                parseChatVisionMessage(userMessage.content)?.question
                                    ?: userMessage.content
                            },
                            studySource = precedingUserMessage?.let { userMessage ->
                                val question = parseChatVisionMessage(userMessage.content)?.question
                                    ?: userMessage.content
                                "سؤال الطالب:\n$question\n\nإجابة Smart Cat:\n${msg.content}"
                            },
                            onCreateStudySet = onCreateStudySet,
                            onRegenerate = if (
                                index == messages.lastIndex && msg.role == "model" &&
                                precedingUserMessage != null && !canRetryVision && !isGenerating
                            ) {
                                { viewModel.sendChatMessage(precedingUserMessage.content) }
                            } else null,
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
                            TypingIndicator(thinkingState)
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
        speechRecognitionError?.let { errorMessage ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = viewModel::clearSpeechRecognitionError) {
                    Text("إغلاق")
                }
            }
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
                        contentDescription = "الصورة المرفقة",
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Text(
                    "اكتب ما تريد معرفته عن الصورة أو اضغط إرسال لتحليلها",
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                IconButton(onClick = {
                    pendingVisionImagePath?.let { deleteChatVisionImage(context, it) }
                    pendingVisionImagePath = null
                }) {
                    Icon(Icons.Default.Close, contentDescription = "إزالة الصورة")
                }
            }
        }

        // Keep the message field above the compact action row, like the reference composer.
        val hasInput = inputText.isNotBlank() || pendingVisionImagePath != null
        val parentDirection = LocalLayoutDirection.current
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Ltr,
            LocalMinimumInteractiveComponentSize provides 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .padding(bottom = 8.dp)
                    .shadow(2.dp, RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .heightIn(min = 92.dp, max = 190.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides parentDirection) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                if (isListeningToSpeech) "جاري الاستماع لصوتك..." else "اكتب رسالة",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 120.dp)
                            .testTag("chat_input"),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendCurrentMessage() }),
                        maxLines = 4
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        if (hasInput) {
                            IconButton(
                                onClick = sendCurrentMessage,
                                enabled = !isGenerating && chatHasSufficientQuota,
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        if (!chatHasSufficientQuota) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.primary,
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "إرسال",
                                    tint = if (!chatHasSufficientQuota) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (isListeningToSpeech) {
                                        viewModel.stopSpeechRecognition()
                                    } else {
                                        showVoiceSetupDialog = true
                                    }
                                },
                                enabled = !isProcessingSpeech,
                                modifier = Modifier
                                    .size(40.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            ) {
                                if (isProcessingSpeech) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isListeningToSpeech) Icons.Default.MicNone else Icons.Default.Mic,
                                        contentDescription = if (isListeningToSpeech) {
                                            "إيقاف التسجيل الصوتي"
                                        } else {
                                            "الإدخال الصوتي"
                                        },
                                        tint = if (isListeningToSpeech) Color.Red
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            showComposerPlugins = false
                            showComposerSheet = true
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        enabled = !isGenerating
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "فتح أدوات المحادثة",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (isListeningToSpeech) {
                        SpeechWaveform(
                            amplitude = speechAmplitude,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatToolChip(
                            label = "بحث",
                            icon = Icons.Default.Language,
                            selected = webSearchEnabled,
                            enabled = !chatExhausted && (
                                webSearchEnabled || (chatRemaining ?: 0) >= if (groqThinkingEnabled) 3 else 2
                            ),
                            onClick = { viewModel.setWebSearchEnabled(!webSearchEnabled) }
                        )
                        ChatToolChip(
                            label = "تفكير",
                            icon = Icons.Default.Psychology,
                            selected = groqThinkingEnabled,
                            enabled = !chatExhausted && (
                                groqThinkingEnabled || (chatRemaining ?: 0) >= if (webSearchEnabled) 3 else 2
                            ),
                            onClick = { viewModel.setGroqThinkingEnabled(!groqThinkingEnabled) }
                        )
                        ChatToolChip(
                            label = "ارسم",
                            icon = Icons.Default.Brush,
                            selected = imageGenerationAvailable && imageGenerationEnabled,
                            available = canGenerateImages || imageGenerationEnabled,
                            enabled = canGenerateImages || imageGenerationEnabled,
                            onClick = {
                                if (canGenerateImages || imageGenerationEnabled) {
                                    viewModel.setImageGenerationEnabled(!imageGenerationEnabled)
                                } else if ((chatRemaining ?: 0) < 20) {
                                    Toast.makeText(context, "تحتاج إلى 20 رسالة متبقية لتوليد صورة.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "🎨 نعمل على تحسين جودة الصور. هترجع أقوى من الأول!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }
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
                            subtitle = "صوّر شيئاً تريد تحليله",
                            onClick = { closeComposerSheet(::openCameraCapture) }
                        )
                        ChatComposerSheetAction(
                            icon = Icons.Default.Image,
                            title = "الصور",
                            subtitle = "اختر صورة من المعرض",
                            onClick = {
                                closeComposerSheet {                                 visionGalleryLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                ) }
                            }
                        )
                        ChatComposerSheetAction(
                            icon = Icons.Default.AttachFile,
                            title = "الملفات",
                            subtitle = "إرفاق ملف PDF أو Word",
                            onClick = {
                                closeComposerSheet {
                                    documentLauncher.launch(
                                        arrayOf(
                                            "application/pdf",
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                            "application/msword"
                                        )
                                    )
                                }
                            }
                        )
                        ChatComposerSheetAction(
                            icon = Icons.Default.Close,
                            title = "إلغاء",
                            subtitle = "إغلاق قائمة المرفقات",
                            onClick = { closeComposerSheet {} }
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
                                viewModel.setThinkingModeEnabled(enabled)
                                closeComposerSheet {}
                            }
                        )
                    }
                }
            }
        }

        if (showVoiceSetupDialog) {
            VoiceSetupDialog(
                viewModel = viewModel,
                includeVoiceSelection = false,
                onDismiss = { showVoiceSetupDialog = false },
                onStartVoice = {
                    showVoiceSetupDialog = false
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
            )
        }
    }
        }
    }
    }
}

@Composable
private fun ChatToolChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    available: Boolean = true,
    enabled: Boolean = true,
    accentColor: Color? = null,
    onClick: () -> Unit
) {
    val primary = accentColor ?: MaterialTheme.colorScheme.primary
    val container = when {
        !available || !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        selected -> primary.copy(alpha = 0.2f)
        else -> Color.Transparent
    }
    val content = when {
        !available || !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        selected -> primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(
            1.dp,
            when {
                !available || !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                selected -> primary
                else -> MaterialTheme.colorScheme.outline
            }
        ),
        enabled = enabled,
        modifier = Modifier.height(28.dp).semantics { this.selected = selected }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(label, fontSize = 10.sp, maxLines = 1)
        }
    }
}
private fun playSendSound() {
    try {
        val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_SYSTEM, 40)
        tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 60)
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 200)
    } catch (_: Exception) {
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatSidebar(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit,
    personaItems: List<Persona>? = null,
    selectedPersonaId: String? = null,
    onSelectPersona: ((String) -> Unit)? = null,
    syncIssue: String? = null
) {
    val authState by AuthManager.state.collectAsState()
    val user = (authState as? AuthState.SignedIn)?.user
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<ChatSession?>(null) }
    var renameFor by remember { mutableStateOf<ChatSession?>(null) }
    var deleteFor by remember { mutableStateOf<ChatSession?>(null) }
    val filtered = remember(sessions, query) {
        sessions.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
    }
    val groupedSessions = remember(filtered) { groupSessionsByTime(filtered) }
    Column(modifier = Modifier.fillMaxHeight().widthIn(max = 320.dp).padding(12.dp)) {
        if (personaItems == null) {
            Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("محادثة جديدة")
            }
        } else {
            Text(
                "الشخصيات",
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("محادثة جديدة")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text(if (personaItems == null) "بحث في المحادثات" else "بحث في الشخصيات والمحادثات")
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        if (syncIssue != null) {
            Text(
                syncIssue,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (personaItems != null) {
                val visiblePersonas = personaItems.filter {
                    query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)
                }
                if (visiblePersonas.isEmpty()) {
                    item { Text("لا توجد شخصيات مطابقة.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                }
                items(visiblePersonas, key = { it.id }) { persona ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (persona.id == selectedPersonaId) persona.color.copy(alpha = 0.16f)
                                else Color.Transparent
                            )
                            .clickable { onSelectPersona?.invoke(persona.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(persona.icon, contentDescription = null, tint = persona.color)
                        Text(
                            persona.name,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (persona.id == selectedPersonaId) {
                            Icon(Icons.Default.Check, contentDescription = "الشخصية الحالية", tint = persona.color)
                        }
                    }
                }
                item {
                    Text(
                        "المحادثات المحفوظة",
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (groupedSessions.isEmpty()) {
                    item {
                        Text(
                            "لا توجد محادثات محفوظة لهذا المساعد.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                groupedSessions.forEach { (groupTitle, groupSessions) ->
                    item(key = "persona-session-group-$groupTitle") {
                        Text(
                            text = groupTitle,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(groupSessions, key = { it.id }) { session ->
                        ChatSidebarSessionItem(
                            session = session,
                            isSelected = session.id == currentSessionId,
                            onSelect = { onSelect(session.id) },
                            onRename = { menuFor = null; renameFor = session },
                            onDelete = { menuFor = null; deleteFor = session },
                            menuExpanded = menuFor?.id == session.id,
                            onOpenMenu = { menuFor = session },
                            onDismissMenu = { menuFor = null }
                        )
                    }
                }
            } else {
                if (groupedSessions.isEmpty()) {
                    item { Text("لا توجد محادثات.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                }
                groupedSessions.forEach { (groupTitle, groupSessions) ->
                    item(key = "chat-session-group-$groupTitle") {
                        Text(
                            text = groupTitle,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(groupSessions, key = { it.id }) { session ->
                        ChatSidebarSessionItem(
                            session = session,
                            isSelected = session.id == currentSessionId,
                            onSelect = { onSelect(session.id) },
                            onRename = { menuFor = null; renameFor = session },
                            onDelete = { menuFor = null; deleteFor = session },
                            menuExpanded = menuFor?.id == session.id,
                            onOpenMenu = { menuFor = session },
                            onDismissMenu = { menuFor = null }
                        )
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (user?.photoUrl != null) {
                AsyncImage(
                    model = user.photoUrl,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                )
            } else {
                Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(36.dp))
            }
            Text(
                user?.displayName ?: "مستخدم H2 Hub",
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "الإعدادات")
            }
        }
    }
    renameFor?.let { session ->
        var title by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("تغيير اسم المحادثة") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRename(session.id, title); renameFor = null }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("إلغاء") } }
        )
    }
    deleteFor?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("حذف المحادثة؟") },
            text = { Text("سيتم حذف \"${session.title}\" نهائياً.") },
            confirmButton = {
                TextButton(onClick = { onDelete(session.id); deleteFor = null }) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun ChatSidebarSessionItem(
    session: ChatSession,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    menuExpanded: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit
) {
    Box(modifier = Modifier.padding(bottom = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else Color.Transparent
                )
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = onOpenMenu
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "حذف المحادثة",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(text = { Text("تغيير الاسم") }, onClick = onRename)
            DropdownMenuItem(text = { Text("حذف") }, onClick = onDelete)
        }
    }
}

@Composable
private fun SpeechWaveform(amplitude: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.width(36.dp).height(28.dp)) {
        val barCount = 5
        val barWidth = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val totalWidth = barCount * barWidth + (barCount - 1) * gap
        val startX = (size.width - totalWidth) / 2f
        val midY = size.height / 2f
        repeat(barCount) { index ->
            val centerBias = 1f - kotlin.math.abs(index - (barCount - 1) / 2f) / 3f
            val minHeight = 4.dp.toPx()
            val maxHeight = (6.dp.toPx() + size.height * amplitude * centerBias)
                .coerceIn(minHeight, size.height)
            drawLine(
                color = Color(0xFFE45757),
                start = androidx.compose.ui.geometry.Offset(
                    x = startX + index * (barWidth + gap) + barWidth / 2f,
                    y = midY - maxHeight / 2f
                ),
                end = androidx.compose.ui.geometry.Offset(
                    x = startX + index * (barWidth + gap) + barWidth / 2f,
                    y = midY + maxHeight / 2f
                ),
                strokeWidth = barWidth,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
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
private fun ChatAvatar(photoUrl: String?, isUser: Boolean) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(
                if (isUser) MaterialTheme.colorScheme.primaryContainer else Color(0xFF3B1F7A)
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            isUser && photoUrl != null -> AsyncImage(
                model = photoUrl,
                contentDescription = "صورة حسابك",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            isUser -> Icon(
                Icons.Default.Person,
                contentDescription = "حسابك",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
            else -> Text("🐱", fontSize = 18.sp)
        }
    }
}

@Composable
private fun BubbleActionIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

private data class CachedGeneratedImage(val file: File, val mimeType: String)

private fun isTrustedGeneratedImageUrl(url: URL): Boolean =
    url.protocol == "https" &&
        url.port in setOf(-1, 443) &&
        url.userInfo == null &&
        (url.host == "ik.imagekit.io" || Regex("^pub-[a-f0-9]{32}\\.r2\\.dev$").matches(url.host))

private suspend fun cacheGeneratedImage(
    context: Context,
    imageUrl: String
): CachedGeneratedImage = withContext(Dispatchers.IO) {
    val url = URL(imageUrl)
    require(isTrustedGeneratedImageUrl(url)) {
        "رابط الصورة غير آمن."
    }
    val connection = (url.openConnection() as HttpsURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
    }
    try {
        check(connection.responseCode in HttpURLConnection.HTTP_OK..299) {
            "تعذر تنزيل الصورة (HTTP ${connection.responseCode})."
        }
        check(isTrustedGeneratedImageUrl(connection.url)) {
            "تم تحويل رابط الصورة إلى مضيف غير موثوق."
        }
        val contentLength = connection.contentLengthLong
        check(contentLength <= 10L * 1024 * 1024) { "حجم الصورة أكبر من المسموح." }
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                totalBytes += read
                check(totalBytes <= 10 * 1024 * 1024) { "حجم الصورة أكبر من المسموح." }
                output.write(buffer, 0, read)
            }
        }
        val bytes = output.toByteArray()
        val (extension, mimeType) = when {
            bytes.size >= 3 &&
                bytes[0] == 0xff.toByte() &&
                bytes[1] == 0xd8.toByte() &&
                bytes[2] == 0xff.toByte() -> "jpg" to "image/jpeg"
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4e.toByte() &&
                bytes[3] == 0x47.toByte() -> "png" to "image/png"
            bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "webp" to "image/webp"
            else -> error("رابط الصورة لا يحتوي على صيغة صورة مدعومة.")
        }
        val directory = File(context.cacheDir, "generated-images")
        check(directory.isDirectory || directory.mkdirs()) { "تعذر تجهيز مساحة مشاركة الصورة." }
        val file = File(directory, "smart-cat-${System.currentTimeMillis()}.$extension")
        file.writeBytes(bytes)
        CachedGeneratedImage(file, mimeType)
    } finally {
        connection.disconnect()
    }
}

private suspend fun saveGeneratedImageToGallery(
    context: Context,
    imageUrl: String
) {
    val cachedImage = cacheGeneratedImage(context, imageUrl)
    withContext(Dispatchers.IO) {
        val fileName = cachedImage.file.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, cachedImage.mimeType)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Smart Cat"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("تعذر إنشاء ملف الصورة في المعرض.")
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    cachedImage.file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("تعذر فتح ملف الصورة في المعرض.")
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            } catch (error: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        } else {
            @Suppress("DEPRECATION")
            val picturesDirectory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            )
            val directory = File(picturesDirectory, "Smart Cat")
            check(directory.isDirectory || directory.mkdirs()) { "تعذر إنشاء مجلد الصور." }
            val destination = File(directory, fileName)
            cachedImage.file.copyTo(destination, overwrite = false)
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(destination.absolutePath),
                arrayOf(cachedImage.mimeType),
                null
            )
        }
    }
}

@Composable
private fun GeneratedImageActions(imageUrl: String, onSpeakImageClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    suspend fun saveImage() {
        try {
            saveGeneratedImageToGallery(context, imageUrl)
            Toast.makeText(context, "تم حفظ الصورة في Pictures/Smart Cat.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            android.util.Log.e("ChatBubble", "Failed to save generated image", error)
            Toast.makeText(
                context,
                com.example.network.GeminiApiClient.friendlyErrorMessage(error),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scope.launch { saveImage() }
        else Toast.makeText(context, "يلزم السماح بحفظ الصور.", Toast.LENGTH_SHORT).show()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = {
                if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    scope.launch { saveImage() }
                }
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("حفظ", fontSize = 12.sp)
        }
        TextButton(
            onClick = {
                scope.launch {
                    try {
                        val cachedImage = cacheGeneratedImage(context, imageUrl)
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            cachedImage.file
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = cachedImage.mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newUri(context.contentResolver, "Smart Cat image", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "مشاركة الصورة"))
                    } catch (error: Exception) {
                        android.util.Log.e("ChatBubble", "Failed to share generated image", error)
                        Toast.makeText(
                            context,
                            com.example.network.GeminiApiClient.friendlyErrorMessage(error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("مشاركة", fontSize = 12.sp)
        }
        TextButton(
            onClick = onSpeakImageClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("اسمع", fontSize = 12.sp)
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    onSpeakClick: (() -> Unit)? = null,
    onSpeakImageClick: () -> Unit = {},
    isSpeaking: Boolean = false,
    isActiveSpeech: Boolean = false,
    onRetryVision: (() -> Unit)? = null,
    renderCoderContent: Boolean = false,
    onRegenerate: (() -> Unit)? = null,
    feedbackQuestion: String? = null,
    studySource: String? = null,
    onCreateStudySet: (String, StudySetMode) -> Unit = { _, _ -> },
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
    val authState by com.example.auth.AuthManager.state.collectAsState()
    val userPhoto = (authState as? com.example.auth.AuthState.SignedIn)?.user?.photoUrl
    var showFeedbackNote by rememberSaveable(message.id) { mutableStateOf(false) }
    var feedbackNote by rememberSaveable(message.id) { mutableStateOf("") }
    var feedbackSubmitted by rememberSaveable(message.id) { mutableStateOf(false) }
    var feedbackSending by rememberSaveable(message.id) { mutableStateOf(false) }
    var feedbackStatus by rememberSaveable(message.id) { mutableStateOf("") }
    var pendingFeedbackRating by remember(message.id) {
        mutableStateOf<com.example.network.GeminiApiClient.FeedbackRating?>(null)
    }
    var showSources by rememberSaveable(message.id) { mutableStateOf(false) }
    val isUser = message.role == "user"
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
    val contentDirection = LocalLayoutDirection.current

    // Keep bubble sides physical so Arabic layout direction does not reverse them.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxBubbleWidth = minOf(
                maxWidth * if (isUser) 0.75f else 0.85f,
                maxWidth - 40.dp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.Top
            ) {
                if (isUser) {
                    ChatAvatar(photoUrl = userPhoto, isUser = true)
                    Spacer(Modifier.width(8.dp))
                }
                Column(
                    modifier = Modifier.widthIn(max = maxBubbleWidth),
                    horizontalAlignment = if (isUser) Alignment.Start else Alignment.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (isUser) 4.dp else 18.dp,
                            bottomEnd = if (isUser) 18.dp else 4.dp
                        ),
                        color = containerColor
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
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
                                                fontSize = 15.sp
                                            )
                                        }
                                    } else {
                                        Text(text = message.content, color = contentColor, fontSize = 15.sp)
                                    }
                                } else {
                                    if (renderCoderContent) {
                                        CoderChatContent(
                                            content = message.content,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        RichChatContent(
                                            content = message.content,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    message.generatedImageUrl?.let { imageUrl ->
                                        AsyncImage(
                                            model = imageUrl,
                                            contentDescription = "الصورة التي أنشأها Smart Cat",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 360.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .padding(top = 8.dp),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                    if (onRetryVision != null) {
                                        TextButton(onClick = onRetryVision) {
                                            Icon(Icons.Default.Replay, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("حل تاني")
                                        }
                                    }
                                    if (onRetryVision != null && !studySource.isNullOrBlank()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            TextButton(
                                                onClick = { onCreateStudySet(studySource, StudySetMode.QUIZ) },
                                                contentPadding = PaddingValues(horizontal = 6.dp)
                                            ) {
                                                Icon(Icons.Default.Quiz, contentDescription = null)
                                                Spacer(Modifier.width(4.dp))
                                                Text("اعمل اختبار", fontSize = 12.sp)
                                            }
                                            TextButton(
                                                onClick = { onCreateStudySet(studySource, StudySetMode.FLASHCARDS) },
                                                contentPadding = PaddingValues(horizontal = 6.dp)
                                            ) {
                                                Icon(Icons.Default.Style, contentDescription = null)
                                                Spacer(Modifier.width(4.dp))
                                                Text("اعمل كروت مراجعة", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    message.thinkingSeconds?.takeIf { it > 0 }?.let { seconds ->
                        Text(
                            text = "🧠 فكر في $seconds ثانية",
                            modifier = Modifier.padding(top = 6.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    Text(
                        text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(message.timestamp)),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    if (!isUser) {
                        message.generatedImageUrl?.let { imageUrl ->
                            GeneratedImageActions(imageUrl, onSpeakImageClick)
                        }
                    }
                    if (!isUser) {
                        val iconTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        val activeTint = MaterialTheme.colorScheme.primary
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BubbleActionIcon(Icons.Default.ContentCopy, "نسخ الرد", iconTint) {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard.setPrimaryClip(ClipData.newPlainText("رد Smart Cat", message.content))
                                Toast.makeText(context, "تم نسخ الرد.", Toast.LENGTH_SHORT).show()
                            }
                            if (onSpeakClick != null && message.generatedImageUrl == null) {
                                BubbleActionIcon(
                                    icon = if (isActiveSpeech) {
                                        if (isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow
                                    } else Icons.Default.VolumeUp,
                                    description = if (isActiveSpeech && isSpeaking) {
                                        "إيقاف مؤقت للقراءة"
                                    } else if (isActiveSpeech) {
                                        "متابعة القراءة"
                                    } else "قراءة النص بصوت عالٍ",
                                    tint = iconTint,
                                    onClick = onSpeakClick
                                )
                            }
                            if (onRegenerate != null) {
                                BubbleActionIcon(Icons.Default.Refresh, "إعادة توليد الرد", iconTint, onClick = onRegenerate)
                            }
                            if (message.sources.isNotEmpty()) {
                                TextButton(
                                    onClick = { showSources = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("🌐 المصادر (${message.sources.size})", fontSize = 12.sp, color = iconTint)
                                }
                            }
                            if (!feedbackQuestion.isNullOrBlank()) {
                                val locked = feedbackSubmitted || feedbackSending
                                BubbleActionIcon(
                                    Icons.Default.ThumbUp,
                                    "إعجاب",
                                    if (feedbackSubmitted && pendingFeedbackRating == FeedbackRating.POSITIVE) activeTint else iconTint,
                                    enabled = !locked
                                ) {
                                    pendingFeedbackRating = FeedbackRating.POSITIVE
                                    feedbackSending = true
                                    feedbackScope.launch {
                                        val success = onFeedbackSubmit(
                                            message.id,
                                            feedbackQuestion,
                                            message.content,
                                            FeedbackRating.POSITIVE,
                                            ""
                                        )
                                        feedbackSending = false
                                        feedbackSubmitted = success
                                        feedbackStatus = if (success) "شكراً لتقييمك!" else "تعذر إرسال التقييم. حاول مرة أخرى."
                                        if (!success) pendingFeedbackRating = null
                                    }
                                }
                                BubbleActionIcon(
                                    Icons.Default.ThumbDown,
                                    "عدم إعجاب",
                                    if (feedbackSubmitted && pendingFeedbackRating == FeedbackRating.NEGATIVE) activeTint else iconTint,
                                    enabled = !locked
                                ) {
                                    pendingFeedbackRating = FeedbackRating.NEGATIVE
                                    feedbackNote = ""
                                    showFeedbackNote = true
                                }
                                BubbleActionIcon(
                                    Icons.Default.Flag,
                                    "إبلاغ عن إجابة خاطئة",
                                    if (feedbackSubmitted && pendingFeedbackRating == FeedbackRating.INCORRECT) activeTint else iconTint,
                                    enabled = !locked
                                ) {
                                    pendingFeedbackRating = FeedbackRating.INCORRECT
                                    feedbackNote = ""
                                    showFeedbackNote = true
                                }
                            }
                        }
                        if (feedbackStatus.isNotBlank()) {
                            Text(
                                feedbackStatus,
                                modifier = Modifier.padding(horizontal = 4.dp),
                                fontSize = 11.sp,
                                color = if (feedbackSubmitted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                if (!isUser) {
                    Spacer(Modifier.width(8.dp))
                    ChatAvatar(photoUrl = null, isUser = false)
                }
            }
        }
    }

    if (showSources) {
        SourcesSheet(message.sources, onDismiss = { showSources = false })
    }

    if (showFeedbackNote) {
        AlertDialog(
            onDismissRequest = { showFeedbackNote = false },
            title = {
                Text(
                    if (pendingFeedbackRating == FeedbackRating.INCORRECT) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesSheet(sources: List<ChatSource>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🌐 المصادر (${sources.size})", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "المصادر",
                            sources.mapIndexed { index, source ->
                                "${source.reference ?: index + 1}. ${source.title}\n${source.url}"
                            }.joinToString("\n\n")
                        )
                    )
                    Toast.makeText(context, "تم نسخ المصادر.", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("نسخ الكل")
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(sources) { index, source ->
                    Surface(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(source.url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                                Toast.makeText(context, "تعذر فتح الرابط.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${source.reference ?: index + 1}. ${source.title}",
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                source.url,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TypingIndicator(state: String? = null) {
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
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Crossfade(targetState = state, label = "typingLabel") { current ->
                        Text(
                            when (current) {
                                "searching" -> "🌐 يترجم السؤال ويبحث بالإنجليزية..."
                                "thinking" -> "🧠 يراجع السؤال بعمق..."
                                "searching+thinking" -> "🌐 يبحث بالإنجليزية ثم 🧠 يراجع النتائج..."
                                "drawing" -> "🎨 جاري الرسم..."
                                else -> "جاري التفكير والكتابة"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                if (state == "searching" || state == "searching+thinking") {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary
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
    val isExtracting by viewModel.isExtractingProductivityFile.collectAsState()
    val selectedFileName by viewModel.productivityFileName.collectAsState()
    val extractedText by viewModel.productivitySourceText.collectAsState()
    val productivityError by viewModel.productivityError.collectAsState()
    val latestSummary by viewModel.latestProductivitySummary.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf("write") } // "write", "summarize", "saved"
    var promptInput by remember { mutableStateOf("") }
    var prodType by remember { mutableStateOf("research") } // "research", "report", "presentation"
    val defaultSummaryFormat by viewModel.productivitySummaryFormat.collectAsState()
    var summaryFormat by rememberSaveable(defaultSummaryFormat) {
        mutableStateOf(defaultSummaryFormat)
    }
    var sourceText by remember(extractedText) { mutableStateOf(extractedText) }
    var selectedFileSize by remember { mutableStateOf<Long?>(null) }
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val metadata = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    (if (nameIndex >= 0) cursor.getString(nameIndex) else null) to
                        (if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null)
                }
            }
            val fileName = metadata?.first ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
            selectedFileSize = metadata?.second
            viewModel.extractProductivityFile(uri, fileName)
        }
    }
    val pdfSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val doc = selectedDoc
        if (uri != null && doc != null) {
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        exportProductivityPdf(context, uri, doc.title, doc.content)
                    }
                    Toast.makeText(context, "تم حفظ مخطط العرض كملف PDF", Toast.LENGTH_LONG).show()
                } catch (error: Exception) {
                    Toast.makeText(
                        context,
                        error.message ?: "تعذر حفظ ملف PDF",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

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
                            modifier = Modifier.fillMaxWidth().smartCatActionHeight(),
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
                        Text("اختر مستنداً لاستخراج محتواه وتلخيصه:", fontSize = 13.sp, fontWeight = FontWeight.Bold)

                        Button(
                            onClick = {
                                documentPicker.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "text/plain"
                                    )
                                )
                            },
                            enabled = !isExtracting && !isGenerating,
                            modifier = Modifier.fillMaxWidth().smartCatActionHeight()
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("رفع ملف PDF أو Word أو Excel أو TXT")
                        }

                        if (selectedFileName != null) {
                            Card(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.InsertDriveFile, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(selectedFileName.orEmpty(), fontWeight = FontWeight.Bold)
                                        Text(
                                            selectedFileSize?.let { String.format(Locale.getDefault(), "%.1f KB", it / 1024f) }
                                                ?: "جارٍ تجهيز الملف",
                                            fontSize = 12.sp
                                        )
                                    }
                                    IconButton(onClick = {
                                        viewModel.clearProductivityFile()
                                        selectedFileSize = null
                                        sourceText = ""
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "حذف الملف")
                                    }
                                }
                            }
                        }
                        if (isExtracting) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("جارٍ رفع الملف واستخراج النص...", fontSize = 12.sp)
                        }
                        productivityError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                        OutlinedTextField(
                            value = sourceText,
                            onValueChange = { sourceText = it },
                            placeholder = { Text("سيظهر النص المستخرج هنا ويمكنك مراجعته وتعديله...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(min = 130.dp, max = 260.dp),
                            maxLines = 10
                        )

                        Text("نوع الملخص", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "short" to "قصير (5–7 نقاط)",
                                "detailed" to "مفصل",
                                "bullets" to "نقاط"
                            ).forEach { (format, label) ->
                                FilterChip(
                                    selected = summaryFormat == format,
                                    onClick = { summaryFormat = format },
                                    label = { Text(label, fontSize = 11.sp) }
                                )
                            }
                        }
                        Button(
                            onClick = {
                                viewModel.setProductivitySummaryFormat(summaryFormat)
                                viewModel.summarizeProductivityFile(
                                    sourceText,
                                    summaryFormat,
                                    selectedFileName ?: "مستند"
                                )
                            },
                            modifier = Modifier.fillMaxWidth().smartCatActionHeight(),
                            enabled = !isGenerating && !isExtracting && sourceText.isNotBlank()
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Text("إنشاء الملخص")
                            }
                        }
                        if (sourceText.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    latestSummary?.let { summary ->
                                        viewModel.createProductivityPresentation(
                                        summary,
                                        selectedFileName ?: "المحتوى التعليمي"
                                        )
                                    }
                                },
                                enabled = !isGenerating && latestSummary != null,
                                modifier = Modifier.fillMaxWidth().smartCatActionHeight()
                            ) {
                                Text("إنشاء مخطط عرض من الملخص")
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
                                Button(
                                    onClick = { doc?.let { viewModel.speakText(it.content, it.id) } },
                                    modifier = Modifier.padding(start = 8.dp).smartCatActionHeight()
                                ) {
                                    Icon(Icons.Default.VolumeUp, contentDescription = "Listen")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("استمع")
                                }
                                if (doc?.type == "presentation") {
                                    IconButton(
                                        onClick = {
                                            pdfSaver.launch(
                                                doc?.title?.take(60)?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                                    ?.plus(".pdf") ?: "presentation.pdf"
                                            )
                                        }
                                    ) {
                                        Icon(Icons.Default.PictureAsPdf, contentDescription = "حفظ العرض PDF")
                                    }
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
                            Button(
                                onClick = { viewModel.clearSelectedDoc() },
                                modifier = Modifier.fillMaxWidth().smartCatActionHeight()
                            ) {
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

private fun exportProductivityPdf(
    context: android.content.Context,
    uri: Uri,
    title: String,
    content: String
) {
    val pageWidth = 595
    val pageHeight = 842
    val margin = 42f
    val pdf = PdfDocument()
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(48, 35, 88)
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(35, 35, 40)
        textSize = 14f
        textAlign = Paint.Align.RIGHT
    }
    val textLines = mutableListOf<String>()
    content.lines().forEach { paragraph ->
        if (paragraph.isBlank()) {
            textLines += ""
        } else {
            var current = ""
            paragraph.split(Regex("\\s+")).forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (bodyPaint.measureText(candidate) > pageWidth - margin * 2 && current.isNotEmpty()) {
                    textLines += current
                    current = word
                } else {
                    current = candidate
                }
            }
            if (current.isNotEmpty()) textLines += current
        }
    }
    val linesPerPage = 42
    val pages = textLines.chunked(linesPerPage).ifEmpty { listOf(emptyList()) }
    pages.forEachIndexed { pageIndex, lines ->
        val page = pdf.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
        )
        val canvas = page.canvas
        canvas.drawText(title.take(80), pageWidth - margin, 56f, titlePaint)
        canvas.drawLine(margin, 72f, pageWidth - margin, 72f, bodyPaint)
        lines.forEachIndexed { lineIndex, line ->
            if (line.isNotEmpty()) {
                canvas.drawText(
                    line,
                    pageWidth - margin,
                    104f + lineIndex * 16f,
                    bodyPaint
                )
            }
        }
        pdf.finishPage(page)
    }
    try {
        context.contentResolver.openOutputStream(uri)?.use(pdf::writeTo)
            ?: throw IOException("تعذر فتح الملف لحفظ PDF.")
    } finally {
        pdf.close()
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
@OptIn(ExperimentalMaterial3Api::class)
fun PersonasScreen(
    viewModel: AppViewModel,
    coderOnly: Boolean = false,
    personaOnlyId: String? = null,
    onBack: (() -> Unit)? = null,
    onPersonaSelected: ((String) -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenSubscription: () -> Unit = {}
) {
    val selectedPersonaId by viewModel.selectedPersonaId.collectAsState()
    val personaSessions by viewModel.chatSessions.collectAsState()
    val conversationSyncStatus by viewModel.conversationSyncStatus.collectAsState()
    val activePersonaSessionIds by viewModel.activePersonaSessionIds.collectAsState()
    val isGenerating by viewModel.isGeneratingPersona.collectAsState()
    val isListeningToSpeech by viewModel.isListeningToSpeech.collectAsState()
    val isProcessingSpeech by viewModel.isProcessingSpeech.collectAsState()
    val speechInputText by viewModel.speechInputText.collectAsState()
    val speechAmplitude by viewModel.speechAmplitude.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val activeSpeechId by viewModel.activeSpeechId.collectAsState()
    val thinkingModeEnabled by viewModel.useThinkingMode.collectAsState()
    val thinkingState by viewModel.thinkingState.collectAsState()
    val usageState by viewModel.usageState.collectAsState()
    val quotaExceeded by viewModel.quotaExceeded.collectAsState()
    val chatActionError by viewModel.chatActionError.collectAsState()
    val socraticModeEnabled by viewModel.socraticModeEnabled.collectAsState()
    val educationalContentEnabled by viewModel.educationalContentEnabled.collectAsState()
    if (personaOnlyId != null && onBack != null) {
        BackHandler(onBack = onBack)
    }
    DisposableEffect(Unit) {
        onDispose {
            activeSpeechId?.let(viewModel::stopSpeakingIfActive)
        }
    }

    // Gamification and Recorder States
    val userPoints by viewModel.userPoints.collectAsState()
    val userBadges by viewModel.userBadges.collectAsState()
    val leaderboardList by viewModel.leaderboardList.collectAsState()
    val isRecordingExplanation by viewModel.isRecordingExplanation.collectAsState()
    val explanationText by viewModel.explanationText.collectAsState()

    val personasList = listOf(
        Persona("teacher", "أ. أحمد", "معلم ومبسط العلوم", Icons.Default.School, AccentColors.forPersona("teacher")),
        Persona("doctor", "د. خالد", "مساعدة التوعية الصحية", Icons.Default.LocalHospital, AccentColors.forPersona("doctor")),
        Persona("business", "أ. سمير", "مساعد التثقيف في الأعمال", Icons.Default.BusinessCenter, AccentColors.forPersona("business")),
        Persona("legal", "المستشار عادل", "مساعد التثقيف القانوني", Icons.Default.Gavel, AccentColors.forPersona("legal")),
        Persona("sheikh", "الشيخ عبد الرحمن", "مساعد المعرفة الإسلامية", Icons.Default.SelfImprovement, AccentColors.forPersona("sheikh")),
        Persona("coach", "الكابتن فهد", "مساعد اللياقة البدنية", Icons.Default.FitnessCenter, AccentColors.forPersona("coach")),
        Persona("designer", "المصممة آية", "واجهات التصميم الجرافيكي", Icons.Default.Palette, AccentColors.forPersona("designer")),
        Persona("chef", "الشيف مراد", "وصفات الطهي والحلويات", Icons.Default.Restaurant, AccentColors.forPersona("chef")),
        Persona("nanny", "المربية أسماء", "التوعية بالتربية الأسرية", Icons.Default.BabyChangingStation, AccentColors.forPersona("nanny"))
    )

    val activePersonaId = resolvePersonaScreenId(personaOnlyId, coderOnly, selectedPersonaId)
    val quotaFeature = if (coderOnly) "coder" else "chat"
    val personaRemaining = usageState.snapshot?.let { snapshot ->
        if (snapshot.legacy) Int.MAX_VALUE
        else snapshot.subscription?.remaining ?: snapshot.feature(quotaFeature)?.remaining
    }
    val personaQuotaExhausted = usageBanner(usageState, quotaFeature)?.exhausted == true
    val activeSessionId = activePersonaSessionIds[activePersonaId] ?: "persona_$activePersonaId"
    var personaSearchEnabled by remember(activePersonaId) {
        mutableStateOf(viewModel.personaWebSearchEnabled(activePersonaId))
    }
    var personaThinkingEnabled by remember(activePersonaId) {
        mutableStateOf(viewModel.personaThinkingEnabled(activePersonaId))
    }
    val personaRequiredQuota = requiredMessageQuota(
        webSearch = personaSearchEnabled,
        thinking = personaThinkingEnabled
    )
    val personaHasSufficientQuota = personaRemaining == null || personaRemaining >= personaRequiredQuota
    var personaAutoReadEnabled by remember(activePersonaId) {
        mutableStateOf(viewModel.personaAutoReadEnabled(activePersonaId))
    }
    var showAssistantSettings by remember { mutableStateOf(false) }
    var showClearChatConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(coderOnly, selectedPersonaId) {
        if (!coderOnly && personaOnlyId == null &&
            (selectedPersonaId == "coder" || selectedPersonaId == "hasan" || selectedPersonaId == "jana")
        ) {
            viewModel.setDefaultPersona("teacher")
        }
    }

    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val personaScope = rememberCoroutineScope()
    val composerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var textInput by rememberSaveable { mutableStateOf("") }
    var showComposerSheet by remember { mutableStateOf(false) }
    var showComposerPlugins by remember { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    fun preparePersonaVisionImage(uri: Uri) {
        if (personaRemaining != null && personaRemaining < 3) {
            Toast.makeText(context, "الرصيد لا يكفي لتحليل الصورة؛ تحتاج إلى 3 رسائل على الأقل.", Toast.LENGTH_SHORT).show()
            return
        }
        personaScope.launch {
            try {
                val imageFile = withContext(Dispatchers.IO) { compressChatImage(context, uri) }
                viewModel.sendPersonaVision(imageFile.absolutePath, textInput.trim(), activePersonaId)
                textInput = ""
            } catch (e: Exception) {
                android.util.Log.e("PersonasScreen", "Failed to prepare vision image", e)
                Toast.makeText(context, e.localizedMessage ?: "تعذر تجهيز الصورة.", Toast.LENGTH_LONG).show()
            } finally {
                if (uri.toString() == pendingCameraUri) {
                    pendingCameraFilePath?.let(::File)?.delete()
                    pendingCameraFilePath = null
                    pendingCameraUri = null
                }
            }
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) preparePersonaVisionImage(uri) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val capturedUri = pendingCameraUri?.let(Uri::parse)
        if (success && capturedUri != null) {
            preparePersonaVisionImage(capturedUri)
        } else {
            pendingCameraFilePath?.let(::File)?.delete()
            pendingCameraFilePath = null
            pendingCameraUri = null
        }
    }

    fun openPersonaCamera() {
        try {
            val directory = File(context.cacheDir, "camera-captures").apply {
                check(mkdirs() || isDirectory) { "تعذر تجهيز مساحة الكاميرا." }
            }
            val file = File.createTempFile("smart-cat-persona-", ".jpg", directory)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraUri = uri.toString()
            pendingCameraFilePath = file.absolutePath
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            android.util.Log.e("PersonasScreen", "Failed to open camera", e)
            Toast.makeText(context, "تعذر فتح الكاميرا.", Toast.LENGTH_SHORT).show()
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
        if (uri != null) personaScope.launch {
            try {
                val encodedPdf = encodePdfAttachment(context, uri)
                viewModel.sendMultimodalMessage(
                    encodedPdf,
                    "application/pdf",
                    activePersonaId,
                    persistSelection = !coderOnly && personaOnlyId == null
                )
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                android.util.Log.e("PersonasScreen", "Failed to attach PDF", e)
                Toast.makeText(context, e.localizedMessage ?: "تعذر إرفاق الملف.", Toast.LENGTH_LONG).show()
            }
        }
    }

    var showVoiceSetupDialog by remember { mutableStateOf(false) }
    fun closePersonaComposer(action: () -> Unit) {
        personaScope.launch {
            composerSheetState.hide()
            showComposerSheet = false
            showComposerPlugins = false
            action()
        }
    }
    LaunchedEffect(activePersonaId) {
        viewModel.ensurePersonaChatSession(activePersonaId)
    }
    LaunchedEffect(activeSessionId) {
        viewModel.watchConversationMessages(activeSessionId)
    }
    val currentMessages by remember(activeSessionId) {
        viewModel.personaMessagesFor(activeSessionId)
    }.collectAsState(initial = emptyList())
    val friends = listOf(
        Persona("hasan", "حسن صديق", "صاحبك الجدع - صوت ولد تفاعلي", Icons.Default.RecordVoiceOver, AccentColors.VoiceFriend),
        Persona("jana", "ريتاج صديقة", "صديقتك الذكية - صوت بنت رقيق", Icons.Default.Face, AccentColors.VoiceFriend)
    )
    val fallbackPersona = Persona(
        "teacher",
        "أ. أحمد",
        "معلم ومبسط العلوم",
        Icons.Default.School,
        AccentColors.PersonaAhmed
    )
    val activePersona = if (coderOnly) {
        Persona("coder", "H2 Code", "خبير البرمجة والأكواد", Icons.Default.Code, AccentColors.Coder)
    } else {
        resolvePersonaDisplay(activePersonaId, personasList + friends, fallbackPersona)
    }
    val saveChatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            personaScope.launch {
                try {
                    val chatText = buildString {
                        appendLine("${activePersona.name} - ${personaSessions.firstOrNull { it.id == activeSessionId }?.title.orEmpty()}")
                        currentMessages.forEach { message ->
                            appendLine()
                            appendLine(if (message.role == "user") "أنت:" else "${activePersona.name}:")
                            val attachment = parseChatVisionMessage(message.content)
                            appendLine(
                                if (attachment != null) {
                                    "[صورة مرفقة] ${attachment.question}".trim()
                                } else {
                                    message.content
                                }
                            )
                        }
                    }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(chatText)
                        } ?: error("تعذر فتح الملف لحفظ المحادثة.")
                    }
                    Toast.makeText(context, "تم حفظ نسخة المحادثة.", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    android.util.Log.e("PersonasScreen", "Failed to export persona chat", error)
                    Toast.makeText(context, "تعذر حفظ المحادثة.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val listState = rememberLazyListState()
    val personaDrawerState = rememberDrawerState(DrawerValue.Closed)
    val personaSidebarItems = remember(personasList, friends) { personasList + friends }
    val personaAccentScheme = MaterialTheme.colorScheme.copy(
        primary = activePersona.color,
        onPrimary = if (activePersona.color.luminance() > 0.22f) Color(0xFF111827) else Color.White,
        primaryContainer = activePersona.color.copy(alpha = 0.22f),
        onPrimaryContainer = MaterialTheme.colorScheme.onSurface,
        secondary = activePersona.color,
        secondaryContainer = activePersona.color.copy(alpha = 0.22f),
        onSecondaryContainer = MaterialTheme.colorScheme.onSurface,
        tertiary = activePersona.color
    )
    val sharedChatAccent = activePersona.color

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

    MaterialTheme(colorScheme = personaAccentScheme) {
        ModalNavigationDrawer(
            drawerState = personaDrawerState,
            gesturesEnabled = onPersonaSelected != null,
            drawerContent = {
                if (onPersonaSelected != null) {
                    ModalDrawerSheet {
                        ChatSidebar(
                            sessions = personaSessions.filter { it.personaId == activePersonaId },
                            currentSessionId = activeSessionId,
                            onNewChat = {
                                viewModel.startNewPersonaChat(activePersonaId)
                                personaScope.launch { personaDrawerState.close() }
                            },
                            onSelect = {
                                viewModel.selectPersonaChatSession(activePersonaId, it)
                                personaScope.launch { personaDrawerState.close() }
                            },
                            onRename = viewModel::renameSession,
                            onDelete = { viewModel.deletePersonaChatSession(activePersonaId, it) },
                            onOpenSettings = {
                                personaScope.launch {
                                    personaDrawerState.close()
                                    onOpenSettings?.invoke()
                                }
                            },
                            personaItems = personaSidebarItems,
                            selectedPersonaId = activePersonaId,
                            syncIssue = conversationSyncStatus.error,
                            onSelectPersona = { personaId ->
                                personaScope.launch {
                                    personaDrawerState.close()
                                    onPersonaSelected(personaId)
                                }
                            }
                        )
                    }
                }
            }
        ) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onPersonaSelected != null) {
                IconButton(onClick = { personaScope.launch { personaDrawerState.open() } }) {
                    Icon(Icons.Default.Menu, contentDescription = "قائمة الشخصيات")
                }
            }
            Icon(activePersona.icon, contentDescription = null, tint = activePersona.color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(activePersona.name, fontWeight = FontWeight.Bold, color = activePersona.color)
            }
            if (onBack != null) {
                TextButton(onClick = onBack) {
                    Text(
                        if (personaOnlyId == "hasan" || personaOnlyId == "jana") "اختيار صديق آخر"
                        else "العودة للشخصيات",
                        color = activePersona.color
                    )
                }
            }
            UsageIndicatorCompact(
                state = usageState,
                onRefresh = viewModel::refreshUsage,
                feature = quotaFeature,
                onUpgrade = onOpenSubscription,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            IconButton(onClick = { showAssistantSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "إعدادات ${activePersona.name}")
            }
        }

        if (usageBanner(usageState, quotaFeature)?.level?.let { it != UsageLevel.NORMAL } == true) {
            UsageBannerCard(
                state = usageState,
                onRefresh = viewModel::refreshUsage,
                feature = quotaFeature,
                onUpgrade = onOpenSubscription,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        Divider()

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
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) {
                        Text("إنهاء والتقرير 🎓", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "H",
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
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
                    itemsIndexed(currentMessages) { index, msg ->
                        val precedingUserMessage = if (msg.role == "model" && index > 0) {
                            currentMessages.subList(0, index).lastOrNull { it.role == "user" }
                        } else null
                        val visionAttachment = precedingUserMessage
                            ?.let { parseChatVisionMessage(it.content) }
                        ChatBubble(
                            message = msg,
                            onSpeakClick = {
                                viewModel.speakText(msg.content, msg.id, activePersona.id)
                            },
                            isSpeaking = isSpeaking,
                            isActiveSpeech = activeSpeechId == msg.id,
                            onRetryVision = if (visionAttachment != null) {
                                { precedingUserMessage?.let { viewModel.retryPersonaVision(activePersona.id, it.id) } }
                            } else null,
                            renderCoderContent = coderOnly,
                            feedbackQuestion = visionAttachment?.question
                                ?: precedingUserMessage?.content,
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
                            TypingIndicator(thinkingState)
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
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
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
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
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
        val hasPersonaInput = textInput.isNotBlank()
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Ltr,
            LocalMinimumInteractiveComponentSize provides 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .padding(bottom = 8.dp)
                    .shadow(2.dp, RoundedCornerShape(28.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .heightIn(min = 92.dp, max = 190.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    TextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = {
                            Text(
                                if (isListeningToSpeech) "جاري الاستماع لصوتك..."
                                else "اكتب رسالة",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 120.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (personaQuotaExhausted) {
                                Toast.makeText(context, "استهلكت كل رسائلك. جدّد اشتراكك أو ارتقِ لخطة أعلى.", Toast.LENGTH_SHORT).show()
                            } else if (!personaHasSufficientQuota) {
                                Toast.makeText(context, "الرصيد لا يكفي لهذه العملية. اختر ميزة أقل تكلفة أو رقِّ اشتراكك.", Toast.LENGTH_SHORT).show()
                            } else if (hasPersonaInput && !isGenerating) {
                                viewModel.sendPersonaMessage(textInput, activePersonaId)
                                textInput = ""
                                viewModel.clearSpeechInput()
                            }
                        }),
                        maxLines = 4
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        if (hasPersonaInput) {
                            IconButton(
                                onClick = {
                                    if (personaQuotaExhausted) {
                                        Toast.makeText(context, "استهلكت كل رسائلك. جدّد اشتراكك أو ارتقِ لخطة أعلى.", Toast.LENGTH_SHORT).show()
                                    } else if (!personaHasSufficientQuota) {
                                        Toast.makeText(context, "الرصيد لا يكفي لهذه العملية. اختر ميزة أقل تكلفة أو رقِّ اشتراكك.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.sendPersonaMessage(textInput, activePersonaId)
                                        textInput = ""
                                        viewModel.clearSpeechInput()
                                    }
                                },
                                enabled = !isGenerating && personaHasSufficientQuota,
                                modifier = Modifier.size(40.dp)
                                    .background(
                                        if (!personaHasSufficientQuota) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.primary,
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = "إرسال",
                                    tint = if (!personaHasSufficientQuota) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (isListeningToSpeech) {
                                        viewModel.stopSpeechRecognition()
                                    } else if (ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        showVoiceSetupDialog = true
                                    } else {
                                        speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                enabled = !isProcessingSpeech,
                                modifier = Modifier.size(40.dp)
                            ) {
                                if (isProcessingSpeech) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        if (isListeningToSpeech) Icons.Default.MicNone else Icons.Default.Mic,
                                        contentDescription = if (isListeningToSpeech) "إيقاف التسجيل الصوتي" else "الإدخال الصوتي",
                                        tint = if (isListeningToSpeech) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            showComposerPlugins = false
                            showComposerSheet = true
                        },
                        modifier = Modifier.size(40.dp),
                        enabled = !isGenerating
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "فتح أدوات المحادثة",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (isListeningToSpeech) {
                        SpeechWaveform(speechAmplitude, Modifier.padding(start = 6.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatToolChip(
                            label = "بحث",
                            icon = Icons.Default.Language,
                            selected = personaSearchEnabled,
                            accentColor = sharedChatAccent,
                            enabled = !personaQuotaExhausted && (
                                personaSearchEnabled || (personaRemaining ?: 0) >= if (personaThinkingEnabled) 3 else 2
                            ),
                            onClick = {
                                personaSearchEnabled = !personaSearchEnabled
                                viewModel.setPersonaWebSearchEnabled(activePersonaId, personaSearchEnabled)
                            }
                        )
                        ChatToolChip(
                            label = "تفكير",
                            icon = Icons.Default.Psychology,
                            selected = personaThinkingEnabled,
                            accentColor = sharedChatAccent,
                            enabled = !personaQuotaExhausted && (
                                personaThinkingEnabled || (personaRemaining ?: 0) >= if (personaSearchEnabled) 3 else 2
                            ),
                            onClick = {
                                personaThinkingEnabled = !personaThinkingEnabled
                                viewModel.setPersonaThinkingEnabled(activePersonaId, personaThinkingEnabled)
                            }
                        )
                    }
                }
            }
        }
    }
        }
    }

    if (showComposerSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showComposerSheet = false
                showComposerPlugins = false
            },
            sheetState = composerSheetState,
            containerColor = Color(0xFF17151D),
            contentColor = Color(0xFFF2EFF7),
            dragHandle = {
                BottomSheetDefaults.DragHandle(color = Color(0xFFB9B6C2))
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (showComposerPlugins) "المكونات الإضافية" else "إضافة إلى المحادثة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        if (showComposerPlugins) showComposerPlugins = false
                        else showComposerSheet = false
                    }) {
                        Icon(
                            if (showComposerPlugins) Icons.Default.ArrowBack else Icons.Default.Close,
                            contentDescription = if (showComposerPlugins) "رجوع" else "إغلاق"
                        )
                    }
                }
                if (showComposerPlugins) {
                    ChatComposerSheetAction(
                        icon = Icons.Default.School,
                        title = "التدريس السقراطي",
                        subtitle = "تعلّم بأسئلة وخطوات تفاعلية",
                        selected = socraticModeEnabled,
                        onClick = {
                            viewModel.setSocraticModeEnabled(!socraticModeEnabled)
                        }
                    )
                    ChatComposerSheetAction(
                        icon = Icons.Default.MenuBook,
                        title = "الرد من المحتوى التعليمي",
                        subtitle = "استند إلى مصادر المقررات المتاحة",
                        selected = educationalContentEnabled,
                        onClick = {
                            viewModel.setEducationalContentEnabled(!educationalContentEnabled)
                        }
                    )
                    ChatComposerSheetAction(
                        icon = Icons.Default.Psychology,
                        title = if (thinkingModeEnabled) "إيقاف التفكير الأعمق" else "فكّر بعمق أكبر",
                        subtitle = "استخدم التفكير الأعمق في ردود هذه الشخصيات",
                        selected = thinkingModeEnabled,
                        onClick = {
                            val enabled = !thinkingModeEnabled
                            viewModel.setThinkingModeEnabled(enabled)
                            closePersonaComposer {}
                        }
                    )
                    ChatComposerSheetAction(
                        icon = Icons.Default.VolumeUp,
                        title = if (personaAutoReadEnabled) "إيقاف القراءة التلقائية" else "قراءة الردود تلقائياً",
                        subtitle = "تشغيل صوت الردود الجديدة للشخصية الحالية",
                        selected = personaAutoReadEnabled,
                        onClick = {
                            personaAutoReadEnabled = !personaAutoReadEnabled
                            viewModel.setPersonaAutoReadEnabled(activePersonaId, personaAutoReadEnabled)
                            closePersonaComposer {}
                        }
                    )
                } else {
                    ChatComposerSheetAction(
                        icon = Icons.Default.CameraAlt,
                        title = "الكاميرا",
                        subtitle = "التقط صورة لمسألة أو صفحة",
                        onClick = { closePersonaComposer(::openPersonaCamera) }
                    )
                    ChatComposerSheetAction(
                        icon = Icons.Default.Image,
                        title = "الصور",
                        subtitle = "اختر صورة وسيتم ضغطها قبل الإرسال",
                        onClick = { closePersonaComposer {                                                 imageLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        ) } }
                    )
                    ChatComposerSheetAction(
                        icon = Icons.Default.AttachFile,
                        title = "الملفات",
                        subtitle = "إرفاق ملف PDF",
                        onClick = { closePersonaComposer { pdfLauncher.launch("application/pdf") } }
                    )
                    ChatComposerSheetAction(
                        icon = Icons.Default.Extension,
                        title = "المكونات الإضافية",
                        subtitle = "التدريس السقراطي والمحتوى التعليمي وخيارات الرد",
                        onClick = { showComposerPlugins = true }
                    )
                    ChatComposerSheetAction(
                        icon = Icons.Default.Psychology,
                        title = if (personaThinkingEnabled) "إيقاف التفكير" else "تفعيل التفكير",
                        subtitle = "استخدم البحث والتفكير المخصصين لهذا المساعد",
                        selected = personaThinkingEnabled,
                        onClick = {
                            personaThinkingEnabled = !personaThinkingEnabled
                            viewModel.setPersonaThinkingEnabled(activePersonaId, personaThinkingEnabled)
                            closePersonaComposer {}
                        }
                    )
                }
            }
        }
    }

    if (showAssistantSettings) {
        AlertDialog(
            onDismissRequest = { showAssistantSettings = false },
            title = {
                Text("إعدادات ${activePersona.name}", color = activePersona.color)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("البحث على الإنترنت", fontWeight = FontWeight.SemiBold)
                            Text("ابحث عن معلومات ومراجع حديثة.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = personaSearchEnabled,
                            onCheckedChange = {
                                personaSearchEnabled = it
                                viewModel.setPersonaWebSearchEnabled(activePersonaId, it)
                            }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("التفكير الأعمق", fontWeight = FontWeight.SemiBold)
                            Text("فعّله للأسئلة التي تحتاج تحليلاً متأنياً.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = personaThinkingEnabled,
                            onCheckedChange = {
                                personaThinkingEnabled = it
                                viewModel.setPersonaThinkingEnabled(activePersonaId, it)
                            }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("قراءة الردود تلقائياً", fontWeight = FontWeight.SemiBold)
                            Text("استخدم الصوت الطبيعي عند توفره.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = personaAutoReadEnabled,
                            onCheckedChange = {
                                personaAutoReadEnabled = it
                                viewModel.setPersonaAutoReadEnabled(activePersonaId, it)
                            }
                        )
                    }
                    Text(
                        "يحاول التطبيق استخدام Google WaveNet للصوت العربي، ثم ينتقل تلقائياً إلى صوت Gemini إذا لم تتوفر الخدمة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                viewModel.startNewPersonaChat(activePersonaId)
                                showAssistantSettings = false
                            }
                        ) { Text("محادثة جديدة") }
                        TextButton(
                            onClick = {
                                val fileName = "${activePersona.name}-محادثة.txt"
                                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                saveChatLauncher.launch(fileName)
                            },
                            enabled = currentMessages.isNotEmpty()
                        ) { Text("حفظ نسخة") }
                    }
                    TextButton(
                        onClick = { showClearChatConfirmation = true },
                        enabled = currentMessages.isNotEmpty(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("مسح رسائل هذه المحادثة")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAssistantSettings = false }) { Text("تم") }
            }
        )
    }

    if (showClearChatConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirmation = false },
            title = { Text("مسح المحادثة؟") },
            text = { Text("سيتم حذف رسائل هذه المحادثة فقط. ستبقى المحادثات الأخرى محفوظة.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearPersonaChat(activeSessionId)
                        showClearChatConfirmation = false
                        showAssistantSettings = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("مسح") }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatConfirmation = false }) { Text("إلغاء") }
            }
        )
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
    chatActionError?.takeIf { quotaExceeded == null }?.let { errorMessage ->
        AlertDialog(
            onDismissRequest = viewModel::clearChatActionError,
            title = { Text("تعذر إرسال الرسالة") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = viewModel::clearChatActionError) { Text("حسنًا") }
            }
        )
    }
    QuotaExceededDialog(
        error = quotaExceeded,
        onDismiss = viewModel::clearQuotaExceeded,
        onSubscribe = {
            viewModel.clearQuotaExceeded()
            onOpenSubscription()
        }
    )
}

@Composable
fun PersonaBubble(
    msg: ChatMessage,
    p: Persona,
    onSpeakClick: (() -> Unit)? = null,
    isSpeaking: Boolean = false,
    isActiveSpeech: Boolean = false,
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
    var feedbackSubmitted by rememberSaveable(msg.id) { mutableStateOf(false) }
    var feedbackSending by rememberSaveable(msg.id) { mutableStateOf(false) }
    var feedbackStatus by rememberSaveable(msg.id) { mutableStateOf("") }
    var showFeedbackNote by rememberSaveable(msg.id) { mutableStateOf(false) }
    var feedbackNote by rememberSaveable(msg.id) { mutableStateOf("") }
    var pendingFeedbackRating by remember(msg.id) {
        mutableStateOf<com.example.network.GeminiApiClient.FeedbackRating?>(null)
    }
    val isUser = msg.role == "user"
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val contentDirection = LocalLayoutDirection.current
    val authState by AuthManager.state.collectAsState()
    val userPhoto = (authState as? AuthState.SignedIn)?.user?.photoUrl
    val visionAttachment = remember(msg.content) {
        if (isUser) parseChatVisionMessage(msg.content) else null
    }
    val attachedImage = remember(visionAttachment?.imagePath) {
        visionAttachment?.imagePath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }

    fun submitFeedback(
        rating: com.example.network.GeminiApiClient.FeedbackRating,
        note: String = ""
    ) {
        if (feedbackSubmitted || feedbackSending || feedbackQuestion.isNullOrBlank()) return
        feedbackSending = true
        feedbackScope.launch {
            val success = onFeedbackSubmit(msg.id, feedbackQuestion, msg.content, rating, note)
            feedbackSending = false
            feedbackSubmitted = success
            feedbackStatus = if (success) "شكراً لتقييمك!" else "تعذر إرسال التقييم. حاول مرة أخرى."
            if (success) showFeedbackNote = false
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            if (isUser) {
                ChatAvatar(photoUrl = userPhoto, isUser = true)
                Spacer(Modifier.width(8.dp))
            }
            Card(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 4.dp else 18.dp,
                    bottomEnd = if (isUser) 18.dp else 4.dp
                ),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                Column(modifier = Modifier.padding(12.dp)) {
                if (isUser) {
                    if (visionAttachment != null) {
                        attachedImage?.let { image ->
                            Image(
                                bitmap = image,
                                contentDescription = "الصورة المرسلة للشخصية",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                        if (visionAttachment.question.isNotBlank()) {
                            Text(
                                visionAttachment.question,
                                modifier = Modifier.padding(top = 8.dp),
                                color = contentColor,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        Text(msg.content, color = contentColor, fontSize = 13.sp)
                    }
                } else {
                    if (p.id == "coder") {
                        CoderChatContent(content = msg.content, modifier = Modifier.fillMaxWidth())
                    } else {
                        RichChatContent(content = msg.content, modifier = Modifier.fillMaxWidth())
                    }
                    if (!feedbackQuestion.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("قيّم الرد:", fontSize = 11.sp, color = contentColor.copy(alpha = 0.75f))
                            TextButton(
                                onClick = {
                                    submitFeedback(com.example.network.GeminiApiClient.FeedbackRating.POSITIVE)
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
                        if (showFeedbackNote) {
                            AlertDialog(
                                onDismissRequest = { showFeedbackNote = false },
                                title = {
                                    Text(
                                        if (pendingFeedbackRating ==
                                            com.example.network.GeminiApiClient.FeedbackRating.INCORRECT
                                        ) "الإبلاغ عن إجابة خاطئة" else "ملاحظتك على الإجابة"
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
                                            pendingFeedbackRating?.let { rating ->
                                                submitFeedback(rating, feedbackNote.trim())
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
                    if (onRetryVision != null) {
                        TextButton(onClick = onRetryVision) {
                            Icon(Icons.Default.Replay, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("حل تاني")
                        }
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
                            if (onSpeakClick != null && msg.generatedImageUrl == null) {
                                IconButton(onClick = onSpeakClick, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        imageVector = if (isActiveSpeech) {
                                            if (isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow
                                        } else Icons.Default.VolumeUp,
                                        contentDescription = if (isActiveSpeech && isSpeaking) {
                                            "إيقاف مؤقت للقراءة"
                                        } else if (isActiveSpeech) {
                                            "متابعة القراءة"
                                        } else "قراءة النص بصوت عالٍ",
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
            if (!isUser) {
                Spacer(Modifier.width(8.dp))
                ChatAvatar(photoUrl = null, isUser = false)
            }
        }
    }
}

// ==================== ORGANIZER & SCHEDULER SCREEN ====================

@Composable
fun OrganizerScreen(viewModel: AppViewModel) = AcademicOrganizerScreen(viewModel)

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
        HubSectionHeading(
            title = "تجويد القرآن",
            description = "اختر السورة والآية، ثم سجّل تلاوتك لتحصل على ملاحظات مختصرة."
        )
        HubCard(
            modifier = Modifier.fillMaxWidth(),
            accentColor = AccentColors.Quran
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
                modifier = Modifier.fillMaxWidth().smartCatActionHeight(),
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
                modifier = Modifier.fillMaxWidth().smartCatActionHeight(),
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
                modifier = Modifier.weight(1f).smartCatActionHeight(),
                enabled = selectedSurah != null
            ) {
                Text(if (reciterName?.startsWith("محمود خليل الحصري") == true) "إيقاف الحصري" else "الحصري")
            }
            OutlinedButton(
                onClick = { viewModel.playQuranReciterSurah(9, "محمد صديق المنشاوي") },
                modifier = Modifier.weight(1f).smartCatActionHeight(),
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            enabled = selectedSurah != null && selectedAyah != null && !isRecording && !isChecking
        ) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isRecording) "جاري التسجيل..." else "سجّل تلاوتك للآية")
        }
        if (isRecording) {
            OutlinedButton(
                onClick = { viewModel.stopQuranRecordingAndCheck() },
                modifier = Modifier.fillMaxWidth().smartCatActionHeight()
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
                            modifier = Modifier.weight(1f).smartCatActionHeight()
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
                            modifier = Modifier.weight(1f).smartCatActionHeight()
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
    section: AppScreen,
    onDismiss: () -> Unit
) {
    val sessions by viewModel.chatSessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val socraticModeEnabled by viewModel.socraticModeEnabled.collectAsState()
    val educationalContentEnabled by viewModel.educationalContentEnabled.collectAsState()
    val thinkingModeEnabled by viewModel.useThinkingMode.collectAsState()
    val reminderEnabled by viewModel.quranReminderEnabled.collectAsState()
    val reminderHour by viewModel.quranReminderHour.collectAsState()
    val reminderMinute by viewModel.quranReminderMinute.collectAsState()
    val speechSpeed by viewModel.speechSpeed.collectAsState()
    val autoReadChat by viewModel.autoReadChatEnabled.collectAsState()
    val summaryFormat by viewModel.productivitySummaryFormat.collectAsState()
    val defaultPersona by viewModel.selectedPersonaId.collectAsState()
    val personaAutoVoice by viewModel.personaVoiceEnabled.collectAsState()
    val confirmOrganizerDelete by viewModel.organizerConfirmDelete.collectAsState()
    val trackLearningProfile by viewModel.trackLearningProfile.collectAsState()
    val quizQuestionCount by viewModel.studyQuizQuestionCount.collectAsState()
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
                    text = "إعدادات ${if (section == AppScreen.MORE) "التطبيق" else section.titleAr}",
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
                    AccountSection()
                    if (section == AppScreen.CHAT) {
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
                                    "يستخدم سياق المحادثة الحالية للتدريس خطوة بخطوة.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = socraticModeEnabled,
                                onCheckedChange = viewModel::setSocraticModeEnabled
                            )
                        }
                    }

                    if (section == AppScreen.CHAT) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("التفكير الأعمق", fontWeight = FontWeight.Bold)
                                    Text("استخدم نموذج التفكير للأسئلة المعقدة.", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = thinkingModeEnabled,
                                    onCheckedChange = viewModel::setThinkingModeEnabled
                                )
                            }
                        }
                    }

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
                                Text("الرد من المحتوى التعليمي", fontWeight = FontWeight.Bold)
                                Text(
                                    "ابحث في ملفات الكورسات وأرفق المصادر بالرد.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = educationalContentEnabled,
                                onCheckedChange = viewModel::setEducationalContentEnabled
                            )
                        }
                    }
                    }

                    if (section == AppScreen.QURAN) {
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
                    }

                    if (section == AppScreen.CHAT) {
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
                    }

                    when (section) {
                        AppScreen.PRODUCTIVITY -> {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("إعدادات الإنتاجية", fontWeight = FontWeight.Bold)
                                Text("نوع الملخص الافتراضي")
                                listOf(
                                    "short" to "قصير (5–7 نقاط)",
                                    "detailed" to "مفصل",
                                    "bullets" to "نقاط"
                                ).forEach { (format, label) ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            viewModel.setProductivitySummaryFormat(format)
                                        },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = summaryFormat == format,
                                            onClick = { viewModel.setProductivitySummaryFormat(format) }
                                        )
                                        Text(label)
                                    }
                                }
                            }
                        }
                        AppScreen.PERSONAS -> {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("إعدادات شخصيات AI", fontWeight = FontWeight.Bold)
                                Text("الشخصية الافتراضية")
                                listOf(
                                    "teacher" to "أ. أحمد",
                                    "doctor" to "د. خالد",
                                    "business" to "أ. سمير",
                                    "legal" to "المستشار عادل",
                                    "sheikh" to "الشيخ عبد الرحمن",
                                    "coach" to "الكابتن فهد",
                                    "designer" to "المصممة آية",
                                    "chef" to "الشيف مراد",
                                    "nanny" to "المربية أسماء"
                                ).forEach { (id, name) ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { viewModel.setDefaultPersona(id) },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = defaultPersona == id,
                                            onClick = { viewModel.setDefaultPersona(id) }
                                        )
                                        Text(name)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("قراءة الردود تلقائياً", Modifier.weight(1f))
                                    Switch(
                                        checked = personaAutoVoice,
                                        onCheckedChange = viewModel::setPersonaAutoVoiceEnabled
                                    )
                                }
                            }
                        }
                        AppScreen.ORGANIZER -> {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("التأكيد قبل حذف الجدول", fontWeight = FontWeight.Bold)
                                        Text("يمنع حذف جدولك اليومي بالخطأ.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(
                                        checked = confirmOrganizerDelete,
                                        onCheckedChange = viewModel::setOrganizerConfirmDelete
                                    )
                                }
                            }
                        }
                        AppScreen.LEARNING_PROFILE -> {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("تسجيل الأسئلة على هذا الجهاز", fontWeight = FontWeight.Bold)
                                        Text("يُستخدم السجل المحلي لعرض تقدمك ونقاط الضعف.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(
                                        checked = trackLearningProfile,
                                        onCheckedChange = viewModel::setTrackLearningProfile
                                    )
                                }
                            }
                        }
                        AppScreen.STUDY_SET -> {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text("عدد أسئلة الاختبار الافتراضي: $quizQuestionCount", fontWeight = FontWeight.Bold)
                                Slider(
                                    value = quizQuestionCount.toFloat(),
                                    onValueChange = { viewModel.setStudyQuizQuestionCount(it.toInt()) },
                                    valueRange = 5f..10f,
                                    steps = 4
                                )
                            }
                        }
                        else -> Unit
                    }

                    if (section == AppScreen.CHAT) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("الصوت والقراءة", fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("قراءة الردود تلقائياً", Modifier.weight(1f))
                                Switch(
                                    checked = autoReadChat,
                                    onCheckedChange = viewModel::setAutoReadChatEnabled
                                )
                            }
                            Text("سرعة القراءة: ${String.format(Locale.US, "%.1f", speechSpeed)}x")
                            Slider(
                                value = speechSpeed,
                                onValueChange = viewModel::setSpeechSpeed,
                                valueRange = 0.75f..1.25f
                            )
                        }
                    }

                    if (section == AppScreen.MORE) {
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
                                        .clickable { viewModel.setAppTheme(id) }
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
    onStartVoice: () -> Unit,
    includeVoiceSelection: Boolean = true
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
                    if (includeVoiceSelection) "المحادثة الصوتية الذكية 🎙️"
                    else "الإدخال الصوتي 🎙️",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (includeVoiceSelection) {
                        "اضبط إعدادات المحادثة الصوتية الفورية مع الصديق الذكي:"
                    } else {
                        "تحدث بالعربية أو الإنجليزية؛ على Android 14 والإصدارات الأحدث يتم التبديل بينهما تلقائياً. راجع النص قبل إرساله."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 1. Voice selector
                if (includeVoiceSelection) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                Text("حسن (نبرة أخفض)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("الصوت العربي على الجهاز بنبرة أخفض", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
                                Text("ريتاج (نبرة أعلى)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("الصوت العربي على الجهاز بنبرة أعلى", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }

                // Speech recognition language selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (includeVoiceSelection) "2. لغة التحدث المفضلة:"
                        else "لغة التعرف على الكلام:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
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
                    viewModel.setDefaultPersona(selectedVoice)
                    viewModel.setSpeechRecognitionLocale(selectedLang)
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
