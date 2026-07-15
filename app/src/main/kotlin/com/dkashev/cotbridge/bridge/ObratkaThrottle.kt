package com.dkashev.cotbridge.bridge

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Планировщик обратки сервер→меш: превращает поток CoT с TAK-сервера в тоненькую струйку,
 * которую физически тянет LoRa.
 *
 * Механика (в порядке применения):
 *  1. **Приоритет чата** — чат/тревога уходят вперёд позиций и не подчиняются порогу движения.
 *     Чат — редкий и ценный, позиция — частая и восстановимая.
 *  2. **Порог движения** — позицию не шлём, если контакт сдвинулся меньше [movementThresholdM].
 *     Стоящий игрок = нулевой трафик. Чтобы контакт не «протух» в ATAK, раз в [refreshMs]
 *     позиция уходит всё равно.
 *  3. **Коалесcинг** — на контакт держим только ПОСЛЕДНЮЮ позицию, а не очередь. Пока пакет
 *     ждёт эфира, новые обновления перезаписывают его: в меш уедет актуальная точка, а не
 *     хвост устаревших.
 *  4. **Бюджет эфира** (token bucket) — жёсткий потолок пкт/мин, считается из пресета ноды
 *     ([com.dkashev.cotbridge.mesh.LoraParams]). Что бы ни творилось на сервере, меш выживает.
 *  5. **Честность** — выдача в порядке ожидания (FIFO по контактам), поэтому один болтливый
 *     игрок не выест весь бюджет и не заморит остальных.
 *
 * Generic по [T], чтобы модуль оставался чистым и тестировался на JVM без proto-типов.
 * Не потокобезопасен — дёргать из одного треда/скоупа.
 */
class ObratkaThrottle<T>(
    private val budgetPerMin: () -> Int,
    private val movementThresholdM: () -> Int,
    private val now: () -> Long,
    private val refreshMs: Long = DEFAULT_REFRESH_MS,
    private val chatQueueCap: Int = DEFAULT_CHAT_CAP,
) {

    private class Sent(val lat: Double, val lon: Double, val at: Long)

    private val pending = LinkedHashMap<String, T>()
    private val coords = HashMap<String, Pair<Double, Double>>()
    private val lastSent = HashMap<String, Sent>()
    private val chat = ArrayDeque<T>()

    private var tokens = INITIAL_BURST
    private var lastRefill: Long? = null

    /** Позиций подавлено порогом движения. */
    var suppressedStill: Int = 0
        private set

    /** Позиций схлопнуто коалесcингом (перезаписаны свежими, не доехав до эфира). */
    var coalesced: Int = 0
        private set

    /** Сообщений чата выброшено переполнением очереди. */
    var chatDropped: Int = 0
        private set

    val pendingCount: Int get() = pending.size + chat.size

    /** Чат/тревога — вне порога движения, вперёд очереди. */
    fun offerChat(payload: T) {
        chat.addLast(payload)
        while (chat.size > chatQueueCap) {
            chat.removeFirst()
            chatDropped++
        }
    }

    /** Позиция контакта. Пройдёт порог движения — встанет в очередь (или перезапишет свою). */
    fun offerPosition(uid: String, lat: Double, lon: Double, payload: T) {
        // Уже стоит в очереди — просто освежаем нагрузку, порог тут не при чём: пакет всё равно уедет.
        if (pending.containsKey(uid)) {
            pending[uid] = payload
            coords[uid] = lat to lon
            coalesced++
            return
        }
        val prev = lastSent[uid]
        val moved = prev == null || distanceM(prev.lat, prev.lon, lat, lon) >= movementThresholdM()
        val stale = prev != null && now() - prev.at >= refreshMs
        if (!moved && !stale) {
            suppressedStill++
            return
        }
        pending[uid] = payload
        coords[uid] = lat to lon
    }

    /**
     * Следующий пакет, если бюджет позволяет. `null` — либо нечего слать, либо эфир исчерпан;
     * вызывать периодически (pump).
     */
    fun poll(): T? {
        refill()
        if (tokens < 1.0) return null

        chat.removeFirstOrNull()?.let {
            tokens -= 1.0
            return it
        }
        // Порядок вставки = порядок ожидания: честный round-robin без отдельного ротора.
        val uid = pending.keys.firstOrNull() ?: return null
        val payload = pending.remove(uid) ?: return null
        coords.remove(uid)?.let { (lat, lon) -> lastSent[uid] = Sent(lat, lon, now()) }
        tokens -= 1.0
        return payload
    }

    /** Контакт ушёл из меша/сервера — забыть его историю. */
    fun forget(uid: String) {
        pending.remove(uid)
        coords.remove(uid)
        lastSent.remove(uid)
    }

    private fun refill() {
        val t = now()
        val prev = lastRefill
        lastRefill = t
        if (prev == null) return
        val perMs = budgetPerMin().coerceAtLeast(1) / 60_000.0
        val burst = maxOf(INITIAL_BURST, budgetPerMin() * BURST_SHARE)
        tokens = min(tokens + (t - prev) * perMs, burst)
    }

    companion object {
        /** Даже неподвижный контакт освежаем, иначе он протухнет и пропадёт с карты ATAK. */
        const val DEFAULT_REFRESH_MS = 5 * 60_000L
        const val DEFAULT_CHAT_CAP = 20
        private const val INITIAL_BURST = 2.0
        private const val BURST_SHARE = 0.25

        /** Haversine, метры. */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return r * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
