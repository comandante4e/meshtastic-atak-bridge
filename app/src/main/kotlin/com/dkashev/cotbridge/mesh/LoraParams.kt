package com.dkashev.cotbridge.mesh

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

/**
 * Радио-параметры меша и расчёт бюджета эфира под обратку сервер→меш.
 *
 * Зачем: LoRa физически не может быть зеркалом TAK-сервера. На дефолтном LONG_FAST пакет
 * ~60 байт занимает ~680 мс эфира — потолок канала ~88 пкт/мин на ВСЮ сеть. Двадцать
 * игроков с PLI раз в 5 с — это 240 пкт/мин, т.е. 27-кратный перегруз: очередь рубится,
 * задержки в минуты, маяки выборов теряются. Поэтому мост обязан быть фильтром, а не
 * трубой, и его бюджет должен считаться от РЕАЛЬНОГО пресета ноды, а не задаваться на глаз.
 */
object LoraParams {

    /** SF/BW(кГц)/CR-знаменатель. Значения — как в прошивке (RadioInterface.cpp). */
    data class Radio(val spreadFactor: Int, val bandwidthKhz: Double, val codingRate: Int)

    /**
     * Пресеты Meshtastic. Имена совпадают с `Config.LoRaConfig.ModemPreset`, матчим по
     * имени — не тащим proto-типы в чистый модуль (иначе не юнит-тестируется на JVM).
     */
    private val PRESETS: Map<String, Radio> = mapOf(
        "SHORT_TURBO" to Radio(7, 500.0, 5),
        "SHORT_FAST" to Radio(7, 250.0, 5),
        "SHORT_SLOW" to Radio(8, 250.0, 5),
        "MEDIUM_FAST" to Radio(9, 250.0, 5),
        "MEDIUM_SLOW" to Radio(10, 250.0, 5),
        "LONG_FAST" to Radio(11, 250.0, 5),
        "LONG_MODERATE" to Radio(11, 125.0, 8),
        "LONG_SLOW" to Radio(12, 125.0, 8),
        "VERY_LONG_SLOW" to Radio(12, 62.5, 8),
        "LONG_TURBO" to Radio(11, 500.0, 5),
    )

    /** Дефолт прошивки и наш fallback, когда пресет с ноды прочитать не удалось. */
    val DEFAULT = PRESETS.getValue("LONG_FAST")
    const val DEFAULT_NAME = "LONG_FAST"

    fun byPresetName(name: String?): Radio? = name?.let { PRESETS[it.uppercase()] }

    /**
     * Пересчёт «специальных» значений bandwidth из proto (там кГц, но часть чисел —
     * сокращения дробных значений).
     */
    fun bandwidthFromProto(raw: Int): Double = when (raw) {
        31 -> 31.25
        62 -> 62.5
        200 -> 203.125
        400 -> 406.25
        800 -> 812.5
        1600 -> 1625.0
        else -> raw.toDouble()
    }

    /**
     * Время в эфире одного LoRa-пакета, секунды. Каноническая формула из даташита SX127x.
     *
     * @param payloadBytes полезная нагрузка (включая заголовок Meshtastic)
     * @param preambleSymbols длина преамбулы (Meshtastic — 16)
     */
    fun airtimeSec(radio: Radio, payloadBytes: Int, preambleSymbols: Int = 16): Double {
        val sf = radio.spreadFactor
        val bw = radio.bandwidthKhz * 1000.0
        val tSym = 2.0.pow(sf) / bw
        // Low Data Rate Optimize включается прошивкой, когда символ длиннее 16 мс.
        val de = if (tSym > 0.016) 1 else 0
        val tPreamble = (preambleSymbols + 4.25) * tSym
        val num = 8 * payloadBytes - 4 * sf + 28 + 16
        val den = 4 * (sf - 2 * de)
        val payloadSymbols = 8 + max(ceil(num.toDouble() / den).toInt() * radio.codingRate, 0)
        return tPreamble + payloadSymbols * tSym
    }

    /** Потолок канала: сколько таких пакетов влезает в минуту при 100% занятости эфира. */
    fun capacityPktPerMin(radio: Radio, payloadBytes: Int = TYPICAL_PAYLOAD): Double =
        60.0 / airtimeSec(radio, payloadBytes)

    /**
     * Бюджет обратки сервер→меш, пакетов в минуту — «чуть ниже» потолка пресета.
     *
     * Берём [AIR_SHARE] эфира: это ниже 10% duty cycle EU868 и сильно ниже порога
     * загруженности канала (~25%), по которому прошивка считает эфир занятым. Остальное
     * оставляем мешу на его собственный трафик и ретрансляции (каждый наш пакет соседи
     * ещё и переповторят).
     *
     * Бюджет автоматически едет за пресетом: LONG_FAST → единицы пкт/мин, SHORT_FAST → десятки.
     */
    fun budgetPktPerMin(radio: Radio, payloadBytes: Int = TYPICAL_PAYLOAD): Int =
        max((capacityPktPerMin(radio, payloadBytes) * AIR_SHARE).toInt(), MIN_BUDGET)

    /** Типичный TAKPacket-PLI в эфире (protobuf + заголовок Meshtastic). */
    const val TYPICAL_PAYLOAD = 60

    /** Доля эфира под обратку. Консервативно: < EU-duty 10% и << порога занятости 25%. */
    const val AIR_SHARE = 0.08

    /** Даже на самом медленном пресете оставляем хоть какую-то обратку. */
    const val MIN_BUDGET = 1
}
