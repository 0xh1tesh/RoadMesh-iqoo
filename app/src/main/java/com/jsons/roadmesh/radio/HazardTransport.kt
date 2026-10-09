package com.jsons.roadmesh.radio

/** Wire adapter boundary. Incoming buffers are owned copies. No UI or scene dependency. */
interface HazardTransport {
    suspend fun start(onFrame: (ByteArray) -> Unit)
    suspend fun transmit(bytes: ByteArray, relay: Boolean): Int
    fun maintain(nowMs: Long)
    fun close()
}
