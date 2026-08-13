package com.pika.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pika.core.source.SourceManager
import com.pika.ui.category.CategoryComicsScreen
import com.pika.ui.category.CategoryScreen
import com.pika.ui.detail.ComicDetailScreen
import com.pika.ui.home.HomeScreen
import com.pika.ui.login.LoginScreen
import com.pika.ui.mine.MineScreen
import com.pika.ui.reader.ReaderScreen
import com.pika.ui.search.SearchScreen
import com.pika.ui.settings.SettingsScreen

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/** 底部导航：首页 / 分类 / 搜索 / 我的（设置入口收敛在"我的"页内） */
private val tabs = listOf(
    TabItem("home", "首页", Icons.Filled.Home),
    TabItem("category", "分类", Icons.AutoMirrored.Filled.List),
    TabItem("search", "搜索", Icons.Filled.Search),
    TabItem("mine", "我的", Icons.Filled.Person),
)

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val activeSource by SourceManager.activeSource.collectAsState()
    val loggedOut by SourceManager.loggedOut.collectAsState()

    // 当前源未登录 / 收到 401 登出事件 → 跳登录页
    LaunchedEffect(activeSource, loggedOut) {
        val needLogin = !SourceManager.current().isLoggedIn
        if (needLogin) {
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
            }
        } else if (loggedOut) {
            SourceManager.consumeLoggedOut()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") {
                HomeScreen(
                    onComicClick = { id ->
                        navController.navigate("comic/$id")
                    },
                    onResumeReading = { id, order ->
                        navController.navigate("reader/$id/$order")
                    },
                )
            }
            composable("category") {
                CategoryScreen(
                    onCategoryClick = { id ->
                        navController.navigate("category/$id")
                    },
                    onComicClick = { id ->
                        navController.navigate("comic/$id")
                    },
                )
            }
            composable("search") {
                SearchScreen(
                    // 底部 Tab：不显示返回按钮
                    onBack = null,
                    onComicClick = { id -> navController.navigate("comic/$id") },
                )
            }
            composable("mine") {
                MineScreen(
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenDownloads = { navController.navigate("downloads") },
                    onOpenFavourites = { navController.navigate("favourites") },
                    onOpenReader = { comicId, order ->
                        navController.navigate("reader/$comicId/$order")
                    },
                )
            }
            composable("downloads") {
                com.pika.ui.download.DownloadScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id, order ->
                        navController.navigate("reader/$id/$order")
                    },
                )
            }
            composable("favourites") {
                com.pika.ui.favourite.FavouriteScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id -> navController.navigate("comic/$id") },
                )
            }
            composable("login") {
                LoginScreen(onLoggedIn = {
                    navController.navigate("home") {
                        popUpTo(0) { inclusive = true }
                    }
                })
            }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "category/{categoryId}",
                arguments = listOf(
                    navArgument("categoryId") { type = NavType.StringType },
                ),
            ) { entry ->
                val categoryId: String? = entry.arguments?.getString("categoryId")
                if (categoryId == null) return@composable
                CategoryComicsScreen(
                    categoryId = categoryId,
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate("comic/$id")
                    },
                )
            }
            composable(
                "comic/{comicId}",
                arguments = listOf(
                    navArgument("comicId") { type = NavType.StringType },
                ),
            ) { entry ->
                val comicId = entry.arguments?.getString("comicId") ?: return@composable
                ComicDetailScreen(
                    comicId = comicId,
                    onBack = { navController.popBackStack() },
                    onOpenReader = { id, order ->
                        navController.navigate("reader/$id/$order")
                    },
                )
            }
            composable(
                "reader/{comicId}/{order}",
                arguments = listOf(
                    navArgument("comicId") { type = NavType.StringType },
                    navArgument("order") { type = NavType.IntType },
                ),
            ) { entry ->
                val comicId = entry.arguments?.getString("comicId") ?: return@composable
                val order = entry.arguments?.getInt("order") ?: 1
                ReaderScreen(
                    comicId = comicId,
                    order = order,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
