package com.watchreader.wear.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.createGraph
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
import com.watchreader.wear.ui.screen.AutoTurnSettingsScreen

/**
 * [resumeBookId] is read once, on the first composition: that book's page opens on top of the
 * library, so the app starts inside the book and Back leads to the library.
 */
@Composable
fun WearNavigation(openRequest: WearActivity.OpenRequest?, resumeBookId: String? = null) {
    val navController = rememberSwipeDismissableNavController()
    val viewModelStoreOwner = LocalViewModelStoreOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val graph = remember(navController) {
        val graph = navController.createGraph(startDestination = "library") {
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
                    onAutoTurn = { navController.navigate("autoturn") },
                )
            }
            composable("appearance") { AppearanceScreen { navController.navigate("appearance/$it") } }
            composable("appearance/{kind}") { entry -> AppearanceEditor(entry.arguments?.getString("kind") ?: "size") }
            composable("speech") { SpeechSettingsScreen() }
            composable("autoturn") { AutoTurnSettingsScreen() }
        }
        // Going back into a book, the back stack is laid down with the graph, before the host
        // first draws, so the page comes up by itself rather than sliding in over the library.
        // The host sets the same store and graph again when it composes; with both already in
        // place that keeps the stack as it is.
        if (resumeBookId != null && viewModelStoreOwner != null && navController.currentBackStackEntry == null) {
            navController.setViewModelStore(viewModelStoreOwner.viewModelStore)
            navController.setLifecycleOwner(lifecycleOwner)
            navController.graph = graph
            navController.navigate("reader/$resumeBookId")
        }
        graph
    }

    LaunchedEffect(openRequest?.serial) {
        val id = openRequest?.bookId ?: return@LaunchedEffect
        navController.navigate("reader/$id") {
            popUpTo("library")
            launchSingleTop = true
        }
    }

    SwipeDismissableNavHost(navController = navController, graph = graph)
}
