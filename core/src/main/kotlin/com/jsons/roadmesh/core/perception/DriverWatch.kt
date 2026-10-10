package com.jsons.roadmesh.core.perception

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One front-camera reading of the driver's face at [tS] seconds. Eye-open probabilities are 0..1, null when the face
 * detector could not tell. Angles are degrees from the camera's view: pitch is positive with the chin up, roll positive
 * with the head tilted towards the right shoulder.
 */
data class FaceSample(val tS: Double, val face: Boolean, val leftEyeOpen: Float? = null, val rightEyeOpen: Float? = null,
                      val pitchDeg: Float = 0f, val rollDeg: Float = 0f)

/** How the driver looks right now, worst last. */
enum class Alertness { NO_FACE, CALIBRATING, AWAKE, DROWSY, UNRESPONSIVE }

/** The live picture for the screen: eyes shut for [eyesClosedS], [perclos] the share of the recent window with eyes shut. */
data class DriverReading(val alertness: Alertness = Alertness.NO_FACE, val eyesClosedS: Double = 0.0, val perclos: Double? = null,
                         val headDropDeg: Float? = null, val reason: String? = null)

/** Raised once when a driver turns drowsy, and again if it gets worse (unresponsive). Each one warns and sends an SOS. */
data class DriverAlarm(val level: Alertness, val atS: Double, val reason: String, val confidence: Double)

/**
 * Starting values for a phone on the dashboard facing the driver, taken from the drowsy-driving literature (a microsleep
 * is an eye closure of about a second or more; PERCLOS over 30% means drowsy). First guesses to check on real drives,
 * not verified thresholds.
 */
data class DriverRules(
    /** An eye counts as shut below this open probability. */
    val closedBelow: Float = 0.3f,
    /** Eyes shut this long is a microsleep: drowsy. */
    val drowsyClosedS: Double = 1.2,
    /** Eyes shut this long and the driver is not responding. */
    val faintClosedS: Double = 3.0,
    val perclosWindowS: Double = 30.0,
    val perclosDrowsy: Double = 0.3,
    /** PERCLOS only counts once the window holds this much of the drive. */
    val perclosMinS: Double = 10.0,
    /** The chin this far below the calibrated pose, or the head slumped this far sideways, counts as a dropped head. */
    val headDropDeg: Float = 25f,
    val headRollDeg: Float = 35f,
    /** A dropped head held this long with eyes shut (or eyes unreadable for twice as long) is passing out. */
    val headDropS: Double = 1.5,
    /** The face gone this long straight after the eyes shut or the head dropped: the driver slumped out of view. */
    val lostAfterS: Double = 2.5,
    /** Seconds of open-eyed face that set the driver's normal head pose. */
    val calibrateS: Double = 2.0,
    /** Eyes open and head up this long ends an episode. */
    val recoverS: Double = 1.5,
)

/**
 * Drowsiness and passing-out rules over a stream of [FaceSample]s from a camera facing the driver:
 * - Drowsy: eyes shut for [DriverRules.drowsyClosedS], or shut for [DriverRules.perclosDrowsy] of the last
 *   [DriverRules.perclosWindowS] seconds.
 * - Unresponsive: eyes shut for [DriverRules.faintClosedS]; the head dropped with eyes shut for [DriverRules.headDropS];
 *   or the face lost for [DriverRules.lostAfterS] right after either.
 * A level is raised once per episode, and the episode ends when the driver is awake again for [DriverRules.recoverS].
 * Deterministic and Android-free.
 */
class DriverWatch(private val rules: DriverRules = DriverRules()) {
    private val calibration = ArrayList<FaceSample>()
    private var basePitch: Float? = null
    private var baseRoll = 0f
    private var closedSince: Double? = null
    private var droppedSince: Double? = null
    private var lostSince: Double? = null
    private var impairedAtS: Double? = null
    private var awakeSince: Double? = null
    private var episode: Alertness? = null
    private val window = ArrayDeque<Pair<Double, Boolean>>()
    var reading = DriverReading(); private set

    fun reset() {
        calibration.clear(); basePitch = null; baseRoll = 0f; closedSince = null; droppedSince = null; lostSince = null
        impairedAtS = null; awakeSince = null; episode = null; window.clear(); reading = DriverReading()
    }

    fun update(s: FaceSample): DriverAlarm? {
        if (!s.face) return lost(s.tS)
        lostSince = null
        val eyes = listOfNotNull(s.leftEyeOpen, s.rightEyeOpen)
        val closed = eyes.isNotEmpty() && eyes.max() < rules.closedBelow
        closedSince = if (closed) closedSince ?: s.tS else null
        val closedS = closedSince?.let { s.tS - it } ?: 0.0

        val base = basePitch
        if (base == null && eyes.isNotEmpty() && !closed) calibrate(s)
        val drop = base?.let { it - s.pitchDeg }
        val dropped = base != null && (drop!! >= rules.headDropDeg || abs(s.rollDeg - baseRoll) >= rules.headRollDeg)
        droppedSince = if (dropped) droppedSince ?: s.tS else null
        val droppedS = droppedSince?.let { s.tS - it } ?: 0.0
        if (closed || dropped) impairedAtS = s.tS

        if (eyes.isNotEmpty()) window.addLast(s.tS to closed)
        while (window.isNotEmpty() && s.tS - window.first().first > rules.perclosWindowS) window.removeFirst()
        val perclos = window.takeIf { it.size > 1 && it.last().first - it.first().first >= rules.perclosMinS }?.let { w -> w.count { it.second }.toDouble() / w.size }

        val (level, reason, confidence) = when {
            closedS >= rules.faintClosedS -> Triple(Alertness.UNRESPONSIVE, "eyes shut for ${"%.1f".format(closedS)} s", 0.9)
            droppedS >= rules.headDropS && closedS >= 0.5 -> Triple(Alertness.UNRESPONSIVE, "head dropped ${drop?.roundToInt() ?: 0}° with eyes shut", 0.9)
            droppedS >= rules.headDropS * 2 && eyes.isEmpty() -> Triple(Alertness.UNRESPONSIVE, "head dropped and eyes out of sight for ${"%.1f".format(droppedS)} s", 0.7)
            closedS >= rules.drowsyClosedS -> Triple(Alertness.DROWSY, "eyes shut for ${"%.1f".format(closedS)} s", 0.7)
            perclos != null && perclos >= rules.perclosDrowsy -> Triple(Alertness.DROWSY, "eyes shut ${(perclos * 100).roundToInt()}% of the last ${rules.perclosWindowS.roundToInt()} s", 0.6)
            basePitch == null -> Triple(Alertness.CALIBRATING, null, 0.0)
            else -> Triple(Alertness.AWAKE, null, 0.0)
        }
        reading = DriverReading(level, closedS, perclos, drop, reason)
        return escalate(level, s.tS, reason, confidence, awake = !closed && !dropped)
    }

    private fun lost(tS: Double): DriverAlarm? {
        val since = lostSince ?: tS.also { lostSince = it }
        closedSince = null; droppedSince = null
        val goneS = tS - since
        val afterImpaired = impairedAtS?.let { since - it <= 1.0 } == true || episode != null
        if (afterImpaired && goneS >= rules.lostAfterS) {
            val reason = "slumped out of view for ${"%.1f".format(goneS)} s after the eyes shut"
            reading = DriverReading(Alertness.UNRESPONSIVE, 0.0, null, null, reason)
            return escalate(Alertness.UNRESPONSIVE, tS, reason, 0.8, awake = false)
        }
        reading = DriverReading(episode ?: Alertness.NO_FACE, 0.0, null, null, reading.reason.takeIf { episode != null })
        awakeSince = null
        return null
    }

    private fun escalate(level: Alertness, tS: Double, reason: String?, confidence: Double, awake: Boolean): DriverAlarm? {
        if (level >= Alertness.DROWSY) {
            awakeSince = null
            if (episode == null || level > episode!!) { episode = level; return DriverAlarm(level, tS, reason!!, confidence) }
            return null
        }
        if (episode != null && awake) {
            val since = awakeSince ?: tS.also { awakeSince = it }
            // Recovered: the episode ends and the old closures leave the window, so the next one starts fresh.
            if (tS - since >= rules.recoverS) { episode = null; awakeSince = null; window.clear() }
        }
        return null
    }

    /** The median head pose over the first seconds of an awake face is this driver's "head up". */
    private fun calibrate(s: FaceSample) {
        calibration += s
        if (s.tS - calibration.first().tS < rules.calibrateS) return
        basePitch = calibration.map { it.pitchDeg }.sorted()[calibration.size / 2]
        baseRoll = calibration.map { it.rollDeg }.sorted()[calibration.size / 2]
        calibration.clear()
    }

    /** Whether an episode is in progress (the driver has not yet been awake again for [DriverRules.recoverS]). */
    val alarmed get() = episode != null
}
