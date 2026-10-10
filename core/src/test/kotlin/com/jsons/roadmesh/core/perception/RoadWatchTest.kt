package com.jsons.roadmesh.core.perception

import com.jsons.roadmesh.core.mesh.HazardKind
import org.junit.Assert.*
import org.junit.Test

class RoadWatchTest {
    private fun o(cls: ObjectClass, x1: Float, y1: Float, x2: Float, y2: Float, score: Float = 0.8f, light: LightColour? = null) =
        SeenObject(cls, score, x1, y1, x2, y2, light)
    private fun frames(watch: RoadWatch, n: Int, speed: Double? = null, at: (Int) -> List<SeenObject>) =
        (0 until n).flatMap { i -> watch.update(i * 0.1, at(i), speed) }

    @Test fun potholeOnTheRoadIsRaisedOnceWithAGroundDistance() {
        val w = RoadWatch()
        val found = frames(w, 6) { listOf(o(ObjectClass.POTHOLE, 0.45f, 0.62f, 0.55f, 0.66f)) }
        val p = found.single()
        assertEquals(HazardKind.POTHOLE, p.kind)
        // 0.65 * 16/9 * 1.3 / (0.66 - 0.45) = 7.2 m
        assertEquals(7.2, p.distanceM!!, 0.2)
    }

    @Test fun aSinglePotholeFlickerIsIgnoredAndOneAboveTheHorizonNeverCounts() {
        assertTrue(frames(RoadWatch(), 6) { i -> if (i == 2) listOf(o(ObjectClass.POTHOLE, 0.45f, 0.62f, 0.55f, 0.66f)) else emptyList() }.isEmpty())
        assertTrue(frames(RoadWatch(), 6) { listOf(o(ObjectClass.POTHOLE, 0.45f, 0.30f, 0.55f, 0.40f)) }.isEmpty())
    }

    @Test fun tripleRidingNeedsThreeRidersOnOneBike() {
        val bike = o(ObjectClass.MOTORCYCLE, 0.40f, 0.50f, 0.60f, 0.80f)
        fun rider(x: Float) = o(ObjectClass.PERSON, x - 0.03f, 0.35f, x + 0.03f, 0.72f)
        assertEquals(HazardKind.TRIPLE_RIDING, frames(RoadWatch(), 5) { listOf(bike, rider(0.45f), rider(0.50f), rider(0.55f)) }.single().kind)
        assertTrue(frames(RoadWatch(), 5) { listOf(bike, rider(0.45f), rider(0.55f)) }.isEmpty())
    }

    @Test fun bareHeadAboveARiderIsAHelmetViolationButAHelmetIsNot() {
        val bike = o(ObjectClass.MOTORCYCLE, 0.40f, 0.50f, 0.60f, 0.80f)
        val head = o(ObjectClass.NO_HELMET, 0.47f, 0.30f, 0.53f, 0.38f, 0.5f)
        assertEquals(HazardKind.NO_HELMET, frames(RoadWatch(), 5) { listOf(bike, head) }.single().kind)
        assertTrue(frames(RoadWatch(), 5) { listOf(bike, head.copy(cls = ObjectClass.HELMET)) }.isEmpty())
    }

    @Test fun wrongSideVehicleClosesFasterThanWeDrive() {
        // A car centred in our lane whose distance falls 20 m/s while we drive 8 m/s: it is coming toward us.
        fun car(d: Double): SeenObject { val w = (0.65 * 1.75 / d).toFloat(); return o(ObjectClass.CAR, 0.5f - w / 2, 0.6f - w * 0.8f, 0.5f + w / 2, 0.6f) }
        val oncoming = frames(RoadWatch(), 8, speed = 8.0) { i -> listOf(car(35.0 - i * 2.0)) }
        assertEquals(HazardKind.WRONG_WAY, oncoming.single().kind)
        // Same car closing at 6 m/s while we drive 8 m/s is just slower traffic.
        assertTrue(frames(RoadWatch(), 8, speed = 8.0) { i -> listOf(car(35.0 - i * 0.6)) }.isEmpty())
        assertTrue("no own speed, no verdict", frames(RoadWatch(), 8) { i -> listOf(car(35.0 - i * 2.0)) }.isEmpty())
    }

    @Test fun vehicleMovingOnThroughARedLight() {
        fun car(d: Double): SeenObject { val w = (0.65 * 1.75 / d).toFloat(); return o(ObjectClass.CAR, 0.5f - w / 2, 0.6f - w * 0.8f, 0.5f + w / 2, 0.6f) }
        val red = o(ObjectClass.TRAFFIC_LIGHT, 0.7f, 0.1f, 0.72f, 0.16f, light = LightColour.RED) // about 17 m
        val found = frames(RoadWatch(), 8, speed = 0.0) { i -> listOf(red, car(10.0 + i * 0.6)) }
        assertEquals(HazardKind.RED_LIGHT, found.single().kind)
        assertTrue(frames(RoadWatch(), 8, speed = 0.0) { i -> listOf(red.copy(light = LightColour.GREEN), car(10.0 + i * 0.6)) }.isEmpty())
    }

    @Test fun personLyingOnTheRoadIsAnAccident() {
        val lying = o(ObjectClass.PERSON, 0.40f, 0.62f, 0.60f, 0.70f, 0.7f)
        assertEquals(HazardKind.ACCIDENT, frames(RoadWatch(), 6) { listOf(lying) }.single().kind)
        val standing = o(ObjectClass.PERSON, 0.48f, 0.40f, 0.52f, 0.70f)
        assertTrue(frames(RoadWatch(), 6) { listOf(standing) }.isEmpty())
    }

    @Test fun lightColourReadsTheLitLamp() {
        fun px(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
        val dark = IntArray(60) { px(30, 30, 30) }
        assertNull(lightColour(dark))
        assertEquals(LightColour.RED, lightColour(dark + IntArray(12) { px(240, 40, 30) }))
        assertEquals(LightColour.GREEN, lightColour(dark + IntArray(12) { px(40, 230, 120) }))
        assertEquals(LightColour.AMBER, lightColour(dark + IntArray(12) { px(250, 170, 20) }))
    }
}
