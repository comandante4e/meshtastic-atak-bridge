package com.dkashev.cotbridge.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallsignFromCotTest {

    @Test fun `extracts contact callsign from server CoT (uid remapped by URPC)`() {
        val xml = """<event version="2.0" uid="SERVER-7" type="a-f-G-U-C" how="m-g">""" +
            """<point lat="55.7" lon="37.6" hae="0" ce="9" le="9"/>""" +
            """<detail><contact callsign="Tunec"/><__group name="Cyan" role="Team Member"/></detail></event>"""
        assertEquals("Tunec", AtakPacketConverter.callsignFromCot(xml))
    }

    @Test fun `null when there is no contact element`() {
        val xml = """<event uid="X" type="b-t-f"><point lat="0" lon="0"/><detail></detail></event>"""
        assertNull(AtakPacketConverter.callsignFromCot(xml))
    }
}
