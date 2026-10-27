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
import com.eagleviewer.app.data.NavigationIndexState
import com.eagleviewer.app.data.DetailNavigation
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
    // The coordinator snapshot has already loaded atomically before navigation is restored.
    val indexState by container.navigationIndex.state.collectAsState()
    val publication by container.index.publication.collectAsState()

    // activity 作用域的共享 ViewModel：网格页与大图页共用同一份筛选结果与分页数据
    val gridVm: GridViewModel = viewModel(factory = GridViewModel.factory(container))

    if (indexState == NavigationIndexState.Failed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Text("索引状态读取失败，请重试")
                androidx.compose.material3.TextButton(onClick = container::initializeIndex) { androidx.compose.material3.Text("重试") }
            }
        }
        return
    }
    if (indexState == NavigationIndexState.Loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val uri = publication.activeUri
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
            vm = gridVm,
            onDone = {
                nav.navigate("grid") { popUpTo("setup") { inclusive = true } }
            },
        )
    }
    composable("grid") {
        GridScreen(
            vm = gridVm,
            onOpenDetail = { index, ticket -> nav.navigate(DetailNavigation.route(index, ticket)) },
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
        route = DetailNavigation.LEGACY_ROUTE,
        arguments = listOf(navArgument("index") { type = NavType.IntType }),
    ) { entry ->
        DetailScreen(
            vm = gridVm,
            startIndex = entry.arguments?.getInt("index") ?: 0,
            expectedTicket = null,
            onBack = { nav.popBackStack() },
            onFindSimilar = { color ->
                gridVm.updateFilter { f ->
                    f.copy(similarColor = null, colorCondition = com.eagleviewer.app.data.ColorCondition(custom = com.eagleviewer.app.data.PaletteRGB.fromInt(color)))
                }
                nav.popBackStack()
            },
        )
    }
    composable(
        route = DetailNavigation.ROUTE,
        arguments = listOf(
            navArgument("index") { type = NavType.IntType },
            navArgument("ticket") { type = NavType.StringType },
        ),
    ) { entry ->
        DetailScreen(
            vm = gridVm,
            startIndex = entry.arguments?.getInt("index") ?: 0,
            expectedTicket = DetailNavigation.decode(entry.arguments?.getString("ticket")),
            onBack = { nav.popBackStack() },
            onFindSimilar = { color ->
                gridVm.updateFilter { f ->
                    f.copy(similarColor = null, colorCondition = com.eagleviewer.app.data.ColorCondition(custom = com.eagleviewer.app.data.PaletteRGB.fromInt(color)))
                }
                nav.popBackStack()
            },
        )
    }
}
}
