package com.dkashev.cotbridge.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Роль инстанса моста в связке «раздал друзьям». */
enum class GatewayRole {
    /** Никогда не ретранслирует. Пассивный клиент. */
    OFF,

    /** Участвует в выборах: ровно один AUTO-инстанс в связном сегменте меша становится активным. */
    AUTO,

    /** Всегда активен (ручной единственный шлюз, без failover). Тоже маячит, чтобы AUTO уступили. */
    FORCE,
}

/**
 * Кодек маяка выборов. Летит по мешу на портнуме PRIVATE_APP (официальный ATAK-плагин его
 * игнорирует — не засоряет чат/карту у мешевых клиентов). 10 байт: "GW" + 8 байт ключа (BE).
 */
object Beacon {
    private const val M0 = 'G'.code.toByte()
    private const val M1 = 'W'.code.toByte()
    const val SIZE = 10

    fun encode(key: Long): ByteArray {
        val b = ByteArray(SIZE)
        b[0] = M0; b[1] = M1
        for (i in 0 until 8) b[2 + i] = (key ushr (8 * (7 - i))).toByte()
        return b
    }

    fun decodeKey(bytes: ByteArray): Long? {
        if (bytes.size != SIZE || bytes[0] != M0 || bytes[1] != M1) return null
        var k = 0L
        for (i in 0 until 8) k = (k shl 8) or (bytes[2 + i].toLong() and 0xFF)
        return k
    }
}

/**
 * Чистый (без Android/корутин) конечный автомат выборов — полностью юнит-тестируемый.
 *
 * Инвариант: в связном сегменте меша ровно один шлюз ACTIVE. Победитель — детерминированный
 * [myKey] = (priority shl 32) | electionId, меньший выигрывает. Активный периодически шлёт маяк.
 * Standby, не слышащий более сильного (меньший ключ) активного маяка дольше [timeoutMs], занимает
 * вакансию. При заживлении партиции два ACTIVE, услышав друг друга, — худший немедленно уступает.
 *
 * [timeoutMs] служит и временем протухания маяков мёртвого шлюза → это и есть время failover.
 */
class ElectionCore(
    val myKey: Long,
    private val beaconIntervalMs: Long,
    private val timeoutMs: Long,
) {
    enum class State { STANDBY, ACTIVE }

    var state: State = State.STANDBY
        private set

    private val activePeers = HashMap<Long, Long>() // peerKey -> lastSeenMs
    private var lastSentBeaconAt: Long? = null // null = ещё не слали (или только промоутнулись)

    val isActive: Boolean get() = state == State.ACTIVE

    /** Услышали активный маяк соседа. Немедленный yield, если сосед сильнее нас. */
    fun onBeacon(peerKey: Long, nowMs: Long) {
        if (peerKey == myKey) return
        activePeers[peerKey] = nowMs
        if (state == State.ACTIVE) {
            val best = bestActiveKey(nowMs)
            if (best != null && best < myKey) state = State.STANDBY
        }
    }

    /**
     * Продвинуть автомат. [capable] = меш поднят И (cert'ов нет ИЛИ хотя бы один upstream жив).
     * Возвращает true, если прямо сейчас нужно отправить маяк.
     */
    fun tick(nowMs: Long, capable: Boolean): Boolean {
        val wasActive = state == State.ACTIVE
        state = if (!capable) {
            State.STANDBY
        } else {
            val best = bestActiveKey(nowMs)
            if (best != null && best < myKey) State.STANDBY else State.ACTIVE
        }
        if (state != State.ACTIVE) return false
        // Только что заняли роль — объявиться маяком немедленно (чтобы соседи быстро уступили).
        if (!wasActive) lastSentBeaconAt = null
        val last = lastSentBeaconAt
        if (last == null || nowMs - last >= beaconIntervalMs) {
            lastSentBeaconAt = nowMs
            return true
        }
        return false
    }

    private fun bestActiveKey(nowMs: Long): Long? {
        val it = activePeers.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value >= timeoutMs) it.remove()
        }
        return activePeers.keys.minOrNull()
    }

    companion object {
        /** priority (меньше = главнее), electionId — уникальный тайбрейкер на инсталляцию. */
        fun keyOf(priority: Int, electionId: Int): Long =
            (priority.toLong() shl 32) or (electionId.toLong() and 0xFFFFFFFFL)
    }
}

/**
 * Android-обёртка над [ElectionCore]: тикер, отправка маяков активным, приём чужих маяков,
 * StateFlow активности для гейтинга ретрансляции в сервисе.
 */
class GatewayElector(
    val role: GatewayRole,
    private val myKey: Long,
    private val beaconIntervalMs: Long,
    timeoutMs: Long,
    private val capable: () -> Boolean,
    private val sendBeacon: (Long) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val core = ElectionCore(myKey, beaconIntervalMs, timeoutMs)
    private val _active = MutableStateFlow(role == GatewayRole.FORCE)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private var job: Job? = null

    fun onBeacon(peerKey: Long) {
        if (role != GatewayRole.AUTO) return
        core.onBeacon(peerKey, clock())
        _active.value = core.isActive
    }

    fun start(scope: CoroutineScope) {
        when (role) {
            GatewayRole.OFF -> _active.value = false
            GatewayRole.FORCE -> {
                _active.value = true
                job = scope.launch {
                    while (isActive) {
                        sendBeacon(myKey)
                        delay(beaconIntervalMs)
                    }
                }
            }
            GatewayRole.AUTO -> {
                job = scope.launch {
                    while (isActive) {
                        val send = core.tick(clock(), capable())
                        _active.value = core.isActive
                        if (send) sendBeacon(myKey)
                        delay(TICK_MS)
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        const val TICK_MS = 3000L
    }
}
