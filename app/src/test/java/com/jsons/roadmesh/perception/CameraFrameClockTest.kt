package com.jsons.roadmesh.perception

import org.junit.Assert.*
import org.junit.Test

class CameraFrameClockTest {
    @Test fun realtimeFrameRetainsCaptureTimeAndCannotBeCountedTwice() {
        val clock = CameraFrameClock()
        val frame = clock.accept(1_000_000_000L, 1_100_000_000L, 1120, 1150, true)!!
        assertEquals(1000, frame.capturedAtMs)
        assertFalse(frame.clockEstimated)
        assertNull(clock.accept(1_000_000_000L, 1_200_000_000L, 1220, 1250, true))
    }
    @Test fun unknownSensorDomainIsMappedToArrivalInsteadOfAssumedElapsedRealtime() {
        val frame = CameraFrameClock().accept(80_000_000_000L, 80_050_000_000L, 2000, 2010, false)!!
        assertEquals(1950, frame.capturedAtMs)
        assertTrue(frame.clockEstimated)
    }
    @Test fun noCameraCompletionOrFrozenCallbacksCannotProduceFreshFrames() {
        val clock = CameraFrameClock()
        assertNull(clock.accept(2_000_000_000L, 0, 0, 2000, true))
        assertNull(clock.accept(2_000_000_000L, 2_000_000_000L, 2000, 2700, true))
    }
    @Test fun staleAndFutureCameraBuffersAreRejected() {
        val clock = CameraFrameClock()
        assertNull(clock.accept(1_000_000_000L, 2_000_000_000L, 2000, 2010, true))
        assertNull(clock.accept(3_000_000_000L, 2_000_000_000L, 2000, 2010, true))
        assertNull(clock.accept(80_800_000_000L, 80_000_000_000L, 2000, 2010, false))
    }
    @Test fun aNewSessionCanHaveANewSensorClock() {
        val clock = CameraFrameClock()
        assertNotNull(clock.accept(10_000_000_000L, 10_000_000_000L, 10_010, 10_020, true))
        clock.reset()
        assertNotNull(clock.accept(5_000_000L, 5_000_000L, 20_000, 20_010, false))
    }
}
