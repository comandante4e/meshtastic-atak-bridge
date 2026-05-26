package com.dkashev.cotbridge.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

enum class ConnectionState { IDLE, CONNECTING, CONNECTED, ERROR }

data class BridgeState(
    val running: Boolean = false,
    val multicast: ConnectionState = ConnectionState.IDLE,
    val localTak: ConnectionState = ConnectionState.IDLE,
    val rxFromAtak: Long = 0,
    val txToMesh: Long = 0,
    val rxFromMesh: Long = 0,
    val txToAtak: Long = 0,
    val txToUpstream: Long = 0,
    val txFallback: Long = 0,
    val droppedLoop: Long = 0,
    val upstreams: Map<String, ConnectionState> = emptyMap(),
    val log: List<LogEntry> = emptyList(),
    val lastError: String? = null,
)

data class LogEntry(val at: Instant, val message: String) {
    override fun toString(): String = "${at.toString().substring(11, 19)}  $message"
}

object BridgeStateHolder {
    private const val MAX_LOG = 200
    private val _state = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    fun update(transform: (BridgeState) -> BridgeState) = _state.update(transform)

    fun log(message: String) = _state.update {
        val entry = LogEntry(Instant.now(), message)
        it.copy(log = (it.log + entry).takeLast(MAX_LOG))
    }
}
