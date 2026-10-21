package com.example.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.network.FeatureUsage
import com.example.network.UsageSnapshot
import kotlinx.coroutines.delay

data class UsageState(
    val snapshot: UsageSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null
) {
    fun withExhausted(feature: String, used: Int, limit: Int): UsageState {
        val current = snapshot ?: UsageSnapshot(paid = false, features = emptyMap())
        val boundedUsage = FeatureUsage(maxOf(used, limit), limit)
        val subscription = current.subscription?.let {
            it.copy(
                used = boundedUsage.used,
                limit = boundedUsage.limit,
                remaining = boundedUsage.remaining
            )
        }
        val updated = current.copy(
            features = current.features + (feature to boundedUsage),
            subscription = subscription
        )
        return copy(snapshot = updated)
    }
}

enum class UsageLevel { NORMAL, WARNING, CRITICAL, EXHAUSTED }

fun requiredMessageQuota(
    webSearch: Boolean = false,
    thinking: Boolean = false,
    vision: Boolean = false,
    imageGeneration: Boolean = false
): Int = when {
    vision -> 3
    imageGeneration -> 20
    webSearch && thinking -> 3
    webSearch || thinking -> 2
    else -> 1
}

data class UsageBannerModel(
    val text: String,
    val warning: Boolean,
    val exhausted: Boolean,
    val level: UsageLevel = UsageLevel.NORMAL
)

private fun usageLevel(remaining: Int, limit: Int): UsageLevel = when {
    remaining <= 0 -> UsageLevel.EXHAUSTED
    limit > 0 && remaining * 100L <= limit * 5L -> UsageLevel.CRITICAL
    limit > 0 && remaining * 100L <= limit * 20L -> UsageLevel.WARNING
    else -> UsageLevel.NORMAL
}

fun usageBanner(state: UsageState, feature: String = "chat"): UsageBannerModel? {
    val snapshot = state.snapshot ?: return null
    if (snapshot.legacy) {
        return UsageBannerModel("بدون حدود ✨", warning = false, exhausted = false)
    }

    val subscription = snapshot.subscription
    if (subscription != null) {
        val level = usageLevel(subscription.remaining, subscription.limit)
        val text = when (level) {
            UsageLevel.CRITICAL -> "🔴 باقي ${subscription.remaining} رسائل. جدّد الآن!"
            UsageLevel.WARNING -> "⚠️ باقي ${subscription.remaining} رسائل فقط"
            UsageLevel.EXHAUSTED -> "استهلكت كل رسائلك. جدّد اشتراكك أو ارتقِ لخطة أعلى."
            UsageLevel.NORMAL -> "رصيدك: ${subscription.remaining} رسالة"
        }
        return UsageBannerModel(text, level != UsageLevel.NORMAL, level == UsageLevel.EXHAUSTED, level)
    }

    val quota: FeatureUsage = snapshot.feature(feature) ?: return null
    val level = usageLevel(quota.remaining, quota.limit)
    val text = when (level) {
        UsageLevel.CRITICAL -> "🔴 باقي ${quota.remaining} رسائل. جدّد الآن!"
        UsageLevel.WARNING -> "⚠️ باقي ${quota.remaining} رسائل فقط"
        UsageLevel.EXHAUSTED -> "استهلكت كل رسائلك. جدّد اشتراكك أو ارتقِ لخطة أعلى."
        UsageLevel.NORMAL -> "مجاني: ${quota.remaining}/${quota.limit} اليوم"
    }
    return UsageBannerModel(text, level != UsageLevel.NORMAL, level == UsageLevel.EXHAUSTED, level)
}

@Composable
fun UsageBannerCard(
    state: UsageState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    feature: String = "chat",
    onUpgrade: () -> Unit = {}
) {
    val banner = usageBanner(state, feature)
    val level = banner?.level
    val container = when (level) {
        UsageLevel.WARNING -> Color(0xFF5C4718)
        UsageLevel.CRITICAL, UsageLevel.EXHAUSTED -> Color(0xFF5A2929)
        UsageLevel.NORMAL -> Color(0xFF174A36)
        null -> if (state.error != null) Color(0xFF5A2929) else MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (level) {
        UsageLevel.WARNING -> Color(0xFFFFE7A3)
        UsageLevel.CRITICAL, UsageLevel.EXHAUSTED -> Color(0xFFFFCACA)
        UsageLevel.NORMAL -> Color(0xFFD2F5DF)
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val text = when {
                banner != null -> banner.text
                state.loading -> "جاري تحميل رصيد الرسائل..."
                else -> state.error ?: "رصيد الرسائل غير متاح"
            }
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (banner?.exhausted == true) {
                TextButton(onClick = onUpgrade) {
                    Text("ترقية", color = content, style = MaterialTheme.typography.labelMedium)
                }
            } else if (state.snapshot == null && !state.loading) {
                TextButton(onClick = onRefresh) {
                    Text("إعادة", color = content, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

data class UsageIndicatorModel(
    val icon: UsageIndicatorIcon,
    val value: String,
    val level: UsageLevel,
    val remaining: Int,
    val total: Int,
    val planName: String,
    val expiresAt: String
)

enum class UsageIndicatorIcon { LEGACY, PAID, FREE }

fun usageIndicatorModel(snapshot: UsageSnapshot?, feature: String = "chat"): UsageIndicatorModel? {
    snapshot ?: return null
    if (snapshot.legacy) {
        return UsageIndicatorModel(
            icon = UsageIndicatorIcon.LEGACY,
            value = "∞",
            level = UsageLevel.NORMAL,
            remaining = 0,
            total = 0,
            planName = "اشتراك قديم غير محدود",
            expiresAt = ""
        )
    }

    snapshot.subscription?.let { subscription ->
        val level = usageLevel(subscription.remaining, subscription.limit)
        return UsageIndicatorModel(
            icon = UsageIndicatorIcon.PAID,
            value = subscription.remaining.toString(),
            level = level,
            remaining = subscription.remaining,
            total = subscription.limit,
            planName = subscription.nameAr.ifBlank { subscription.plan },
            expiresAt = subscription.expiresAt
        )
    }

    val usage = snapshot.feature(feature) ?: return null
    return UsageIndicatorModel(
        icon = UsageIndicatorIcon.FREE,
        value = "${usage.remaining}/${usage.limit}",
        level = usageLevel(usage.remaining, usage.limit),
        remaining = usage.remaining,
        total = usage.limit,
        planName = "مجاني",
        expiresAt = ""
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageIndicatorCompact(
    state: UsageState,
    onRefresh: () -> Unit,
    feature: String = "chat",
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDetails by remember { mutableStateOf(false) }
    val model = usageIndicatorModel(state.snapshot, feature)
    val transition = rememberInfiniteTransition(label = "usage-indicator-pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (model?.level == UsageLevel.CRITICAL) 0.65f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "usage-indicator-pulse-alpha"
    )
    val container = when (model?.level) {
        UsageLevel.WARNING -> Color(0xFFF59E0B)
        UsageLevel.CRITICAL -> Color(0xFFEF4444)
        UsageLevel.EXHAUSTED -> Color(0xFF64748B)
        UsageLevel.NORMAL -> if (model.icon == UsageIndicatorIcon.FREE) Color(0xFF3B82F6) else Color(0xFF10B981)
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    val indicatorContent = if (model == null) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Color.White
    }

    Surface(
        onClick = {
            if (model == null) onRefresh()
            else showDetails = true
        },
        modifier = modifier.graphicsLayer {
            alpha = if (model?.level == UsageLevel.CRITICAL) pulseAlpha else 1f
        },
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = indicatorContent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (model?.icon) {
                UsageIndicatorIcon.LEGACY -> Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = "رصيد غير محدود",
                    modifier = Modifier.size(14.dp),
                    tint = indicatorContent
                )
                UsageIndicatorIcon.PAID -> Icon(
                    Icons.Default.Diamond,
                    contentDescription = "رصيد الاشتراك",
                    modifier = Modifier.size(14.dp),
                    tint = indicatorContent
                )
                UsageIndicatorIcon.FREE, null -> Icon(
                    Icons.Default.Star,
                    contentDescription = "الرصيد اليومي",
                    modifier = Modifier.size(14.dp),
                    tint = indicatorContent
                )
            }
            Text(
                text = model?.value ?: if (state.loading) "…" else "!",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = indicatorContent
            )
        }
    }

    if (showDetails && model != null) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("رصيد الرسائل", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (model.icon == UsageIndicatorIcon.LEGACY) {
                        "الرصيد: غير محدود"
                    } else {
                        "الرصيد المتبقي: ${model.remaining} من ${model.total}"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text("نوع الاشتراك: ${model.planName}")
                when {
                    model.expiresAt.isNotBlank() ->
                        Text("تاريخ الانتهاء: ${model.expiresAt.substringBefore('T')}")
                    model.icon == UsageIndicatorIcon.FREE ->
                        Text("تتجدد الرسائل اليومية عند منتصف الليل بتوقيت مصر.")
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        showDetails = false
                        onUpgrade()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            model.icon == UsageIndicatorIcon.LEGACY -> "تجديد الاشتراك"
                            model.level == UsageLevel.EXHAUSTED -> "ترقية أو تجديد"
                            else -> "ترقية"
                        }
                    )
                }
                TextButton(onClick = { showDetails = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("إغلاق")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            onRefresh()
        }
    }
}
