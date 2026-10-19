package com.watchreader.mobile.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.watchreader.mobile.R
import com.watchreader.mobile.ui.SharedIntent
import com.watchreader.mobile.ui.screen.AddBookScreen
import com.watchreader.mobile.ui.screen.BookDetailsScreen
import com.watchreader.mobile.ui.screen.BookListScreen
import com.watchreader.mobile.ui.screen.FreeBooksScreen
import com.watchreader.mobile.ui.screen.ReadingStatsScreen
import com.watchreader.mobile.ui.screen.ReaderScreen
import com.watchreader.mobile.ui.viewmodel.BookListViewModel

/** The three places the bottom bar leads to; every other screen is reached from one of them. */
private enum class Tab(val route: String, @StringRes val label: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
    LIBRARY("books", R.string.nav_library, Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
    DISCOVER("free", R.string.nav_discover, Icons.Filled.Explore, Icons.Outlined.Explore),
    STATS("stats", R.string.nav_stats, Icons.Filled.BarChart, Icons.Outlined.BarChart),
}

@Composable
fun MobileNavigation(shareGeneration: Int) {
    val navController = rememberNavController()
    // One list view model for every screen: the reader sends to the watch through the same path.
    val listVm: BookListViewModel = viewModel()
    // Popping past the start destination leaves the host blank, and a screen can ask to go back
    // twice: an import that finishes after its screen was already left does exactly that.
    val back: () -> Unit = { if (navController.previousBackStackEntry != null) navController.popBackStack() }
    val selectTab: (Tab) -> Unit = { navController.selectTab(it) }
    // Tapping the tab already open counts up here, and that tab scrolls back to its top.
    val reselected = remember { mutableStateMapOf<Tab, Int>() }

    // A file or link shared from another app opens the add screen straight away.
    LaunchedEffect(shareGeneration) {
        if (shareGeneration > 0 && SharedIntent.pending != null) {
            navController.navigate("add") { launchSingleTop = true }
        }
    }

    val entry by navController.currentBackStackEntryAsState()
    // The bar belongs to the three tabs only; reading, details and adding a book have the whole screen.
    val current = Tab.entries.firstOrNull { it.route == entry?.destination?.route }

    Scaffold(
        // Each screen handles the system bars itself, as it did before there was a bottom bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (current != null) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == current,
                            onClick = {
                                if (tab == current) reselected[tab] = (reselected[tab] ?: 0) + 1 else selectTab(tab)
                            },
                            icon = { Icon(if (tab == current) tab.selectedIcon else tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // The bar's height, system navigation included, is taken off the screens inside, so their
        // own scaffolds do not leave room for the system bar a second time.
        NavHost(
            navController = navController,
            startDestination = Tab.LIBRARY.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Tab.LIBRARY.route) {
                BookListScreen(
                    vm = listVm,
                    onAddBook = { navController.navigate("add") },
                    onOpenBook = { id -> navController.navigate("read/$id") },
                    onDetails = { id -> navController.navigate("details/$id") },
                    onFreeBooks = { selectTab(Tab.DISCOVER) },
                    scrollToTop = reselected[Tab.LIBRARY] ?: 0,
                )
            }
            composable(Tab.DISCOVER.route) {
                // A book added from the free library is shown where it landed, in the library itself.
                FreeBooksScreen(onAdded = { selectTab(Tab.LIBRARY) }, scrollToTop = reselected[Tab.DISCOVER] ?: 0)
            }
            composable(Tab.STATS.route) {
                ReadingStatsScreen(
                    listVm = listVm,
                    onDetails = { id -> navController.navigate("details/$id") },
                    scrollToTop = reselected[Tab.STATS] ?: 0,
                )
            }
            composable(
                route = "details/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                BookDetailsScreen(
                    bookId = entry.arguments?.getString("bookId").orEmpty(),
                    listVm = listVm,
                    onBack = back,
                    onOpenBook = { id -> navController.navigate("read/$id") },
                )
            }
            composable("add") {
                AddBookScreen(
                    shareGeneration = shareGeneration,
                    listVm = listVm,
                    onOpenBook = { id ->
                        navController.popBackStack(Tab.LIBRARY.route, inclusive = false)
                        navController.navigate("read/$id")
                    },
                    onBack = back,
                    // Leave the add screen first, so it is not kept as part of the library's saved screens.
                    onFreeBooks = {
                        back()
                        selectTab(Tab.DISCOVER)
                    },
                )
            }
            composable(
                route = "read/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                // The reader applies its own ReadingTheme, as the page colours are chosen inside it.
                ReaderScreen(
                    bookId = entry.arguments?.getString("bookId").orEmpty(),
                    listVm = listVm,
                    onBack = back,
                )
            }
        }
    }
}

/**
 * Switches tabs the usual way: back to the library underneath, each tab keeping its own scroll,
 * search and screens for when it is opened again.
 */
private fun NavController.selectTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Runs [scroll] each time [signal] goes up while the screen is showing. The count already seen is
 * kept with the screen, so coming back to a tab later does not scroll it as well.
 */
@Composable
fun ScrollToTopOn(signal: Int, scroll: suspend () -> Unit) {
    var seen by rememberSaveable { mutableIntStateOf(signal) }
    LaunchedEffect(signal) {
        if (signal != seen) {
            seen = signal
            scroll()
        }
    }
}
