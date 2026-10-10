package com.jsons.roadmesh.core.perception

import com.jsons.roadmesh.core.mesh.HazardKind
import org.junit.Assert.*
import org.junit.Test

class DashcamHazardsTest {
    private fun obj(cls: ObjectClass, cx: Float, y2: Float, h: Float, w: Float = h * 0.8f, score: Float = 0.8f) =
        SeenObject(cls, score, cx - w / 2, y2 - h, cx + w / 2, y2)

    @Test fun personInLaneForThreeFramesIsAPedestrianOnce() {
        val rules = DashcamHazards()
        val person = obj(ObjectClass.PERSON, 0.5f, 0.8f, 0.2f, 0.06f)
        assertNull(rules.update(0.0, listOf(person)))
        assertNull(rules.update(0.1, listOf(person)))
        assertEquals(HazardKind.PEDESTRIAN, rules.update(0.2, listOf(person))?.kind)
        assertNull("held off", rules.update(0.3, listOf(person)))
        assertEquals(HazardKind.PEDESTRIAN, rules.update(4.3, listOf(person))?.kind)
    }

    @Test fun riderOnAMotorcycleAndPeopleOnTheKerbAreNotPedestrians() {
        val rules = DashcamHazards()
        val bike = obj(ObjectClass.MOTORCYCLE, 0.5f, 0.85f, 0.18f, 0.08f)
        val rider = SeenObject(ObjectClass.PERSON, 0.8f, 0.47f, 0.6f, 0.53f, 0.8f)
        val kerb = obj(ObjectClass.PERSON, 0.95f, 0.8f, 0.25f, 0.06f)
        repeat(10) { assertNull(rules.update(it * 0.1, listOf(bike, rider, kerb))) }
    }

    @Test fun fastGrowingCarAheadIsBrakingAndASteadyOneIsNot() {
        val steady = DashcamHazards()
        repeat(20) { assertNull(steady.update(it * 0.1, listOf(obj(ObjectClass.CAR, 0.5f, 0.8f, 0.2f)))) }
        val closing = DashcamHazards()
        // Height grows 1.0 per second relative: time to contact about 1 s.
        val calls = (0 until 10).mapNotNull { i -> val h = 0.08f * kotlin.math.exp(i * 0.1f); closing.update(i * 0.1, listOf(obj(ObjectClass.CAR, 0.5f, 0.6f + h, h))) }
        assertEquals(HazardKind.BRAKING, calls.single().kind)
        assertEquals(1.0, closing.lead!!.ttcS!!, 0.1)
    }

    @Test fun carCuttingInAFewMetresAheadIsVehicleClose() {
        val rules = DashcamHazards()
        val call = rules.update(0.0, listOf(SeenObject(ObjectClass.CAR, 0.8f, 0.42f, 0.4f, 0.78f, 0.8f)))
        assertEquals(HazardKind.VEHICLE_CLOSE, call?.kind)
        assertEquals(3.2, call!!.distanceM, 0.1)
    }

    @Test fun carInTheNextLaneIsNotTheLead() {
        val rules = DashcamHazards()
        (0 until 10).forEach { i -> val h = 0.2f * kotlin.math.exp(i * 0.1f); assertNull(rules.update(i * 0.1, listOf(obj(ObjectClass.CAR, 0.9f, 0.6f + h, h)))) }
        assertNull(rules.lead)
    }

    /** Replays YOLO11n detections of the two CC0 Bangalore clips (tools/fetch_dashcam.py) through the rules. */
    @Test fun realBangaloreFootageRaisesFewCalls() {
        for ((clip, seconds) in listOf("cv_raman_road" to 48.4, "new_bel_road" to 96.2)) {
            val frames = javaClass.getResourceAsStream("/dashcam/$clip.csv")!!.bufferedReader().readLines().drop(1)
                .map { it.split(',') }
                .groupBy({ it[0].toDouble() }, { SeenObject(ObjectClass.coco(it[1].toInt()), it[2].toFloat(), it[3].toFloat(), it[4].toFloat(), it[5].toFloat(), it[6].toFloat()) })
            val times = (0 until (seconds * 10).toInt()).map { it / 10.0 }
            val rules = DashcamHazards()
            val calls = times.mapNotNull { t -> rules.update(t, frames.entries.firstOrNull { kotlin.math.abs(it.key - t) < 0.05 }?.value.orEmpty()) }
            calls.forEach { println("$clip ${"%.1f".format(it.atS)}s ${it.kind} ${it.reason}") }
            assertTrue("$clip: ${calls.size} calls", calls.size <= seconds / 4)
            if (clip == "cv_raman_road") {
                // Two overtaking cars cut in a few metres ahead, at about 8 s and 33 s (checked frame by frame).
                val close = calls.filter { it.kind == HazardKind.VEHICLE_CLOSE }.map { it.atS }
                assertTrue("$close", close.any { it in 7.5..10.0 } && close.any { it in 32.5..34.0 })
            }
        }
    }
}

class ObjectTrackerTest {
    @Test fun keepsIdsAndMeasuresGroundPointVelocity() {
        val tracker = ObjectTracker(smoothing = 1f)
        fun car(x: Float, y2: Float) = SeenObject(ObjectClass.CAR, 0.9f, x - 0.05f, y2 - 0.1f, x + 0.05f, y2)
        val first = tracker.update(0.0, listOf(car(0.5f, 0.7f))).single()
        val second = tracker.update(0.1, listOf(car(0.51f, 0.71f), car(0.9f, 0.7f)))
        val same = second.first { it.subject.cx == 0.51f }
        assertEquals(first.id, same.id)
        assertEquals(0.1f, same.vx, 1e-3f); assertEquals(0.1f, same.vy, 1e-3f)
        assertNotEquals(first.id, second.first { it.subject.cx == 0.9f }.id)
    }
}
