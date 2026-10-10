package com.jsons.roadmesh.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.ArrayDeque

/** One extended advertising set, multiplexed by a single TX coroutine.
 * Broadcast completion means the local controller ran the burst, never remote delivery.
 */
@SuppressLint("MissingPermission")
class BleHazardTransport(context: Context, private val report: (String) -> Unit,
    private val availability: (Boolean, String) -> Unit = { _, _ -> }) : HazardTransport {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val thread = HandlerThread("roadmesh-ble").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var set: AdvertisingSet? = null
    private var onFrame: (ByteArray) -> Unit = {}
    @Volatile private var closed = false
    @Volatile private var startedAt = 0L
    @Volatile private var scanning = false
    @Volatile private var nextScanAttempt = 0L
    private val scanStarts = ArrayDeque<Long>()
    @Volatile private var started = CompletableDeferred<AdvertisingSet>()
    @Volatile private var beaconSet: AdvertisingSet? = null
    @Volatile private var beaconStarted: CompletableDeferred<AdvertisingSet>? = null
    @Volatile private var beaconData: CompletableDeferred<Int>? = null
    @Volatile private var beaconUnavailable = false
    @Volatile private var dataReady: CompletableDeferred<Int>? = null
    @Volatile private var enableReady: CompletableDeferred<Int>? = null
    @Volatile private var ended: CompletableDeferred<Unit>? = null

    private val advertisingCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (closed) { runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(this) }; return }
            if (status == ADVERTISE_SUCCESS && advertisingSet != null) started.complete(advertisingSet)
            else started.completeExceptionally(IllegalStateException("extended advertising start: $status"))
        }
        override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) { dataReady?.complete(status) }
        override fun onAdvertisingEnabled(advertisingSet: AdvertisingSet?, enable: Boolean, status: Int) {
            enableReady?.complete(status)
            if (!enable) {
                if (status == ADVERTISE_SUCCESS) ended?.complete(Unit)
                else ended?.completeExceptionally(IllegalStateException("advertising stop failed: $status"))
            }
        }
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (closed) return
            val raw = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
            val magic = raw.firstOrNull()?.toInt()?.and(255)
            if (magic == 0x52 || magic == 0x53) onFrame(raw.copyOf())
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
        override fun onScanFailed(errorCode: Int) {
            if (closed) return
            scanning = false; nextScanAttempt = SystemClock.elapsedRealtime() + 2_000
            report("scan failed: $errorCode"); availability(false, "Scan failed: $errorCode · retrying")
        }
    }

    override suspend fun start(onFrame: (ByteArray) -> Unit) {
        this.onFrame = onFrame
        val a = adapter ?: error("Bluetooth unavailable")
        check(a.isEnabled) { "Turn Bluetooth on" }
        check(a.isLeExtendedAdvertisingSupported) { "Extended BLE advertising is required for this envelope. Check every phone." }
        check(a.leMaximumAdvertisingDataLength >= 160) { "Extended advertisement payload too small" }
        openBurstSet()
        startScan(SystemClock.elapsedRealtime())
        report("BLE ready · extended · burst set + presence set")
    }

    /** Android rejects intervals under INTERVAL_LOW (160 units, 100 ms) with an exception, so never ask for less. */
    private fun params(interval: Int) = AdvertisingSetParameters.Builder().setLegacyMode(false).setConnectable(false).setScannable(false)
        .setInterval(interval.coerceAtLeast(AdvertisingSetParameters.INTERVAL_LOW)).setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH).build()

    /** The set hazard and query bursts go out on, at the fastest interval Android allows (100 ms). */
    private suspend fun openBurstSet() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: error("BLE advertiser unavailable")
        started = CompletableDeferred()
        advertiser.startAdvertisingSet(params(AdvertisingSetParameters.INTERVAL_LOW), AdvertiseData.Builder().build(), null, null, null, advertisingCallback, handler)
        set = withTimeout(3_000) { started.await() }
        enabled(false)
    }

    private val beaconCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (closed) { runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(this) }; return }
            if (status == ADVERTISE_SUCCESS && advertisingSet != null) beaconStarted?.complete(advertisingSet)
            else beaconStarted?.completeExceptionally(IllegalStateException("presence set start: $status"))
        }
        override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) { beaconData?.complete(status) }
    }

    override suspend fun beacon(bytes: ByteArray): Boolean {
        if (closed || beaconUnavailable) return false
        val data = AdvertiseData.Builder().addManufacturerData(COMPANY_ID, bytes).build()
        val current = beaconSet
        if (current == null) {
            val advertiser = adapter?.bluetoothLeAdvertiser ?: return false
            val ready = CompletableDeferred<AdvertisingSet>().also { beaconStarted = it }
            // Always on, every 200 ms, until the session stops. A second set is optional hardware; without it the
            // session falls back to short presence bursts on the shared set.
            advertiser.startAdvertisingSet(params(320), data, null, null, null, 0, 0, beaconCallback, handler)
            beaconSet = runCatching { withTimeout(2_000) { ready.await() } }.getOrElse {
                beaconUnavailable = true; report("presence set unavailable (${it.message}); presence uses bursts"); return false
            }
            return true
        }
        val done = CompletableDeferred<Int>().also { beaconData = it }
        current.setAdvertisingData(data)
        val status = runCatching { withTimeout(300) { done.await() } }.getOrDefault(-1)
        beaconData = null
        return status == AdvertisingSetCallback.ADVERTISE_SUCCESS
    }

    override suspend fun recover() {
        report("recovering burst set")
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advertisingCallback) }
        set = null; dataReady = null; enableReady = null; ended = null
        kotlinx.coroutines.delay(150)
        openBurstSet()
        report("burst set recovered")
    }

    override suspend fun transmit(bytes: ByteArray, relay: Boolean): Int {
        check(!closed) { "transport closed" }
        val ad = set ?: error("BLE not ready")
        val data = CompletableDeferred<Int>().also { dataReady = it }
        ad.setAdvertisingData(AdvertiseData.Builder().addManufacturerData(COMPANY_ID, bytes).build())
        check(withTimeout(300) { data.await() } == AdvertisingSetCallback.ADVERTISE_SUCCESS) { "advertising data rejected" }
        dataReady = null
        // Fixed burst counts limit channel load. Origin repeats longer to tolerate packet loss.
        val events = if (relay) 4 else 10
        val finish = CompletableDeferred<Unit>().also { ended = it }
        enabled(true, events)
        var automaticallyDisabled = false
        try {
            withTimeout(if (relay) 1_000L else 1_800L) { finish.await() }
            automaticallyDisabled = true
        }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // Some controllers omit the automatic-disable callback. Explicitly stop before reuse.
            report("burst timeout · explicit disable")
        } finally {
            if (!automaticallyDisabled) enabled(false)
            ended = null
        }
        return events
    }

    private suspend fun enabled(value: Boolean, events: Int = 0) {
        val ready = CompletableDeferred<Int>().also { enableReady = it }
        set?.enableAdvertising(value, 0, events) ?: error("BLE set unavailable")
        check(withTimeout(500) { ready.await() } == AdvertisingSetCallback.ADVERTISE_SUCCESS) { "advertising enable/disable failed" }
        enableReady = null
    }

    private fun startScan(now: Long) {
        if (now < nextScanAttempt) return
        while (scanStarts.isNotEmpty() && now - scanStarts.first > 30_000) scanStarts.removeFirst()
        if (scanStarts.size >= 4) {
            nextScanAttempt = scanStarts.first + 30_001
            report("scan restart budget exhausted; retrying after window")
            availability(false, "Scan retry waiting for restart budget")
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: error("BLE scanner unavailable")
        // 0x52 (hazard envelope) and 0x53 (control frame) differ only in the lowest bit.
        val filter = ScanFilter.Builder().setManufacturerData(COMPANY_ID, byteArrayOf(0x52), byteArrayOf(0xfe.toByte())).build()
        val settings = ScanSettings.Builder().setLegacy(false).setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0).build()
        scanStarts.addLast(now)
        scanning = true; startedAt = now
        availability(true, "BLE ready")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    override fun maintain(nowMs: Long) {
        if (closed || set == null) return
        if (!scanning || nowMs - startedAt > 8 * 60_000) {
            runCatching {
                if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                scanning = false
                startScan(nowMs)
            }.onFailure {
                scanning = false
                nextScanAttempt = nowMs + 2_000
                report("scan restart failed: ${it.message}"); availability(false, "Scan restart failed · retrying")
            }
        }
    }

    override fun close() {
        closed = true
        onFrame = {}
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(advertisingCallback) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(beaconCallback) }
        set = null; beaconSet = null; scanning = false
        thread.quitSafely()
    }
    companion object { const val COMPANY_ID = 0xffff }
}
