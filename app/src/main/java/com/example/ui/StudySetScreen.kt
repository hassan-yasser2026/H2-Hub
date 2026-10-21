package com.example.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.GeneratedStudySet
import com.example.data.StudySetMode
import java.util.UUID

@Composable
fun StudySetScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val studySet by viewModel.generatedStudySet.collectAsState()
    val isGenerating by viewModel.isGeneratingStudySet.collectAsState()
    val error by viewModel.studySetError.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                Text("رجوع")
            }
            Text(
                studySet?.title ?: "المراجعة التعليمية",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(56.dp))
        }

        if (isGenerating) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("جاري إعداد محتوى المراجعة...")
            }
        } else if (error != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = viewModel::retryStudySetGeneration,
                    modifier = Modifier.smartCatActionHeight()
                ) {
                    Icon(Icons.Default.Replay, contentDescription = null)
                    Text("حاول مرة أخرى")
                }
            }
        } else if (studySet == null) {
            Text("ابدأ من زر الاختبار أو كروت المراجعة أسفل إجابة Smart Cat.")
        } else {
            when (studySet?.mode) {
                StudySetMode.QUIZ -> QuizPlayer(
                    studySet = studySet!!,
                    onSaveResult = viewModel::saveQuizResult
                )
                StudySetMode.FLASHCARDS -> FlashcardPlayer(studySet!!)
                null -> Unit
            }
        }
    }
}

@Composable
private fun QuizPlayer(
    studySet: GeneratedStudySet,
    onSaveResult: (String, String, Int, Int) -> Unit
) {
    var questionIndex by remember(studySet.id) { mutableIntStateOf(0) }
    var selectedOption by remember(studySet.id, questionIndex) { mutableStateOf<Int?>(null) }
    var score by remember(studySet.id) { mutableIntStateOf(0) }
    var isComplete by remember(studySet.id) { mutableStateOf(false) }
    var attemptId by remember(studySet.id) { mutableStateOf(UUID.randomUUID().toString()) }
    val question = studySet.questions[questionIndex]

    if (isComplete) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Text("انتهى الاختبار", style = MaterialTheme.typography.headlineSmall)
            Text(
                "نتيجتك: $score من ${studySet.questions.size}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    questionIndex = 0
                    score = 0
                    isComplete = false
                    attemptId = UUID.randomUUID().toString()
                },
                modifier = Modifier.smartCatActionHeight()
            ) {
                Icon(Icons.Default.Replay, contentDescription = null)
                Text("أعد الاختبار")
            }
        }
        return
    }

    Text(
        "السؤال ${questionIndex + 1} من ${studySet.questions.size}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    LinearProgressIndicator(
        progress = { (questionIndex + 1).toFloat() / studySet.questions.size },
        modifier = Modifier.fillMaxWidth().smartCatActionHeight()
    )
    Text(question.question, style = MaterialTheme.typography.titleLarge, lineHeight = 30.sp)

    question.options.forEachIndexed { index, option ->
        val selected = selectedOption == index
        val isCorrect = index == question.answerIndex
        val containerColor = when {
            selected && isCorrect -> MaterialTheme.colorScheme.primaryContainer
            selected -> MaterialTheme.colorScheme.errorContainer
            selectedOption != null && isCorrect -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        Card(
            modifier = Modifier.fillMaxWidth().clickable(enabled = selectedOption == null) {
                selectedOption = index
                if (isCorrect) score++
            },
            colors = CardDefaults.cardColors(containerColor = containerColor),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                option,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }

    if (selectedOption != null) {
        Text(
            if (selectedOption == question.answerIndex) "إجابة صحيحة" else "إجابة غير صحيحة",
            color = if (selectedOption == question.answerIndex) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            fontWeight = FontWeight.Bold
        )
        Text(question.explanation, style = MaterialTheme.typography.bodyMedium)
    }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            if (questionIndex == studySet.questions.lastIndex) {
                isComplete = true
                onSaveResult(attemptId, studySet.title, score, studySet.questions.size)
            } else {
                questionIndex++
            }
        },
        enabled = selectedOption != null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (questionIndex == studySet.questions.lastIndex) "عرض النتيجة" else "السؤال التالي")
    }
}

@Composable
private fun FlashcardPlayer(studySet: GeneratedStudySet) {
    var cardIndex by remember(studySet.id) { mutableIntStateOf(0) }
    var isFlipped by remember(studySet.id, cardIndex) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        label = "flashcardFlip"
    )
    val card = studySet.cards[cardIndex]

    Text("كارت ${cardIndex + 1} من ${studySet.cards.size}", style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(
        progress = { (cardIndex + 1).toFloat() / studySet.cards.size },
        modifier = Modifier.fillMaxWidth()
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp)
            .clickable { isFlipped = !isFlipped }
            .graphicsLayer { rotationY = rotation },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Style, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                if (isFlipped) card.back else card.front,
                modifier = Modifier.graphicsLayer { rotationY = if (isFlipped) 180f else 0f },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                lineHeight = 32.sp
            )
            Spacer(Modifier.height(16.dp))
            Text("المس البطاقة لقلبها", style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(
            enabled = cardIndex > 0,
            onClick = { cardIndex--; isFlipped = false },
            modifier = Modifier.smartCatActionHeight()
        ) { Text("السابق") }
        OutlinedButton(
            enabled = cardIndex < studySet.cards.lastIndex,
            onClick = { cardIndex++; isFlipped = false },
            modifier = Modifier.smartCatActionHeight()
        ) { Text("التالي") }
    }
}
