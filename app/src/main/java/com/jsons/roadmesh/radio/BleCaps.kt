package com.jsons.roadmesh.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Handler
import android.os.Looper

/** What the Bluetooth controller says it can do. Read-only, needs no runtime permission. */
data class BleCaps(
    val hasAdapter: Boolean,
    val enabled: Boolean,
    val extendedAdv: Boolean,
    val le2mPhy: Boolean,
    val codedPhy: Boolean,
    val periodicAdv: Boolean,
    val maxAdvDataLength: Int,
    val multipleAdv: Boolean,
    val offloadedFiltering: Boolean,
)

object BleProbe {

    fun readCaps(context: Context): BleCaps {
        val adapter: BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) return BleCaps(false, false, false, false, false, false, 0, false, false)
        return BleCaps(
            hasAdapter = true,
            enabled = adapter.isEnabled,
            extendedAdv = adapter.isLeExtendedAdvertisingSupported,
            le2mPhy = adapter.isLe2MPhySupported,
            codedPhy = adapter.isLeCodedPhySupported,
            periodicAdv = adapter.isLePeriodicAdvertisingSupported,
            maxAdvDataLength = adapter.leMaximumAdvertisingDataLength,
            multipleAdv = adapter.isMultipleAdvertisementSupported,
            offloadedFiltering = adapter.isOffloadedFilteringSupported,
        )
    }

    /**
     * Starts non-connectable advertising sets one after another until the controller refuses,
     * then stops them all. Needs BLUETOOTH_ADVERTISE. Reports how many sets ran at once.
     */
    @SuppressLint("MissingPermission")
    fun countAdvertisingSets(
        context: Context,
        maxTry: Int = 10,
        onDone: (count: Int, note: String) -> Unit,
    ): () -> Unit {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (adapter == null || advertiser == null) {
            onDone(0, "no advertiser (Bluetooth off or unsupported)")
            return {}
        }
        val extended = adapter.isLeExtendedAdvertisingSupported
        val mode = if (extended) "extended" else "legacy"
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(!extended)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_LOW)
            .build()
        val main = Handler(Looper.getMainLooper())
        val callbacks = mutableListOf<AdvertisingSetCallback>()
        var started = 0
        var finished = false
        var timeout: Runnable? = null

        fun finish(note: String) {
            if (finished) return
            finished = true
            timeout?.let { main.removeCallbacks(it) }
            callbacks.forEach { runCatching { advertiser.stopAdvertisingSet(it) } }
            onDone(started, note)
        }

        fun startNext(index: Int) {
            if (finished) return
            if (index >= maxTry) {
                finish("reached the test limit of $maxTry (mode: $mode)")
                return
            }
            val data = AdvertiseData.Builder()
                .addManufacturerData(0xFFFF, byteArrayOf(0x52, index.toByte()))
                .build()
            val callback = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                    if (finished) { runCatching { advertiser.stopAdvertisingSet(this) }; return }
                    if (status == ADVERTISE_SUCCESS) {
                        started += 1
                        main.post { startNext(index + 1) }
                    } else {
                        finish("set ${index + 1} refused, status $status (2 = too many advertisers; mode: $mode)")
                    }
                }
            }
            callbacks += callback
            try {
                advertiser.startAdvertisingSet(params, data, null, null, null, callback)
            } catch (e: Exception) {
                finish("exception on set ${index + 1}: ${e.javaClass.simpleName} ${e.message}")
            }
        }

        timeout = Runnable { finish("timeout after 15 s") }
        main.postDelayed(timeout!!, 15_000)
        startNext(0)
        return { if (Looper.myLooper() == Looper.getMainLooper()) finish("cancelled") else main.post { finish("cancelled") }; Unit }
    }
}
