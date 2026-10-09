package com.jsons.roadmesh

import com.jsons.roadmesh.core.mesh.*
import org.junit.Assert.*
import org.junit.Test

class ProfileTest {
    @Test fun completeProfileRoundTrips() {
        val p = MeshProfile(mode = Mode.ROAD, maxRelayDelayMs = 350, pendingCapacity = 32)
        assertEquals(p, parseProfile(profileJson(p).toString()))
    }
    @Test fun invalidAndUnknownSettingsAreRejected() {
        listOf("{\"pendingCapacity\":0}", "{\"maxRelayDelayMs\":1.5}", "{\"originPerSecond\":\"2\"}",
            "{\"schema\":2}", "{\"schema\":\"1\"}", "{\"mode\":null}", "{\"typo\":123}", "[]", "bad json").forEach { text ->
            assertTrue("reject $text", runCatching { parseProfile(text) }.isFailure)
        }
    }
    @Test fun defaultsAreVisibleStageAndRoadHasItsOwnHopBudget() {
        assertEquals(MeshProfile(), parseProfile("{}"))
        assertEquals(2, parseProfile("{\"mode\":\"ROAD\"}").initialTtl)
    }
}
