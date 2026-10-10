package com.jsons.roadmesh.radio

/** Wire adapter boundary. Incoming buffers are owned copies. No UI or scene dependency. */
interface HazardTransport {
    suspend fun start(onFrame: (ByteArray) -> Unit)
    suspend fun transmit(bytes: ByteArray, relay: Boolean): Int
    /** Replaces the always-on presence advertisement. Returns false when the phone has no second advertising set. */
    suspend fun beacon(bytes: ByteArray): Boolean
    /** Tears down and rebuilds the burst advertising set after a failure, keeping scanning and the session alive. */
    suspend fun recover()
    fun maintain(nowMs: Long)
    fun close()
}
