package com.jsons.roadmesh.core.perception

import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.core.road.RoadMap
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Actual analyzer -> local event -> packet codec -> relay -> recipient state machines; the radio is simulated. */
class AutomaticMeshIntegrationTest {
    private class Clock : MeshClock {
        var now = 0L
        override fun monotonicMs() = now
        override fun utcMs() = 3_600_000L + now
    }
    private val adjacent = LinkPolicy { local, sender, _ -> abs(local.value - sender.value) == 1L }
    private fun effect(effects: List<MeshEffect>, code: String) = effects.any { it is MeshEffect.Notice && it.code == code }
    private fun bytes(frame: HazardEnvelope) = HazardEnvelopeCodec.encode(frame)
    private fun hazard(event: AutomaticTrafficEvent, provenance: Provenance) = LocalHazard(event.kind, provenance,
        "automatic-camera-${event.kind.name.lowercase()}",
        HazardDetail(event.estimatedRangeM?.roundToInt(), VehicleType.CAR, event.detectorConfidence),
        (event.atS * 1_000).roundToLong())

    @Test fun automaticSlowdownTraversesThreeNodesPreservingCaptureAndOneOrigin() {
        // Both transport modes are exercised with deterministic inputs; this does not certify live GNSS or BLE.
        for ((mode, provenance) in listOf(Mode.STAGE to Provenance.REPLAY, Mode.ROAD to Provenance.LIVE)) {
            val clock = Clock()
            val profile = MeshProfile(mode = mode, initialTtl = 1)
            fun fix(index: Int) = stageFix(index).copy(synthetic = mode == Mode.STAGE)
            fun phone(id: Long) = MeshEngine(NodeId(id), id.toInt(), 42, profile, clock,
                PositionSource { fix((id - 1).toInt()) }, adjacent, random = { 0 })
            val a = phone(1); val b = phone(2); val c = phone(3)
            val event = AutomationScenario.run("collective_slowdown").events.single()
            assertEquals(HazardKind.SLOWDOWN, event.kind)
            val local = hazard(event, provenance)
            clock.now = local.capturedAtMs!! + 350 // Deliberate inference/queueing delay must remain in event age.
            val origin = a.publish(local).filterIsInstance<MeshEffect.Transmit>().single().frame
            assertEquals(350L, generationAgeMs(origin.packet.tGenDeci, clock.utcMs()))
            assertTrue(effect(c.receive(bytes(origin)), "link_filtered"))
            assertEquals(1, b.receive(bytes(origin)).filterIsInstance<MeshEffect.Received>().size)
            clock.now += 250
            val relay = b.tick().filterIsInstance<MeshEffect.Transmit>().single().frame
            val received = c.receive(bytes(relay))
            val announcement = received.filterIsInstance<MeshEffect.Announce>().single()
            assertEquals(event.kind, announcement.kind)
            assertEquals(origin.id, announcement.frame.id)
            assertEquals(NodeId(1), announcement.frame.id.origin)
            assertEquals(NodeId(2), announcement.frame.forwarder)
            assertEquals(provenance, announcement.frame.provenance)
            assertEquals(origin.packet.tGenDeci, announcement.frame.packet.tGenDeci)
            assertEquals(600L, generationAgeMs(announcement.frame.packet.tGenDeci, clock.utcMs()))
            assertEquals(1, relay.hop)
            assertEquals(0, relay.ttl)
            assertEquals(origin.packet.p2, relay.packet.p2)

            val map = RoadMap()
            val age = generationAgeMs(relay.packet.tGenDeci, clock.utcMs())
            val detail = HazardDetail.from(relay.packet.p1, relay.packet.p2)!!
            fun remember() = map.add(event.kind, fix(0), relay.id.origin, clock.now, detail.confidence, false,
                observationId = relay.id, capturedMs = clock.now - age,
                capturedUtcMs = clock.utcMs() - age, provenance = relay.provenance)
            remember()
            val originalCapture = map.all.single().lastSeenMs
            repeat(10) {
                val duplicate = c.receive(bytes(relay))
                assertTrue(effect(duplicate, "duplicate"))
                assertTrue(duplicate.none { it is MeshEffect.Announce || it is MeshEffect.Received })
            }
            clock.now += 1_000
            // Even an erroneous caller trying to re-age a copied id cannot refresh the existing map evidence.
            remember()
            assertEquals(setOf(NodeId(1)), map.all.single().reporters)
            assertEquals(1, map.all.single().evidence.size)
            assertEquals(originalCapture, map.all.single().lastSeenMs)
            assertTrue(c.tick().filterIsInstance<MeshEffect.Transmit>().isEmpty())
        }
    }

    @Test fun replayedAutomaticDetectionCannotEnterRoadNetworkAsLiveEvidence() {
        val clock = Clock()
        val event = AutomationScenario.run("rapid_approach").events.single()
        clock.now = (event.atS * 1_000).roundToLong() + 100
        val replay = MeshEngine(NodeId(1), 1, 1, MeshProfile(initialTtl = 1), clock,
            PositionSource { stageFix(0) }, random = { 0 })
        val origin = replay.publish(hazard(event, Provenance.REPLAY)).filterIsInstance<MeshEffect.Transmit>().single().frame
        val road = MeshEngine(NodeId(2), 2, 2, MeshProfile(mode = Mode.ROAD, initialTtl = 1), clock,
            PositionSource { stageFix(1).copy(synthetic = false) }, random = { 0 })
        val rejected = road.receive(bytes(origin))
        assertTrue(effect(rejected, "provenance_rejected"))
        assertTrue(rejected.none { it is MeshEffect.Received || it is MeshEffect.Announce })
    }

    @Test fun anInferenceQueuedPastFreshnessLimitIsNotPublishedAsNew() {
        val clock = Clock()
        val event = AutomationScenario.run("host_hard_braking").events.single()
        val local = hazard(event, Provenance.REPLAY)
        clock.now = local.capturedAtMs!! + 5_000
        val engine = MeshEngine(NodeId(1), 1, 1, MeshProfile(), clock, PositionSource { stageFix(0) })
        val rejected = engine.publish(local)
        assertTrue(effect(rejected, "source_stale_or_future"))
        assertTrue(rejected.none { it is MeshEffect.Transmit })
    }
}
