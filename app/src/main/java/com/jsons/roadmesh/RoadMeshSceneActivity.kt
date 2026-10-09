package com.jsons.roadmesh

import android.os.Bundle
import android.widget.Toast
import com.jsons.roadmesh.core.mesh.*
import org.resilane.sim.SimulatorActivity
import org.resilane.sim.api.SceneEvent

/** Scene knows only SceneEvent; this app adapter maps it to a local hazard source. */
class RoadMeshSceneActivity : SimulatorActivity() {
    override val pitchMode = true
    private val mesh get() = (application as RoadMeshApplication).session
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!mesh.state.value.ready || mesh.state.value.role != Role.DETECTOR || mesh.state.value.mode != Mode.STAGE) {
            Toast.makeText(this, "Start Detector in Stage mode first", Toast.LENGTH_LONG).show(); finish()
        }
    }
    override fun onSceneEvent(event: SceneEvent) {
        if (event is SceneEvent.BrakeStarted) {
            val sent = mesh.publish(LocalHazard(HazardKind.BRAKING, Provenance.DEMO, event.eventId))
            Toast.makeText(this, if (sent) "Virtual brake → BLE queue" else "Mesh unavailable · return and restart", Toast.LENGTH_SHORT).show()
        }
    }
}
