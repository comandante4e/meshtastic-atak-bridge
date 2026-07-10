package com.dkashev.cotbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dkashev.cotbridge.BridgeApp
import com.dkashev.cotbridge.MainActivity
import com.dkashev.cotbridge.bridge.Beacon
import com.dkashev.cotbridge.bridge.BridgeStateHolder
import com.dkashev.cotbridge.bridge.ConnectionState
import com.dkashev.cotbridge.bridge.ElectionCore
import com.dkashev.cotbridge.bridge.GatewayElector
import com.dkashev.cotbridge.cot.AtakPacketConverter
import com.dkashev.cotbridge.cot.AtakPacketConverter.callsign
import com.dkashev.cotbridge.cot.CotXml
import com.dkashev.cotbridge.cot.LoopDetector
import com.dkashev.cotbridge.mesh.MeshAidlClient
import com.dkashev.cotbridge.net.AtakInjector
import com.dkashev.cotbridge.net.AtakMulticastListener
import com.dkashev.cotbridge.tak.UpstreamFleet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.random.Random
import org.meshtastic.proto.TAKPacket

class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var multicast: AtakMulticastListener? = null
    private var injector: AtakInjector? = null
    private var mesh: MeshAidlClient? = null
    private var fleet: UpstreamFleet? = null
    private var elector: GatewayElector? = null
    private val loop = LoopDetector(capacity = 512)
    private var myCallsign: String = "Bridge"

    @Volatile private var bridgeUp = false

    private fun setShouldRun(v: Boolean) =
        getSharedPreferences("bridge_svc", Context.MODE_PRIVATE).edit().putBoolean("should_run", v).apply()

    private fun shouldRun(): Boolean =
        getSharedPreferences("bridge_svc", Context.MODE_PRIVATE).getBoolean("should_run", false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                setShouldRun(true)
                startBridge()
            }
            ACTION_STOP -> {
                setShouldRun(false)
                stopBridge()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // START_STICKY передоставляет НУ intent после того, как ОС убила процесс (агрессивный
            // OEM-киллер энергосбережения). Раньше это игнорировалось → сервис жил, а мост не
            // поднимался. Теперь поднимаем заново, если по флагу мы должны работать.
            else -> if (shouldRun()) startBridge() else stopSelf()
        }
        return START_STICKY
    }

    private fun startBridge() {
        if (bridgeUp) return // защита от двойного старта (повторный ACTION_START / гонка рестарта)
        bridgeUp = true
        startForeground(NOTIF_ID, buildNotification("Запуск моста..."))
        BridgeStateHolder.update { it.copy(running = true, lastError = null) }
        BridgeStateHolder.log("Запуск bridge service")
        BridgeStateHolder.log("Совет: исключи CoT Bridge из энергосбережения, иначе OEM убьёт сервис")

        scope.launch {
            try {
                val cfg = BridgeApp.instance.preferences.current()
                myCallsign = cfg.myCallsign

                injector = AtakInjector(host = "127.0.0.1", port = cfg.atakInputPort)
                BridgeStateHolder.log("Инжектор готов: UDP 127.0.0.1:${cfg.atakInputPort}")

                BridgeStateHolder.update { it.copy(localTak = ConnectionState.CONNECTING) }
                val meshClient = MeshAidlClient(
                    context = this@BridgeService,
                    onPacketReceived = ::onMeshPacket,
                    onBeaconReceived = { bytes ->
                        Beacon.decodeKey(bytes)?.let { key -> elector?.onBeacon(key) }
                    },
                    onConnected = {
                        BridgeStateHolder.update { it.copy(localTak = ConnectionState.CONNECTED) }
                        BridgeStateHolder.log("Meshtastic AIDL: подключено")
                    },
                    onDisconnected = {
                        BridgeStateHolder.update { it.copy(localTak = ConnectionState.CONNECTING) }
                        BridgeStateHolder.log("Meshtastic AIDL: отвалился")
                    },
                )
                mesh = meshClient
                meshClient.start()

                val mc = AtakMulticastListener(
                    context = this@BridgeService,
                    groupAddress = cfg.multicastAddress,
                    port = cfg.multicastPort,
                    onEvent = ::onAtakEvent,
                    onError = { err ->
                        BridgeStateHolder.update { it.copy(multicast = ConnectionState.ERROR, lastError = err.message) }
                        BridgeStateHolder.log("Multicast ошибка: ${err.message}")
                    },
                )
                multicast = mc
                BridgeStateHolder.update { it.copy(multicast = ConnectionState.CONNECTED) }
                BridgeStateHolder.log("Слушаю multicast ${cfg.multicastAddress}:${cfg.multicastPort}")
                mc.start(scope)

                val entries = BridgeApp.instance.certVault.loadAll()
                BridgeStateHolder.update {
                    it.copy(upstreams = entries.associate { e -> e.callsign to ConnectionState.CONNECTING })
                }
                BridgeStateHolder.log("Загружено ${entries.size} cert'ов из vault")

                fleet = UpstreamFleet(
                    onUpstreamEvent = { _, _ -> /* per-user RX игнорим — multicast handles broadcasts */ },
                    onClientConnected = { cs ->
                        BridgeStateHolder.update { it.copy(upstreams = it.upstreams + (cs to ConnectionState.CONNECTED)) }
                        BridgeStateHolder.log("URPC [$cs]: connected")
                        updateNotification("Мост активен · ${connectedCount()} cert(s) · меш ${if (mesh != null) "OK" else "?"}")
                    },
                    onClientDisconnected = { cs, err ->
                        BridgeStateHolder.update { it.copy(upstreams = it.upstreams + (cs to ConnectionState.CONNECTING)) }
                        BridgeStateHolder.log("URPC [$cs]: разрыв — ${err?.message ?: "EOF"}, переподключаюсь...")
                    },
                ).also { it.start(scope, entries) }

                // --- Выборы единственного активного шлюза (анти-дубль при раздаче друзьям) ---
                var eid = cfg.electionId
                if (eid == 0) {
                    eid = Random.nextInt(1, Int.MAX_VALUE)
                    BridgeApp.instance.preferences.update { it.copy(electionId = eid) }
                }
                val gw = GatewayElector(
                    role = cfg.gatewayRole,
                    myKey = ElectionCore.keyOf(cfg.gatewayPriority, eid),
                    beaconIntervalMs = BEACON_INTERVAL_MS,
                    timeoutMs = BEACON_TIMEOUT_MS,
                    capable = {
                        val meshUp = mesh?.connected == true
                        val f = fleet
                        val upstreamOk = f == null || f.count() == 0 || f.anyConnected()
                        meshUp && upstreamOk
                    },
                    sendBeacon = { key -> mesh?.sendBeacon(Beacon.encode(key)) },
                )
                elector = gw
                gw.start(scope)
                scope.launch {
                    gw.active.collect { active ->
                        BridgeStateHolder.update {
                            it.copy(gatewayActive = active, gatewayRole = cfg.gatewayRole.name)
                        }
                    }
                }
                BridgeStateHolder.log(
                    "Роль шлюза: ${cfg.gatewayRole} (priority=${cfg.gatewayPriority}, id=$eid)"
                )
            } catch (t: Throwable) {
                BridgeStateHolder.update {
                    it.copy(running = false, lastError = t.message,
                        multicast = ConnectionState.ERROR, localTak = ConnectionState.ERROR)
                }
                BridgeStateHolder.log("Ошибка запуска: ${t.message}")
                stopBridge()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopBridge() {
        bridgeUp = false
        multicast?.stop()
        multicast = null
        mesh?.stop()
        mesh = null
        elector?.stop()
        elector = null
        fleet?.stop()
        fleet = null
        injector?.close()
        injector = null
        loop.reset()
        BridgeStateHolder.update {
            it.copy(running = false, multicast = ConnectionState.IDLE,
                localTak = ConnectionState.IDLE,
                upstreams = it.upstreams.mapValues { ConnectionState.IDLE })
        }
        BridgeStateHolder.log("Мост остановлен")
    }

    /** ATAK → multicast 6969 → bridge → меш (входящие с URPC форвардим в меш). */
    private fun onAtakEvent(xml: String) {
        BridgeStateHolder.update { it.copy(rxFromAtak = it.rxFromAtak + 1) }
        if (elector?.active?.value != true) {
            BridgeStateHolder.update { it.copy(droppedStandby = it.droppedStandby + 1) }
            return
        }
        val key = keyOf(xml) ?: return
        if (loop.markAndCheck(key)) {
            BridgeStateHolder.update { it.copy(droppedLoop = it.droppedLoop + 1) }
            return
        }
        val tak = AtakPacketConverter.cotToTakPacket(xml) ?: return
        val sent = mesh?.send(tak) ?: false
        if (sent) BridgeStateHolder.update { it.copy(txToMesh = it.txToMesh + 1) }
    }

    /** Меш → bridge → (либо UpstreamFleet под cert юзера, либо fallback маркер через ATAK). */
    private fun onMeshPacket(packet: TAKPacket) {
        BridgeStateHolder.update { it.copy(rxFromMesh = it.rxFromMesh + 1) }
        if (elector?.active?.value != true) {
            BridgeStateHolder.update { it.copy(droppedStandby = it.droppedStandby + 1) }
            return
        }
        val callsign = packet.callsign()
        val cot = AtakPacketConverter.takPacketToCot(packet) ?: return
        val key = keyOf(cot) ?: return
        if (loop.markAndCheck(key)) {
            BridgeStateHolder.update { it.copy(droppedLoop = it.droppedLoop + 1) }
            return
        }

        val viaCert = fleet?.sendForCallsign(callsign, cot) ?: false
        if (viaCert) {
            BridgeStateHolder.update { it.copy(txToUpstream = it.txToUpstream + 1) }
        } else {
            // Fallback: cert'а нет (или не подключён). PLI шлём как маркер u-d-p,
            // chat — как мой чат с префиксом [callsign]. Через ATAK на 4242,
            // ATAK сам форварднет на URPC под мой cert.
            val fallbackCot = when {
                packet.pli != null -> AtakPacketConverter.takPacketToMarkerCot(packet)
                packet.chat != null -> AtakPacketConverter.takPacketToPrefixedChatCot(packet, myCallsign)
                else -> null
            } ?: return
            // Пометим UID инжектнутого CoT, чтобы его мультикаст-эхо от ATAK не улетело назад в меш.
            keyOf(fallbackCot)?.let { loop.markAndCheck(it) }
            val ok = injector?.send(fallbackCot) ?: false
            if (ok) {
                BridgeStateHolder.update { it.copy(txFallback = it.txFallback + 1, txToAtak = it.txToAtak + 1) }
                if (packet.pli != null) {
                    BridgeStateHolder.log("Fallback для $callsign — нет cert'а, шлю u-d-p маркер. Импортируй DataPackage для $callsign в Серты.")
                    notifyMissingCert(callsign)
                }
            }
        }
    }

    private fun keyOf(xml: String): String? {
        val uid = CotXml.extractUid(xml) ?: return null
        val time = CotXml.extractTime(xml) ?: ""
        return "$uid|$time"
    }

    private fun connectedCount(): Int =
        BridgeStateHolder.state.value.upstreams.count { it.value == ConnectionState.CONNECTED }

    private fun notifyMissingCert(callsign: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_FALLBACK, "CoT Bridge — нет cert", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val tap = PendingIntent.getActivity(
            this, callsign.hashCode(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_FALLBACK)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Импортируй cert для $callsign")
            .setContentText("Шлю как маркер вместо иконки юзера. Открой Серты → Импорт DataPackage.")
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        nm.notify(NOTIF_MISSING_BASE + (callsign.hashCode() and 0xFFFF), n)
    }

    override fun onDestroy() {
        stopBridge()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "cotbridge"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "CoT Bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("CoT Bridge")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "com.dkashev.cotbridge.START"
        const val ACTION_STOP = "com.dkashev.cotbridge.STOP"
        private const val NOTIF_ID = 1
        private const val NOTIF_MISSING_BASE = 100
        private const val CHANNEL_FALLBACK = "cotbridge_fallback"

        // Маяк выборов раз в 30с; standby занимает вакансию, если не слышал активный маяк ~95с.
        private const val BEACON_INTERVAL_MS = 30_000L
        private const val BEACON_TIMEOUT_MS = 95_000L

        fun start(ctx: Context) {
            val intent = Intent(ctx, BridgeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP))
        }
    }
}
