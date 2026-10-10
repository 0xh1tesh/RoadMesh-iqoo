package com.jsons.roadmesh.core.mesh

/** One phone the mesh currently hears, with the chain position the roster gave it. */
data class Member(
    val node: NodeId, val startedMs: Long, val observer: Boolean, val vehicle: VehicleType, val fix: GeoFix?,
    val lastHeardMs: Long, val rank: Int, val role: Role, val self: Boolean, val known: Int = 0,
)

/**
 * Arranges the mesh into a chain from presence beacons alone, the same way on every phone so they agree without a
 * coordinator: the phone that started the mesh first is the observer (position 0, A), and every later phone takes the
 * next position in the order it started. Each phone relays to the one behind it; the phone that started last only
 * receives. Members silent for [silenceMs] drop out and the chain closes up.
 *
 * Start order needs no shared clock: a beacon carries how long its phone has been in the mesh, and the listener turns
 * that into a start time on its own monotonic clock (keeping the earliest estimate, since a beacon can be up to a second
 * stale). Phones that start within [tieMs] of each other are ordered by node id so every phone breaks the tie the same way.
 *
 * On the road ([byRoad]) the order comes from position along the direction of travel instead: furthest ahead first.
 */
class ChainRoster(val self: NodeId, private val startedMs: Long, private val silenceMs: Long = 4_000, private val byRoad: Boolean = false,
                  private val tieMs: Long = 1_500) {
    private data class Heard(val p: ControlFrame.Presence, val at: Long, val started: Long)
    private val heard = LinkedHashMap<NodeId, Heard>()
    /** True while this phone's camera (or the dashcam replay) is running. Shown to others; it does not change the order. */
    var observer = false
    var vehicle = VehicleType.UNKNOWN
    var fix: GeoFix? = null
    var known = 0
    private var members: List<Member> = emptyList()

    fun hear(p: ControlFrame.Presence, nowMs: Long) {
        if (p.node == self) return
        val started = nowMs - p.ageMs
        heard[p.node] = Heard(p, nowMs, heard[p.node]?.started?.let { minOf(it, started) } ?: started)
    }

    /** Recomputes the chain. Call after [hear] and on every tick. */
    fun update(nowMs: Long): List<Member> {
        heard.entries.removeAll { nowMs - it.value.at > silenceMs }
        data class Raw(val node: NodeId, val started: Long, val observer: Boolean, val vehicle: VehicleType, val fix: GeoFix?, val at: Long, val self: Boolean, val known: Int)
        val raw = heard.values.map { Raw(it.p.node, it.started, it.p.observer, it.p.vehicle, it.p.fix, it.at, false, it.p.known) } +
            Raw(self, startedMs, observer, vehicle, fix, nowMs, true, known)
        val ordered = if (byRoad) orderByRoad(raw.associate { it.node to it.fix }).let { order -> raw.sortedBy { order.indexOf(it.node) } }
            else raw.sortedWith { a, b ->
                if (kotlin.math.abs(a.started - b.started) >= tieMs) a.started.compareTo(b.started)
                else java.lang.Long.compareUnsigned(a.node.value, b.node.value)
            }
        val last = ordered.lastIndex
        members = ordered.mapIndexed { i, m ->
            val role = when { i == 0 -> Role.OBSERVER; i == last -> Role.RECEIVER; else -> Role.RELAY }
            Member(m.node, m.started, m.observer, m.vehicle, m.fix, m.at, i, role, m.self, m.known)
        }
        return members
    }

    val chain get() = members
    val me get() = members.firstOrNull { it.self }
    val rank get() = me?.rank ?: NO_RANK
    /** True when at least one phone is behind this one, so relaying helps someone. */
    val hasBehind get() = members.size > (me?.rank ?: Int.MAX_VALUE) + 1
    fun presence(synthetic: Boolean, nowMs: Long) =
        ControlFrame.Presence(self, (nowMs - startedMs).coerceAtLeast(0), observer, synthetic, rank.coerceAtMost(254), vehicle, fix, known)

    /** Furthest along the mean heading first; phones without a fix go last in start order. */
    private fun orderByRoad(fixes: Map<NodeId, GeoFix?>): List<NodeId> {
        val located = fixes.filterValues { it != null }.mapValues { it.value!! }
        if (located.isEmpty()) return fixes.keys.toList()
        val heading = located.values.mapNotNull { it.heading }.let { h -> if (h.isEmpty()) 0.0 else meanHeading(h) }
        val ref = located.values.first()
        val along = located.mapValues { (_, f) -> alongM(ref, f, heading) }
        return along.entries.sortedByDescending { it.value }.map { it.key } + fixes.keys.filter { it !in located }
    }
}

/** Signed distance of [p] from [ref] along [heading] (degrees, 0 = north). Positive means ahead. */
fun alongM(ref: GeoFix, p: GeoFix, heading: Double): Double {
    val d = distanceM(ref, p); if (d < 0.01) return 0.0
    return d * kotlin.math.cos(Math.toRadians(bearing(ref, p) - heading))
}
fun meanHeading(h: List<Double>): Double {
    val s = h.sumOf { kotlin.math.sin(Math.toRadians(it)) }; val c = h.sumOf { kotlin.math.cos(Math.toRadians(it)) }
    return (Math.toDegrees(kotlin.math.atan2(s, c)) + 360) % 360
}
