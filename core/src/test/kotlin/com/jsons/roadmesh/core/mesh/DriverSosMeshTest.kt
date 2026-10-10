package com.jsons.roadmesh.core.mesh

import com.jsons.roadmesh.core.road.RoadMap
import org.junit.Assert.*
import org.junit.Test

class DriverSosMeshTest {
    private val clock = TestClock()
    private val metre = 1.0 / 111_195.0
    /** A point [northM] metres north of the origin, on one meridian, so bearings are exactly north or south. */
    private fun at(northM: Double, heading: Double) = GeoFix(12.0 + northM * metre, 77.0, heading, 15.0, true)
    private fun engine(id: Long, fix: () -> GeoFix, announce: Boolean) =
        MeshEngine(NodeId(id), id.toInt(), id, MeshProfile(initialTtl = 1), clock, PositionSource { fix() }, OpenLinks, announce, random = { 0 })
    private fun send(sender: MeshEngine, kind: HazardKind = HazardKind.DRIVER_SOS) =
        sender.publish(LocalHazard(kind, Provenance.DEMO, "driver camera")).filterIsInstance<MeshEffect.Transmit>().single().frame
    private fun announces(listener: MeshEngine, frame: HazardEnvelope) =
        listener.receive(HazardEnvelopeCodec.encode(frame)).filterIsInstance<MeshEffect.Announce>()

    @Test fun aFollowingDriverIsToldInCautionWordsAndNotInEmergencyOnes() {
        val sender = engine(1, { at(100.0, 0.0) }, false)
        val listener = engine(9, { at(0.0, 0.0) }, true)
        val spoken = announces(listener, send(sender)).single()
        assertEquals("Caution. A driver ahead may be drowsy. Keep your distance.", spoken.kind.speech)
        assertEquals(HazardUse.CAUTION, spoken.kind.use)
        assertFalse(spoken.kind.speech.contains("Emergency"))
    }

    @Test fun anOncomingDriverWithinThreeHundredMetresHearsItButNotBeyond() {
        val sender = engine(1, { at(200.0, 0.0) }, false)             // 200 m south of nothing: sender is north, heading north
        val listener = engine(9, { at(400.0, 180.0) }, true)           // north of the sender, heading south: they approach head-on
        assertEquals(1, announces(listener, send(sender)).size)       // 200 m apart
        clock.advance(31_000)
        val far = engine(2, { at(0.0, 0.0) }, false)
        assertTrue(announces(engine(8, { at(350.0, 180.0) }, true), send(far)).isEmpty()) // 350 m apart
    }

    @Test fun oncomingOnlyCountsForADriverSosNotForOtherCautions() {
        val sender = engine(1, { at(200.0, 0.0) }, false)
        val listener = engine(9, { at(400.0, 180.0) }, true)
        assertTrue(announces(listener, send(sender, HazardKind.BRAKING)).isEmpty())
    }

    @Test fun aDriverMovingAwayBehindYouIsSilent() {
        val sender = engine(1, { at(-100.0, 0.0) }, false)             // 100 m behind, same direction
        val listener = engine(9, { at(0.0, 0.0) }, true)
        assertTrue(announces(listener, send(sender)).isEmpty())
        val passed = engine(2, { at(600.0, 0.0) }, false)              // far ahead beyond the 400 m relevance
        assertTrue(announces(listener, send(passed)).isEmpty())
    }

    @Test fun anOncomingDriverAlreadyPastYouIsSilent() {
        val sender = engine(1, { at(400.0, 0.0) }, false)              // north of the listener, heading north (away)
        val listener = engine(9, { at(300.0, 180.0) }, true)           // heading south, sender is behind it
        assertTrue(announces(listener, send(sender)).isEmpty())
    }

    @Test fun aSustainedSosIsSpokenOnceThenAgainAfterAThirtySecondGap() {
        val sender = engine(1, { at(100.0, 0.0) }, false)
        val listener = engine(9, { at(0.0, 0.0) }, true)
        val spokenAt = ArrayList<Long>()
        for (beacon in 0 until 20) {                                  // a beacon every 2 s for 40 s
            if (announces(listener, send(sender)).isNotEmpty()) spokenAt += clock.mono
            clock.advance(2_000)
        }
        assertEquals(listOf(0L, 30_000L), spokenAt)
    }

    @Test fun twoDifferentDriversAreEachSpoken() {
        val a = engine(1, { at(100.0, 0.0) }, false); val b = engine(2, { at(150.0, 0.0) }, false)
        val listener = engine(9, { at(0.0, 0.0) }, true)
        assertEquals(1, announces(listener, send(a)).size)
        clock.advance(2_000)
        assertEquals(1, announces(listener, send(b)).size)
    }

    @Test fun theDriverOkMessageIsRelayedAndStoredButNeverSpoken() {
        val sender = engine(1, { at(100.0, 0.0) }, false)
        val listener = engine(9, { at(0.0, 0.0) }, true)
        val ok = send(sender, HazardKind.DRIVER_OK)
        val effects = listener.receive(HazardEnvelopeCodec.encode(ok))
        assertTrue(effects.any { it is MeshEffect.Received })
        assertTrue(effects.none { it is MeshEffect.Announce })
        assertEquals(HazardKind.DRIVER_OK, HazardEnvelopeCodec.decode(HazardEnvelopeCodec.encode(ok))!!.kind)
    }

    @Test fun driverOkAndSosKeepApartOnTheWire() {
        assertEquals(HazardKind.DRIVER_SOS, HazardKind.from(93, 0))
        assertEquals(HazardKind.DRIVER_OK, HazardKind.from(93, 100))
        assertEquals(HazardUse.CANCEL, HazardKind.DRIVER_OK.use)
        assertEquals(10, HazardKind.DRIVER_SOS.lifetimeS)
    }

    @Test fun anSosExpiresTenSecondsAfterTheLastBeaconAndAnOkRemovesItAtOnce() {
        val map = RoadMap()
        val here = at(100.0, 0.0)
        val first = MessageId(NodeId(1), 1, 1); val second = MessageId(NodeId(1), 1, 2); val other = MessageId(NodeId(2), 1, 1)
        map.add(HazardKind.DRIVER_SOS, here, NodeId(1), 1_000, 0.7, false, observationId = first, capturedMs = 1_000, provenance = Provenance.DEMO)
        assertEquals(1, map.all.size)
        map.expire(10_999); assertEquals(1, map.all.size)
        map.expire(11_000); assertTrue(map.all.isEmpty())
        map.add(HazardKind.DRIVER_SOS, here, NodeId(1), 20_000, 0.7, false, observationId = second, capturedMs = 20_000, provenance = Provenance.DEMO)
        map.add(HazardKind.DRIVER_SOS, here, NodeId(2), 20_100, 0.7, false, observationId = other, capturedMs = 20_100, provenance = Provenance.DEMO)
        map.cancel(NodeId(1), 20_200)
        assertEquals(setOf(NodeId(2)), map.all.single().reporters)   // the other phone's own report stays
        map.cancel(NodeId(2), 20_300)
        assertTrue(map.all.isEmpty())
    }
}
