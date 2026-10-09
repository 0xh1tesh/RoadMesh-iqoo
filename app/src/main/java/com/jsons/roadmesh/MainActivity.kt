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
import com.jsons.roadmesh.service.MeshService
import com.jsons.roadmesh.ui.RoadMeshScreen
import com.jsons.roadmesh.core.mesh.Mode

class MainActivity : ComponentActivity() {
    private val mesh get() = (application as RoadMeshApplication).session
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requiredPermissions().all { p -> ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED }) startMesh()
        else Toast.makeText(this, "Bluetooth and fine location permissions are required", Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            RoadMeshScreen(mesh, ::requestStart, { stopService(Intent(this, MeshService::class.java)) }, {
                startActivity(Intent(this, RoadMeshSceneActivity::class.java))
            })
        }
    }
    private fun requiredPermissions() = buildList {
        addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
        if (mesh.preferences.profile.mode == Mode.ROAD) addAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private fun requestStart() {
        val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isEmpty()) startMesh() else permissions.launch(missing.toTypedArray())
    }
    private fun startMesh() {
        runCatching { startForegroundService(Intent(this, MeshService::class.java)) }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
    }
}
