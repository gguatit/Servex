package com.example.servex.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.servex.di.AppContainer
import com.example.servex.ui.detail.DetailViewModel
import com.example.servex.ui.edit.EditServerViewModel
import com.example.servex.ui.overview.OverviewViewModel
import com.example.servex.ui.settings.SettingsViewModel

class VmFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(OverviewViewModel::class.java) ->
            OverviewViewModel(container.repository) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(container.repository, container.appContext) as T
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }

    fun detail(serverId: String, refreshIntervalS: Int): DetailViewModel =
        DetailViewModel(container.repository, serverId, refreshIntervalS)

    fun edit(serverId: String?): EditServerViewModel =
        EditServerViewModel(container.repository, serverId)
}
