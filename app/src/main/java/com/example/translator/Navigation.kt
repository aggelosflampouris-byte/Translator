package com.example.translator

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.translator.ui.apps.TargetAppsScreen
import com.example.translator.ui.main.MainScreen
import com.example.translator.ui.main.MainViewModel
import com.example.translator.ui.main.SettingsScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)
  // Por qué: Comparte una sola instancia de MainViewModel entre Inicio y Ajustes para sincronizar actualizaciones y estado
  val mainViewModel: MainViewModel = viewModel()

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          // Por qué: Evita duplicar safeDrawingPadding y padding(16.dp) ya que MainScreen gestiona su propio Scaffold
          MainScreen(
            onItemClick = { navKey -> backStack.add(navKey) },
            modifier = Modifier,
            viewModel = mainViewModel
          )
        }
        entry<TargetApps> {
          TargetAppsScreen(onBack = { backStack.removeLastOrNull() }, modifier = Modifier)
        }
        entry<Settings> {
          SettingsScreen(
            viewModel = mainViewModel,
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier
          )
        }
      },
  )
}
