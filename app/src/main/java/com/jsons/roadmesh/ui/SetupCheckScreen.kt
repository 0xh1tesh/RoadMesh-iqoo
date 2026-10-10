package com.jsons.roadmesh.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsons.roadmesh.diag.DeviceProbe
import com.jsons.roadmesh.diag.Probe
import com.jsons.roadmesh.diag.REQUIRED_PERMISSIONS
import com.jsons.roadmesh.radio.BleProbe

/** The first-hour probe screen: reads what this phone can do and exports it as JSON. */
@SuppressLint("BatteryLife")
@Composable
fun SetupCheckScreen(canProbeRadio: Boolean = true) {
    val context = LocalContext.current
    var staticProbes by remember { mutableStateOf(DeviceProbe.readStatic(context)) }
    var ttsProbes by remember { mutableStateOf<List<Probe>>(emptyList()) }
    var advProbe by remember { mutableStateOf<Probe?>(null) }
    var status by remember { mutableStateOf("ready") }
    var probing by remember { mutableStateOf(false) }
    var cancelProbe by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(Unit) { onDispose { cancelProbe?.invoke() } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        staticProbes = DeviceProbe.readStatic(context)
        status = "permissions updated"
    }

    LaunchedEffect(Unit) {
        DeviceProbe.readTts(context) { ttsProbes = it }
    }

    val all = staticProbes + ttsProbes + listOfNotNull(advProbe)

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("RESILANE setup check", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { permissionLauncher.launch(REQUIRED_PERMISSIONS) }) { Text("Permissions", fontSize = 12.sp) }
            Button(onClick = {
                probing = true
                status = "counting advertising sets..."
                cancelProbe = BleProbe.countAdvertisingSets(context) { count, note ->
                    probing = false
                    advProbe = Probe("BLE advertising sets at once", "$count ($note)", count >= 1)
                    status = "advertising sets: $count"
                }
            }, enabled = canProbeRadio && !probing) { Text("Count adv sets", fontSize = 12.sp) }
            Button(onClick = {
                staticProbes = DeviceProbe.readStatic(context)
                DeviceProbe.readTts(context) { ttsProbes = it }
                status = "refreshed"
            }) { Text("Refresh", fontSize = 12.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
                    .onFailure { status = "battery dialog failed: ${it.message}" }
            }) { Text("Battery exempt", fontSize = 12.sp) }
            Button(onClick = { status = DeviceProbe.exportJson(context, all) }) { Text("Export JSON", fontSize = 12.sp) }
        }
        Text("status: $status", fontSize = 12.sp)
        if (!canProbeRadio) Text("Stop the mesh before probing radio capacity. This app uses one advertising set.", fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(all) { p ->
                val color = when (p.ok) {
                    true -> Color(0xFF1B7F3B)
                    false -> Color(0xFFB3261E)
                    null -> MaterialTheme.colorScheme.onSurface
                }
                Text("${p.name}: ${p.value}", color = color, fontSize = 12.sp)
            }
        }
    }
}
