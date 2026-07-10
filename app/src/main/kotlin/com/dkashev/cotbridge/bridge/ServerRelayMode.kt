package com.dkashev.cotbridge.bridge

/** Как гнать обратку URPC → меш. */
enum class ServerRelayMode {
    /**
     * Через мультикаст от локального ATAK. Зависит от того, ретранслит ли ATAK входящий
     * серверный CoT в mesh-SA multicast — не гарантировано. Запасной вариант (напр. когда
     * cert'ов в мост не импортировано и URPC достижим только через ATAK оператора).
     */
    MULTICAST,

    /**
     * Напрямую из RX per-cert TLS-стримов [com.dkashev.cotbridge.tak.UpstreamFleet] + фильтр эха
     * по callsign. Не зависит от поведения ATAK; работает, пока подключён хотя бы один cert.
     * Рекомендуемый режим для URPC (per-cert, Модель А).
     */
    UPSTREAM_RX,
}
