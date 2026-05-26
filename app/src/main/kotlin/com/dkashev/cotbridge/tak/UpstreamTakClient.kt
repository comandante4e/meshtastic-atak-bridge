package com.dkashev.cotbridge.tak

import com.dkashev.cotbridge.cot.CotStreamSplitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Один TLS-CoT-стрим к TAK Server под одним cert'ом. Автоматически переподключается с
 * экспоненциальным backoff'ом, пока [start]-coroutine активна.
 *
 * RX (с сервера) → [onEvent] построчно (XML-event'ы).
 * TX (в сервер) → [send] потокобезопасный (synchronized).
 */
class UpstreamTakClient(
    val callsign: String,
    private val sslContext: SSLContext,
    private val host: String,
    private val port: Int,
    private val onEvent: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: (Throwable?) -> Unit,
) {

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    private var job: Job? = null
    private val writeLock = Any()

    val isConnected: Boolean get() = synchronized(writeLock) { writer != null }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        synchronized(writeLock) {
            runCatching { writer?.flush() }
            runCatching { socket?.close() }
            writer = null
            socket = null
        }
    }

    fun send(cotXml: String): Boolean {
        val w = synchronized(writeLock) { writer } ?: return false
        return try {
            synchronized(writeLock) {
                w.write(cotXml)
                if (!cotXml.endsWith("\n")) w.write("\n")
                w.flush()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun runLoop() = withContext(Dispatchers.IO) {
        var backoffMs = 1000L
        while (isActive) {
            try {
                connectAndPump()
                backoffMs = 1000L
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (isActive) onDisconnected(t)
            }
            if (!isActive) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private fun connectAndPump() {
        val factory = sslContext.socketFactory
        val s = factory.createSocket() as SSLSocket
        s.soTimeout = 60_000
        s.connect(InetSocketAddress(host, port), 10_000)
        s.startHandshake()
        socket = s
        synchronized(writeLock) {
            writer = BufferedWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8))
        }
        onConnected()

        val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
        val splitter = CotStreamSplitter()
        val buf = CharArray(8192)
        try {
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                splitter.feed(String(buf, 0, n)).forEach(onEvent)
            }
        } finally {
            synchronized(writeLock) {
                runCatching { writer?.close() }
                writer = null
            }
            runCatching { s.close() }
        }
    }
}
