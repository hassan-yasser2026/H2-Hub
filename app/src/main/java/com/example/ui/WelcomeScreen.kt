package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.auth.AuthManager
import com.example.auth.AuthState
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isShortScreen = configuration.screenHeightDp < 600
    val isWideScreen = configuration.screenWidthDp >= 600
    val logoSize = when {
        isShortScreen -> 88.dp
        isWideScreen -> 144.dp
        else -> 120.dp
    }
    val headingSize = if (isShortScreen) 24.sp else 28.sp
    val contentSpacing = if (isShortScreen) 16.dp else 24.dp
    val scope = rememberCoroutineScope()
    val state by AuthManager.state.collectAsState()
    val busy = state is AuthState.SigningIn

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1035), Color(0xFF3B1F7A), Color(0xFF1B1035))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (isWideScreen) 40.dp else 28.dp,
                    vertical = if (isShortScreen) 12.dp else 24.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.img_app_icon_1784292338011),
                contentDescription = "شعار H2 Hub",
                modifier = Modifier.size(logoSize)
            )
            Spacer(Modifier.height(contentSpacing))
            Text(
                "مرحباً بك في H2 Hub",
                color = Color.White,
                fontSize = headingSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text("مساعدك الذكي", color = Color(0xFFD1C4E9), fontSize = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(if (isShortScreen) 24.dp else 48.dp))

            Button(
                onClick = { scope.launch { AuthManager.signInWithGoogle(context) } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF1F1F1F),
                    disabledContainerColor = Color(0xFFE0E0E0),
                    disabledContentColor = Color(0xFF616161)
                )
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Color(0xFF3B1F7A))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(
                            painter = painterResource(R.drawable.ic_google_logo),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Text("المتابعة باستخدام Google", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            (state as? AuthState.Error)?.let {
                Spacer(Modifier.height(16.dp))
                Text(it.message, color = Color(0xFFFFB4AB), textAlign = TextAlign.Center, fontSize = 14.sp)
            }

            Spacer(Modifier.height(contentSpacing))
            Text(
                "بالمتابعة أنت توافق على الشروط وسياسة الخصوصية",
                color = Color(0xFF9E9E9E),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
