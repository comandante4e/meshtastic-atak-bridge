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
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
) {

    @Volatile private var service: IMeshService? = null
    @Volatile private var bound = false

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

    private val atakReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != MeshtasticIntent.ACTION_RECEIVED_ATAK_PLUGIN) return
            val packet = intent.getParcelableExtraCompat(
                MeshtasticIntent.EXTRA_PAYLOAD, DataPacket::class.java
            ) ?: return
            val bytes = packet.bytes?.toByteArray() ?: return
            val tak = try { TAKPacket.ADAPTER.decode(bytes) } catch (_: Throwable) { return }
            onPacketReceived(tak)
        }
    }

    fun start() {
        val filter = IntentFilter(MeshtasticIntent.ACTION_RECEIVED_ATAK_PLUGIN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(atakReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(atakReceiver, filter)
        }

        val intent = Intent().setClassName(
            MESHTASTIC_PACKAGE,
            "com.geeksville.mesh.service.MeshService",
        )
        val ok = context.bindService(intent, serviceConn, Context.BIND_AUTO_CREATE)
        if (!ok) {
            runCatching { context.unbindService(serviceConn) }
            throw IllegalStateException(
                "Не получилось привязаться к Meshtastic Android — установлен ли он?"
            )
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(atakReceiver) }
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

    private companion object {
        const val MESHTASTIC_PACKAGE = "com.geeksville.mesh"
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
