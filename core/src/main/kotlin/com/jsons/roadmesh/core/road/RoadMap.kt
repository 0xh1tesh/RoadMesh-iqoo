package com.jsons.roadmesh.core.road

import com.jsons.roadmesh.core.mesh.*
import kotlin.math.cos
import kotlin.math.roundToInt

/** Original observation identity and capture time on this receiver's monotonic clock; relays never refresh it. */
data class HazardEvidence(
    val id: MessageId, val capturedMs: Long, val capturedUtcMs: Long?, val provenance: Provenance?,
    val confidence: Double? = null, val own: Boolean = false,
    /** This observation's position and uncertainty, rather than the spatial cluster's representative point. */
    val fix: GeoFix? = null,
)

/** One hazard this phone knows about, from its own camera or from the mesh, merged across reports of the same place. */
data class KnownHazard(
    val kind: HazardKind, val lat: Double, val lon: Double, val firstSeenMs: Long, val lastSeenMs: Long,
    val reporters: Set<NodeId>, val confidence: Double?, val own: Boolean, val note: String = "",
    val evidence: List<HazardEvidence> = emptyList(),
    val accuracyM: Double? = null,
) {
    val fix get() = GeoFix(lat, lon, null, null, false, accuracyM)
    fun ageS(nowMs: Long) = ((nowMs - lastSeenMs).coerceAtLeast(0) / 1000).coerceAtMost(65_535).toInt()
}

/**
 * Bounded road memory. A genuinely new observation can update a condition; a copied identity cannot.
 * Nearby reports are a coarse spatial hypothesis, not confirmed object association or lane membership.
 */
class RoadMap(private val mergeM: Double = 25.0, private val capacity: Int = 256) {
    private val items = ArrayList<KnownHazard>()
    private val seen = LinkedHashMap<MessageId, Long>()
    val all: List<KnownHazard> get() = items.toList()

    init { require(capacity > 0 && mergeM > 0) }

    fun add(
        kind: HazardKind, at: GeoFix, reporter: NodeId, nowMs: Long, confidence: Double?, own: Boolean, note: String = "",
        observationId: MessageId? = null, capturedMs: Long = nowMs, capturedUtcMs: Long? = null, provenance: Provenance? = null,
    ): KnownHazard? {
        expire(nowMs)
        if (nowMs < 0 || !at.valid() || reporter.value == 0L || capturedMs > nowMs ||
            capturedMs <= nowMs - kind.lifetimeS * 1000L ||
            confidence != null && (!confidence.isFinite() || confidence !in 0.0..1.0)) return null
        if (observationId != null) {
            if (observationId.origin != reporter) return null
            if (observationId in seen) {
                // The short warning envelope lacks accuracy. A later full record may fill that missing metadata,
                // but only for the same original claim. It cannot refresh time, support, confidence or location.
                val existing = items.indexOfFirst { k -> k.kind == kind && at.accuracyM != null && k.evidence.any { e ->
                    e.id == observationId && e.fix?.accuracyM == null && e.capturedUtcMs != null &&
                        e.capturedUtcMs == capturedUtcMs && e.provenance == provenance && distanceM(e.fix ?: k.fix, at) <= mergeM
                } }
                if (existing < 0) return null
                val known = items[existing]
                val support = known.evidence.map { e ->
                    if (e.id != observationId) e else {
                        val original = e.fix ?: known.fix
                        e.copy(fix = original.copy(accuracyM = at.accuracyM!! + distanceM(original, at)))
                    }
                }
                return known.copy(evidence = support, accuracyM = aggregateAccuracy(known.fix, support)).also { items[existing] = it }
            }
            if (seen.size >= MAX_IDENTITIES) return null
            // Retain tombstones beyond record expiry, including after spatial/capacity eviction.
            seen[observationId] = nowMs
        }
        val observation = observationId?.let { HazardEvidence(it, capturedMs, capturedUtcMs, provenance, confidence, own, at) }
        val i = items.indexOfFirst { it.kind == kind && distanceM(it.fix, at) <= mergeM }
        val merged = if (i >= 0) items[i].let { k ->
            val support = (k.evidence + listOfNotNull(observation)).sortedByDescending { it.capturedMs }
                .distinctBy { it.id.origin }.take(MAX_SUPPORT)
            k.copy(lastSeenMs = maxOf(k.lastSeenMs, capturedMs), reporters = if (support.isEmpty()) k.reporters + reporter else support.map { it.id.origin }.toSet(),
                confidence = listOfNotNull(k.confidence, confidence).maxOrNull(), own = k.own || own,
                note = note.ifEmpty { k.note }.take(120), evidence = support,
                accuracyM = if (support.isNotEmpty()) aggregateAccuracy(k.fix, support)
                    else if (k.accuracyM != null && at.accuracyM != null) maxOf(k.accuracyM, at.accuracyM + distanceM(k.fix, at)) else null)
        } else KnownHazard(kind, at.lat, at.lon, capturedMs, capturedMs, setOf(reporter), confidence, own, note.take(120), listOfNotNull(observation), at.accuracyM)
        if (i >= 0) items[i] = merged else { if (items.size >= capacity) items.removeAt(0); items += merged }
        return merged
    }

    /** True when this hazard is new or was last seen more than [quietMs] ago, so speaking it again is worth it. */
    fun fresh(kind: HazardKind, at: GeoFix, nowMs: Long, quietMs: Long): Boolean =
        items.none { it.kind == kind && distanceM(it.fix, at) <= mergeM && nowMs - it.lastSeenMs < quietMs }

    fun expire(nowMs: Long) {
        seen.entries.removeAll { nowMs - it.value >= TOMBSTONE_MS }
        for (i in items.indices.reversed()) {
            val h = items[i]
            if (h.evidence.isEmpty()) {
                if (nowMs - h.lastSeenMs >= h.kind.lifetimeS * 1000L) items.removeAt(i)
                continue
            }
            val support = h.evidence.filter { nowMs >= it.capturedMs && nowMs - it.capturedMs < h.kind.lifetimeS * 1000L }
            if (support.isEmpty()) items.removeAt(i)
            else items[i] = h.copy(evidence = support, reporters = support.map { it.id.origin }.toSet(),
                lastSeenMs = support.maxOf { it.capturedMs }, own = support.any { it.own },
                confidence = support.mapNotNull { it.confidence }.maxOrNull(), accuracyM = aggregateAccuracy(h.fix, support))
        }
    }

    /** The most useful records to share from [from]: cautions before reports, nearest first, at most [max]. */
    fun records(from: GeoFix, nowMs: Long, radiusM: Double, max: Int = ControlCodec.MAX_RECORDS): List<ControlFrame.Record> {
        expire(nowMs)
        return items.filter { distanceM(from, it.fix) <= radiusM }
            .sortedWith(compareBy({ it.kind.use != HazardUse.CAUTION }, { distanceM(from, it.fix) }))
            .flatMap { h ->
                val (n, e) = offsetM(from, h.fix)
                if (h.evidence.isEmpty()) listOf(ControlFrame.Record(h.kind, n.roundToInt(), e.roundToInt(), h.ageS(nowMs), 1, h.confidence))
                else h.evidence.map { evi ->
                    val position = evi.fix ?: h.fix
                    val (sourceN, sourceE) = offsetM(from, position)
                    ControlFrame.Record(h.kind, sourceN.roundToInt(), sourceE.roundToInt(), ((nowMs - evi.capturedMs + 999) / 1000).toInt(), 1,
                        evi.confidence, evi.id, evi.capturedUtcMs, evi.provenance, evi.fix?.accuracyM?.plus(1.0))
                }
            }.take(max.coerceIn(0, ControlCodec.MAX_RECORDS))
    }

    private fun aggregateAccuracy(anchor: GeoFix, support: List<HazardEvidence>): Double? {
        if (support.isEmpty() || support.any { it.fix?.accuracyM == null }) return null
        return support.maxOf { e -> e.fix!!.accuracyM!! + distanceM(anchor, e.fix) }
    }

    companion object {
        const val MAX_SUPPORT = 8
        const val MAX_IDENTITIES = 2_048
        const val TOMBSTONE_MS = 3_600_000L
    }
}

/** North and east offset in metres of [to] from [from]. Flat-earth, fine within a few kilometres. */
fun offsetM(from: GeoFix, to: GeoFix): Pair<Double, Double> =
    (to.lat - from.lat) * 111_195.0 to (to.lon - from.lon) * 111_195.0 * cos(Math.toRadians(from.lat))
fun moved(from: GeoFix, northM: Double, eastM: Double) =
    GeoFix(from.lat + northM / 111_195.0, from.lon + eastM / (111_195.0 * cos(Math.toRadians(from.lat))), null, null, from.synthetic)
