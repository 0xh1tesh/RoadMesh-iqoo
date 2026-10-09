package org.resilane.sim

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.content.res.ColorStateList
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import org.resilane.core.math.Angles
import org.resilane.core.sync.NodeRegistry
import org.resilane.core.sync.RenderMode
import org.resilane.core.sync.VehicleOffset
import org.resilane.sim.ar.SimEngine
import org.resilane.sim.ar.SimPeers
import org.resilane.sim.net.NetController
import org.resilane.sim.render.GlbModel
import org.resilane.sim.render.MsaaConfigChooser
import org.resilane.sim.ui.Joystick
import org.resilane.sim.ui.OverlayView
import org.resilane.sim.ui.SettingsPanel
import org.resilane.sim.ui.Ui
import org.resilane.sim.ui.bar
import org.resilane.sim.vision.VisionPipeline
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var registry: NodeRegistry
    private lateinit var net: NetController
    private lateinit var vision: VisionPipeline
    private lateinit var sim: SimPeers
    private lateinit var engine: SimEngine
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var ui: Ui
    private lateinit var root: FrameLayout
    private lateinit var scaleLabel: TextView
    private lateinit var scaleBar: SeekBar
    private lateinit var joystick: Joystick
    private var settingsPanel: SettingsPanel? = null
    private var session: Session? = null
    private var installRequested = false
    private var netStarted = false
    @Volatile private var showLog = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        res.filterValues { !it }.keys.takeIf { it.isNotEmpty() }?.let { RLog.w("App", "permissions denied: ${it.joinToString()}") }
        startNet()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RLog.open(this)
        settings = Settings(this)
        RLog.i("App", "ResiLane ${BuildConfig.VERSION_NAME} node '${settings.nodeName}' %016x on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})".format(settings.nodeId))
        RLog.i("App", "ARCore availability: ${ArCoreApk.getInstance().checkAvailability(this)}")
        registry = NodeRegistry(settings.nodeId)
        vision = VisionPipeline(this, settings)
        sim = SimPeers(registry, settings)
        net = NetController(this, settings, registry) { runOnUiThread { refreshScale(); sim.setScale(settings.myVehicleScale) } }
        engine = SimEngine(this, settings, registry, net, vision, sim)
        ui = Ui(this)
        buildUi()
        loadModel()
        requestPermissions()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this)
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setEGLConfigChooser(MsaaConfigChooser())
            setRenderer(engine)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }
        vision.surface = glView
        root.addView(glView, FrameLayout.LayoutParams(MATCH, MATCH))

        overlay = OverlayView(this, settings, vision, { engine.snapshot }, ::debugLines) { if (showLog) RLog.recent(22) else null }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // Bottom: heading joystick, scale slider row, action bar.
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        joystick = Joystick(this, engine.heading::setStick) {
            engine.snapshot?.takeIf { it.myWorld != null }?.let { Angles.wrapPi(it.myViewYaw - it.myYaw) }
        }.apply { visibility = if (settings.joystickHeading) View.VISIBLE else View.GONE }
        bottom.addView(joystick, LinearLayout.LayoutParams(ui.px(132), ui.px(132)).apply {
            gravity = Gravity.START; setMargins(ui.px(16), 0, 0, ui.px(10))
        })
        val scaleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.bg(android.graphics.Color.argb(200, 12, 15, 20), 22f)
            setPadding(ui.px(16), ui.px(4), ui.px(12), ui.px(4))
        }
        scaleLabel = ui.label("", 13f, Ui.ACCENT).apply { typeface = android.graphics.Typeface.MONOSPACE; minWidth = ui.px(120) }
        scaleBar = SeekBar(this).apply {
            max = 58 // 0.10 .. 3.00 in 0.05 steps
            progressTintList = ColorStateList.valueOf(Ui.ACCENT); thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) setQuickScale(0.1f + p * 0.05f) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { settings.save() }
            })
        }
        scaleRow.addView(scaleLabel)
        scaleRow.addView(scaleBar, LinearLayout.LayoutParams(0, ui.px(40), 1f))
        bottom.addView(scaleRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ui.px(12), 0, ui.px(12), ui.px(8)) })
        bottom.addView(ui.bar(this,
            ui.button("Calibrate", primary = true) { engine.calibrator.requestCalibration() },
            ui.button("+ Test car") { spawnTestCar() },
            ui.button("Motion") { sim.cycleMotion() },
            ui.button("Clear") { sim.clear() },
            ui.button("Settings") { toggleSettings() },
            ui.button("Log") { showLog = !showLog },
        ))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        refreshScale()

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            overlay.topInset = bars.top.toFloat()
            bottom.setPadding(0, 0, 0, bars.bottom + ui.px(10))
            insets
        }
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        overlay.postOnAnimation(object : Runnable {
            override fun run() { overlay.invalidate(); joystick.invalidate(); overlay.postOnAnimation(this) }
        })
    }


    /** The bottom slider: fleet scale when synchronised globally, otherwise this phone's view only. */
    private fun setQuickScale(v: Float) {
        if (settings.scaleSyncGlobal) {
            net.publishScale(v)
            sim.setScale(v)
        } else {
            settings.localScale = v
        }
        refreshScale()
    }

    private fun refreshScale() {
        val v = if (settings.scaleSyncGlobal) settings.myVehicleScale else settings.localScale
        scaleLabel.text = (if (settings.scaleSyncGlobal) "Fleet %.2f×" else "View %.2f×").format(v)
        scaleBar.progress = ((v - 0.1f) / 0.05f).roundToInt().coerceIn(0, 58)
    }

    private fun spawnTestCar() {
        val me = engine.snapshot?.myWorld
        if (me == null) { RLog.w("Sim", "can't place a test car until tracking starts"); return }
        sim.spawn(me, SimPeers.Motion.PARKED)
    }

    private fun toggleSettings() {
        settingsPanel?.let { root.removeView(it); settingsPanel = null; settings.save(); return }
        val p = SettingsPanel(this, ui)
        p.header("Settings") { toggleSettings() }

        p.section("This node")
        p.textField("Name", { settings.nodeName }) { settings.nodeName = it; net.sendHello() }
        p.note("Vehicle colour (how others see this phone)")
        p.swatches(Settings.PALETTE, { settings.vehicleColor }) { settings.vehicleColor = it; net.sendHello() }

        p.section("Driving")
        p.toggle("Steer heading with the joystick", { settings.joystickHeading }) {
            settings.joystickHeading = it
            engine.heading.setStick(0f, 0f)
            joystick.visibility = if (it) View.VISIBLE else View.GONE
        }
        p.note("The joystick only turns your car; where it is comes from walking with the phone. Off: the car faces where the camera looks.")

        p.section("Vehicle size")
        p.slider("Fleet / my vehicle scale", 0.1f, 3f, 0.05f, { settings.myVehicleScale }, { "%.2f×".format(it) }) {
            net.publishScale(it); sim.setScale(it); refreshScale()
        }
        p.slider("Local view multiplier", 0.1f, 3f, 0.05f, { settings.localScale }, { "%.2f×".format(it) }) { settings.localScale = it; refreshScale() }
        p.toggle("Synchronise scale across all phones", { settings.scaleSyncGlobal }) { settings.scaleSyncGlobal = it; refreshScale() }
        p.slider("Vehicle length at 1.0×", 2f, 6f, 0.1f, { settings.defaultLengthM }, { "%.1f m".format(it) }) { settings.defaultLengthM = it }
        p.note("Scale changes only the rendered car. Positions, distances and altitude are unaffected.")

        p.section("Look")
        p.choice("Vehicle style", listOf("Hologram", "Realistic (best for YOLO)"), { if (settings.hologram) 0 else 1 }) { settings.hologram = it == 0 }
        p.toggle("Holographic ground grid", { settings.showPlanes }) { settings.showPlanes = it }
        p.note("YOLO is trained on real cars: it scores the realistic style far better than the hologram.")

        p.section("Placement")
        p.choice("Render mode", listOf("Ground-locked", "Free 3D (debug)"), { settings.renderMode.ordinal }) { settings.renderMode = RenderMode.entries[it] }
        p.slider("Offset forward", -3f, 3f, 0.05f, { settings.offset.forward }, { "%+.2f m".format(it) }) { settings.offset = settings.offset.copy(forward = it) }
        p.slider("Offset right", -3f, 3f, 0.05f, { settings.offset.right }, { "%+.2f m".format(it) }) { settings.offset = settings.offset.copy(right = it) }
        p.slider("Offset up (free mode)", -2f, 2f, 0.05f, { settings.offset.up }, { "%+.2f m".format(it) }) { settings.offset = settings.offset.copy(up = it) }
        p.slider("Phone height (no floor detected)", 0.5f, 2.2f, 0.05f, { settings.eyeHeightM }, { "%.2f m".format(it) }) { settings.eyeHeightM = it }

        p.section("Calibration marker")
        p.slider("Printed width (measure it!)", 100f, 400f, 1f, { settings.markerWidthM * 1000 }, { "%.0f mm".format(it) }) { settings.markerWidthM = it / 1000f; reconfigureMarker() }
        p.slider("Marker centre above ground", 0f, 2f, 0.01f, { settings.markerHeightM }, { "%.2f m".format(it) }) { settings.markerHeightM = it }
        p.note("Recalibrate after changing either value. Every phone must use the same printed marker.")

        p.section("Network")
        p.toggle("Nearby Connections (no Wi-Fi needed)", { settings.useNearby }) { settings.useNearby = it; net.applyTransports() }
        p.toggle("UDP on shared Wi-Fi / hotspot", { settings.useUdp }) { settings.useUdp = it; net.applyTransports() }
        p.slider("Pose rate", 2f, 30f, 1f, { settings.poseRateHz.toFloat() }, { "%.0f Hz".format(it) }) { settings.poseRateHz = it.roundToInt() }

        p.section("Display")
        p.toggle("Vehicle labels", { settings.showLabels }) { settings.showLabels = it }
        p.toggle("Distance in labels", { settings.showDistance }) { settings.showDistance = it }
        p.toggle("Debug: coordinates, heading, GT boxes", { settings.showDebug }) { settings.showDebug = it }
        p.toggle("Driver windshield overlay", { settings.showWindshield }) { settings.showWindshield = it }
        p.toggle("Radar", { settings.showRadar }) { settings.showRadar = it }
        p.toggle("Off-screen pointers", { settings.showOffscreen }) { settings.showOffscreen = it }

        p.section("YOLO (simulator feed)")
        p.toggle("Run YOLO on the AR frame", { settings.yoloEnabled }) { settings.yoloEnabled = it }
        p.slider("YOLO rate", 1f, 10f, 1f, { settings.yoloHz.toFloat() }, { "%.0f Hz".format(it) }) { settings.yoloHz = it.roundToInt() }
        p.slider("Confidence threshold", 0.1f, 0.9f, 0.05f, { settings.yoloConf }, { "%.2f".format(it) }) { settings.yoloConf = it }
        p.toggle("Record dataset (frames + auto labels)", { settings.datasetCapture }) { settings.datasetCapture = it }
        p.note("Recall/precision compare YOLO against the simulator's exact car boxes. Reset by toggling YOLO.")

        val lp = FrameLayout.LayoutParams(MATCH, (resources.displayMetrics.heightPixels * 0.72f).toInt(), Gravity.BOTTOM)
        root.addView(p, lp)
        settingsPanel = p
    }

    private fun debugLines(): List<String> {
        val s = engine.snapshot
        val out = ArrayList<String>(16)
        out += "%s  %016x".format(settings.nodeName, settings.nodeId)
        s?.myWorld?.let { out += "me   x%+7.2f y%+6.2f z%+7.2f  hdg %03.0f°".format(it.t.x, it.t.y, it.t.z, s.myHeadingDeg) }
        val c = engine.calibrator
        out += "mode " + (s?.mode ?: "-") + (engine.notice?.let { "  " + it.take(46) } ?: "")
        out += "cal  " + (if (s?.calibrated == true) "epoch ${c.epoch}" else "none") + "  " + c.message.take(40)
        c.residual?.let { (d, y) -> out += "     marker drift %.1f cm  %.1f°".format(d * 100, y) }
        s?.floorOffsetM?.let { out += "     floor plane at y %+.2f m (≈0 if marker on floor)".format(it) }
        out += "net  ${net.status}"
        out += "     rx ${net.rx}  tx ${net.tx}  dup ${net.dups}"
        val views = s?.vehicles?.associateBy { it.nodeId } ?: emptyMap()
        for (n in registry.all()) {
            val st = n.state(System.currentTimeMillis())
            val v = views[n.id]
            out += "%-12s %-8s %4.1fHz %5s gap%-3d %s".format(
                n.name.take(12), st.name.take(8), n.rateHz,
                n.lastPacketAgeMs?.let { "${it}ms" } ?: "  -  ", n.seqGaps, v?.let { "%.1fm".format(it.distanceM) } ?: "")
        }
        val st = vision.stats
        out += "yolo ${vision.status}  ${vision.lastInferMs}ms"
        if (st.frames > 0) out += "     recall %.2f  prec %.2f  IoU %.2f  (%d frames)".format(st.recall, st.precision, st.meanIou, st.frames)
        out += "size fleet %.2f× view %.2f×  %s".format(settings.myVehicleScale, settings.localScale, settings.renderMode.name.lowercase())
        return out
    }

    // ------------------------------------------------------------------ model, network

    private fun loadModel() = thread(name = "rl-model") {
        try {
            val bytes = assets.open("models/vehicle_${settings.modelId}.glb").use { it.readBytes() }
            engine.model = GlbModel.load(bytes)
        } catch (e: Throwable) {
            RLog.e("Model", "vehicle model failed to load", e)
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) perms += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.NEARBY_WIFI_DEVICES
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startNet() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startNet() {
        if (netStarted) return
        netStarted = true
        net.start()
    }

    // ------------------------------------------------------------------ ARCore session

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            engine.notice = "Camera permission is needed. Allow it in the prompt or in system Settings."
            return
        }
        if (!engine.fallbackActive) {
            if (!ensureSession()) { if (!engine.fallbackActive) return }
            else try {
                session?.resume()
            } catch (e: CameraNotAvailableException) {
                RLog.e("Track", "camera not available", e)
                engine.notice = "Camera is in use by another app. Close it and reopen ResiLane."
                return
            }
        }
        if (engine.fallbackActive) { engine.feed.start(); engine.orientation.start() }
        glView.onResume()
    }

    /** Creates the ARCore session, or switches to the 3-DoF fallback when ARCore can't run here. */
    private fun ensureSession(): Boolean {
        if (session != null) return true
        val core = ArCoreApk.getInstance()
        val availability = core.checkAvailability(this)
        RLog.i("Track", "ARCore availability: $availability")
        if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            useFallback("ARCore does not support this phone (${Build.MODEL})")
            return false
        }
        return try {
            when (core.requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return false }
                else -> {}
            }
            val s = Session(this)
            configure(s)
            session = s
            engine.attachSession(s)
            engine.notice = null
            true
        } catch (e: UnavailableDeviceNotCompatibleException) {
            useFallback("ARCore does not support this phone (${Build.MODEL})"); false
        } catch (e: UnavailableUserDeclinedInstallationException) {
            useFallback("Google Play Services for AR was not installed"); false
        } catch (e: UnavailableException) {
            useFallback("ARCore unavailable: ${e.javaClass.simpleName.removePrefix("Unavailable").removeSuffix("Exception")}"); false
        } catch (e: Exception) {
            RLog.e("Track", "ARCore session failed", e)
            useFallback("ARCore failed to start: ${e.javaClass.simpleName}"); false
        }
    }

    private fun useFallback(why: String) {
        RLog.w("Track", "$why -> 3-DoF camera mode (rotation only)")
        engine.notice = "$why. Rotation-only view: turn to look around; walking won't move the cars."
        engine.fallbackActive = true
    }

    private fun configure(s: Session) {
        val marker = assets.open("markers/resilane_origin.png").use { BitmapFactory.decodeStream(it) }
        val db = AugmentedImageDatabase(s)
        db.addImage("resilane_origin", marker, settings.markerWidthM)
        val cfg = Config(s).apply {
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            augmentedImageDatabase = db
            depthMode = Config.DepthMode.DISABLED
        }
        try {
            s.configure(cfg)
        } catch (e: Exception) {
            RLog.w("Track", "HDR light estimation unsupported, falling back: $e")
            cfg.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            s.configure(cfg)
        }
        RLog.i("Calib", "marker DB ready: width %.0f mm, centre %.2f m above ground".format(settings.markerWidthM * 1000, settings.markerHeightM))
    }

    private fun reconfigureMarker() {
        val s = session ?: return
        glView.queueEvent {
            try { configure(s); engine.calibrator.reset() } catch (e: Exception) { RLog.e("Calib", "reconfigure failed", e) }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::glView.isInitialized) glView.onPause()
        session?.pause()
        engine.feed.stop()
        engine.orientation.stop()
        settings.save()
        RLog.flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        net.stop()
        vision.close()
        session?.close()
        session = null
        RLog.flush()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
