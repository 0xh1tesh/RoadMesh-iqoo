package com.jsons.roadmesh.core.swarm

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SwarmScenarioTest {
    private fun result(config: SwarmScenarioConfig = SwarmScenarioConfig(), label: String = "RoadMesh") =
        SwarmScenario.run(config).comparisons.single { it.label == label }

    @Test fun `same event arrives through actual two-hop path without extra witnesses`() {
        val mesh = result()
        assertEquals(listOf("A", "B", "C"), mesh.path)
        assertEquals(300L, mesh.deliveredAtMs)
        assertEquals(SwarmStatus.CAUTION, mesh.assessment.status)
        assertEquals(1, mesh.assessment.independentSources)
        assertEquals("A", mesh.assessment.evidence.single().sourceId)
        assertEquals(2_000L, mesh.assessment.oldestAgeMs)
        assertEquals(DirectionRelation.OPPOSING, mesh.assessment.neighbours.single { it.id == "E" }.relation)
        assertNull(mesh.warningLeadSeconds)
    }

    @Test fun `removing relay loses knowledge and only a real alternate path restores it`() {
        val cut = result(SwarmScenarioConfig(relayEnabled = false))
        assertEquals(SwarmStatus.UNKNOWN, cut.assessment.status)
        assertNull(cut.deliveredAtMs)
        assertTrue(cut.path.isEmpty())
        val alternate = result(SwarmScenarioConfig(relayEnabled = false, alternateEnabled = true))
        assertEquals(SwarmStatus.CAUTION, alternate.assessment.status)
        assertEquals(listOf("A", "D", "C"), alternate.path)
        assertEquals(300L, alternate.deliveredAtMs)
    }

    @Test fun `fixed V2I wins with coverage when mobile graph is disconnected`() {
        val config = SwarmScenarioConfig(relayEnabled = false, v2iCovered = true)
        assertEquals(SwarmStatus.UNKNOWN, result(config).assessment.status)
        val v2i = result(config, "Fixed V2I")
        assertEquals(SwarmStatus.CAUTION, v2i.assessment.status)
        assertEquals(250L, v2i.deliveredAtMs)
        assertEquals(result().assessment.evidence, v2i.assessment.evidence)
    }

    @Test fun `navigation with an incident feed receives the identical evidence`() {
        assertEquals(SwarmStatus.UNKNOWN, result(label = "Navigation").assessment.status)
        val navigation = result(SwarmScenarioConfig(navigationDelayMs = 500), "Navigation")
        assertEquals(SwarmStatus.CAUTION, navigation.assessment.status)
        assertEquals(result().assessment.evidence, navigation.assessment.evidence)
        assertEquals(500L, navigation.deliveredAtMs)
    }

    @Test fun `local sensor gains knowledge when actual coverage is increased`() {
        assertEquals(SwarmStatus.UNKNOWN, result(label = "Local camera").assessment.status)
        val local = result(SwarmScenarioConfig(sensorRangeM = 120.0), "Local camera")
        assertEquals(SwarmStatus.CAUTION, local.assessment.status)
        assertEquals(0L, local.deliveredAtMs)
        assertEquals(result().assessment.evidence, local.assessment.evidence)
    }

    @Test fun `delivery timing matters and all methods lose expired single observations`() {
        assertEquals(SwarmStatus.UNKNOWN, result(SwarmScenarioConfig(elapsedMs = 299)).assessment.status)
        assertEquals(SwarmStatus.CAUTION, result(SwarmScenarioConfig(elapsedMs = 300)).assessment.status)
        val expired = SwarmScenario.run(SwarmScenarioConfig(elapsedMs = 15_000, sensorRangeM = 120.0, v2iCovered = true, navigationDelayMs = 0))
        assertTrue(expired.comparisons.all { it.assessment.status == SwarmStatus.UNKNOWN && it.assessment.independentSources == 0 })
        assertTrue(expired.comparisons.all { it.detail.contains("expired") })
    }

    @Test fun `graph range and source sight actually constrain the comparison`() {
        assertEquals(SwarmStatus.UNKNOWN, result(SwarmScenarioConfig(radioRangeM = 20.0)).assessment.status)
        assertEquals(SwarmStatus.UNKNOWN, result(SwarmScenarioConfig(sensorRangeM = 10.0)).assessment.status)
        val direct = result(SwarmScenarioConfig(relayEnabled = false, radioRangeM = 100.0))
        assertEquals(listOf("A", "C"), direct.path)
        assertEquals(150L, direct.deliveredAtMs)
    }

    @Test fun `shortest path is deterministic loop safe and cannot bridge disconnected components`() {
        val links = listOf("A" to "D", "D" to "C", "A" to "B", "B" to "C", "B" to "A")
        assertEquals(listOf("A", "B", "C"), SwarmScenario.shortestPath("A", "C", links))
        assertEquals(SwarmScenario.shortestPath("A", "C", links), SwarmScenario.shortestPath("A", "C", links.reversed()))
        assertTrue(SwarmScenario.shortestPath("A", "Z", links).isEmpty())
    }

    @Test fun `report is produced by the shared evaluator with explicit limits`() {
        val report = SwarmScenarioCli.reportJson()
        assertEquals(report, SwarmScenarioCli.reportJson())
        assertTrue(report.contains("\"name\":\"v2i_covers_mesh_disconnected\""))
        assertTrue(report.contains("\"path\":[\"A\",\"D\",\"C\"]"))
        assertTrue(report.contains("No collision reduction"))
        assertFalse(report.contains("NaN"))
        val file = File("build/reports/swarm-comparison.json")
        file.parentFile.mkdirs()
        file.writeText(report)
    }
}
