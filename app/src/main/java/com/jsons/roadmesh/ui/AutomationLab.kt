package com.jsons.roadmesh.ui

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
import com.jsons.roadmesh.core.perception.AutomationScenario

/** Runs the production temporal evaluator. Inputs are labelled fixtures, never presented as live YOLO. */
@Composable internal fun AutomationLab() {
    val cases = remember { AutomationScenario.runAll() }
    var selected by remember { mutableStateOf("collective_slowdown") }
    var expanded by remember { mutableStateOf(false) }
    val result = cases.firstOrNull { it.id == selected } ?: cases.firstOrNull() ?: return
    Surface(shape = RoundedCornerShape(20.dp), color = Tokens.Panel, border = BorderStroke(1.dp, Tokens.Line), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AUTOMATIC EVENT LAB", fontFamily = Michroma, fontSize = 10.sp, letterSpacing = 1.sp, color = Tokens.Yellow)
            Text("From motion to a shared event.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Synthetic detector boxes and position-speed samples run through the same temporal evaluator used by the camera pipeline. No one taps an incident report.",
                color = Tokens.Muted, style = MaterialTheme.typography.bodyMedium)
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(result.title, color = Tokens.Yellow) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = Tokens.Raised) {
                    cases.forEach { scenario -> DropdownMenuItem(text = { Text(scenario.title) }, onClick = { selected = scenario.id; expanded = false }) }
                }
            }
            Text(result.description, color = Tokens.Secondary, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(color = Tokens.Line)
            Text("${result.frames} frames evaluated", fontSize = 12.sp, color = Tokens.Faint)
            Text(if (result.observedKinds.isEmpty()) "No automatic event raised" else result.observedKinds.joinToString { it.title() },
                color = if (result.observedKinds.isEmpty()) Tokens.Paper else Tokens.Yellow, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
            Text(if (result.passed) "Fixture expectation matched" else "Fixture expectation failed", color = if (result.passed) Tokens.Green else Tokens.Red, fontSize = 13.sp)
            Text("Expected: " + if (result.expectedKinds.isEmpty()) "no event" else result.expectedKinds.joinToString { it.title() }, color = Tokens.Faint, fontSize = 12.sp)
            result.events.firstOrNull()?.let { event ->
                Text(event.reason, color = Tokens.Secondary, fontSize = 14.sp, lineHeight = 19.sp)
                event.evidence.forEach { evidence -> Text(evidence, color = Tokens.Faint, fontSize = 12.sp, lineHeight = 17.sp) }
            }
            Text("${cases.count { it.passed }}/${cases.size} scenario expectations matched. Includes steady traffic, one-frame noise and missing speed, not only hazard cases.",
                color = Tokens.Muted, fontSize = 12.sp, lineHeight = 17.sp)
            Text("This tests temporal event logic. It does not establish camera detection accuracy, measured braking by another vehicle, or Bluetooth delivery.", color = Tokens.Faint, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}
