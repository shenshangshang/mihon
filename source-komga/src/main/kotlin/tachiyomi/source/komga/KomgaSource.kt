package tachiyomi.source.komga

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import tachiyomi.source.komga.api.KomgaApi
import tachiyomi.source.komga.api.toSChapter
import tachiyomi.source.komga.api.toSManga
import tachiyomi.source.komga.dto.LibraryDto
import tachiyomi.source.komga.dto.SeriesDto
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class KomgaSource(
    private val context: Context,
    private val preferences: KomgaPreferences,
) : Source, UnmeteredSource {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val api: KomgaApi by lazy { KomgaApi(client, preferences) }

    @Volatile
    private var seriesCache: Pair<List<SeriesDto>, Long>? = null
    @Volatile
    private var librariesCache: List<LibraryDto>? = null
    private val cacheTtl = 5 * 60 * 1000L

    override val id: Long = ID

    override val name: String = "书城"

    override val lang: String = "zh"

    override val supportsLatest: Boolean = true

    override fun getFilterList(): FilterList = FilterList(
        listOf(
            LibraryFilter(getCachedLibraries()),
            SortFilter(),
        ),
    )

    private fun getCachedLibraries(): List<LibraryDto> {
        librariesCache?.let { return it }
        return try {
            val libs = api.getLibraries()
            librariesCache = libs
            libs
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getCachedSeries(libraryId: String? = null): List<SeriesDto> {
        val cached = seriesCache
        if (cached != null && System.currentTimeMillis() - cached.second < cacheTtl) {
            return cached.first
        }
        val series = api.getAllSeries(libraryId)
        seriesCache = series to System.currentTimeMillis()
        return series
    }

    private fun createLibrarySManga(lib: LibraryDto): SManga = SManga.create().apply {
        title = lib.name
        url = "$LIB_PREFIX${lib.id}"
        thumbnail_url = null
        status = SManga.UNKNOWN
        initialized = true
        memo = buildJsonObject { put(MEMO_KIND, JsonPrimitive(KIND_LIBRARY)) }
    }

    // --- Directory tree browsing ---

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
        if (pathsWithUrl.isEmpty()) return allSeries.map { it.toSManga(preferences.baseUrl) }

        val splitPaths = pathsWithUrl.map { (_, path) ->
            path.split("/").filter { it.isNotBlank() }
        }
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
                items.add(series.toSManga(preferences.baseUrl))
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

    // --- Source interface ---

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val libraries = getCachedLibraries()
        val items = libraries.map { createLibrarySManga(it) }
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
        // Library card click → show series directory tree for that library
        if (query.startsWith(LIB_PREFIX)) {
            if (page > 1) return MangasPage(emptyList(), false)
            val libId = query.removePrefix(LIB_PREFIX)
            val allSeries = getCachedSeries(libId)
            val items = buildDirectoryItems(allSeries, "")
            return MangasPage(items, false)
        }

        // Directory card click → show subdirectories/series
        if (query.startsWith(DIR_PREFIX)) {
            if (page > 1) return MangasPage(emptyList(), false)
            val dirPath = query.removePrefix(DIR_PREFIX)
            val allSeries = getCachedSeries()
            val items = buildDirectoryItems(allSeries, dirPath)
            return MangasPage(items, false)
        }

        // Root listing → show libraries
        if (query.isBlank()) {
            if (page > 1) return MangasPage(emptyList(), false)
            val libraries = getCachedLibraries()
            val items = libraries.map { createLibrarySManga(it) }
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
            val streamUrl = "${preferences.baseUrl}/api/v1/books/$bookId/stream"
            return listOf(Page(1, imageUrl = streamUrl))
        }

        val pages = api.getPages(bookId)
        return pages.map { p ->
            val url = "${preferences.baseUrl}/api/v1/books/$bookId/pages/${p.number}" +
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
        const val MEMO_KIND = "mihon.kind"
        const val KIND_DIRECTORY = "directory"
        const val KIND_LIBRARY = "library"
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
