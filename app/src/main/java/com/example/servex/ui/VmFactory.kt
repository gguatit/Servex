package com.example.servex.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.servex.di.AppContainer
import com.example.servex.ui.detail.DetailViewModel
import com.example.servex.ui.edit.EditServerViewModel
import com.example.servex.ui.overview.OverviewViewModel
import com.example.servex.ui.settings.SettingsViewModel

/** Factory for the ViewModels that need no navigation arguments. */
class VmFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(OverviewViewModel::class.java) ->
            OverviewViewModel(container.repository) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(container.repository, container.appContext) as T
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}

/** Factory for a single server-detail screen; scoped to that nav entry. */
class DetailVmFactory(
    private val container: AppContainer,
    private val serverId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DetailViewModel::class.java)) {
            return DetailViewModel(container.repository, serverId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}

/** Factory for the add/edit screen; scoped to that nav entry. */
class EditVmFactory(
    private val container: AppContainer,
    private val serverId: String?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EditServerViewModel::class.java)) {
            return EditServerViewModel(container.repository, serverId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}
