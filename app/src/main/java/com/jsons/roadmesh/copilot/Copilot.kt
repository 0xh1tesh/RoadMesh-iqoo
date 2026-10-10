package com.jsons.roadmesh.copilot

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** One co-pilot answer. [done] is false while tokens are still streaming in. */
data class CopilotLine(val tag: String, val text: String, val done: Boolean, val ms: Long? = null)

data class CopilotState(
    val status: Status = Status.MISSING,
    val detail: String = "No language model on this phone",
    val lines: Map<String, CopilotLine> = emptyMap(),
    val latestScene: CopilotLine? = null,
    val calls: Int = 0,
    val importProgress: Float? = null,
    /** True when the RESILANE fine-tune is loaded rather than stock Gemma. */
    val tuned: Boolean = false,
    /** Answers the guard replaced with deterministic text. */
    val guarded: Int = 0,
) {
    enum class Status { MISSING, LOADING, READY, THINKING, FAILED }
    val ready get() = status == Status.READY || status == Status.THINKING
}

/** Spoken questions first, then warnings, then background scene commentary. */
enum class Priority { SCENE, EVENT, QUESTION }

/** "a car", "an auto-rickshaw": the article the co-pilot's facts and training data both use. */
fun withArticle(word: String) = (if (word.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an " else "a ") + word

/**
 * On-device co-pilot: Gemma 2B (MediaPipe LLM Inference, CPU, int4) turns structured facts (YOLO detections, a mesh
 * warning and this node's own inference, the combined road picture) into a short answer, streamed token by token. It
 * prefers the RESILANE fine-tune (tools/copilot) and falls back to stock Gemma 2B-it. It is never on the alert path:
 * the mesh speaks a caution first; a co-pilot line arrives later or not at all. Every finished answer passes a guard:
 * filler openers are cut, and an answer that calls something safe or uses a number absent from the facts is replaced by
 * the caller's deterministic text. One generation runs at a time. Each priority keeps only its newest waiting request.
 */
class Copilot(private val context: Context) {
    private val state = MutableStateFlow(CopilotState())
    val flow = state.asStateFlow()
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var engine: LlmInference? = null
    private class Request(val tag: String, val prompt: String, val facts: String, val fallback: String?, val priority: Priority, val done: ((String) -> Unit)?)
    private val waiting = sortedMapOf<Priority, Request>(compareByDescending { it })
    private var running = false

    private val modelDir get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    private val candidates get() = listOf(TUNED, MODEL).flatMap { listOf(File(modelDir, it), File(context.filesDir, "models/$it"), File("/data/local/tmp/llm/$it")) }

    /** Finds the model and loads it in the background. Safe to call repeatedly. */
    fun load() {
        if (engine != null || state.value.status == CopilotState.Status.LOADING) return
        val file = candidates.firstOrNull { it.canRead() && it.length() > 100_000_000 }
        if (file == null) {
            state.update { it.copy(status = CopilotState.Status.MISSING, detail = "Import $MODEL to turn on the co-pilot") }
            return
        }
        val tuned = file.name == TUNED
        state.update { it.copy(status = CopilotState.Status.LOADING, detail = if (tuned) "Loading RESILANE Gemma 2B…" else "Loading Gemma 2B…") }
        scope.launch {
            runCatching {
                val started = SystemClock.elapsedRealtime()
                LlmInference.createFromOptions(context, LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath).setMaxTokens(640).setMaxTopK(40).build()) to SystemClock.elapsedRealtime() - started
            }.onSuccess { (llm, ms) ->
                engine = llm
                state.update { it.copy(status = CopilotState.Status.READY, tuned = tuned, detail = "${if (tuned) "RESILANE Gemma 2B" else "Gemma 2B"} on CPU · loaded in ${"%.1f".format(ms / 1000.0)} s") }
                pump()
            }.onFailure { e ->
                state.update { it.copy(status = CopilotState.Status.FAILED, detail = "Model failed to load: ${e.message?.take(120)}") }
            }
        }
    }

    /** Copies a model the user picked (for example from Download/Office Kit) into app storage, then loads it. */
    fun import(uri: Uri, name: String? = null) {
        val target = if (name?.contains("resilane", ignoreCase = true) == true) TUNED else MODEL
        scope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: -1L
                modelDir.mkdirs()
                val tmp = File(modelDir, "$target.part")
                resolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20); var copied = 0L; var shown = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); copied += n
                            if (total > 0 && copied - shown > 16L shl 20) {
                                shown = copied; state.update { it.copy(importProgress = copied.toFloat() / total, detail = "Importing model… ${copied shr 20} of ${total shr 20} MB") }
                            }
                        }
                    }
                }
                check(tmp.length() > 100_000_000) { "That file is too small to be the model" }
                File(modelDir, target).delete()
                check(tmp.renameTo(File(modelDir, target))) { "Could not save the model" }
            }.onSuccess {
                state.update { it.copy(importProgress = null, status = CopilotState.Status.MISSING) }
                // A newly imported fine-tune replaces the stock model that may already be loaded.
                engine?.close(); engine = null
                withContext(Dispatchers.Main) { load() }
            }.onFailure { e -> state.update { it.copy(importProgress = null, status = CopilotState.Status.FAILED, detail = "Import failed: ${e.message}") } }
        }
    }

    /**
     * Queues a request. [facts] are the only things the model may use. [task] says what to produce. [done] receives the
     * final text on the main thread. Returns false when no model is loaded, so callers can fall back to plain text.
     */
    fun ask(tag: String, facts: String, priority: Priority, task: String = DEFAULT_TASK, fallback: String? = null, done: ((String) -> Unit)? = null): Boolean {
        if (engine == null) return false
        scope.launch {
            waiting[priority] = Request(tag, prompt(task, facts), facts, fallback, priority, done)
            pump()
        }
        return true
    }

    /** True when nothing is generating or waiting, so a background scene request would not delay anything. */
    val idle get() = !running && waiting.isEmpty()

    fun close() { scope.cancel(); engine?.close(); engine = null }

    private fun pump() {
        val llm = engine ?: return
        if (running || waiting.isEmpty()) return
        val next = waiting.remove(waiting.firstKey())!!
        running = true
        state.update { it.copy(status = CopilotState.Status.THINKING) }
        val started = SystemClock.elapsedRealtime()
        val text = StringBuilder()
        publish(CopilotLine(next.tag, "", false), next.priority)
        val future = llm.generateResponseAsync(next.prompt) { partial, finished ->
            scope.launch {
                text.append(partial)
                publish(CopilotLine(next.tag, clean(text.toString(), finished), finished, if (finished) SystemClock.elapsedRealtime() - started else null), next.priority)
            }
        }
        future.addListener({
            scope.launch {
                val raw = clean(runCatching { future.get() }.getOrElse { e -> text.ifEmpty { "(co-pilot error: ${e.message?.take(80)})" }.toString() }, true)
                val final = guard(raw, next.facts, next.fallback)
                if (final != raw) state.update { it.copy(guarded = it.guarded + 1) }
                val ms = SystemClock.elapsedRealtime() - started
                publish(CopilotLine(next.tag, final, true, ms), next.priority)
                state.update { it.copy(status = CopilotState.Status.READY, calls = it.calls + 1) }
                running = false
                next.done?.let { cb -> withContext(Dispatchers.Main) { cb(final) } }
                pump()
            }
        }, Runnable::run)
    }

    private fun publish(line: CopilotLine, priority: Priority) = state.update { s ->
        if (priority == Priority.SCENE) s.copy(latestScene = line)
        else s.copy(lines = (s.lines - line.tag + (line.tag to line)).entries.toList().takeLast(40).associate { it.key to it.value })
    }

    /** Must match tools/copilot/dataset.py word for word: the fine-tune learned this exact frame. */
    private fun prompt(task: String, facts: String) = "<start_of_turn>user\nYou are RESILANE, an in-car road co-pilot on Indian roads (traffic keeps left). " +
        "$task Use only the facts. Never invent objects, distances or speeds. Never call a manoeuvre safe. Do not start with Sure or Okay.\n" +
        "Facts: $facts<end_of_turn>\n<start_of_turn>model\n"

    private fun clean(text: String, finished: Boolean): String {
        val plain = text.replace(Regex("<[^>]*>?"), "").replace(Regex("[*#]"), "").replace(Regex("\\s+"), " ").trim()
            .replace(FILLER, "").replaceFirstChar { it.uppercaseChar() }
        if (!finished) return plain
        val sentences = plain.split(Regex("(?<=[.!?])\\s")).filter { it.isNotBlank() }
        return sentences.take(2).joinToString(" ").take(260)
    }

    /** The deterministic text wins over an answer that invents numbers or calls something safe. */
    private fun guard(answer: String, facts: String, fallback: String?): String {
        if (answer.isBlank()) return fallback ?: answer
        val known = Regex("\\d+").findAll(facts).map { it.value }.toSet()
        val invented = Regex("\\d+").findAll(answer).any { it.value !in known }
        val unsafe = Regex("\\bsafe to\\b|\\bit is safe\\b|\\bit's safe\\b", RegexOption.IGNORE_CASE).containsMatchIn(answer)
        return if ((invented || unsafe) && fallback != null) fallback else answer
    }

    companion object {
        const val MODEL = "gemma-2b-it-cpu-int4.bin"
        /** The RESILANE fine-tune from tools/copilot/finetune_kaggle.py. Preferred when present. */
        const val TUNED = "gemma-2b-it-resilane-cpu-int4.bin"
        const val DEFAULT_TASK = "Tell the driver what is happening and what to do, in one sentence of at most 22 words."
        const val TASK_SCENE = "Describe what the camera sees and what to watch, in one sentence of at most 22 words."
        const val TASK_ROAD = "Summarise the road for the driver in at most two short sentences, nearest hazard first, keeping every number exactly."
        const val TASK_REPORT = "Tell the driver in one short sentence what was logged for the traffic police."
        fun taskQuestion(q: String) = "The driver asks: \"$q\". Answer in at most two short sentences. If the facts do not answer it, say you cannot tell from this phone."
        private val FILLER = Regex("^(sure|okay|ok|certainly|of course|absolutely|alright|here(?:'s| is) (?:the|a|my) [a-z ]+?)[,!.:]*\\s+", RegexOption.IGNORE_CASE)
    }
}
