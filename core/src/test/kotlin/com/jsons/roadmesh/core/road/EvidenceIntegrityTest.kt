package com.jsons.roadmesh.core.road

import com.jsons.roadmesh.core.mesh.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EvidenceIntegrityTest {
    private val here = GeoFix(12.0, 77.0, 0.0, 10.0, false, 4.0, 3.0)
    private val at = moved(here, 120.0, 0.0).copy(accuracyM = 5.0)
    private val id = MessageId(NodeId(11), 22, 33)
    private val query = MessageId(NodeId(99), 88, 1)
    private fun record(id: MessageId = this.id, age: Int = 10, provenance: Provenance = Provenance.LIVE) =
        ControlFrame.Record(HazardKind.ROAD_NARROWED, 120, 0, age, 1, 0.8, id, 90_000, provenance, 5.0)
    private fun reply(holder: Long, records: List<ControlFrame.Record>) =
        ControlFrame.Reply(query, NodeId(holder), NodeId(holder), 5, 1, 1, here, records)

    @Test fun copiesAcrossHoldersNeverBecomeIndependentObservers() {
        val copies = (1L..5L).map { reply(it, listOf(record())) }
        val picture = RoadPicture.combine(here, emptyList(), copies, 100_000, 100_000)
        assertEquals(1, picture.items.single().reporters)
        assertEquals(setOf(id), picture.items.single().evidenceIds)
        assertTrue(picture.items.single().lineageKnown)
        val supported = RoadPicture.combine(here, emptyList(), copies + reply(6, listOf(record(id.copy(origin = NodeId(12))))), 100_000, 100_000)
        assertEquals(2, supported.items.single().reporters)
    }

    @Test fun localCopyAndRemoteCopyOfSameObservationCountOnce() {
        val map = RoadMap()
        map.add(HazardKind.ROAD_NARROWED, at, id.origin, 100_000, 0.8, false,
            observationId = id, capturedMs = 90_000, capturedUtcMs = 90_000, provenance = Provenance.LIVE)
        val picture = RoadPicture.combine(here, map.all, listOf(reply(2, listOf(record()))), 100_000, 100_000)
        assertEquals(1, picture.items.single().reporters)
        assertFalse("holding somebody else's observation is not own sensing", picture.items.single().fromSelf)
    }

    @Test fun oldCountsAreUnresolvedAndNeverAddedToIdentifiedSupport() {
        val legacy = ControlFrame.Record(HazardKind.ROAD_NARROWED, 120, 0, 10, 200, 0.9)
        val picture = RoadPicture.combine(here, emptyList(), listOf(reply(1, listOf(legacy)), reply(2, listOf(legacy)), reply(3, listOf(record()))), 100_000, 100_000)
        assertEquals(1, picture.items.sumOf { it.reporters })
        assertEquals(1, picture.items.count { !it.lineageKnown })
        assertTrue(picture.facts().contains("source lineage unknown"))
    }

    @Test fun replayAndLiveNeverCorroborateEachOther() {
        val picture = RoadPicture.combine(here, emptyList(), listOf(reply(1, listOf(record())),
            reply(2, listOf(record(id.copy(origin = NodeId(12)), provenance = Provenance.REPLAY)))), 100_000, 100_000)
        assertEquals(2, picture.items.size)
        assertTrue(picture.items.all { it.reporters == 1 })
    }

    @Test fun duplicateCannotResetCaptureOrExpiryEvenAfterCapacityEviction() {
        val map = RoadMap(capacity = 1)
        map.add(HazardKind.ROAD_NARROWED, at, id.origin, 100_000, 0.8, true,
            observationId = id, capturedMs = 90_000, capturedUtcMs = 90_000, provenance = Provenance.LIVE)
        assertNull(map.add(HazardKind.ROAD_NARROWED, at, id.origin, 110_000, 1.0, true, observationId = id))
        assertEquals(90_000, map.all.single().lastSeenMs)
        map.add(HazardKind.POTHOLE, moved(at, 200.0, 0.0), NodeId(12), 110_000, 0.7, true,
            observationId = id.copy(origin = NodeId(12)))
        assertNull(map.add(HazardKind.ROAD_NARROWED, at, id.origin, 120_000, 0.8, false, observationId = id))
        assertEquals(HazardKind.POTHOLE, map.all.single().kind)
    }

    @Test fun newEvidenceExpiresSeparatelyAndCannotKeepOldSourcesInSupport() {
        val map = RoadMap()
        map.add(HazardKind.ROAD_NARROWED, at, id.origin, 100_000, 0.8, true, observationId = id, capturedMs = 0)
        map.add(HazardKind.ROAD_NARROWED, at, NodeId(12), 110_000, 0.9, false,
            observationId = id.copy(origin = NodeId(12)), capturedMs = 110_000)
        map.expire(120_000)
        assertEquals(setOf(NodeId(12)), map.all.single().reporters)
        assertFalse(map.all.single().own)
        map.expire(230_000)
        assertTrue(map.all.isEmpty())
        assertNull(map.add(HazardKind.ROAD_NARROWED, at, id.origin, 240_000, 0.8, false, observationId = id, capturedMs = 0))
        assertNull(map.add(HazardKind.ROAD_NARROWED, at, id.origin, 240_000, 0.8, false, observationId = id, capturedMs = 250_000))
    }

    @Test fun originalUtcCaptureDefeatsForgedYoungAgeAndDelayedRepliesAgeDuringCollection() {
        assertTrue(RoadPicture.combine(here, emptyList(), listOf(reply(1, listOf(record(age = 0)))), 220_000, 220_000).items.isEmpty())
        assertTrue(RoadPicture.combine(here, emptyList(), listOf(reply(1, listOf(record().copy(capturedUtcMs = 101_000)))), 100_000, 100_000).items.isEmpty())
        val delayed = reply(1, listOf(record())).copy(receivedAtMs = 100_000)
        assertEquals(13, RoadPicture.combine(here, emptyList(), listOf(delayed), 102_100, 102_100).items.single().ageS)
    }

    @Test fun wirePreservesIdentityProvenanceAndUncertaintyWithinBudget() {
        val frame = reply(4, List(ControlCodec.MAX_RECORDS) { record(id.copy(sequence = it)) })
        val encoded = ControlCodec.encode(frame)
        assertEquals(217, encoded.size)
        assertTrue(encoded.size <= ControlCodec.MAX_BYTES)
        val decoded = ControlCodec.decode(encoded) as ControlFrame.Reply
        assertEquals(frame.records, decoded.records)
        assertEquals(4.0, decoded.fix.accuracyM!!, 0.001)
        assertEquals(3.0, decoded.fix.headingAccuracyDeg!!, 0.001)
        assertEquals(frame.records, (ControlCodec.decode(ControlCodec.encode(decoded.relayedBy(NodeId(5))!!)) as ControlFrame.Reply).records)
        assertNull(ControlCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(ControlCodec.decode(encoded + byteArrayOf(0)))
    }

    @Test fun versionOneDecodeRetainsUnknownLineageAndUnknownAccuracy() {
        // A genuine v1 layout: header54 + one record10. No identity/uncertainty bytes exist.
        val b = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        b.put(0x53); b.put(0x13)
        b.putLong(99); b.putLong(88); b.putInt(1); b.putLong(2); b.putLong(3)
        b.put(5); b.put(1); b.put(1)
        b.putInt(120_000_000); b.putInt(770_000_000); b.putShort(1_000); b.put(0); b.put(0); b.put(1)
        b.put(HazardKind.POTHOLE.cause.toByte()); b.put(0); b.putShort(120); b.putShort(0); b.putShort(10); b.put(200.toByte()); b.put(80)
        val decoded = ControlCodec.decode(b.array()) as ControlFrame.Reply
        assertNull(decoded.fix.accuracyM)
        assertNull(decoded.records.single().observationId)
        assertFalse(RoadPicture.combine(here, emptyList(), listOf(decoded), 100_000).items.single().lineageKnown)
        assertEquals(0, RoadPicture.combine(here, emptyList(), listOf(decoded), 100_000).items.single().reporters)
    }

    @Test fun missingHeadingAndLowSpeedsNeverInventAnArrivalWindow() {
        assertTrue(RoadPicture.combine(here.copy(heading = null), emptyList(), listOf(reply(1, listOf(record()))), 100_000).items.isEmpty())
        for (speed in listOf(0.0, 0.1, 1.1, 2.0, 4.1, 15.0, Double.NaN)) {
            val f = RoadPicture.forecast(speed, listOf(PicturePeer(NodeId(1), 0, 100, 0.0, 1, 0)), emptyList())
            assertNull(f.recommendedKmh)
            assertNull(f.meetS)
            assertTrue(f.advice.single().contains("Clearance time is unknown"))
        }
    }

    @Test fun queryClosesAtDeadlineAndDoesNotRetainUnboundedReplies() {
        val queries = RoadQueries(NodeId(99), 88, windowMs = 2_500, records = { _, _, _ -> emptyList() }, here = { here }, rank = { 0 })
        val q = queries.ask(here, 100_000)
        for (holder in 1L..100L) queries.receive(reply(holder, listOf(record())).copy(query = q.id), 101_000)
        assertEquals(RoadQueries.MAX_REPLIES, queries.queries.single().replies.size)
        queries.receive(reply(200, listOf(record())).copy(query = q.id), 102_500)
        assertEquals(RoadQueries.MAX_REPLIES, queries.tick(102_500).single().replies.size)
    }

    @Test fun roadNarrowingSurvivesExistingHazardEnvelopeAndRelay() {
        val clock = TestClock(mono = 100_000, utc = 3_700_000)
        val engine = MeshEngine(NodeId(1), 1, 42, MeshProfile(), clock, PositionSource { stageFix(0) }, OpenLinks, true, random = { 0 })
        val frame = engine.publish(LocalHazard(HazardKind.ROAD_NARROWED, Provenance.DEMO, "manual test"))
            .filterIsInstance<MeshEffect.Transmit>().single().frame
        val relayed = HazardEnvelopeCodec.decode(HazardEnvelopeCodec.encode(frame.relayedBy(NodeId(2), 2)!!))!!
        assertEquals(HazardKind.ROAD_NARROWED, relayed.kind)
        assertEquals(frame.id, relayed.id)
        assertEquals(frame.packet.tGenDeci, relayed.packet.tGenDeci)
    }

    @Test fun noClockRewriteOrRelayCanMakeExpiredOriginalEvidenceFresh() {
        val forwarded = reply(2, listOf(record(age = 0))).relayedBy(NodeId(3))!!
        val decoded = ControlCodec.decode(ControlCodec.encode(forwarded)) as ControlFrame.Reply
        assertTrue(RoadPicture.combine(here, emptyList(), listOf(decoded), 220_000, 220_000).items.isEmpty())
    }

    @Test fun opposingAndParallelRoadPeersDoNotProduceAQueueForecast() {
        val opposite = reply(2, emptyList()).copy(fix = moved(here, 120.0, 0.0).copy(heading = 180.0, speedMps = 0.0))
        val parallel = reply(3, emptyList()).copy(fix = moved(here, 120.0, 60.0).copy(heading = 0.0, speedMps = 0.0))
        val picture = RoadPicture.combine(here, emptyList(), listOf(opposite, parallel), 100_000)
        assertTrue(picture.peers.isEmpty())
        assertNull(picture.forecast.slowdownAheadM)
    }

    @Test fun laterQueryCanFillMissingAccuracyButCannotRefreshSameObservation() {
        val map = RoadMap()
        map.add(HazardKind.ROAD_NARROWED, at.copy(accuracyM = null), id.origin, 100_000, 0.8, false,
            observationId = id, capturedMs = 90_000, capturedUtcMs = 90_000, provenance = Provenance.LIVE)
        map.add(HazardKind.ROAD_NARROWED, at, id.origin, 110_000, 1.0, false,
            observationId = id, capturedMs = 110_000, capturedUtcMs = 90_000, provenance = Provenance.LIVE)
        assertEquals(5.0, map.all.single().accuracyM!!, 0.001)
        assertEquals(90_000, map.all.single().lastSeenMs)
        assertEquals(90_000, map.all.single().evidence.single().capturedMs)
        assertEquals(0.8, map.all.single().confidence!!, 0.001)
        assertEquals(1, map.all.single().reporters.size)
        map.expire(210_000)
        assertTrue(map.all.isEmpty())
    }

    @Test fun inconsistentCopiesOfOneIdentityDoNotBecomeSeparateHazards() {
        val picture = RoadPicture.combine(here, emptyList(), listOf(reply(1, listOf(record())),
            reply(2, listOf(record().copy(northM = 500)))), 100_000, 100_000)
        assertTrue(picture.items.isEmpty())
    }

    @Test fun expiredCopyIsNotDiscardedBeforeItCanConstrainAYoungerDuplicate() {
        val picture = RoadPicture.combine(here, emptyList(), listOf(reply(1, listOf(record(age = 120))),
            reply(2, listOf(record(age = 10)))), 100_000, 100_000)
        assertTrue(picture.items.isEmpty())
    }

    @Test fun twoSourcesCanEnrichTheirOwnAccuracyWithoutRefreshingOrBorrowingSupport() {
        val map = RoadMap()
        val secondId = id.copy(origin = NodeId(12))
        val secondAt = moved(at, 10.0, 0.0)
        map.add(HazardKind.ROAD_NARROWED, at.copy(accuracyM = null), id.origin, 100_000, 0.6, false,
            observationId = id, capturedMs = 90_000, capturedUtcMs = 90_000, provenance = Provenance.LIVE)
        map.add(HazardKind.ROAD_NARROWED, secondAt, secondId.origin, 100_500, 0.7, false,
            observationId = secondId, capturedMs = 91_000, capturedUtcMs = 91_000, provenance = Provenance.LIVE)
        assertNull(map.all.single().accuracyM)

        // A contradictory original capture is not permission to edit this identity's metadata.
        assertNull(map.add(HazardKind.ROAD_NARROWED, at, id.origin, 101_000, 1.0, false,
            observationId = id, capturedUtcMs = 90_001, provenance = Provenance.LIVE))
        map.add(HazardKind.ROAD_NARROWED, at, id.origin, 101_000, 1.0, false,
            observationId = id, capturedMs = 101_000, capturedUtcMs = 90_000, provenance = Provenance.LIVE)
        val partlyKnown = map.all.single()
        assertNull("The second source cannot borrow the first source's accuracy", partlyKnown.accuracyM)
        assertEquals(5.0, partlyKnown.evidence.single { it.id == id }.fix!!.accuracyM!!, 0.001)
        assertNull(partlyKnown.evidence.single { it.id == secondId }.fix!!.accuracyM)

        map.add(HazardKind.ROAD_NARROWED, secondAt.copy(accuracyM = 6.0), secondId.origin, 102_000, 1.0, false,
            observationId = secondId, capturedMs = 102_000, capturedUtcMs = 91_000, provenance = Provenance.LIVE)
        val known = map.all.single()
        assertEquals(setOf(id, secondId), known.evidence.map { it.id }.toSet())
        assertEquals(setOf(90_000L, 91_000L), known.evidence.map { it.capturedMs }.toSet())
        assertEquals(setOf(0.6, 0.7), known.evidence.map { it.confidence }.toSet())
        assertEquals(91_000, known.lastSeenMs)
        assertEquals(16.0, known.accuracyM!!, 0.05)
        val outgoing = map.records(here, 102_000, 1_000.0).associateBy { it.observationId }
        assertEquals(120, outgoing.getValue(id).northM)
        assertEquals(130, outgoing.getValue(secondId).northM)
        assertEquals(6.0, outgoing.getValue(id).accuracyM!!, 0.001)
        assertEquals(7.0, outgoing.getValue(secondId).accuracyM!!, 0.001)
        map.expire(210_000)
        assertEquals(listOf(secondId), map.all.single().evidence.map { it.id })
        map.expire(211_000)
        assertTrue(map.all.isEmpty())
    }
}
