package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        val viewModel =
            assistedMetroViewModel<BrowseSourceViewModel, BrowseSourceViewModel.Factory> {
                create(sourceId = sourceId, listingQuery = listingQuery)
            }
        val state by viewModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val scopeForTab = rememberCoroutineScope()
        val navigateUp: () -> Unit = {
            val query = state.listing.query
            val dirQuery = query?.takeIf { it.startsWith("dir://") }
            val libQuery = query?.takeIf { it.startsWith("lib://") }
            val seriesQuery = query?.takeIf { it.startsWith("series://") }
            val bookDirQuery = query?.takeIf { it.startsWith("bookdir://") }
            when {
                bookDirQuery != null -> {
                    // Walking up inside a series' book directory tree
                    val rest = bookDirQuery.removePrefix("bookdir://")
                    val slashIdx = rest.indexOf("/")
                    val seriesId = rest.substring(0, slashIdx)
                    val subPath = rest.substring(slashIdx + 1)
                    val segments = subPath.split("/").filter { it.isNotBlank() }
                    if (segments.size > 1) {
                        val parentPath = segments.dropLast(1).joinToString("/")
                        viewModel.setListing(Listing.Search("bookdir://$seriesId/$parentPath", FilterList()))
                    } else {
                        // Back to series root (book listing)
                        viewModel.setListing(Listing.Search("series://$seriesId", FilterList()))
                    }
                }
                seriesQuery != null -> {
                    // Back from series book listing -> return to libraries root
                    viewModel.setListing(Listing.Search("", FilterList()))
                }
                dirQuery != null -> {
                    val segments = dirQuery.removePrefix("dir://").split("/").filter { it.isNotBlank() }
                    if (segments.size > 1) {
                        viewModel.setListing(Listing.Search("dir://" + segments.dropLast(1).joinToString("/"), FilterList()))
                    } else {
                        // Back from dir -> return to library root
                        viewModel.setListing(Listing.Search("", FilterList()))
                    }
                }
                libQuery != null -> {
                    // Back from library listing -> return to libraries
                    viewModel.setListing(Listing.Search("", FilterList()))
                }
                !state.isUserQuery && state.toolbarQuery != null -> viewModel.setToolbarQuery(null)
                sourceId == tachiyomi.source.komga.KomgaSource.ID -> {
                    // At bookshelf root: pop this screen and switch back to the
                    // Library tab. KomgaTab would otherwise re-push the screen
                    // on recomposition, making back appear to do nothing.
                    navigator.pop()
                    scopeForTab.launch {
                        eu.kanade.tachiyomi.ui.home.HomeScreen.openTab(
                            eu.kanade.tachiyomi.ui.home.HomeScreen.Tab.Library(),
                        )
                    }
                }
                else -> navigator.pop()
            }
        }

        // In directory-browse mode (Komga source), the system back button
        // follows the same navigation logic as the toolbar up button:
        // walk up the directory tree, and at the root pop + switch to Library.
        // This BackHandler registers later than the Navigator's default
        // pop handler, so it takes priority when enabled.
        if (sourceId == tachiyomi.source.komga.KomgaSource.ID) {
            androidx.activity.compose.BackHandler(enabled = true) {
                navigateUp()
            }
        }

        val source = state.source
        if (source == null) {
            LoadingScreen()
            return
        }

        if (source is StubSource) {
            MissingSourceScreen(
                source = source,
                navigateUp = navigateUp,
            )
            return
        }

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        val onWebViewClick = f@{
            val httpSource = source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = httpSource.getHomeUrl(),
                    initialTitle = httpSource.name,
                    sourceId = httpSource.id,
                ),
            )
        }

        LaunchedEffect(source) {
            assistUrl = (source as? HttpSource)?.getHomeUrl()
        }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    BrowseSourceToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = viewModel::setToolbarQuery,
                        source = source,
                        displayMode = viewModel.displayMode,
                        onDisplayModeChange = { viewModel.displayMode = it },
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                        onSearch = viewModel::search,
                    )

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        FilterChip(
                            selected = state.listing == Listing.Popular,
                            onClick = {
                                viewModel.resetFilters()
                                viewModel.setListing(Listing.Popular)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = {
                                Text(text = stringResource(MR.strings.popular))
                            },
                        )
                        if (source.supportsLatest) {
                            FilterChip(
                                selected = state.listing == Listing.Latest,
                                onClick = {
                                    viewModel.resetFilters()
                                    viewModel.setListing(Listing.Latest)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.NewReleases,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.latest))
                                },
                            )
                        }
                        if (state.filters.isNotEmpty()) {
                            FilterChip(
                                selected = state.listing is Listing.Search,
                                onClick = viewModel::openFilterSheet,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.FilterList,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.action_filter))
                                },
                            )
                        }
                    }

                    HorizontalDivider()

                    // Breadcrumb navigation for directory tree browsing
                    val query = state.listing.query
                    val dirQuery = query?.takeIf { it.startsWith("dir://") }
                    val libQuery = query?.takeIf { it.startsWith("lib://") }
                    val seriesQuery = query?.takeIf { it.startsWith("series://") }
                    val bookDirQuery = query?.takeIf { it.startsWith("bookdir://") }
                    if (dirQuery != null || libQuery != null || seriesQuery != null || bookDirQuery != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = MaterialTheme.padding.small, vertical = MaterialTheme.padding.extraSmall),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                        ) {
                            Text(
                                text = stringResource(MR.strings.label_library),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    viewModel.setListing(Listing.Search("", FilterList()))
                                },
                            )
                            if (libQuery != null) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(MaterialTheme.padding.small),
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                                Text(
                                    text = "媒体库",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (dirQuery != null) {
                                val segments = dirQuery.removePrefix("dir://").split("/").filter { it.isNotBlank() }
                                segments.forEachIndexed { index, segment ->
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(MaterialTheme.padding.small),
                                        tint = MaterialTheme.colorScheme.outline,
                                    )
                                    val isLast = index == segments.lastIndex && seriesQuery == null
                                    Text(
                                        text = segment,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isLast) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.primary,
                                        modifier = if (isLast) Modifier
                                        else Modifier.clickable {
                                            val targetPath = segments.subList(0, index + 1).joinToString("/")
                                            viewModel.setListing(Listing.Search("dir://$targetPath", FilterList()))
                                        },
                                    )
                                }
                            }
                            if (seriesQuery != null) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(MaterialTheme.padding.small),
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                                Text(
                                    text = "系列",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (bookDirQuery == null) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.primary,
                                    modifier = if (bookDirQuery == null) Modifier
                                    else Modifier.clickable {
                                        viewModel.setListing(Listing.Search(seriesQuery, FilterList()))
                                    },
                                )
                            }
                            if (bookDirQuery != null) {
                                val rest = bookDirQuery.removePrefix("bookdir://")
                                val slashIdx = rest.indexOf("/")
                                val subPath = if (slashIdx >= 0) rest.substring(slashIdx + 1) else ""
                                val segments = subPath.split("/").filter { it.isNotBlank() }
                                segments.forEachIndexed { index, segment ->
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(MaterialTheme.padding.small),
                                        tint = MaterialTheme.colorScheme.outline,
                                    )
                                    val isLast = index == segments.lastIndex
                                    Text(
                                        text = segment,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isLast) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.primary,
                                        modifier = if (isLast) Modifier
                                        else Modifier.clickable {
                                            val seriesId = rest.substring(0, slashIdx)
                                            val targetPath = segments.subList(0, index + 1).joinToString("/")
                                            viewModel.setListing(Listing.Search("bookdir://$seriesId/$targetPath", FilterList()))
                                        },
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            BrowseSourceContent(
                source = source,
                mangaList = viewModel.mangaPagerFlowFlow.collectAsLazyPagingItems(),
                columns = viewModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = viewModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = onHelpClick,
                onMangaClick = { manga ->
                    val memoKind = manga.memo?.let {
                        try {
                            it["mihon.kind"]?.jsonPrimitive?.contentOrNull
                        } catch (e: Exception) { null }
                    }
                    when (memoKind) {
                        "directory", "library", "series", "book_directory" -> {
                            viewModel.setListing(Listing.Search(manga.url, FilterList()))
                        }
                        "book" -> {
                            // Single book opened as manga — its URL is the
                            // Komga book API URL, which getMangaUpdate
                            // recognises via extractBookId.
                            navigator.push(MangaScreen(manga.id, true))
                        }
                        else -> {
                            navigator.push(MangaScreen(manga.id, true))
                        }
                    }
                },
                onMangaLongClick = { manga ->
                    val memoKind = manga.memo?.let {
                        try {
                            it["mihon.kind"]?.jsonPrimitive?.contentOrNull
                        } catch (e: Exception) { null }
                    }
                    if (memoKind != "directory" && memoKind != "library" &&
                        memoKind != "series" && memoKind != "book_directory"
                    ) {
                        scope.launchIO {
                            val duplicates = viewModel.getDuplicateLibraryManga(manga)
                            when {
                                manga.favorite -> viewModel.setDialog(BrowseSourceViewModel.Dialog.RemoveManga(manga))
                                duplicates.isNotEmpty() -> viewModel.setDialog(
                                    BrowseSourceViewModel.Dialog.AddDuplicateManga(manga, duplicates),
                                )
                                else -> viewModel.addFavorite(manga)
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
            )
        }

        val onDismissRequest = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceViewModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = viewModel::resetFilters,
                    onFilter = { viewModel.search(filters = state.filters) },
                    onUpdate = viewModel::setFilters,
                )
            }
            is BrowseSourceViewModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.setDialog(BrowseSourceViewModel.Dialog.Migrate(dialog.manga, it)) },
                )
            }

            is BrowseSourceViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceViewModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
                )
            }
            is BrowseSourceViewModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        viewModel.changeMangaFavorite(dialog.manga)
                        viewModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }
            else -> {}
        }

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> viewModel.searchGenre(it.txt)
                        is SearchType.Text -> viewModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}
