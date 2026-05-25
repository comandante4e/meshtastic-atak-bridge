package com.dkashev.cotbridge.net

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends CoT XML to ATAK's "Network Input" — by default UDP 127.0.0.1:4242.
 * Each CoT event is sent as a single datagram.
 */
class AtakInjector(host: String = "127.0.0.1", private val port: Int = 4242) {

    private val address: InetAddress = InetAddress.getByName(host)
    private val socket = DatagramSocket()

    fun send(cotXml: String): Boolean {
        val bytes = cotXml.toByteArray(Charsets.UTF_8)
        return try {
            socket.send(DatagramPacket(bytes, bytes.size, address, port))
            true
        } catch (_: IOException) {
            false
        }
    }

    fun close() = runCatching { socket.close() }
}
