package com.jsons.roadmesh.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PacketCodecTest {

    private fun sample() = Packet(
        cls = PacketClass.ALERT, senderId = 0xABCDEF, seq = 0x1234, ttl = 3, hop = 0,
        flags = Flags.PROXY or Flags.SIGNED, tGenDeci = 12_345,
        latE7 = 129_716_000, lonE7 = 775_946_000, speedCms = 1_389, headingQ = 64,
        cause = 99, subCause = 1, p1 = 45, p2 = 7, lastHop = 0xEF, validityS = 2,
    )

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    @Test fun sizeIsTwentySevenBytes() {
        assertEquals(27, PacketCodec.encode(sample()).size)
    }

    @Test fun roundTripKeepsEveryField() {
        val p = sample()
        assertEquals(DecodeResult.Ok(p), PacketCodec.decode(PacketCodec.encode(p)))
    }

    @Test fun fieldsSitAtTheDocumentedOffsets() {
        val b = PacketCodec.encode(sample())
        assertEquals(0x52, u8(b, 0))
        assertEquals(0x21, u8(b, 1))                                    // version 2, class ALERT
        assertEquals(0xABCDEF, (u8(b, 2) shl 16) or (u8(b, 3) shl 8) or u8(b, 4))
        assertEquals(0xC9, u8(b, 7))                                    // ttl 3, hop 0, flags 9
        assertEquals(99, u8(b, 21))
        assertEquals(1, u8(b, 22))
        assertEquals(2, u8(b, 26))
    }

    @Test fun negativeCoordinatesSurvive() {
        val p = sample().copy(latE7 = -338_688_000, lonE7 = -151_100_000)
        assertEquals(DecodeResult.Ok(p), PacketCodec.decode(PacketCodec.encode(p)))
    }

    @Test fun rejectsWrongSizeMagicVersionAndClass() {
        val good = PacketCodec.encode(sample())
        assertTrue(PacketCodec.decode(good.copyOf(26)) is DecodeResult.Error)
        assertTrue(PacketCodec.decode(good + 0) is DecodeResult.Error)
        assertTrue(PacketCodec.decode(good.clone().also { it[0] = 0x51 }) is DecodeResult.Error)
        assertTrue(PacketCodec.decode(good.clone().also { it[1] = 0x31 }) is DecodeResult.Error)
        assertTrue(PacketCodec.decode(good.clone().also { it[1] = 0x2F }) is DecodeResult.Error)
        assertTrue(PacketCodec.decode(null) is DecodeResult.Error)
    }

    @Test fun rejectsOutOfRangeTimestamp() {
        val b = PacketCodec.encode(sample())
        b[8] = 0xFF.toByte(); b[9] = 0xFF.toByte()
        assertTrue(PacketCodec.decode(b) is DecodeResult.Error)
    }

    @Test fun randomBytesNeverCrashTheDecoder() {
        val rnd = Random(42)
        repeat(10_000) {
            val raw = ByteArray(rnd.nextInt(60)).also { rnd.nextBytes(it) }
            PacketCodec.decode(raw)
        }
        repeat(10_000) {
            val raw = ByteArray(Packet.SIZE).also { rnd.nextBytes(it) }
            raw[0] = Packet.MAGIC.toByte()
            raw[1] = ((Packet.VERSION shl 4) or 1).toByte()
            PacketCodec.decode(raw)
        }
    }

    @Test fun relayLowersTtlRaisesHopAndKeepsTheMessageKey() {
        val p = sample()
        val r = PacketCodec.forRelay(p, relayId = 0x1AB)!!
        assertEquals(2, r.ttl); assertEquals(1, r.hop); assertEquals(0xAB, r.lastHop)
        assertEquals(p.msgKey, r.msgKey)
        assertEquals(p.latE7, r.latE7)
    }

    @Test fun relayStopsAtTtlZeroAndAtThreeHops() {
        assertNull(PacketCodec.forRelay(sample().copy(ttl = 0), 1))
        assertNull(PacketCodec.forRelay(sample().copy(hop = 3), 1))
    }

    @Test fun quantisingRoundsToAboutOneMetre() {
        val q = sample().copy(latE7 = 129_716_049, lonE7 = 775_946_051).quantised()
        assertEquals(129_716_000, q.latE7)
        assertEquals(775_946_100, q.lonE7)
        assertArrayEquals(PacketCodec.encode(q), PacketCodec.encode(q.quantised()))
    }

    @Test fun messageKeyCombinesSenderAndSequence() {
        assertEquals((0xABCDEFL shl 16) or 0x1234L, sample().msgKey)
    }
}
