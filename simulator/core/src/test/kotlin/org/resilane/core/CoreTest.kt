package org.resilane.core

import org.resilane.core.control.HeadingControl
import org.resilane.core.frame.SharedFrame
import org.resilane.core.math.Angles
import org.resilane.core.math.Pose
import org.resilane.core.math.Quat
import org.resilane.core.math.Vec3
import org.resilane.core.proto.Bye
import org.resilane.core.proto.Codec
import org.resilane.core.proto.DedupeWindow
import org.resilane.core.proto.Envelope
import org.resilane.core.proto.Hello
import org.resilane.core.proto.Ping
import org.resilane.core.proto.PoseMsg
import org.resilane.core.proto.Pong
import org.resilane.core.proto.ScaleSync
import org.resilane.core.proto.Tracking
import org.resilane.core.sync.ClockSync
import org.resilane.core.sync.NodeRegistry
import org.resilane.core.sync.NodeState
import org.resilane.core.sync.PoseInterpolator
import org.resilane.core.sync.RenderMode
import org.resilane.core.sync.TimedPose
import org.resilane.core.sync.VehicleOffset
import org.resilane.core.sync.VehiclePlacement
import org.resilane.core.vision.Box
import org.resilane.core.vision.Coco
import org.resilane.core.vision.Detection
import org.resilane.core.vision.GroundTruth
import org.resilane.core.vision.Letterbox
import org.resilane.core.vision.Matcher
import org.resilane.core.vision.Projection
import org.resilane.core.vision.YoloDecoder
import kotlin.math.abs
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun near(a: Float, b: Float, eps: Float = 1e-3f, msg: String = "") =
    assertTrue(abs(a - b) <= eps, "$msg expected $b got $a")
private fun near(a: Vec3, b: Vec3, eps: Float = 1e-3f, msg: String = "") =
    assertTrue(a.distanceTo(b) <= eps, "$msg expected $b got $a")

class GeometryTest {
    @Test fun composeAndInverse() {
        val a = Pose(Vec3(1f, 2f, 3f), Quat.axisAngle(Vec3(0.3f, 1f, -0.2f), 0.9f))
        val b = Pose(Vec3(-2f, 0.5f, 4f), Quat.axisAngle(Vec3(1f, 0f, 0.4f), -1.3f))
        val p = Vec3(0.7f, -1.1f, 2.2f)
        near(a.compose(b).transformPoint(p), a.transformPoint(b.transformPoint(p)))
        near(a.inverse().transformPoint(a.transformPoint(p)), p)
    }

    @Test fun yawConventions() {
        // rotY(yaw) maps +Z to (sin, 0, cos); yawOf inverts it.
        for (deg in listOf(-170, -90, 0, 30, 90, 179)) {
            val yaw = Math.toRadians(deg.toDouble()).toFloat()
            val f = Quat.rotY(yaw).rotate(Vec3.Z)
            near(Angles.yawOf(f), yaw, 1e-4f)
        }
        // Heading: facing world -Z (marker TOP) is 0, facing +X is 90 (clockwise from above).
        near(Angles.headingDegOfYaw(Angles.yawOf(Vec3(0f, 0f, -1f))), 0f, 1e-3f)
        near(Angles.headingDegOfYaw(Angles.yawOf(Vec3(1f, 0f, 0f))), 90f, 1e-3f)
        near(Angles.headingDegOfYaw(Angles.yawOf(Vec3(0f, 0f, 1f))), 180f, 1e-3f)
        near(Angles.headingDegOfYaw(Angles.yawOf(Vec3(-1f, 0f, 0f))), 270f, 1e-3f)
    }
}

/**
 * The property the whole project rests on: two phones with different, arbitrary session frames
 * that both observe the same marker must agree on where a third thing is in the shared world.
 */
class SharedFrameTest {
    private fun session(seed: Int): Pose {
        // Arbitrary per-phone session origin; ARCore guarantees only gravity (Y) alignment.
        val r = Random(seed)
        return Pose(Vec3(r.nextFloat() * 10 - 5, r.nextFloat() - 0.5f, r.nextFloat() * 10 - 5), Quat.rotY(r.nextFloat() * 6.28f))
    }

    @Test fun twoPhonesAgreeOnSharedCoordinates() {
        // Ground truth in a "lab" frame: the marker lies flat at lab origin, rotated 37 degrees.
        val labFromMarker = Pose(Vec3(0f, 0f, 0f), Quat.rotY(Math.toRadians(37.0).toFloat()) * Quat.IDENTITY)
        val phoneB = Pose(Vec3(2.5f, 1.45f, -3.0f), Quat.rotY(1.1f) * Quat.axisAngle(Vec3.X, -0.2f))

        val estimates = listOf(11, 22).map { seed ->
            val labFromSession = session(seed)
            val sessionFromLab = labFromSession.inverse()
            val samples = (0 until 30).map { i ->
                val jitter = Pose(Vec3((i % 3 - 1) * 0.002f, 0f, (i % 5 - 2) * 0.001f), Quat.IDENTITY)
                SharedFrame.MarkerSample(sessionFromLab.compose(labFromMarker).compose(jitter), i * 33L)
            }
            val est = assertNotNull(SharedFrame.estimate(samples))
            assertTrue(SharedFrame.Gate().accepts(est), SharedFrame.Gate().whyNot(est))
            Triple(sessionFromLab, est, labFromSession)
        }
        // Each phone expresses phone B's pose in the shared world via its own session frame.
        val worldPositions = estimates.map { (sessionFromLab, est, _) ->
            val sessionFromB = sessionFromLab.compose(phoneB)
            est.sessionFromWorld.inverse().compose(sessionFromB).t
        }
        near(worldPositions[0], worldPositions[1], 0.01f, "phones disagree")
        // Height above the marker plane is preserved.
        near(worldPositions[0].y, 1.45f, 0.01f)
        // And the yaw agrees too.
        val yaws = estimates.map { (sessionFromLab, est, _) ->
            Angles.yawOf(est.sessionFromWorld.inverse().compose(sessionFromLab.compose(phoneB)).cameraForward())
        }
        near(Angles.wrapPi(yaws[0] - yaws[1]), 0f, 0.01f)
    }

    @Test fun rawSessionCoordinatesWouldDisagree() {
        // Sanity check of the test itself: without the shared frame the phones do not agree.
        val p = Vec3(1f, 1f, 1f)
        val a = session(11).inverse().transformPoint(p)
        val b = session(22).inverse().transformPoint(p)
        assertTrue(a.distanceTo(b) > 0.5f)
    }

    @Test fun tiltedMarkerKeepsGravityUp() {
        val tilted = Pose(Vec3(0f, 0.8f, 0f), Quat.rotY(0.4f) * Quat.axisAngle(Vec3.X, 0.25f))
        val est = assertNotNull(SharedFrame.estimate(List(25) { SharedFrame.MarkerSample(tilted, it * 40L) }, markerHeightM = 0.8f))
        near(est.sessionFromWorld.rotateVector(Vec3.Y), Vec3.Y, 1e-4f)
        near(est.sessionFromWorld.t.y, 0f, 1e-4f)
        assertTrue(est.tiltDeg in 13f..16f)
    }

    @Test fun gateRejectsShakySamples() {
        val r = Random(3)
        val shaky = List(30) {
            SharedFrame.MarkerSample(Pose(Vec3(r.nextFloat() * 0.1f, 0f, 0f), Quat.rotY(r.nextFloat() * 0.2f)), it * 40L)
        }
        assertFalse(SharedFrame.Gate().accepts(SharedFrame.estimate(shaky)!!))
    }

    @Test fun residualMeasuresDrift() {
        val world = Pose(Vec3(1f, 0f, 2f), Quat.rotY(0.5f))
        val drifted = Pose(Vec3(1.05f, 0f, 2f), Quat.rotY(0.5f + Math.toRadians(3.0).toFloat()))
        val (d, yaw) = SharedFrame.residual(world, drifted)!!
        near(d, 0.05f, 1e-3f); near(yaw, 3f, 0.05f)
    }
}

class ProtocolTest {
    @Test fun roundTripAll() {
        val msgs = listOf(
            Hello("Phone Ä", 0, 0xFF3366CC.toInt(), 0.5f, 180f, "0.1.0"),
            PoseMsg(42, 1_760_000_000_123L, Tracking.PAUSED, true, 3, Vec3(1f, 1.5f, -2f), Quat(0.1f, 0.2f, 0.3f, 0.9f), 1.25f),
            ScaleSync(1.75f, 99L), Ping(5L), Pong(7L, 5L, 9L), Bye,
        )
        for ((i, m) in msgs.withIndex()) {
            val e = Envelope(0x1234_5678_9ABC_DEF0L, i, 1, m)
            assertEquals(e, Codec.decode(Codec.encode(e)))
        }
    }

    @Test fun rejectsGarbage() {
        assertNull(Codec.decode(ByteArray(0)))
        assertNull(Codec.decode(ByteArray(40) { 7 }))
        val ok = Codec.encode(Envelope(1, 1, 1, PoseMsg(1, 1, Tracking.TRACKING, true, 1, Vec3.ZERO, Quat.IDENTITY, 0f)))
        assertNull(Codec.decode(ok.copyOf(ok.size - 3)))
    }

    @Test fun poseFitsInOneSmallDatagram() {
        val n = Codec.encode(Envelope(1, 1, 1, PoseMsg(1, 1, Tracking.TRACKING, true, 1, Vec3.ZERO, Quat.IDENTITY, 0f))).size
        assertTrue(n <= 80, "pose message is $n bytes")
    }

    @Test fun dedupeAcrossTransportsAndReorder() {
        val d = DedupeWindow()
        assertTrue(d.firstSeen(1, 10)); assertFalse(d.firstSeen(1, 10))
        assertTrue(d.firstSeen(1, 12)); assertTrue(d.firstSeen(1, 11)); assertFalse(d.firstSeen(1, 11))
        assertTrue(d.firstSeen(2, 10))
        assertTrue(d.firstSeen(1, 200)); assertFalse(d.firstSeen(1, 200))
        assertFalse(d.firstSeen(1, 1)) // old messages cannot reset a session
        assertFalse(d.firstSeen(1, 200))
        assertTrue(d.firstSeen(1, 1, sessionId = 42))
        assertFalse(d.firstSeen(1, 200, sessionId = 0)) // retired session
    }
}

class SyncTest {
    private fun tp(t: Long, seq: Int, x: Float, yaw: Float = 0f, tr: Tracking = Tracking.TRACKING) =
        TimedPose(t, seq, Pose(Vec3(x, 0f, 0f), Quat.rotY(yaw)), yaw, tr)

    @Test fun interpolatesBetweenAndNeverExtrapolates() {
        val ip = PoseInterpolator()
        ip.add(tp(1000, 1, 0f), 1000); ip.add(tp(1100, 2, 1f), 1100)
        near(ip.sample(1050)!!.pose.t.x, 0.5f)
        val held = ip.sample(5000)!!
        assertTrue(held.holding); near(held.pose.t.x, 1f)
        near(ip.sample(0)!!.pose.t.x, 0f)
    }

    @Test fun outOfOrderAndDuplicates() {
        val ip = PoseInterpolator()
        ip.add(tp(1000, 1, 0f), 1000); ip.add(tp(1200, 3, 2f), 1150); ip.add(tp(1100, 2, 1f), 1160); ip.add(tp(1100, 2, 9f), 1170)
        near(ip.sample(1150)!!.pose.t.x, 1.5f)
    }

    @Test fun yawTakesShortWayAround() {
        val ip = PoseInterpolator()
        ip.add(tp(0, 1, 0f, 3.0f), 0); ip.add(tp(100, 2, 0f, -3.0f), 100)
        assertTrue(abs(ip.sample(50)!!.yaw) > 3.0f)
    }

    @Test fun degradedSamplesAreHeldNotBlended() {
        val ip = PoseInterpolator()
        ip.add(tp(0, 1, 0f), 0); ip.add(tp(100, 2, 5f, tr = Tracking.PAUSED), 100)
        val s = ip.sample(20)!!
        near(s.pose.t.x, 0f)
    }

    @Test fun clockOffsetFromPingPong() {
        val c = ClockSync()
        // Peer clock is 500 ms ahead; one-way delay 20 ms; a slow (queued) sample is ignored.
        c.onPong(t0 = 1000, t1Peer = 1520, t3 = 1040)
        c.onPong(t0 = 2000, t1Peer = 2700, t3 = 2400)
        assertEquals(500L, c.offsetMs)
    }

    @Test fun nodeStatesFollowAgeAndTracking() {
        val reg = NodeRegistry(selfId = 1)
        assertNull(reg.getOrCreate(1))
        val n = reg.getOrCreate(2)!!
        fun pose(seq: Int, tr: Tracking = Tracking.TRACKING, cal: Boolean = true) =
            PoseMsg(seq, 0, tr, cal, 1, Vec3.ZERO, Quat.IDENTITY, 0f)
        n.onPose(pose(1), 1000, "test")
        assertEquals(NodeState.LIVE, n.state(1100))
        assertEquals(NodeState.DEGRADED, n.state(2000))
        assertEquals(NodeState.LOST, n.state(7000))
        n.onPose(pose(2, Tracking.PAUSED), 7000, "test")
        assertEquals(NodeState.DEGRADED, n.state(7010))
        n.onPose(pose(5, cal = false), 7100, "test")
        assertEquals(NodeState.UNCALIBRATED, n.state(7110))
        assertEquals(2L, n.seqGaps)
    }

    @Test fun groundLockedPlacementIgnoresPhoneHeightAndTilt() {
        val phone = Pose(Vec3(2f, 1.6f, -1f), Quat.rotY(0.7f) * Quat.axisAngle(Vec3.X, -0.4f))
        val yaw = Angles.yawOf(phone.cameraForward())
        val car = VehiclePlacement.place(phone, yaw, RenderMode.GROUND_LOCKED, VehicleOffset())
        near(car.t, Vec3(2f, 0f, -1f))
        // Car forward (+Z) points where the camera looks, horizontally.
        val f = car.rotateVector(Vec3.Z)
        near(f.y, 0f, 1e-5f)
        near(Angles.yawOf(f), yaw, 1e-4f)
    }

    @Test fun offsetIsInTheCarFrame() {
        val phone = Pose(Vec3(0f, 1.5f, 0f), Quat.rotY(Angles.PI / 2)) // camera looks toward -X
        val yaw = Angles.yawOf(phone.cameraForward())
        val car = VehiclePlacement.place(phone, yaw, RenderMode.GROUND_LOCKED, VehicleOffset(forward = 1f, right = 0.5f))
        // Facing -X with +Y up, the driver's right is forward x up = -Z.
        val fwd = Vec3(-1f, 0f, 0f)
        val right = fwd.cross(Vec3.Y)
        near(car.t, fwd * 1f + right * 0.5f)
    }

    @Test fun free3dCarFacesCameraForward() {
        val phone = Pose(Vec3(0f, 1.2f, 0f), Quat.rotY(0.3f) * Quat.axisAngle(Vec3.X, 0.5f))
        val car = VehiclePlacement.place(phone, 0f, RenderMode.FREE_3D, VehicleOffset())
        near(car.rotateVector(Vec3.Z), phone.cameraForward(), 1e-4f)
        near(car.t, phone.t)
    }
}

class VisionTest {
    @Test fun letterboxRoundTrip() {
        val lb = Letterbox.fit(1080, 2400, 320, 640)
        val src = Box(100f, 200f, 500f, 900f)
        val net = Box(src.x1 * lb.scale + lb.padX, src.y1 * lb.scale + lb.padY, src.x2 * lb.scale + lb.padX, src.y2 * lb.scale + lb.padY)
        val back = lb.toSource(net)
        near(back.x1, 100f, 0.01f); near(back.y2, 900f, 0.01f)
    }

    @Test fun decodesYoloHeadAndSuppresses() {
        val n = 3; val nc = 80
        val out = FloatArray((4 + nc) * n)
        fun put(i: Int, cx: Float, cy: Float, w: Float, h: Float, cls: Int, s: Float) {
            out[i] = cx; out[n + i] = cy; out[2 * n + i] = w; out[3 * n + i] = h; out[(4 + cls) * n + i] = s
        }
        put(0, 100f, 100f, 50f, 40f, Coco.CAR, 0.9f)
        put(1, 102f, 101f, 50f, 40f, Coco.CAR, 0.7f) // duplicate, suppressed
        put(2, 250f, 300f, 30f, 30f, 0, 0.1f)       // below threshold
        val d = YoloDecoder.decode(out, nc, n, Letterbox(1f, 0f, 0f, 320, 640))
        assertEquals(1, d.size); assertEquals(Coco.CAR, d[0].cls); near(d[0].box.x1, 75f)
    }

    @Test fun projectionMatchesPinhole() {
        // Camera at origin looking -Z, 90 deg vertical FOV, square 100x100 viewport.
        val f = 1f / tan(Math.toRadians(45.0)).toFloat()
        val near = 0.1f; val far = 100f
        val proj = floatArrayOf(f, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, (far + near) / (near - far), -1f, 0f, 0f, 2 * far * near / (near - far), 0f)
        val model = Pose(Vec3(0f, 0f, -5f), Quat.IDENTITY).toMatrix()
        val pts = floatArrayOf(-1f, -1f, 0f, 1f, 1f, 0f)
        val b = Projection.projectPoints(pts, model, proj, 100, 100)!!
        near(b.x1, 40f, 0.01f); near(b.x2, 60f, 0.01f); near(b.y1, 40f, 0.01f); near(b.y2, 60f, 0.01f)
        // Entirely behind the camera: nothing.
        assertNull(Projection.projectPoints(pts, Pose(Vec3(0f, 0f, 5f), Quat.IDENTITY).toMatrix(), proj, 100, 100))
    }

    @Test fun matcherIsOneToOne() {
        val gt = listOf(GroundTruth(1, Box(0f, 0f, 10f, 10f), 3f), GroundTruth(2, Box(50f, 50f, 60f, 60f), 5f))
        val dets = listOf(
            Detection(Box(1f, 1f, 10f, 10f), Coco.CAR, 0.9f),
            Detection(Box(0f, 0f, 9f, 9f), Coco.TRUCK, 0.6f),
            Detection(Box(50f, 50f, 60f, 60f), 0, 0.9f), // a person there does not count
        )
        val r = Matcher.match(gt, dets)
        assertEquals(1, r.pairs.size); assertEquals(1L, r.pairs[0].first.nodeId)
        assertEquals(listOf(2L), r.missed.map { it.nodeId })
        assertEquals(1, r.falsePositives.size)
    }
}

/** Writes one encoded pose for tools/monitor.py's parser to be checked against (see README). */
class WireSampleTest {
    @Test fun writeSample() {
        val e = Envelope(0x0123456789ABCDEFL, 7, 1, PoseMsg(42, 1_760_000_000_123L, Tracking.TRACKING, true, 3,
            Vec3(1.25f, 1.5f, -2.75f), Quat(0f, 0.38268343f, 0f, 0.9238795f), 0.7853982f))
        val f = java.io.File(System.getProperty("resilane.root") ?: "..", "core/build/wire_pose_sample.bin")
        f.parentFile.mkdirs(); f.writeBytes(Codec.encode(e))
    }
}

/** Joystick heading: relative to the view while held, latched on release, frame-independent. */
class HeadingControlTest {
    /** Camera with ground heading [yaw], tilted down by [pitchDown] radians (a phone camera looks down -Z). */
    private fun cam(yaw: Float, pitchDown: Float = 0f) =
        Pose(Vec3(0f, 1.4f, 0f), (Quat.rotY(yaw + Angles.PI) * Quat.axisAngle(Vec3.X, -pitchDown)).normalized())

    private fun nearAngle(a: Float, b: Float, msg: String = "") = near(Angles.wrapPi(a - b), 0f, 1e-3f, msg)

    @Test fun stickIsRelativeToTheView() {
        val h = HeadingControl()
        val view = 0.6f
        h.setStick(0f, -1f); nearAngle(h.update(cam(view)), view, "up = ahead")
        // Right on the stick is a clockwise turn: +90 degrees of heading.
        h.setStick(1f, 0f)
        near(Angles.headingDegOfYaw(h.update(cam(view))), (Angles.headingDegOfYaw(view) + 90f) % 360f, 0.05f, "right")
        h.setStick(0f, 1f); nearAngle(h.update(cam(view)), view + Angles.PI, "down = behind")
    }

    @Test fun releaseLatchesTheHeading() {
        val h = HeadingControl()
        h.setStick(-1f, 0f)
        val set = h.update(cam(0.2f))
        h.setStick(0f, 0f)
        nearAngle(h.update(cam(2.5f)), set, "turning the phone must not turn the car")
        h.setStick(0.1f, 0.1f) // inside the deadzone
        nearAngle(h.update(cam(-1f)), set, "deadzone")
        h.reset()
        nearAngle(h.update(cam(-1f)), -1f, "reset follows the camera again")
    }

    @Test fun firstFrameFacesTheCamera() = nearAngle(HeadingControl().update(cam(1.3f)), 1.3f)

    @Test fun pointingAtTheFloorUsesTheScreenTop() {
        for (pitch in listOf(0.3f, 1.0f, Angles.PI / 2)) {
            nearAngle(HeadingControl.groundForwardYaw(cam(-0.8f, pitch)), -0.8f, "pitch $pitch")
        }
    }

    @Test fun twoPhonesAgreeOnTheRelayedHeading() {
        // The same physical heading, latched in two different session frames, maps to one world yaw.
        val labHeading = 0.9f
        val world = listOf(Quat.rotY(1.7f), Quat.rotY(-2.4f)).map { qSession ->
            val sessionFromLab = Pose(Vec3(3f, 0f, -1f), qSession)
            val h = HeadingControl()
            h.setStick(0f, -1f)
            val camSession = sessionFromLab.compose(cam(labHeading))
            val trackingYaw = h.update(camSession)
            HeadingControl.toWorld(trackingYaw, sessionFromLab.inverse())
        }
        nearAngle(world[0], labHeading); nearAngle(world[1], labHeading)
    }
}
