package com.jsons.roadmesh.core.junction

import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class JunctionPolicyCliTest {
    private val valid = listOf(
        "EVAL", "mesh_informed", "car", "1000", "true", "east", "100", "10",
        "queue-1", "1000", "30000", "east", "observer", "mesh",
    )

    private fun line(index: Int? = null, value: String = ""): String =
        valid.mapIndexed { i, original -> if (index == i) value else original }.joinToString("\t")

    private fun run(vararg lines: String): List<String> {
        val output = StringWriter()
        JunctionPolicyCli.run(StringReader(lines.joinToString("\n")).buffered(), PrintWriter(output))
        return output.toString().lineSequence().filter { it.isNotEmpty() }.toList()
    }

    @Test fun `persistent protocol reset quit and exact output`() {
        assertEquals(
            listOf("WARN\tqueue_ahead\t0", "IGNORE\tduplicate", "OK", "WARN\tqueue_ahead\t0", "OK"),
            run(line(), line(), "RESET", line(), "QUIT", line()),
        )
    }

    @Test fun `warn age and stable ignore reasons are emitted without diagnostics`() {
        assertEquals(listOf("WARN\tqueue_ahead\t500"), run(line(9, "500")))
        assertEquals(listOf("IGNORE\tno_advice"), run(line(1, "no_advice")))
        assertEquals(listOf("IGNORE\tchannel_ineligible"), run(line(1, "local_only")))
        assertEquals(listOf("IGNORE\tunequipped"), run(line(4, "false")))
        assertEquals(listOf("IGNORE\tfuture_observation"), run(line(9, "1001")))
        assertEquals(listOf("IGNORE\texpired"), run(valid.mapIndexed { i, v -> when (i) { 9 -> "0"; 10 -> "1000"; else -> v } }.joinToString("\t")))
    }

    @Test fun `malformed commands produce one error and allow next valid command`() {
        val malformed = listOf(
            "", "UNKNOWN", "RESET\textra", "QUIT\textra", "eval", "EVAL", line() + "\textra",
            valid.dropLast(1).joinToString("\t"), line() + "\t", line(0, "eval"),
            line(1, "unknown"), line(1, "MESH_INFORMED"), line(13, "bluetooth"),
            line(4, "TRUE"), line(4, "1"), line(3, "-1"), line(3, "1.0"),
            line(3, "9223372036854775808"), line(9, "-1"), line(9, "oops"),
            line(10, "1000"), line(10, "999"), line(10, "-1"), line(10, "9223372036854775808"),
            line(6, "NaN"), line(6, "Infinity"), line(6, "-Infinity"), line(6, "1e999"),
            line(7, "NaN"), line(7, "Infinity"), line(7, "-0.01"), line(7, "fast"),
            line(2, ""), line(5, ""), line(8, ""), line(11, ""), line(12, ""),
        )
        for (bad in malformed) {
            assertEquals("Failed recovery for [$bad]", listOf("ERROR\tinvalid_input", "WARN\tqueue_ahead\t0"), run(bad, line()))
        }
    }

    @Test fun `reset clears backward clock and malformed input never advances it`() {
        val invalidFuture = valid.mapIndexed { i, v -> when (i) { 3 -> "50000"; 7 -> "bad"; else -> v } }.joinToString("\t")
        assertEquals(
            listOf("ERROR\tinvalid_input", "WARN\tqueue_ahead\t0", "IGNORE\tbackward_time", "OK", "WARN\tqueue_ahead\t0"),
            run(invalidFuture, line(), line(3, "999"), "RESET", line()),
        )
    }

    @Test fun `unicode identifiers and EOF without newline are supported`() {
        assertEquals(listOf("WARN\tqueue_ahead\t0"), run(line(2, "वाहन-🚗")))
        assertEquals(emptyList<String>(), run())
    }
}
