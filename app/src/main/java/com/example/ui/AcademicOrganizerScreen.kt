package com.example.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.NotificationScheduler
import com.example.data.Lesson
import com.example.data.StudySession
import com.example.ui.theme.AccentColors
import java.util.Calendar
import java.util.Locale
import java.util.UUID

@Composable
fun AcademicOrganizerScreen(viewModel: AppViewModel) {
    val schedule by viewModel.userSchedule.collectAsState()
    val sessions by viewModel.studySessions.collectAsState()
    val lessons by viewModel.lessons.collectAsState()
    val isGenerating by viewModel.isGeneratingSchedule.collectAsState()
    val generationError by viewModel.organizerGenerationError.collectAsState()
    val reminderLead by viewModel.organizerReminderLeadMinutes.collectAsState()
    val remindersPaused by viewModel.organizerRemindersPaused.collectAsState()
    val context = LocalContext.current
    var remindersEnabled by remember {
        mutableStateOf(
            NotificationScheduler.isEnabled(
                context,
                NotificationScheduler.KEY_STUDY_ENABLED,
                false
            )
        )
    }
    val reminderPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            remindersEnabled = true
            viewModel.setNotificationEnabled(NotificationScheduler.KEY_STUDY_ENABLED, true)
        } else {
            Toast.makeText(context, "اسمح بالإشعارات لتفعيل تنبيهات المذاكرة.", Toast.LENGTH_SHORT).show()
        }
    }
    var weekly by rememberSaveable { mutableStateOf(false) }
    var showGenerator by rememberSaveable { mutableStateOf(false) }
    var creatingSession by remember { mutableStateOf(false) }
    var editingSession by remember { mutableStateOf<StudySession?>(null) }
    var creatingLesson by remember { mutableStateOf(false) }
    var editingLesson by remember { mutableStateOf<Lesson?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var age by rememberSaveable { mutableStateOf("") }
    var occupation by rememberSaveable { mutableStateOf("student") }
    var schoolHours by rememberSaveable { mutableStateOf("") }
    var lessonHours by rememberSaveable { mutableStateOf("") }

    val today = Calendar.getInstance()
    val todayDay = today.get(Calendar.DAY_OF_WEEK)
    val todayDate = dateToday()
    val dayNames = listOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
    val todaySessions = sessions.filter {
        (it.repeatsWeekly && it.day == todayDay) || (!it.repeatsWeekly && it.date == todayDate)
    }.sortedBy(StudySession::startTime)
    val visibleSessions = if (weekly) sessions.filter(StudySession::repeatsWeekly) else todaySessions
    val minutesPlanned = visibleSessions.sumOf(StudySession::duration)
    val completed = visibleSessions.count(StudySession::completed)
    val adherence = if (visibleSessions.isEmpty()) 0 else completed * 100 / visibleSessions.size

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TabRow(selectedTabIndex = if (weekly) 1 else 0) {
            Tab(selected = !weekly, onClick = { weekly = false }, text = { Text("يومي") })
            Tab(selected = weekly, onClick = { weekly = true }, text = { Text("أسبوعي") })
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HubSectionHeading(
                        title = if (weekly) "الجدول الأكاديمي الأسبوعي" else "جدول اليوم الدراسي",
                        description = if (weekly) "من الأحد إلى السبت" else "يوم ${dayNames[todayDay - 1]}",
                        modifier = Modifier.weight(1f),
                        accentColor = AccentColors.Organizer
                    )
                    Row {
                        IconButton(onClick = {
                            val text = buildString {
                                appendLine(if (weekly) "الجدول الأكاديمي الأسبوعي" else "جدول اليوم الدراسي")
                                if (weekly) {
                                    dayNames.forEachIndexed { index, label ->
                                        val daySessions = sessions.filter {
                                            it.repeatsWeekly && it.day == index + 1
                                        }.sortedBy(StudySession::startTime)
                                        if (daySessions.isNotEmpty()) {
                                            appendLine()
                                            appendLine(label)
                                            daySessions.forEach {
                                                appendLine("• ${formatTime(it.startTime)} - ${it.subject} (${it.duration} دقيقة)")
                                            }
                                        }
                                    }
                                } else {
                                    todaySessions.forEach {
                                        appendLine("• ${formatTime(it.startTime)} - ${it.subject} (${it.duration} دقيقة)")
                                    }
                                }
                            }.trim()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text.ifBlank { "لم تتم إضافة جلسات للجدول بعد." })
                            }
                            context.startActivity(Intent.createChooser(intent, "مشاركة الجدول الأكاديمي"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "مشاركة الجدول")
                        }
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "حذف الجدول", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("تذكيرات المذاكرة والدروس", fontWeight = FontWeight.Bold)
                                Text(
                                    if (remindersEnabled) "التذكيرات مفعّلة" else "فعّلها لاستقبال التنبيهات",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = remindersEnabled,
                                onCheckedChange = {
                                    if (
                                        it &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        reminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        remindersEnabled = it
                                        viewModel.setNotificationEnabled(NotificationScheduler.KEY_STUDY_ENABLED, it)
                                    }
                                }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("قبل المذاكرة:", modifier = Modifier.weight(1f))
                            listOf(5, 10, 15).forEach { minutes ->
                                FilterChip(
                                    selected = reminderLead == minutes,
                                    onClick = { viewModel.setOrganizerReminderLeadMinutes(minutes) },
                                    label = { Text("$minutes د") },
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("إيقاف التنبيهات مؤقتاً")
                            Switch(
                                checked = remindersPaused,
                                enabled = remindersEnabled,
                                onCheckedChange = viewModel::setOrganizerRemindersPaused
                            )
                        }
                    }
                }
            }
            item {
                Card(onClick = { creatingSession = true }) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("إضافة جلسة مذاكرة", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (weekly) {
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dayNames.forEachIndexed { index, dayName ->
                            val day = index + 1
                            val daySessions = sessions.filter { it.repeatsWeekly && it.day == day }
                                .sortedBy(StudySession::startTime)
                            Card(
                                modifier = Modifier.fillMaxWidth(0.48f),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                )
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(dayName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    if (daySessions.isEmpty()) {
                                        Text("لا توجد جلسات", style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        daySessions.forEach { session ->
                                            SessionCard(
                                                session,
                                                { viewModel.markStudySessionCompleted(session, it) },
                                                { editingSession = session },
                                                { viewModel.deleteStudySession(session.id) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (todaySessions.isEmpty()) {
                item {
                    Card {
                        Text(
                            "لا توجد جلسات لهذا اليوم بعد. أضف جلسة أو أنشئ خطة بالذكاء الاصطناعي.",
                            Modifier.fillMaxWidth().padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                item {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            todaySessions.forEach { session ->
                                SessionCard(
                                    session,
                                    { viewModel.markStudySessionCompleted(session, it) },
                                    { editingSession = session },
                                    { viewModel.deleteStudySession(session.id) }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("إحصائيات ${if (weekly) "الأسبوع" else "اليوم"}", fontWeight = FontWeight.Bold)
                        Text("ساعات المذاكرة المخططة: ${"%.1f".format(Locale.US, minutesPlanned / 60f)}")
                        Text("عدد الدروس الأسبوعية: ${lessons.size}")
                        Text("نسبة الالتزام: $adherence% ($completed / ${visibleSessions.size} جلسة مكتملة)")
                    }
                }
            }
            item {
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("دروسي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { creatingLesson = true }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Text("إضافة درس")
                            }
                        }
                        if (lessons.isEmpty()) {
                            Text("أضف دروسك الأسبوعية لتصلك تنبيهات قبلها بـ 15 دقيقة.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            lessons.sortedWith(compareBy(Lesson::dayOfWeek, Lesson::time)).forEach { lesson ->
                                LessonItem(
                                    lesson,
                                    dayNames[lesson.dayOfWeek - 1],
                                    { editingLesson = lesson },
                                    { viewModel.deleteLesson(lesson.id) }
                                )
                            }
                        }
                    }
                }
            }
            item {
                OutlinedCard(onClick = { showGenerator = !showGenerator }) {
                    Text(
                        if (showGenerator) "إخفاء إنشاء الخطة بالذكاء الاصطناعي" else "إنشاء خطة بالذكاء الاصطناعي",
                        Modifier.fillMaxWidth().padding(14.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (showGenerator) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = age,
                            onValueChange = { age = it },
                            label = { Text("العمر") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("student" to "طالب", "employee" to "موظف", "both" to "الاثنان").forEach { (key, label) ->
                                FilterChip(
                                    selected = occupation == key,
                                    onClick = { occupation = key },
                                    label = { Text(label) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = schoolHours,
                            onValueChange = { schoolHours = it },
                            label = { Text("مواعيد المدرسة أو العمل") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = lessonHours,
                            onValueChange = { lessonHours = it },
                            label = { Text("مواعيد الدروس والمواد") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                viewModel.generateDailySchedule(
                                    age.toIntOrNull() ?: 20,
                                    occupation,
                                    schoolHours,
                                    lessonHours,
                                    weekly
                                )
                            },
                            modifier = Modifier.fillMaxWidth().smartCatActionHeight(),
                            enabled = !isGenerating && age.isNotBlank()
                        ) {
                            Text(if (isGenerating) "جارٍ إنشاء الجدول..." else if (weekly) "إنشاء جدول أسبوعي" else "إنشاء جدول اليوم")
                        }
                        generationError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            schedule?.let { savedSchedule ->
                item {
                    OutlinedCard {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("الخطة الإرشادية المحفوظة", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.size(8.dp))
                            Text(savedSchedule.scheduleText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("حذف الجدول؟") },
            text = { Text("سيتم حذف الخطة المحفوظة وجلسات المذاكرة من هذا الجهاز.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearSchedule()
                    viewModel.clearAcademicSchedule()
                    showDeleteConfirmation = false
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("إلغاء") } }
        )
    }
    if (creatingSession || editingSession != null) {
        SessionEditorDialog(
            existing = editingSession,
            weekly = editingSession?.repeatsWeekly ?: weekly,
            todayDay = todayDay,
            reminderMinutes = reminderLead,
            onDismiss = { creatingSession = false; editingSession = null },
            onSave = { viewModel.saveStudySession(it); creatingSession = false; editingSession = null }
        )
    }
    if (creatingLesson || editingLesson != null) {
        LessonEditorDialog(
            existing = editingLesson,
            defaultDay = todayDay,
            onDismiss = { creatingLesson = false; editingLesson = null },
            onSave = { viewModel.saveLesson(it); creatingLesson = false; editingLesson = null }
        )
    }
}

@Composable
private fun SessionCard(
    session: StudySession,
    onCompleted: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = listOf(
        Color(0xFFE3F2FD), Color(0xFFE8F5E9), Color(0xFFFFF3E0),
        Color(0xFFF3E5F5), Color(0xFFE0F7FA), Color(0xFFFFEBEE)
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors[(session.subject.hashCode() and Int.MAX_VALUE) % colors.size]
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = session.completed, onCheckedChange = onCompleted)
            Column(Modifier.weight(1f)) {
                Text("${formatTime(session.startTime)} • ${session.subject}", fontWeight = FontWeight.Bold)
                Text("${session.duration} دقيقة", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "تعديل الجلسة") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "حذف الجلسة") }
        }
    }
}

@Composable
private fun LessonItem(lesson: Lesson, dayName: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(lesson.name, fontWeight = FontWeight.SemiBold)
            Text("$dayName • ${formatTime(lesson.time)} • تذكير قبلها بـ 15 دقيقة", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "تعديل الدرس") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "حذف الدرس") }
    }
}

@Composable
private fun SessionEditorDialog(
    existing: StudySession?,
    weekly: Boolean,
    todayDay: Int,
    reminderMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (StudySession) -> Unit
) {
    val context = LocalContext.current
    var subject by remember(existing?.id) { mutableStateOf(existing?.subject.orEmpty()) }
    var start by remember(existing?.id) { mutableIntStateOf(existing?.startTime ?: 16 * 60) }
    var duration by remember(existing?.id) { mutableStateOf((existing?.duration ?: 50).toString()) }
    var day by remember(existing?.id) { mutableIntStateOf(existing?.day ?: todayDay) }
    var expanded by remember { mutableStateOf(false) }
    val days = listOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "إضافة جلسة مذاكرة" else "تعديل الجلسة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(subject, { subject = it }, label = { Text("المادة") }, singleLine = true)
                if (weekly) {
                    OutlinedButton(onClick = { expanded = true }) { Text(days[day - 1]) }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        days.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { day = index + 1; expanded = false }
                            )
                        }
                    }
                }
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute -> start = hour * 60 + minute }, start / 60, start % 60, false).show()
                }) { Text("وقت البداية: ${formatTime(start)}") }
                OutlinedTextField(
                    duration,
                    { duration = it.filter(Char::isDigit).take(3) },
                    label = { Text("المدة بالدقائق") },
                    singleLine = true
                )
                Text("التنبيه قبلها بـ $reminderMinutes دقائق", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = subject.isNotBlank() && (duration.toIntOrNull() ?: 0) in 15..240,
                onClick = {
                    onSave(
                        StudySession(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            subject = subject.trim(),
                            startTime = start,
                            duration = duration.toInt(),
                            day = if (weekly) day else todayDay,
                            reminderMinutes = reminderMinutes,
                            date = if (weekly) null else dateToday(),
                            repeatsWeekly = weekly,
                            completed = existing?.completed ?: false
                        )
                    )
                }
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun LessonEditorDialog(
    existing: Lesson?,
    defaultDay: Int,
    onDismiss: () -> Unit,
    onSave: (Lesson) -> Unit
) {
    val context = LocalContext.current
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var day by remember(existing?.id) { mutableIntStateOf(existing?.dayOfWeek ?: defaultDay) }
    var time by remember(existing?.id) { mutableIntStateOf(existing?.time ?: 17 * 60) }
    var expanded by remember { mutableStateOf(false) }
    val days = listOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "إضافة درس" else "تعديل الدرس") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("اسم الدرس") }, singleLine = true)
                OutlinedButton(onClick = { expanded = true }) { Text(days[day - 1]) }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    days.forEachIndexed { index, label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { day = index + 1; expanded = false }
                        )
                    }
                }
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute -> time = hour * 60 + minute }, time / 60, time % 60, false).show()
                }) { Text("موعد الدرس: ${formatTime(time)}") }
                Text("يتم إرسال التذكير قبل الموعد بـ 15 دقيقة.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(
                    Lesson(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        dayOfWeek = day,
                        time = time,
                        reminderMinutes = 15
                    )
                )
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

private fun formatTime(minutes: Int): String =
    "%02d:%02d".format(Locale.US, minutes / 60, minutes % 60)

private fun dateToday(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
