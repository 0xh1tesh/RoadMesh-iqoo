package org.resilane.sim.ar

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.SessionPausedException
import org.resilane.core.control.HeadingControl
import org.resilane.core.math.Angles
import org.resilane.core.math.Pose
import org.resilane.core.math.Vec3
import org.resilane.core.proto.PoseMsg
import org.resilane.core.proto.Tracking
import org.resilane.core.sync.NodeRegistry
import org.resilane.core.sync.NodeState
import org.resilane.core.sync.RenderMode
import org.resilane.core.sync.VehiclePlacement
import org.resilane.core.vision.GroundTruth
import org.resilane.core.vision.Projection
import org.resilane.sim.RLog
import org.resilane.sim.Settings
import org.resilane.sim.net.NetController
import org.resilane.sim.render.BackgroundRenderer
import org.resilane.sim.render.Environment
import org.resilane.sim.render.GlbModel
import org.resilane.sim.render.HologramRenderer
import org.resilane.sim.render.PlaneRenderer
import org.resilane.sim.render.VehicleInstance
import org.resilane.sim.render.VehicleRenderer
import org.resilane.sim.vision.VisionPipeline
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * The per-frame loop, on the GL thread:
 *   ARCore update -> calibration -> own shared-world pose -> broadcast (rate-limited)
 *   -> for each peer: interpolate -> place (ground-locked / free) -> shared->session -> draw
 *   -> ground-truth boxes for YOLO -> publish a [Snapshot] for the overlay.
 *
 * The only coordinates that leave this phone are shared-world ones (see SharedFrame). The only
 * place a session coordinate is produced from a remote pose is `sessionFromWorld.compose(...)`.
 */
class SimEngine(
    private val context: Context,
    private val settings: Settings,
    private val registry: NodeRegistry,
    private val net: NetController,
    private val vision: VisionPipeline,
    val sim: SimPeers,
) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    val calibrator = Calibrator(settings)
    /** Joystick-set vehicle heading; written by the joystick view, read here on the GL thread. */
    val heading = HeadingControl()
    @Volatile var snapshot: Snapshot? = null; private set
    @Volatile var model: GlbModel? = null
    @Volatile var onSurfaceReady: (() -> Unit)? = null

    private val background = BackgroundRenderer()
    private val env = Environment()
    private val cars = VehicleRenderer(env)
    private val planes = PlaneRenderer()
    private val holo = HologramRenderer()
    private val texM = FloatArray(16)

    /** No-ARCore fallback: Camera2 image + rotation sensor (3-DoF). Started by the activity. */
    val feed = CameraFeed(context)
    val orientation = OrientationTracker(context)
    @Volatile var fallbackActive = false
    /** Shown on screen while the phone runs in a degraded mode (e.g. why ARCore isn't available). */
    @Volatile var notice: String? = null
    private var width = 1
    private var height = 1
    private var rotation = 0
    @Volatile private var geometryDirty = true

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val viewProj = FloatArray(16)

    private var frameId = 0L
    private var poseSeq = 0
    private var lastPoseSent = 0L
    private var lastConfirmedWorld: Pose? = null
    /** Vehicle heading (world yaw) and camera ground direction for [lastConfirmedWorld]. */
    private var lastYaw = 0f
    private var lastViewYaw = 0f
    private var lastTracking: TrackingState? = null
    private var fps = 0f
    private var lastFrameMs = 0L
    private var speed = 0f
    private var lastSpeedPos: Vec3? = null
    private var lastSpeedMs = 0L
    private var lastLoggedState = HashMap<Long, NodeState>()

    val backgroundTextureId get() = background.textureId

    fun setDisplay(rotation: Int) { this.rotation = rotation; geometryDirty = true }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        background.init()
        env.init()
        cars.init()
        holo.init()
        planes.init()
        session?.setCameraTextureName(background.textureId)
        onSurfaceReady?.invoke()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES30.glViewport(0, 0, w, h)
        geometryDirty = true
    }

    fun attachSession(s: Session) {
        session = s
        if (background.textureId != 0) s.setCameraTextureName(background.textureId)
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val now = System.currentTimeMillis()
        model?.let { if (!it.uploaded) it.upload(srgbImagesOf(it)) }
        val s = session
        when {
            s != null -> arFrame(s, now)
            fallbackActive -> fallbackFrame(now)
        }
    }

    /** 6-DoF frame from ARCore. */
    private fun arFrame(s: Session, now: Long) {
        if (geometryDirty) { s.setDisplayGeometry(rotation, width, height); geometryDirty = false }
        s.setCameraTextureName(background.textureId)

        val frame = try { s.update() } catch (_: SessionPausedException) { return } catch (e: Exception) {
            RLog.e("Track", "session.update failed", e); return
        }
        val camera = frame.camera
        background.draw(frame)
        env.update(frame.lightEstimate)
        calibrator.onFrame(s, frame, now)
        tickFps(now)

        val tracking = camera.trackingState
        if (tracking != lastTracking) {
            RLog.i("Track", "camera $tracking" + if (tracking != TrackingState.TRACKING) " (${camera.trackingFailureReason})" else "")
            lastTracking = tracking
        }

        // Shared frame (or, for solo testing before calibration, this session's own frame).
        val sessionFromWorld = calibrator.sessionFromWorld()
        val calibrated = sessionFromWorld != null
        val sfw = sessionFromWorld ?: Pose.IDENTITY
        val worldFromSession = sfw.inverse()

        if (tracking == TrackingState.TRACKING) {
            val camSession = camera.displayOrientedPose.toCore()
            lastConfirmedWorld = worldFromSession.compose(camSession)
            updateHeading(camSession, worldFromSession)
            updateSpeed(lastConfirmedWorld!!.t, now)
        }
        val tr = when (tracking) { TrackingState.TRACKING -> Tracking.TRACKING; TrackingState.PAUSED -> Tracking.PAUSED; else -> Tracking.STOPPED }
        broadcast(now, tr, calibrated)
        sim.tick(now)

        if (tracking != TrackingState.TRACKING) {
            publish(now, tracking.name, camera.trackingFailureReason.name, calibrated, null, emptyList(), null, null)
            return
        }
        val myWorld = lastConfirmedWorld!!

        camera.getProjectionMatrix(proj, 0, 0.05f, 300f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        val camPos = floatArrayOf(camera.pose.tx(), camera.pose.ty(), camera.pose.tz())

        if (settings.showPlanes) planes.draw(s.getAllTrackables(Plane::class.java), viewProj, worldFromSession.toMatrix(), camPos)

        // Cars stand on the shared floor once calibrated; before that on the detected floor plane,
        // or an assumed eye height below the phone.
        val groundY = if (calibrated) 0f else sessionFloorY(s) ?: (camPos[1] - settings.eyeHeightM)
        val views = renderScene(now, sfw, calibrated, myWorld, camPos, groundY)
        val axes = if (calibrated && settings.showDebug) arrayOf(
            sfw.transformPoint(Vec3.ZERO), sfw.transformPoint(Vec3.X), sfw.transformPoint(Vec3.Z), sfw.transformPoint(Vec3.Y * 0.5f),
        ).map { Projection.projectPoint(it.x, it.y, it.z, viewProj, width, height) }.toTypedArray() else null
        publish(now, tracking.name, "", calibrated, myWorld, views, axes, floorOffset(s, sfw, calibrated))
    }

    /**
     * 3-DoF frame for phones without ARCore: Camera2 image, orientation from the rotation sensor,
     * the camera held at eye height above an assumed floor at y = 0. Walking does not move the
     * view, and there is no shared frame, so only simulated cars are placed; real peers need ARCore.
     */
    private fun fallbackFrame(now: Long) {
        val live = feed.update(background.textureId, texM)
        if (live) background.drawFeed(texM, feed.imageAspect, width, height)
        tickFps(now)
        val q = orientation.orientation
        if (!live || q == null) {
            val why = feed.error ?: if (!orientation.available) "no rotation sensor" else "starting camera"
            publish(now, "STARTING", why, false, null, emptyList(), null, null)
            return
        }
        val me = Pose(Vec3(0f, settings.eyeHeightM, 0f), q)
        lastConfirmedWorld = me
        updateHeading(me, Pose.IDENTITY)
        broadcast(now, Tracking.TRACKING, false)
        sim.tick(now)

        me.inverse().toMatrix(view)
        val aspect = width.toFloat() / height
        Matrix.perspectiveM(proj, 0, feed.screenFovYDeg(aspect), aspect, 0.05f, 300f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        val camPos = floatArrayOf(me.t.x, me.t.y, me.t.z)
        if (settings.showPlanes) planes.drawEstimatedFloor(viewProj, camPos, 0f, 7f)
        val views = renderScene(now, Pose.IDENTITY, false, me, camPos, 0f)
        publish(now, "TRACKING", "", false, me, views, null, null)
    }

    /**
     * Vehicle heading for this frame. Position never comes from here: only the direction the car
     * faces, which the joystick sets (latched in the tracking frame, see HeadingControl).
     */
    private fun updateHeading(trackingFromCamera: Pose, worldFromTracking: Pose) {
        if (!settings.joystickHeading) heading.reset()
        lastYaw = HeadingControl.toWorld(heading.update(trackingFromCamera), worldFromTracking)
        lastViewYaw = HeadingControl.toWorld(HeadingControl.groundForwardYaw(trackingFromCamera), worldFromTracking)
    }

    /** Own pose out at the configured rate; while tracking is lost, the last confirmed pose flagged PAUSED. */
    private fun broadcast(now: Long, tracking: Tracking, calibrated: Boolean) {
        val period = 1000L / settings.poseRateHz.coerceIn(1, 60)
        val confirmed = lastConfirmedWorld ?: return
        if (now - lastPoseSent < period) return
        lastPoseSent = now
        net.sendPose(PoseMsg(poseSeq++, now, tracking, calibrated, calibrator.epoch, confirmed.t, confirmed.q, lastYaw))
    }

    /** Every other node: interpolate, place, draw, ground-truth box; then the YOLO hand-off. */
    private fun renderScene(now: Long, sfw: Pose, calibrated: Boolean, myWorld: Pose, camPos: FloatArray, groundY: Float): List<VehicleView> {
        val m = model
        val instances = ArrayList<VehicleInstance>()
        val views = ArrayList<VehicleView>()
        val gts = ArrayList<GroundTruth>()
        for (node in registry.all()) {
            val st = node.state(now)
            if (lastLoggedState[node.id] != st) {
                RLog.i("Peer", "${node.name}: $st" + (node.lastPacketAgeMs?.let { " (age $it ms)" } ?: ""))
                lastLoggedState[node.id] = st
            }
            if (st == NodeState.LOST || st == NodeState.UNCALIBRATED) continue
            // A real peer's coordinates mean nothing here until this phone shares the frame too.
            if (!node.simulated && !calibrated) continue
            val ip = node.interp.sample(now - node.interp.delayMs) ?: continue
            val carWorld = VehiclePlacement.place(ip.pose, ip.yaw, settings.renderMode, settings.offset, groundY)
            val carSession = sfw.compose(carWorld)
            val nodeScale = (node.hello?.modelScale ?: 1f).coerceIn(0.05f, 5f)
            val scale = nodeScale * settings.localScale * (if (m != null) settings.defaultLengthM / m.lengthM else 1f)
            val modelM = carSession.toMatrix(scale = scale)
            val degraded = st == NodeState.DEGRADED
            instances += VehicleInstance(modelM, node.hello?.colorArgb ?: Settings.PALETTE[0],
                if (degraded) 0.45f else 1f, if (degraded) 0.8f else 0f, settings.renderMode == RenderMode.GROUND_LOCKED)

            val box = m?.let { Projection.projectPoints(it.silhouette, modelM, viewProj, width, height) }
            val dist = myWorld.t.distanceTo(ip.pose.t)
            if (box != null && !degraded) gts += GroundTruth(node.id, box, dist)
            val top = (m?.heightM ?: 1.3f) * scale + 0.25f
            val label = carSession.transformPoint(Vec3(0f, top, 0f))
            val ground = carSession.transformPoint(Vec3.ZERO)
            val ahead = carSession.transformPoint(Vec3(0f, 0f, 1.5f))
            views += VehicleView(
                node.id, node.name, node.hello?.colorArgb ?: Settings.PALETTE[0], st, node.simulated,
                ip.pose.t, Angles.headingDegOfYaw(ip.yaw), ip.yaw, dist,
                myWorld.t.horizontalDistanceTo(ip.pose.t), ip.pose.t.y,
                node.lastPacketAgeMs, node.rateHz, nodeScale,
                Projection.projectPoint(label.x, label.y, label.z, viewProj, width, height),
                box,
                Projection.projectPoint(ground.x, ground.y, ground.z, viewProj, width, height),
                Projection.projectPoint(ahead.x, ahead.y, ahead.z, viewProj, width, height),
                screenDirection(ground),
            )
        }
        if (m != null) {
            if (settings.hologram) holo.draw(m, instances, viewProj, camPos) else cars.draw(m, instances, viewProj, camPos)
        }

        frameId++
        if (vision.wantsFrame(now)) vision.onFrameRendered(frameId, gts, width, height, myWorld, views)
        return views
    }

    private fun tickFps(now: Long) {
        fps = if (lastFrameMs == 0L) 0f else fps * 0.9f + 1000f / (now - lastFrameMs).coerceAtLeast(1) * 0.1f
        lastFrameMs = now
    }

    private fun publish(now: Long, tracking: String, reason: String, calibrated: Boolean, my: Pose?, views: List<VehicleView>,
                        axes: Array<FloatArray?>?, floor: Float?) {
        snapshot = Snapshot(
            frameId, now, width, height, tracking, reason, calibrated,
            calibrator.message, calibrator.epoch, calibrator.residual, calibrator.markerVisible,
            my ?: lastConfirmedWorld, Angles.headingDegOfYaw(lastYaw), lastYaw, lastViewYaw,
            speed, views, axes, fps, floor,
            if (session != null) "ARCORE" else "3DOF", notice,
        )
    }

    /** Screen-space direction (x right, y down) toward a session-space point, for edge pointers. */
    private fun screenDirection(p: Vec3): FloatArray {
        val v = FloatArray(4)
        Matrix.multiplyMV(v, 0, view, 0, floatArrayOf(p.x, p.y, p.z, 1f), 0)
        var x = v[0]; var y = -v[1]
        if (v[2] > 0) { if (abs(x) < 1e-3f && abs(y) < 1e-3f) y = 1f } // behind: point to the nearer side
        val l = kotlin.math.sqrt(x * x + y * y).coerceAtLeast(1e-6f)
        x /= l; y /= l
        return floatArrayOf(x, y, if (v[2] > 0) 1f else 0f)
    }

    private fun updateSpeed(p: Vec3, now: Long) {
        val last = lastSpeedPos
        if (last == null || now - lastSpeedMs > 1000) { lastSpeedPos = p; lastSpeedMs = now; return }
        if (now - lastSpeedMs >= 200) {
            val v = p.horizontalDistanceTo(last) / ((now - lastSpeedMs) / 1000f)
            speed = speed * 0.7f + v * 0.3f
            lastSpeedPos = p; lastSpeedMs = now
        }
    }

    /**
     * Height of the lowest tracked horizontal plane in the shared frame. If the marker lies on that
     * floor this should be ~0; a large value means the marker height setting is wrong.
     */
    private var floorCache: Float? = null
    private var floorAt = 0L
    private fun floorOffset(s: Session, sfw: Pose, calibrated: Boolean): Float? {
        if (!calibrated) return null
        val now = System.currentTimeMillis()
        if (now - floorAt < 1000) return floorCache
        floorAt = now
        val wfs = sfw.inverse()
        floorCache = s.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.subsumedBy == null }
            .minOfOrNull { wfs.transformPoint(it.centerPose.toCore().t).y }
        return floorCache
    }

    /** Height of the lowest tracked floor plane in session coordinates (uncalibrated placement). */
    private var sessionFloorCache: Float? = null
    private var sessionFloorAt = 0L
    private fun sessionFloorY(s: Session): Float? {
        val now = System.currentTimeMillis()
        if (now - sessionFloorAt < 1000) return sessionFloorCache
        sessionFloorAt = now
        sessionFloorCache = s.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.subsumedBy == null }
            .minOfOrNull { it.centerPose.ty() }
        return sessionFloorCache
    }

    private fun srgbImagesOf(m: GlbModel): Set<Int> =
        m.groups.flatMap { listOfNotNull(it.material.baseTex, it.material.emTex) }.toSet()
}
