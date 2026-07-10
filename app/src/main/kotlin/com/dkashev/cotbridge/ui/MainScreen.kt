package com.dkashev.cotbridge.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dkashev.cotbridge.BridgeApp
import com.dkashev.cotbridge.bridge.BridgeState
import com.dkashev.cotbridge.bridge.BridgeStateHolder
import com.dkashev.cotbridge.bridge.ConnectionState
import com.dkashev.cotbridge.bridge.GatewayRole
import com.dkashev.cotbridge.bridge.ServerRelayMode
import com.dkashev.cotbridge.service.BridgeService
import com.dkashev.cotbridge.settings.BridgeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    Surface(color = MaterialTheme.colorScheme.background) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val state by BridgeStateHolder.state.collectAsState()
        val cfg by BridgeApp.instance.preferences.config.collectAsState(initial = null)

        var importUri by remember { mutableStateOf<Uri?>(null) }
        var certs by remember { mutableStateOf(BridgeApp.instance.certVault.list()) }

        val pickDataPackage = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { importUri = it }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("CoT Bridge — ATAK ↔ Meshtastic") }) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusCard(state)
                CountersCard(state)
                CertsCard(
                    state = state,
                    certs = certs,
                    onImport = {
                        pickDataPackage.launch(arrayOf("application/zip", "application/*", "*/*"))
                    },
                    onRemove = { cs ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                BridgeApp.instance.certVault.remove(cs)
                            }
                            certs = BridgeApp.instance.certVault.list()
                            BridgeStateHolder.log("Cert $cs удалён. Перезапусти мост чтобы освободить TLS-сессию.")
                        }
                    },
                )
                GatewayCard(
                    cfg = cfg,
                    onCfgChange = { transform ->
                        scope.launch { BridgeApp.instance.preferences.update(transform) }
                    },
                )
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

            importUri?.let { uri ->
                ImportDialog(
                    uri = uri,
                    onDismiss = { importUri = null },
                    onImported = { added ->
                        certs = certs + added
                        importUri = null
                        BridgeStateHolder.log("Импортирован cert: $added. Перезапусти мост чтобы поднять TLS-сессию.")
                    },
                )
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
            StatusRow(
                "Шлюз (${state.gatewayRole})",
                if (state.gatewayActive) "АКТИВНЫЙ" else "standby",
                if (state.gatewayActive) Color(0xFF2E7D32) else Color(0xFFFFA000),
            )
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
            Text("меш → bridge: ${state.rxFromMesh}")
            Text("bridge → URPC (под cert): ${state.txToUpstream}")
            Text("bridge → ATAK (fallback маркер): ${state.txFallback}")
            Text("Эхо-фильтр: ${state.droppedLoop}", fontSize = 12.sp, color = Color.Gray)
            Text("Пропущено (не активный шлюз): ${state.droppedStandby}", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun CertsCard(
    state: BridgeState,
    certs: List<String>,
    onImport: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Серты мешевых юзеров (${certs.size})", fontWeight = FontWeight.SemiBold)
            Text(
                "Каждый юзер на базе делает enrollment в своём ATAK (login+pass → cert от URPC), " +
                    "потом экспортит DataPackage (.zip) и передаёт тебе для импорта сюда.",
                fontSize = 11.sp, color = Color.Gray,
            )

            if (certs.isEmpty()) {
                Text(
                    "Нет импортированных cert'ов. Без них мешевые юзеры пойдут на URPC как маркеры (u-d-p).",
                    fontSize = 12.sp,
                )
            } else {
                certs.forEach { cs ->
                    val st = state.upstreams[cs] ?: ConnectionState.IDLE
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(10.dp).background(st.color(), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(cs, modifier = Modifier.weight(1f))
                        Text(st.label(), fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { onRemove(cs) }) { Text("✕") }
                    }
                }
            }

            Button(onClick = onImport) { Text("Импорт DataPackage (.zip)") }
        }
    }
}

@Composable
private fun ImportDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onImported: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("atakatak") }
    var callsignOverride by remember { mutableStateOf("") }
    var hostOverride by remember { mutableStateOf("") }
    var portOverride by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт DataPackage") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Файл: ${uri.lastPathSegment ?: uri}", fontSize = 11.sp)
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Пароль .p12 (обычно atakatak)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = callsignOverride, onValueChange = { callsignOverride = it },
                    label = { Text("Callsign (пусто = из CN cert'а)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = hostOverride, onValueChange = { hostOverride = it },
                        label = { Text("URPC host (пусто = из pref)") },
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = portOverride, onValueChange = { portOverride = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading && password.isNotEmpty(),
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                BridgeApp.instance.certVault.import(
                                    sourceUri = uri,
                                    password = password,
                                    callsignOverride = callsignOverride.ifBlank { null },
                                    hostOverride = hostOverride.ifBlank { null },
                                    portOverride = portOverride.toIntOrNull(),
                                )
                            }
                        }.onSuccess { onImported(it.callsign) }
                            .onFailure {
                                error = it.message ?: "Не удалось импортировать"
                                loading = false
                            }
                    }
                },
            ) { Text(if (loading) "..." else "Импорт") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun GatewayCard(
    cfg: BridgeConfig?,
    onCfgChange: ((BridgeConfig) -> BridgeConfig) -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Режим шлюза (анти-дубль)", fontWeight = FontWeight.SemiBold)
            if (cfg == null) {
                Text("Загружаю...", fontSize = 12.sp)
                return@Column
            }
            Text(
                "AUTO — выборы: ровно один активный шлюз в связном меше (безопасно раздавать друзьям). " +
                    "FORCE — всегда я (единственный, без failover). OFF — не ретранслирую.",
                fontSize = 11.sp, color = Color.Gray,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GatewayRole.entries.forEach { r ->
                    val selected = cfg.gatewayRole == r
                    Button(
                        onClick = { onCfgChange { c -> c.copy(gatewayRole = r) } },
                        enabled = !selected,
                        modifier = Modifier.weight(1f),
                    ) { Text(r.name) }
                }
            }
            var prio by remember(cfg) { mutableStateOf(cfg.gatewayPriority.toString()) }
            OutlinedTextField(
                value = prio,
                onValueChange = {
                    prio = it.filter(Char::isDigit)
                    onCfgChange { c -> c.copy(gatewayPriority = prio.toIntOrNull() ?: 100) }
                },
                label = { Text("Приоритет выборов (меньше = главнее; 0 = предпочтительный шлюз)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Смена роли/приоритета применяется при следующем Старте. " +
                    "Для раздачи: у всех AUTO; у самого мощного шлюза priority=0.",
                fontSize = 11.sp, color = Color.Gray,
            )
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
            Text("Сеть и идентификация", fontWeight = FontWeight.SemiBold)
            if (cfg == null) {
                Text("Загружаю настройки...", fontSize = 12.sp)
                return@Column
            }

            var myCs by remember(cfg) { mutableStateOf(cfg.myCallsign) }
            OutlinedTextField(
                value = myCs,
                onValueChange = { myCs = it; onCfgChange { c -> c.copy(myCallsign = myCs) } },
                label = { Text("Мой callsign (для fallback-чата)") },
                modifier = Modifier.fillMaxWidth(),
            )

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

            Text("Обратка URPC → меш", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ServerRelayMode.entries.forEach { m ->
                    val selected = cfg.serverRelayMode == m
                    Button(
                        onClick = { onCfgChange { c -> c.copy(serverRelayMode = m) } },
                        enabled = !selected,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (m == ServerRelayMode.UPSTREAM_RX) "RX (cert)" else "Multicast") }
                }
            }
            Text(
                "RX — надёжно, напрямую из per-cert стримов (реком.). " +
                    "Multicast — через ретрансляцию ATAK (запасной). Применяется при след. Старте.",
                fontSize = 11.sp, color = Color.Gray,
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
