package com.watchreader.wear.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.watchreader.wear.ui.WearActivity
import com.watchreader.wear.ui.screen.LibraryScreen
import com.watchreader.wear.ui.screen.ReaderScreen
import com.watchreader.wear.ui.screen.SettingsScreen
import com.watchreader.wear.ui.screen.AppearanceScreen
import com.watchreader.wear.ui.screen.AppearanceEditor
import com.watchreader.wear.ui.screen.SpeechSettingsScreen

@Composable
fun WearNavigation(openRequest: WearActivity.OpenRequest?) {
    val navController = rememberSwipeDismissableNavController()

    LaunchedEffect(openRequest?.serial) {
        val id = openRequest?.bookId ?: return@LaunchedEffect
        navController.navigate("reader/$id") {
            popUpTo("library")
            launchSingleTop = true
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = "library",
    ) {
        composable("library") {
            LibraryScreen(
                onBookClick = { bookId -> navController.navigate("reader/$bookId") },
                onSettings = { navController.navigate("settings") },
            )
        }
        composable("reader/{bookId}") { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
            ReaderScreen(bookId = bookId)
        }
        composable("settings") {
            SettingsScreen(
                onAppearance = { navController.navigate("appearance") },
                onSpeech = { navController.navigate("speech") },
            )
        }
        composable("appearance") { AppearanceScreen { navController.navigate("appearance/$it") } }
        composable("appearance/{kind}") { entry -> AppearanceEditor(entry.arguments?.getString("kind") ?: "size") }
        composable("speech") { SpeechSettingsScreen() }
    }
}
