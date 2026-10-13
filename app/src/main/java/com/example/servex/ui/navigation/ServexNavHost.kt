package com.example.servex.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.servex.di.AppContainer
import com.example.servex.ui.VmFactory
import com.example.servex.ui.detail.DetailScreen
import com.example.servex.ui.edit.EditServerScreen
import com.example.servex.ui.overview.OverviewScreen
import com.example.servex.ui.overview.OverviewViewModel
import com.example.servex.ui.settings.SettingsScreen
import com.example.servex.ui.settings.SettingsViewModel

object Routes {
    const val OVERVIEW = "overview"
    const val DETAIL = "detail/{serverId}"
    const val EDIT = "edit?serverId={serverId}"
    const val SETTINGS = "settings"
    fun detail(id: String) = "detail/$id"
    fun edit(id: String?) = if (id == null) "edit" else "edit?serverId=$id"
}

@Composable
fun ServexNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val factory = VmFactory(container)
    NavHost(nav, startDestination = Routes.OVERVIEW, modifier = modifier) {
        composable(Routes.OVERVIEW) {
            val vm: OverviewViewModel = viewModel(factory = factory)
            OverviewScreen(
                viewModel = vm,
                onOpenServer = { nav.navigate(Routes.detail(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onAddServer = { nav.navigate(Routes.edit(null)) },
            )
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("serverId") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("serverId").orEmpty()
            DetailScreen(
                viewModel = factory.detail(id, 5),
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.edit(it)) },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("serverId") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            val id = entry.arguments?.getString("serverId").orEmpty().ifBlank { null }
            EditServerScreen(viewModel = factory.edit(id), onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
    }
}
