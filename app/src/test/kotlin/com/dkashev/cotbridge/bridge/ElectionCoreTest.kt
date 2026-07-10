package com.dkashev.cotbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElectionCoreTest {

    private val interval = 30_000L
    private val timeout = 95_000L
    private fun core(priority: Int, id: Int) =
        ElectionCore(ElectionCore.keyOf(priority, id), interval, timeout)

    @Test fun `keyOf orders by priority then id`() {
        assertTrue(ElectionCore.keyOf(0, 999) < ElectionCore.keyOf(1, 1))
        assertTrue(ElectionCore.keyOf(100, 1) < ElectionCore.keyOf(100, 2))
    }

    @Test fun `lone capable instance becomes active and beacons`() {
        val e = core(100, 5)
        assertFalse(e.isActive)
        val sent = e.tick(1_000, capable = true)
        assertTrue(e.isActive)
        assertTrue("first active tick should emit a beacon", sent)
        // within one interval: no new beacon
        assertFalse(e.tick(2_000, capable = true))
        // after the interval: beacon again
        assertTrue(e.tick(1_000 + interval, capable = true))
    }

    @Test fun `not capable stays standby and never beacons`() {
        val e = core(100, 5)
        assertFalse(e.tick(1_000, capable = false))
        assertFalse(e.isActive)
    }

    @Test fun `loss of capability demotes an active gateway`() {
        val e = core(100, 5)
        e.tick(1_000, capable = true)
        assertTrue(e.isActive)
        e.tick(2_000, capable = false)
        assertFalse(e.isActive)
    }

    @Test fun `yields immediately to a stronger active peer`() {
        val me = core(100, 50)
        me.tick(1_000, capable = true)
        assertTrue(me.isActive)
        me.onBeacon(ElectionCore.keyOf(100, 10), 2_000) // lower id = stronger
        assertFalse("should yield to the stronger gateway", me.isActive)
        assertFalse(me.tick(3_000, capable = true)) // stays standby while peer is fresh
        assertFalse(me.isActive)
    }

    @Test fun `takes over from a weaker active peer`() {
        val me = core(100, 10)
        me.onBeacon(ElectionCore.keyOf(100, 50), 1_000) // weaker peer
        me.tick(1_500, capable = true)
        assertTrue("stronger node should claim the role", me.isActive)
    }

    @Test fun `promotes after a dead gateway's beacons time out`() {
        val me = core(100, 50)
        me.onBeacon(ElectionCore.keyOf(100, 10), 1_000) // stronger peer present
        me.tick(2_000, capable = true)
        assertFalse(me.isActive)
        // stronger peer goes silent; its beacon expires after timeout -> vacancy
        me.tick(1_000 + timeout + 1, capable = true)
        assertTrue("should fail over once the stronger beacon expires", me.isActive)
    }

    @Test fun `two fresh capable nodes converge to the stronger one`() {
        val strong = core(100, 10)
        val weak = core(100, 50)
        // both promote in isolation
        strong.tick(1_000, true); weak.tick(1_000, true)
        assertTrue(strong.isActive); assertTrue(weak.isActive)
        // they hear each other
        weak.onBeacon(strong.myKey, 2_000)
        strong.onBeacon(weak.myKey, 2_000)
        assertTrue("stronger stays active", strong.isActive)
        assertFalse("weaker yields", weak.isActive)
    }

    @Test fun `equal keys are impossible via distinct ids`() {
        assertEquals(false, ElectionCore.keyOf(100, 1) == ElectionCore.keyOf(100, 2))
    }
}
