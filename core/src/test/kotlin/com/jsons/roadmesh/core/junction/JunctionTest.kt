package com.jsons.roadmesh.core.junction

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class JunctionGeometryTest {
    private val g = JunctionGeometry()

    @Test fun twelveMovementsWithLeftHandTurns() {
        assertEquals(12, Movement.ALL.size)
        assertEquals(Arm.W, Movement(Arm.S, Turn.LEFT).to)
        assertEquals(Arm.N, Movement(Arm.S, Turn.STRAIGHT).to)
        assertEquals(Arm.E, Movement(Arm.S, Turn.RIGHT).to)
        for (arm in Arm.entries) {
            val left = g.pathLength(Movement(arm, Turn.LEFT)); val straight = g.pathLength(Movement(arm, Turn.STRAIGHT))
            assertTrue("left is the short turn", left < straight && straight < g.pathLength(Movement(arm, Turn.RIGHT)))
        }
    }

    @Test fun pathsRunFromStopLineToExitLane() {
        for (m in Movement.ALL) {
            assertTrue("$m start", g.positionAt(m, 0.0).distance(g.approachPoint(m.from, 0.0)) < 1e-6)
            assertTrue("$m end", g.positionAt(m, g.clearTime(m)).distance(g.exitPoint(m.to, 0.0)) < 1e-6)
        }
    }

    @Test fun leftTurnsCrossNoOtherPathAndThroughMovementsCrossPerpendicularOnes() {
        val crossing = crossingPairs()
        assertTrue(crossing.none { (a, b) -> a.turn == Turn.LEFT || b.turn == Turn.LEFT })
        val s = Movement(Arm.S, Turn.STRAIGHT)
        assertTrue(s to Movement(Arm.E, Turn.STRAIGHT) in crossing || Movement(Arm.E, Turn.STRAIGHT) to s in crossing)
        assertTrue(Movement.ALL.all { m -> g.uses.getValue(m).isNotEmpty() })
    }

    /** Movement pairs with different entries and exits whose box centrelines intersect. */
    fun crossingPairs(): Set<Pair<Movement, Movement>> {
        val out = HashSet<Pair<Movement, Movement>>()
        val all = Movement.ALL
        for (i in all.indices) for (j in i + 1 until all.size) {
            val a = all[i]; val b = all[j]
            if (a.from == b.from || a.to == b.to) continue
            if (intersects(g.boxCentreline(a), g.boxCentreline(b))) out += a to b
        }
        return out
    }

    private fun intersects(p: List<Point>, q: List<Point>): Boolean {
        fun cross(o: Point, a: Point, b: Point) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        for (i in 0 until p.size - 1) for (j in 0 until q.size - 1) {
            val d1 = cross(q[j], q[j + 1], p[i]); val d2 = cross(q[j], q[j + 1], p[i + 1])
            val d3 = cross(p[i], p[i + 1], q[j]); val d4 = cross(p[i], p[i + 1], q[j + 1])
            if ((d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0)) return true
        }
        return false
    }
}

class JunctionSchedulerTest {
    private val geometry = JunctionGeometry()

    @Test fun grantsAreSeparatedAndLanesStayFifo() {
        val s = JunctionScheduler(geometry, SchedulerConfig(exitCapacity = 50))
        val rnd = Random(3)
        var id = 1L
        repeat(40) { Arm.entries.forEach { arm -> s.request(id++, Movement(arm, Turn.entries[rnd.nextInt(3)]), it * 0.5) } }
        val grants = s.schedule(0.0)
        assertEquals(160, grants.size)
        assertTrue(s.reservationsSeparated())
        for (arm in Arm.entries) {
            val slots = grants.filter { it.movement.from == arm }.sortedBy { it.vehicle }.map { it.slot }
            assertTrue("FIFO with headway", slots.zipWithNext().all { (a, b) -> b >= a + s.config.headwayS - 1e-9 })
        }
    }

    @Test fun exitTokensCapAdmissionsUntilSpaceIsReleased() {
        val s = JunctionScheduler(geometry, SchedulerConfig(exitCapacity = 2))
        val intoNorth = listOf(Movement(Arm.S, Turn.STRAIGHT), Movement(Arm.E, Turn.RIGHT), Movement(Arm.W, Turn.LEFT), Movement(Arm.S, Turn.STRAIGHT))
        assertTrue(intoNorth.all { it.to == Arm.N })
        intoNorth.forEachIndexed { i, m -> s.request(i + 1L, m, 0.0) }
        assertEquals(2, s.schedule(0.0).size)
        assertEquals(2, s.tokensOut(Arm.N))
        s.released(Arm.N)
        assertEquals(1, s.schedule(1.0).size)
        assertEquals(2, s.tokensOut(Arm.N))
    }

    @Test fun delayAttenuatesUpstreamAlongTheLane() {
        val s = JunctionScheduler(geometry, SchedulerConfig(exitCapacity = 50))
        for (k in 1L..8L) s.request(k, Movement(Arm.S, Turn.STRAIGHT), 10.0)
        s.schedule(0.0)
        val before = (1L..8L).associateWith { s.slot(it)!! }
        val shifts = s.delay(1L, before.getValue(1L) + 4.0)
        val laneShifts = (1L..8L).map { shifts[it] ?: 0.0 }
        assertEquals(4.0, laneShifts[0], 1e-9)
        assertTrue("never amplified", laneShifts.zipWithNext().all { (a, b) -> b <= a + 1e-9 })
        // Required average-speed cut for a follower cruising at 10 m/s: v·Δk / (Tk + Δk). It shrinks upstream.
        val cuts = (1L..8L).mapIndexed { i, k -> 10.0 * laneShifts[i] / (before.getValue(k) + laneShifts[i]) }
        assertTrue(cuts.zipWithNext().all { (a, b) -> b <= a + 1e-9 })
        assertTrue(s.reservationsSeparated())
    }

    @Test fun delayedLaneKeepsPriorityOverLaterCrossTraffic() {
        val s = JunctionScheduler(geometry)
        for (k in 1L..6L) s.request(k, Movement(Arm.S, Turn.STRAIGHT), 5.0)
        for (k in 11L..16L) s.request(k, Movement(Arm.E, Turn.STRAIGHT), 5.0)
        s.schedule(0.0)
        val delayed = s.delay(1L, s.slot(1L)!! + 3.0)
        val lane = (1L..6L).map { delayed[it] ?: 0.0 }
        assertTrue(lane[0] >= 3.0 - 1e-9)
        assertTrue("followers never shift more than the delayed vehicle: $lane", lane.all { it <= lane[0] + 1e-9 })
        assertTrue(lane.zipWithNext().all { (a, b) -> b <= a + 1e-9 })
        assertTrue(s.reservationsSeparated())
    }

    @Test fun committedSlotsNeverMove() {
        val s = JunctionScheduler(geometry)
        s.request(1, Movement(Arm.S, Turn.STRAIGHT), 5.0); s.request(2, Movement(Arm.S, Turn.STRAIGHT), 5.0)
        s.schedule(0.0)
        val first = s.slot(1)!!
        s.commit(1)
        assertTrue(s.delay(1, first + 5).isEmpty())
        s.delay(2, s.slot(2)!! + 5)
        assertEquals(first, s.slot(1)!!, 0.0)
    }
}

class LaneBeliefTest {
    @Test fun chainPropagationEqualsTheDenseSolution() {
        val rnd = Random(9)
        repeat(20) {
            val n = 2 + rnd.nextInt(7)
            val priors = List(n) { if (it == 0 || rnd.nextBoolean()) Gauss(rnd.nextDouble(0.0, 200.0), rnd.nextDouble(1.0, 25.0)) else null }
            val gaps = List(n - 1) { Gauss(rnd.nextDouble(6.0, 40.0), rnd.nextDouble(0.1, 4.0)) }
            val fused = LaneBelief.fuse(priors, gaps)
            val (mean, variance) = dense(priors, gaps)
            for (i in 0 until n) {
                assertEquals(mean[i], fused[i]!!.mean, 1e-6)
                assertEquals(variance[i], fused[i]!!.variance, 1e-6)
            }
        }
    }

    @Test fun unanchoredSegmentHasNoEstimate() {
        val fused = LaneBelief.fuse(listOf(Gauss(100.0, 4.0), null, null), listOf(Gauss(10.0, 1.0), null))
        assertNotNull(fused[1]); assertNull(fused[2])
    }

    @Test fun unseenVehicleBecomesAProxyAnchoredByItsObserver() {
        val lane = LaneBelief.build(listOf(
            // A 15 m mismatch is only separable from GNSS noise when sigma is about 2 m; at 4 m it is ambiguous.
            LaneReport("A", Gauss(100.0, 4.0)),
            LaneReport("B", Gauss(70.0, 4.0), leadGap = Gauss(15.0, 0.5)),
        ))
        assertEquals(listOf("A", "proxy:B", "B"), lane.map { it.id })
        val proxy = lane[1]
        assertFalse(proxy.equipped)
        assertEquals(85.0, proxy.s.mean, 1e-9)
    }

    @Test fun matchingCameraGapLinksNeighboursAndTightensBoth() {
        val lane = LaneBelief.build(listOf(
            LaneReport("A", Gauss(100.0, 16.0)),
            LaneReport("B", Gauss(78.0, 16.0), leadGap = Gauss(21.0, 0.25)),
        ))
        assertEquals(listOf("A", "B"), lane.map { it.id })
        assertTrue(lane.all { it.s.variance < 9.0 })
        assertEquals(21.0, lane[0].s.mean - lane[1].s.mean, 0.1)
    }

    private fun dense(priors: List<Gauss?>, gaps: List<Gauss>): Pair<DoubleArray, DoubleArray> {
        val n = priors.size
        val l = Array(n) { DoubleArray(n) }; val e = DoubleArray(n)
        priors.forEachIndexed { i, p -> if (p != null) { l[i][i] += 1 / p.variance; e[i] += p.mean / p.variance } }
        gaps.forEachIndexed { k, g ->
            val w = 1 / g.variance
            l[k][k] += w; l[k + 1][k + 1] += w; l[k][k + 1] -= w; l[k + 1][k] -= w
            e[k] += w * g.mean; e[k + 1] -= w * g.mean
        }
        val inv = invert(l)
        return DoubleArray(n) { i -> (0 until n).sumOf { inv[i][it] * e[it] } } to DoubleArray(n) { inv[it][it] }
    }

    private fun invert(m: Array<DoubleArray>): Array<DoubleArray> {
        val n = m.size
        val a = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) m[i][j] else if (j - n == i) 1.0 else 0.0 } }
        for (c in 0 until n) {
            val p = (c until n).maxBy { abs(a[it][c]) }
            val t = a[c]; a[c] = a[p]; a[p] = t
            val d = a[c][c]
            for (j in 0 until 2 * n) a[c][j] /= d
            for (r in 0 until n) if (r != c) { val f = a[r][c]; for (j in 0 until 2 * n) a[r][j] -= f * a[c][j] }
        }
        return Array(n) { i -> DoubleArray(n) { a[i][n + it] } }
    }
}

class ApproachLawTest {
    private val c = ApproachConfig()

    @Test fun arrivesOnTimeAndStopsWithoutASlot() {
        assertEquals(10.0, ApproachLaw.target(ApproachView(0.0, 100.0, 10.0, slot = 10.0), c), 1e-9)
        assertEquals(c.speedLimitMps, ApproachLaw.target(ApproachView(0.0, 300.0, 10.0, slot = 10.0), c), 1e-9)
        assertEquals(0.0, ApproachLaw.target(ApproachView(0.0, 0.0, 3.0, slot = null), c), 1e-9)
    }

    @Test fun predecessorPlanLowersTheTargetBeforeTheGapCloses() {
        val observedOnly = ApproachLaw.target(ApproachView(0.0, 120.0, 10.0, slot = 12.0, gapM = 20.0), c)
        val withPlan = ApproachLaw.target(ApproachView(0.0, 120.0, 10.0, slot = 12.0, gapM = 20.0, predecessorPlanMps = 4.0), c)
        assertTrue(withPlan < observedOnly)
    }

    @Test fun advisorIsQuantizedRateLimitedAndUrgentWhenStopping() {
        val a = Advisor()
        assertEquals(AdviceKind.HOLD, a.update(0.0, 10.0, 10.0, cleared = false)!!.kind)
        assertNull("tiny change", a.update(1.0, 10.3, 10.0, cleared = false))
        assertNull("too soon", a.update(1.0, 7.0, 7.5, cleared = false))
        val stop = a.update(1.5, 0.0, 6.0, cleared = false)!!
        assertEquals(AdviceKind.STOP_AT_LINE, stop.kind); assertTrue(stop.urgent)
        assertEquals("Ease off to 25.", Advice(AdviceKind.EASE_OFF, 25, false).speech)
    }
}

class TransferTest {
    @Test fun ownershipFollowsTheHandover() {
        val t = Transfer(1)
        assertEquals(Owner.INBOUND_EDGE, t.owner)
        assertFalse("cannot skip the grant", t.advance(TransferState.IN_BOX))
        for (s in listOf(TransferState.REQUESTED, TransferState.GRANTED, TransferState.REQUESTED, TransferState.GRANTED, TransferState.COMMITTED)) assertTrue(t.advance(s))
        assertEquals(Owner.JUNCTION, t.owner)
        assertTrue(t.advance(TransferState.IN_BOX)); assertTrue(t.advance(TransferState.EXITED))
        assertEquals(Owner.EXIT_EDGE, t.owner)
        assertFalse(t.advance(TransferState.APPROACH))
    }

    @Test fun committedVehicleThatMissesItsWindowFallsBackOutsideTheBox() {
        val t = Transfer(2)
        listOf(TransferState.REQUESTED, TransferState.GRANTED, TransferState.COMMITTED, TransferState.HOLD_AT_LINE, TransferState.GRANTED).forEach { assertTrue(t.advance(it)) }
    }
}

class EgoTrackerTest {
    @Test fun gnssAndImuConvergeOnTheTrueTrack() {
        val rnd = Random(5)
        val ego = EgoTracker(0.0, 0.0)
        var s = 0.0; var v = 8.0
        repeat(600) { i ->
            val a = if (i in 200..260) -2.0 else 0.0
            s += v * 0.05 + 0.5 * a * 0.0025; v += a * 0.05
            ego.predict(0.05, a + rnd.nextDouble(-0.3, 0.3))
            if (i % 20 == 0) { ego.observePosition(s + rnd.nextDouble(-4.0, 4.0), 4.0); ego.observeSpeed(v + rnd.nextDouble(-0.3, 0.3), 0.3) }
        }
        assertEquals(s, ego.s, 3.0); assertEquals(v, ego.v, 0.5)
        assertTrue(ego.positionVariance < 16.0)
    }

    @Test fun edgeFrameGivesDistanceAlongAndSignedOffset() {
        val edge = EdgeFrame(listOf(Point(0.0, 0.0), Point(0.0, 100.0), Point(50.0, 100.0)))
        assertEquals(150.0, edge.length, 1e-9)
        edge.project(Point(-2.0, 40.0)).let { assertEquals(40.0, it.s, 1e-9); assertEquals(2.0, it.d, 1e-9) }
        edge.project(Point(20.0, 98.0)).let { assertEquals(120.0, it.s, 1e-9); assertEquals(-2.0, it.d, 1e-9) }
        val p = EdgeFrame.local(12.0, 77.0, 12.0 + 100.0 / 111_195.0, 77.0)
        assertEquals(100.0, p.y, 0.1)
    }
}
