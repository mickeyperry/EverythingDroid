package com.mickey.everythingdroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "everythingdroid_settings")

data class ServerSettings(
    val host: String = "",
    val port: Int = 80,
    val https: Boolean = false,
    val username: String = "",
    val password: String = "",
) {
    fun baseUrl(): String {
        val scheme = if (https) "https" else "http"
        val hostTrimmed = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        return "$scheme://$hostTrimmed:$port"
    }
    fun isConfigured(): Boolean = host.isNotBlank()
}

class SettingsStore(private val context: Context) {
    private val KEY_HOST = stringPreferencesKey("host")
    private val KEY_PORT = intPreferencesKey("port")
    private val KEY_HTTPS = booleanPreferencesKey("https")
    private val KEY_USER = stringPreferencesKey("user")
    private val KEY_PASS = stringPreferencesKey("pass")

    val settings: Flow<ServerSettings> = context.dataStore.data.map { p ->
        ServerSettings(
            host = p[KEY_HOST].orEmpty(),
            port = p[KEY_PORT] ?: 80,
            https = p[KEY_HTTPS] ?: false,
            username = p[KEY_USER].orEmpty(),
            password = p[KEY_PASS].orEmpty(),
        )
    }

    suspend fun save(s: ServerSettings) {
        context.dataStore.edit { p ->
            p[KEY_HOST] = s.host
            p[KEY_PORT] = s.port
            p[KEY_HTTPS] = s.https
            p[KEY_USER] = s.username
            p[KEY_PASS] = s.password
        }
    }
}
