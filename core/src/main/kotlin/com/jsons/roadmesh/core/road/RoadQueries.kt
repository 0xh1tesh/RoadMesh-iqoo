package com.jsons.roadmesh.core.road

import com.jsons.roadmesh.core.mesh.*

/** A road-condition request this phone sent and the answers gathered so far. */
data class OpenQuery(val id: MessageId, val sentMs: Long, val fix: GeoFix, val replies: List<ControlFrame.Reply>, val done: Boolean)

/**
 * Road-condition requests. A query floods the chain in both directions (each phone relays it once while budget lasts);
 * every phone that hears it answers with its own position, speed and hazard records, and the answers flood back the same
 * way. The asking phone collects them for [windowMs]. Pure: callers pass time in and send what comes out.
 */
class RoadQueries(
    private val node: NodeId, private val session: Long, private val ttl: Int = 8, private val windowMs: Long = 2_500,
    private val records: (from: GeoFix, nowMs: Long, radiusM: Double) -> List<ControlFrame.Record>,
    private val here: () -> GeoFix?, private val rank: () -> Int,
) {
    private val seen = LinkedHashMap<Pair<MessageId, NodeId>, Long>()
    private var sequence = 0
    private val open = LinkedHashMap<MessageId, OpenQuery>()
    val queries: List<OpenQuery> get() = open.values.toList()

    /** Starts a request from [fix]; returns the frame to broadcast. */
    fun ask(fix: GeoFix, nowMs: Long, radiusM: Int = 2_000): ControlFrame.Query {
        val q = ControlFrame.Query(MessageId(node, session, ++sequence), node, ttl, 0, radiusM, fix)
        mark(q.id to node, nowMs)
        open[q.id] = OpenQuery(q.id, nowMs, fix, emptyList(), false)
        while (open.size > 8) open.remove(open.keys.first())
        return q
    }

    /** Handles a received query or reply; returns frames to broadcast (a reply, relays). */
    fun receive(frame: ControlFrame, nowMs: Long): List<ControlFrame> {
        expire(nowMs)
        return when (frame) {
            is ControlFrame.Query -> {
                if (frame.id.origin == node || !mark(frame.id to frame.id.origin, nowMs)) return emptyList()
                val out = mutableListOf<ControlFrame>()
                here()?.let { fix ->
                    val reply = ControlFrame.Reply(frame.id, node, node, ttl, 0, rank().coerceIn(0, 255), fix,
                        records(fix, nowMs, frame.radiusM.toDouble()))
                    mark(frame.id to node, nowMs); out += reply
                }
                frame.relayedBy(node)?.let { out += it }
                out
            }
            is ControlFrame.Reply -> {
                if (frame.responder == node || !mark(frame.query to frame.responder, nowMs)) return emptyList()
                open[frame.query]?.let { q ->
                    if (!q.done && nowMs - q.sentMs < windowMs && q.replies.size < MAX_REPLIES)
                        open[frame.query] = q.copy(replies = q.replies + frame.copy(receivedAtMs = nowMs))
                    return emptyList()
                }
                // Legacy v1 can carry eight small records; a v2 relay caps them to its evidence byte budget.
                listOfNotNull(frame.copy(records = frame.records.take(ControlCodec.MAX_RECORDS)).relayedBy(node))
            }
            is ControlFrame.Presence -> emptyList()
        }
    }

    /** Closes requests whose window has passed; returns the ones that just finished. */
    fun tick(nowMs: Long): List<OpenQuery> {
        val finished = open.values.filter { !it.done && nowMs - it.sentMs >= windowMs }.map { it.copy(done = true) }
        finished.forEach { open[it.id] = it }
        return finished
    }

    private fun mark(key: Pair<MessageId, NodeId>, nowMs: Long): Boolean {
        if (key in seen) return false
        seen[key] = nowMs; while (seen.size > 2_048) seen.remove(seen.keys.first())
        return true
    }
    private fun expire(nowMs: Long) { seen.entries.removeAll { nowMs - it.value > 30_000 } }

    companion object { const val MAX_REPLIES = 64 }
}
