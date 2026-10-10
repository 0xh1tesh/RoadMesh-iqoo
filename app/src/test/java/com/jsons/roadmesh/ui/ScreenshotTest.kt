package com.jsons.roadmesh.ui

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.jsons.roadmesh.Question
import com.jsons.roadmesh.RoadMeshApplication
import com.jsons.roadmesh.copilot.CopilotLine
import com.jsons.roadmesh.copilot.CopilotState
import com.jsons.roadmesh.core.insight.NodeInference
import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.core.perception.*
import com.jsons.roadmesh.perception.DriverState
import com.jsons.roadmesh.perception.ObserverState
import com.jsons.roadmesh.perception.SosSent
import com.jsons.roadmesh.perception.Overlay
import com.jsons.roadmesh.perception.Raised
import com.jsons.roadmesh.service.EventTrace
import com.jsons.roadmesh.service.SessionState
import com.jsons.roadmesh.core.road.*
import com.jsons.roadmesh.core.swarm.SwarmScenarioConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the screens on the JVM so the design can be reviewed without a phone. Run with -PwithScreenshots=true. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<RoadMeshApplication>()
    private val actions = Actions({}, {}, {}, {}, {}, {}, {}, {}, {})
    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/screens/$name.png")

    private val ready = CopilotState(status = CopilotState.Status.READY, detail = "Gemma 2B on CPU")
    private val now get() = SystemClock.elapsedRealtime()

    /** The real frame and YOLO11n detections at 8.3 s of the bundled clip, judged by the real rules. */
    private fun observed(): ObserverState {
        val rows = listOf(
            "2,0.779,0.4970,0.4838,0.6038,0.6627", "2,0.727,0.6437,0.4041,0.9997,0.7981", "0,0.607,0.2871,0.5084,0.3248,0.6363",
            "3,0.535,0.2814,0.5683,0.3239,0.6565", "2,0.427,0.5928,0.5268,0.6170,0.5830",
        ).map { it.split(',') }.map { SeenObject(ObjectClass.coco(it[0].toInt()), it[1].toFloat(), it[2].toFloat(), it[3].toFloat(), it[4].toFloat(), it[5].toFloat()) }
        val rules = DashcamHazards()
        val vel = listOf(-0.18f to 0.05f, -0.22f to -0.03f, 0f to 0.02f, 0.01f to 0.02f, 0.02f to 0f)
        val call = HazardCall(HazardKind.VEHICLE_CLOSE, 8.2, "car in the lane only 3.2 m ahead", rows[1], 3.2)
        return ObserverState(simulating = true, playing = true, detector = "YOLO11n · XNNPACK", inferMs = 23, fps = 9.6, clipS = 8.3,
            overlays = rows.mapIndexed { i, o -> Overlay(o, rules.inLane(o), rules.distanceM(o), rules.lateralM(o), i == 1, i + 3, vel[i].first, vel[i].second) },
            lead = Lead(rows[1], 3.2, 1.4, null), raised = listOf(Raised(call, true, now)),
            counts = mapOf(ObjectClass.CAR to 3, ObjectClass.PERSON to 1, ObjectClass.MOTORCYCLE to 1), objects = rows)
    }

    private fun event(origin: Long, via: Long, toOrigin: Double, relayed: Boolean) = EventTrace(
        MessageId(NodeId(origin), 7, 3), HazardKind.PEDESTRIAN, HazardDetail(16, VehicleType.CAR, 0.6), false, NodeId(via), now - 2_000, toOrigin,
        NodeInference.at(HazardKind.PEDESTRIAN, HazardDetail(16, VehicleType.CAR, 0.6), toOrigin, 10.0), relayedAtMs = if (relayed) now - 1_700 else null,
        announced = true, hops = (via - origin).toInt(), canRelay = via == origin)

    @Test fun onboarding() {
        app.preferences.onboarded = false
        compose.mainClock.autoAdvance = false
        compose.setContent { RoadMeshScreen(app, {}, {}, {}) }
        compose.mainClock.advanceTimeBy(1_700)
        shot("1-onboarding-intro")
        compose.onNodeWithText("Get started").performClick(); compose.mainClock.advanceTimeBy(1_200)
        shot("2-onboarding-vehicle")
        compose.onNodeWithText("Continue").performClick(); compose.mainClock.advanceTimeBy(1_200)
        shot("3-onboarding-convoy")
    }

    @Test fun observeIdle() {
        app.preferences.onboarded = true
        compose.mainClock.autoAdvance = false
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) { MainTabs(app, SessionState(Role.OBSERVER, Mode.STAGE, running = true, ready = true, node = NodeId(10), chain = chain(0)), CopilotState(), ObserverState(), emptyList(), actions) } } }
        compose.mainClock.advanceTimeBy(1_500)
        shot("4-observe-idle")
    }

    @Test fun driveStartsWithOneActionAndReplayStaysAvailable() {
        var starts = 0
        var replayStarts = 0
        val driveActions = Actions({}, {}, {}, {}, {}, {}, {}, {}, {}, startDrive = { starts++ }, startReplay = { replayStarts++ })
        // Scroll changes node coordinates. Let layout and click recompositions settle during interaction.
        compose.mainClock.autoAdvance = true
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) {
            MainTabs(app, SessionState(Role.RELAY, Mode.ROAD), CopilotState(), ObserverState(), emptyList(), driveActions)
        } } }
        compose.waitForIdle()
        compose.onNodeWithText("Start drive").performScrollTo().performClick()
        org.junit.Assert.assertEquals(1, starts)
        compose.onNodeWithText("Open replay lab").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Hide replay lab").assertExists()
        com.jsons.roadmesh.perception.DashcamObserver.CLIPS.forEach { (_, label) -> compose.onNodeWithText(label).assertExists() }
        compose.onNodeWithText("Start replay demo").performScrollTo().performClick()
        org.junit.Assert.assertEquals(1, replayStarts)
        compose.mainClock.autoAdvance = false
        shot("17-drive-replay-choice")
    }

    @Test fun automaticPipelineShowsLocalOnlyWithoutPosition() {
        val observer = observed().copy(simulating = false, live = true, frames = 84, capturedAtMs = now,
            cameraStatus = "Rear camera streaming", analysisStatus = "Rapid closing candidate · sustained track growth")
        compose.mainClock.autoAdvance = false
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DrivePipeline(SessionState(Role.RELAY, Mode.ROAD, running = true, ready = true), observer)
            }
        } } }
        compose.onNodeWithText("Local sensing · awaiting real position for sharing").assertExists()
        shot("18-live-pipeline-position-unknown")
    }

    @Test fun automatedEventLabUsesComputedFixtures() {
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { AutomationLab() }
        } } }
        compose.onNodeWithText("AUTOMATIC EVENT LAB").assertExists()
        compose.onNodeWithText("Fixture expectation matched").assertExists()
        shot("19-automatic-event-lab")
    }

    @Test fun observeSimulating() {
        val frame = BitmapFactory.decodeStream(javaClass.getResourceAsStream("/cv_raman_8s.jpg")).asImageBitmap()
        val o = observed()
        val copilot = ready.copy(lines = mapOf("hazard:8.2" to CopilotLine("hazard:8.2", "A car has cut in about 3 metres ahead; ease off and let the gap open before you", false),
            "q:1" to CopilotLine("q:1", "A pothole was reported ahead; watch the road surface and prepare to slow.", true, 2600)))
        val q = listOf(Question("q:1", "Any potholes ahead?", "A pothole was reported ahead. Watch the road surface and prepare to slow.", "", now))
        compose.setContent {
            RoadMeshTheme { Surface(color = Tokens.Ink) {
                Column(Modifier.fillMaxSize()) {
                    SignalEdge(Tokens.Yellow)
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Tokens.Ink)) {
                        Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        DetectionOverlay3D(o.overlays)
                    }
                    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ObservePanel(app, o, copilot, q, actions)
                    }
                }
            } }
        }
        shot("5-observe-simulating")
    }

    /** Five phones on a table: B is this phone, relaying for the observer A to C, D and E. */
    private fun chain(self: Int): List<Member> = (0..4).map { i ->
        Member(NodeId(10L + i), 1_000L + i, i == 0, listOf(VehicleType.CAR, VehicleType.TWO_WHEELER, VehicleType.AUTO, VehicleType.CAR, VehicleType.BUS_TRUCK)[i],
            stageFix(i), now - 400L * i, i, if (i == 0) Role.OBSERVER else if (i == 4) Role.RECEIVER else Role.RELAY, i == self, 3)
    }
    private fun known(): List<KnownHazard> = listOf(
        KnownHazard(HazardKind.POTHOLE, stageFix(0).lat + 40 / 111_195.0, 77.0, now - 9_000, now - 2_000, setOf(NodeId(10), NodeId(11)), 0.8, false),
        KnownHazard(HazardKind.ACCIDENT, stageFix(3).lat - 30 / 111_195.0, 77.0 - 2 / 111_195.0, now - 20_000, now - 5_000, setOf(NodeId(13)), 0.7, false),
        KnownHazard(HazardKind.NO_HELMET, stageFix(1).lat + 15 / 111_195.0, 77.0, now - 30_000, now - 30_000, setOf(NodeId(10)), 0.6, false))

    @Test fun meshOnB() {
        val state = SessionState(Role.RELAY, Mode.STAGE, running = true, ready = true, node = NodeId(11), events = listOf(event(10, 10, 100.0, true).copy(viaRank = 0)),
            chain = chain(1), known = known())
        val copilot = ready.copy(lines = mapOf("event:${state.events[0].id}" to CopilotLine("x", "Someone on the road about 116 m ahead: slow down and be ready to stop.", true, 3100)))
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) { MeshTab(state, copilot, now, actions) } } }
        shot("6-mesh-B")
    }

    @Test fun roadOnE() {
        val here = stageFix(4).copy(speedMps = 12.0)
        val replies = (0..3).map { i ->
            ControlFrame.Reply(MessageId(NodeId(14), 1, 1), NodeId(10L + i), NodeId(10L + i), 7, 4 - i - 1, i, stageFix(i).copy(speedMps = if (i == 0) 1.5 else 11.0),
                if (i == 0) listOf(ControlFrame.Record(HazardKind.POTHOLE, 40, 0, 12, 2, 0.8)) else emptyList())
        }
        val picture = RoadPicture.combine(here, known().filter { it.kind == HazardKind.ACCIDENT }, replies, now)
        val state = SessionState(Role.RECEIVER, Mode.STAGE, running = true, ready = true, node = NodeId(14), chain = chain(4), known = known(), picture = picture, pictureAtMs = now - 3_000)
        val q = listOf(Question("q:2", "What's the road like ahead?", picture.forecast.advice.take(2).joinToString(" "), picture.facts(), now, picture))
        val copilot = ready.copy(lines = mapOf("q:2" to CopilotLine("q:2", "Traffic is slowing ahead. Keep space and prepare to stop; clearance is unknown.", true, 2900)))
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) { ReceivedTab(app, state, copilot, q, now, actions) } } }
        shot("7-road-E")
    }

    @Test fun reports() {
        app.reports.clear()
        app.reports.add(HazardKind.POTHOLE, "pothole on the road about 7 m ahead", 0.81, 12.0, 77.0, true, null, true, "clip")
        app.reports.add(HazardKind.TRIPLE_RIDING, "3 people riding one two-wheeler", 0.74, 12.0, 77.0, true, null, true, "clip")
        app.reports.add(HazardKind.NO_HELMET, "two-wheeler rider without a helmet", 0.66, 12.0, 77.0, true, null, true, "clip")
        app.reports.add(HazardKind.ACCIDENT, "reported over the mesh by phone A", null, null, null, true, null, false, "mesh", fromMesh = true)
        val reportSnapshot = app.reports.flow.value
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) { ReportsTab(app, reportSnapshot) } } }
        shot("8-reports")
    }

    private fun drowsy(): DriverState {
        val alarm = DriverAlarm(Alertness.DROWSY, 12.4, "eyes shut for 1.4 s", 0.7)
        return DriverState(running = true, reading = DriverReading(Alertness.DROWSY, 1.4, 0.18, 2f, alarm.reason), leftEyeOpen = 0.04f, rightEyeOpen = 0.06f,
            fps = 14.0, alarm = alarm, sos = listOf(SosSent(alarm, true, now - 3_000)))
    }

    @Test fun driverMonitorPanel() {
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { DriverPanel(app, drowsy()) }
        } } }
        shot("driver_panel")
    }

    @Test fun driverWakeUpAlarm() {
        val d = drowsy()
        compose.setContent { RoadMeshTheme { WakeUpContent(d.copy(alarm = d.alarm!!.copy(level = Alertness.UNRESPONSIVE, reason = "eyes shut for 3.0 s"),
            sos = listOf(SosSent(d.alarm!!.copy(level = Alertness.UNRESPONSIVE, reason = "eyes shut for 3.0 s"), true, now))), {}) } }
        shot("driver_wake_up")
    }

    @Test fun swarmConnectedAndComparisons() {
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) { SwarmScreen(SessionState(Role.RECEIVER, Mode.STAGE)) } } }
        shot("9-swarm-connected")
        compose.onNodeWithText("Navigation is a configurable information baseline, not a test of Google Maps. V2I can win when its sensor has coverage.").performScrollTo()
        shot("10-swarm-comparison")
        compose.onNodeWithText("The unchanged model underestimates a new traffic regime and performs worse than the fixed estimate. Past clearance is not permission to proceed.").performScrollTo()
        shot("15-swarm-learning-shift")
        compose.onNodeWithText("Replay only. No live training, driving-policy learning or passage permission. These are prediction errors, not travel-time savings.").performScrollTo()
        shot("16-swarm-learning-adaptation")
    }

    @Test fun swarmDisconnectedAndAlternatePath() {
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) {
            SwarmScreen(SessionState(Role.RECEIVER, Mode.STAGE), initialConfig = SwarmScenarioConfig(relayEnabled = false))
        } } }
        shot("11-swarm-no-route")
        compose.onNodeWithContentDescription("Alternative neighbour D").performScrollTo().performClick()
        compose.onAllNodesWithText("A → D → C")[0].assertExists()
        compose.onNodeWithText("SYNTHETIC SCENARIO").performScrollTo()
        shot("12-swarm-alternate-path")
    }

    @Test fun swarmExpired() {
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) {
            SwarmScreen(SessionState(Role.RECEIVER, Mode.STAGE), initialConfig = SwarmScenarioConfig(elapsedMs = 16_000))
        } } }
        shot("13-swarm-expired")
    }

    @Test fun swarmPhoneWithoutPosition() {
        compose.setContent { RoadMeshTheme { Surface(color = Tokens.Ink) {
            SwarmScreen(SessionState(Role.RECEIVER, Mode.ROAD), initialLive = true)
        } } }
        shot("14-swarm-phone-unknown")
    }
}
