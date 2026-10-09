package org.resilane.sim

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logging for field tests. Three sinks:
 *  - logcat, tagged RL/<area> (`adb logcat -s RL/Calib RL/Peer ...`)
 *  - an in-memory ring the on-screen log panel shows
 *  - CSV files under Android/data/org.resilane.sim/files/sessions/<start>/ that tools/deploy.sh
 *    pulls: events.csv (calibration, tracking, peers) and poses.csv (every pose sent / received,
 *    with packet age), for checking the brief's experiments afterwards.
 */
object RLog {
    private const val RING = 300
    private val ring = ArrayDeque<String>(RING)
    private val io = Executors.newSingleThreadExecutor { Thread(it, "rl-log").apply { isDaemon = true } }
    private var events: BufferedWriter? = null
    private var poses: BufferedWriter? = null
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile var sessionDir: File? = null; private set

    fun open(context: Context) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "sessions/$stamp").apply { mkdirs() }
        sessionDir = dir
        io.execute {
            events = File(dir, "events.csv").bufferedWriter().apply { write("t_ms,area,message\n") }
            poses = File(dir, "poses.csv").bufferedWriter().apply {
                write("t_ms,dir,node,seq,sender_t_ms,age_ms,tracking,calibrated,x,y,z,yaw_deg,qx,qy,qz,qw\n")
            }
        }
    }

    fun i(area: String, msg: String) = emit(Log.INFO, area, msg)
    fun w(area: String, msg: String) = emit(Log.WARN, area, msg)
    fun e(area: String, msg: String, t: Throwable? = null) = emit(Log.ERROR, area, msg + (t?.let { ": $it" } ?: ""), t)

    private fun emit(level: Int, area: String, msg: String, t: Throwable? = null) {
        Log.println(level, "RL/$area", msg + (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
        val now = System.currentTimeMillis()
        val line = "${clock.format(Date(now))} ${"IWE"[(level - Log.INFO).coerceIn(0, 2)]} $area  $msg"
        synchronized(ring) {
            if (ring.size == RING) ring.removeFirst()
            ring.addLast(line)
        }
        io.execute { events?.apply { write("$now,$area,\"${msg.replace("\"", "'")}\"\n") } }
    }

    fun recent(n: Int = 60): List<String> = synchronized(ring) { ring.takeLast(n) }

    fun pose(dir: String, node: Long, seq: Int, senderT: Long, ageMs: Long?, tracking: String, calibrated: Boolean,
             x: Float, y: Float, z: Float, yawDeg: Float, qx: Float, qy: Float, qz: Float, qw: Float) {
        val now = System.currentTimeMillis()
        io.execute {
            poses?.write("$now,$dir,%016x,$seq,$senderT,${ageMs ?: ""},$tracking,$calibrated,%.4f,%.4f,%.4f,%.2f,%.5f,%.5f,%.5f,%.5f\n"
                .format(Locale.US, node, x, y, z, yawDeg, qx, qy, qz, qw))
        }
    }

    fun flush() = io.execute { events?.flush(); poses?.flush() }
}
