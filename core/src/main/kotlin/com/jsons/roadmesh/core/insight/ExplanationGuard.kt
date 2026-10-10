package com.jsons.roadmesh.core.insight

/** Conservative language filter, not a proof that arbitrary model prose is factually grounded. */
object ExplanationGuard {
    const val UNKNOWN = "The available observations do not support an explanation."
    private val numbers = Regex("-?\\d+(?:\\.\\d+)?")
    private val manoeuvre = Regex("\\b(safe|overtak\\w*|swerve|accelerate|speed up|change lanes?|move (?:left|right)|steer|road is clear|no (?:danger|risk)|collision avoided|accident prevented|guarantee\\w*)\\b", RegexOption.IGNORE_CASE)
    private val subjects = listOf("pedestrian", "pothole", "accident", "collision", "bus", "truck", "motorcycle", "bicycle", "ambulance", "animal", "signal")

    fun apply(answer: String, facts: String, fallback: String? = null): String {
        val backup = fallback?.takeIf { it.isNotBlank() } ?: UNKNOWN
        val known = numbers.findAll(facts).map { it.value }.toSet()
        val inventedNumber = numbers.findAll(answer).any { it.value !in known }
        val inventedSubject = subjects.any { subject ->
            Regex("\\b${subject}s?\\b", RegexOption.IGNORE_CASE).containsMatchIn(answer) &&
                !Regex("\\b${subject}s?\\b", RegexOption.IGNORE_CASE).containsMatchIn(facts)
        }
        return if (answer.isBlank() || inventedNumber || inventedSubject || manoeuvre.containsMatchIn(answer)) backup else answer
    }
}
