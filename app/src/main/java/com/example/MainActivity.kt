package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import com.example.auth.AuthManager
import com.example.auth.AuthState
import com.example.ui.AppUi
import com.example.ui.WelcomeScreen
import com.example.ui.AppViewModel
import com.example.ui.theme.MyApplicationTheme
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.delay

@SuppressLint("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_NOTIFICATION_TARGET = "notification_target"
        const val EXTRA_CHAT_SESSION_ID = "notification_session_id"
        const val EXTRA_CHAT_JOB_ID = "notification_job_id"
        const val EXTRA_SPEAK_TEXT = "notification_speak_text"
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) PushNotifications.registerCurrentToken(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PushNotifications.createChannel(this)
        NotificationScheduler.initialize(this)
        PushNotifications.openNotificationIntent(intent)
        AuthManager.refreshState(this)

        MobileAds.initialize(this)

        setContent {
            val viewModel: AppViewModel = viewModel()
            val themeMode by viewModel.appTheme.collectAsState()
            val conversationSyncReady by viewModel.conversationSyncReady.collectAsState()
            MyApplicationTheme(themeMode = themeMode) {
                var showSplash by remember { mutableStateOf(true) }
                val logoScale by animateFloatAsState(
                    targetValue = if (showSplash) 1f else 0.85f,
                    animationSpec = tween(700),
                    label = "splashLogoScale"
                )
                LaunchedEffect(Unit) {
                    delay(2_200)
                    showSplash = false
                }
                LaunchedEffect(showSplash) {
                    if (!showSplash) requestPushPermissionIfNeeded()
                }
                val authState by AuthManager.state.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    when (authState) {
                        is AuthState.SignedIn -> if (conversationSyncReady) {
                            AppUi(viewModel = viewModel)
                        } else {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        AuthState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                        else -> WelcomeScreen()
                    }
                    AnimatedVisibility(
                        visible = showSplash,
                        enter = fadeIn(tween(250)),
                        exit = fadeOut(tween(500)) + scaleOut(tween(500))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.img_app_icon_1784292338011),
                                contentDescription = "شعار H2 Smart Hub",
                                modifier = Modifier.size(96.dp).scale(logoScale)
                            )
                            Text(
                                "H2 Smart Hub",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("رفيقك الذكي كل يوم", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PushNotifications.registerCurrentToken(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PushNotifications.openNotificationIntent(intent)
    }

    private fun requestPushPermissionIfNeeded() {
        if (!PushNotifications.isFirebaseConfigured(this)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            PushNotifications.registerCurrentToken(this)
            return
        }
        if (!PushNotifications.wasPermissionRequested(this)) {
            PushNotifications.markPermissionRequested(this)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
