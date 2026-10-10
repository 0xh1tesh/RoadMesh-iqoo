package com.jsons.roadmesh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jsons.roadmesh.service.MeshService
import com.jsons.roadmesh.ui.RoadMeshScreen
import com.jsons.roadmesh.core.mesh.Mode
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class MainActivity : ComponentActivity() {
    private val app get() = application as RoadMeshApplication
    private val mesh get() = app.session
    private var requestedMode: Mode? = null
    private var starting = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requiredPermissions().all { p -> ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED }) startRequestedSession()
        else Toast.makeText(this, "Grant the requested camera, Bluetooth and precise location permissions to start this mode", Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedMode = savedInstanceState?.getString("requestedMode")?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            RoadMeshScreen(app, { requestStart(null) }, ::stopDrive, {
                startActivity(Intent(this, RoadMeshSceneActivity::class.java))
            }, startDrive = { requestStart(Mode.ROAD) }, stopDrive = ::stopDrive, startReplay = { requestStart(Mode.STAGE) })
        }
    }
    override fun onResume() { super.onResume(); app.observer.onForeground(true) }
    override fun onPause() { app.observer.onForeground(false); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        requestedMode?.let { outState.putString("requestedMode", it.name) }
        super.onSaveInstanceState(outState)
    }
    private fun requiredPermissions() = buildList {
        addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
        if ((requestedMode ?: mesh.preferences.profile.mode) == Mode.ROAD) addAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        if (requestedMode == Mode.ROAD) add(Manifest.permission.CAMERA)
    }
    private fun requestStart(mode: Mode?) {
        if (starting) return
        requestedMode = mode
        val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isEmpty()) startRequestedSession() else permissions.launch(missing.toTypedArray())
    }
    private fun startRequestedSession() {
        if (starting) return
        val target = requestedMode
        starting = true
        lifecycleScope.launch {
            try {
                if (target != null && mesh.state.value.mode != target) {
                    app.observer.stopLive(); app.observer.simulate(false)
                    if (mesh.state.value.running) {
                        stopService(Intent(this@MainActivity, MeshService::class.java))
                        withTimeout(5_000) { mesh.state.first { !it.running } }
                    }
                    mesh.mode(target)
                }
                startForegroundService(Intent(this@MainActivity, MeshService::class.java))
                when (target) {
                    Mode.ROAD -> app.observer.startLive()
                    Mode.STAGE -> app.observer.simulate(true)
                    null -> Unit
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
            } finally { starting = false }
        }
    }
    private fun stopDrive() {
        app.observer.stopLive(); app.observer.simulate(false)
        stopService(Intent(this, MeshService::class.java))
    }
}
