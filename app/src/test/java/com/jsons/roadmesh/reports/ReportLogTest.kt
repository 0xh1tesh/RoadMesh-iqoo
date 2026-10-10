package com.jsons.roadmesh.reports

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import com.jsons.roadmesh.core.mesh.HazardKind
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class ReportLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context(root: File): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = root
    }
    private fun add(log: ReportLog, bitmap: Bitmap?) = log.add(HazardKind.VEHICLE_CLOSE, "Automatic relative approach", 0.9,
        null, null, false, bitmap, false, "test camera")

    @Test fun automaticSnapshotsAndMetadataStayBoundedDuringABurst() = runBlocking {
        val root = temporary.newFolder("bounded")
        val log = ReportLog(context(root))
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        repeat(ReportLog.MAX + 12) { add(log, source) }
        log.awaitPendingWrites()
        val rows = log.flow.value
        val photos = File(root, "reports").listFiles()!!.filter { it.extension == "jpg" }
        assertEquals(ReportLog.MAX, rows.size)
        assertEquals(ReportLog.MAX, rows.map { it.id }.toSet().size)
        assertEquals(rows.mapNotNull { it.snapshot }.toSet(), photos.map { it.name }.toSet())
        assertEquals(ReportLog.MAX, photos.size)
        assertTrue(rows.all { log.snapshot(it)?.length()?.let { bytes -> bytes > 0 } == true })
        assertFalse("The caller owns the camera bitmap", source.isRecycled)
        assertEquals("Persisted report count; storage diagnostics: ${ShadowLog.getLogsForTag("RoadMeshReports")}",
            ReportLog.MAX, JSONArray(File(root, "reports/reports.json").readText()).length())
        source.recycle()
    }

    @Test fun blockedStorageDoesNotCrashObservationOrRetainMissingSnapshotReferences() = runBlocking {
        val root = temporary.newFolder("blocked")
        File(root, "reports").writeText("A file prevents creating the snapshot directory")
        val log = ReportLog(context(root))
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        add(log, source)
        log.awaitPendingWrites()
        assertEquals(1, log.flow.value.size)
        assertNull(log.flow.value.single().snapshot)
        assertFalse(source.isRecycled)
        add(log, null)
        log.awaitPendingWrites()
        assertEquals("Later observations continue after IO failure", 2, log.flow.value.size)
        source.recycle()
    }

    @Test fun startupRemovesOldOrphanSnapshotsAndClearRetainsNoImages() = runBlocking {
        val root = temporary.newFolder("startup")
        val dir = File(root, "reports").apply { mkdirs() }
        File(dir, "orphan.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "unrelated.txt").writeText("Not a report snapshot")
        val log = ReportLog(context(root))
        log.awaitPendingWrites()
        assertFalse(File(dir, "orphan.jpg").exists())
        assertTrue(File(dir, "unrelated.txt").exists())
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        add(log, source)
        log.awaitPendingWrites()
        assertEquals(1, dir.listFiles()!!.count { it.extension == "jpg" })
        log.clear()
        log.awaitPendingWrites()
        assertTrue(log.flow.value.isEmpty())
        assertEquals(0, dir.listFiles()!!.count { it.extension == "jpg" })
        assertEquals(0, JSONArray(File(dir, "reports.json").readText()).length())
        source.recycle()
    }
}
