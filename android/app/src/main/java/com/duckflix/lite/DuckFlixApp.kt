package com.duckflix.lite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.duckflix.lite.ui.screens.admin.AdminScreen
import com.duckflix.lite.ui.screens.detail.DetailScreen
import com.duckflix.lite.ui.screens.filmography.ActorFilmographyScreen
import com.duckflix.lite.ui.screens.login.LoginScreen
import com.duckflix.lite.ui.screens.login.UsernameScreen
import com.duckflix.lite.ui.screens.login.PasswordScreen
import com.duckflix.lite.ui.screens.home.HomeScreen
import com.duckflix.lite.ui.screens.landing.LandingScreen
import com.duckflix.lite.ui.screens.vod.VodContainerScreen
import com.duckflix.lite.ui.screens.providers.ProviderDetailScreen
import com.duckflix.lite.ui.screens.player.VideoPlayerScreen
import com.duckflix.lite.ui.screens.search.SearchScreen
import com.duckflix.lite.ui.screens.settings.SettingsScreen
import com.duckflix.lite.ui.screens.livetv.LiveTvScreen
import com.duckflix.lite.ui.screens.dvr.DvrScreen
import com.duckflix.lite.ui.screens.dvr.DvrPlayerScreen
import com.duckflix.lite.ui.components.UpdateAvailableDialog

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object LoginUsername : Screen("login_username")
    object LoginPassword : Screen("login_password/{username}") {
        fun createRoute(username: String) = "login_password/${java.net.URLEncoder.encode(username, "UTF-8")}"
    }
    object Home : Screen("home")
    object Search : Screen("search")
    object Detail : Screen("detail/{tmdbId}?type={type}&season={season}&episode={episode}") {
        fun createRoute(tmdbId: Int, type: String = "movie", season: Int? = null, episode: Int? = null) =
            "detail/$tmdbId?type=$type&season=${season ?: -1}&episode=${episode ?: -1}"
    }
    object ActorFilmography : Screen("actor/{personId}") {
        fun createRoute(personId: Int) = "actor/$personId"
    }
    object Player : Screen("player/{tmdbId}/{title}?year={year}&type={type}&season={season}&episode={episode}&resumePosition={resumePosition}&posterUrl={posterUrl}&logoUrl={logoUrl}&originalLanguage={originalLanguage}&isRandom={isRandom}&episodeTitle={episodeTitle}&isAutoplay={isAutoplay}") {
        fun createRoute(
            tmdbId: Int,
            title: String,
            year: String? = null,
            type: String = "movie",
            season: Int? = null,
            episode: Int? = null,
            resumePosition: Long? = null,
            posterUrl: String? = null,
            logoUrl: String? = null,
            originalLanguage: String? = null,
            isRandom: Boolean = false,
            episodeTitle: String? = null,
            isAutoplay: Boolean = false
        ) = "player/$tmdbId/${java.net.URLEncoder.encode(title, "UTF-8")}?" +
                "year=${year ?: ""}&type=$type" +
                "&season=${season ?: -1}" +
                "&episode=${episode ?: -1}" +
                "&resumePosition=${resumePosition ?: -1L}" +
                (if (posterUrl != null) "&posterUrl=${java.net.URLEncoder.encode(posterUrl, "UTF-8")}" else "") +
                (if (logoUrl != null) "&logoUrl=${java.net.URLEncoder.encode(logoUrl, "UTF-8")}" else "") +
                (if (originalLanguage != null) "&originalLanguage=$originalLanguage" else "") +
                "&isRandom=$isRandom" +
                (if (episodeTitle != null) "&episodeTitle=${java.net.URLEncoder.encode(episodeTitle, "UTF-8")}" else "") +
                "&isAutoplay=$isAutoplay"
    }
    object Vod : Screen("vod")
    object LiveTV : Screen("livetv")
    object Dvr : Screen("dvr")
    object DvrPlayer : Screen("dvr_player/{filePath}?title={title}") {
        fun createRoute(filePath: String, title: String) =
            "dvr_player/${java.net.URLEncoder.encode(filePath, "UTF-8")}?title=${java.net.URLEncoder.encode(title, "UTF-8")}"
    }
    object Settings : Screen("settings")
    object Admin : Screen("admin")
    object ProviderDetail : Screen("provider/{providerId}?name={name}&logoUrl={logoUrl}") {
        fun createRoute(providerId: Int, name: String, logoUrl: String? = null) =
            "provider/$providerId?name=${java.net.URLEncoder.encode(name, "UTF-8")}" +
            (if (logoUrl != null) "&logoUrl=${java.net.URLEncoder.encode(logoUrl, "UTF-8")}" else "")
    }
}

@Composable
fun DuckFlixApp(
    viewModel: AppViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()

    // Update check state
    val updateInfo by viewModel.updateInfo.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    // Navigate to Home when login status changes to true (auto-login)
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            val currentRoute = navController.currentDestination?.route
            if (currentRoute == Screen.LoginUsername.route ||
                currentRoute?.startsWith("login") == true) {
                navController.navigate(Screen.Home.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
            // Check for updates after login
            viewModel.checkForUpdate()
        }
    }

    // Show update dialog
    updateInfo?.let { info ->
        UpdateAvailableDialog(
            currentVersion = BuildConfig.VERSION_NAME,
            newVersion = info.versionName,
            releaseNotes = info.releaseNotes,
            isDownloading = isDownloading,
            downloadProgress = downloadProgress,
            onUpdateNow = { viewModel.downloadAndInstall() },
            onLater = { viewModel.dismissUpdate() }
        )
    }

    // Start at login, will auto-navigate to Home if already logged in
    val startDestination = Screen.LoginUsername.route

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Old single-screen login (kept for backwards compatibility with logout)
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        // Two-step login flow - Username screen
        composable(Screen.LoginUsername.route) {
            UsernameScreen(
                onUsernameSubmit = { username ->
                    navController.navigate(Screen.LoginPassword.createRoute(username))
                }
            )
        }

        // Two-step login flow - Password screen
        composable(
            route = Screen.LoginPassword.route,
            arguments = listOf(
                navArgument("username") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username") ?: ""
            PasswordScreen(
                username = username,
                onPasswordSubmit = { password ->
                    // Login is handled in the PasswordScreen via ViewModel
                },
                onBack = {
                    navController.navigateUp()
                }
            )

            // Listen for login success via ViewModel
            val viewModel: com.duckflix.lite.ui.screens.login.LoginViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            if (uiState.isLoggedIn) {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.LoginUsername.route) { inclusive = true }
                }
            }
        }

        composable(Screen.Home.route) {
            LandingScreen(
                onNavigateToVod = {
                    navController.navigate(Screen.Vod.route)
                },
                onNavigateToLiveTv = {
                    navController.navigate(Screen.LiveTV.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Vod.route) {
            VodContainerScreen(navController = navController)
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
                },
                navArgument("season") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("episode") {
                    type = NavType.IntType
                    defaultValue = -1
                }
            )
        ) {
            DetailScreen(
                onPlayClick = { tmdbId, title, year, type, season, episode, resumePosition, posterUrl, logoUrl, originalLanguage, isRandom, episodeTitle ->
                    println("[LOGO-DEBUG-NAV] Navigation to player:")
                    println("[LOGO-DEBUG-NAV]   title: $title")
                    println("[LOGO-DEBUG-NAV]   posterUrl: $posterUrl")
                    println("[LOGO-DEBUG-NAV]   logoUrl: $logoUrl")
                    println("[LOGO-DEBUG-NAV]   isRandom: $isRandom")
                    println("[LOGO-DEBUG-NAV]   episodeTitle: $episodeTitle")
                    val route = Screen.Player.createRoute(tmdbId, title, year, type, season, episode, resumePosition, posterUrl, logoUrl, originalLanguage, isRandom, episodeTitle)
                    println("[LOGO-DEBUG-NAV]   route: $route")
                    navController.navigate(route)
                },
                onSearchTorrents = { tmdbId, title ->
                    // TODO: Navigate to torrent search/Prowlarr flow
                },
                onNavigateBack = {
                    navController.navigateUp()
                },
                onActorClick = { personId ->
                    navController.navigate(Screen.ActorFilmography.createRoute(personId))
                }
            )
        }

        composable(
            route = Screen.ActorFilmography.route,
            arguments = listOf(
                navArgument("personId") { type = NavType.IntType }
            )
        ) {
            ActorFilmographyScreen(
                onNavigateBack = {
                    navController.navigateUp()
                },
                onContentClick = { tmdbId, mediaType ->
                    navController.navigate(Screen.Detail.createRoute(tmdbId, mediaType))
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
                },
                navArgument("logoUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("originalLanguage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("isRandom") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("episodeTitle") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("isAutoplay") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) {
            val playerViewModel: com.duckflix.lite.ui.screens.player.VideoPlayerViewModel = androidx.hilt.navigation.compose.hiltViewModel()

            // Setup auto-play navigation callbacks
            playerViewModel.onAutoPlayNext = onAutoPlayNext@{ season, episode, episodeTitle ->
                val currentEntry = navController.currentBackStackEntry ?: return@onAutoPlayNext
                val tmdbId = currentEntry.arguments?.getInt("tmdbId") ?: return@onAutoPlayNext
                val title = currentEntry.arguments?.getString("title") ?: return@onAutoPlayNext
                val year = currentEntry.arguments?.getString("year")
                val posterUrl = currentEntry.arguments?.getString("posterUrl")
                val logoUrl = currentEntry.arguments?.getString("logoUrl")
                val originalLanguage = currentEntry.arguments?.getString("originalLanguage")
                val isRandom = currentEntry.arguments?.getBoolean("isRandom") ?: false

                navController.navigate(
                    Screen.Player.createRoute(tmdbId, title, year, "tv", season, episode, null, posterUrl, logoUrl, originalLanguage, isRandom, episodeTitle, isAutoplay = true)
                ) {
                    popUpTo(Screen.Player.route) { inclusive = true }
                }
            }

            playerViewModel.onAutoPlayRecommendation = { recommendationTmdbId ->
                navController.navigate(Screen.Detail.createRoute(recommendationTmdbId, "movie"))
            }

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
                },
                onLogoutSuccess = {
                    navController.navigate(Screen.LoginUsername.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Admin.route) {
            AdminScreen(
                onNavigateBack = {
                    navController.navigateUp()
                }
            )
        }

        composable(Screen.LiveTV.route) {
            LiveTvScreen(
                onNavigateBack = {
                    navController.navigateUp()
                },
                onNavigateToDvr = {
                    navController.navigate(Screen.Dvr.route)
                }
            )
        }

        // Provider Detail Screen
        composable(
            route = Screen.ProviderDetail.route,
            arguments = listOf(
                navArgument("providerId") { type = NavType.IntType },
                navArgument("name") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("logoUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.getInt("providerId") ?: 0
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val logoUrl = backStackEntry.arguments?.getString("logoUrl")

            ProviderDetailScreen(
                providerId = providerId,
                providerName = java.net.URLDecoder.decode(name, "UTF-8"),
                providerLogoUrl = logoUrl?.let { java.net.URLDecoder.decode(it, "UTF-8") },
                onContentClick = { tmdbId, mediaType ->
                    navController.navigate(Screen.Detail.createRoute(tmdbId, mediaType))
                },
                onNavigateBack = {
                    navController.navigateUp()
                }
            )
        }

        composable(Screen.Dvr.route) {
            DvrScreen(
                onNavigateBack = {
                    navController.navigateUp()
                },
                onPlayRecording = { filePath, title ->
                    navController.navigate(Screen.DvrPlayer.createRoute(filePath, title))
                }
            )
        }

        composable(
            route = Screen.DvrPlayer.route,
            arguments = listOf(
                navArgument("filePath") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = "Recording"
                }
            )
        ) { backStackEntry ->
            val filePath = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("filePath") ?: "", "UTF-8"
            )
            val title = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("title") ?: "Recording", "UTF-8"
            )
            DvrPlayerScreen(
                filePath = filePath,
                title = title,
                onNavigateBack = { navController.navigateUp() }
            )
        }
    }
}
