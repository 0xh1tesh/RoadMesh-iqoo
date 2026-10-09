package com.jsons.roadmesh.sensing

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.jsons.roadmesh.core.mesh.*

/** Road mode never substitutes a synthetic fix when GNSS is missing or stale. */
class AndroidPositionSource(context: Context) : PositionSource, LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    @Volatile private var last: Location? = null
    @SuppressLint("MissingPermission")
    fun start() { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0f, this, Looper.getMainLooper()) }
    fun close() { manager.removeUpdates(this); last = null }
    override fun onLocationChanged(location: Location) { if (!location.isMock) last = Location(location) }
    override fun current(): GeoFix? {
        val l = last ?: return null
        if (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos > 5_000_000_000L || !l.hasAccuracy() || l.accuracy > 30) return null
        return GeoFix(l.latitude, l.longitude, if (l.hasBearing()) l.bearing.toDouble() else null,
            if (l.hasSpeed()) l.speed.toDouble() else null, false)
    }
}
