package eu.kanade.tachiyomi.ui.komga

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookstore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.komga.KomgaSource

data object KomgaTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            return TabOptions(
                index = 3u,
                title = "书城",
                icon = Icons.Outlined.Bookstore,
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Navigate back to root of Komga browse
        navigator.popUntilRoot()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        LaunchedEffect(Unit) {
            navigator.push(BrowseSourceScreen(KomgaSource.ID, null))
        }
    }
}
