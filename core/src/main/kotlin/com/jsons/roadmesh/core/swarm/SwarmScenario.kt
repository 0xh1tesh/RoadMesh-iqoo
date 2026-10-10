package com.jsons.roadmesh.core.swarm

import com.jsons.roadmesh.core.mesh.GeoFix
import kotlin.math.cos
import kotlin.math.hypot

data class SwarmScenarioConfig(
    val relayEnabled: Boolean = true,
    val alternateEnabled: Boolean = false,
    val v2iCovered: Boolean = false,
    val elapsedMs: Long = 2_000,
    val navigationDelayMs: Long? = null,
    val radioRangeM: Double = 50.0,
    val perHopDelayMs: Long = 150,
    val sensorRangeM: Double = 30.0,
) {
    init {
        require(elapsedMs in 0..60_000)
        require(navigationDelayMs == null || navigationDelayMs in 0..60_000)
        require(radioRangeM.isFinite() && radioRangeM in 1.0..500.0)
        require(perHopDelayMs in 1..5_000)
        require(sensorRangeM.isFinite() && sensorRangeM in 1.0..500.0)
    }
}

data class SwarmComparison(
    val label: String,
    val assessment: SwarmAssessment,
    val path: List<String>,
    /** Milliseconds AFTER the common event sample. Null means no reachable delivery path. */
    val deliveredAtMs: Long?,
    /** Reserved for measured approach trajectories. This static harness deliberately does not invent one. */
    val warningLeadSeconds: Double? = null,
    val detail: String,
)

data class SwarmScenarioResult(
    val comparisons: List<SwarmComparison>,
    val participants: List<SwarmParticipant>,
    val observations: List<SwarmObservation>,
    val elapsedMs: Long,
    val assumptions: List<String>,
    val configuredLinks: List<Pair<String, String>>,
)

/**
 * Deterministic observation-delivery experiment, NOT a traffic or RF simulator.
 * Every method receives the exact same timestamped event sample when its stated visibility/channel allows it.
 * Explicitly allowing an incident feed avoids claiming that navigation products cannot receive reports.
 * V2I is allowed to win when it covers the site and mesh connectivity fails.
 */
object SwarmScenario {
    private const val EVENT_AT_MS = 1_000L
    private const val EVENT_TTL_MS = 15_000L

    fun run(config: SwarmScenarioConfig = SwarmScenarioConfig()): SwarmScenarioResult {
        val nowMs = EVENT_AT_MS + config.elapsedMs
        fun participant(id: String, north: Double, east: Double = 0.0, heading: Double = 0.0) =
            SwarmParticipant(id, position(north, east, heading), nowMs, 3.0, 5.0)
        // These are continuously refreshed stationary topology fixtures, not trace-derived car positions.
        val participants = buildList {
            add(participant("A", 80.0))
            if (config.relayEnabled) add(participant("B", 40.0))
            add(participant("C", 0.0))
            if (config.alternateEnabled) add(participant("D", 40.0, 8.0))
            add(participant("E", 120.0, 3.0, 180.0))
        }.sortedBy { it.id }
        val ego = participants.first { it.id == "C" }
        val event = SwarmObservation("stopped-bus-001", "A", ObservationKind.OBSTRUCTION,
            position(100.0), EVENT_AT_MS, EVENT_TTL_MS, 0.85, 3.0)
        val links = buildList {
            for (a in participants.indices) for (b in a + 1 until participants.size) {
                val (east, north) = offset(participants[a].fix!!, participants[b].fix!!)
                if (hypot(east, north) <= config.radioRangeM) add(participants[a].id to participants[b].id)
            }
        }
        val meshPath = shortestPath("A", "C", links)
        val sourceCanObserve = distance(participants.first { it.id == "A" }.fix!!, event.fix) <= config.sensorRangeM
        val meshArrival = if (sourceCanObserve && meshPath.isNotEmpty()) (meshPath.size - 1) * config.perHopDelayMs else null
        val localVisible = distance(ego.fix!!, event.fix) <= config.sensorRangeM
        // Coverage is a declared experimental condition; this does not model a particular roadside product.
        val v2iVisible = config.v2iCovered
        fun comparison(label: String, arrival: Long?, path: List<String>, detail: String): SwarmComparison {
            val delivered = arrival != null && config.elapsedMs >= arrival
            val assessment = SwarmAdvisor.evaluate(ego, participants, if (delivered) listOf(event) else emptyList(), nowMs)
            val phase = when {
                arrival == null -> "No delivery in this configuration."
                !delivered -> "Observation has not arrived yet."
                assessment.status == SwarmStatus.UNKNOWN -> "Observation expired; absence of a fresh report is not clearance."
                else -> "Original observation retained; relays do not add independent witnesses."
            }
            return SwarmComparison(label, assessment, if (delivered) path else emptyList(), arrival,
                detail = "$detail $phase")
        }
        val comparisons = listOf(
            comparison("Navigation", config.navigationDelayMs, listOf("Incident feed", "C"),
                if (config.navigationDelayMs == null) "Map and GPS only; no live incident feed supplied." else "The same observation is supplied by an incident feed after ${config.navigationDelayMs} ms."),
            comparison("Local camera", if (localVisible) 0 else null, listOf("C"),
                "Recipient C is 100 m from the event; configured sight range is ${config.sensorRangeM.toInt()} m."),
            comparison("Fixed V2I", if (v2iVisible) 250 else null, listOf("Roadside sensor", "C"),
                if (v2iVisible) "A roadside sensor covers the site; configured delivery takes 250 ms." else "The event is outside the configured roadside sensor coverage."),
            comparison("RoadMesh", meshArrival, meshPath,
                "Vehicle A can observe the site when within sensor range. Links require distance <= ${config.radioRangeM.toInt()} m; delivery takes ${config.perHopDelayMs} ms per hop."),
        )
        return SwarmScenarioResult(comparisons, participants, listOf(event), config.elapsedMs, listOf(
            "Synthetic fixed road snapshot. A observes a stopped bus at 100 m; C receives from 0 m. E faces the opposite direction.",
            "Identical event, detector confidence and 15 s original lifetime for all four methods; no repeated observation refresh.",
            "Ideal undirected range-based links, deterministic delay, no radio interference, packet loss, clock error or operating-system suspension.",
            "A shortest reachable path establishes possible delivery in this harness; the phone's BLE stack is not exercised here.",
            "Navigation incident feeds and fixed V2I coverage are configurable. Neither comparison represents a named commercial product.",
            "Participant positions refresh; the single event does not. No collision reduction, travel-time saving or real warning lead time is measured.",
        ), links)
    }

    /** Breadth-first traversal only uses supplied live links; missing relays never create a virtual bridge. */
    fun shortestPath(source: String, destination: String, links: List<Pair<String, String>>): List<String> {
        if (source == destination) return listOf(source)
        val neighbours = mutableMapOf<String, MutableSet<String>>()
        for ((a, b) in links) {
            neighbours.getOrPut(a) { sortedSetOf() }.add(b)
            neighbours.getOrPut(b) { sortedSetOf() }.add(a)
        }
        val pending = ArrayDeque<String>()
        val previous = mutableMapOf<String, String?>()
        pending.add(source)
        previous[source] = null
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            for (next in neighbours[node].orEmpty()) {
                if (next in previous) continue
                previous[next] = node
                if (next == destination) {
                    val path = mutableListOf(next)
                    var step = previous[next]
                    while (step != null) { path.add(step); step = previous[step] }
                    return path.reversed()
                }
                pending.add(next)
            }
        }
        return emptyList()
    }

    private fun position(northM: Double, eastM: Double = 0.0, heading: Double = 0.0): GeoFix = GeoFix(
        12.9716 + Math.toDegrees(northM / 6_371_000.0),
        77.5946 + Math.toDegrees(eastM / (6_371_000.0 * cos(Math.toRadians(12.9716)))),
        heading, 0.0, true,
    )

    private fun distance(a: GeoFix, b: GeoFix): Double = offset(a, b).let { hypot(it.first, it.second) }
}

/** Dependency-free JVM export of the exact evaluator used by Android. Run through :core:swarmReport. */
object SwarmScenarioCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty()) { "This report exports the fixed audit suite; no command-line options are supported." }
        print(reportJson())
    }

    fun reportJson(): String {
        val scenarios = linkedMapOf(
            "connected" to SwarmScenarioConfig(),
            "relay_removed" to SwarmScenarioConfig(relayEnabled = false),
            "alternate_path" to SwarmScenarioConfig(relayEnabled = false, alternateEnabled = true),
            "v2i_covers_mesh_disconnected" to SwarmScenarioConfig(relayEnabled = false, v2iCovered = true),
            "navigation_feed_supplied" to SwarmScenarioConfig(navigationDelayMs = 500),
            "before_delivery" to SwarmScenarioConfig(elapsedMs = 0),
            "expired" to SwarmScenarioConfig(elapsedMs = 16_000, v2iCovered = true, navigationDelayMs = 500),
        )
        return "{\"schema\":1,\"kind\":\"synthetic_observation_delivery\",\"scenarios\":[" + scenarios.entries.joinToString(",") { (name, config) ->
            val result = SwarmScenario.run(config)
            "{\"name\":${jsonString(name)},\"elapsedMs\":${result.elapsedMs},\"assumptions\":[${result.assumptions.joinToString(",", transform = ::jsonString)}],\"comparisons\":[" +
                result.comparisons.joinToString(",") { c ->
                    "{\"system\":${jsonString(c.label)},\"status\":${jsonString(c.assessment.status.name)},\"independentSources\":${c.assessment.independentSources},\"observationAgeMs\":${c.assessment.oldestAgeMs},\"deliveryDelayMs\":${c.deliveredAtMs},\"path\":[${c.path.joinToString(",", transform = ::jsonString)}],\"detail\":${jsonString(c.detail)}}"
                } + "]}"
        } + "],\"learningMode\":\"synthetic_shadow_only\",\"learning\":[" + ClearanceLearningDemo.run().joinToString(",") { r ->
            "{\"label\":${jsonString(r.label)},\"trainingWindowSamples\":${r.trainingSamples},\"estimateSeconds\":${r.estimateSeconds},\"baselineSeconds\":${r.baselineSeconds},\"heldOutSeconds\":[${r.heldOutSeconds.joinToString(",")}],\"adaptiveMaeSeconds\":${r.adaptiveMaeSeconds},\"baselineMaeSeconds\":${r.baselineMaeSeconds},\"detail\":${jsonString(r.detail)}}"
        } + "]}\n"
    }

    private fun jsonString(text: String): String = buildString {
        append('"')
        for (c in text) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
}
