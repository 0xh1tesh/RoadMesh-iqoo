package com.jsons.roadmesh.core.road

import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.core.mesh.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** A hazard on the combined picture: [aheadM] is along this phone's direction of travel (negative is behind). */
data class PictureItem(val kind: HazardKind, val aheadM: Int, val reporters: Int, val ageS: Int, val fromSelf: Boolean)

/** Another phone's view: where it is along the road from here and how fast it is moving. */
data class PicturePeer(val node: NodeId, val rank: Int, val aheadM: Int, val speedMps: Double?, val hops: Int, val records: Int)

/**
 * Prediction made on this phone from the combined picture, never sent anywhere. A slowdown is a stretch where phones
 * ahead move much slower than this one; it travels back toward us as a wave ([waveMps], about 16 km/h on city roads).
 * Easing off early to [recommendedKmh] means arriving after it clears instead of braking into it, which is what damps
 * phantom jams.
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
        if (ahead.isEmpty()) append("No hazards reported ahead. ")
        else append("Ahead: " + ahead.joinToString("; ") { "${it.kind.title().lowercase()} at ${it.aheadM} m (${it.reporters} phone${if (it.reporters == 1) "" else "s"}, ${it.ageS} s old)" } + ". ")
        if (behind.isNotEmpty()) append("Behind: " + behind.joinToString("; ") { "${it.kind.title().lowercase()} ${-it.aheadM} m back" } + ". ")
        forecast.advice.forEach { append(it).append(' ') }
    }.trim()

    companion object {
        private const val WAVE_MPS = 4.5
        private const val CLEAR_S = 20.0
        private const val MIN_MPS = 4.2

        fun kmh(mps: Double) = (mps * 3.6).roundToInt()

        /**
         * Combines this phone's own road map with every reply. Records of the same kind within 30 m are one hazard;
         * its corroboration is the larger of the reporters any phone counted and the number of phones that listed it.
         */
        fun combine(here: GeoFix, own: List<KnownHazard>, replies: List<ControlFrame.Reply>, nowMs: Long): RoadPicture {
            val heading = here.heading ?: replies.mapNotNull { it.fix.heading }.let { if (it.isEmpty()) 0.0 else meanHeading(it) }
            data class Hit(val kind: HazardKind, val at: GeoFix, val reporters: Int, val age: Int, val self: Boolean, val source: NodeId?)
            val hits = own.map { Hit(it.kind, it.fix, it.reporters.size, it.ageS(nowMs), true, null) } +
                replies.flatMap { r -> r.records.map { Hit(it.kind, moved(r.fix, it.northM.toDouble(), it.eastM.toDouble()), it.reporters, it.ageS, false, r.responder) } }
            val merged = mutableListOf<MutableList<Hit>>()
            for (h in hits) merged.firstOrNull { g -> g[0].kind == h.kind && distanceM(g[0].at, h.at) <= 30.0 }?.add(h) ?: merged.add(mutableListOf(h))
            val items = merged.map { g ->
                val sources = g.mapNotNull { it.source }.toSet().size + if (g.any { it.self }) 1 else 0
                PictureItem(g[0].kind, alongM(here, g[0].at, heading).roundToInt(), maxOf(g.maxOf { it.reporters }, sources), g.minOf { it.age }, g.any { it.self })
            }
            val peers = replies.distinctBy { it.responder }.map { r ->
                PicturePeer(r.responder, r.rank, alongM(here, r.fix, heading).roundToInt(), r.fix.speedMps, r.hop, r.records.size)
            }.sortedByDescending { it.aheadM }
            return RoadPicture(peers, items, here.speedMps, forecast(here.speedMps, peers, items))
        }

        fun forecast(speed: Double?, peers: List<PicturePeer>, items: List<PictureItem>): Forecast {
            val v = speed?.takeIf { it > 1.0 }
            val slowPeers = peers.filter { it.aheadM > 0 && it.speedMps != null && (v != null && it.speedMps < v * 0.5 || it.speedMps < 3.0) }
            val slowReports = items.filter { it.aheadM > 0 && (it.kind == HazardKind.SLOWDOWN || it.kind == HazardKind.BRAKING) }
            val slowAt = (slowPeers.map { it.aheadM } + slowReports.map { it.aheadM }).minOrNull()
            val advice = mutableListOf<String>()
            var meet: Double? = null; var rec: Int? = null
            if (slowAt != null) {
                if (v != null) {
                    meet = slowAt / (v + WAVE_MPS)
                    val target = (v * slowAt / (slowAt + WAVE_MPS * CLEAR_S)).coerceIn(MIN_MPS, v)
                    rec = kmh(target)
                    advice += "Traffic is slowing about $slowAt m ahead; at your speed you would meet it in about ${meet.roundToInt()} s."
                    if (rec < kmh(v) - 3) advice += "Easing off to about $rec km/h now lets it clear before you arrive, so you avoid braking hard and don't pass the jam back."
                } else advice += "Traffic is slowing about $slowAt m ahead."
            }
            val next = items.filter { it.aheadM in 0..1_500 && it.kind.use == HazardUse.CAUTION && it.kind != HazardKind.SLOWDOWN }.minByOrNull { it.aheadM }
            val eta = next?.let { n -> v?.let { n.aheadM / it } }
            next?.let { n ->
                val when_ = eta?.let { " (about ${it.roundToInt()} s away)" } ?: ""
                advice += when (n.kind) {
                    HazardKind.POTHOLE -> "Pothole ${n.aheadM} m ahead$when_: slow to about 20 km/h and watch the road surface."
                    HazardKind.ACCIDENT -> "Accident ${n.aheadM} m ahead$when_: slow down early, expect a blocked lane and people on the road."
                    HazardKind.WRONG_WAY -> "A vehicle is on the wrong side ${n.aheadM} m ahead$when_: keep left and be ready to stop."
                    HazardKind.PEDESTRIAN -> "Someone is on the road ${n.aheadM} m ahead$when_: slow down."
                    HazardKind.BRAKING -> "Traffic is braking ${n.aheadM} m ahead$when_: ease off early."
                    HazardKind.DRIVER_SOS -> "A driver ${n.aheadM} m ahead$when_ may be asleep or unconscious: slow down and keep clear."
                    else -> "${n.kind.title()} ${n.aheadM} m ahead$when_."
                }
            }
            if (slowAt == null && next == null) advice += "Nothing reported on the road ahead within range."
            return Forecast(slowAt, meet, rec, next, eta, advice)
        }
    }
}

/** Signed metres as words: "120 m ahead" or "80 m behind". */
fun aheadWords(m: Int) = if (m >= 0) "$m m ahead" else "${abs(m)} m behind"
