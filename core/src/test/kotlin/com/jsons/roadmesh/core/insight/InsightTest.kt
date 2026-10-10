package com.jsons.roadmesh.core.insight

import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.core.perception.Lead
import com.jsons.roadmesh.core.perception.ObjectClass
import com.jsons.roadmesh.core.perception.SeenObject
import org.junit.Assert.*
import org.junit.Test

class InsightTest {
    private val clock = TestClock()

    @Test fun originInferenceRidesInTheSpareBytesAndSurvivesTheRelay() {
        val detail = HazardDetail(9, VehicleType.TWO_WHEELER, 0.8)
        assertEquals(detail, HazardDetail.from(detail.p1, detail.p2))
        assertNull(HazardDetail.from(0, 0))
        val a = MeshEngine(NodeId(1), 1, 42, MeshProfile(), clock, PositionSource { stageFix(Role.OBSERVER) }, StageChain, false) { 0 }
        val b = MeshEngine(NodeId(2), 2, 42, MeshProfile(), clock, PositionSource { stageFix(Role.RELAY) }, StageChain, true) { 0 }
        val frame = a.publish(LocalHazard(HazardKind.PEDESTRIAN, Provenance.REPLAY, "cam", detail)).filterIsInstance<MeshEffect.Transmit>().single().frame
        b.receive(HazardEnvelopeCodec.encode(frame))
        clock.advance(250)
        val relayed = b.tick().filterIsInstance<MeshEffect.Transmit>().single().frame
        assertEquals(detail, HazardEnvelopeCodec.decode(HazardEnvelopeCodec.encode(relayed))!!.detail)
    }

    @Test fun eachNodeAddsItsOwnDistanceToTheOriginsRange() {
        val atB = NodeInference.at(HazardKind.PEDESTRIAN, HazardDetail(9, VehicleType.CAR, 0.7), 100.0, 10.0)
        assertEquals(109, atB.hazardAheadM); assertEquals(10.9, atB.etaS!!, 0.01)
        val atC = NodeInference.at(HazardKind.PEDESTRIAN, HazardDetail(9, VehicleType.CAR, 0.7), 200.0, 10.0)
        assertEquals(209, atC.hazardAheadM)
        assertTrue(atB.action.startsWith("ease off") || atB.action.startsWith("slow"))
        assertTrue(atC.facts('A', 'B').contains("relayed by phone B"))
    }

    private fun car(cx: Float, w: Float, y2: Float = 0.8f) = SeenObject(ObjectClass.CAR, 0.8f, cx - w / 2, y2 - w * 0.9f, cx + w / 2, y2)

    @Test fun overtakeIsNeverCalledSafe() {
        val advisor = OvertakeAdvisor()
        val clear = advisor.assess(true, listOf(car(0.5f, 0.1f)), null, emptyList(), VehicleType.CAR)
        assertFalse(clear.notNow)
        assertFalse(clear.verdict.contains("safe", ignoreCase = true))
        assertTrue(clear.verdict.contains("mirrors"))
    }

    @Test fun rightLaneTrafficCameraOffAndMeshWarningsAllSayNotNow() {
        val advisor = OvertakeAdvisor()
        // Lateral (0.75 - 0.5) / 0.1 * 1.75 = 4.4 m right, distance 0.65 * 1.75 / 0.1 = 11 m.
        assertTrue(advisor.assess(true, listOf(car(0.75f, 0.1f)), null, emptyList(), VehicleType.CAR).reasons.single().contains("right lane"))
        assertTrue(advisor.assess(false, emptyList(), null, emptyList(), VehicleType.CAR).notNow)
        assertTrue(advisor.assess(true, emptyList(), null, listOf(ActiveWarning(HazardKind.BRAKING, 150)), VehicleType.CAR).verdict.contains("braking ahead"))
        val lead = Lead(car(0.5f, 0.2f), 5.7, 0.0, 1.5)
        assertTrue(advisor.assess(true, emptyList(), lead, emptyList(), VehicleType.CAR).verdict.contains("closing fast"))
    }
}
