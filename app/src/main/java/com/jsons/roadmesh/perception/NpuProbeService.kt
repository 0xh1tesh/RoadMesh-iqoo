package com.jsons.roadmesh.perception

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.os.SystemClock
import org.resilane.sim.vision.YoloDetector
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs once, in the separate ":npu" process: builds the detector on the Hexagon NPU and times two inferences. The
 * result goes to a small file the app reads before choosing a backend. If QNN faults natively, only this process dies;
 * the file still says "started" and the app never asks for the NPU on this install again.
 */
class NpuProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val out = file(this)
        out.writeText("started ${version(this)}")
        thread(name = "npu-probe") {
            val result = runCatching {
                YoloDetector(this, DashcamObserver.MODEL_ASSET, npu = true).use { yolo ->
                    check(yolo.provider == "QNN-HTP") { "provider ${yolo.provider}" }
                    val frame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                    yolo.detect(frame, 0.25f)
                    val t0 = SystemClock.elapsedRealtime(); yolo.detect(frame, 0.25f)
                    "ok ${version(this)} ${SystemClock.elapsedRealtime() - t0}ms"
                }
            }.getOrElse { "fail ${version(this)} ${it.message?.take(200)}" }
            out.writeText(result)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    companion object {
        fun file(context: Context) = File(context.filesDir, "npu_probe.txt")
        private fun version(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        /** "ok …", "fail …", "started …" (it crashed), or null (never tried for this app version). */
        fun result(context: Context): String? = file(context).takeIf { it.exists() }?.readText()?.takeIf { it.contains(" ${version(context)}") }
        fun start(context: Context) { runCatching { context.startService(Intent(context, NpuProbeService::class.java)) } }
    }
}
