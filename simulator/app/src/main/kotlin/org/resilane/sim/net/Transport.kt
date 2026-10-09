package org.resilane.sim.net

import org.resilane.core.proto.Codec
import org.resilane.core.proto.DedupeWindow
import org.resilane.core.proto.Envelope
import org.resilane.sim.RLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A way of moving ResiLane envelopes between phones. Implementations: [NearbyTransport]
 * (Bluetooth/Wi-Fi Direct, no infrastructure) and [UdpTransport] (shared Wi-Fi or hotspot).
 *
 * MotorMesh integration point: a MotorMesh event-relay adapter implements this same interface,
 * carrying `bytes` as an opaque event payload. The envelope already has origin, msgSeq and ttl,
 * which is what relaying and de-duplication need; [MeshRouter] handles both.
 */
interface PeerTransport {
    val name: String
    /** Human-readable link status for the HUD, e.g. "3 connected". */
    val status: String
    fun start(onReceive: (bytes: ByteArray, len: Int, via: String) -> Unit)
    fun broadcast(bytes: ByteArray)
    fun stop()
}

/**
 * Fans outgoing messages to every transport and funnels incoming ones through a per-origin replay
 * window, so a pose that arrives over both Nearby and UDP (or, later, via a relay) is handled once.
 * Messages with ttl > 1 are re-broadcast with ttl - 1 (multi-hop event relay); with every phone
 * one hop away, poses are sent with ttl 1 and never forwarded.
 */
class MeshRouter(private val selfId: Long, private val onMessage: (Envelope, via: String) -> Unit) {
    private val transports = CopyOnWriteArrayList<PeerTransport>()
    private val dedupe = DedupeWindow()
    @Volatile var rxCount = 0L; private set
    @Volatile var txCount = 0L; private set
    @Volatile var dupCount = 0L; private set

    fun add(t: PeerTransport) {
        transports += t
        t.start { bytes, len, via -> receive(bytes, len, via) }
        RLog.i("Net", "transport ${t.name} started")
    }

    fun remove(name: String) {
        transports.filter { it.name == name }.forEach { it.stop(); transports.remove(it); RLog.i("Net", "transport $name stopped") }
    }

    fun has(name: String) = transports.any { it.name == name }

    fun send(e: Envelope) {
        val bytes = Codec.encode(e)
        txCount++
        for (t in transports) runCatching { t.broadcast(bytes) }.onFailure { RLog.w("Net", "${t.name} send: $it") }
    }

    private fun receive(bytes: ByteArray, len: Int, via: String) {
        val e = Codec.decode(bytes, len) ?: return
        if (e.origin == selfId) return
        if (!dedupe.firstSeen(e.origin, e.msgSeq)) { dupCount++; return }
        rxCount++
        onMessage(e, via)
        if (e.ttl > 1) send(e.copy(ttl = e.ttl - 1))
    }

    fun status(): String = transports.joinToString("  ") { "${it.name}: ${it.status}" }

    fun stopAll() { transports.forEach { it.stop() }; transports.clear() }
}
