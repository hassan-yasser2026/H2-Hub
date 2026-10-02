package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppUi
import com.example.ui.AppViewModel
import com.example.ui.theme.MyApplicationTheme
import com.google.android.gms.ads.MobileAds
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) PushNotifications.registerCurrentToken(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PushNotifications.createChannel(this)

        // AdMob init is heavy; run off the main thread as Google recommends
        thread { MobileAds.initialize(this@MainActivity) }

        // The mic is needed for Quran recitation analysis and voice input;
        // without this runtime request both features fail silently.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_REQUEST_CODE)
        }

        setContent {
            val viewModel: AppViewModel = viewModel()
            val themeMode by viewModel.appTheme.collectAsState()
            MyApplicationTheme(themeMode = themeMode) {
                AppUi(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PushNotifications.registerCurrentToken(this)
        requestPushPermissionIfNeeded()
    }

    private fun requestPushPermissionIfNeeded() {
        if (!PushNotifications.isFirebaseConfigured(this)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!PushNotifications.wasPermissionRequested(this)) {
            PushNotifications.markPermissionRequested(this)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 1001
    }
}
