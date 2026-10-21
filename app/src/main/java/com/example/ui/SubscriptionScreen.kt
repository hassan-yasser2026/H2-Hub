package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.example.network.*
import com.example.ui.theme.AccentColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

const val VODAFONE_CASH_NUMBER = "01067254988"

private fun statusLabel(status: String) = when (status) {
    "active", "approved", "paid" -> "مدفوع"
    "free" -> "مجاني"
    "pending" -> "قيد المراجعة"
    "rejected" -> "مرفوض"
    "expired" -> "منتهي"
    "awaiting_payment", "created" -> "بانتظار الدفع"
    "" -> "غير مشترك"
    else -> status
}

private fun planLabel(plan: String) = when (plan) {
    "trial" -> "تجريبي"
    "basic" -> "أساسي"
    "premium" -> "مميز"
    "annual" -> "سنوي"
    "annual_premium" -> "سنوي مميز"
    else -> SubscriptionPlan.fromApi(plan)?.titleAr ?: plan.ifBlank { "—" }
}

@Composable
fun SubscriptionScreen(accentColor: Color = AccentColors.Subscription) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { SubscriptionApiClient() }

    var status by remember { mutableStateOf<SubscriptionStatus?>(null) }
    var plans by remember { mutableStateOf<List<ServerSubscriptionPlan>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var phone by rememberSaveable { mutableStateOf("") }
    var payment by remember { mutableStateOf<PaymentCode?>(null) }
    var screenshotUploaded by remember { mutableStateOf(false) }
    var pickedName by remember { mutableStateOf<String?>(null) }

    fun deviceId() = SubscriptionDeviceId.get(context)

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                val loadedPlans = api.fetchPlans()
                if (loadedPlans.isEmpty()) throw IOException("لم يعرض الخادم أي خطط اشتراك.")
                plans = loadedPlans
                val s = api.status(deviceId())
                status = s
                if (phone.isBlank()) phone = s.user.vodafoneNumber
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "تعذر تحميل حالة الاشتراك."
            } finally {
                loading = false
            }
        }
    }

    fun runAction(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            info = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "حدث خطأ غير متوقع."
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val current = payment ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        runAction {
            val (bytes, mime) = SubscriptionApiClient.readImage(context, uri)
            api.uploadScreenshot(deviceId(), current.requestId, bytes, mime)
            screenshotUploaded = true
            pickedName = "تم رفع الصورة"
            info = "تم رفع صورة التحويل بنجاح."
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val current = status
    val pendingRequest = current?.requests?.firstOrNull { it.status == "pending" }
    val latestRejected = current?.requests?.firstOrNull()?.takeIf { it.status == "rejected" }

    val code = payment
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HubSectionHeading(
                title = "الاشتراك",
                description = "إدارة خطتك وحالة اشتراكك.",
                accentColor = accentColor
            )
        }
        item {
            HubCard(modifier = Modifier.fillMaxWidth(), accentColor = accentColor) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("رقم فودافون كاش للدفع", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    PaymentCopyValue(
                        label = "رقم فودافون كاش",
                        value = VODAFONE_CASH_NUMBER,
                        onCopy = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("رقم فودافون كاش", VODAFONE_CASH_NUMBER))
                            Toast.makeText(context, "تم نسخ رقم فودافون كاش", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { refresh() }, enabled = !loading) { Text("إعادة المحاولة") }
                    }
                }
            }
        }
        info?.let { message -> item { Text(message, color = accentColor) } }
        current?.let { s ->
            item {
                HubCard(modifier = Modifier.fillMaxWidth(), accentColor = accentColor) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("حالة الاشتراك: ${statusLabel(s.subscription.status)}", fontWeight = FontWeight.Bold)
                        if (s.user.userCode.isNotBlank()) Text("كود المستخدم: ${s.user.userCode}")
                        Text("الخطة: ${planLabel(s.subscription.plan)}")
                        if (s.subscription.endDate.isNotBlank()) Text("ينتهي في: ${s.subscription.endDate}")
                        if (s.subscription.status in setOf("active", "approved", "paid")) {
                            Text("الأيام المتبقية: ${s.subscription.daysRemaining}")
                        }
                    }
                }
            }
        }
        pendingRequest?.let { request ->
            item {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Text(
                        "طلبك (${planLabel(request.plan)} - ${request.amount} جنيه) قيد المراجعة. سيتم تفعيل الاشتراك بعد الموافقة.",
                        Modifier.padding(14.dp)
                    )
                }
            }
        }
        latestRejected?.let { request ->
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "تم رفض آخر طلب" + if (request.rejectionReason.isNotBlank()) ": ${request.rejectionReason}" else ".",
                        Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        if (code == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                        label = { Text("رقم فودافون كاش") },
                        singleLine = true,
                        enabled = pendingRequest == null && !busy,
                        isError = phone.isNotEmpty() && !isValidVodafoneNumber(phone),
                        supportingText = { Text("رقم مصري من 11 رقم يبدأ بـ 01") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("اختر خطتك", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            items(plans, key = { it.name }) { plan ->
                PlanCard(
                    plan = plan,
                    accentColor = accentColor,
                    enabled = pendingRequest == null && !busy && !loading && isValidVodafoneNumber(phone),
                    onSubscribe = {
                        runAction {
                            val id = deviceId()
                            api.register(id, phone.trim())
                            payment = api.generateCode(id, plan.name)
                            screenshotUploaded = false
                            pickedName = null
                        }
                    }
                )
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val copyPaymentValue: (String, String) -> Unit = { label, value ->
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                        Toast.makeText(context, "تم نسخ $label", Toast.LENGTH_SHORT).show()
                    }
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text("💰 خطوات الدفع", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("1. حوّل مبلغ ${code.amount} جنيه على رقم فودافون كاش:")
                            PaymentCopyValue(
                                label = "رقم فودافون كاش",
                                value = VODAFONE_CASH_NUMBER,
                                onCopy = { copyPaymentValue("رقم فودافون كاش", VODAFONE_CASH_NUMBER) }
                            )
                            Text("2. اكتب كود الطلب في ملاحظة التحويل:")
                            PaymentCopyValue(
                                label = "كود الطلب",
                                value = code.uniqueCode,
                                onCopy = { copyPaymentValue("كود الطلب", code.uniqueCode) }
                            )
                            Text("3. ارفع لقطة شاشة للتحويل.")
                            Text("4. اضغط «إرسال الطلب».")
                        }
                    }
                    OutlinedButton(
                        onClick = { picker.launch("image/*") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().smartCatActionHeight()
                    ) { Text(if (screenshotUploaded) "تغيير صورة التحويل" else "اختيار صورة التحويل") }
                    pickedName?.let { Text(it) }
                    Button(
                        onClick = {
                            runAction {
                                api.submitRequest(deviceId(), code.requestId, phone.trim())
                                payment = null
                                screenshotUploaded = false
                                pickedName = null
                                info = "تم إرسال الطلب وهو الآن قيد المراجعة."
                                status = api.status(deviceId())
                            }
                        },
                        enabled = !busy && screenshotUploaded,
                        modifier = Modifier.fillMaxWidth().smartCatActionHeight()
                    ) { Text("إرسال الطلب") }
                    TextButton(
                        onClick = { payment = null; screenshotUploaded = false; pickedName = null },
                        enabled = !busy
                    ) { Text("إلغاء") }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        current?.requests?.takeIf { it.isNotEmpty() }?.let { requests ->
            item { Text("طلباتي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(requests, key = { it.id }) { request ->
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${planLabel(request.plan)} • ${request.amount} جنيه • ${statusLabel(request.status)}")
                        Text("الكود: ${request.uniqueCode}", style = MaterialTheme.typography.bodySmall)
                        if (request.status == "rejected" && request.rejectionReason.isNotBlank()) {
                            Text("السبب: ${request.rejectionReason}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { refresh() },
                enabled = !loading && !busy,
                modifier = Modifier.fillMaxWidth().smartCatActionHeight()
            ) { Text("تحديث الحالة") }
        }
    }
}

@Composable
private fun PlanCard(
    plan: ServerSubscriptionPlan,
    accentColor: Color,
    enabled: Boolean,
    onSubscribe: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(2.dp, accentColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(plan.nameAr, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("%,d".format(plan.price), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("جنيه", Modifier.padding(bottom = 8.dp))
            }
            Text("المدة: ${plan.durationDays} يوم", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%,d رسالة".format(plan.messages), fontWeight = FontWeight.SemiBold, color = accentColor)
            val features = buildList {
                add("المحادثة")
                if (plan.features["search"] == true) add("البحث")
                if (plan.features["thinking"] == true) add("التفكير")
                if (plan.features["images"] == true) add("توليد الصور")
                if (plan.features["vision"] == true) add("تحليل الصور")
                if (plan.features["priority"] == true) add("أولوية")
                if (plan.features["support"] == true) add("دعم فني")
            }
            features.forEach { feature ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = accentColor)
                    Text(feature)
                }
            }
            Button(
                onClick = onSubscribe,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().smartCatActionHeight()
            ) { Text("اشترك الآن") }
        }
    }
}

@Composable
private fun PaymentCopyValue(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Card(
        onClick = onCopy,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text("اضغط للنسخ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "نسخ $label",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}