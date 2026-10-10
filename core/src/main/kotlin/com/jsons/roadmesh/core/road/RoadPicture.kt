package com.jsons.roadmesh.core.road

import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.core.mesh.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** A hazard on the combined picture: [aheadM] is along this phone's direction of travel (negative is behind). */
data class PictureItem(
    val kind: HazardKind, val aheadM: Int, val reporters: Int, val ageS: Int, val fromSelf: Boolean,
    val evidenceIds: Set<MessageId> = emptySet(), val lineageKnown: Boolean = false,
    val provenances: Set<Provenance> = emptySet(),
)

/** Another phone's view: where it is along the road from here and how fast it is moving. */
data class PicturePeer(val node: NodeId, val rank: Int, val aheadM: Int, val speedMps: Double?, val hops: Int, val records: Int)

/**
 * Advisory derived from recent observations. No calibrated queue-clearance model or speed recommendation is available.
 * [recommendedKmh] and [meetS] remain nullable compatibility fields; they are never populated by this prototype.
 */
data class Forecast(val slowdownAheadM: Int?, val meetS: Double?, val recommendedKmh: Int?, val nextHazard: PictureItem?, val etaS: Double?, val advice: List<String>)

data class RoadPicture(val peers: List<PicturePeer>, val items: List<PictureItem>, val ownSpeedMps: Double?, val forecast: Forecast) {
    val ahead get() = items.filter { it.aheadM >= 0 }.sortedBy { it.aheadM }
    val behind get() = items.filter { it.aheadM < 0 }.sortedBy { -it.aheadM }

    /** Facts for the co-pilot: numbers it may repeat, nothing else. */
    fun facts(): String = buildString {
        append("Asked ${peers.size} phone${if (peers.size == 1) "" else "s"} on the road")
        if (peers.isNotEmpty()) append(" (${peers.joinToString { p -> "${if (p.aheadM >= 0) "${p.aheadM} m ahead" else "${-p.aheadM} m behind"}${p.speedMps?.let { " at ${kmh(it)} km/h" } ?: ""}" }})")
        append(". ")
        if (ahead.isEmpty()) append("No recent report ahead; coverage is unknown. ")
        else append("Ahead: " + ahead.joinToString("; ") {
            val support = if (it.lineageKnown) "${it.reporters} claimed source${if (it.reporters == 1) "" else "s"}" else "source lineage unknown"
            "${it.kind.title().lowercase()} at ${it.aheadM} m ($support, ${it.ageS} s old${if (it.provenances.any { p -> p != Provenance.LIVE }) ", replay/demo" else ""})"
        } + ". ")
        if (behind.isNotEmpty()) append("Behind: " + behind.joinToString("; ") { "${it.kind.title().lowercase()} ${-it.aheadM} m back" } + ". ")
        forecast.advice.forEach { append(it).append(' ') }
    }.trim()

    companion object {
        fun kmh(mps: Double) = (mps * 3.6).roundToInt()

        fun unknown() = RoadPicture(emptyList(), emptyList(), null,
            Forecast(null, null, null, null, null, listOf("Position or direction unavailable; road conditions are unknown.")))

        /**
         * Combines coarse nearby reports. Count original claimed sources, never holders or forwarded count fields.
         * Replays and live observations remain separate. Missing direction cannot establish ahead/behind.
         */
        fun combine(here: GeoFix, own: List<KnownHazard>, replies: List<ControlFrame.Reply>, nowMs: Long, utcMs: Long? = null): RoadPicture {
            if (!here.valid()) return unknown()
            val heading = here.heading ?: return unknown()
            data class Hit(val kind: HazardKind, val at: GeoFix, val age: Int, val self: Boolean, val id: MessageId?, val provenance: Provenance?)
            val local = own.flatMap { h ->
                if (h.evidence.isEmpty()) listOf(Hit(h.kind, h.fix, h.ageS(nowMs), h.own, null, null))
                else h.evidence.filter { it.capturedMs <= nowMs }.map { e ->
                    Hit(h.kind, e.fix ?: h.fix, ((nowMs - e.capturedMs) / 1000).coerceAtMost(65_535).toInt(), e.own, e.id, e.provenance)
                }
            }
            val remote = replies.flatMap { reply -> reply.records.mapNotNull { r ->
                val heldMs = reply.receivedAtMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0
                var age = r.ageS.toLong() + (heldMs + 999) / 1000
                if (r.ageS < 0 || r.capturedUtcMs != null && utcMs != null && r.capturedUtcMs > utcMs) return@mapNotNull null
                if (r.capturedUtcMs != null && utcMs != null) age = maxOf(age, (utcMs - r.capturedUtcMs + 999) / 1000)
                val identified = r.observationId != null && r.capturedUtcMs != null && r.provenance != null
                Hit(r.kind, moved(reply.fix, r.northM.toDouble(), r.eastM.toDouble()), age.coerceAtMost(65_535).toInt(), false,
                    r.observationId.takeIf { identified }, r.provenance.takeIf { identified })
            } }
            val candidates = (local + remote).filter { it.at.valid() && it.age >= 0 }
            val identified = candidates.filter { it.id != null }.groupBy { it.id }.values.mapNotNull { copies ->
                val first = copies.first()
                // Conflicting copies of one identity cannot create two hazards or be silently fused.
                if (copies.any { it.kind != first.kind || it.provenance != first.provenance || distanceM(it.at, first.at) > 30.0 }) null
                else copies.maxBy { it.age }.copy(self = copies.any { it.self })
            }
            val hits = (identified + candidates.filter { it.id == null }).filter { it.age < it.kind.lifetimeS }
            val merged = mutableListOf<MutableList<Hit>>()
            for (h in hits) merged.firstOrNull { g -> g[0].kind == h.kind && g[0].provenance == h.provenance && distanceM(g[0].at, h.at) <= 30.0 }
                ?.add(h) ?: merged.add(mutableListOf(h))
            val items = merged.map { g ->
                val ids = g.mapNotNull { it.id }.toSet()
                // Conflicting ages for a copied identity retain its older age; independent newer evidence can refresh a condition.
                val age = g.groupBy { it.id }.values.minOf { copies -> copies.maxOf { it.age } }
                PictureItem(g[0].kind, alongM(here, g[0].at, heading).roundToInt(), ids.map { it.origin }.toSet().size,
                    age, g.any { it.self }, ids, ids.isNotEmpty(), g.mapNotNull { it.provenance }.toSet())
            }
            // A slow opposing/crossing vehicle is not evidence of a queue in this direction.
            // This is only a coarse corridor check; it cannot establish precise lane or flyover membership.
            val peers = replies.distinctBy { it.responder }.filter { r ->
                val peerHeading = r.fix.heading ?: return@filter false
                val difference = abs(((peerHeading - heading + 540) % 360) - 180)
                val lateral = abs(alongM(here, r.fix, (heading + 90) % 360))
                difference <= 45.0 && lateral <= 35.0
            }.map { r ->
                PicturePeer(r.responder, r.rank, alongM(here, r.fix, heading).roundToInt(), r.fix.speedMps, r.hop, r.records.size)
            }.sortedByDescending { it.aheadM }
            return RoadPicture(peers, items, here.speedMps, forecast(here.speedMps, peers, items))
        }

        fun forecast(speed: Double?, peers: List<PicturePeer>, items: List<PictureItem>): Forecast {
            val v = speed?.takeIf { it.isFinite() && it > 1.0 && it <= 100.0 }
            val slowPeers = peers.filter { it.aheadM > 0 && it.speedMps != null && (v != null && it.speedMps < v * 0.5 || it.speedMps < 3.0) }
            val slowReports = items.filter { it.aheadM > 0 && (it.kind == HazardKind.SLOWDOWN || it.kind == HazardKind.BRAKING) }
            val slowAt = (slowPeers.map { it.aheadM } + slowReports.map { it.aheadM }).minOrNull()
            val advice = mutableListOf<String>()
            if (slowAt != null) {
                advice += "Slow traffic reported about $slowAt m ahead; prepare to slow and keep the junction exit clear. Clearance time is unknown."
            }
            val next = items.filter { it.aheadM in 0..1_500 && it.kind.use == HazardUse.CAUTION && it.kind != HazardKind.SLOWDOWN }.minByOrNull { it.aheadM }
            val eta = next?.let { n -> v?.let { n.aheadM / it } }
            next?.let { n ->
                val when_ = eta?.let { " (about ${it.roundToInt()} s to the reported position at unchanged speed)" } ?: ""
                advice += when (n.kind) {
                    HazardKind.POTHOLE -> "Pothole reported ${n.aheadM} m ahead$when_: watch the road surface and adapt to conditions."
                    HazardKind.ACCIDENT -> "Accident ${n.aheadM} m ahead$when_: slow down early, expect a blocked lane and people on the road."
                    HazardKind.WRONG_WAY -> "Wrong-way vehicle reported ${n.aheadM} m ahead$when_: stay alert and be prepared to stop."
                    HazardKind.PEDESTRIAN -> "Someone is on the road ${n.aheadM} m ahead$when_: slow down."
                    HazardKind.BRAKING -> "Traffic is braking ${n.aheadM} m ahead$when_: ease off early."
                    HazardKind.DRIVER_SOS -> "A driver ${n.aheadM} m ahead$when_ may be drowsy: keep your distance and do not overtake closely."
                    HazardKind.ROAD_NARROWED -> "Road narrowing reported ${n.aheadM} m ahead$when_: watch for opposing traffic and leave room. Passage availability is unknown."
                    else -> "${n.kind.title()} ${n.aheadM} m ahead$when_."
                }
            }
            if (slowAt == null && next == null) advice += "Nothing recent reported on the road ahead within range; this does not establish that it is clear."
            return Forecast(slowAt, null, null, next, eta, advice)
        }
    }
}

/** Signed metres as words: "120 m ahead" or "80 m behind". */
fun aheadWords(m: Int) = if (m >= 0) "$m m ahead" else "${abs(m)} m behind"
