package com.jsons.roadmesh.ui

import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.perception.ObserverState
import com.jsons.roadmesh.service.EventTrace
import com.jsons.roadmesh.service.SessionState
import org.junit.Assert.*
import org.junit.Test

class DriveDashboardTest {
    private val road = SessionState(Role.RELAY, Mode.ROAD, running = true, ready = true)

    @Test fun missingPositionKeepsLocalCameraUsefulButDoesNotClaimSharingReady() {
        val observer = ObserverState(live = true, cameraStatus = "Camera streaming", capturedAtMs = 9_900,
            frames = 12, analysisStatus = "Collecting track history")
        val steps = drivePipelineSteps(road, observer, 10_000)
        assertTrue(steps[0].active)
        assertTrue(steps[1].active)
        assertFalse(steps[3].active)
        assertTrue(steps[3].value.contains("awaiting real position"))
    }

    @Test fun pausedOrStaleCameraDoesNotShowActivePerception() {
        val observer = ObserverState(live = true, cameraStatus = "App backgrounded", frames = 120, capturedAtMs = 5_000)
        val steps = drivePipelineSteps(road, observer, 10_000)
        assertFalse(steps[0].active)
        assertFalse(steps[1].active)
    }

    @Test fun clockRollbackDoesNotMakeCameraFresh() {
        val observer = ObserverState(live = true, frames = 120, capturedAtMs = 20_000)
        assertFalse(drivePipelineSteps(road, observer, 10_000)[0].active)
    }

    @Test fun replayIsLabelledRecordedAndCanRunWithoutLivePosition() {
        val observer = ObserverState(simulating = true, playing = true, frames = 20, analysisStatus = "Replay analysis")
        val steps = drivePipelineSteps(road.copy(mode = Mode.STAGE), observer, 10_000)
        assertEquals("Recorded clip playing", steps[0].value)
        assertTrue(steps[0].active)
        assertTrue(steps[1].active)
        assertTrue(steps[3].active)
        assertFalse(steps[3].value.contains("delivered", ignoreCase = true))
    }

    @Test fun heardButIrrelevantPacketDoesNotBecomeDrivingWarning() {
        val heard = EventTrace(MessageId(NodeId(7), 1, 1), HazardKind.SLOWDOWN, null, false, NodeId(8), 9_000)
        assertNull(recentDriveWarning(road.copy(events = listOf(heard)), 10_000))
        val relevant = heard.copy(announced = true)
        assertEquals(relevant, recentDriveWarning(road.copy(events = listOf(relevant)), 10_000))
        assertNull(recentDriveWarning(road.copy(events = listOf(relevant)), 20_000))
        assertNull(recentDriveWarning(road.copy(events = listOf(relevant)), 8_000))
    }
}
