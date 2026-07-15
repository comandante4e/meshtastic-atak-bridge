package com.dkashev.cotbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObratkaThrottleTest {

    private var now = 0L

    private fun throttle(budget: Int = 60, threshold: Int = 50) =
        ObratkaThrottle<String>(
            budgetPerMin = { budget },
            movementThresholdM = { threshold },
            now = { now },
        )

    /** Даём боту накопить токенов, не проверяя выдачу. */
    private fun advance(ms: Long) { now += ms }

    @Test fun `chat jumps ahead of positions`() {
        val t = throttle()
        t.offerPosition("a", 0.0, 0.0, "pos-a")
        t.offerChat("chat")
        advance(60_000)
        assertEquals("chat", t.poll())
        assertEquals("pos-a", t.poll())
    }

    @Test fun `standing still is suppressed, moving is not`() {
        val t = throttle(threshold = 50)
        t.offerPosition("a", 59.0, 30.0, "first")
        advance(60_000)
        assertEquals("first", t.poll())

        // ~11 м — меньше порога.
        t.offerPosition("a", 59.0001, 30.0, "still")
        assertNull(t.poll())
        assertEquals(1, t.suppressedStill)

        // ~222 м — больше порога.
        t.offerPosition("a", 59.002, 30.0, "moved")
        assertEquals("moved", t.poll())
    }

    @Test fun `stationary contact is refreshed after staleness so it does not go stale in ATAK`() {
        val t = ObratkaThrottle<String>(
            budgetPerMin = { 60 },
            movementThresholdM = { 50 },
            now = { now },
            refreshMs = 300_000,
        )
        t.offerPosition("a", 59.0, 30.0, "first")
        advance(60_000)
        assertEquals("first", t.poll())

        advance(299_000)
        t.offerPosition("a", 59.0, 30.0, "still-early")
        assertNull("рано — подавлено", t.poll())

        advance(2_000) // перевалили refreshMs
        t.offerPosition("a", 59.0, 30.0, "still-late")
        assertEquals("still-late", t.poll())
    }

    @Test fun `coalescing keeps only the latest position per contact`() {
        val t = throttle(budget = 1)
        t.offerPosition("a", 59.0, 30.0, "v1")
        t.offerPosition("a", 59.01, 30.0, "v2")
        t.offerPosition("a", 59.02, 30.0, "v3")
        advance(60_000)
        assertEquals("в меш едет свежая точка, а не хвост устаревших", "v3", t.poll())
        assertNull(t.poll())
        assertEquals(2, t.coalesced)
    }

    @Test fun `budget caps the rate — server flood cannot drown the mesh`() {
        val t = throttle(budget = 6) // 6 пкт/мин = 1 в 10 с
        repeat(100) { i -> t.offerPosition("u$i", 59.0 + i, 30.0, "p$i") }

        // Стартовый burst — пара пакетов, дальше строго по бюджету.
        var sent = 0
        while (t.poll() != null) sent++
        assertTrue("burst=$sent", sent <= 3)

        advance(10_000)
        assertNotNull(t.poll())
        assertNull("второй в том же окне — эфира нет", t.poll())

        advance(60_000)
        var inMinute = 0
        while (t.poll() != null) inMinute++
        assertTrue("за минуту не больше бюджета: $inMinute", inMinute <= 7)
    }

    @Test fun `fair round-robin — one chatty contact cannot starve the rest`() {
        val t = throttle(budget = 600)
        t.offerPosition("a", 59.0, 30.0, "a1")
        t.offerPosition("b", 58.0, 30.0, "b1")
        t.offerPosition("c", 57.0, 30.0, "c1")
        // «a» тараторит, но уже стоит в очереди — только освежает свою запись, не лезет вперёд.
        t.offerPosition("a", 59.001, 30.0, "a2")
        // Между выдачами прокручиваем время: стартовый burst маленький, токены копятся.
        fun next(): String? { advance(1_000); return t.poll() }
        assertEquals("a2", next())
        assertEquals("b1", next())
        assertEquals("c1", next())
    }

    @Test fun `chat queue is bounded — oldest dropped, never unbounded growth`() {
        val t = ObratkaThrottle<String>(
            budgetPerMin = { 60 },
            movementThresholdM = { 50 },
            now = { now },
            chatQueueCap = 3,
        )
        repeat(5) { t.offerChat("m$it") }
        assertEquals(2, t.chatDropped)
        advance(60_000)
        assertEquals("m2", t.poll())
    }

    @Test fun `forget drops contact history`() {
        val t = throttle()
        t.offerPosition("a", 59.0, 30.0, "first")
        advance(60_000)
        assertEquals("first", t.poll())
        t.forget("a")
        // История стёрта → следующая точка считается первой и порог не применяется.
        t.offerPosition("a", 59.0, 30.0, "again")
        assertEquals("again", t.poll())
    }

    @Test fun `haversine sanity`() {
        // 0.001° широты ≈ 111 м.
        val d = ObratkaThrottle.distanceM(59.0, 30.0, 59.001, 30.0)
        assertTrue("d=$d", d in 100.0..120.0)
    }
}
