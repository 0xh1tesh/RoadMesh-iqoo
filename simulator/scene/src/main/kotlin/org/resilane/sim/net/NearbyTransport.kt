package org.resilane.sim.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.resilane.sim.RLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Nearby Connections, P2P_CLUSTER: every phone advertises and discovers at once, forming a full
 * mesh of direct links with no access point or server.
 *
 * Both sides of a pair discover each other; to avoid two crossing connection requests, only the
 * phone with the lower node id dials. If nothing has connected 8 s after discovery, the other side
 * dials too (covers one phone discovering much later than the other).
 */
class NearbyTransport(context: Context, private val selfId: Long, private val displayName: () -> String) : PeerTransport {
    override val name = "nearby"
    private val client = Nearby.getConnectionsClient(context)
    private val main = Handler(Looper.getMainLooper())
    private val names = ConcurrentHashMap<String, String>() // endpointId -> endpoint name
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private var onReceive: ((ByteArray, Int, String) -> Unit)? = null
    @Volatile private var running = false

    override val status: String get() = "${connected.size} linked" + if (pending.isNotEmpty()) " +${pending.size} pending" else ""

    private val endpointName get() = "${displayName().take(20)}#%016x".format(selfId)

    private fun idOf(endpointName: String): Long? =
        endpointName.substringAfterLast('#', "").takeIf { it.length == 16 }?.toULongOrNull(16)?.toLong()

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val b = payload.asBytes() ?: return
            onReceive?.invoke(b, b.size, name)
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            RLog.i("Peer", "nearby: accepting ${info.endpointName} ($endpointId)")
            client.acceptConnection(endpointId, payloads)
                .addOnFailureListener { RLog.w("Peer", "nearby: accept failed $endpointId: $it") }
            pending += endpointId
            names[endpointId] = info.endpointName
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pending -= endpointId
            val nm = names[endpointId] ?: endpointId
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> { connected += endpointId; RLog.i("Peer", "nearby: CONNECTED $nm") }
                else -> RLog.w("Peer", "nearby: connection to $nm failed: ${ConnectionsStatusCodes.getStatusCodeString(result.status.statusCode)}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected -= endpointId
            RLog.w("Peer", "nearby: disconnected ${names[endpointId] ?: endpointId}")
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val theirId = idOf(info.endpointName)
            RLog.i("Peer", "nearby: found ${info.endpointName} ($endpointId)")
            if (theirId == null || selfId < theirId) dial(endpointId, info.endpointName)
            else main.postDelayed({ if (running && endpointId !in connected && endpointId !in pending) dial(endpointId, info.endpointName) }, 8_000)
        }
        override fun onEndpointLost(endpointId: String) { RLog.i("Peer", "nearby: lost endpoint $endpointId") }
    }

    private fun dial(endpointId: String, nm: String) {
        if (endpointId in connected || endpointId in pending) return
        pending += endpointId
        client.requestConnection(endpointName, endpointId, lifecycle)
            .addOnFailureListener { e ->
                pending -= endpointId
                RLog.w("Peer", "nearby: request to $nm failed: $e")
                // Retry while the endpoint is still around; ALREADY_CONNECTED is fine.
                if (running && !e.toString().contains("8003")) main.postDelayed({ if (running) dial(endpointId, nm) }, 3_000)
            }
    }

    override fun start(onReceive: (ByteArray, Int, String) -> Unit) {
        this.onReceive = onReceive
        running = true
        val strat = Strategy.P2P_CLUSTER
        client.startAdvertising(endpointName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strat).build())
            .addOnSuccessListener { RLog.i("Peer", "nearby: advertising as $endpointName") }
            .addOnFailureListener { RLog.e("Peer", "nearby: advertise failed (permissions? Bluetooth/location on?)", it) }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strat).build())
            .addOnSuccessListener { RLog.i("Peer", "nearby: discovering") }
            .addOnFailureListener { RLog.e("Peer", "nearby: discovery failed", it) }
    }

    override fun broadcast(bytes: ByteArray) {
        val ids = connected.toList()
        if (ids.isNotEmpty()) client.sendPayload(ids, Payload.fromBytes(bytes))
    }

    override fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        connected.clear(); pending.clear(); names.clear()
    }

    companion object { const val SERVICE_ID = "org.resilane.sim.v1" }
}
