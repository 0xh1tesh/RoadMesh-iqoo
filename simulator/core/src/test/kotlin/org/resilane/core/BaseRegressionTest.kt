package org.resilane.core

import org.junit.jupiter.api.Test
import org.resilane.core.control.Braking
import org.resilane.core.proto.*
import org.resilane.core.sync.NodeRegistry
import kotlin.test.*

class BaseRegressionTest {
    @Test fun brakingStopsWithoutReversingAndHasATimedLampWindow() {
        assertEquals(2f, Braking.sample(0f).speedMps)
        assertEquals(0f, Braking.sample(1f).speedMps)
        assertEquals(1f, Braking.sample(1f).distanceM)
        assertEquals(Braking.sample(1f).distanceM, Braking.sample(100f).distanceM)
        assertTrue(Braking.sample(0f).lampsOn); assertFalse(Braking.sample(5f).lampsOn)
    }
    @Test fun veryOldSequenceCannotResetReplayWindow() {
        val window = DedupeWindow()
        assertTrue(window.firstSeen(1, 100))
        assertFalse(window.firstSeen(1, 1))
        assertFalse(window.firstSeen(1, 100))
    }
    @Test fun shortRestartNeedsANewSessionAndOldSessionStaysRetired() {
        val window = DedupeWindow()
        (1..20).forEach { assertTrue(window.firstSeen(1, it, 50)) }
        assertTrue(window.firstSeen(1, 1, 51))
        assertFalse(window.firstSeen(1, 21, 50))
        assertFalse(window.firstSeen(1, 1, 51))
        assertTrue(window.firstSeen(1, 2, 51))
    }
    @Test fun replayTrackingHasABound() {
        val window = DedupeWindow(2)
        assertTrue(window.firstSeen(1, 1)); assertTrue(window.firstSeen(2, 1)); assertFalse(window.firstSeen(3, 1))
        assertFalse(window.firstSeen(1, 1))
    }
    @Test fun sceneV2CarriesSessionAndV1RemainsReadable() {
        val frame = Envelope(7, 5, 1, Ping(123), 0x1020304050607080)
        val bytes = Codec.encode(frame)
        assertEquals(frame, Codec.decode(bytes))
        val legacy = bytes.copyOfRange(0, 18) + bytes.copyOfRange(26, bytes.size)
        legacy[2] = 1
        assertEquals(frame.copy(sessionId = 0), Codec.decode(legacy))
        assertNull(Codec.decode(bytes + byteArrayOf(0)))
        assertNull(Codec.decode(bytes.copyOf(23)))
    }
    @Test fun helloOnlyPeersExpireAndRegistryIsBounded() {
        val registry = NodeRegistry(1, capacity = 2)
        assertNotNull(registry.getOrCreate(2, now = 100)); assertNotNull(registry.getOrCreate(3, now = 100))
        assertNull(registry.getOrCreate(4, now = 100))
        registry.pruneLost(60_101)
        assertTrue(registry.all().isEmpty())
        assertNotNull(registry.getOrCreate(4, now = 60_101))
    }
}
