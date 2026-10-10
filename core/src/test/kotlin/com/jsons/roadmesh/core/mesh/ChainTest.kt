package com.jsons.roadmesh.core.mesh

import com.jsons.roadmesh.core.road.RoadMap
import com.jsons.roadmesh.core.road.RoadPicture
import com.jsons.roadmesh.core.road.RoadQueries
import org.junit.Assert.*
import org.junit.Test

/** Phones on one table: every broadcast reaches every phone, and the chain filter decides what counts as a link. */
class ChainTest {
    private val clock = TestClock(mono = 100_000)

    /** A phone that started its mesh session [started] ms after the test began (2 s apart is a realistic minimum). */
    private inner class Phone(val id: Long, started: Long, observer: Boolean = false) {
        val node = NodeId(id)
        val roster = ChainRoster(node, 10_000 + started).also { it.observer = observer }
        val engine = MeshEngine(node, id.toInt(), id, MeshProfile(), clock, PositionSource { stageFix(roster.rank.coerceAtMost(200)) },
            ChainLinks { roster.rank }, announce = true, random = { 0 }, rank = { roster.rank }, relayGate = { roster.hasBehind })
        val map = RoadMap()
        val queries = RoadQueries(node, id, records = { from, now, r -> map.records(from, now, r) }, here = { stageFix(roster.rank) }, rank = { roster.rank })
    }

    private fun meet(phones: List<Phone>) {
        repeat(2) {
            for (p in phones) { val beat = p.roster.presence(true, clock.mono); phones.forEach { it.roster.hear(ControlCodec.decode(ControlCodec.encode(beat)) as ControlFrame.Presence, clock.mono) } }
            phones.forEach { it.roster.update(clock.mono) }
        }
    }

    /** Runs the air until nothing is left to send; returns every frame accepted, by phone. */
    private fun run(phones: List<Phone>, first: List<MeshEffect>, from: Phone): Map<Long, List<HazardEnvelope>> {
        val got = phones.associate { it.id to mutableListOf<HazardEnvelope>() }
        var air = first.filterIsInstance<MeshEffect.Transmit>().map { from to it.frame }
        repeat(20) {
            val next = mutableListOf<Pair<Phone, HazardEnvelope>>()
            for ((sender, frame) in air) for (p in phones) if (p !== sender)
                p.engine.receive(HazardEnvelopeCodec.encode(frame)).filterIsInstance<MeshEffect.Received>().forEach { got[p.id]!! += it.frame }
            clock.advance(250)
            for (p in phones) p.engine.tick().filterIsInstance<MeshEffect.Transmit>().forEach { next += p to it.frame }
            air = next
            if (air.isEmpty()) return got
        }
        return got
    }

    @Test fun firstToStartIsTheObserverAndTheRestFollowInStartOrder() {
        // Started out of id order, 2 s apart; phone 7 has its camera on but started third, which does not move it.
        val phones = listOf(Phone(4, 0), Phone(9, 2_000), Phone(7, 4_000, observer = true), Phone(2, 6_000), Phone(5, 8_000), Phone(8, 10_000))
        meet(phones)
        val chain = phones[0].roster.chain
        assertEquals(listOf(4L, 9L, 7L, 2L, 5L, 8L), chain.map { it.node.value })
        assertEquals(listOf(Role.OBSERVER, Role.RELAY, Role.RELAY, Role.RELAY, Role.RELAY, Role.RECEIVER), chain.map { it.role })
        phones.forEach { p -> assertEquals("every phone agrees", chain.map { it.node }, p.roster.chain.map { it.node }) }
        assertFalse(phones.last().roster.hasBehind)
    }

    @Test fun startOrderNeedsNoSharedClockAndCloseStartsBreakTiesTheSameWayEverywhere() {
        // Each phone's own clock would put them in a different order; only ages travel on air.
        val a = Phone(30, 0); val b = Phone(20, 500); val c = Phone(10, 5_000)
        meet(listOf(a, b, c))
        listOf(a, b, c).forEach { p -> assertEquals(listOf(20L, 30L, 10L), p.roster.chain.map { it.node.value }) }
    }

    @Test fun warningWalksTheWholeChainOneLinkAtATimeAndTheLastPhoneOnlyReceives() {
        val phones = (1L..6L).map { Phone(it, it * 2_000) }
        meet(phones)
        val observer = phones.first()
        val first = observer.engine.publish(LocalHazard(HazardKind.POTHOLE, Provenance.REPLAY, "test"))
        val got = run(phones, first, observer)
        for ((i, p) in phones.drop(1).withIndex()) {
            val frames = got[p.id]!!
            assertEquals("phone ${p.id} accepts the warning once", 1, frames.size)
            assertEquals("phone ${p.id} hears it from the phone in front", i, frames.single().hop)
        }
        val last = phones.last()
        clock.advance(1_000)
        assertTrue(last.engine.tick().filterIsInstance<MeshEffect.Transmit>().isEmpty())
    }

    @Test fun aPhoneThatLeavesClosesTheGap() {
        val phones = (1L..4L).map { Phone(it, it * 2_000) }
        meet(phones)
        clock.advance(5_000)
        val remaining = phones.filter { it.id != 3L }
        meet(remaining)
        assertEquals(listOf(1L, 2L, 4L), remaining[0].roster.chain.map { it.node.value })
        assertEquals(Role.RECEIVER, remaining.last().roster.me!!.role)
        assertEquals(2, remaining.last().roster.rank)
    }

    @Test fun controlFramesRoundTripAndRejectNoise() {
        val fix = GeoFix(12.97, 77.59, 90.0, 8.5, false)
        val frames = listOf(
            ControlFrame.Presence(NodeId(-5), 1_700_000_000_000, true, true, 3, VehicleType.AUTO, fix.copy(synthetic = true), 4),
            ControlFrame.Presence(NodeId(6), 1, false, false, 255, VehicleType.CAR, null, 0),
            ControlFrame.Query(MessageId(NodeId(1), 2, 3), NodeId(4), 8, 0, 2_000, fix),
            ControlFrame.Reply(MessageId(NodeId(1), 2, 3), NodeId(9), NodeId(10), 7, 1, 2, fix,
                listOf(ControlFrame.Record(HazardKind.POTHOLE, 120, -3, 40, 2, 0.81), ControlFrame.Record(HazardKind.ACCIDENT, -500, 0, 3, 1, null))),
        )
        for (f in frames) {
            val back = ControlCodec.decode(ControlCodec.encode(f))
            assertNotNull(f.toString(), back)
            if (f is ControlFrame.Reply) {
                val r = back as ControlFrame.Reply
                assertEquals(f.records, r.records); assertEquals(f.responder, r.responder); assertEquals(f.fix.lat, r.fix.lat, 1e-7)
            } else if (f is ControlFrame.Presence) assertEquals(f.copy(fix = null), (back as ControlFrame.Presence).copy(fix = null))
        }
        val random = kotlin.random.Random(3)
        repeat(5_000) { ControlCodec.decode(random.nextBytes(random.nextInt(0, 140)).also { if (it.isNotEmpty()) it[0] = 0x53 }) }
    }

    @Test fun roadQueryTravelsBothWaysAndAggregatesIntoOnePicture() {
        // Five phones heading north 40 m apart; the asker is in the middle (position 2).
        val phones = (1L..5L).map { Phone(it, it * 2_000) }
        meet(phones)
        val now = clock.mono
        phones[0].map.add(HazardKind.POTHOLE, stageFix(0).let { it.copy(lat = it.lat + 40 / 111_195.0) }, NodeId(1), now, 0.8, own = true)
        phones[1].map.add(HazardKind.POTHOLE, stageFix(0).let { it.copy(lat = it.lat + 45 / 111_195.0) }, NodeId(2), now, 0.6, own = true)
        phones[4].map.add(HazardKind.ACCIDENT, stageFix(4).let { it.copy(lat = it.lat - 50 / 111_195.0) }, NodeId(5), now, 0.7, own = true)
        val asker = phones[2]
        var air: List<Pair<Phone, ControlFrame>> = listOf(asker to asker.queries.ask(stageFix(2).copy(speedMps = 12.0), now))
        repeat(10) {
            val next = mutableListOf<Pair<Phone, ControlFrame>>()
            for ((sender, f) in air) {
                val wire = ControlCodec.decode(ControlCodec.encode(f))!!
                // Neighbours only: a frame reaches the phones one position either side of the sender.
                for (p in phones) if (p !== sender && kotlin.math.abs(p.roster.rank - sender.roster.rank) == 1) p.queries.receive(wire, now).forEach { next += p to it }
            }
            air = next
        }
        clock.advance(3_000)
        val done = asker.queries.tick(clock.mono).single()
        assertEquals(setOf(1L, 2L, 4L, 5L), done.replies.map { it.responder.value }.toSet())
        assertEquals("the far phones answer through a relay", 1, done.replies.first { it.responder.value == 5L }.hop)
        val picture = RoadPicture.combine(stageFix(2).copy(speedMps = 12.0), asker.map.all, done.replies, clock.mono)
        val pothole = picture.items.single { it.kind == HazardKind.POTHOLE }
        assertEquals("two phones reported the same pothole", 2, pothole.reporters)
        assertEquals(2 * STAGE_GAP_M + 40, pothole.aheadM.toDouble(), 6.0)
        assertEquals(-(2 * STAGE_GAP_M + 50), picture.items.single { it.kind == HazardKind.ACCIDENT }.aheadM.toDouble(), 3.0)
        assertTrue(picture.facts().contains("Ahead: pothole"))
        assertTrue(picture.facts().contains("Behind: accident"))
    }

    @Test fun slowdownAheadGivesAMeetingTimeAndAGentlerSpeed() {
        val f = RoadPicture.forecast(14.0, listOf(com.jsons.roadmesh.core.road.PicturePeer(NodeId(1), 0, 400, 2.0, 1, 0)), emptyList())
        assertEquals(400, f.slowdownAheadM)
        assertEquals(400 / (14.0 + 4.5), f.meetS!!, 0.01)
        assertTrue("recommends easing off below the current 50 km/h", f.recommendedKmh!! < 50 && f.recommendedKmh >= 15)
        assertTrue(f.advice.any { it.contains("avoid braking hard") })
        val clear = RoadPicture.forecast(14.0, emptyList(), emptyList())
        assertNull(clear.slowdownAheadM); assertTrue(clear.advice.single().startsWith("Nothing reported"))
    }

    @Test fun roadMapMergesRepeatsAndCountsDistinctReporters() {
        val map = RoadMap()
        val at = GeoFix(12.0, 77.0, null, null, false)
        map.add(HazardKind.POTHOLE, at, NodeId(1), 0, 0.5, own = false)
        map.add(HazardKind.POTHOLE, at.copy(lat = at.lat + 10 / 111_195.0), NodeId(1), 1_000, 0.7, own = false)
        map.add(HazardKind.POTHOLE, at.copy(lat = at.lat + 15 / 111_195.0), NodeId(2), 2_000, null, own = true)
        map.add(HazardKind.POTHOLE, at.copy(lat = at.lat + 200 / 111_195.0), NodeId(3), 2_000, null, own = false)
        assertEquals(2, map.all.size)
        assertEquals(2, map.all.first().reporters.size); assertEquals(0.7, map.all.first().confidence!!, 1e-9)
        assertFalse(map.fresh(HazardKind.POTHOLE, at, 3_000, 60_000))
        map.expire(2_000 + HazardKind.POTHOLE.lifetimeS * 1000L + 1)
        assertTrue(map.all.isEmpty())
    }
}
