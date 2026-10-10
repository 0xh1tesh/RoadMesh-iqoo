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
        val a = MeshEngine(NodeId(1), 1, 42, MeshProfile(), clock, PositionSource { stageFix(Role.OBSERVER) }, StageChain, false, random = { 0 })
        val b = MeshEngine(NodeId(2), 2, 42, MeshProfile(), clock, PositionSource { stageFix(Role.RELAY) }, StageChain, true, random = { 0 })
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
}
