package com.jsons.roadmesh.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.core.mesh.Mode
import com.jsons.roadmesh.perception.ObserverState
import com.jsons.roadmesh.service.EventTrace
import com.jsons.roadmesh.service.SessionState
import kotlinx.coroutines.delay

/** UI states describe measured local activity; queueing is deliberately separate from reception. */
internal data class DrivePipelineStep(val label: String, val value: String, val active: Boolean)

internal fun drivePipelineSteps(s: SessionState, o: ObserverState, nowMs: Long): List<DrivePipelineStep> {
    val captured = o.capturedAtMs?.let { nowMs - it in 0..2_000 } == true
    val fix = s.me?.fix?.takeIf { it.valid() && !it.synthetic }
    return listOf(
        DrivePipelineStep("01  CAMERA", when {
            o.live -> o.cameraStatus
            o.simulating -> if (o.playing) "Recorded clip playing" else "Recorded clip paused"
            else -> "Ready to start"
        }, if (o.live) captured else o.simulating && o.playing),
        DrivePipelineStep("02  PERCEPTION", if (o.frames > 0) "${o.overlays.size} tracks · ${o.frames} analysed frames" else o.detector,
            o.frames > 0 && (captured || o.simulating && o.playing)),
        DrivePipelineStep("03  DECISION", o.analysisStatus, o.raised.isNotEmpty()),
        DrivePipelineStep("04  BLUETOOTH", when {
            !s.ready -> s.status
            o.live && s.mode != Mode.ROAD -> "Live input isolated from replay sharing"
            o.simulating && s.mode != Mode.STAGE -> "Replay input isolated from road sharing"
            o.live && fix == null -> "Local sensing · awaiting real position for sharing"
            else -> "${s.chain.count { !it.self }} peers heard · ${s.events.count { it.originated && it.sentAtMs != null }} local broadcast requests"
        }, s.ready && (o.live && s.mode == Mode.ROAD && fix != null || o.simulating && s.mode == Mode.STAGE)),
    )
}

@Composable internal fun DriveWelcome(state: SessionState, o: ObserverState, actions: Actions) {
    Text("AUTOMATIC COOPERATIVE PERCEPTION", fontFamily = Michroma, fontSize = 10.sp, letterSpacing = 1.sp, color = Tokens.Yellow)
    Text("Your camera sees.\nYour network shares.", fontFamily = Saira, fontSize = 34.sp, lineHeight = 37.sp, fontWeight = FontWeight.ExtraBold)
    Text("Mount the phone with its rear camera facing the road. Local YOLO tracks visible objects; motion over time creates event candidates that nearby phones can relay automatically.",
        color = Tokens.Muted, fontSize = 16.sp, lineHeight = 23.sp)
    Surface(shape = RoundedCornerShape(22.dp), color = Tokens.Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SENSE  →  REASON  →  RELAY", fontFamily = Michroma, fontSize = 12.sp, color = Tokens.Yellow)
            Text("Every participating phone can observe, relay and receive. Start once; no incident report tap.", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
            PrimaryButton("Start drive", actions.startDrive, Modifier.fillMaxWidth())
            Text("Set up while parked. Keep the app open and the camera view unobstructed. Start drive uses real positioning; replay remains a separate demo.",
                color = Tokens.Faint, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
    o.error?.let { Text(it, color = Tokens.Red, fontSize = 14.sp) }
    if (!state.ready && state.running) Text(state.status, color = Tokens.Yellow, fontSize = 13.sp)
}

@Composable internal fun DrivePipeline(state: SessionState, o: ObserverState) {
    val now by produceState(SystemClock.elapsedRealtime()) { while (true) { value = SystemClock.elapsedRealtime(); delay(500) } }
    Surface(shape = RoundedCornerShape(18.dp), color = Tokens.Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("AUTOMATIC PIPELINE", fontFamily = Michroma, fontSize = 10.sp, color = Tokens.Yellow)
                Text(if (o.live) "LIVE" else "REPLAY", color = Tokens.Yellow, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            drivePipelineSteps(state, o, now).forEach { step ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(step.label, fontSize = 10.sp, color = if (step.active) Tokens.Green else Tokens.Faint, fontWeight = FontWeight.Bold)
                    Text(step.value, color = Tokens.Secondary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            if (o.live && state.mode != Mode.ROAD) Text("Live camera is separate from this stage session. Switch to a road session to share live observations.", color = Tokens.Yellow, fontSize = 12.sp)
            if (!o.live) Text("Recorded input. Replay provenance is not live road evidence.", color = Tokens.Yellow, fontSize = 12.sp)
            Text("A queued candidate still passes mesh checks. Broadcast requests are local; only another phone's reception confirms delivery. Track IDs belong to this camera session.",
                color = Tokens.Faint, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

/** Only promote warnings the mesh's relevance checks actually announced, never arbitrary heard packets. */
internal fun recentDriveWarning(state: SessionState, nowMs: Long): EventTrace? = state.events.firstOrNull {
    !it.originated && it.announced && it.kind?.violation != true && nowMs - it.atMs in 0..10_000
}

@Composable internal fun DriveIncomingWarning(state: SessionState) {
    val now by produceState(SystemClock.elapsedRealtime()) { while (true) { value = SystemClock.elapsedRealtime(); delay(500) } }
    val event = recentDriveWarning(state, now) ?: return
    Surface(shape = RoundedCornerShape(16.dp), color = Tokens.Panel, border = BorderStroke(1.dp, Tokens.Yellow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("RECEIVED FROM THE MESH", fontFamily = Michroma, fontSize = 10.sp, color = Tokens.Yellow)
            Text(event.kind?.title() ?: "Road event", fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, color = Tokens.Yellow)
            Text(event.inference?.action ?: "Position relevance unknown. Stay attentive to the road.", color = Tokens.Paper, fontSize = 16.sp)
            Text("${event.hops + 1} radio hop(s) · ${((now - event.atMs) / 1000).coerceAtLeast(0)} s since local receipt", color = Tokens.Faint, fontSize = 12.sp)
        }
    }
}
