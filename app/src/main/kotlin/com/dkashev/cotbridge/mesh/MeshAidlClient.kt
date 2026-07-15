package com.dkashev.cotbridge.mesh

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.api.MeshtasticIntent
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.service.IMeshService
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.TAKPacket

/**
 * Тонкая обёртка над bound service `com.geeksville.mesh.service.MeshService` из приложения
 * Meshtastic Android. Транслирует TX [TAKPacket] → меш, RX → callback.
 *
 * Использует deprecated AIDL, который Meshtastic v2.7.13 ещё поддерживает.
 */
class MeshAidlClient(
    private val context: Context,
    private val onPacketReceived: (TAKPacket) -> Unit,
    private val onBeaconReceived: (ByteArray) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
) {

    @Volatile private var service: IMeshService? = null
    @Volatile private var bound = false

    /** Меш реально привязан (сервис жив). Используется предикатом capability выборов. */
    val connected: Boolean get() = bound && service != null

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IMeshService.Stub.asInterface(binder)
            bound = true
            onConnected()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            onDisconnected()
        }
    }

    private val meshReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val packet = intent?.getParcelableExtraCompat(
                MeshtasticIntent.EXTRA_PAYLOAD, DataPacket::class.java
            ) ?: return
            val bytes = packet.bytes?.toByteArray() ?: return
            when (intent.action) {
                MeshtasticIntent.ACTION_RECEIVED_ATAK_PLUGIN -> {
                    val tak = try { TAKPacket.ADAPTER.decode(bytes) } catch (_: Throwable) { return }
                    onPacketReceived(tak)
                }
                // Маяки выборов на отдельном портнуме — официальный ATAK-плагин их не трогает.
                MeshtasticIntent.ACTION_RECEIVED_PRIVATE_APP -> onBeaconReceived(bytes)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(MeshtasticIntent.ACTION_RECEIVED_ATAK_PLUGIN)
            addAction(MeshtasticIntent.ACTION_RECEIVED_PRIVATE_APP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(meshReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(meshReceiver, filter)
        }

        // Bind by the stable intent-filter action, NOT a hardcoded class name.
        // Meshtastic moved the service class com.geeksville.mesh.service.MeshService ->
        // org.meshtastic.core.service.MeshService (seen on 2.7.14), but kept the action
        // "com.geeksville.mesh.Service". Explicit setClassName silently fails to bind after
        // the rename; the action survives across versions.
        val intent = Intent(MESHTASTIC_SERVICE_ACTION).setPackage(MESHTASTIC_PACKAGE)
        val ok = context.bindService(intent, serviceConn, Context.BIND_AUTO_CREATE)
        if (!ok) {
            runCatching { context.unbindService(serviceConn) }
            throw IllegalStateException(
                "Не получилось привязаться к Meshtastic Android — установлен ли он?"
            )
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(meshReceiver) }
        if (bound) {
            runCatching { context.unbindService(serviceConn) }
            bound = false
        }
        service = null
    }

    /** Отправить TAKPacket в меш на broadcast-адрес (всем в канале 0). */
    fun send(packet: TAKPacket): Boolean {
        val svc = service ?: return false
        val data = DataPacket(
            to = DataPacket.ID_BROADCAST,
            bytes = packet.encode().toByteString(),
            dataType = PortNum.ATAK_PLUGIN.value,
        ).apply {
            channel = 0
            wantAck = false
        }
        return try {
            svc.send(data)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Радио-параметры ноды (пресет либо кастомные SF/BW/CR) — из них считается бюджет эфира
     * под обратку. `null`, если нода не подключена или конфиг не читается: тогда зовущий
     * должен взять [LoraParams.DEFAULT] (LONG_FAST — дефолт прошивки и самый узкий из ходовых).
     */
    fun loraRadio(): LoraParams.Radio? {
        val svc = service ?: return null
        val cfg = try {
            ChannelSet.ADAPTER.decode(svc.getChannelSet() ?: return null).lora_config
        } catch (_: Throwable) {
            null
        } ?: return null

        if (cfg.use_preset) return LoraParams.byPresetName(cfg.modem_preset.name)

        // Кастомная модуляция: доверяем только правдоподобным значениям.
        val sf = cfg.spread_factor
        val bw = LoraParams.bandwidthFromProto(cfg.bandwidth)
        val cr = cfg.coding_rate
        if (sf !in 7..12 || bw <= 0.0 || cr !in 5..8) return null
        return LoraParams.Radio(sf, bw, cr)
    }

    /** Имя пресета ноды для UI/лога («LONG_FAST», «CUSTOM», null — не прочитали). */
    fun loraPresetName(): String? {
        val svc = service ?: return null
        return try {
            val cfg = ChannelSet.ADAPTER.decode(svc.getChannelSet() ?: return null).lora_config
            if (cfg?.use_preset == true) cfg.modem_preset.name else if (cfg != null) "CUSTOM" else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Отправить маяк выборов в меш на портнуме PRIVATE_APP (не виден ATAK-плагину). */
    fun sendBeacon(bytes: ByteArray): Boolean {
        val svc = service ?: return false
        val data = DataPacket(
            to = DataPacket.ID_BROADCAST,
            bytes = bytes.toByteString(),
            dataType = PortNum.PRIVATE_APP.value,
        ).apply {
            channel = 0
            wantAck = false
        }
        return try {
            svc.send(data)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private companion object {
        const val MESHTASTIC_PACKAGE = "com.geeksville.mesh"
        const val MESHTASTIC_SERVICE_ACTION = "com.geeksville.mesh.Service"
    }
}

// Compat helper for getParcelableExtra
@Suppress("DEPRECATION", "UNCHECKED_CAST")
private fun <T> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, clazz)
    } else {
        getParcelableExtra(name) as? T
    }
