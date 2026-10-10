package com.jsons.roadmesh.core.perception

/** Where a drowsiness episode stands: the phone alarms first and only tells the mesh once the driver has had a chance to answer. */
enum class EscalationState { AWAKE, ALERTING, SOS_ACTIVE }

/** What the app should do now. The state machine never touches audio or radio itself. */
sealed interface EscalationAction {
    /** Start the wake-up alarm on this phone. */
    data class StartAlarm(val level: Alertness, val reason: String) : EscalationAction
    /** The driver is back: silence the alarm. */
    data object StopAlarm : EscalationAction
    /** Broadcast the SOS: same [episode] every time, [sequence] rising from 0 so receivers can tell a repeat from a new episode. */
    data class SendSos(val episode: Int, val sequence: Int, val level: Alertness, val reason: String, val confidence: Double) : EscalationAction
    /** One last broadcast telling nearby phones the driver recovered, so they stop warning before the SOS would expire. */
    data class SendClear(val episode: Int, val sequence: Int) : EscalationAction
}

/** Starting values, to tune on real drives. */
data class EscalationRules(
    /** How long a drowsy (not yet unresponsive) driver has to answer before the mesh hears about it. */
    val drowsyWindowS: Double = 6.0,
    /** How long an unresponsive driver gets. */
    val unresponsiveWindowS: Double = 2.0,
    /** While the SOS is active it is sent again this often. */
    val repeatS: Double = 2.0,
    /** A new SOS episode cannot start within this long of the last one, so a flickering face cannot flood the mesh. */
    val episodeCooldownS: Double = 60.0,
)

/**
 * Alarm first, SOS second:
 * ```
 * AWAKE --drowsy--> ALERTING --no answer in the window--> SOS_ACTIVE --recovered or acknowledged--> AWAKE (+ CLEAR)
 *                      |
 *                      +--tap, volume key, or eyes open and head up--> AWAKE (no SOS)
 * ```
 * The window is [EscalationRules.drowsyWindowS] for a drowsy driver and [EscalationRules.unresponsiveWindowS] once the
 * driver is unresponsive. An answer must be deliberate: a tap, a volume key, or [DriverWatch] reporting that the driver has
 * been awake long enough to end the episode (so a single reopened eye does not count). Deterministic and Android-free.
 */
class DriverEscalation(private val rules: EscalationRules = EscalationRules()) {
    var state = EscalationState.AWAKE; private set
    private var windowEndS = 0.0
    private var level = Alertness.DROWSY
    private var reason = ""
    private var confidence = 0.0
    private var episode = 0
    private var sequence = 0
    private var nextSendS = 0.0
    private var lastEpisodeStartS: Double? = null

    /** An alarm from [DriverWatch], raised once per level per episode. */
    fun onAlarm(alarm: DriverAlarm, tS: Double): List<EscalationAction> {
        level = alarm.level; reason = alarm.reason; confidence = alarm.confidence
        val window = if (alarm.level == Alertness.UNRESPONSIVE) rules.unresponsiveWindowS else rules.drowsyWindowS
        return when (state) {
            EscalationState.AWAKE -> {
                state = EscalationState.ALERTING
                windowEndS = tS + window
                listOf(EscalationAction.StartAlarm(alarm.level, alarm.reason))
            }
            // Getting worse shortens the wait; it never lengthens it.
            EscalationState.ALERTING -> { windowEndS = minOf(windowEndS, tS + window); emptyList() }
            // Already telling the mesh: send the worse level straight away.
            EscalationState.SOS_ACTIVE -> { nextSendS = tS; emptyList() }
        }
    }

    /** The driver answered on purpose: a tap or a volume key. */
    fun acknowledge(): List<EscalationAction> = end()

    /**
     * Call on every camera tick. [episodeOngoing] is [DriverWatch.alarmed]: it turns false once the driver has been awake
     * for the recovery time, which also counts as an answer.
     */
    fun tick(tS: Double, episodeOngoing: Boolean): List<EscalationAction> = when (state) {
        EscalationState.AWAKE -> emptyList()
        EscalationState.ALERTING -> when {
            !episodeOngoing -> end()
            tS >= windowEndS -> startSos(tS)
            else -> emptyList()
        }
        EscalationState.SOS_ACTIVE -> when {
            !episodeOngoing -> end()
            tS >= nextSendS -> send(tS)
            else -> emptyList()
        }
    }

    private fun startSos(tS: Double): List<EscalationAction> {
        val last = lastEpisodeStartS
        // Too soon after the last SOS: keep the alarm going and try again when the cooldown ends. A driver who is
        // unresponsive always gets through; the cooldown only stops a flickering drowsy reading from flooding the mesh.
        if (level != Alertness.UNRESPONSIVE && last != null && tS - last < rules.episodeCooldownS) return emptyList()
        episode++; sequence = 0; lastEpisodeStartS = tS
        state = EscalationState.SOS_ACTIVE
        return send(tS)
    }

    private fun send(tS: Double): List<EscalationAction> {
        nextSendS = tS + rules.repeatS
        return listOf(EscalationAction.SendSos(episode, sequence++, level, reason, confidence))
    }

    private fun end(): List<EscalationAction> {
        if (state == EscalationState.AWAKE) return emptyList()
        val wasActive = state == EscalationState.SOS_ACTIVE
        state = EscalationState.AWAKE
        return buildList {
            add(EscalationAction.StopAlarm)
            if (wasActive) add(EscalationAction.SendClear(episode, sequence++))
        }
    }

    fun reset() {
        state = EscalationState.AWAKE; windowEndS = 0.0; nextSendS = 0.0; sequence = 0; lastEpisodeStartS = null
    }
}
