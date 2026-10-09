package org.resilane.sim.net

import android.content.Context
import android.net.wifi.WifiManager
import org.resilane.sim.RLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * UDP broadcast on the local network (shared Wi-Fi, or one phone's hotspot). A fallback and a
 * second path alongside Nearby, and the way the laptop monitor (tools/monitor.py) listens in.
 * Sends to the limited broadcast address and to every interface's directed broadcast address,
 * because some hotspot interfaces drop 255.255.255.255.
 */
class UdpTransport(context: Context, private val port: Int = PORT) : PeerTransport {
    override val name = "udp"
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var socket: DatagramSocket? = null
    private val out = LinkedBlockingQueue<ByteArray>(256)
    @Volatile private var running = false
    @Volatile private var rx = 0L
    @Volatile private var targets: List<InetAddress> = emptyList()
    @Volatile private var lastErr: String? = null

    override val status get() = if (!running) "off" else "${targets.size} nets, rx $rx" + (lastErr?.let { " ($it)" } ?: "")

    override fun start(onReceive: (ByteArray, Int, String) -> Unit) {
        running = true
        lock = wifi.createMulticastLock("resilane").apply { setReferenceCounted(false); acquire() }
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(port))
        }
        socket = s
        Thread({
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    s.receive(pkt)
                    rx++
                    onReceive(buf.copyOf(pkt.length), pkt.length, name)
                } catch (e: Exception) {
                    if (running) { lastErr = e.javaClass.simpleName; Thread.sleep(200) }
                }
            }
        }, "rl-udp-rx").apply { isDaemon = true }.start()
        Thread({
            var refreshed = 0L
            while (running) {
                val b = out.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val now = System.currentTimeMillis()
                if (now - refreshed > 3_000) { targets = broadcastTargets(); refreshed = now }
                for (a in targets) try {
                    s.send(DatagramPacket(b, b.size, a, port))
                    lastErr = null
                } catch (e: Exception) { lastErr = e.javaClass.simpleName }
            }
        }, "rl-udp-tx").apply { isDaemon = true }.start()
        RLog.i("Peer", "udp: listening on :$port")
    }

    override fun broadcast(bytes: ByteArray) { out.offer(bytes) }

    override fun stop() {
        running = false
        socket?.close(); socket = null
        lock?.release(); lock = null
    }

    private fun broadcastTargets(): List<InetAddress> {
        val set = linkedSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) if (ia.address is Inet4Address) ia.broadcast?.let { set += it }
            }
        }
        return set.toList()
    }

    companion object { const val PORT = 47800 }
}
