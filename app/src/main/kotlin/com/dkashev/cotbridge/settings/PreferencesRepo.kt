package com.dkashev.cotbridge.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "cotbridge")

data class BridgeConfig(
    val multicastAddress: String = "239.2.3.1",
    val multicastPort: Int = 6969,
    val atakInputPort: Int = 4242,
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
        }
    }

    private fun toConfig(p: Preferences) = BridgeConfig(
        multicastAddress = p[KEY_MC_ADDR] ?: "239.2.3.1",
        multicastPort = p[KEY_MC_PORT] ?: 6969,
        atakInputPort = p[KEY_INJECT_PORT] ?: 4242,
    )

    private companion object {
        val KEY_MC_ADDR = stringPreferencesKey("multicast_addr")
        val KEY_MC_PORT = intPreferencesKey("multicast_port")
        val KEY_INJECT_PORT = intPreferencesKey("atak_input_port")
    }
}
