package tachiyomi.source.komga

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.OkHttpClient
import tachiyomi.source.komga.api.KomgaApi
import tachiyomi.source.komga.api.toSChapter
import tachiyomi.source.komga.api.toSManga
import tachiyomi.source.komga.dto.BookDto
import tachiyomi.source.komga.dto.LibraryDto
import tachiyomi.source.komga.dto.SeriesDto
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class KomgaSource(
    private val context: Context,
    private val preferences: KomgaPreferences,
) : HttpSource(), UnmeteredSource {

    override val id: Long = ID

    override val name: String = "书城"

    override val lang: String = "zh"

    override val supportsLatest: Boolean = true

    override val baseUrl: String get() = preferences.baseUrl

    private val komgaClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Use our own client with auth headers; HttpSource.client is the
    // global network client without Komga credentials.
    override val client: OkHttpClient get() = komgaClient

    // Covers go through MangaCoverFetcher which uses HttpSource.headers
    // and HttpSource.client. Override both so cover/page/image requests
    // carry Komga auth automatically.
    override fun headersBuilder(): Headers.Builder {
        val builder = super.headersBuilder()
        if (preferences.apiKey.isNotBlank()) {
            builder.add("X-API-Key", preferences.apiKey)
        } else if (preferences.username.isNotBlank()) {
            builder.add("Authorization", Credentials.basic(preferences.username, preferences.password))
        }
        return builder
    }

    private val api: KomgaApi by lazy { KomgaApi(komgaClient, preferences) }

    @Volatile
    private var seriesCache: Pair<List<SeriesDto>, Long>? = null
    @Volatile
    private var seriesCacheLibId: String? = null
    @Volatile
    private var librariesCache: Pair<List<LibraryDto>, Long>? = null
    @Volatile
    private var booksCache: Pair<String, List<BookDto>>? = null
    private val cacheTtl = 5 * 60 * 1000L

    override fun getFilterList(): FilterList = FilterList(
        listOf(
            LibraryFilter(cachedLibrariesOnly()),
            SortFilter(),
        ),
    )

    // --- Cache helpers ---

    private fun cachedLibrariesOnly(): List<LibraryDto> = librariesCache?.first ?: emptyList()

    private fun getCachedLibraries(): List<LibraryDto> {
        librariesCache?.let { (libs, ts) ->
            if (System.currentTimeMillis() - ts < cacheTtl) return libs
        }
        return try {
            val libs = api.getLibraries()
            if (libs.isNotEmpty()) {
                librariesCache = libs to System.currentTimeMillis()
                libs
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getCachedSeries(libraryId: String? = null): List<SeriesDto> {
        val cached = seriesCache
        if (cached != null && seriesCacheLibId == libraryId && System.currentTimeMillis() - cached.second < cacheTtl) {
            return cached.first
        }
        val series = api.getAllSeries(libraryId)
        val readyIds = api.getReadyBookSeriesIds(libraryId)
        val filtered = series.filter { it.id in readyIds }
        seriesCache = filtered to System.currentTimeMillis()
        seriesCacheLibId = libraryId
        return filtered
    }

    private fun createLibrarySManga(lib: LibraryDto, coverUrl: String? = null): SManga = SManga.create().apply {
        title = lib.name
        url = "$LIB_PREFIX${lib.id}"
        thumbnail_url = coverUrl?.takeIf { it.isNotBlank() }
        status = SManga.UNKNOWN
        initialized = true
        memo = buildJsonObject { put(MEMO_KIND, JsonPrimitive(KIND_LIBRARY)) }
    }

    // --- Directory tree ---

    private fun findCommonPrefixSegments(paths: List<List<String>>): List<String> {
        if (paths.isEmpty()) return emptyList()
        var prefix = paths[0]
        for (path in paths.drop(1)) {
            val len = minOf(prefix.size, path.size)
            var i = 0
            while (i < len && prefix[i] == path[i]) i++
            prefix = prefix.subList(0, i)
            if (prefix.isEmpty()) return emptyList()
        }
        return prefix
    }

    private fun buildDirectoryItems(allSeries: List<SeriesDto>, currentPath: String): List<SManga> {
        val pathsWithUrl = allSeries.mapNotNull { series ->
            series.url?.takeIf { it.isNotBlank() }?.let { series to it }
        }
        if (pathsWithUrl.isEmpty()) return allSeries.map { series ->
            series.toSManga(preferences.baseUrl).apply {
                url = "$SERIES_PREFIX${series.id}"
                memo = buildJsonObject {
                    put(MEMO_KIND, JsonPrimitive(KIND_SERIES))
                }
            }
        }

        val splitPaths = pathsWithUrl.map { (_, path) -> path.split("/").filter { it.isNotBlank() } }
        val commonPrefix = findCommonPrefixSegments(splitPaths)
        val dirPrefix = if (splitPaths.any { it.size == commonPrefix.size }) {
            commonPrefix.dropLast(1)
        } else {
            commonPrefix
        }

        val currentSegments = if (currentPath.isBlank()) emptyList()
        else currentPath.split("/").filter { it.isNotBlank() }

        val items = mutableListOf<SManga>()
        val seenDirs = mutableSetOf<String>()

        for ((series, fullPath) in pathsWithUrl) {
            val segments = fullPath.split("/").filter { it.isNotBlank() }
            val relativeSegments = if (dirPrefix.size < segments.size) {
                segments.subList(dirPrefix.size, segments.size)
            } else {
                emptyList()
            }

            if (relativeSegments.size <= currentSegments.size) continue
            if (currentSegments.isNotEmpty() &&
                relativeSegments.subList(0, currentSegments.size) != currentSegments
            ) continue

            val nextSegment = relativeSegments[currentSegments.size]

            if (relativeSegments.size == currentSegments.size + 1) {
                items.add(series.toSManga(preferences.baseUrl).apply {
                    url = "$SERIES_PREFIX${series.id}"
                    memo = buildJsonObject {
                        put(MEMO_KIND, JsonPrimitive(KIND_SERIES))
                    }
                })
            } else if (nextSegment !in seenDirs) {
                seenDirs.add(nextSegment)
                val dirPath = (currentSegments + nextSegment).joinToString("/")
                items.add(createDirectorySManga(nextSegment, dirPath))
            }
        }
        return items
    }

    private fun createDirectorySManga(name: String, dirPath: String): SManga = SManga.create().apply {
        title = name
        url = "$DIR_PREFIX$dirPath"
        thumbnail_url = null
        status = SManga.UNKNOWN
        initialized = true
        memo = buildJsonObject { put(MEMO_KIND, JsonPrimitive(KIND_DIRECTORY)) }
    }

    // --- Book directory tree (inside a series) ---

    @Volatile
    private var booksCacheTime: Long = 0L

    private fun getCachedBooks(seriesId: String): List<BookDto> {
        val cached = booksCache
        if (cached != null && cached.first == seriesId &&
            System.currentTimeMillis() - booksCacheTime < cacheTtl
        ) {
            return cached.second
        }
        val books = api.getAllBooksBySeries(seriesId)
        booksCache = seriesId to books
        booksCacheTime = System.currentTimeMillis()
        return books
    }

    private fun buildBookDirectoryItems(
        allBooks: List<BookDto>,
        seriesId: String,
        currentPath: String,
        baseUrl: String,
    ): List<SManga> {
        val currentSegments = if (currentPath.isBlank()) emptyList()
        else currentPath.split("/").filter { it.isNotBlank() }

        val items = mutableListOf<SManga>()
        val seenDirs = mutableSetOf<String>()

        for (book in allBooks) {
            val dirPath = book.directoryPath ?: ""
            val segments = dirPath.split("/").filter { it.isNotBlank() }

            // Books at current level (no deeper path)
            if (segments.size == currentSegments.size &&
                (currentSegments.isEmpty() || segments == currentSegments)
            ) {
                items.add(createBookSManga(book, seriesId, baseUrl))
                continue
            }

            // Books deeper than current level — extract subdirectory
            if (segments.size > currentSegments.size &&
                (currentSegments.isEmpty() || segments.subList(0, currentSegments.size) == currentSegments)
            ) {
                val nextSegment = segments[currentSegments.size]
                if (nextSegment !in seenDirs) {
                    seenDirs.add(nextSegment)
                    val subPath = (currentSegments + nextSegment).joinToString("/")
                    items.add(createBookDirectorySManga(nextSegment, seriesId, subPath))
                }
            }
        }
        return items
    }

    private fun createBookSManga(book: BookDto, seriesId: String, baseUrl: String): SManga = SManga.create().apply {
        title = book.metadata.title.ifBlank { book.name }
        url = "$baseUrl/api/v1/books/${book.id}"
        thumbnail_url = "$baseUrl/api/v1/books/${book.id}/thumbnail"
        status = SManga.UNKNOWN
        initialized = true
        memo = buildJsonObject {
            put(MEMO_KIND, JsonPrimitive(KIND_BOOK))
            put(MEMO_QUERY, JsonPrimitive("$BOOK_PREFIX$seriesId/${book.id}"))
        }
    }

    private fun createBookDirectorySManga(name: String, seriesId: String, subPath: String): SManga = SManga.create().apply {
        title = name
        url = "$BOOKDIR_PREFIX$seriesId/$subPath"
        thumbnail_url = null
        status = SManga.UNKNOWN
        initialized = true
        memo = buildJsonObject { put(MEMO_KIND, JsonPrimitive(KIND_BOOK_DIRECTORY)) }
    }

    // --- Source interface (suspend API, overrides CatalogueSource defaults) ---

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val libraries = getCachedLibraries()
        val items = libraries.map { lib ->
            createLibrarySManga(lib, api.getFirstSeriesThumbnail(lib.id))
        }
        return MangasPage(items, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val (manga, hasNext) = api.getSeries(
            page = page - 1,
            sort = "lastModifiedDate,desc",
        )
        return MangasPage(manga, hasNext)
    }

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        // Library card click -> show series directory tree for that library
        if (query.startsWith(LIB_PREFIX)) {
            if (page > 1) return MangasPage(emptyList(), false)
            val libId = query.removePrefix(LIB_PREFIX)
            val allSeries = getCachedSeries(libId)
            val items = buildDirectoryItems(allSeries, "")
            return MangasPage(items, false)
        }

        // Directory card click -> show subdirectories/series
        if (query.startsWith(DIR_PREFIX)) {
            if (page > 1) return MangasPage(emptyList(), false)
            val dirPath = query.removePrefix(DIR_PREFIX)
            val allSeries = getCachedSeries()
            val items = buildDirectoryItems(allSeries, dirPath)
            return MangasPage(items, false)
        }

        // Series card click -> show book directory tree for that series
        if (query.startsWith(SERIES_PREFIX)) {
            if (page > 1) return MangasPage(emptyList(), false)
            // Format: series://seriesId
            val seriesId = query.removePrefix(SERIES_PREFIX).takeIf { it.isNotBlank() }
                ?: return MangasPage(emptyList(), false)
            val books = getCachedBooks(seriesId)
            val items = buildBookDirectoryItems(books, seriesId, "", preferences.baseUrl)
            return MangasPage(items, false)
        }

        // Book directory card click -> show subdirectories/books at deeper level
        if (query.startsWith(BOOKDIR_PREFIX)) {
            if (page > 1) return MangasPage(emptyList(), false)
            val rest = query.removePrefix(BOOKDIR_PREFIX)
            val slashIdx = rest.indexOf("/")
            if (slashIdx < 0) return MangasPage(emptyList(), false)
            val seriesId = rest.substring(0, slashIdx)
            val subPath = rest.substring(slashIdx + 1)
            val books = getCachedBooks(seriesId)
            val items = buildBookDirectoryItems(books, seriesId, subPath, preferences.baseUrl)
            return MangasPage(items, false)
        }

        // Root listing -> show libraries
        if (query.isBlank()) {
            if (page > 1) return MangasPage(emptyList(), false)
            val libraries = api.getLibraries()
            if (libraries.isNotEmpty()) {
                librariesCache = libraries to System.currentTimeMillis()
            }
            val items = libraries.map { lib ->
                createLibrarySManga(lib, api.getFirstSeriesThumbnail(lib.id))
            }
            return MangasPage(items, false)
        }

        val libraryId = (filters.find { it is LibraryFilter } as? LibraryFilter)?.let {
            it.libraries[it.state].id.takeIf { lib -> lib.isNotBlank() }
        }

        val sort = (filters.find { it is SortFilter } as? SortFilter)?.let { filter ->
            val state = filter.state ?: return@let null
            val column = when (state.index) {
                0 -> "relevance"
                1 -> "metadata.titleSort"
                2 -> "createdDate"
                3 -> "lastModifiedDate"
                else -> null
            }
            column?.let { "$it,${if (state.ascending) "asc" else "desc"}" }
        }

        val (manga, hasNext) = api.getSeries(
            page = page - 1,
            search = query.takeIf { it.isNotBlank() },
            sort = sort ?: "metadata.titleSort,asc",
            libraryId = libraryId,
        )
        return MangasPage(manga, hasNext)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Book-level manga (opened from the book directory tree) — the
        // book itself is the single chapter.
        if (manga.url.contains("/api/v1/books/")) {
            val bookId = extractBookId(manga.url)
            val book = api.getBookById(bookId)
            val updatedManga = if (fetchDetails && book != null) {
                manga.apply {
                    title = book.metadata.title.ifBlank { book.name }
                    thumbnail_url = "$baseUrl/api/v1/books/$bookId/thumbnail"
                    initialized = true
                }
            } else {
                manga
            }
            val updatedChapters = if (fetchChapters && book != null) {
                val isReadable = book.media.mediaProfile != "EPUB" || book.media.epubDivinaCompatible
                if (isReadable) {
                    listOf(book.toSChapter(preferences.baseUrl))
                } else {
                    emptyList()
                }
            } else {
                chapters
            }
            return SMangaUpdate(updatedManga, updatedChapters)
        }

        val seriesId = extractSeriesId(manga.url)

        val updatedManga = if (fetchDetails) {
            api.getSeriesById(seriesId)?.toSManga(preferences.baseUrl) ?: manga
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val books = api.getAllBooksBySeries(seriesId)
            books
                .filter {
                    it.media.mediaProfile != "EPUB" || it.media.epubDivinaCompatible
                }
                .map { it.toSChapter(preferences.baseUrl) }
                .sortedByDescending { it.chapter_number }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val bookId = extractBookId(chapter.url)

        val book = api.getBookById(bookId)
        if (book?.media?.mediaProfile == "VIDEO" || book?.media?.mediaProfile == "AUDIO") {
            // Mihon's reader is image-only — it cannot play video/audio.
            // Return the stream URL so the reader's "Open in WebView" button
            // lets the user play it in the system browser.
            val streamUrl = "$baseUrl/api/v1/books/$bookId/stream"
            return listOf(Page(1, imageUrl = streamUrl))
        }

        val pages = api.getPages(bookId)
        if (pages.isEmpty()) return emptyList()

        return pages.map { p ->
            // Non-image pages (e.g. PDF) get ?convert=png so Komga renders
            // them as images. Supported image types load directly.
            val url = "$baseUrl/api/v1/books/$bookId/pages/${p.number}" +
                if (p.mediaType !in KomgaApi.SUPPORTED_IMAGE_TYPES) "?convert=png" else ""
            Page(p.number, imageUrl = url)
        }
    }

    private fun extractSeriesId(url: String): String =
        url.substringAfter("/api/v1/series/").substringBefore("/").substringBefore("?")

    private fun extractBookId(url: String): String =
        url.substringAfter("/api/v1/books/").substringBefore("/").substringBefore("?")

    companion object {
        const val ID = 69420L
        const val DIR_PREFIX = "dir://"
        const val LIB_PREFIX = "lib://"
        const val SERIES_PREFIX = "series://"
        const val BOOK_PREFIX = "book://"
        const val BOOKDIR_PREFIX = "bookdir://"
        const val MEMO_KIND = "mihon.kind"
        const val MEMO_QUERY = "mihon.query"
        const val KIND_DIRECTORY = "directory"
        const val KIND_LIBRARY = "library"
        const val KIND_SERIES = "series"
        const val KIND_BOOK = "book"
        const val KIND_BOOK_DIRECTORY = "book_directory"
    }
}

class LibraryFilter(
    val libraries: List<LibraryDto>,
) : Filter.Select<String>(
    "Library",
    arrayOf("All") + libraries.map { it.name }.toTypedArray(),
    0,
)

class SortFilter : Filter.Sort(
    "Sort",
    arrayOf("Relevance", "Title", "Created", "Last Modified"),
    Selection(1, true),
)
