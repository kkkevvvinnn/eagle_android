package com.eagleviewer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.eagleviewer.app.ui.DetailScreen
import com.eagleviewer.app.ui.EagleTheme
import com.eagleviewer.app.ui.GridScreen
import com.eagleviewer.app.ui.GridViewModel
import com.eagleviewer.app.ui.LibrarySetupScreen
import com.eagleviewer.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EagleApp).container
        setContent {
            val themeMode by container.settings.themeMode.collectAsState(initial = 0)
            EagleTheme(themeMode = themeMode) {
                AppNavHost(container)
            }
        }
    }
}

@Composable
fun AppNavHost(container: AppContainer) {
    val nav: NavHostController = rememberNavController()
    // null = DataStore 尚未读出；"" = 未设置图库
    val libraryUri by container.settings.libraryUri.collectAsState(initial = null)

    // activity 作用域的共享 ViewModel：网格页与大图页共用同一份筛选结果与分页数据
    val gridVm: GridViewModel = viewModel(factory = GridViewModel.factory(container))

    when (val uri = libraryUri) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> {
            // SAF 持久授权可能被用户在系统设置中撤销（或提供方不支持持久授权）：
            // URI 失效时图片全部加载失败且无提示，启动时校验并引导重新选择目录
            val context = LocalContext.current
            val hasReadPermission = remember(uri) {
                uri.isNotEmpty() && context.contentResolver.persistedUriPermissions
                    .any { it.uri.toString() == uri && it.isReadPermission }
            }
            NavHost(
                navController = nav,
                startDestination = if (uri.isEmpty() || !hasReadPermission) "setup" else "grid",
            ) {
            composable("setup") {
                LibrarySetupScreen(
                    container = container,
                    onDone = {
                        nav.navigate("grid") { popUpTo("setup") { inclusive = true } }
                    },
                )
            }
            composable("grid") {
                GridScreen(
                    vm = gridVm,
                    onOpenDetail = { index -> nav.navigate("detail/$index") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    vm = gridVm,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = "detail/{index}",
                arguments = listOf(navArgument("index") { type = NavType.IntType }),
            ) { entry ->
                DetailScreen(
                    vm = gridVm,
                    startIndex = entry.arguments?.getInt("index") ?: 0,
                    onBack = { nav.popBackStack() },
                    onFindSimilar = { color ->
                        gridVm.updateFilter { f ->
                            f.copy(tags = emptySet(), untaggedOnly = false, similarColor = color)
                        }
                        nav.popBackStack()
                    },
                )
            }
        }
    }
}}
