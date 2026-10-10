package com.jsons.roadmesh.core.junction

import kotlin.math.max

/**
 * Reserve-then-Roll limits. [gapS] must be at least the timing error each vehicle can have
 * against its slot on both sides (2ε for |error| ≤ ε). [exitCapacity] is the storage of each outbound
 * segment up to its spillback buffer, so a vehicle holding a token always has room to leave the box.
 */
data class SchedulerConfig(
    val gapS: Double = 1.0,
    val headwayS: Double = 1.5,
    val exitCapacity: Int = 7,
    val maxSearchSteps: Int = 5_000,
) {
    fun validate() = apply {
        require(gapS in 0.0..10.0 && headwayS in 0.5..10.0) { "gap/headway" }
        require(exitCapacity in 1..500 && maxSearchSteps in 10..100_000) { "capacity/search" }
    }

    companion object {
        /**
         * Same-lane slots must be at least as far apart as the following law needs at crossing speed; otherwise a
         * committed follower has to slow behind its predecessor and misses its own window.
         */
        fun laneHeadway(law: ApproachConfig) = (law.standstillGapM + law.headwayS * law.crossingSpeedMps) / law.crossingSpeedMps
    }
}

data class Grant(val vehicle: Long, val movement: Movement, val slot: Double)

/**
 * Single-owner slot scheduler for one junction. Conflict "points" are box tiles; a vehicle gets a slot only when
 * every tile on its path is free for its whole occupancy interval (plus [SchedulerConfig.gapS]) and an exit token is
 * available. Lanes are FIFO. Among lane heads, higher pressure (inbound queue minus outbound load) is served first.
 *
 * Deterministic: the same requests in the same order give the same table, so every phone in a cluster can replicate it.
 */
class JunctionScheduler(val geometry: JunctionGeometry, val config: SchedulerConfig = SchedulerConfig()) {
    private class Use(val start: Double, val end: Double, val vehicle: Long)
    private class Entry(val vehicle: Long, val movement: Movement, var earliest: Double) {
        var slot: Double? = null
        var committed = false
        var token = false
    }

    private val tiles = Array(geometry.tileCount) { ArrayList<Use>() }
    private val lanes = Arm.entries.associateWith { ArrayList<Entry>() }
    private val byVehicle = HashMap<Long, Entry>()
    private val lastEntered = DoubleArray(4) { Double.NEGATIVE_INFINITY }
    private val tokens = IntArray(4)

    init { config.validate() }

    fun slot(vehicle: Long) = byVehicle[vehicle]?.slot
    fun committed(vehicle: Long) = byVehicle[vehicle]?.committed == true
    fun tokensOut(arm: Arm) = tokens[arm.ordinal]
    fun queueLength(arm: Arm) = lanes.getValue(arm).size

    fun request(vehicle: Long, movement: Movement, earliest: Double) {
        require(vehicle !in byVehicle) { "duplicate request $vehicle" }
        Entry(vehicle, movement, earliest).also { lanes.getValue(movement.from) += it; byVehicle[vehicle] = it }
    }

    /** Grants as many lane heads as fit. Call every tick; it is cheap when nothing changed. */
    fun schedule(now: Double): List<Grant> {
        prune(now)
        val grants = ArrayList<Grant>()
        val stuck = HashSet<Arm>()
        while (true) {
            val head = Arm.entries.asSequence().filter { it !in stuck }
                .mapNotNull { arm -> lanes.getValue(arm).firstOrNull { it.slot == null } }
                .filter { it.token || tokens[it.movement.to.ordinal] < config.exitCapacity }
                .sortedWith(compareByDescending<Entry> { pressure(it.movement) }.thenBy { it.earliest }.thenBy { it.vehicle })
                .firstOrNull() ?: break
            if (!head.token) { head.token = true; tokens[head.movement.to.ordinal]++ }
            if (place(head, max(head.earliest, now))) grants += Grant(head.vehicle, head.movement, head.slot!!)
            else stuck += head.movement.from
        }
        return grants
    }

    /** Past the point of no return: the slot can no longer move. */
    fun commit(vehicle: Long) { byVehicle[vehicle]?.committed = true }

    /** The vehicle crossed its stop line. It leaves the lane queue; its tile uses expire on their own. */
    fun entered(vehicle: Long) {
        val e = byVehicle.remove(vehicle) ?: return
        lanes.getValue(e.movement.from).remove(e)
        lastEntered[e.movement.from.ordinal] = e.slot ?: lastEntered[e.movement.from.ordinal]
    }

    /** A vehicle left outbound [arm] at its leaf: its exit space is free again. */
    fun released(arm: Arm) { check(tokens[arm.ordinal] > 0) { "no token out for $arm" }; tokens[arm.ordinal]-- }

    fun cancel(vehicle: Long) {
        val e = byVehicle.remove(vehicle) ?: return
        release(e); lanes.getValue(e.movement.from).remove(e)
        if (e.token) tokens[e.movement.to.ordinal]--
    }

    /**
     * Broadcast re-slotting (Theorem 3). The delayed vehicle and its uncommitted lane followers keep FIFO order and are
     * re-placed first; uncommitted vehicles in other lanes that were scheduled after it are re-placed afterwards,
     * because they are further away and can absorb a delay with a smaller speed change.
     * Returns each moved vehicle's shift in seconds. [force] re-slots a committed vehicle that missed its window.
     */
    fun delay(vehicle: Long, newEarliest: Double, force: Boolean = false): Map<Long, Double> {
        val head = byVehicle[vehicle] ?: return emptyMap()
        head.earliest = max(head.earliest, newEarliest)
        val headOld = head.slot ?: return emptyMap()
        if (head.committed && !force || newEarliest <= headOld) return emptyMap()
        head.committed = false
        val lane = lanes.getValue(head.movement.from)
        val block = lane.subList(lane.indexOf(head), lane.size).filter { it.slot != null && (it === head || !it.committed) }
        val others = byVehicle.values.filter { it.movement.from != head.movement.from && !it.committed && (it.slot ?: Double.NEGATIVE_INFINITY) >= headOld }
            .sortedBy { it.slot }
        val old = (block + others).associateWith { it.slot!! }
        for (e in block + others) { release(e); e.slot = null }
        for (e in block + others) {
            val ok = place(e, if (e === head) head.earliest else old.getValue(e))
            if (!ok) unsetFrom(e)
        }
        return old.entries.mapNotNull { (e, was) -> e.slot?.let { e.vehicle to it - was } }.toMap()
    }

    /** All live reservations are mutually separated by the gap. Used by tests and debug exports. */
    fun reservationsSeparated(): Boolean = tiles.all { list ->
        val sorted = list.sortedBy { it.start }
        sorted.zipWithNext().all { (a, b) -> a.vehicle == b.vehicle || b.start >= a.end + config.gapS - 1e-9 }
    }

    private fun pressure(m: Movement) = lanes.getValue(m.from).size - tokens[m.to.ordinal]

    private fun predecessorSlot(e: Entry): Double? {
        val lane = lanes.getValue(e.movement.from)
        val i = lane.indexOf(e)
        return if (i <= 0) lastEntered[e.movement.from.ordinal] else lane[i - 1].slot
    }

    private fun place(e: Entry, from: Double): Boolean {
        val before = predecessorSlot(e) ?: return false
        val t = earliestFeasible(e.movement, max(from, before + config.headwayS)) ?: return false
        e.slot = t
        for (u in geometry.uses.getValue(e.movement)) tiles[u.tile] += Use(t + u.from, t + u.to, e.vehicle)
        return true
    }

    private fun earliestFeasible(m: Movement, from: Double): Double? {
        var t = from
        repeat(config.maxSearchSteps) {
            var bump = t
            for (u in geometry.uses.getValue(m)) for (other in tiles[u.tile]) {
                if (t + u.to + config.gapS > other.start && t + u.from < other.end + config.gapS) bump = max(bump, other.end + config.gapS - u.from)
            }
            if (bump == t) return t
            t = bump
        }
        return null
    }

    private fun release(e: Entry) { for (u in geometry.uses.getValue(e.movement)) tiles[u.tile].removeAll { it.vehicle == e.vehicle } }

    /** Keeps lane FIFO consistent if a vehicle cannot be placed: it and everyone behind it wait for [schedule]. */
    private fun unsetFrom(e: Entry) {
        val lane = lanes.getValue(e.movement.from)
        for (f in lane.subList(lane.indexOf(e), lane.size)) if (!f.committed) { release(f); f.slot = null }
    }

    private fun prune(now: Double) { for (list in tiles) list.removeAll { it.end + config.gapS < now } }
}
