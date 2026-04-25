package com.watchreader.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.watchreader.mobile.ui.screen.AddBookScreen
import com.watchreader.mobile.ui.screen.BookListScreen

@Composable
fun MobileNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "books") {
        composable("books") {
            BookListScreen(
                onAddBook = { navController.navigate("add") },
            )
        }
        composable("add") {
            AddBookScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
