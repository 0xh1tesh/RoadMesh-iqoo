package com.jsons.roadmesh.core.mesh

import org.junit.Assert.*
import org.junit.Test

class AutomaticDeliveryTest {
    @Test fun originalCaptureAgeIncludesInferenceAndQueueTime() {
        val clock = TestClock(10_000, 3_610_000)
        val engine = MeshEngine(NodeId(1), 1, 2, MeshProfile(), clock, PositionSource { stageFix(0) })
        val event = LocalHazard(HazardKind.BRAKING, Provenance.REPLAY, "automatic temporal test", capturedAtMs = 9_000)
        val frame = engine.publish(event).filterIsInstance<MeshEffect.Transmit>().single().frame
        assertEquals(1_000, generationAgeMs(frame.packet.tGenDeci, clock.utcMs()))
        clock.advance(500)
        assertEquals(1_500, generationAgeMs(frame.packet.tGenDeci, clock.utcMs()))
    }

    @Test fun delayedOrFutureSensorCandidatesCannotBeReoriginatedAsFresh() {
        val clock = TestClock(10_000, 3_610_000)
        val engine = MeshEngine(NodeId(1), 1, 2, MeshProfile(), clock, PositionSource { stageFix(0) })
        for (capture in listOf(-1L, 4_999L, 5_000L, 10_001L)) {
            val fx = engine.publish(LocalHazard(HazardKind.BRAKING, Provenance.REPLAY, "old", capturedAtMs = capture))
            assertTrue(fx.any { it is MeshEffect.Notice && it.code == "source_stale_or_future" })
            assertTrue(fx.none { it is MeshEffect.Transmit })
        }
    }

    @Test fun originEffectRetainsExactGnssUncertaintyWithoutReadingAnotherFix() {
        val clock = TestClock(10_000, 3_610_000)
        val measured = GeoFix(12.9716, 77.5946, 25.0, 8.0, false, accuracyM = 7.0,
            headingAccuracyDeg = 4.0, sampledAtMs = 9_900, speedAccuracyMps = 0.3)
        var reads = 0
        val engine = MeshEngine(NodeId(1), 1, 2, MeshProfile(mode = Mode.ROAD), clock,
            PositionSource { reads++; measured })
        val effects = engine.publish(LocalHazard(HazardKind.SLOWDOWN, Provenance.LIVE, "camera+speed", capturedAtMs = 9_900))
        val originated = effects.filterIsInstance<MeshEffect.Originated>().single()
        assertEquals(1, reads)
        assertEquals(measured, originated.originFix)
        assertEquals(measured.lat, originated.frame.packet.latE7 / 1e7, 1e-7)
        assertEquals(7.0, originated.originFix!!.accuracyM!!, 0.0)
    }

    @Test fun urgentEventsDisplaceContextUnderPressureAndKeepEarliestDeadline() {
        val q = PriorityOutbox<String>(2)
        assertTrue(q.offer("presence", PriorityOutbox.Priority.CONTEXT, 0, 2_000))
        assertTrue(q.offer("query", PriorityOutbox.Priority.CONTEXT, 0, 2_000))
        assertTrue(q.offer("braking", PriorityOutbox.Priority.URGENT, 0, 1_000))
        assertFalse(q.offer("extra query", PriorityOutbox.Priority.CONTEXT, 0, 3_000))
        assertEquals(2, q.size())
        assertEquals("braking", q.poll(100))
    }

    @Test fun expiresCoalescesPresenceAndPreventsPermanentContextStarvation() {
        val q = PriorityOutbox<String>(8, urgentQuota = 2)
        q.offer("old presence", PriorityOutbox.Priority.CONTEXT, 0, 100, "presence")
        q.offer("latest presence", PriorityOutbox.Priority.CONTEXT, 0, 1_000, "presence")
        repeat(3) { q.offer("urgent$it", PriorityOutbox.Priority.URGENT, 0, 500) }
        assertEquals(4, q.size())
        assertEquals("urgent0", q.poll(10)); assertEquals("urgent1", q.poll(10))
        assertEquals("latest presence", q.poll(10))
        assertNull(q.poll(500))
    }

    @Test fun continuousConditionsDoNotStarveContextEnrichment() {
        val q = PriorityOutbox<String>(8, urgentQuota = 2)
        q.offer("enrichment", PriorityOutbox.Priority.CONTEXT, 0, 1_000)
        repeat(3) { q.offer("condition$it", PriorityOutbox.Priority.CONDITION, 0, 1_000) }
        assertEquals("condition0", q.poll(0))
        assertEquals("condition1", q.poll(0))
        assertEquals("enrichment", q.poll(0))
    }

    @Test fun allThreePrioritiesMakeProgressUnderSustainedLoad() {
        val q = PriorityOutbox<String>(32, urgentQuota = 2)
        repeat(8) { i ->
            q.offer("urgent$i", PriorityOutbox.Priority.URGENT, 0, 1_000)
            q.offer("condition$i", PriorityOutbox.Priority.CONDITION, 0, 1_000)
            q.offer("context$i", PriorityOutbox.Priority.CONTEXT, 0, 1_000)
        }
        assertEquals(listOf("urgent0", "urgent1", "condition0", "context0", "urgent2", "urgent3", "condition1", "context1"),
            (0 until 8).map { q.poll(0) })
    }
}
