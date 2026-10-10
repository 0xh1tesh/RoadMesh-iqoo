package com.jsons.roadmesh.core.junction

enum class Owner { INBOUND_EDGE, JUNCTION, EXIT_EDGE }

enum class TransferState(val owner: Owner) {
    APPROACH(Owner.INBOUND_EDGE),
    REQUESTED(Owner.INBOUND_EDGE),
    GRANTED(Owner.INBOUND_EDGE),
    HOLD_AT_LINE(Owner.INBOUND_EDGE),
    COMMITTED(Owner.JUNCTION),
    IN_BOX(Owner.JUNCTION),
    EXITED(Owner.EXIT_EDGE),
}

/**
 * Edge → junction → edge handover. Exactly one owner at any time (H3). GRANTED → REQUESTED is a re-slot;
 * COMMITTED → HOLD_AT_LINE is the forced fallback when a committed vehicle misses its window.
 * Every failure path ends outside the box, which never causes a collision or a box jam.
 */
class Transfer(val vehicle: Long) {
    var state = TransferState.APPROACH
        private set
    val owner get() = state.owner

    fun advance(to: TransferState): Boolean {
        if (to !in LEGAL.getValue(state)) return false
        state = to
        return true
    }

    companion object {
        private val LEGAL = mapOf(
            TransferState.APPROACH to setOf(TransferState.REQUESTED),
            TransferState.REQUESTED to setOf(TransferState.GRANTED, TransferState.HOLD_AT_LINE),
            TransferState.GRANTED to setOf(TransferState.COMMITTED, TransferState.REQUESTED, TransferState.HOLD_AT_LINE),
            TransferState.HOLD_AT_LINE to setOf(TransferState.GRANTED),
            TransferState.COMMITTED to setOf(TransferState.IN_BOX, TransferState.HOLD_AT_LINE),
            TransferState.IN_BOX to setOf(TransferState.EXITED),
            TransferState.EXITED to emptySet(),
        )
    }
}
