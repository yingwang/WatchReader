package com.watchreader.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.watchreader.mobile.ui.SharedIntent
import com.watchreader.mobile.ui.screen.AddBookScreen
import com.watchreader.mobile.ui.screen.BookListScreen

@Composable
fun MobileNavigation(shareGeneration: Int) {
    val navController = rememberNavController()

    // A file shared from another app opens the add screen straight away.
    LaunchedEffect(shareGeneration) {
        if (shareGeneration > 0 && SharedIntent.pendingUri != null) {
            navController.navigate("add") { launchSingleTop = true }
        }
    }

    NavHost(navController = navController, startDestination = "books") {
        composable("books") {
            BookListScreen(onAddBook = { navController.navigate("add") })
        }
        composable("add") {
            AddBookScreen(onBack = { navController.popBackStack() })
        }
    }
}
