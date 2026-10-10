package com.jsons.roadmesh.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jsons.roadmesh.Question
import com.jsons.roadmesh.copilot.CopilotState

/** Push-to-talk speech recognition, on-device when the phone offers it. Returns (listening, partial text, start). */
@Composable fun rememberVoice(onText: (String) -> Unit): Triple<Boolean, String, () -> Unit> {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    val recognizer = remember {
        runCatching {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; partial = "" }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) { listening = false; partial = if (error == SpeechRecognizer.ERROR_NO_MATCH) "Didn't catch that. Try again." else "" }
            override fun onResults(results: Bundle?) {
                listening = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = ""; onText(it) }
            }
            override fun onPartialResults(results: Bundle?) { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it } }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { recognizer?.destroy() }
    }
    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        if (listening) recognizer?.stopListening() else recognizer?.startListening(intent)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) listen() else partial = "Microphone permission is needed to ask by voice." }
    val start = {
        if (recognizer == null) partial = "Speech recognition is not available on this phone. Type the question instead."
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    return Triple(listening, partial, start)
}

private val suggestions = listOf("Is it safe to overtake now?", "What's ahead of me?", "Should I slow down?")

/**
 * Ask the co-pilot by voice or text. Answers come from this phone's own camera and the warnings it holds; an
 * overtaking question shows the rule's verdict at once and the co-pilot's wording as it streams in.
 */
@Composable fun AskPanel(questions: List<Question>, copilot: CopilotState, ask: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val (listening, partial, listen) = rememberVoice { ask(it) }
    Surface(shape = RoundedCornerShape(20.dp), color = Tokens.Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ASK THE CO-PILOT", fontFamily = Michroma, fontSize = 11.sp, letterSpacing = 1.sp, color = Tokens.Yellow)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s ->
                    Surface(onClick = { ask(s) }, shape = RoundedCornerShape(12.dp), color = Tokens.Raised, border = BorderStroke(1.dp, Tokens.Line)) {
                        Text(s, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Type a question", color = Tokens.Faint) }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { ask(text); text = "" }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Tokens.Yellow, unfocusedBorderColor = Tokens.Outline, cursorColor = Tokens.Yellow))
                FilledIconButton(onClick = listen, modifier = Modifier.size(56.dp).semantics { contentDescription = if (listening) "Stop listening" else "Ask by voice" },
                    shape = RoundedCornerShape(14.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (listening) Tokens.Red else Tokens.Yellow)) {
                    MicGlyph(Tokens.Ink)
                }
            }
            if (listening || partial.isNotEmpty()) Text(if (listening && partial.isEmpty()) "Listening…" else partial, color = Tokens.Secondary, fontSize = 15.sp)
            questions.firstOrNull()?.let { q -> Answer(q, copilot) }
        }
    }
}

@Composable private fun Answer(q: Question, copilot: CopilotState) {
    val line = copilot.lines[q.tag]
    Column(Modifier.fillMaxWidth().background(Tokens.Ink, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("“${q.text}”", color = Tokens.Muted, fontSize = 15.sp)
        q.notNow?.let { notNow ->
            Text(if (notNow) "NOT NOW" else "NOTHING SEEN AGAINST IT", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, letterSpacing = 0.5.sp,
                color = Tokens.Ink, modifier = Modifier.background(if (notNow) Tokens.Red else Tokens.Green, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        when {
            line != null && line.text.isNotEmpty() -> Text(line.text + if (line.done) "" else " ▍", fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium)
            line != null -> Text("Thinking…", color = Tokens.Yellow, fontSize = 15.sp)
            q.verdict != null -> Text(q.verdict, fontSize = 17.sp, lineHeight = 24.sp)
            !copilot.ready -> Text("The co-pilot model is not installed on this phone, so only rule-based answers are available.", color = Tokens.Faint, fontSize = 14.sp)
        }
        if (q.verdict != null && line?.text?.isNotEmpty() == true) Text("Rule check: ${q.verdict}", color = Tokens.Faint, fontSize = 13.sp, lineHeight = 18.sp)
        Text("Answered on this phone from its own camera and the mesh. It cannot see everything: check your mirrors.", color = Tokens.Faint, fontSize = 12.sp)
    }
}

@Composable private fun MicGlyph(color: androidx.compose.ui.graphics.Color) = Canvas(Modifier.size(24.dp)) {
    val w = size.width
    drawRoundRect(color, Offset(0.34f * w, 0.06f * w), Size(0.32f * w, 0.52f * w), CornerRadius(0.16f * w))
    drawArc(color, 0f, 180f, false, Offset(0.2f * w, 0.24f * w), Size(0.6f * w, 0.5f * w), style = Stroke(0.08f * w))
    drawLine(color, Offset(0.5f * w, 0.74f * w), Offset(0.5f * w, 0.92f * w), strokeWidth = 0.08f * w)
}
