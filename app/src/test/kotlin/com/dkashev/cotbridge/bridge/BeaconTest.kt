package com.dkashev.cotbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeaconTest {

    @Test fun `encode then decode round-trips the key`() {
        val key = ElectionCore.keyOf(100, 123_456_789)
        val enc = Beacon.encode(key)
        assertEquals(Beacon.SIZE, enc.size)
        assertEquals(key, Beacon.decodeKey(enc))
    }

    @Test fun `decode rejects wrong length`() {
        assertNull(Beacon.decodeKey(byteArrayOf(1, 2, 3)))
    }

    @Test fun `decode rejects bad magic`() {
        assertNull(Beacon.decodeKey(ByteArray(Beacon.SIZE))) // all zeros -> no "GW" prefix
    }

    @Test fun `handles priority-zero and large ids`() {
        val key = ElectionCore.keyOf(0, Int.MAX_VALUE)
        assertEquals(key, Beacon.decodeKey(Beacon.encode(key)))
    }
}
