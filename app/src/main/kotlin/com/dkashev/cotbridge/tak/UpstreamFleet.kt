package com.dkashev.cotbridge.tak

import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Управляет N [UpstreamTakClient]'ами — по одному на каждый импортированный DataPackage.
 * Persistent: каждый клиент держит постоянное TLS-соединение и переподключается.
 *
 * Маршрутизация исходящего: [sendForCallsign] ищет клиента по callsign'у мешевого юзера.
 * Если клиента нет — возвращает false (вызывающий код решает что делать: fallback на маркер,
 * notification и т.д.).
 */
class UpstreamFleet(
    private val onUpstreamEvent: (callsign: String, cot: String) -> Unit,
    private val onClientConnected: (callsign: String) -> Unit,
    private val onClientDisconnected: (callsign: String, err: Throwable?) -> Unit,
) {

    private val clients = ConcurrentHashMap<String, UpstreamTakClient>()
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope, entries: List<CertVault.Entry>) {
        this.scope = scope
        entries.forEach { addAndStart(it) }
    }

    fun stop() {
        clients.values.forEach { it.stop() }
        clients.clear()
        scope = null
    }

    /** Добавить новый cert в работу. Сразу открывает TLS-сессию. */
    fun add(entry: CertVault.Entry) {
        val s = scope ?: return
        addAndStart(entry).start(s)
    }

    /** Удалить cert + закрыть его сессию. */
    fun remove(callsign: String) {
        clients.remove(callsign)?.stop()
    }

    /** Отправить CoT XML под cert'ом юзера [callsign]. Вернёт false если cert не зарегистрирован или не подключён. */
    fun sendForCallsign(callsign: String, cotXml: String): Boolean =
        clients[callsign]?.send(cotXml) ?: false

    fun hasCert(callsign: String): Boolean = clients.containsKey(callsign)

    fun count(): Int = clients.size

    fun anyConnected(): Boolean = clients.values.any { it.isConnected }

    fun statuses(): Map<String, Boolean> =
        clients.mapValues { it.value.isConnected }

    private fun addAndStart(entry: CertVault.Entry): UpstreamTakClient {
        val client = UpstreamTakClient(
            callsign = entry.callsign,
            sslContext = entry.ssl,
            host = entry.host,
            port = entry.port,
            onEvent = { cot -> onUpstreamEvent(entry.callsign, cot) },
            onConnected = { onClientConnected(entry.callsign) },
            onDisconnected = { err -> onClientDisconnected(entry.callsign, err) },
        )
        clients[entry.callsign] = client
        scope?.let(client::start)
        return client
    }
}
