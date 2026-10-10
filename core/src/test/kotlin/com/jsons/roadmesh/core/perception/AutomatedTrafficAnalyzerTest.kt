package com.jsons.roadmesh.core.perception

import com.jsons.roadmesh.core.mesh.HazardKind
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.exp

class AutomatedTrafficAnalyzerTest {
    private fun car(cx: Float = 0.5f, scale: Double = 1.0, cls: ObjectClass = ObjectClass.CAR): SeenObject {
        val w = (0.12 * scale).toFloat(); val h = (0.10 * scale).toFloat()
        return SeenObject(cls, 0.9f, cx - w / 2, 0.72f - h, cx + w / 2, 0.72f)
    }
    private fun ego(t: Double, speed: Double, accuracy: Double? = 0.1) = EgoMotion(t, speed, accuracy, 0.0, 2.0)
    private fun evaluate(frames: Int = 18, objects: (Int) -> List<SeenObject>, motion: (Int) -> EgoMotion? = { null }): List<AutomaticTrafficEvent> {
        val analyzer = AutomatedTrafficAnalyzer()
        return (0 until frames).flatMap { i -> analyzer.update(i * 0.1, objects(i), motion(i)).events }
    }

    @Test fun syntheticScenariosHaveExplicitPositiveAndNegativeOutcomes() {
        val results = AutomationScenario.runAll()
        assertEquals(7, results.size)
        results.forEach { assertTrue("${it.id}: expected ${it.expectedKinds}, observed ${it.observedKinds}", it.passed) }
        File("build/reports").mkdirs()
        File("build/reports/automation-scenarios.json").writeText(AutomationScenario.reportJson())
    }

    @Test fun tracksAreLocalStableAndBounded() {
        val analyzer = AutomatedTrafficAnalyzer(AutomatedTrafficConfig(maxTracks = 4))
        val first = analyzer.update(0.0, listOf(car(0.4f), car(0.6f)))
        val next = analyzer.update(0.1, listOf(car(0.6f), car(0.4f)))
        assertEquals(first.tracks.associate { it.subject.cx to it.id }, next.tracks.associate { it.subject.cx to it.id })
        repeat(100) { i ->
            val boxes = (0..9).map { n -> car(0.07f + n * 0.09f, 0.25) }
            assertTrue(analyzer.update(0.2 + i * 0.1, boxes).tracks.size <= 4)
        }
    }

    @Test fun detectorDuplicatesDoNotBecomeMultipleTrafficWitnesses() {
        val events = evaluate(objects = { listOf(car(), car().copy(score = 0.8f)) }, motion = { i -> ego(i * 0.1, 12.0 - i * 0.25) })
        assertTrue(events.none { it.kind == HazardKind.SLOWDOWN })
        val competingLabels = evaluate(objects = { listOf(car(), car(cls = ObjectClass.TRUCK).copy(score = 0.8f)) },
            motion = { i -> ego(i * 0.1, 12.0 - i * 0.25) })
        assertTrue(competingLabels.none { it.kind == HazardKind.SLOWDOWN })
    }

    @Test fun closeObjectNeedsPersistenceNotOneFrame() {
        val analyzer = AutomatedTrafficAnalyzer()
        assertTrue(analyzer.update(0.0, listOf(car(scale = 2.0))).events.isEmpty())
        val events = (1..10).flatMap { analyzer.update(it * 0.1, listOf(car(scale = 2.0))).events }
        assertEquals(listOf(HazardKind.VEHICLE_CLOSE), events.map { it.kind })
        assertTrue(events.single().estimatedRangeM!! < 6)
    }

    @Test fun rapidApproachNeverClaimsOtherVehicleIsBraking() {
        val events = evaluate(objects = { i -> listOf(car(scale = exp(i * 0.07))) })
        assertEquals(listOf(HazardKind.VEHICLE_CLOSE), events.map { it.kind })
        assertEquals(1.0 / 0.7, events.single().estimatedTtcS!!, 0.03)
        assertTrue(events.single().reason.contains("cause unknown"))
    }

    @Test fun repeatedGnssSampleCannotManufactureSpeedHistory() {
        val events = evaluate(objects = { listOf(car(0.4f), car(0.6f)) }, motion = { i -> ego(0.0, 12.0 - i * 0.5) })
        assertTrue(events.isEmpty())
    }

    @Test fun oneBadSpeedSampleDoesNotBecomeHardBraking() {
        val events = evaluate(objects = { listOf(car(0.4f), car(0.6f)) }, motion = { i ->
            ego(i * 0.1, if (i == 12) 4.0 else 12.0)
        })
        assertTrue(events.none { it.kind == HazardKind.BRAKING || it.kind == HazardKind.SLOWDOWN })
    }

    @Test fun oneHertzGnssMeasurementsCanCorroborateCameraTracks() {
        val events = evaluate(frames = 26, objects = { listOf(car(0.4f), car(0.6f)) }, motion = { i ->
            val sampleAt = (i / 10).toDouble()
            ego(sampleAt, 12.0 - sampleAt * 2.5)
        })
        assertEquals(listOf(HazardKind.SLOWDOWN), events.map { it.kind })
    }

    @Test fun missingInaccurateAndStaleSpeedSuppressDecelerationClaims() {
        for (accuracy in listOf(null, 4.0, Double.NaN)) {
            val events = evaluate(objects = { listOf(car(0.4f), car(0.6f)) }, motion = { i -> ego(i * 0.1, 15.0 - i * 0.5, accuracy) })
            assertTrue(events.isEmpty())
        }
        val stale = evaluate(objects = { listOf(car(0.4f), car(0.6f)) }, motion = { i -> ego(i * 0.1 - 2.0, 15.0 - i * 0.5) })
        assertTrue(stale.isEmpty())
    }

    @Test fun speedUncertaintyIsSubtractedBeforeHardBrakingDecision() {
        // 5m/s apparent speed drop with +/-1m/s at both ends is below the 3.5m/s² hard-braking threshold.
        val events = evaluate(frames = 11, objects = { listOf(car()) }, motion = { i -> ego(i * 0.1, 15.0 - i * 0.5, 1.0) })
        assertTrue(events.none { it.kind == HazardKind.BRAKING })
    }

    @Test fun oneTrackedVehicleDoesNotCountAsCollectiveSlowdown() {
        val events = evaluate(objects = { listOf(car()) }, motion = { i -> ego(i * 0.1, 12.0 - i * 0.25) })
        assertTrue(events.none { it.kind == HazardKind.SLOWDOWN })
    }

    @Test fun brakingRefersToMeasuredHostAndUsesHostLocation() {
        val event = AutomationScenario.run("host_hard_braking").events.single()
        assertEquals(HazardKind.BRAKING, event.kind)
        assertNull(event.estimatedRangeM)
        assertTrue(event.reason.startsWith("This vehicle"))
        assertTrue(event.evidence.any { it.contains("target braking is not directly measured") })
    }

    @Test fun turningAndMountMovementPauseTemporalClaims() {
        val events = evaluate(objects = { i -> listOf(car(scale = exp(i * 0.07))) }, motion = { i -> ego(i * 0.1, 12.0).copy(cameraStable = false) })
        assertTrue(events.isEmpty())
        assertTrue(AutomationScenario.run("turning_camera").events.isEmpty())
    }

    @Test fun clockResetAndLongFrameGapRequireNewEvidence() {
        val analyzer = AutomatedTrafficAnalyzer()
        (0..8).forEach { analyzer.update(it * 0.1, listOf(car(scale = 2.0))) }
        val old = analyzer.update(0.9, listOf(car(scale = 2.0))).tracks.single().id
        val reset = analyzer.update(0.1, listOf(car(scale = 2.0)))
        assertTrue(reset.events.isEmpty())
        assertNotEquals(old, reset.tracks.single().id)
        val gap = analyzer.update(5.0, listOf(car(scale = 2.0)))
        assertTrue(gap.events.isEmpty())
        assertEquals(0.0, gap.tracks.single().ageS, 0.0)
    }

    @Test fun duplicateCameraTimestampsCannotCreatePersistence() {
        val analyzer = AutomatedTrafficAnalyzer()
        repeat(30) { assertTrue(analyzer.update(0.0, listOf(car(scale = 2.0))).events.isEmpty()) }
    }

    @Test fun invalidBoxesAndCapacityAreUnknownNotEvents() {
        val analyzer = AutomatedTrafficAnalyzer()
        val invalid = listOf(car().copy(x1 = Float.NaN), car().copy(x2 = -1f), car().copy(score = 5f))
        assertTrue(analyzer.update(0.0, invalid).tracks.isEmpty())
        val tooMany = analyzer.update(0.1, List(257) { car() })
        assertTrue(tooMany.events.isEmpty())
        assertTrue(tooMany.status.contains("capacity"))
        assertTrue(analyzer.update(Double.NaN, listOf(car())).tracks.isEmpty())
    }

    @Test fun noDetectionsAndLostTracksNeverAssertRoadIsClear() {
        val analyzer = AutomatedTrafficAnalyzer()
        val firstId = analyzer.update(0.0, listOf(car())).tracks.single().id
        (1..10).forEach { i ->
            val frame = analyzer.update(i * 0.1, emptyList())
            assertTrue(frame.events.isEmpty())
            assertTrue(frame.status.startsWith("Unknown"))
        }
        assertNotEquals(firstId, analyzer.update(1.1, listOf(car())).tracks.single().id)
    }

    @Test fun repeatedEvidenceIsRateLimited() {
        val events = evaluate(frames = 70, objects = { listOf(car(scale = 2.0)) })
        assertEquals(1, events.count { it.kind == HazardKind.VEHICLE_CLOSE })
    }

    @Test fun kerbObjectAndSidewaysCutAcrossDoNotBecomeClosingTraffic() {
        assertTrue(evaluate(objects = { listOf(car(0.92f)) }).isEmpty())
        val crossing = evaluate(frames = 10, objects = { i -> listOf(car(0.32f + i * 0.04f, exp(i * 0.08))) })
        assertTrue(crossing.none { it.kind == HazardKind.VEHICLE_CLOSE })
    }

    @Test fun pedestrianOnFootIsAutomaticButMotorcycleRiderIsExcluded() {
        val person = SeenObject(ObjectClass.PERSON, 0.9f, 0.47f, 0.5f, 0.53f, 0.75f)
        val pedestrian = evaluate(objects = { listOf(person) })
        assertEquals(listOf(HazardKind.PEDESTRIAN), pedestrian.map { it.kind })
        val motorcycle = SeenObject(ObjectClass.MOTORCYCLE, 0.9f, 0.45f, 0.65f, 0.55f, 0.80f)
        assertTrue(evaluate(objects = { listOf(person, motorcycle) }).none { it.kind == HazardKind.PEDESTRIAN })
    }

    @Test fun repeatedRoadModelObstacleHasUnknownDimensions() {
        val pothole = SeenObject(ObjectClass.POTHOLE, 0.8f, 0.40f, 0.65f, 0.60f, 0.75f)
        val event = evaluate(objects = { listOf(pothole) }).single()
        assertEquals(HazardKind.POTHOLE, event.kind)
        assertNull(event.estimatedRangeM)
        assertNull(event.estimatedTtcS)
    }

    @Test fun matchingKeepsMixedTrafficClassesSeparate() {
        val analyzer = AutomatedTrafficAnalyzer()
        val frame = analyzer.update(0.0, listOf(car(0.4f), car(0.6f, cls = ObjectClass.MOTORCYCLE)))
        assertEquals(2, frame.tracks.size)
        assertEquals(2, frame.monitoredVehicles)
        val next = analyzer.update(0.1, listOf(car(0.4f), car(0.6f, cls = ObjectClass.MOTORCYCLE)))
        assertEquals(frame.tracks.map { it.id }, next.tracks.map { it.id })
    }
}
