package com.jsons.roadmesh.core.junction

enum class JunctionMode(val wireName: String) {
    NO_ADVICE("no_advice"), LOCAL_ONLY("local_only"), MESH_INFORMED("mesh_informed")
}

enum class ObservationChannel(val wireName: String) { LOCAL("local"), MESH("mesh") }

/** Immutable evidence only: no queue count, signal state, or ground-truth traffic input. */
data class QueueObservation(
    val eventId: String,
    val capturedMs: Long,
    val expiresMs: Long,
    val scope: String,
    val sourceId: String,
)

data class JunctionInput(
    val mode: JunctionMode,
    val recipientId: String,
    val nowMs: Long,
    val equipped: Boolean,
    val recipientScope: String,
    val aheadDistanceM: Double,
    val speedMps: Double,
    val observation: QueueObservation,
    val channel: ObservationChannel,
) {
    internal fun isValid(): Boolean = nowMs >= 0 && observation.capturedMs >= 0 &&
        observation.expiresMs > observation.capturedMs && aheadDistanceM.isFinite() &&
        speedMps.isFinite() && speedMps >= 0 &&
        listOf(recipientId, recipientScope, observation.eventId, observation.scope, observation.sourceId)
            .all { it.isNotBlank() && it.none { char -> char == '\t' || char == '\n' || char == '\r' } }
}

sealed class JunctionDecision {
    data class Warn(val ageMs: Long) : JunctionDecision()
    data class Ignore(val reason: String) : JunctionDecision()
}

/**
 * Serial, Android-free experiment policy. RESET starts a new clock/replay epoch.
 * Event identity is (recipientId, eventId), independent of route of delivery or source.
 * Live keys are never evicted under pressure. The transport must preserve immutable IDs.
 * Local sensing and driver response are external simulation assumptions, not guarantees.
 */
class JunctionPolicy(
    private val maxRecipients: Int = 512,
    private val maxAcceptedEventKeys: Int = 512,
) {
    init {
        require(maxRecipients > 0)
        require(maxAcceptedEventKeys > 0)
    }

    private data class EventKey(val recipientId: String, val eventId: String)
    private val lastWarnings = mutableMapOf<String, Long>()
    private val acceptedEvents = mutableMapOf<EventKey, Long>()
    private var lastNowMs: Long? = null

    internal val recipientCount: Int get() = lastWarnings.size
    internal val acceptedEventCount: Int get() = acceptedEvents.size

    fun reset() {
        lastWarnings.clear()
        acceptedEvents.clear()
        lastNowMs = null
    }

    fun evaluate(input: JunctionInput): JunctionDecision {
        fun ignore(reason: String) = JunctionDecision.Ignore(reason)
        if (!input.isValid()) return ignore("invalid_input")
        val now = input.nowMs
        if (lastNowMs?.let { now < it } == true) return ignore("backward_time")
        lastNowMs = now
        acceptedEvents.entries.removeAll { now >= it.value }
        // Subtraction is safe: nonnegative timestamps and a nondecreasing clock.
        lastWarnings.entries.removeAll { now - it.value >= COOLDOWN_MS }

        val event = input.observation
        if (!input.equipped) return ignore("unequipped")
        if (input.mode == JunctionMode.NO_ADVICE) return ignore("no_advice")
        if (input.mode == JunctionMode.LOCAL_ONLY && input.channel == ObservationChannel.MESH) {
            return ignore("channel_ineligible")
        }
        if (input.recipientScope != event.scope) return ignore("scope_mismatch")
        if (input.aheadDistanceM <= 0 || input.aheadDistanceM > MAX_DISTANCE_M) {
            return ignore("out_of_range")
        }
        if (event.capturedMs > now) return ignore("future_observation")
        if (now >= event.expiresMs) return ignore("expired")
        val age = now - event.capturedMs
        if (age > MAX_AGE_MS) return ignore("stale")
        if (input.speedMps <= MIN_SPEED_MPS) return ignore("low_speed")
        val key = EventKey(input.recipientId, event.eventId)
        if (key in acceptedEvents) return ignore("duplicate")
        if (input.recipientId in lastWarnings) return ignore("cooldown")
        if (lastWarnings.size >= maxRecipients || acceptedEvents.size >= maxAcceptedEventKeys) {
            return ignore("state_capacity")
        }
        acceptedEvents[key] = event.expiresMs
        lastWarnings[input.recipientId] = now
        return JunctionDecision.Warn(age)
    }

    companion object {
        const val COOLDOWN_MS = 12_000L
        const val MAX_AGE_MS = 10_000L
        const val MAX_DISTANCE_M = 400.0
        const val MIN_SPEED_MPS = 0.5
    }
}
