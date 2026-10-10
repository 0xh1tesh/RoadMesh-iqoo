package com.jsons.roadmesh.ui

import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.core.road.*
import com.jsons.roadmesh.core.swarm.SwarmStatus
import com.jsons.roadmesh.service.SessionState
import org.junit.Assert.*
import org.junit.Test

class SwarmSessionTest {
    private val now = 20_000L
    private val fix = GeoFix(12.9716, 77.5946, 0.0, 5.0, false, 3.0, 5.0)
    private fun evidence(source: Long, capturedMs: Long, provenance: Provenance?, confidence: Double?) =
        HazardEvidence(MessageId(NodeId(source), 7, 1), capturedMs, 100_000 + capturedMs, provenance, confidence)

    private fun hazard(vararg evidence: HazardEvidence) = KnownHazard(
        HazardKind.ROAD_NARROWED, fix.lat, fix.lon, 0L, now,
        evidence.map { it.id.origin }.toSet(), 1.0, false, evidence = evidence.toList(), accuracyM = 3.0,
    )

    private fun state(vararg hazards: KnownHazard) = SessionState(Role.RECEIVER, Mode.ROAD, running = true, ready = true,
        node = NodeId(3), chain = listOf(Member(NodeId(3), 0, false, VehicleType.CAR, fix, now, 0, Role.RECEIVER, true)),
        known = hazards.toList())

    @Test fun freshMergedRecordDoesNotRefreshAnOldOriginalObservation() {
        val assessment = sessionSwarmAssessment(state(hazard(evidence(1, 1_000, Provenance.LIVE, 0.8))), now)
        assertEquals(SwarmStatus.UNKNOWN, assessment.status)
        assertTrue(assessment.evidence.isEmpty())
    }

    @Test fun replayCannotContributeConfidenceOrSupportToLiveRoadEvidence() {
        val report = hazard(evidence(1, 19_000, Provenance.LIVE, 0.2), evidence(2, 19_000, Provenance.REPLAY, 1.0))
        val assessment = sessionSwarmAssessment(state(report), now)
        assertEquals(SwarmStatus.UNKNOWN, assessment.status)
        assertEquals(0, assessment.independentSources)
    }

    @Test fun manualReportKeepsUnknownConfidenceAndCopiedIdentityDoesNotAddSupport() {
        val report = hazard(evidence(1, 19_000, Provenance.LIVE, null))
        val assessment = sessionSwarmAssessment(state(report, report), now)
        assertEquals(SwarmStatus.CAUTION, assessment.status)
        assertTrue(assessment.title.contains("unverified", ignoreCase = true))
        assertEquals(1, assessment.independentSources)
        assertEquals(1, assessment.evidence.size)
        assertNull(assessment.evidence.single().confidence)
    }

    @Test fun unidentifiedLegacyRecordsDoNotBecomeIndependentEvidence() {
        val assessment = sessionSwarmAssessment(state(hazard()), now)
        assertEquals(SwarmStatus.UNKNOWN, assessment.status)
        assertEquals(0, assessment.independentSources)
    }
}
