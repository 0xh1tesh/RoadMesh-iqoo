package com.jsons.roadmesh.output

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.jsons.roadmesh.core.mesh.MeshEffect

/** The engine emits Announce once per event; this adapter owns Android speech and haptics. */
class HazardOutput(context: Context, private val report: (String) -> Unit) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private var ready = false
    private var closed = false
    private var lastOutputMs = -2_000L
    private var pending: Pair<MeshEffect.Announce, Long>? = null
    private var pendingLocal: Pair<String, Long>? = null
    private lateinit var tts: TextToSpeech
    init {
        tts = TextToSpeech(context) { status ->
            if (closed) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                val voice = tts.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
                    ?.sortedByDescending { it.locale.country == "IN" }?.firstOrNull()
                if (voice != null) { tts.voice = voice; ready = true; report("offline voice ready: ${voice.name}") }
                else report("offline English voice unavailable · visual and haptic alerts active")
                pending?.let { (effect, expires) ->
                    if (ready && android.os.SystemClock.elapsedRealtime() <= expires) speak(effect)
                }
                pending = null
                pendingLocal?.let { (text, expires) -> if (ready && android.os.SystemClock.elapsedRealtime() < expires) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "local-caution") }
                pendingLocal = null
            } else report("speech unavailable: $status")
        }
    }
    fun announce(effect: MeshEffect.Announce) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastOutputMs < 1_500) { report("near-simultaneous cautions coalesced locally"); return }
        lastOutputMs = now
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 180), -1))
        if (ready) speak(effect)
        else pending = effect to (android.os.SystemClock.elapsedRealtime() + 2_000)
    }
    private fun speak(effect: MeshEffect.Announce) {
        val result = tts.speak(effect.kind.speech, TextToSpeech.QUEUE_FLUSH, null, effect.frame.id.toString())
        report(if (result == TextToSpeech.SUCCESS) "speech queued locally" else "speech queue rejected")
    }
    /** A follow-up line (the co-pilot's explanation), queued after any caution that is still speaking. */
    fun say(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "copilot-${android.os.SystemClock.elapsedRealtime()}")
    }
    /** Immediate local sensor caution, even when there is no position or radio delivery. */
    fun localCaution(text: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (closed || text.isBlank() || now - lastOutputMs < 1_500) return
        lastOutputMs = now
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 180), -1))
        if (ready) tts.speak(text.take(240), TextToSpeech.QUEUE_FLUSH, null, "local-$now")
        else pendingLocal = text.take(240) to (now + 2_000)
        report("local sensor caution; remote delivery unknown")
    }
    fun close() { closed = true; ready = false; pending = null; pendingLocal = null; tts.stop(); tts.shutdown(); vibrator.cancel() }
}
