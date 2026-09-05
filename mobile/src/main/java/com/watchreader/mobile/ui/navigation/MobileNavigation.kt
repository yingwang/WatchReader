package com.watchreader.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.watchreader.mobile.ui.SharedIntent
import com.watchreader.mobile.ui.screen.AddBookScreen
import com.watchreader.mobile.ui.screen.BookListScreen
import com.watchreader.mobile.ui.screen.ReaderScreen
import com.watchreader.mobile.ui.viewmodel.BookListViewModel

@Composable
fun MobileNavigation(shareGeneration: Int) {
    val navController = rememberNavController()
    // One list view model for both screens: the reader sends to the watch through the same path.
    val listVm: BookListViewModel = viewModel()

    // A file shared from another app opens the add screen straight away.
    LaunchedEffect(shareGeneration) {
        if (shareGeneration > 0 && SharedIntent.pendingUri != null) {
            navController.navigate("add") { launchSingleTop = true }
        }
    }

    NavHost(navController = navController, startDestination = "books") {
        composable("books") {
            BookListScreen(
                vm = listVm,
                onAddBook = { navController.navigate("add") },
                onOpenBook = { id -> navController.navigate("read/$id") },
            )
        }
        composable("add") {
            AddBookScreen(shareGeneration = shareGeneration, onBack = { navController.popBackStack() })
        }
        composable(
            route = "read/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            ReaderScreen(
                bookId = entry.arguments?.getString("bookId").orEmpty(),
                listVm = listVm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
