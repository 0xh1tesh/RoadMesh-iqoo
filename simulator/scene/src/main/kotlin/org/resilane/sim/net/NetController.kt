package org.resilane.sim.net

import org.resilane.scene.BuildConfig

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.resilane.core.math.Angles
import org.resilane.core.proto.Bye
import org.resilane.core.proto.Envelope
import org.resilane.core.proto.Hello
import org.resilane.core.proto.Message
import org.resilane.core.proto.Ping
import org.resilane.core.proto.PoseMsg
import org.resilane.core.proto.Pong
import org.resilane.core.proto.ScaleSync
import org.resilane.core.sync.NodeRegistry
import org.resilane.sim.RLog
import org.resilane.sim.Settings
import java.util.concurrent.atomic.AtomicInteger

/**
 * The node's network side: identity beacons (HELLO), pose broadcast, clock sync (PING/PONG) and
 * fleet-wide vehicle-scale sync. Everything received lands in [registry].
 */
class NetController(
    private val context: Context,
    private val settings: Settings,
    val registry: NodeRegistry,
    private val onScaleSynced: () -> Unit,
) {
    private val selfId = settings.nodeId
    private val msgSeq = AtomicInteger(0)
    private val sessionId = java.security.SecureRandom().nextLong()
    private val router = MeshRouter(selfId, ::onMessage)
    private val thread = HandlerThread("rl-net").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var lastScaleIssued = 0L
    private val warnedMarker = HashSet<Long>()

    val status get() = router.status()
    val rx get() = router.rxCount
    val tx get() = router.txCount
    val dups get() = router.dupCount

    fun start() {
        applyTransports()
        handler.post(object : Runnable {
            var tick = 0
            override fun run() {
                if (tick % 4 == 0) sendHello()
                if (tick % 4 == 2) send(Ping(System.currentTimeMillis()))
                if (tick % 20 == 0) registry.pruneLost(System.currentTimeMillis())
                tick++
                handler.postDelayed(this, 500)
            }
        })
    }

    /** Start/stop transports to match settings. Safe to call repeatedly. */
    fun applyTransports() = handler.post {
        if (settings.useNearby && !router.has("nearby")) {
            runCatching { router.add(NearbyTransport(context, selfId) { settings.nodeName }) }
                .onFailure { RLog.e("Net", "nearby start failed", it) }
        } else if (!settings.useNearby) router.remove("nearby")
        if (settings.useUdp && !router.has("udp")) {
            runCatching { router.add(UdpTransport(context)) }.onFailure { RLog.e("Net", "udp start failed", it) }
        } else if (!settings.useUdp) router.remove("udp")
    }

    fun stop() {
        send(Bye)
        handler.postDelayed({ router.stopAll(); thread.quitSafely() }, 150)
    }

    private fun send(m: Message) = router.send(Envelope(selfId, msgSeq.incrementAndGet(), 1, m, sessionId))

    fun sendHello() = send(
        Hello(settings.nodeName, settings.modelId, settings.vehicleColor, settings.myVehicleScale,
            settings.markerWidthM * 1000f, BuildConfig.VERSION_NAME)
    )

    fun sendPose(p: PoseMsg) {
        send(p)
        RLog.pose("tx", selfId, p.poseSeq, p.timeMs, null, p.tracking.name, p.calibrated,
            p.position.x, p.position.y, p.position.z, Angles.headingDegOfYaw(p.yaw),
            p.rotation.x, p.rotation.y, p.rotation.z, p.rotation.w)
    }

    /** Local slider moved. With global sync on, every node adopts it. */
    fun publishScale(scale: Float) {
        settings.myVehicleScale = scale
        if (settings.scaleSyncGlobal) {
            lastScaleIssued = System.currentTimeMillis()
            send(ScaleSync(scale, lastScaleIssued))
        }
        sendHello()
    }

    private fun onMessage(e: Envelope, via: String) {
        val now = System.currentTimeMillis()
        when (val m = e.body) {
            is Hello -> {
                val n = registry.getOrCreate(e.origin) ?: return
                if (n.hello == null) RLog.i("Peer", "hello from ${m.name} (%016x) via $via, v${m.appVersion}".format(e.origin))
                n.hello = m
                if (kotlin.math.abs(m.markerWidthMm - settings.markerWidthM * 1000f) > 2f && warnedMarker.size < 1_024 && warnedMarker.add(e.origin)) {
                    RLog.w("Calib", "${m.name} uses marker width ${m.markerWidthMm} mm, this phone ${settings.markerWidthM * 1000f} mm: distances will disagree")
                }
            }
            is PoseMsg -> {
                val n = registry.getOrCreate(e.origin) ?: return
                n.onPose(m, now, via)
                RLog.pose("rx", e.origin, m.poseSeq, m.timeMs, n.lastPacketAgeMs, m.tracking.name, m.calibrated,
                    m.position.x, m.position.y, m.position.z, Angles.headingDegOfYaw(m.yaw),
                    m.rotation.x, m.rotation.y, m.rotation.z, m.rotation.w)
            }
            is ScaleSync -> if (settings.scaleSyncGlobal && m.issuedMs > lastScaleIssued) {
                lastScaleIssued = m.issuedMs
                settings.myVehicleScale = m.scale
                settings.save()
                RLog.i("Net", "fleet vehicle scale -> %.2f (from %016x)".format(m.scale, e.origin))
                sendHello()
                onScaleSynced()
            }
            is Ping -> send(Pong(e.origin, m.t0, now))
            is Pong -> if (m.target == selfId) registry.get(e.origin)?.clock?.onPong(m.t0, m.t1, now)
            Bye -> { registry.remove(e.origin); RLog.i("Peer", "bye from %016x".format(e.origin)) }
        }
    }
}
