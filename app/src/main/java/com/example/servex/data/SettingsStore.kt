package com.example.servex.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.example.servex.domain.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "servex_settings")

class SettingsStore(private val context: Context) : SettingsStoreContract {
    private val kRefresh = doublePreferencesKey("refresh_interval_s")
    private val kWarn = doublePreferencesKey("warning_pct")
    private val kCrit = doublePreferencesKey("critical_pct")
    private val kAlerts = booleanPreferencesKey("alerts_enabled")
    private val kDemo = booleanPreferencesKey("demo_mode")

    private fun read(p: Preferences) = AppSettings(
        refreshIntervalS = p[kRefresh] ?: 5.0,
        warningPct = p[kWarn] ?: 75.0,
        criticalPct = p[kCrit] ?: 90.0,
        alertsEnabled = p[kAlerts] ?: false,
        demoMode = p[kDemo] ?: false,
    )

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { read(it) }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { p ->
            val next = transform(read(p))
            p[kRefresh] = next.refreshIntervalS
            p[kWarn] = next.warningPct
            p[kCrit] = next.criticalPct
            p[kAlerts] = next.alertsEnabled
            p[kDemo] = next.demoMode
        }
    }
}
