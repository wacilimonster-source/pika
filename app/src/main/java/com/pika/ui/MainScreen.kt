package com.pika.ui

import android.net.Uri
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
import com.pika.ui.author.AuthorComicsScreen
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

/** 搴曢儴瀵艰埅锛氶椤?/ 鍒嗙被 / 鎼滅储 / 鎴戠殑锛堣缃叆鍙ｆ敹鏁涘湪"鎴戠殑"椤靛唴锛?*/
private val tabs = listOf(
    TabItem("home", "棣栭〉", Icons.Filled.Home),
    TabItem("category", "鍒嗙被", Icons.AutoMirrored.Filled.List),
    TabItem("search", "鎼滅储", Icons.Filled.Search),
    TabItem("mine", "鎴戠殑", Icons.Filled.Person),
)

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val activeSource by SourceManager.activeSource.collectAsState()
    val loggedOut by SourceManager.loggedOut.collectAsState()

    // 褰撳墠婧愭湭鐧诲綍 / 鏀跺埌 401 鐧诲嚭浜嬩欢 鈫?璺崇櫥褰曢〉
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
                        navController.navigate("comic/${Uri.encode(id)}")
                    },
                    onResumeReading = { id, order ->
                        navController.navigate("reader/${Uri.encode(id)}/$order")
                    },
                    onOpenRank = {
                        navController.navigate("rank")
                    },
                )
            }
            composable("rank") {
                com.pika.ui.rank.RankScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate("comic/${Uri.encode(id)}")
                    },
                )
            }
            composable("category") {
                CategoryScreen(
                    onCategoryClick = { id ->
                        navController.navigate("category/${Uri.encode(id)}")
                    },
                    onComicClick = { id ->
                        navController.navigate("comic/${Uri.encode(id)}")
                    },
                )
            }
            composable("search") {
                SearchScreen(
                    // 搴曢儴 Tab锛氫笉鏄剧ず杩斿洖鎸夐挳
                    onBack = null,
                    onComicClick = { id -> navController.navigate("comic/${Uri.encode(id)}") },
                )
            }
            composable("mine") {
                MineScreen(
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenDownloads = { navController.navigate("downloads") },
                    onOpenFavourites = { navController.navigate("favourites") },
                    onOpenReader = { comicId, order ->
                        navController.navigate("reader/${Uri.encode(comicId)}/$order")
                    },
                    onOpenProfile = { navController.navigate("profile") },
                    onOpenMyComments = { navController.navigate("my-comments") },
                )
            }
            composable("profile") {
                com.pika.ui.profile.ProfileScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable("my-comments") {
                com.pika.ui.comments.MyCommentsScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id ->
                        navController.navigate("comic/${Uri.encode(id)}") {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("downloads") {
                com.pika.ui.download.DownloadScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id, order ->
                        navController.navigate("reader/${Uri.encode(id)}/$order")
                    },
                )
            }
            composable("favourites") {
                com.pika.ui.favourite.FavouriteScreen(
                    onBack = { navController.popBackStack() },
                    onComicClick = { id -> navController.navigate("comic/${Uri.encode(id)}") },
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
                        navController.navigate("comic/${Uri.encode(id)}")
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
                        navController.navigate("reader/${Uri.encode(id)}/$order")
                    },
                    onOpenAuthor = { author ->
                        navController.navigate("author/${Uri.encode(author)}")
                    },
                    onComicClick = { id ->
                        navController.navigate("comic/${Uri.encode(id)}") {
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
                        navController.navigate("comic/${Uri.encode(id)}")
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
