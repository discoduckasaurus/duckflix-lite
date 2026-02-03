package com.duckflix.lite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.duckflix.lite.ui.screens.detail.DetailScreen
import com.duckflix.lite.ui.screens.login.LoginScreen
import com.duckflix.lite.ui.screens.home.HomeScreen
import com.duckflix.lite.ui.screens.player.VideoPlayerScreen
import com.duckflix.lite.ui.screens.search.SearchScreen
import com.duckflix.lite.ui.screens.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Home : Screen("home")
    object Search : Screen("search")
    object Detail : Screen("detail/{tmdbId}?type={type}") {
        fun createRoute(tmdbId: Int, type: String = "movie") = "detail/$tmdbId?type=$type"
    }
    object Player : Screen("player/{tmdbId}/{title}?year={year}&type={type}&season={season}&episode={episode}&resumePosition={resumePosition}&posterUrl={posterUrl}") {
        fun createRoute(
            tmdbId: Int,
            title: String,
            year: String? = null,
            type: String = "movie",
            season: Int? = null,
            episode: Int? = null,
            resumePosition: Long? = null,
            posterUrl: String? = null
        ) = "player/$tmdbId/${java.net.URLEncoder.encode(title, "UTF-8")}?" +
                "year=${year ?: ""}&type=$type" +
                "&season=${season ?: -1}" +
                "&episode=${episode ?: -1}" +
                "&resumePosition=${resumePosition ?: -1L}" +
                (if (posterUrl != null) "&posterUrl=${java.net.URLEncoder.encode(posterUrl, "UTF-8")}" else "")
    }
    object Vod : Screen("vod")
    object LiveTV : Screen("livetv")
    object Dvr : Screen("dvr")
    object Settings : Screen("settings")
}

@Composable
fun DuckFlixApp(
    viewModel: AppViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()

    // TODO: Re-enable login after testing
    // val startDestination = Screen.Home.route // Skip login for testing
    val startDestination = if (isLoggedIn) Screen.Home.route else Screen.Login.route

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onContentSelected = { tmdbId, title, type ->
                    navController.navigate(Screen.Detail.createRoute(tmdbId, type))
                },
                onNavigateBack = {
                    navController.navigateUp()
                }
            )
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(
                navArgument("tmdbId") { type = NavType.IntType },
                navArgument("type") {
                    type = NavType.StringType
                    defaultValue = "movie"
                }
            )
        ) {
            DetailScreen(
                onPlayClick = { tmdbId, title, year, type, season, episode, resumePosition, posterUrl ->
                    navController.navigate(Screen.Player.createRoute(tmdbId, title, year, type, season, episode, resumePosition, posterUrl))
                },
                onSearchTorrents = { tmdbId, title ->
                    // TODO: Navigate to torrent search/Prowlarr flow
                },
                onNavigateBack = {
                    navController.navigateUp()
                }
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("tmdbId") { type = NavType.IntType },
                navArgument("title") { type = NavType.StringType },
                navArgument("year") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("type") {
                    type = NavType.StringType
                    defaultValue = "movie"
                },
                navArgument("season") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("episode") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("resumePosition") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("posterUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            VideoPlayerScreen(
                onNavigateBack = {
                    navController.navigateUp()
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.navigateUp()
                }
            )
        }

        // TODO: Add other screens (VOD detail, Live TV, DVR)
    }
}
