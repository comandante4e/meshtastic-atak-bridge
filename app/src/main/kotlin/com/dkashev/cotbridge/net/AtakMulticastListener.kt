package com.dkashev.cotbridge.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Listens for CoT events ATAK broadcasts to the SA-multicast group (default 239.2.3.1:6969).
 * Each received UDP datagram is one CoT XML event.
 */
class AtakMulticastListener(
    private val context: Context,
    private val groupAddress: String,
    private val port: Int,
    private val onEvent: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {

    private var socket: MulticastSocket? = null
    private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        acquireMulticastLock()
        job = scope.launch(Dispatchers.IO) { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private suspend fun run() = withContext(Dispatchers.IO) {
        try {
            val s = MulticastSocket(port).apply {
                reuseAddress = true
                soTimeout = 1000
            }
            socket = s
            val group = InetSocketAddress(InetAddress.getByName(groupAddress), port)
            val nif = defaultMulticastInterface()
            if (nif != null) s.joinGroup(group, nif) else s.joinGroup(InetAddress.getByName(groupAddress))

            val buf = ByteArray(64 * 1024)
            while (isActive) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    s.receive(pkt)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                if (pkt.length <= 0) continue
                val text = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8)
                onEvent(text)
            }
        } catch (t: Throwable) {
            onError(t)
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun acquireMulticastLock() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("cotbridge-multicast")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun defaultMulticastInterface(): NetworkInterface? {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
        return candidates.firstOrNull { it.name.startsWith("wlan") || it.name.startsWith("eth") }
            ?: candidates.firstOrNull()
    }
}
