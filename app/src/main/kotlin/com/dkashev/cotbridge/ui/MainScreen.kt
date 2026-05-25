package com.dkashev.cotbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dkashev.cotbridge.BridgeApp
import com.dkashev.cotbridge.bridge.BridgeState
import com.dkashev.cotbridge.bridge.BridgeStateHolder
import com.dkashev.cotbridge.bridge.ConnectionState
import com.dkashev.cotbridge.service.BridgeService
import com.dkashev.cotbridge.settings.BridgeConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    Surface(color = MaterialTheme.colorScheme.background) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val state by BridgeStateHolder.state.collectAsState()
        val cfg by BridgeApp.instance.preferences.config.collectAsState(initial = null)

        Scaffold(
            topBar = { TopAppBar(title = { Text("CoT Bridge — ATAK ↔ Meshtastic") }) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusCard(state)
                CountersCard(state)
                ConfigCard(
                    cfg = cfg,
                    onCfgChange = { transform ->
                        scope.launch { BridgeApp.instance.preferences.update(transform) }
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { BridgeService.start(ctx) },
                        enabled = !state.running,
                        modifier = Modifier.weight(1f),
                    ) { Text("Старт") }
                    Button(
                        onClick = { BridgeService.stop(ctx) },
                        enabled = state.running,
                        modifier = Modifier.weight(1f),
                    ) { Text("Стоп") }
                }

                state.lastError?.let { err ->
                    Card {
                        Text(
                            "Ошибка: $err",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                LogCard(state)
            }
        }
    }
}

@Composable
private fun StatusCard(state: BridgeState) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Статус", fontWeight = FontWeight.SemiBold)
            StatusRow(
                "Сервис",
                if (state.running) "запущен" else "остановлен",
                if (state.running) Color(0xFF2E7D32) else Color.Gray,
            )
            StatusRow("Multicast от ATAK", state.multicast.label(), state.multicast.color())
            StatusRow("Meshtastic AIDL", state.localTak.label(), state.localTak.color())
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        Text(value)
    }
}

private fun ConnectionState.label(): String = when (this) {
    ConnectionState.IDLE -> "idle"
    ConnectionState.CONNECTING -> "соединяюсь"
    ConnectionState.CONNECTED -> "OK"
    ConnectionState.ERROR -> "ошибка"
}

private fun ConnectionState.color(): Color = when (this) {
    ConnectionState.IDLE -> Color.Gray
    ConnectionState.CONNECTING -> Color(0xFFFFA000)
    ConnectionState.CONNECTED -> Color(0xFF2E7D32)
    ConnectionState.ERROR -> Color(0xFFC62828)
}

@Composable
private fun CountersCard(state: BridgeState) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Счётчики", fontWeight = FontWeight.SemiBold)
            Text("ATAK → bridge: ${state.rxFromAtak}     bridge → меш: ${state.txToMesh}")
            Text("меш → bridge: ${state.rxFromMesh}     bridge → ATAK: ${state.txToAtak}")
            Text("Эхо-фильтр: ${state.droppedLoop}", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun ConfigCard(
    cfg: BridgeConfig?,
    onCfgChange: ((BridgeConfig) -> BridgeConfig) -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Сеть (обычно менять не надо)", fontWeight = FontWeight.SemiBold)
            if (cfg == null) {
                Text("Загружаю настройки...", fontSize = 12.sp)
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                var mc by remember(cfg) { mutableStateOf(cfg.multicastAddress) }
                var mcp by remember(cfg) { mutableStateOf(cfg.multicastPort.toString()) }
                OutlinedTextField(
                    value = mc,
                    onValueChange = { mc = it; onCfgChange { c -> c.copy(multicastAddress = mc) } },
                    label = { Text("Multicast от ATAK") },
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = mcp,
                    onValueChange = {
                        mcp = it.filter(Char::isDigit)
                        onCfgChange { c -> c.copy(multicastPort = mcp.toIntOrNull() ?: 6969) }
                    },
                    label = { Text("Port") },
                    modifier = Modifier.weight(1f),
                )
            }

            var inj by remember(cfg) { mutableStateOf(cfg.atakInputPort.toString()) }
            OutlinedTextField(
                value = inj,
                onValueChange = {
                    inj = it.filter(Char::isDigit)
                    onCfgChange { c -> c.copy(atakInputPort = inj.toIntOrNull() ?: 4242) }
                },
                label = { Text("UDP-порт Network Input у ATAK (по умолчанию 4242)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LogCard(state: BridgeState) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.size - 1)
    }
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Лог", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.log) { entry ->
                    Text(
                        text = entry.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
