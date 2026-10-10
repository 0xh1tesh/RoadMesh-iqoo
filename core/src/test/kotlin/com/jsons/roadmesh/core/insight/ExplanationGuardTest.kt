package com.jsons.roadmesh.core.insight

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplanationGuardTest {
    @Test fun neverNeedsOptionalFallbackToRejectUnsafeLanguage() {
        assertEquals(ExplanationGuard.UNKNOWN, ExplanationGuard.apply("It is safe to overtake.", "Traffic ahead."))
        assertEquals("Traffic ahead.", ExplanationGuard.apply("Accelerate to pass the bus.", "Bus ahead.", "Traffic ahead."))
    }
    @Test fun decimalNumbersAreComparedAsWholeValues() {
        assertEquals("Unknown distance.", ExplanationGuard.apply("Gap is 2.5 m.", "2 vehicles, 5 frames.", "Unknown distance."))
        assertEquals(ExplanationGuard.UNKNOWN, ExplanationGuard.apply("The distance is 999.", "Distance unknown."))
    }
    @Test fun unsupportedIncidentIsNotIntroducedFromTrafficCounts() {
        assertEquals(ExplanationGuard.UNKNOWN, ExplanationGuard.apply("An accident is ahead.", "2 cars slowing."))
    }
    @Test fun supportedCautiousDescriptionSurvives() {
        val text = "A possible pothole is visible; distance is unknown."
        assertEquals(text, ExplanationGuard.apply(text, "Possible pothole; distance unknown."))
    }
    @Test fun emptyOutputHasNonemptyFallback() {
        assertEquals(ExplanationGuard.UNKNOWN, ExplanationGuard.apply("", "", ""))
    }
}
