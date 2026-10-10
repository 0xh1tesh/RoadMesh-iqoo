package com.jsons.roadmesh.core.mesh

import com.jsons.roadmesh.core.protocol.Flags
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TestClock(var mono: Long = 0, var utc: Long = 3_600_000) : MeshClock {
    override fun monotonicMs() = mono
    override fun utcMs() = utc
    fun advance(ms: Long) { mono += ms; utc += ms }
}
private val demoBrake = LocalHazard(HazardKind.BRAKING, Provenance.DEMO, "test-scene")

class MeshEngineTest {
    private val clock = TestClock()
    private fun stage(role: Role, profile: MeshProfile = MeshProfile(), announce: Boolean = role == Role.RECEIVER) =
        MeshEngine(NodeId(role.stageId), role.stageId.toInt(), 42, profile, clock, PositionSource { stageFix(role) }, StageChain, announce) { 0 }
    private fun firstFrame() = stage(Role.OBSERVER).publish(demoBrake).filterIsInstance<MeshEffect.Transmit>().single().frame
    private fun bytes(frame: HazardEnvelope) = HazardEnvelopeCodec.encode(frame)
    private fun code(effects: List<MeshEffect>, reason: String) = effects.any { it is MeshEffect.Notice && it.code == reason }

    @Test fun threePhoneChainUsesTwoRadioLinksAndOneRelay() {
        val a = stage(Role.OBSERVER); val b = stage(Role.RELAY); val c = stage(Role.RECEIVER)
        val originated = a.publish(demoBrake).filterIsInstance<MeshEffect.Transmit>().single().frame
        assertEquals(58, bytes(originated).size)
        assertTrue(code(c.receive(bytes(originated)), "link_filtered"))
        assertEquals(1, b.receive(bytes(originated)).filterIsInstance<MeshEffect.Received>().size)
        clock.advance(250)
        val relayed = b.tick().filterIsInstance<MeshEffect.Transmit>().single().frame
        assertEquals(originated.id, relayed.id); assertEquals(NodeId(2), relayed.forwarder)
        assertEquals(0, relayed.packet.ttl); assertEquals(1, relayed.packet.hop); assertEquals(2, relayed.radioLinks)
        val observed = c.receive(bytes(relayed))
        assertEquals(1, observed.filterIsInstance<MeshEffect.Announce>().size)
        assertTrue(c.tick().filterIsInstance<MeshEffect.Transmit>().isEmpty())
        repeat(10) { assertTrue(code(c.receive(bytes(relayed)), "duplicate")) }
        assertTrue(code(a.receive(bytes(relayed)), "own_copy"))
    }

    @Test fun replayedDashcamPedestrianReachesTheReceiverAsAPedestrianCaution() {
        val a = stage(Role.OBSERVER); val b = stage(Role.RELAY); val c = stage(Role.RECEIVER)
        val origin = a.publish(LocalHazard(HazardKind.PEDESTRIAN, Provenance.REPLAY, "dashcam")).filterIsInstance<MeshEffect.Transmit>().single().frame
        assertEquals(1, b.receive(bytes(origin)).filterIsInstance<MeshEffect.Received>().size)
        clock.advance(250)
        val relayed = b.tick().filterIsInstance<MeshEffect.Transmit>().single().frame
        val announce = c.receive(bytes(relayed)).filterIsInstance<MeshEffect.Announce>().single()
        assertEquals(HazardKind.PEDESTRIAN, announce.kind)
        assertEquals(Provenance.REPLAY, announce.frame.provenance)
    }

    @Test fun unsignedEnvelopeRoundTripsFullIdentitiesAndRejectsUnknownVariants() {
        val original = firstFrame().let { it.copy(id = it.id.copy(origin = NodeId(0x1234567812345678)), forwarder = NodeId(0x1234567812345678)) }
        assertEquals(original, HazardEnvelopeCodec.decode(bytes(original)))
        Provenance.entries.forEach { provenance ->
            val variant = original.copy(provenance = provenance)
            assertEquals(variant, HazardEnvelopeCodec.decode(bytes(variant)))
        }
        assertNull(HazardEnvelopeCodec.decode(bytes(original).also { it[it.lastIndex] = 99 }))
        assertNull(HazardEnvelopeCodec.decode(bytes(original).copyOf(27)))
        val unknown = bytes(original).also { it[28] = 99 }; assertNull(HazardEnvelopeCodec.decode(unknown))
        val inconsistent = original.copy(packet = original.packet.copy(seq = 7)); assertNull(HazardEnvelopeCodec.decode(bytes(inconsistent)))
    }

    @Test fun randomWireInputNeverThrows() {
        val random = Random(9)
        repeat(10_000) { HazardEnvelopeCodec.decode(random.nextBytes(random.nextInt(0, 96))) }
    }

    @Test fun fullIdsAndSessionNonceAvoidLegacyAliasCollisionsAndShortRestartLoss() {
        val listener = MeshEngine(NodeId(9), 9, 42, MeshProfile(), clock, PositionSource { stageFix(Role.RELAY) }, OpenLinks, false) { 0 }
        val f = firstFrame()
        assertEquals(1, listener.receive(bytes(f)).filterIsInstance<MeshEffect.Received>().size)
        val other = f.copy(id = f.id.copy(origin = NodeId(0x100000001)), forwarder = NodeId(0x100000001))
        assertEquals(f.packet.senderId, other.packet.senderId)
        assertEquals(1, listener.receive(bytes(other)).filterIsInstance<MeshEffect.Received>().size)
        val restarted = f.copy(id = f.id.copy(session = 99))
        assertEquals(1, listener.receive(bytes(restarted)).filterIsInstance<MeshEffect.Received>().size)
        assertTrue(code(listener.receive(bytes(f)), "duplicate"))
    }

    @Test fun roadModeRejectsSyntheticAndReplayInputAndNeedsARealFix() {
        val p = MeshProfile(mode = Mode.ROAD, initialTtl = 1)
        var fix: GeoFix? = null
        val road = MeshEngine(NodeId(7), 7, 1, p, clock, PositionSource { fix })
        assertTrue(code(road.publish(LocalHazard(HazardKind.BRAKING, Provenance.LIVE, "imu")), "position_unavailable"))
        fix = stageFix(Role.RELAY).copy(synthetic = false)
        assertTrue(code(road.publish(demoBrake), "provenance_rejected"))
        assertTrue(code(road.publish(demoBrake.copy(provenance = Provenance.REPLAY)), "provenance_rejected"))
        assertTrue(code(road.receive(bytes(firstFrame())), "provenance_rejected"))
        assertEquals(1, road.publish(demoBrake.copy(provenance = Provenance.LIVE)).filterIsInstance<MeshEffect.Transmit>().size)
    }

    @Test fun freshnessWorksAcrossHourBoundaryAndRejectsOldOrFuturePackets() {
        assertEquals(150L, generationAgeMs(35_999, 3_600_050))
        assertEquals(-50L, generationAgeMs(0, 3_599_950))
        val f = firstFrame()
        clock.advance(6_000)
        assertTrue(code(stage(Role.RELAY).receive(bytes(f)), "stale_or_future"))
        val future = f.copy(packet = f.packet.copy(tGenDeci = 200))
        assertTrue(code(stage(Role.RELAY).receive(bytes(future)), "stale_or_future"))
    }

    @Test fun noHeadingOrOncomingTrafficDoesNotAnnounce() {
        val f = firstFrame().relayedBy(NodeId(2), 2)!!
        var fix = stageFix(Role.RECEIVER).copy(heading = 180.0)
        fun listener() = MeshEngine(NodeId(3), 3, 1, MeshProfile(), clock, PositionSource { fix }, StageChain) { 0 }
        assertTrue(listener().receive(bytes(f)).filterIsInstance<MeshEffect.Announce>().isEmpty())
        fix = fix.copy(heading = null)
        assertTrue(listener().receive(bytes(f)).filterIsInstance<MeshEffect.Announce>().isEmpty())
        val noHeading = f.copy(packet = f.packet.copy(flags = f.packet.flags or Flags.NO_HEADING))
        fix = stageFix(Role.RECEIVER)
        assertTrue(listener().receive(bytes(noHeading)).filterIsInstance<MeshEffect.Announce>().isEmpty())
    }

    @Test fun validatedDuplicateProgressCancelsPendingRelay() {
        val p = MeshProfile(initialTtl = 2, suppressionCopies = 2)
        val origin = stage(Role.OBSERVER, p).publish(demoBrake).filterIsInstance<MeshEffect.Transmit>().single().frame
        val listener = MeshEngine(NodeId(10), 10, 1, p, clock, PositionSource { stageFix(Role.RELAY) }, OpenLinks, false) { 0 }
        listener.receive(bytes(origin))
        assertEquals(1, listener.bounds.pending)
        val copy = origin.relayedBy(NodeId(11), 11)!!
        assertTrue(code(listener.receive(bytes(copy)), "duplicate"))
        assertEquals(1, listener.bounds.pending)
        assertTrue(code(listener.receive(bytes(copy.copy(forwarder = NodeId(12)))), "relay_suppressed"))
        clock.advance(300)
        assertTrue(listener.tick().filterIsInstance<MeshEffect.Transmit>().isEmpty())
    }

    @Test fun boundedTablesRejectOverflowWithoutEvictingLiveDedupKeys() {
        val p = MeshProfile(dedupCapacity = 64, neighborCapacity = 100, pendingCapacity = 2)
        val listener = MeshEngine(NodeId(200), 200, 1, p, clock, PositionSource { stageFix(Role.RELAY) }, OpenLinks, false) { 0 }
        val f = firstFrame()
        for (id in 1L..65) {
            val incoming = f.copy(id = f.id.copy(origin = NodeId(id)), forwarder = NodeId(id))
            val effects = listener.receive(bytes(incoming))
            if (id == 65L) assertTrue(code(effects, "dedup_full"))
        }
        assertEquals(MeshBounds(64, 65, 2), listener.bounds)
        assertTrue(code(listener.receive(bytes(f)), "duplicate"))
        clock.advance(31_000)
        listener.tick()
        assertEquals(MeshBounds(0, 0, 0), listener.bounds)
    }

    @Test fun neighborTableAndOriginRateHaveIndependentLimits() {
        val p = MeshProfile(neighborCapacity = 3)
        val listener = MeshEngine(NodeId(20), 20, 1, p, clock, PositionSource { stageFix(Role.RELAY) }, OpenLinks, false) { 0 }
        val f = firstFrame()
        for (id in 1L..4) {
            val effects = listener.receive(bytes(f.copy(id = f.id.copy(origin = NodeId(id)), forwarder = NodeId(id))))
            if (id == 4L) assertTrue(code(effects, "neighbors_full"))
        }
        val next = f.copy(id = f.id.copy(sequence = 2), packet = f.packet.copy(seq = 2))
        assertEquals(1, listener.receive(bytes(next)).filterIsInstance<MeshEffect.Received>().size)
        assertTrue(code(listener.receive(bytes(next.copy(id = next.id.copy(sequence = 3), packet = next.packet.copy(seq = 3)))), "origin_rate"))
    }

    @Test fun expiredPendingRelaysAreNotSentAndTtlCannotBeExtended() {
        val f = firstFrame(); val b = stage(Role.RELAY)
        assertTrue(code(b.receive(bytes(f.copy(packet = f.packet.copy(ttl = 2)))), "hop_budget"))
        b.receive(bytes(f)); clock.advance(5_100)
        assertTrue(b.tick().filterIsInstance<MeshEffect.Transmit>().isEmpty())
    }

    @Test fun geometryAndProfileValidationAreMeaningful() {
        assertEquals(100.0, distanceM(stageFix(Role.OBSERVER), stageFix(Role.RELAY)), .5)
        assertEquals(0.0, bearing(stageFix(Role.RELAY), stageFix(Role.OBSERVER)), .01)
        assertEquals(2.0, angleDifference(359.0, 1.0), .01)
        assertThrows(IllegalArgumentException::class.java) { MeshProfile(pendingCapacity = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { MeshProfile(rangeM = Double.NaN).validate() }
    }
}
