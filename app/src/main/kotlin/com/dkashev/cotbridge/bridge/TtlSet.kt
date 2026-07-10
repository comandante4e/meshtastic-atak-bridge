package com.dkashev.cotbridge.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * Множество строк с временем жизни. Используется для «callsign'ов мешевого происхождения» —
 * тех, кого мост форвардит С меша НА URPC. При обратке (URPC→меш) события с этими callsign'ами
 * дропаются как эхо: URPC ремапит наш `MESH-X` в `SERVER-X`, поэтому дедуп по uid не срабатывает,
 * а callsign в CoT сохраняется — по нему и фильтруем.
 *
 * Clock инъектируется → чистый юнит-тест.
 */
class TtlSet(
    private val ttlMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val seen = ConcurrentHashMap<String, Long>()

    fun mark(key: String) {
        seen[key] = clock()
    }

    fun contains(key: String): Boolean {
        val at = seen[key] ?: return false
        if (clock() - at > ttlMs) {
            seen.remove(key)
            return false
        }
        return true
    }

    fun size(): Int = seen.size
}
