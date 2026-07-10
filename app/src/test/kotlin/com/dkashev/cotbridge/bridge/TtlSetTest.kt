package com.dkashev.cotbridge.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtlSetTest {

    @Test fun `marked key is contained within ttl`() {
        var now = 1_000L
        val s = TtlSet(ttlMs = 5_000, clock = { now })
        s.mark("Tunec")
        assertTrue(s.contains("Tunec"))
        now = 1_000 + 4_999
        assertTrue(s.contains("Tunec"))
    }

    @Test fun `key expires after ttl`() {
        var now = 1_000L
        val s = TtlSet(ttlMs = 5_000, clock = { now })
        s.mark("Tunec")
        now = 1_000 + 5_001
        assertFalse(s.contains("Tunec"))
    }

    @Test fun `unknown key is not contained`() {
        val s = TtlSet(ttlMs = 5_000, clock = { 0L })
        assertFalse(s.contains("nobody"))
    }

    @Test fun `re-marking refreshes the ttl`() {
        var now = 1_000L
        val s = TtlSet(ttlMs = 5_000, clock = { now })
        s.mark("X")
        now = 4_000; s.mark("X")   // refresh at 4000
        now = 8_000                // 8000-4000 = 4000 <= 5000 -> still alive
        assertTrue(s.contains("X"))
    }
}
