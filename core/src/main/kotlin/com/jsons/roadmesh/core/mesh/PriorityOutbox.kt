package com.jsons.roadmesh.core.mesh

/** Bounded local radio work. An urgent event may displace background context, never the reverse. */
class PriorityOutbox<T>(private val capacity: Int = 64, private val urgentQuota: Int = 4) {
    enum class Priority { CONTEXT, CONDITION, URGENT }
    private data class Entry<T>(val value: T, val priority: Priority, val expiresAtMs: Long, val key: String?)
    private val entries = ArrayList<Entry<T>>()
    private var urgentRun = 0
    private var foregroundRun = 0
    init { require(capacity > 0 && urgentQuota > 0) }

    @Synchronized fun offer(value: T, priority: Priority, nowMs: Long, expiresAtMs: Long, key: String? = null): Boolean {
        expire(nowMs)
        if (nowMs < 0 || expiresAtMs <= nowMs) return false
        if (key != null) entries.removeAll { it.key == key }
        if (entries.size >= capacity) {
            val victim = entries.indices.minByOrNull { entries[it].priority.ordinal } ?: return false
            if (entries[victim].priority >= priority) return false
            entries.removeAt(victim)
        }
        entries += Entry(value, priority, expiresAtMs, key)
        return true
    }

    @Synchronized fun poll(nowMs: Long): T? {
        expire(nowMs)
        if (entries.isEmpty()) { urgentRun = 0; foregroundRun = 0; return null }
        // With all classes busy, service urgentQuota urgent items, one condition, then one context item.
        // Condition-only traffic also accrues context debt; otherwise continuous conditions starve enrichment.
        val available = when {
            urgentRun >= urgentQuota && entries.any { it.priority == Priority.CONDITION } ->
                entries.indices.filter { entries[it].priority == Priority.CONDITION }
            foregroundRun >= urgentQuota && entries.any { it.priority == Priority.CONTEXT } ->
                entries.indices.filter { entries[it].priority == Priority.CONTEXT }
            else -> entries.indices.toList()
        }
        val highest = available.maxOf { entries[it].priority }
        val index = available.filter { entries[it].priority == highest }.minBy { entries[it].expiresAtMs }
        val next = entries.removeAt(index)
        urgentRun = when (next.priority) {
            Priority.URGENT -> (urgentRun + 1).coerceAtMost(urgentQuota)
            Priority.CONDITION -> 0
            Priority.CONTEXT -> urgentRun
        }
        foregroundRun = if (next.priority == Priority.CONTEXT) 0 else (foregroundRun + 1).coerceAtMost(urgentQuota)
        return next.value
    }

    @Synchronized fun clear() { entries.clear(); urgentRun = 0; foregroundRun = 0 }
    @Synchronized fun size(): Int = entries.size
    private fun expire(nowMs: Long) { entries.removeAll { it.expiresAtMs <= nowMs } }
}
