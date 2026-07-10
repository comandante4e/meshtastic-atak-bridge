package com.dkashev.cotbridge.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dkashev.cotbridge.bridge.GatewayRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "cotbridge")

data class BridgeConfig(
    val multicastAddress: String = "239.2.3.1",
    val multicastPort: Int = 6969,
    val atakInputPort: Int = 4242,
    val myCallsign: String = "Bridge",
    /** Роль в связке шлюзов. Дефолт AUTO — безопасно раздавать: выборы держат ровно один активный. */
    val gatewayRole: GatewayRole = GatewayRole.AUTO,
    /** Приоритет в выборах: меньше = главнее. 0 — «я предпочтительный шлюз» (лучшая антенна/питание). */
    val gatewayPriority: Int = 100,
    /** Уникальный тайбрейкер на инсталляцию. 0 = ещё не сгенерирован (сервис проставит случайный). */
    val electionId: Int = 0,
)

class PreferencesRepo(private val context: Context) {

    val config: Flow<BridgeConfig> = context.dataStore.data.map(::toConfig)

    suspend fun current(): BridgeConfig = toConfig(context.dataStore.data.first())

    suspend fun update(transform: (BridgeConfig) -> BridgeConfig) {
        val updated = transform(current())
        context.dataStore.edit { p ->
            p[KEY_MC_ADDR] = updated.multicastAddress
            p[KEY_MC_PORT] = updated.multicastPort
            p[KEY_INJECT_PORT] = updated.atakInputPort
            p[KEY_MY_CALLSIGN] = updated.myCallsign
            p[KEY_GW_ROLE] = updated.gatewayRole.name
            p[KEY_GW_PRIORITY] = updated.gatewayPriority
            p[KEY_ELECTION_ID] = updated.electionId
        }
    }

    private fun toConfig(p: Preferences) = BridgeConfig(
        multicastAddress = p[KEY_MC_ADDR] ?: "239.2.3.1",
        multicastPort = p[KEY_MC_PORT] ?: 6969,
        atakInputPort = p[KEY_INJECT_PORT] ?: 4242,
        myCallsign = p[KEY_MY_CALLSIGN] ?: "Bridge",
        gatewayRole = p[KEY_GW_ROLE]?.let { runCatching { GatewayRole.valueOf(it) }.getOrNull() }
            ?: GatewayRole.AUTO,
        gatewayPriority = p[KEY_GW_PRIORITY] ?: 100,
        electionId = p[KEY_ELECTION_ID] ?: 0,
    )

    private companion object {
        val KEY_MC_ADDR = stringPreferencesKey("multicast_addr")
        val KEY_MC_PORT = intPreferencesKey("multicast_port")
        val KEY_INJECT_PORT = intPreferencesKey("atak_input_port")
        val KEY_MY_CALLSIGN = stringPreferencesKey("my_callsign")
        val KEY_GW_ROLE = stringPreferencesKey("gateway_role")
        val KEY_GW_PRIORITY = intPreferencesKey("gateway_priority")
        val KEY_ELECTION_ID = intPreferencesKey("election_id")
    }
}
