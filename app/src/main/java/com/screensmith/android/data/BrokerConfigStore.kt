package com.screensmith.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.screensmith.android.mqtt.BrokerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * MQTT broker connection details, persisted outside the imported project
 * bundle - same reasoning as the firmware needing separate WiFi/MQTT
 * configuration beyond what `project.json` carries; a design file
 * shouldn't need to know which broker a given phone talks to.
 */
class BrokerConfigStore(private val context: Context) {
    private object Keys {
        val HOST = stringPreferencesKey("broker_host")
        val PORT = intPreferencesKey("broker_port")
        val USERNAME = stringPreferencesKey("broker_username")
        val PASSWORD = stringPreferencesKey("broker_password")
    }

    val config: Flow<BrokerConfig> = context.settingsDataStore.data.map { prefs ->
        BrokerConfig(
            host = prefs[Keys.HOST] ?: "",
            port = prefs[Keys.PORT] ?: 1883,
            username = prefs[Keys.USERNAME] ?: "",
            password = prefs[Keys.PASSWORD] ?: "",
        )
    }

    suspend fun save(config: BrokerConfig) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.HOST] = config.host
            prefs[Keys.PORT] = config.port
            prefs[Keys.USERNAME] = config.username
            prefs[Keys.PASSWORD] = config.password
        }
    }
}
