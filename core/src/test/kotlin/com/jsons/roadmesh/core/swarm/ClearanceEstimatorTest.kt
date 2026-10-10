package com.jsons.roadmesh.core.swarm

import org.junit.Assert.*
import org.junit.Test

class ClearanceEstimatorTest {
    private fun episode(id: String = "one", start: Long = 1_000, duration: Long = 10_000, region: String = "market") =
        ClearanceEpisode(id, region, "observer", start, start + duration, true)

    @Test fun `unknown context and too little training produce no estimate`() {
        val learner = ClearanceEstimator()
        assertNull(learner.estimate("market"))
        assertTrue(learner.observe(episode(), 11_000))
        assertNull(learner.estimate("market"))
        assertNull(learner.estimate("other"))
    }

    @Test fun `copies and overlapping episode observations cannot train repeatedly`() {
        val learner = ClearanceEstimator(minimumSamples = 1)
        assertTrue(learner.observe(episode(), 11_000))
        assertFalse(learner.observe(episode(), 12_000))
        assertFalse(learner.observe(episode(start = 20_000), 30_000))
        assertFalse(learner.observe(episode("copy-id", start = 2_000, duration = 10_000), 12_000))
        assertEquals(1, learner.estimate("market")!!.sampleCount)
    }

    @Test fun `unconfirmed invalid future and old outcomes cannot train`() {
        val invalid = listOf(
            episode().copy(directlyObserved = false), episode().copy(region = ""), episode().copy(sourceId = ""),
            episode().copy(onsetMs = -1), episode(duration = 0), episode(duration = 301_000), episode(duration = 500),
        )
        invalid.forEach { assertFalse(it.toString(), ClearanceEstimator().observe(it, 400_000)) }
        assertFalse(ClearanceEstimator().observe(episode(), 10_000))
        assertFalse(ClearanceEstimator().observe(episode(), 400_000))
    }

    @Test fun `recent median follows changed outcomes with bounded memory`() {
        val learner = ClearanceEstimator(windowSize = 3)
        var start = 1_000L
        listOf(10_000L, 11_000L, 12_000L, 40_000L, 50_000L, 60_000L).forEachIndexed { index, duration ->
            val item = episode("$index", start, duration)
            assertTrue(learner.observe(item, item.clearedMs))
            start = item.clearedMs + 1
            if (index == 2) assertEquals(11.0, learner.estimate("market")!!.estimatedSeconds, 0.0)
        }
        val estimate = learner.estimate("market")!!
        assertEquals(3, estimate.sampleCount)
        assertEquals(50.0, estimate.estimatedSeconds, 0.0)
        assertEquals(40.0, estimate.minSeconds, 0.0)
        assertEquals(60.0, estimate.maxSeconds, 0.0)
    }

    @Test fun `contexts evict without allowing old cleared episodes to be replayed`() {
        val learner = ClearanceEstimator(maxContexts = 1, minimumSamples = 1)
        val first = episode()
        assertTrue(learner.observe(first, first.clearedMs))
        val next = episode("two", 20_000, region = "bridge")
        assertTrue(learner.observe(next, next.clearedMs))
        assertNull(learner.estimate("market"))
        assertNotNull(learner.estimate("bridge"))
        assertFalse(learner.observe(first, 40_000))
    }

    @Test fun `synthetic evaluation shows improvement shift failure and adaptation on unseen episodes`() {
        val results = ClearanceLearningDemo.run()
        val stable = results[0]
        val shift = results[1]
        val adapted = results[2]
        assertEquals(10.0, stable.estimateSeconds, 0.0)
        assertEquals(1.0, stable.adaptiveMaeSeconds, 0.0)
        assertTrue(stable.adaptiveMaeSeconds < stable.baselineMaeSeconds)
        assertTrue(shift.adaptiveMaeSeconds > shift.baselineMaeSeconds)
        assertEquals(40.0, adapted.estimateSeconds, 0.0)
        assertEquals(4.0, adapted.adaptiveMaeSeconds, 0.0)
        assertTrue(adapted.adaptiveMaeSeconds < adapted.baselineMaeSeconds)
        assertTrue(results.all { it.trainingSamples == 5 })
        assertEquals(results, ClearanceLearningDemo.run())
    }
}
