package com.jsons.roadmesh.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import com.jsons.roadmesh.MainActivity
import com.jsons.roadmesh.RoadMeshApplication
import com.jsons.roadmesh.core.mesh.Mode

class MeshService : Service() {
    private val session get() = (application as RoadMeshApplication).session
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val renewWakeLock = object : Runnable {
        override fun run() {
            wakeLock?.acquire(10 * 60_000L)
            handler.postDelayed(this, 5 * 60_000L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "RESILANE mesh session", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MeshService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("RESILANE · ${session.state.value.role.title}").setContentText("BLE mesh session is active")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        try {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (session.preferences.profile.mode == Mode.ROAD) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(1, notification, type)
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoadMesh:MeshSession").apply { setReferenceCounted(false) }
                renewWakeLock.run()
            }
            session.begin { stopSelf() }
        } catch (e: Exception) {
            session.fail(e.message ?: "Service failed"); stopSelf()
        }
        // Explicit user start keeps role/configuration visible after an OS process kill.
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(renewWakeLock)
        session.stop(); wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val STOP = "com.jsons.roadmesh.STOP"; private const val CHANNEL = "roadmesh-session" }
}
