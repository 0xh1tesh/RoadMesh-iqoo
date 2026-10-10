package com.jsons.roadmesh.core.swarm

import com.jsons.roadmesh.core.mesh.GeoFix
import org.junit.Assert.*
import org.junit.Test

class SwarmAdvisorTest {
    private fun fix(north: Double = 0.0, east: Double = 0.0, heading: Double? = 0.0) =
        GeoFix(12.0 + north / 111_195.0, 77.0 + east / 108_764.0, heading, 5.0, true)
    private fun person(id: String = "C", north: Double = 0.0, heading: Double? = 0.0, at: Long = 1_000) =
        SwarmParticipant(id, fix(north, heading = heading), at, 3.0, 5.0)
    private fun event(id: String = "event-1", source: String = "A", at: Long = 1_000) =
        SwarmObservation(id, source, ObservationKind.OBSTRUCTION, fix(100.0), at, 15_000, 0.8, 3.0)
    private fun assess(events: List<SwarmObservation>, now: Long = 2_000, ego: SwarmParticipant = person(at = now)) =
        SwarmAdvisor.evaluate(ego, listOf(person("A", 80.0, at = now)), events, now)

    @Test fun `headings classify same opposing and crossing without treating startup order as traffic order`() {
        val ego = person()
        assertEquals(DirectionRelation.SAME_DIRECTION, SwarmAdvisor.classify(ego, person("A", 40.0, 10.0), 2_000).relation)
        assertEquals(DirectionRelation.OPPOSING, SwarmAdvisor.classify(ego, person("B", 50.0, 180.0), 2_000).relation)
        assertEquals(DirectionRelation.CROSSING, SwarmAdvisor.classify(ego, person("D", 40.0, 90.0), 2_000).relation)
    }

    @Test fun `north wraparound and heading uncertainty are conservative`() {
        val ego = person(heading = 357.0)
        val nearNorth = person("A", 40.0, 3.0)
        assertEquals(DirectionRelation.SAME_DIRECTION, SwarmAdvisor.classify(ego, nearNorth, 2_000).relation)
        assertEquals(DirectionRelation.UNCERTAIN, SwarmAdvisor.classify(ego.copy(headingAccuracyDeg = 40.0), nearNorth.copy(headingAccuracyDeg = 40.0), 2_000).relation)
        assertEquals(DirectionRelation.UNCERTAIN, SwarmAdvisor.classify(ego, nearNorth.copy(fix = fix(40.0, heading = null)), 2_000).relation)
    }

    @Test fun `stale missing inaccurate distant and mixed-domain positions never form a direction group`() {
        val ego = person()
        assertEquals(DirectionRelation.STALE, SwarmAdvisor.classify(ego, person("A", at = 10_000), 2_000).relation)
        assertEquals(DirectionRelation.STALE, SwarmAdvisor.classify(ego.copy(observedAtMs = 9_000), person("A"), 9_000).relation)
        assertEquals(DirectionRelation.UNLOCATED, SwarmAdvisor.classify(ego, person("A").copy(fix = null), 2_000).relation)
        assertEquals(DirectionRelation.UNCERTAIN, SwarmAdvisor.classify(ego, person("A").copy(positionAccuracyM = Double.POSITIVE_INFINITY), 2_000).relation)
        assertEquals(DirectionRelation.OUT_OF_RANGE, SwarmAdvisor.classify(ego, person("A", 300.0), 2_000).relation)
        assertEquals(DirectionRelation.UNLOCATED, SwarmAdvisor.classify(ego, person("A").copy(fix = fix().copy(synthetic = false)), 2_000).relation)
    }

    @Test fun `dateline adjacent positions remain local`() {
        val ego = person().copy(fix = GeoFix(0.0, 179.9999, 90.0, 5.0, false))
        val other = person("A").copy(fix = GeoFix(0.0, -179.9999, 90.0, 5.0, false))
        val neighbour = SwarmAdvisor.classify(ego, other, 2_000)
        assertEquals(DirectionRelation.SAME_DIRECTION, neighbour.relation)
        assertEquals(22.24, neighbour.distanceM!!, 0.1)
        assertTrue(neighbour.aheadM!! > 0)
    }

    @Test fun `no evidence missing ego location and expired evidence stay unknown`() {
        assertEquals(SwarmStatus.UNKNOWN, assess(emptyList()).status)
        assertTrue(assess(emptyList()).advice.contains("does not mean a clear road"))
        assertEquals(SwarmStatus.UNKNOWN, assess(listOf(event()), ego = person().copy(fix = null)).status)
        assertEquals(SwarmStatus.UNKNOWN, assess(listOf(event()), now = 16_000).status)
        assertEquals(SwarmStatus.UNKNOWN, assess(listOf(event(at = 3_000))).status)
        assertEquals(SwarmStatus.UNKNOWN, assess(listOf(event().copy(fix = fix().copy(synthetic = false)))).status)
    }

    @Test fun `only original sources count and retransmission cannot renew lifetime`() {
        val original = event()
        val copies = listOf(original, original, original.copy(observedAtMs = 10_000))
        val report = assess(copies, now = 11_000)
        assertEquals(SwarmStatus.CAUTION, report.status)
        assertEquals(1, report.independentSources)
        assertEquals(1, report.evidence.size)
        assertEquals(10_000L, report.oldestAgeMs)
        assertEquals(SwarmStatus.UNKNOWN, assess(copies, now = 16_000).status)
        assertEquals(2, assess(listOf(original, event("event-2", "B"))).independentSources)
        assertEquals(1, assess(listOf(original, event("event-2", "A"))).independentSources)
    }

    @Test fun `invalid evidence and remote lateral or behind reports cannot become an approach warning`() {
        val variants = listOf(
            event().copy(confidence = Double.NaN), event().copy(confidence = 0.1), event().copy(ttlMs = 0),
            event().copy(sourceId = ""), event().copy(eventId = ""), event().copy(positionAccuracyM = -1.0),
            event().copy(fix = fix(300.0)), event().copy(fix = fix(50.0, 100.0)), event().copy(fix = fix(-50.0)),
        )
        variants.forEach { assertEquals(it.toString(), SwarmStatus.UNKNOWN, assess(listOf(it)).status) }
    }

    @Test fun `unknown accuracy gives generic caution without a direction assignment or nominee`() {
        val result = assess(listOf(event().copy(positionAccuracyM = Double.POSITIVE_INFINITY)),
            ego = person().copy(positionAccuracyM = Double.POSITIVE_INFINITY, headingAccuracyDeg = Double.POSITIVE_INFINITY))
        assertEquals(SwarmStatus.CAUTION, result.status)
        assertEquals("Temporary obstruction reported (unverified)", result.title)
        assertTrue(result.advice.contains("location uncertainty"))
        assertNull(result.nominatedObserverId)
        assertEquals(DirectionRelation.UNCERTAIN, result.neighbours.single().relation)
    }

    @Test fun `manual observations need no invented detector confidence`() {
        val result = assess(listOf(event().copy(confidence = null)))
        assertEquals(SwarmStatus.CAUTION, result.status)
        assertEquals("Temporary obstruction reported (unverified)", result.title)
        assertTrue(result.advice.contains("without a detector confidence score"))
        assertNull(result.evidence.single().confidence)
    }

    @Test fun `local nominee is order independent and excludes opposing observers`() {
        val a = person("A", 80.0)
        val b = person("B", 90.0, 180.0).copy(positionAccuracyM = 1.0)
        val c = person()
        val events = listOf(event(), event("b", "B"))
        val first = SwarmAdvisor.evaluate(c, listOf(a, b), events, 2_000)
        val reversed = SwarmAdvisor.evaluate(c, listOf(b, a), events.reversed(), 2_000)
        assertEquals("A", first.nominatedObserverId)
        assertEquals(first, reversed)
    }

    @Test fun `cache is bounded deduplicated and original lifetime wins`() {
        val store = SwarmEvidenceStore(capacity = 2)
        assertTrue(store.ingest(event(), 2_000))
        assertFalse(store.ingest(event().copy(observedAtMs = 5_000), 5_000))
        assertEquals(1_000L, store.current(5_000).single().observedAtMs)
        assertTrue(store.ingest(event("two"), 5_000))
        assertFalse(store.ingest(event("three"), 5_000))
        assertEquals(listOf("event-1", "two"), store.current(5_000).map { it.eventId })
        assertTrue(store.current(16_000).isEmpty())
        assertFalse(store.ingest(event(), 16_000))
        assertFalse(store.ingest(event().copy(observedAtMs = 16_000), 16_000))
        assertTrue(store.ingest(event("three", at = 31_000), 31_000))
    }

    @Test fun `excessive caller lifetime is bounded and unknown never turns into clear`() {
        val longLived = event().copy(ttlMs = Long.MAX_VALUE)
        assertEquals(SwarmStatus.CAUTION, assess(listOf(longLived), now = 30_000).status)
        assertEquals(SwarmStatus.UNKNOWN, assess(listOf(longLived), now = 31_000).status)
        assertEquals(setOf(SwarmStatus.CAUTION, SwarmStatus.UNKNOWN), SwarmStatus.entries.toSet())
    }

    @Test fun `pedestrian observation takes priority while all relevant evidence is retained`() {
        val result = assess(listOf(event(), event("pedestrian").copy(kind = ObservationKind.PEDESTRIAN)))
        assertEquals("Pedestrian reported nearby", result.title)
        assertEquals(2, result.evidence.size)
    }

    @Test fun `unbounded caller input fails to unknown before sorting or grouping`() {
        val result = SwarmAdvisor.evaluate(person(), emptyList(), List(513) { event("$it") }, 2_000)
        assertEquals(SwarmStatus.UNKNOWN, result.status)
        assertEquals("Assessment capacity reached", result.title)
        assertTrue(result.evidence.isEmpty())
    }
}
