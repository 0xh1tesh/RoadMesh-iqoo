package com.jsons.roadmesh.core.perception

import org.junit.Assert.*
import org.junit.Test

class DriverWatchTest {
    private val open = 0.95f
    private val shut = 0.05f
    /** Feeds 10 Hz samples from [fromS] for [seconds], returning every alarm raised. */
    private fun DriverWatch.feed(fromS: Double, seconds: Double, at: (Double) -> FaceSample): List<DriverAlarm> =
        (0 until (seconds * 10).toInt()).mapNotNull { i -> update(at(fromS + i * 0.1)) }
    private fun face(t: Double, eyes: Float?, pitch: Float = 0f, roll: Float = 0f) = FaceSample(t, true, eyes, eyes, pitch, roll)
    /** An alert driver for the first [s] seconds, so the head pose is calibrated. */
    private fun DriverWatch.awake(s: Double = 3.0) = feed(0.0, s) { face(it, open) }

    @Test fun anAlertDriverRaisesNothing() {
        val w = DriverWatch()
        assertTrue(w.feed(0.0, 60.0) { t -> face(t, if ((t * 10).toInt() % 40 == 0) shut else open) }.isEmpty()) // a blink every 4 s
        assertEquals(Alertness.AWAKE, w.reading.alertness)
    }

    @Test fun aMicrosleepIsDrowsyThenALongerOneEscalates() {
        val w = DriverWatch()
        w.awake()
        val alarms = w.feed(3.0, 3.5) { face(it, shut) }
        assertEquals(listOf(Alertness.DROWSY, Alertness.UNRESPONSIVE), alarms.map { it.level })
        assertEquals(4.2, alarms[0].atS, 0.01)  // 1.2 s after the eyes shut
        assertEquals(6.0, alarms[1].atS, 0.01)  // 3 s after
        assertTrue(alarms[1].reason.startsWith("eyes shut for 3.0"))
    }

    @Test fun eachLevelIsRaisedOncePerEpisodeAndRecoveryStartsANewOne() {
        val w = DriverWatch()
        w.awake()
        assertEquals(1, w.feed(3.0, 2.0) { face(it, shut) }.size)
        assertEquals(0, w.feed(5.0, 0.5) { face(it, open) }.size + w.feed(5.5, 1.5) { face(it, shut) }.size) // still the same episode
        assertTrue(w.alarmed)
        w.feed(7.0, 2.0) { face(it, open) }
        assertFalse(w.alarmed)
        assertEquals(1, w.feed(9.0, 1.5) { face(it, shut) }.size)
    }

    @Test fun headDroppingWithEyesShutIsPassingOut() {
        val w = DriverWatch()
        w.awake()
        val alarms = w.feed(3.0, 2.0) { face(it, 0.2f, pitch = -35f) }
        assertEquals(Alertness.UNRESPONSIVE, alarms.last().level)
        assertTrue(alarms.last().reason.contains("head dropped 35°"))
    }

    @Test fun lookingDownWithEyesOpenIsNotAnSos() {
        val w = DriverWatch()
        w.awake()
        assertTrue(w.feed(3.0, 5.0) { face(it, open, pitch = -35f) }.isEmpty())
    }

    @Test fun frequentLongBlinksAddUpToDrowsy() {
        val w = DriverWatch()
        w.awake()
        // Eyes shut 0.8 s of every 2 s: no single microsleep, but 40% closure.
        val alarms = w.feed(3.0, 20.0) { t -> face(t, if (((t - 3.0) * 10).toInt() % 20 < 8) shut else open) }
        assertEquals(Alertness.DROWSY, alarms.single().level)
        assertTrue(alarms.single().reason.contains("% of the last 30 s"))
    }

    @Test fun slumpingOutOfViewAfterTheEyesShutIsUnresponsive() {
        val w = DriverWatch()
        w.awake()
        w.feed(3.0, 0.8) { face(it, shut) }
        val alarms = w.feed(3.8, 3.0) { FaceSample(it, false) }
        assertEquals(Alertness.UNRESPONSIVE, alarms.single().level)
    }

    @Test fun simplyLeavingTheFrameIsNot() {
        val w = DriverWatch()
        w.awake()
        assertTrue(w.feed(3.0, 10.0) { FaceSample(it, false) }.isEmpty())
        assertEquals(Alertness.NO_FACE, w.reading.alertness)
    }
}
