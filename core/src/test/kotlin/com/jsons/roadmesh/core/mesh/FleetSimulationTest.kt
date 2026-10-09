package com.jsons.roadmesh.core.mesh

import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue
import java.io.File
import kotlin.random.Random

/** Deterministic logical radio simulation. It does not predict BLE airtime, RF range or device PDR. */
class FleetSimulationTest {
    data class Delivery(val at: Long, val target: Int, val bytes: ByteArray)
    data class Result(val nodes: Int, val accepted: Int, val cautions: Int, val bursts: Int, val maxLinks: Int, val maxDedup: Int)
    private fun runFleet(size: Int, partition: Boolean = false): Result {
        val clock = TestClock()
        val random = Random(2048 + size)
        val profile = MeshProfile(mode = Mode.ROAD)
        val fixes = List(size) { i -> GeoFix(12.0 - i * 20.0 / 111_195, 77.0, 0.0, 10.0, false) }
        val engines = List(size) { i -> MeshEngine(NodeId(i + 1L), i + 1, 1000 + i.toLong(), profile, clock,
            PositionSource { fixes[i] }, random = { bound -> random.nextInt(bound) }) }
        val queue = PriorityQueue<Delivery>(compareBy { it.at })
        var accepted = 0; var cautions = 0; var bursts = 0; var maxLinks = 0; var maxDedup = 0
        val receipts = HashMap<MessageId, MutableSet<Int>>()
        fun process(sender: Int, effects: List<MeshEffect>) {
            for (effect in effects) when (effect) {
                is MeshEffect.Transmit -> {
                    bursts++; maxLinks = maxOf(maxLinks, effect.frame.radioLinks)
                    val bytes = HazardEnvelopeCodec.encode(effect.frame)
                    val copies = if (effect.relay) 3 else 10
                    for (target in (sender - 7).coerceAtLeast(0)..(sender + 7).coerceAtMost(size - 1)) {
                        if (target == sender) continue
                        if (partition && (sender < size / 2) != (target < size / 2)) continue
                        repeat(copies) { copy ->
                            if (random.nextDouble() >= .25) queue += Delivery(clock.mono + 5 + copy * 100 + random.nextInt(8), target, bytes)
                        }
                    }
                }
                is MeshEffect.Received -> {
                    accepted++
                    val seen = receipts.getOrPut(effect.frame.id) { HashSet() }
                    assertTrue("Each observer accepts each event once", seen.add(sender))
                    assertTrue(effect.frame.radioLinks <= 3)
                    val origin = (effect.frame.id.origin.value - 1).toInt()
                    assertTrue("Bounded links cannot flood across the fleet", kotlin.math.abs(sender - origin) <= 21)
                    if (partition) assertEquals(origin < size / 2, sender < size / 2)
                }
                is MeshEffect.Announce -> { cautions++; assertTrue(effect.distanceM <= 400) }
                else -> Unit
            }
        }
        // Many devices over kilometers; every immediate radio neighborhood has <=14 peers.
        val origins = if (partition) listOf(size / 2 - 1) else (0 until size step 25).toList()
        origins.forEach { process(it, engines[it].publish(LocalHazard(HazardKind.BRAKING, Provenance.LIVE, "sim-$it"))) }
        repeat(650) {
            while (queue.isNotEmpty() && queue.peek().at <= clock.mono) {
                val d = queue.remove(); process(d.target, engines[d.target].receive(d.bytes))
            }
            engines.forEachIndexed { i, engine ->
                process(i, engine.tick())
                val b = engine.bounds
                assertTrue(b.dedup <= profile.dedupCapacity && b.neighbors <= profile.neighborCapacity && b.pending <= profile.pendingCapacity)
                maxDedup = maxOf(maxDedup, b.dedup)
            }
            clock.advance(10)
        }
        origins.forEach { origin ->
            val key = MessageId(NodeId(origin + 1L), 1000 + origin.toLong(), 1)
            assertTrue("A neighboring receiver must get the repeated burst", receipts[key]?.isNotEmpty() == true)
        }
        assertTrue(accepted > 0 && bursts > origins.size)
        return Result(size, accepted, cautions, bursts, maxLinks, maxDedup)
    }

    @Test fun fleetsOf100300And500StayBoundedUnderLossAndDuplicateReception() {
        val results = listOf(100, 300, 500).map { runFleet(it) }
        assertTrue(results.all { it.maxLinks <= 3 && it.maxDedup <= 5 && it.cautions > 0 })
        val output = File("build/reports/mesh-simulation.json").apply { parentFile.mkdirs() }
        output.writeText("{\"disclosure\":\"logical simulation, 25% seeded loss, repeated packets, <=14 radio neighbors; not measured BLE performance\",\"runs\":[" +
            results.joinToString(",") { "{\"nodes\":${it.nodes},\"accepted\":${it.accepted},\"cautions\":${it.cautions},\"bursts\":${it.bursts},\"maxRadioLinks\":${it.maxLinks},\"maxDedupEntries\":${it.maxDedup}}" } + "]}")
    }
    @Test fun partitionPreventsDeliveryAcrossDisconnectedHalves() { runFleet(100, partition = true) }
}
