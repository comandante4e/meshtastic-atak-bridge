package com.dkashev.cotbridge.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUidTest {

    @Test fun `same inputs give the same uid (cross-gateway dedup)`() {
        val a = ChatUid.of("Tunec", "привет", 1_000L)
        val b = ChatUid.of("Tunec", "привет", 1_000L)
        assertEquals(a, b)
    }

    @Test fun `different second gives a different uid (repeat messages survive)`() {
        assertNotEquals(
            ChatUid.of("Tunec", "привет", 1_000L),
            ChatUid.of("Tunec", "привет", 1_001L),
        )
    }

    @Test fun `different message gives a different uid`() {
        assertNotEquals(
            ChatUid.of("Tunec", "привет", 1_000L),
            ChatUid.of("Tunec", "пока", 1_000L),
        )
    }

    @Test fun `uid carries the sender for readability`() {
        assertTrue(ChatUid.of("Tunec", "hi", 1_000L).startsWith("GeoChat.Tunec."))
    }
}
