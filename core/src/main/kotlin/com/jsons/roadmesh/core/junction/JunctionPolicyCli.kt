package com.jsons.roadmesh.core.junction

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter

/** Persistent UTF-8 TSV protocol v1; stdout contains exactly one response per command. */
object JunctionPolicyCli {
    @JvmStatic
    fun main(args: Array<String>) {
        run(
            BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8)),
            PrintWriter(OutputStreamWriter(System.out, Charsets.UTF_8)),
        )
    }

    fun run(reader: BufferedReader, writer: PrintWriter, policy: JunctionPolicy = JunctionPolicy()) {
        while (true) {
            val line = reader.readLine() ?: break
            val response = when (line) {
                "RESET" -> { policy.reset(); "OK" }
                "QUIT" -> "OK"
                else -> {
                    val input = parse(line)
                    if (input == null) "ERROR\tinvalid_input"
                    else when (val decision = policy.evaluate(input)) {
                        is JunctionDecision.Warn -> "WARN\tqueue_ahead\t${decision.ageMs}"
                        is JunctionDecision.Ignore -> "IGNORE\t${decision.reason}"
                    }
                }
            }
            writer.println(response)
            writer.flush()
            if (line == "QUIT") break
        }
    }

    internal fun parse(line: String): JunctionInput? {
        val fields = line.split('\t')
        if (fields.size != 14 || fields[0] != "EVAL") return null
        val mode = JunctionMode.entries.find { it.wireName == fields[1] } ?: return null
        val equipped = when (fields[4]) { "true" -> true; "false" -> false; else -> return null }
        val channel = ObservationChannel.entries.find { it.wireName == fields[13] } ?: return null
        val now = fields[3].toLongOrNull() ?: return null
        val distance = fields[6].toDoubleOrNull() ?: return null
        val speed = fields[7].toDoubleOrNull() ?: return null
        val captured = fields[9].toLongOrNull() ?: return null
        val expires = fields[10].toLongOrNull() ?: return null
        return JunctionInput(
            mode, fields[2], now, equipped, fields[5], distance, speed,
            QueueObservation(fields[8], captured, expires, fields[11], fields[12]), channel,
        ).takeIf { it.isValid() }
    }
}
