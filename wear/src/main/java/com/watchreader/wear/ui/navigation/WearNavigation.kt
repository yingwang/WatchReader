package com.watchreader.wear.ui.navigation

import androidx.compose.runtime.Composable
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.watchreader.wear.ui.screen.LibraryScreen
import com.watchreader.wear.ui.screen.ReaderScreen
import com.watchreader.wear.ui.screen.SettingsScreen

@Composable
fun WearNavigation() {
    val navController = rememberSwipeDismissableNavController()

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = "library",
    ) {
        composable("library") {
            LibraryScreen(
                onBookClick = { bookId ->
                    navController.navigate("reader/$bookId")
                },
                onSettings = {
                    navController.navigate("settings")
                },
            )
        }
        composable("reader/{bookId}") { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
            ReaderScreen(bookId = bookId)
        }
        composable("settings") {
            SettingsScreen()
        }
    }
}
