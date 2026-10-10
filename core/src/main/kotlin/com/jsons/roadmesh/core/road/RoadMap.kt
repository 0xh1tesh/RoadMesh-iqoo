package com.jsons.roadmesh.core.road

import com.jsons.roadmesh.core.mesh.*
import kotlin.math.cos
import kotlin.math.roundToInt

/** One hazard this phone knows about, from its own camera or from the mesh, merged across reports of the same place. */
data class KnownHazard(
    val kind: HazardKind, val lat: Double, val lon: Double, val firstSeenMs: Long, val lastSeenMs: Long,
    val reporters: Set<NodeId>, val confidence: Double?, val own: Boolean, val note: String = "",
) {
    val fix get() = GeoFix(lat, lon, null, null, false)
    fun ageS(nowMs: Long) = ((nowMs - lastSeenMs) / 1000).toInt().coerceAtLeast(0)
}

/**
 * Every phone's memory of the road. A report of the same kind within [mergeM] of a known one is the same hazard seen
 * again: it refreshes the entry and adds its reporter, so corroboration counts distinct phones, never repeated copies.
 * Entries expire after their kind's lifetime. Pure and single-owner; the session drives it.
 */
class RoadMap(private val mergeM: Double = 25.0, private val capacity: Int = 256) {
    private val items = ArrayList<KnownHazard>()
    val all: List<KnownHazard> get() = items.toList()

    fun add(kind: HazardKind, at: GeoFix, reporter: NodeId, nowMs: Long, confidence: Double?, own: Boolean, note: String = ""): KnownHazard {
        expire(nowMs)
        val i = items.indexOfFirst { it.kind == kind && distanceM(it.fix, at) <= mergeM }
        val merged = if (i >= 0) items[i].let { k ->
            k.copy(lastSeenMs = nowMs, reporters = k.reporters + reporter, confidence = listOfNotNull(k.confidence, confidence).maxOrNull(),
                own = k.own || own, note = note.ifEmpty { k.note })
        } else KnownHazard(kind, at.lat, at.lon, nowMs, nowMs, setOf(reporter), confidence, own, note)
        if (i >= 0) items[i] = merged else { if (items.size >= capacity) items.removeAt(0); items += merged }
        return merged
    }

    /** True when this hazard is new or was last seen more than [quietMs] ago, so speaking it again is worth it. */
    fun fresh(kind: HazardKind, at: GeoFix, nowMs: Long, quietMs: Long): Boolean =
        items.none { it.kind == kind && distanceM(it.fix, at) <= mergeM && nowMs - it.lastSeenMs < quietMs }

    fun expire(nowMs: Long) { items.removeAll { nowMs - it.lastSeenMs > it.kind.lifetimeS * 1000L } }

    /** The most useful records to share from [from]: cautions before reports, nearest first, at most [max]. */
    fun records(from: GeoFix, nowMs: Long, radiusM: Double, max: Int = ControlCodec.MAX_RECORDS): List<ControlFrame.Record> {
        expire(nowMs)
        return items.filter { distanceM(from, it.fix) <= radiusM }
            .sortedWith(compareBy({ it.kind.use != HazardUse.CAUTION }, { distanceM(from, it.fix) }))
            .take(max).map { h ->
                val (n, e) = offsetM(from, h.fix)
                ControlFrame.Record(h.kind, n.roundToInt(), e.roundToInt(), h.ageS(nowMs), h.reporters.size, h.confidence)
            }
    }
}

/** North and east offset in metres of [to] from [from]. Flat-earth, fine within a few kilometres. */
fun offsetM(from: GeoFix, to: GeoFix): Pair<Double, Double> =
    (to.lat - from.lat) * 111_195.0 to (to.lon - from.lon) * 111_195.0 * cos(Math.toRadians(from.lat))
fun moved(from: GeoFix, northM: Double, eastM: Double) =
    GeoFix(from.lat + northM / 111_195.0, from.lon + eastM / (111_195.0 * cos(Math.toRadians(from.lat))), null, null, from.synthetic)
