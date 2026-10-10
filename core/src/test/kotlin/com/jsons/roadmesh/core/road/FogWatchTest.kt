package com.jsons.roadmesh.core.road

import com.jsons.roadmesh.core.mesh.NodeId
import org.junit.Assert.*
import org.junit.Test

class FogWatchTest {
    private fun peer(aheadM: Int, speed: Double?) = PicturePeer(NodeId(2), 1, aheadM, speed, 1, 0)

    @Test fun stoppingDistanceAtSeventyKmhIsAboutSeventyOneMetres() {
        val d = FogWatch.stopDistanceM(70 / 3.6)
        assertEquals(71.0, d, 1.0)
    }

    @Test fun aStoppedCarFarAheadIsNotAThreatYet() {
        assertNull(FogWatch.evaluate(19.4, listOf(peer(400, 0.0))))
    }

    @Test fun aStoppedCarWithinFourSecondsOrTwoStoppingDistancesIsAWarning() {
        val w = FogWatch.evaluate(19.4, listOf(peer(110, 0.0)))!!   // 5.7 s away, but inside two stopping distances (142 m)
        assertEquals(FogThreat.STOPPED, w.threat)
        assertEquals(110, w.aheadM)
        assertTrue(w.beyondSight)
        assertEquals("Caution. Stopped vehicle ahead, 110 metres.", w.speech)
    }

    @Test fun aSlowCarIsASlowWarningAndNeedsRealClosing() {
        assertEquals(FogThreat.SLOW, FogWatch.evaluate(20.0, listOf(peer(80, 8.0)))!!.threat)
        assertNull(FogWatch.evaluate(20.0, listOf(peer(80, 19.5))))        // keeping pace
    }

    @Test fun peersBehindOfUnknownSpeedOrOutOfRangeAreIgnored() {
        assertNull(FogWatch.evaluate(20.0, listOf(peer(-50, 0.0), peer(30, null), peer(700, 0.0))))
        assertNull(FogWatch.evaluate(null, listOf(peer(30, 0.0))))
        assertNull(FogWatch.evaluate(0.5, listOf(peer(30, 0.0))))
    }

    @Test fun theNearestThreatWins() {
        assertEquals(60, FogWatch.evaluate(20.0, listOf(peer(120, 0.0), peer(60, 2.0)))!!.aheadM)
    }

    /**
     * A car at 70 km/h closing on a stopped one in fog where the driver sees 50 m. The phone is told at the first second the
     * rules fire; the warning must arrive beyond sight, with the whole stopping distance still in hand.
     */
    @Test fun inFogTheWarningArrivesBeyondWhatTheDriverCanSeeAndEarlyEnoughToStop() {
        val rules = FogRules(visibilityM = 50.0)
        val v = 70 / 3.6
        var gap = 500.0
        var warnedAt: FogWarning? = null
        var second = 0
        while (gap > 0 && warnedAt == null) {
            warnedAt = FogWatch.evaluate(v, listOf(peer(gap.toInt(), 0.0)), rules)
            if (warnedAt == null) { gap -= v; second++ }
        }
        val w = warnedAt!!
        assertTrue("warned at ${w.aheadM} m", w.beyondSight)
        assertTrue(w.aheadM > FogWatch.stopDistanceM(v, rules) * 1.5)    // room to stop after hearing it, with margin
        assertTrue(w.aheadM > rules.visibilityM * 2)                     // at least twice what the driver can see
    }

    @Test fun withoutTheWarningADriverSeeingFiftyMetresCannotStopAtSeventyKmh() {
        // The premise of the feature, stated as arithmetic: stopping needs more than the driver can see.
        assertTrue(FogWatch.stopDistanceM(70 / 3.6) > FogRules().visibilityM)
    }
}
