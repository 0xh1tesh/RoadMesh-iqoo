package com.jsons.roadmesh.core.mesh

import com.jsons.roadmesh.core.road.moved
import org.junit.Assert.*
import org.junit.Test

class RoadRosterTest {
    private val ego = GeoFix(12.0, 77.0, 0.0, 6.0, false)
    private fun presence(id: Long, north: Double, heading: Double?, synthetic: Boolean = false) =
        ControlFrame.Presence(NodeId(id), 500, false, synthetic, 0, VehicleType.CAR,
            moved(ego, north, 0.0).copy(heading = heading, synthetic = synthetic), 0)

    @Test fun opposingTrafficDoesNotRotateOurRoadOrBecomeTheLeader() {
        val roster = ChainRoster(NodeId(1), 0, byRoad = true).also { it.fix = ego }
        roster.hear(presence(2, 50.0, 0.0), 1_000)
        roster.hear(presence(3, 100.0, 180.0), 1_000)
        roster.hear(presence(4, 80.0, 90.0), 1_000)
        val members = roster.update(1_000)
        assertEquals(listOf(2L, 1L, 3L, 4L), members.map { it.node.value })
        assertTrue("road roles don't nominate an arbitrary frontmost camera", members.all { it.role == Role.RELAY })
    }

    @Test fun unknownOwnHeadingDoesNotInventNorthAndStagePeersAreRejectedOnRoad() {
        val roster = ChainRoster(NodeId(1), 0, byRoad = true).also { it.fix = ego.copy(heading = null) }
        roster.hear(presence(2, 50.0, 0.0), 1_000)
        roster.hear(presence(3, 80.0, 0.0, true), 1_000)
        assertEquals(listOf(1L, 2L), roster.update(1_000).map { it.node.value })
        assertEquals(listOf(1L), roster.update(5_001).map { it.node.value })
    }

    @Test fun identityFloodIsBoundedAndClockRollbackExpiresPeers() {
        val roster = ChainRoster(NodeId(1), 0, byRoad = true).also { it.fix = ego }
        (2L..300L).forEach { roster.hear(presence(it, it.toDouble(), 0.0), 1_000) }
        assertEquals(129, roster.update(1_000).size)
        assertEquals(1, roster.update(999).size)
    }
}
