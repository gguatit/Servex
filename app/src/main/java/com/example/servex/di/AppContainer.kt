package com.example.servex.di

import android.content.Context
import com.example.servex.data.HttpClients
import com.example.servex.data.LastStatusStore
import com.example.servex.data.ServerRepository
import com.example.servex.data.ServerStore
import com.example.servex.data.ServexApi
import com.example.servex.data.SettingsStore
import com.example.servex.data.TokenStore

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val serverStore = ServerStore(appContext)
    val settingsStore = SettingsStore(appContext)
    val tokenStore = TokenStore(appContext)
    val lastStatusStore = LastStatusStore(appContext)
    val api = ServexApi(HttpClients.shared)
    val repository = ServerRepository(serverStore, settingsStore, tokenStore, api)
}
