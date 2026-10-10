package com.jsons.roadmesh.ui

import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.core.road.moved
import org.junit.Assert.*
import org.junit.Test

class MeshMapTest {
    private val origin = GeoFix(12.9716, 77.5946, 0.0, 5.0, false)
    private fun member(rank: Int, fix: GeoFix?) = Member(
        NodeId(rank + 1L), 0L, rank == 0, VehicleType.CAR, fix, 1_000L, rank,
        if (rank == 0) Role.OBSERVER else Role.RELAY, rank == 0,
    )

    @Test fun allUnlocatedPeersProduceNoInventedRoadPositions() {
        val (nodes, hazards) = layoutMesh(listOf(member(0, null), member(1, null), member(2, null)), emptyList())
        assertTrue(nodes.isEmpty())
        assertTrue(hazards.isEmpty())
    }

    @Test fun onlyLocatedTailIsAnchorInsteadOfAFabricatedRankGap() {
        val nodes = layoutMesh(listOf(member(0, null), member(1, null), member(2, origin)), emptyList()).first
        assertEquals(1, nodes.size)
        assertEquals(2, nodes.single().rank)
        assertEquals(0.0, nodes.single().z, 0.001)
        assertEquals(0.0, nodes.single().x, 0.001)
    }

    @Test fun measuredLateralOffsetsAreNotSnappedToAnInventedLane() {
        val right = moved(origin, 40.0, 2.0).copy(heading = 0.0)
        val slightlyLeft = moved(origin, 20.0, -0.25).copy(heading = 0.0)
        val nodes = layoutMesh(listOf(member(0, origin), member(1, right), member(2, slightlyLeft)), emptyList()).first
        assertEquals(0.0, nodes[0].x, 0.01)
        assertEquals(2.0, nodes[1].x, 0.02)
        assertEquals(-0.25, nodes[2].x, 0.02)
        assertEquals(40.0, nodes[1].z, 0.03)
    }

    @Test fun opposingHeadingDoesNotRotateTheAnchorFrame() {
        val other = moved(origin, 50.0, 2.0).copy(heading = 180.0)
        val nodes = layoutMesh(listOf(member(0, origin), member(1, other)), emptyList()).first
        assertEquals(50.0, nodes[1].z, 0.03)
        assertEquals(2.0, nodes[1].x, 0.02)
    }
}
