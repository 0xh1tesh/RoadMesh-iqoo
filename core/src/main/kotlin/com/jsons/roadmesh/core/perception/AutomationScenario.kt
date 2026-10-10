package com.jsons.roadmesh.core.perception

import com.jsons.roadmesh.core.mesh.HazardKind
import kotlin.math.exp

data class AutomationScenarioResult(
    val id: String,
    val title: String,
    val description: String,
    val expectedKinds: Set<HazardKind>,
    val observedKinds: Set<HazardKind>,
    val passed: Boolean,
    val frames: Int,
    val events: List<AutomaticTrafficEvent>,
    val finalFrame: AutomatedTrafficFrame,
)

/** Deterministic synthetic detector outputs; evaluates temporal rules, not YOLO accuracy or Bluetooth delivery. */
object AutomationScenario {
    private data class Input(val atS: Double, val boxes: List<SeenObject>, val ego: EgoMotion?)
    private data class Case(val id: String, val title: String, val description: String,
        val expected: Set<HazardKind>, val input: (Int) -> Input)

    private fun vehicle(cx: Float = 0.5f, scale: Double = 1.0): SeenObject {
        val w = (0.12 * scale).toFloat()
        val h = (0.10 * scale).toFloat()
        return SeenObject(ObjectClass.CAR, 0.9f, cx - w / 2, 0.72f - h, cx + w / 2, 0.72f)
    }
    private fun ego(t: Double, speed: Double, heading: Double = 0.0) = EgoMotion(t, speed, 0.1, heading, 2.0)
    private val cases = listOf(
        Case("steady_traffic", "Steady traffic", "Two stable vehicle tracks at constant host speed should not create an event.", emptySet()) { i ->
            val t = i * 0.1; Input(t, listOf(vehicle(0.4f), vehicle(0.6f)), ego(t, 12.0))
        },
        Case("single_frame_glitch", "One-frame detector glitch", "One sudden oversized box must not count as persistent evidence.", emptySet()) { i ->
            val t = i * 0.1; Input(t, listOf(vehicle(scale = if (i == 8) 2.5 else 1.0)), ego(t, 12.0))
        },
        Case("rapid_approach", "Sustained relative approach", "Growing boxes support relative approach; they do not prove the target is braking.", setOf(HazardKind.VEHICLE_CLOSE)) { i ->
            val t = i * 0.1; Input(t, listOf(vehicle(scale = exp(t * 0.7))), null)
        },
        Case("collective_slowdown", "Corroborated traffic slowdown", "Two persistent vehicle gaps remain steady while accurate host speed measurements fall.", setOf(HazardKind.SLOWDOWN)) { i ->
            val t = i * 0.1; Input(t, listOf(vehicle(0.4f), vehicle(0.6f)), ego(t, 12.0 - 2.5 * t))
        },
        Case("host_hard_braking", "Measured host hard braking", "Sharp measured host deceleration is shared with visual traffic context; target braking is not asserted.", setOf(HazardKind.BRAKING)) { i ->
            val t = i * 0.1; Input(t, listOf(vehicle()), ego(t, 15.0 - 5.0 * t))
        },
        Case("missing_speed", "Unknown speed quality", "Stable multiple tracks with no reliable speed measurement cannot prove a collective slowdown.", emptySet()) { i ->
            val t = i * 0.1; Input(t, listOf(vehicle(0.4f), vehicle(0.6f)), EgoMotion(t, 12.0 - 2.5 * t))
        },
        Case("turning_camera", "Turning suppresses motion claims", "Heading changes during apparent growth invalidate the straight-camera motion assumption.", emptySet()) { i ->
            val t = i * 0.1; Input(t, listOf(vehicle(scale = exp(t * 0.7))), ego(t, 12.0, 40.0 * t))
        },
    )

    fun runAll(): List<AutomationScenarioResult> = cases.map(::evaluate)
    fun run(id: String): AutomationScenarioResult = evaluate(cases.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown automation scenario: $id"))

    private fun evaluate(case: Case): AutomationScenarioResult {
        val analyzer = AutomatedTrafficAnalyzer()
        val events = ArrayList<AutomaticTrafficEvent>()
        var frame = AutomatedTrafficFrame(emptyList(), emptyList(), "Not started", 0)
        repeat(18) { i ->
            val input = case.input(i)
            frame = analyzer.update(input.atS, input.boxes, input.ego)
            events += frame.events
        }
        val observed = events.map { it.kind }.toSet()
        return AutomationScenarioResult(case.id, case.title, case.description, case.expected, observed,
            observed == case.expected, 18, events, frame)
    }

    fun reportJson(): String = runAll().joinToString(prefix = "{\"synthetic\":true,\"scope\":\"Temporal rules over supplied detections; no detector or radio validation\",\"scenarios\":[", postfix = "]}") { r ->
        "{\"id\":\"${r.id}\",\"passed\":${r.passed},\"frames\":${r.frames}," +
            "\"expected\":${kindsJson(r.expectedKinds)},\"observed\":${kindsJson(r.observedKinds)},\"events\":${r.events.size}}"
    }
    private fun kindsJson(kinds: Set<HazardKind>) = kinds.joinToString(prefix = "[", postfix = "]") { "\"${it.name}\"" }
}

object AutomationScenarioCli {
    @JvmStatic fun main(args: Array<String>) { println(AutomationScenario.reportJson()) }
}
