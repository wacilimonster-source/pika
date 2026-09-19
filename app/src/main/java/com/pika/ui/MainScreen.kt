package com.pika.ui

import android.net.Uri
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pika.core.source.SourceManager
import com.pika.data.ReaderPrefs
import com.pika.ui.author.AuthorComicsScreen
import com.pika.ui.category.CategoryComicsScreen
import com.pika.ui.category.CategoryScreen
import com.pika.ui.detail.ComicDetailScreen
import com.pika.ui.home.HomeScreen
import com.pika.ui.login.LoginScreen
import com.pika.ui.mine.MineScreen
import com.pika.ui.reader.ReaderScreen
import com.pika.ui.search.SearchScreen
import com.pika.ui.settings.LogScreen
import com.pika.ui.settings.SettingsScreen
import com.pika.ui.settings.SourceManageScreen

private data class TabItem(
    val route: String,
    val label: String,
)

private val tabs = listOf(
    TabItem("home", "首页"),
    TabItem("category", "分类"),
    TabItem("search", "搜索"),
    TabItem("mine", "我的"),
)

/** 需要整屏展示、不应挂底部标签栏的路由前缀 */
private val fullScreenRoutePrefixes = listOf("reader/")

/**
 * 详情页路由。[ref] 必须是作品标识 `源_id`（ComicSummary.ref / ComicDetail.ref），
 * 不能传裸 comicId —— 否则点开的页面会按"当时的活动源"去请求另一源的作品。
 * 历史裸 id 仍可用（按哔咔解释），见 ComicRef.parse。
 */
private fun comicRoute(ref: String) = "comic/${Uri.encode(ref)}"

/** 阅读器路由，[ref] 同上 */
private fun readerRoute(ref: String, order: Int) = "reader/${Uri.encode(ref)}/$order"

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val activeSource by SourceManager.activeSource.collectAsState()
    val unauthorizedTick by SourceManager.unauthorizedTick.collectAsState()
    val hideBottomBarInReader by ReaderPrefs.current().hideBottomBarInReader
        .collectAsState(initial = true)

    val route = currentDestination?.route
    val hideBottomBar = hideBottomBarInReader &&
        fullScreenRoutePrefixes.any { route?.startsWith(it) == true }

    LaunchedEffect(activeSource, unauthorizedTick) {
        if (!SourceManager.current().isLoggedIn) {
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            // 阅读页整屏展示：直接移除标签栏，Scaffold 会把这 64dp 还给内容区
            if (!hideBottomBar) {
                NavigationBar(modifier = Modifier.height(64.dp)) {
                    tabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route?.substringBefore('?') == tab.route } == true
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
                            icon = {},
                            label = { Text(tab.label, fontSize = 12.sp) },
                        )
                    }
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
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable("rank") {
                com.pika.ui.rank.RankScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable("category") {
                CategoryScreen(
                    onCategoryClick = { id ->
                        navController.navigate("category/${Uri.encode(id)}")
                    },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable(
                "search?keyword={keyword}",
                arguments = listOf(
                    navArgument("keyword") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                SearchScreen(
                    initialKeyword = entry.arguments?.getString("keyword"),
                    onBack = null,
                    onComicClick = { id -> navController.navigate(comicRoute(id)) },
                )
            }
            composable("mine") {
                MineScreen(
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenDownloads = { navController.navigate("downloads") },
                    onOpenFavourites = { navController.navigate("favourites") },
                    onOpenAuthorFavourites = { navController.navigate("author-favourites") },
                    onOpenFollowManage = { navController.navigate("follow-manage") },
                    onOpenReader = { comicId, order ->
                        navController.navigate(readerRoute(comicId, order))
                    },
                    onOpenProfile = { navController.navigate("profile") },
                    onOpenRecentReads = { navController.navigate("recent-reads") },
                    onOpenCloudHistory = { navController.navigate("cloud-history") },
                )
            }
            composable("profile") {
                com.pika.ui.profile.ProfileScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable("recent-reads") {
                com.pika.ui.history.RecentReadsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenComic = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable("cloud-history") {
                com.pika.ui.history.CloudHistoryScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable("downloads") {
                com.pika.ui.download.DownloadScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id, order ->
                        navController.navigate(readerRoute(id, order))
                    },
                )
            }
            composable("favourites") {
                com.pika.ui.favourite.FavouriteScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id -> navController.navigate(comicRoute(id)) },
                )
            }
            composable("author-favourites") {
                com.pika.ui.author.AuthorFavouritesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAuthor = { author ->
                        navController.navigate("author/${Uri.encode(author)}")
                    },
                )
            }
            composable("follow-manage") {
                com.pika.ui.follow.FollowManageScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable("login") {
                LoginScreen(
                    onLoggedIn = {
                        navController.navigate("home") {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onOpenRegister = {
                        navController.navigate("register")
                    },
                    onOpenForgotPassword = {
                        navController.navigate("forgot-password")
                    },
                )
            }
            composable("forgot-password") {
                com.pika.ui.login.ForgotPasswordScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable("register") {
                com.pika.ui.login.RegisterScreen(
                    onBack = { navController.popBackStack() },
                    onLoggedIn = {
                        navController.navigate("home") {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLog = { navController.navigate("log") },
                    onOpenSourceManage = { navController.navigate("settings/source") },
                    onOpenLogin = {
                        navController.navigate("login") { launchSingleTop = true }
                    },
                )
            }
            composable("settings/source") {
                SourceManageScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLogin = {
                        navController.navigate("login") { launchSingleTop = true }
                    },
                )
            }
            composable("log") {
                LogScreen(onBack = { navController.popBackStack() })
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
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable(
                "comic/{ref}",
                arguments = listOf(
                    navArgument("ref") { type = NavType.StringType },
                ),
            ) { entry ->
                // 载荷是作品标识 `源_id`：进页面即固化来源，之后用户切源也不影响本页
                val ref = entry.arguments?.getString("ref") ?: return@composable
                ComicDetailScreen(
                    ref = ref,
                    onBack = { navController.popBackStack() },
                    onOpenReader = { id, order ->
                        navController.navigate(readerRoute(id, order))
                    },
                    onOpenAuthor = { author ->
                        navController.navigate("author/${Uri.encode(author)}")
                    },
                    onOpenTagSearch = { tag ->
                        navController.navigate("search?keyword=${Uri.encode(tag)}")
                    },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id)) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                "author/{author}",
                arguments = listOf(
                    navArgument("author") { type = NavType.StringType },
                ),
            ) { entry ->
                val author = entry.arguments?.getString("author") ?: return@composable
                AuthorComicsScreen(
                    author = author,
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate(comicRoute(id))
                    },
                )
            }
            composable(
                "reader/{ref}/{order}",
                arguments = listOf(
                    navArgument("ref") { type = NavType.StringType },
                    navArgument("order") { type = NavType.IntType },
                ),
            ) { entry ->
                val ref = entry.arguments?.getString("ref") ?: return@composable
                val order = entry.arguments?.getInt("order") ?: 1
                ReaderScreen(
                    ref = ref,
                    order = order,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
