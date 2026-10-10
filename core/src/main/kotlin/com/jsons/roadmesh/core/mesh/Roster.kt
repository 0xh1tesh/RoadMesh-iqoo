package com.jsons.roadmesh.core.mesh

/** One phone the mesh currently hears, with the chain position the roster gave it. */
data class Member(
    val node: NodeId, val startedMs: Long, val observer: Boolean, val vehicle: VehicleType, val fix: GeoFix?,
    val lastHeardMs: Long, val rank: Int, val role: Role, val self: Boolean, val known: Int = 0,
)

/**
 * A bounded local view of presence. Stage mode orders the phones into a presentation chain; road mode groups
 * compatible headings relative to this phone. Different views can have different ranks: this is not consensus.
 * Members silent for [silenceMs] drop out. Road ranks are display metadata, never forwarding authority.
 *
 * Start order needs no shared clock: a beacon carries how long its phone has been in the mesh, and the listener turns
 * that into a start time on its own monotonic clock (keeping the earliest estimate, since a beacon can be up to a second
 * stale). Phones that start within [tieMs] of each other are ordered by node id so every phone breaks the tie the same way.
 *
 * On the road ([byRoad]), located same-direction peers come first, furthest ahead along our heading first.
 * Other approaches and unknown positions stay visible but do not rotate the direction of our road.
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
        if (p.node == self || nowMs < 0 || p.ageMs < 0 || byRoad && (p.synthetic || p.fix?.synthetic == true)) return
        heard.entries.removeAll { nowMs < it.value.at || nowMs - it.value.at > silenceMs }
        // A new identity cannot evict an active neighbor during an identity/broadcast flood.
        if (p.node !in heard && heard.size >= 128) return
        val started = nowMs - p.ageMs
        heard[p.node] = Heard(p, nowMs, heard[p.node]?.started?.let { minOf(it, started) } ?: started)
    }

    /** Recomputes the chain. Call after [hear] and on every tick. */
    fun update(nowMs: Long): List<Member> {
        heard.entries.removeAll { nowMs < it.value.at || nowMs - it.value.at > silenceMs }
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
            val role = if (byRoad) {
                when { m.observer -> Role.OBSERVER; raw.size > 1 -> Role.RELAY; else -> Role.RECEIVER }
            } else when { i == 0 -> Role.OBSERVER; i == last -> Role.RECEIVER; else -> Role.RELAY }
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

    /** Ego-relative ordering is deliberately local; opposing headings never redefine our forward direction. */
    private fun orderByRoad(fixes: Map<NodeId, GeoFix?>): List<NodeId> {
        val stable = Comparator<NodeId> { a, b -> java.lang.Long.compareUnsigned(a.value, b.value) }
        val ref = fixes[self]?.takeIf { it.valid() && !it.synthetic }
        val heading = ref?.heading
        if (ref == null || heading == null) return listOf(self) + fixes.keys.filter { it != self }.sortedWith(stable)
        val compatible = fixes.filterValues { f ->
            f != null && f.valid() && !f.synthetic && f.heading != null &&
                kotlin.math.abs(((f.heading - heading + 540.0) % 360.0) - 180.0) <= 45.0 &&
                kotlin.math.abs(alongM(ref, f, (heading + 90.0) % 360.0)) <= 40.0
        }
        val ahead = compatible.keys.sortedWith { a, b ->
            val position = alongM(ref, compatible.getValue(b)!!, heading).compareTo(alongM(ref, compatible.getValue(a)!!, heading))
            if (position == 0) stable.compare(a, b) else position
        }
        return ahead + fixes.keys.filter { it !in compatible }.sortedWith(stable)
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
