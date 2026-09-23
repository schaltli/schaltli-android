package com.schaltli.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.schaltli.android.mqtt.BrokerConfig
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
        val DISPLAY_OFF_SECONDS = intPreferencesKey("display_off_seconds")
    }

    val config: Flow<BrokerConfig> = context.settingsDataStore.data.map { prefs ->
        BrokerConfig(
            host = prefs[Keys.HOST] ?: "",
            port = prefs[Keys.PORT] ?: 1883,
            username = prefs[Keys.USERNAME] ?: "",
            password = prefs[Keys.PASSWORD] ?: "",
        )
    }

    /**
     * Seconds without a touch before the panel goes dark (ScreenSleep.kt); 0
     * keeps it on. A setting of the phone, like its broker, not of the
     * project: the same project may hang in a bright van and a dark one.
     */
    val displayOffSeconds: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.DISPLAY_OFF_SECONDS] ?: com.schaltli.android.ScreenSleep.DEFAULT_SECONDS
    }

    suspend fun saveDisplayOffSeconds(seconds: Int) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.DISPLAY_OFF_SECONDS] = seconds.coerceAtLeast(0) }
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
