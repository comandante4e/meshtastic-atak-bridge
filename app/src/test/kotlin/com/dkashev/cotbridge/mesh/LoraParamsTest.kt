package com.dkashev.cotbridge.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraParamsTest {

    @Test fun `LONG_FAST airtime for typical PLI is about 680ms`() {
        val t = LoraParams.airtimeSec(LoraParams.DEFAULT, LoraParams.TYPICAL_PAYLOAD)
        // Референс из даташита SX127x: SF11/BW250/CR4:5, 60 байт ≈ 0.68 с.
        assertTrue("airtime=$t", t in 0.60..0.75)
    }

    @Test fun `faster preset means shorter airtime`() {
        val long = LoraParams.airtimeSec(LoraParams.byPresetName("LONG_FAST")!!, 60)
        val medium = LoraParams.airtimeSec(LoraParams.byPresetName("MEDIUM_FAST")!!, 60)
        val short = LoraParams.airtimeSec(LoraParams.byPresetName("SHORT_FAST")!!, 60)
        assertTrue(short < medium)
        assertTrue(medium < long)
    }

    @Test fun `budget tracks the preset and stays well under capacity`() {
        for (name in listOf("LONG_FAST", "MEDIUM_FAST", "SHORT_FAST")) {
            val radio = LoraParams.byPresetName(name)!!
            val capacity = LoraParams.capacityPktPerMin(radio)
            val budget = LoraParams.budgetPktPerMin(radio)
            assertTrue("$name: бюджет $budget должен быть ниже потолка $capacity", budget < capacity)
            assertEquals((capacity * LoraParams.AIR_SHARE).toInt(), budget)
        }
    }

    @Test fun `LONG_FAST budget is single digits — the whole point of throttling`() {
        val budget = LoraParams.budgetPktPerMin(LoraParams.DEFAULT)
        assertTrue("LONG_FAST бюджет = $budget", budget in 1..12)
    }

    @Test fun `faster preset grants a bigger budget`() {
        val long = LoraParams.budgetPktPerMin(LoraParams.byPresetName("LONG_FAST")!!)
        val short = LoraParams.budgetPktPerMin(LoraParams.byPresetName("SHORT_FAST")!!)
        assertTrue("short=$short long=$long", short > long)
    }

    @Test fun `slowest preset still keeps a minimum budget`() {
        val radio = LoraParams.byPresetName("VERY_LONG_SLOW")!!
        assertTrue(LoraParams.budgetPktPerMin(radio) >= LoraParams.MIN_BUDGET)
    }

    @Test fun `preset lookup is case insensitive and unknown yields null`() {
        assertNotNull(LoraParams.byPresetName("long_fast"))
        assertNull(LoraParams.byPresetName("NOPE"))
        assertNull(LoraParams.byPresetName(null))
    }

    @Test fun `special proto bandwidth codes map to fractional kHz`() {
        assertEquals(62.5, LoraParams.bandwidthFromProto(62), 0.001)
        assertEquals(203.125, LoraParams.bandwidthFromProto(200), 0.001)
        assertEquals(250.0, LoraParams.bandwidthFromProto(250), 0.001)
    }
}
