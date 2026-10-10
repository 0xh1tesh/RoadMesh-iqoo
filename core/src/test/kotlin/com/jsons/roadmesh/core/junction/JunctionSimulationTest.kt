package com.jsons.roadmesh.core.junction

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic single-junction simulation of Reserve-then-Roll: four 150 m single-lane approaches, four 60 m exits.
 * Drivers follow the target speed with a 0.8 s lag and enter the box at their slot plus a random delay in [0, ε].
 * Box crossing is at a fixed speed. It checks the theorems' invariants; it does not model BLE, GNSS error or
 * unequipped road users, and every vehicle complies.
 */
class JunctionSimulationTest {
    data class Scenario(
        val name: String,
        val ratePerArm: Double,
        val epsilon: Double = 0.4,
        val seconds: Double = 900.0,
        val closedExit: Arm? = null,
        val closedFrom: Double = 0.0,
        val closedUntil: Double = 0.0,
        val seed: Int = 11,
    )

    data class Result(
        val scenario: Scenario, val spawned: Int, val completed: Int,
        val collisions: Int, val boxBlocked: Int, val windowViolations: Int, val followingViolations: Int,
        val lateAtLine: Int, val maxLeafQueue: Int, val maxExitOccupancy: Int, val exitCapacity: Int,
        val throughputPerHour: Double, val meanDelayS: Double, val stopsPerCar: Double,
        val reslots: Int, val amplifiedReslots: Int, val completedOtherExitsDuringClosure: Int, val minSeparationM: Double,
    )

    private class Car(val id: Long, val m: Movement, val spawnAt: Double, val entryNoise: Double) {
        val transfer = Transfer(id)
        var s = 0.0; var v = 0.0; var target = 0.0; var entryTime = 0.0
        var moving = true; var stops = 0
    }

    private val dt = 0.05
    private val inboundM = 150.0
    private val outboundM = 60.0
    private val lengthM = 5.0
    private val lagS = 0.8
    private val collisionM = 3.0

    fun run(sc: Scenario): Result {
        val rnd = Random(sc.seed)
        val geometry = JunctionGeometry()
        val law = ApproachConfig()
        val exitCapacity = ((outboundM - 10.0) / law.standstillGapM).toInt()
        val scheduler = JunctionScheduler(geometry, SchedulerConfig(gapS = max(2 * sc.epsilon, 0.5),
            headwayS = SchedulerConfig.laneHeadway(law), exitCapacity = exitCapacity))
        val all = HashMap<Long, Car>()
        val leaf = Arm.entries.associateWith { ArrayDeque<Car>() }
        val inbound = Arm.entries.associateWith { ArrayList<Car>() }
        val outbound = Arm.entries.associateWith { ArrayList<Car>() }
        val box = ArrayList<Car>()
        val lastIntoBox = HashMap<Arm, Car>()
        var nextId = 1L; var spawned = 0; var completed = 0; var collisions = 0; var boxBlocked = 0
        var windowViolations = 0; var followingViolations = 0; var lateAtLine = 0; var maxLeaf = 0; var maxExit = 0
        var reslots = 0; var amplified = 0; var otherExits = 0; var delaySum = 0.0; var stops = 0; var minSeparation = Double.MAX_VALUE
        val vx = geometry.config.crossingSpeedMps

        fun reslot(car: Car, at: Double, force: Boolean = false) {
            val shifts = scheduler.delay(car.id, at, force)
            if (shifts.isEmpty()) return
            reslots++
            val own = shifts[car.id] ?: 0.0
            if (shifts.any { (id, d) -> id != car.id && all[id]?.m?.from == car.m.from && d > own + 1e-6 }) amplified++
        }

        val steps = (sc.seconds / dt).toInt()
        for (step in 0 until steps) {
            val t = step * dt
            // Arrivals beyond the leaf; a growing leaf queue is spillback out of the cluster.
            for (arm in Arm.entries) if (t < sc.seconds - 180 && rnd.nextDouble() < sc.ratePerArm * dt) {
                val turn = rnd.nextDouble().let { if (it < .25) Turn.LEFT else if (it < .75) Turn.STRAIGHT else Turn.RIGHT }
                // Human start delay after the slot, kept one step inside ε so the entry window is never skipped.
                val car = Car(nextId++, Movement(arm, turn), t, rnd.nextDouble() * (sc.epsilon - dt))
                all[car.id] = car; leaf.getValue(arm).addLast(car); spawned++
            }
            for (arm in Arm.entries) {
                val lane = inbound.getValue(arm); val back = lane.lastOrNull(); val queue = leaf.getValue(arm)
                if (queue.isNotEmpty() && (back == null || back.s >= law.standstillGapM + law.headwayS * back.v)) {
                    val car = queue.removeFirst()
                    car.v = back?.v ?: law.speedLimitMps
                    lane += car
                    car.transfer.advance(TransferState.REQUESTED)
                    scheduler.request(car.id, car.m, t + ApproachLaw.minimumTimeToLine(inboundM, car.v, law))
                }
                maxLeaf = max(maxLeaf, queue.size)
            }
            for (g in scheduler.schedule(t)) all.getValue(g.vehicle).transfer.advance(TransferState.GRANTED)

            // Inbound edges, front car first so each follower sees its predecessor's fresh plan.
            for (arm in Arm.entries) {
                val lane = inbound.getValue(arm)
                var i = 0
                while (i < lane.size) {
                    val car = lane[i]; val pred = lane.getOrNull(i - 1)
                    val dist = inboundM - car.s
                    var slot = scheduler.slot(car.id)
                    if (slot != null && !scheduler.committed(car.id)) {
                        val eta = t + ApproachLaw.minimumTimeToLine(dist, car.v, law)
                        if (eta > slot + sc.epsilon / 2) {
                            reslot(car, eta)
                            if (car.transfer.state == TransferState.GRANTED) { car.transfer.advance(TransferState.REQUESTED); car.transfer.advance(TransferState.GRANTED) }
                            slot = scheduler.slot(car.id)
                        }
                        // Commit only when on track for the window; otherwise the car can still stop at the line.
                        val approachSpeed = if (car.v > vx) (car.v + vx) / 2 else car.v
                        val onTrack = slot != null && (t + dist / max(approachSpeed, 0.1)).let { it >= slot && it <= slot + sc.epsilon }
                        if (onTrack && dist <= car.v * car.v / (2 * law.comfortDecelMps2) + 2.0) {
                            scheduler.commit(car.id); car.transfer.advance(TransferState.COMMITTED)
                        }
                    }
                    val (gap, plan) = when {
                        pred != null -> (pred.s - car.s) to pred.target
                        else -> lastIntoBox[arm]?.takeIf { it in box }?.let { (dist + vx * (t - it.entryTime)) to vx } ?: (null to null)
                    }
                    car.target = ApproachLaw.target(ApproachView(t, dist, car.v, slot, gap, plan, pred?.v, sc.epsilon), law)
                    val a = ((car.target - car.v) / lagS).coerceIn(-6.0, law.maxAccelMps2)
                    car.v = max(0.0, car.v + a * dt); car.s += car.v * dt
                    if (pred != null && car.s > pred.s - lengthM) { followingViolations++; car.s = pred.s - lengthM; car.v = min(car.v, pred.v) }
                    if (car.s >= inboundM) {
                        car.s = inboundM
                        val granted = scheduler.slot(car.id)
                        when {
                            granted == null -> { car.v = 0.0; car.transfer.advance(TransferState.HOLD_AT_LINE) }
                            t < granted + car.entryNoise -> car.v = 0.0
                            t <= granted + sc.epsilon + 1e-9 -> {
                                if (car.transfer.state == TransferState.GRANTED) car.transfer.advance(TransferState.COMMITTED)
                                assertTrue(car.transfer.advance(TransferState.IN_BOX))
                                if (t < granted - 1e-9) windowViolations++
                                scheduler.entered(car.id)
                                car.entryTime = t; box += car; lastIntoBox[arm] = car
                                lane.removeAt(i)
                                continue
                            }
                            else -> {
                                // A committed car missing its window is a protocol fault; an uncommitted one just re-slots.
                                if (scheduler.committed(car.id)) lateAtLine++
                                car.v = 0.0
                                car.transfer.advance(TransferState.HOLD_AT_LINE)
                                reslot(car, t + 0.5, force = true)
                                if (scheduler.slot(car.id) != null) car.transfer.advance(TransferState.GRANTED)
                            }
                        }
                    }
                    if (car.v < 0.1 && car.moving) { car.moving = false; car.stops++ }
                    if (car.v > 1.0) car.moving = true
                    i++
                }
            }

            // Box: fixed-speed crossing along the reserved path.
            for (car in box.toList()) {
                if (t - car.entryTime < geometry.clearTime(car.m)) continue
                box.remove(car)
                val lane = outbound.getValue(car.m.to)
                if (lane.lastOrNull()?.let { it.s < lengthM } == true) boxBlocked++
                car.s = 0.0; car.v = vx; lane += car
                assertTrue(car.transfer.advance(TransferState.EXITED))
            }

            // Exit edges, front (nearest the leaf) first. A closed leaf holds traffic on its exit edge.
            for (arm in Arm.entries) {
                val lane = outbound.getValue(arm)
                val closed = sc.closedExit == arm && t >= sc.closedFrom && t < sc.closedUntil
                var i = 0
                while (i < lane.size) {
                    val car = lane[i]; val pred = lane.getOrNull(i - 1)
                    var target = law.speedLimitMps
                    if (pred != null) target = min(target, ApproachLaw.followSpeed(pred.s - car.s, car.v, pred.target, pred.v, law))
                    if (closed) target = min(target, sqrt(2 * law.comfortDecelMps2 * max(0.0, outboundM - car.s)))
                    car.target = target
                    val a = ((target - car.v) / lagS).coerceIn(-6.0, law.maxAccelMps2)
                    car.v = max(0.0, car.v + a * dt); car.s += car.v * dt
                    if (pred != null && car.s > pred.s - lengthM) { followingViolations++; car.s = pred.s - lengthM; car.v = min(car.v, pred.v) }
                    if (car.s >= outboundM) {
                        if (closed) { car.s = outboundM; car.v = 0.0 } else {
                            lane.removeAt(i); scheduler.released(arm); completed++
                            val freeFlow = inboundM / law.speedLimitMps + geometry.clearTime(car.m) + outboundM / law.speedLimitMps
                            delaySum += t - car.spawnAt - freeFlow; stops += car.stops
                            if (sc.closedExit != null && arm != sc.closedExit && t >= sc.closedFrom && t < sc.closedUntil) otherExits++
                            continue
                        }
                    }
                    i++
                }
                maxExit = max(maxExit, lane.size)
            }

            // Independent collision check on world positions near the box.
            val near = ArrayList<Point>()
            for (arm in Arm.entries) {
                inbound.getValue(arm).filter { inboundM - it.s <= 10 }.forEach { near += geometry.approachPoint(arm, inboundM - it.s) }
                outbound.getValue(arm).filter { it.s <= 10 }.forEach { near += geometry.exitPoint(arm, it.s) }
            }
            box.forEach { near += geometry.positionAt(it.m, t - it.entryTime) }
            for (a in near.indices) for (b in a + 1 until near.size) {
                val d = near[a].distance(near[b])
                minSeparation = min(minSeparation, d)
                if (d < collisionM) collisions++
            }
        }
        val hours = (sc.seconds - 180) / 3600
        return Result(sc, spawned, completed, collisions, boxBlocked, windowViolations, followingViolations, lateAtLine,
            maxLeaf, maxExit, exitCapacity, completed / hours, if (completed > 0) delaySum / completed else 0.0,
            if (completed > 0) stops.toDouble() / completed else 0.0, reslots, amplified, otherExits, minSeparation)
    }

    private fun assertSafe(r: Result) {
        assertEquals("collisions $r", 0, r.collisions)
        assertEquals("box blocked $r", 0, r.boxBlocked)
        assertEquals("entry outside slot window $r", 0, r.windowViolations)
        assertTrue("exit storage $r", r.maxExitOccupancy <= r.exitCapacity)
    }

    @Test fun moderateDemandIsCollisionFreeWithoutSpillback() {
        val r = run(Scenario("moderate", ratePerArm = 0.06))
        assertSafe(r)
        assertEquals("every vehicle transfers", r.spawned, r.completed)
        assertEquals("no rear-end conflicts on the approaches: $r", 0, r.followingViolations)
        assertEquals("no committed vehicle misses its window: $r", 0, r.lateAtLine)
        assertTrue("no spillback past the leaves: $r", r.maxLeafQueue <= 3)
        assertEquals(0, r.amplifiedReslots)
    }

    @Test fun overloadQueuesOnTheArmsButTheBoxNeverJams() {
        val r = run(Scenario("overload", ratePerArm = 0.3))
        assertSafe(r)
        assertTrue("demand outside the capacity region must queue on the arms: $r", r.maxLeafQueue > 20)
    }

    @Test fun blockedExitHoldsItsTrafficOutsideTheBox() {
        val r = run(Scenario("blocked-north-exit", ratePerArm = 0.05, closedExit = Arm.N, closedFrom = 200.0, closedUntil = 500.0))
        assertSafe(r)
        // Single-lane FIFO: a head waiting for the closed exit blocks its own lane (head-of-line), the others keep moving.
        assertTrue("other exits keep flowing during the closure: $r", r.completedOtherExitsDuringClosure >= 20)
    }

    @Test fun timingErrorCostsCapacityNotSafety() {
        val sweep = listOf(0.25, 0.5, 1.0).map { run(Scenario("overload-eps-$it", ratePerArm = 0.3, epsilon = it)) }
        sweep.forEach(::assertSafe)
        assertTrue(sweep.map { it.throughputPerHour }.toString(), sweep.first().throughputPerHour > sweep.last().throughputPerHour)
        val runs = listOf(run(Scenario("moderate", ratePerArm = 0.06)), run(Scenario("overload", ratePerArm = 0.3)),
            run(Scenario("blocked-north-exit", ratePerArm = 0.05, closedExit = Arm.N, closedFrom = 200.0, closedUntil = 500.0))) + sweep
        File("build/reports/junction-simulation.json").apply { parentFile.mkdirs() }.writeText(
            "{\"disclosure\":\"single 4-way junction, all vehicles equipped and compliant, fixed-speed box crossing, no BLE/GNSS error; " +
                "validates scheduler invariants, not road performance\",\"runs\":[" + runs.joinToString(",") { r ->
                "{\"name\":\"${r.scenario.name}\",\"ratePerArmPerS\":${r.scenario.ratePerArm},\"epsilonS\":${r.scenario.epsilon}," +
                    "\"spawned\":${r.spawned},\"completed\":${r.completed},\"throughputPerHour\":${"%.0f".format(r.throughputPerHour)}," +
                    "\"meanDelayS\":${"%.1f".format(r.meanDelayS)},\"stopsPerVehicle\":${"%.2f".format(r.stopsPerCar)}," +
                    "\"collisions\":${r.collisions},\"boxBlocked\":${r.boxBlocked},\"windowViolations\":${r.windowViolations}," +
                    "\"followingViolations\":${r.followingViolations},\"lateAtLine\":${r.lateAtLine},\"maxLeafQueue\":${r.maxLeafQueue}," +
                    "\"maxExitOccupancy\":${r.maxExitOccupancy},\"exitCapacity\":${r.exitCapacity},\"reslots\":${r.reslots}," +
                    "\"amplifiedReslots\":${r.amplifiedReslots},\"minSeparationM\":${"%.2f".format(r.minSeparationM)}}"
            } + "]}")
    }
}
