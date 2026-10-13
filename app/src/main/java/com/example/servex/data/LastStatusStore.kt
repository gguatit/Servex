package com.example.servex.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.servex.domain.ServerStatus
import kotlinx.coroutines.flow.first

private val Context.statusDataStore by preferencesDataStore(name = "servex_last_status")

class LastStatusStore(private val context: Context) {
    private fun key(id: String) = stringPreferencesKey("s_$id")

    suspend fun get(id: String): ServerStatus? =
        context.statusDataStore.data.first()[key(id)]?.let {
            runCatching { ServerStatus.valueOf(it) }.getOrNull()
        }

    suspend fun set(id: String, status: ServerStatus) {
        context.statusDataStore.edit { it[key(id)] = status.name }
    }
}
