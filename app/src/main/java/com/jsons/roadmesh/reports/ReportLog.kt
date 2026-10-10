package com.jsons.roadmesh.reports

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.core.mesh.HazardKind
import com.jsons.roadmesh.service.saveDownload
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * A road report: a pothole, accident or violation this phone's camera found (with a snapshot), or one it heard from the
 * mesh. [lat]/[lon] are where the hazard is; on the stage they are synthetic positions and [synthetic] says so.
 */
data class RoadReport(
    val id: String, val kind: HazardKind, val utcMs: Long, val lat: Double?, val lon: Double?, val synthetic: Boolean,
    val reason: String, val confidence: Double?, val snapshot: String?, val fromMesh: Boolean, val sentToMesh: Boolean, val source: String,
)

/** Persistent list of reports in app storage, newest first, with JSON/CSV export to Downloads/RESILANE. */
class ReportLog(private val context: Context) {
    private val dir = File(context.filesDir, "reports").apply { runCatching { mkdirs() } }
    private val index = File(dir, "reports.json")
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val pendingLock = Any()
    private val pendingWrites = HashSet<Job>()
    private val state = MutableStateFlow(load())
    val flow = state.asStateFlow()

    init { writeLater { persistAndPrune() } }

    /** Records a report; [frame] (if any) is copied and saved as a JPEG snapshot off the calling thread. */
    fun add(kind: HazardKind, reason: String, confidence: Double?, lat: Double?, lon: Double?, synthetic: Boolean, frame: Bitmap?, sent: Boolean, source: String, fromMesh: Boolean = false) {
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID()}-${kind.name.lowercase()}"
        val copy = runCatching { frame?.copy(Bitmap.Config.ARGB_8888, false) }
            .onFailure { Log.w("RoadMeshReports", "Snapshot copy unavailable", it) }.getOrNull()
        val report = RoadReport(id, kind, System.currentTimeMillis(), lat, lon, synthetic, reason, confidence, copy?.let { "$id.jpg" }, fromMesh, sent, source)
        state.update { (listOf(report) + it).take(MAX) }
        writeLater {
            try {
                // A rapid burst may have evicted this report before its write starts; do not create an orphan.
                if (copy != null && state.value.any { it.id == id }) {
                    File(dir, "$id.jpg").outputStream().use {
                        check(copy.compress(Bitmap.CompressFormat.JPEG, 82, it)) { "JPEG compression failed" }
                    }
                }
            } catch (e: Exception) {
                state.update { rows -> rows.map { if (it.id == id) it.copy(snapshot = null) else it } }
                Log.w("RoadMeshReports", "Snapshot could not be saved", e)
            } finally {
                copy?.recycle()
                persistAndPrune()
            }
        }
    }

    fun snapshot(r: RoadReport): File? = r.snapshot?.let(::snapshotFile)?.takeIf { it.isFile }

    fun clear() { state.value = emptyList(); writeLater { persistAndPrune() } }

    /** Join actual writes rather than assuming that a newly scheduled empty job is a dispatcher FIFO barrier. */
    internal suspend fun awaitPendingWrites() {
        while (true) {
            val pending = synchronized(pendingLock) { pendingWrites.toList() }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }

    private fun writeLater(block: () -> Unit) {
        val job = io.launch(start = CoroutineStart.LAZY) { block() }
        synchronized(pendingLock) { pendingWrites += job }
        job.invokeOnCompletion { synchronized(pendingLock) { pendingWrites -= job } }
        job.start()
    }

    /** Writes reports.json, reports.csv and every snapshot to Downloads/RESILANE/reports. Returns where they went. */
    suspend fun export(): String = withContext(Dispatchers.IO) {
        val rows = state.value
        val stamp = System.currentTimeMillis()
        saveDownload(context, "reports-$stamp.json", "application/json", JSONArray(rows.map { it.json() }).toString(2))
        val csv = buildString {
            appendLine("id,kind,title,utc_ms,lat,lon,synthetic_position,confidence,from_mesh,sent_to_mesh,source,reason,snapshot")
            rows.forEach { r -> appendLine(listOf(r.id, r.kind.name, r.kind.title(), r.utcMs, r.lat ?: "", r.lon ?: "", r.synthetic, r.confidence ?: "", r.fromMesh, r.sentToMesh,
                r.source, r.reason, r.snapshot ?: "").joinToString(",") { "\"" + it.toString().replace("\"", "\"\"") + "\"" }) }
        }
        saveDownload(context, "reports-$stamp.csv", "text/csv", csv)
        var images = 0
        rows.mapNotNull { r -> snapshot(r)?.let { r to it } }.forEach { (r, f) ->
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, r.snapshot); put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RESILANE/reports")
            }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }; images++
            }
        }
        "Downloads/RESILANE: ${rows.size} reports, $images snapshots"
    }

    private fun RoadReport.json() = JSONObject().apply {
        put("id", id); put("kind", kind.name); put("utcMs", utcMs); lat?.let { put("lat", it) }; lon?.let { put("lon", it) }
        put("synthetic", synthetic); put("reason", reason); confidence?.let { put("confidence", it) }; snapshot?.let { put("snapshot", it) }
        put("fromMesh", fromMesh); put("sentToMesh", sentToMesh); put("source", source)
    }

    private fun save() {
        val pending = File(dir, "reports.json.pending")
        try {
            pending.outputStream().use { output ->
                output.write(JSONArray(state.value.map { it.json() }).toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(pending.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // Some storage providers cannot rename atomically; replacement still reports failures to our caller.
                Files.move(pending.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { if (pending.exists()) pending.delete() }
    }

    private fun snapshotFile(name: String): File? = if (File(name).name == name && name.endsWith(".jpg", ignoreCase = true)) File(dir, name) else null

    private fun persistAndPrune() {
        runCatching {
            val retained = state.value.mapNotNull { it.snapshot }.toSet()
            dir.listFiles()?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) && it.name !in retained }
                ?.forEach { file -> if (!file.delete()) Log.w("RoadMeshReports", "Could not remove expired snapshot") }
            save()
        }.onFailure { Log.w("RoadMeshReports", "Report storage unavailable; observation continues", it) }
    }

    private fun load(): List<RoadReport> = runCatching {
        val a = JSONArray(index.readText())
        List(minOf(a.length(), MAX)) { i ->
            val o = a.getJSONObject(i)
            RoadReport(o.getString("id"), HazardKind.valueOf(o.getString("kind")), o.getLong("utcMs"), o.optDouble("lat").takeIf { !it.isNaN() },
                o.optDouble("lon").takeIf { !it.isNaN() }, o.optBoolean("synthetic"), o.optString("reason"), o.optDouble("confidence").takeIf { !it.isNaN() },
                o.optString("snapshot").ifEmpty { null }, o.optBoolean("fromMesh"), o.optBoolean("sentToMesh"), o.optString("source"))
        }
    }.getOrDefault(emptyList())

    companion object { const val MAX = 200 }
}
