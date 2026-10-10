package com.jsons.roadmesh.core.perception

import org.junit.Assert.*
import org.junit.Test

class DriverEscalationTest {
    private val open = 0.95f
    private val shut = 0.05f

    /** A scripted drive: [DriverWatch] feeds [DriverEscalation] at 10 Hz and every action is recorded with its time. */
    private class Drive {
        val watch = DriverWatch()
        val esc = DriverEscalation()
        val log = ArrayList<Pair<Double, EscalationAction>>()
        fun run(fromS: Double, seconds: Double, eyes: Float, tap: (Double) -> Boolean = { false }) {
            for (i in 0 until (seconds * 10).toInt()) {
                val t = fromS + i * 0.1
                watch.update(FaceSample(t, true, eyes, eyes, 0f, 0f))?.let { a -> esc.onAlarm(a, t).forEach { log += t to it } }
                if (tap(t)) esc.acknowledge().forEach { log += t to it }
                esc.tick(t, watch.alarmed).forEach { log += t to it }
            }
        }
        inline fun <reified T : EscalationAction> all() = log.filter { it.second is T }
    }

    @Test fun anAlertDriverNeverStartsAnything() {
        val d = Drive()
        d.run(0.0, 60.0, open)
        assertTrue(d.log.isEmpty())
        assertEquals(EscalationState.AWAKE, d.esc.state)
    }

    @Test fun drowsyDriverGetsTheAlarmAtOnceAndTheSosOnlyAfterTheWindow() {
        val d = Drive()
        d.run(0.0, 3.0, open)
        d.run(3.0, 12.0, shut)
        val start = d.all<EscalationAction.StartAlarm>().single()
        assertEquals(4.2, start.first, 0.11)  // 1.2 s after the eyes shut
        // Eyes stay shut, so 3.0 s of closure (t=6.0) makes the driver unresponsive and the 2 s window ends at 8.0.
        val firstSos = d.all<EscalationAction.SendSos>().first()
        assertEquals(8.0, firstSos.first, 0.11)
        assertEquals(EscalationState.SOS_ACTIVE, d.esc.state)
    }

    @Test fun tappingBeforeTheWindowEndsSendsNothingToTheMesh() {
        val d = Drive()
        d.run(0.0, 3.0, open)
        d.run(3.0, 1.5, shut)                                  // drowsy at 4.2
        d.run(4.5, 1.0, open) { it >= 5.0 }                    // tap at 5.0, well inside the 6 s window
        assertEquals(1, d.all<EscalationAction.StartAlarm>().size)
        assertEquals(1, d.all<EscalationAction.StopAlarm>().size)
        assertTrue(d.all<EscalationAction.SendSos>().isEmpty())
        assertEquals(EscalationState.AWAKE, d.esc.state)
    }

    @Test fun eyesOpenAndHeadUpForTheRecoveryTimeAlsoCountsAsAnAnswer() {
        val d = Drive()
        d.run(0.0, 3.0, open)
        d.run(3.0, 1.5, shut)
        d.run(4.5, 3.0, open)                                  // awake for 1.5 s ends the episode, long before 10.2 s
        assertTrue(d.all<EscalationAction.SendSos>().isEmpty())
        assertEquals(1, d.all<EscalationAction.StopAlarm>().size)
    }

    @Test fun aSingleReopenedEyeDoesNotEndTheAlarm() {
        val d = Drive()
        d.run(0.0, 3.0, open)
        d.run(3.0, 1.5, shut)                                  // drowsy at 4.2
        d.run(4.5, 0.5, open)                                  // 0.5 s awake: not 1.5 s
        d.run(5.0, 1.0, shut)
        assertTrue(d.all<EscalationAction.StopAlarm>().isEmpty())
        assertEquals(EscalationState.ALERTING, d.esc.state)
    }

    @Test fun anActiveSosRepeatsEveryTwoSecondsWithTheSameEpisodeAndRisingSequence() {
        val d = Drive()
        d.run(0.0, 3.0, open)
        d.run(3.0, 14.0, shut)
        val sos = d.all<EscalationAction.SendSos>().map { it.second as EscalationAction.SendSos }
        assertTrue(sos.size >= 4)
        assertEquals(1, sos.map { it.episode }.toSet().single())
        assertEquals(sos.indices.toList(), sos.map { it.sequence })
        d.all<EscalationAction.SendSos>().map { it.first }.zipWithNext().forEach { (a, b) -> assertEquals(2.0, b - a, 0.11) }
    }

    @Test fun recoveryDuringAnSosSendsOneClearAndStopsTheAlarm() {
        val d = Drive()
        d.run(0.0, 3.0, open)
        d.run(3.0, 7.0, shut)                                  // SOS active from 8.0
        assertEquals(EscalationState.SOS_ACTIVE, d.esc.state)
        d.run(10.0, 3.0, open)
        val clearEntry = d.all<EscalationAction.SendClear>().single()
        val clear = clearEntry.second as EscalationAction.SendClear
        val lastSos = d.all<EscalationAction.SendSos>().last().second as EscalationAction.SendSos
        assertEquals(lastSos.episode, clear.episode)
        assertTrue(clear.sequence > lastSos.sequence)
        assertEquals(1, d.all<EscalationAction.StopAlarm>().size)
        assertEquals(EscalationState.AWAKE, d.esc.state)
        d.run(13.0, 5.0, open)
        assertTrue(d.all<EscalationAction.SendSos>().none { it.first > clearEntry.first })
    }

    @Test fun anAcknowledgeDuringAnSosClearsIt() {
        val d = Drive()
        d.run(0.0, 3.0, open)
        d.run(3.0, 7.0, shut)
        d.run(10.0, 1.0, shut) { it >= 10.5 }
        assertEquals(1, d.all<EscalationAction.SendClear>().size)
        assertEquals(EscalationState.AWAKE, d.esc.state)
    }

    @Test fun aSecondDrowsyEpisodeSoonAfterTheFirstDoesNotStartAnotherSosButAnUnresponsiveOneDoes() {
        val esc = DriverEscalation()
        val drowsy = DriverAlarm(Alertness.DROWSY, 0.0, "eyes shut for 1.2 s", 0.7)
        esc.onAlarm(drowsy, 0.0)
        assertEquals(1, esc.tick(6.0, true).filterIsInstance<EscalationAction.SendSos>().size)
        esc.acknowledge()
        esc.onAlarm(drowsy, 20.0)
        assertTrue(esc.tick(30.0, true).isEmpty())            // 24 s after the last SOS began: inside the 60 s cooldown
        assertEquals(EscalationState.ALERTING, esc.state)
        esc.onAlarm(DriverAlarm(Alertness.UNRESPONSIVE, 31.0, "eyes shut for 3.0 s", 0.9), 31.0)
        val sos = esc.tick(33.0, true).filterIsInstance<EscalationAction.SendSos>().single()
        assertEquals(2, sos.episode)
        assertEquals(0, sos.sequence)
    }

    @Test fun worseningWhileAlertingShortensTheWindowButNeverLengthensIt() {
        val esc = DriverEscalation()
        esc.onAlarm(DriverAlarm(Alertness.DROWSY, 0.0, "eyes shut for 1.2 s", 0.7), 0.0)        // window ends at 6.0
        esc.onAlarm(DriverAlarm(Alertness.UNRESPONSIVE, 3.0, "eyes shut for 3.0 s", 0.9), 3.0)  // now ends at 5.0
        assertTrue(esc.tick(4.9, true).isEmpty())
        assertEquals(1, esc.tick(5.0, true).filterIsInstance<EscalationAction.SendSos>().size)
    }

    @Test fun anUnresponsiveDriverWhoWasAlreadyDrowsyIsSentAtTheWorseLevelWhileActive() {
        val esc = DriverEscalation()
        esc.onAlarm(DriverAlarm(Alertness.DROWSY, 0.0, "eyes shut for 1.2 s", 0.7), 0.0)
        esc.tick(6.0, true)
        esc.onAlarm(DriverAlarm(Alertness.UNRESPONSIVE, 7.0, "eyes shut for 3.0 s", 0.9), 7.0)
        val next = esc.tick(7.1, true).filterIsInstance<EscalationAction.SendSos>().single()
        assertEquals(Alertness.UNRESPONSIVE, next.level)
        assertEquals(1, next.sequence)
    }

    @Test fun theCountdownShowsHowLongTheDriverHasLeftToAnswer() {
        val esc = DriverEscalation()
        assertNull(esc.remainingS(0.0))
        esc.onAlarm(DriverAlarm(Alertness.DROWSY, 10.0, "eyes shut for 1.2 s", 0.7), 10.0)
        assertEquals(6.0, esc.remainingS(10.0)!!, 0.001)
        assertEquals(2.5, esc.remainingS(13.5)!!, 0.001)
        esc.tick(16.0, true)
        assertNull(esc.remainingS(16.0))                            // the SOS is out: no window left
    }
}
