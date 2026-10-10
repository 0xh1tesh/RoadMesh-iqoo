package com.jsons.roadmesh.telemetry

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jsons.roadmesh.RoadMeshApplication
import com.jsons.roadmesh.ui.RoadMeshTheme

class MeshDeskActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val desk = (application as RoadMeshApplication).meshDesk
        setContent { RoadMeshTheme { Surface(Modifier.fillMaxSize()) {
            val status by desk.status.collectAsState()
            var host by remember { mutableStateOf(desk.host) }; var port by remember { mutableStateOf(desk.port) }
            var token by remember { mutableStateOf("") }; var demo by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = { finish() }) { Text("Back to RESILANE") }
                Text("Mesh Desk", style = MaterialTheme.typography.headlineLarge)
                Text("Watch this phone as a car on your laptop. Connect both devices to the same trusted Wi-Fi or hotspot, and start the mesh.")
                OutlinedTextField(host,{host=it},label={Text("Laptop IPv4 address")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(port,{port=it},label={Text("Port")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(token,{token=it},label={Text("Pairing token")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
                Row { Checkbox(demo,{demo=it}); Text("Use synthetic stage positions (demo only)",Modifier.padding(top=12.dp)) }
                Text("Live: GPS moves the car; phone heading is used when stationary. GPS cannot reliably resolve small indoor movements or lane-level overtakes. Stage mode is explicitly labeled DEMO.",style=MaterialTheme.typography.bodySmall)
                Button(onClick={permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))},modifier=Modifier.fillMaxWidth()) { Text("Allow precise location") }
                Button(onClick={runCatching { desk.connect(host.trim(),port.trim(),token.trim(),demo) }.onSuccess { error="" }.onFailure { error=it.message ?: "Cannot connect" }},modifier=Modifier.fillMaxWidth()) { Text("Connect desk") }
                OutlinedButton(onClick={desk.disconnect()},modifier=Modifier.fillMaxWidth()) { Text("Stop sharing") }
                Text(status,color=MaterialTheme.colorScheme.primary)
                if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
                Text("This sends position, direction and mesh events over local unencrypted HTTP. No video is sent. The token stays in memory; sharing stops on app restart. Only use a trusted network.",style=MaterialTheme.typography.bodySmall)
            }
        } } }
    }
}
