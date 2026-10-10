package com.jsons.roadmesh.core.junction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JunctionPolicyTest {
    private fun input(
        now: Long = 1_000,
        recipient: String = "car",
        event: String = "queue-1",
        captured: Long = now,
        expires: Long = captured + 30_000,
    ) = JunctionInput(
        JunctionMode.MESH_INFORMED, recipient, now, true, "east", 100.0, 10.0,
        QueueObservation(event, captured, expires, "east", "observer"), ObservationChannel.MESH,
    )

    private fun ignored(reason: String, policy: JunctionPolicy, input: JunctionInput) {
        assertEquals(JunctionDecision.Ignore(reason), policy.evaluate(input))
    }

    @Test fun `exclusive expiry and inclusive freshness boundary`() {
        assertEquals(JunctionDecision.Warn(10_000), JunctionPolicy().evaluate(input(now = 10_000, captured = 0)))
        ignored("stale", JunctionPolicy(), input(now = 10_001, captured = 0))
        assertEquals(JunctionDecision.Warn(999), JunctionPolicy().evaluate(input(now = 999, captured = 0, expires = 1_000)))
        ignored("expired", JunctionPolicy(), input(now = 1_000, captured = 0, expires = 1_000))
    }

    @Test fun `directed scope range and speed gates`() {
        ignored("scope_mismatch", JunctionPolicy(), input().copy(recipientScope = "west"))
        for (distance in listOf(-1.0, 0.0, 400.001)) {
            ignored("out_of_range", JunctionPolicy(), input().copy(aheadDistanceM = distance))
        }
        assertTrue(JunctionPolicy().evaluate(input().copy(aheadDistanceM = 400.0)) is JunctionDecision.Warn)
        for (speed in listOf(0.0, 0.5)) {
            ignored("low_speed", JunctionPolicy(), input().copy(speedMps = speed))
        }
        assertTrue(JunctionPolicy().evaluate(input().copy(speedMps = 0.50001)) is JunctionDecision.Warn)
    }

    @Test fun `mode channel and equipment eligibility`() {
        for (channel in ObservationChannel.entries) {
            ignored("no_advice", JunctionPolicy(), input().copy(mode = JunctionMode.NO_ADVICE, channel = channel))
            ignored("unequipped", JunctionPolicy(), input().copy(equipped = false, channel = channel))
            assertTrue(JunctionPolicy().evaluate(input().copy(channel = channel)) is JunctionDecision.Warn)
        }
        ignored("channel_ineligible", JunctionPolicy(), input().copy(mode = JunctionMode.LOCAL_ONLY))
        assertTrue(JunctionPolicy().evaluate(input().copy(mode = JunctionMode.LOCAL_ONLY, channel = ObservationChannel.LOCAL)) is JunctionDecision.Warn)
    }

    @Test fun `rejected evidence does not consume warning budget`() {
        val policy = JunctionPolicy()
        ignored("scope_mismatch", policy, input().copy(recipientScope = "west"))
        assertEquals(0, policy.recipientCount)
        assertEquals(0, policy.acceptedEventCount)
        assertEquals(JunctionDecision.Warn(0), policy.evaluate(input()))
    }

    @Test fun `dedup survives channel source scope and cooldown changes until original expiry`() {
        val policy = JunctionPolicy()
        val first = input()
        assertTrue(policy.evaluate(first) is JunctionDecision.Warn)
        ignored("duplicate", policy, first.copy(channel = ObservationChannel.LOCAL))
        // Even inconsistent metadata cannot turn an already accepted ID into new evidence.
        val changed = input(now = 13_000).copy(
            recipientScope = "north",
            observation = first.observation.copy(capturedMs = 13_000, sourceId = "relay", scope = "north"),
        )
        ignored("duplicate", policy, changed)
        assertEquals(0, policy.recipientCount)
        assertEquals(1, policy.acceptedEventCount)
        assertTrue(policy.evaluate(input(now = 31_000)) is JunctionDecision.Warn)
    }

    @Test fun `cooldown spans events and modes but not recipients`() {
        val policy = JunctionPolicy()
        assertTrue(policy.evaluate(input()) is JunctionDecision.Warn)
        ignored("cooldown", policy, input(event = "queue-2").copy(mode = JunctionMode.LOCAL_ONLY, channel = ObservationChannel.LOCAL))
        assertTrue(policy.evaluate(input(recipient = "other")) is JunctionDecision.Warn)
        ignored("cooldown", policy, input(now = 12_999, event = "queue-2"))
        assertTrue(policy.evaluate(input(now = 13_000, event = "queue-2")) is JunctionDecision.Warn)
    }

    @Test fun `recipient capacity rejects without evicting active cooldown`() {
        val policy = JunctionPolicy(maxRecipients = 1, maxAcceptedEventKeys = 10)
        assertTrue(policy.evaluate(input()) is JunctionDecision.Warn)
        ignored("state_capacity", policy, input(recipient = "other"))
        ignored("cooldown", policy, input(event = "new"))
        assertEquals(1, policy.recipientCount)
        assertEquals(1, policy.acceptedEventCount)
        assertTrue(policy.evaluate(input(now = 13_000, recipient = "other")) is JunctionDecision.Warn)
    }

    @Test fun `event capacity remains bounded after recipient cooldown expires`() {
        val policy = JunctionPolicy(maxRecipients = 10, maxAcceptedEventKeys = 1)
        assertTrue(policy.evaluate(input()) is JunctionDecision.Warn)
        ignored("state_capacity", policy, input(now = 13_000, event = "new"))
        ignored("duplicate", policy, input(now = 13_000))
        assertEquals(0, policy.recipientCount)
        assertEquals(1, policy.acceptedEventCount)
        assertTrue(policy.evaluate(input(now = 31_000, event = "new")) is JunctionDecision.Warn)
    }

    @Test fun `expiry frees event keys but never cuts cooldown short`() {
        val policy = JunctionPolicy(maxAcceptedEventKeys = 1)
        assertTrue(policy.evaluate(input(expires = 1_001)) is JunctionDecision.Warn)
        ignored("expired", policy, input(now = 1_001, captured = 1_000, expires = 1_001))
        ignored("cooldown", policy, input(now = 1_001, event = "new"))
        assertEquals(0, policy.acceptedEventCount)
        assertEquals(1, policy.recipientCount)
    }

    @Test fun `default stores admit at most 512 entries without eviction`() {
        val policy = JunctionPolicy()
        repeat(512) { assertTrue(policy.evaluate(input(recipient = "car-$it")) is JunctionDecision.Warn) }
        repeat(100) { ignored("state_capacity", policy, input(recipient = "overflow-$it")) }
        assertEquals(512, policy.recipientCount)
        assertEquals(512, policy.acceptedEventCount)
        ignored("duplicate", policy, input(recipient = "car-0"))
    }

    @Test fun `future capture and global backward clock are rejected until reset`() {
        val policy = JunctionPolicy()
        ignored("future_observation", policy, input(captured = 1_001))
        ignored("backward_time", policy, input(now = 999, recipient = "other"))
        assertTrue(policy.evaluate(input()) is JunctionDecision.Warn)
        ignored("backward_time", policy, input(now = 999, recipient = "other"))
        policy.reset()
        assertEquals(0, policy.acceptedEventCount)
        assertEquals(0, policy.recipientCount)
        assertTrue(policy.evaluate(input(now = 999)) is JunctionDecision.Warn)
    }

    @Test fun `long limits cannot overflow expiry age or cooldown arithmetic`() {
        val policy = JunctionPolicy()
        val nearEnd = input(now = Long.MAX_VALUE - 1, captured = Long.MAX_VALUE - 1, expires = Long.MAX_VALUE)
        assertEquals(JunctionDecision.Warn(0), policy.evaluate(nearEnd))
        ignored("expired", policy, nearEnd.copy(nowMs = Long.MAX_VALUE))
        ignored("stale", JunctionPolicy(), input(now = Long.MAX_VALUE - 1, captured = 0, expires = Long.MAX_VALUE))
    }

    @Test fun `invalid direct input does not advance clock`() {
        val policy = JunctionPolicy()
        ignored("invalid_input", policy, input(now = 50_000).copy(speedMps = Double.NaN))
        ignored("invalid_input", policy, input().copy(nowMs = -1))
        assertTrue(policy.evaluate(input()) is JunctionDecision.Warn)
    }
}
