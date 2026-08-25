package eu.kanade.tachiyomi.ui.komga

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import tachiyomi.source.komga.KomgaSource

data object KomgaTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val icon = rememberVectorPainter(Icons.Outlined.CollectionsBookmark)
            return TabOptions(
                index = 3u,
                title = "书城",
                icon = icon,
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Pop everything above BrowseSourceScreen when reselecting the tab
        while (navigator.lastItem !is BrowseSourceScreen && navigator.canPop) {
            navigator.pop()
        }
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        LaunchedEffect(Unit) {
            // Push only if no BrowseSourceScreen exists anywhere in the stack.
            // Checking the whole stack (not just lastItem) makes this idempotent
            // across recomposition: after back pops the screen, HomeScreen
            // recombines KomgaTab.Content and must NOT push it again.
            if (navigator.items.none { it is BrowseSourceScreen }) {
                navigator.push(BrowseSourceScreen(KomgaSource.ID, null))
            }
        }
    }
}
