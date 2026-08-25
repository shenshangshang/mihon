package tachiyomi.source.komga.api

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.source.komga.KomgaPreferences
import tachiyomi.source.komga.dto.AuthorDto
import tachiyomi.source.komga.dto.BookDto
import tachiyomi.source.komga.dto.CollectionDto
import tachiyomi.source.komga.dto.LibraryDto
import tachiyomi.source.komga.dto.PageDto
import tachiyomi.source.komga.dto.PageWrapperDto
import tachiyomi.source.komga.dto.SeriesDto

class KomgaApi(
    private val client: OkHttpClient,
    private val preferences: KomgaPreferences,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val baseUrl: String get() = preferences.baseUrl

    private fun requestBuilder(path: String): Request.Builder {
        val url = "$baseUrl$path".toHttpUrl()
        val builder = Request.Builder().url(url)
        if (preferences.apiKey.isNotBlank()) {
            builder.addHeader("X-API-Key", preferences.apiKey)
        } else if (preferences.username.isNotBlank()) {
            builder.addHeader("Authorization", Credentials.basic(preferences.username, preferences.password))
        }
        return builder
    }

    private fun execute(path: String): String {
        val response = client.newCall(requestBuilder(path).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw RuntimeException("Komga API error: ${response.code} for $path")
        }
        return response.body?.string() ?: ""
    }

    fun getLibraries(): List<LibraryDto> =
        try {
            json.decodeFromString(execute("/api/v1/libraries"))
        } catch (e: Exception) {
            emptyList()
        }

    fun getSeries(
        page: Int,
        size: Int = 25,
        search: String? = null,
        sort: String? = null,
        libraryId: String? = null,
    ): Pair<List<SManga>, Boolean> {
        val url = "/api/v1/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("size", size.toString())
            .addQueryParameter("deleted", "false")

        search?.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("search", it) }
        sort?.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("sort", it) }
        libraryId?.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("library_id", it) }

        val builtUrl = url.build()
        val data = json.decodeFromString<PageWrapperDto<SeriesDto>>(execute(builtUrl.encodedPath + "?" + builtUrl.encodedQuery))
        val manga = data.content.map { it.toSManga(baseUrl) }
        return manga to !data.last
    }

    fun getAllSeries(libraryId: String? = null): List<SeriesDto> {
        val all = mutableListOf<SeriesDto>()
        var page = 0
        var hasNext = true
        while (hasNext) {
            val urlBuilder = "/api/v1/series".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("size", "100")
                .addQueryParameter("deleted", "false")
            libraryId?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("library_id", it) }
            val builtUrl = urlBuilder.build()
            val data = try {
                json.decodeFromString<PageWrapperDto<SeriesDto>>(execute(builtUrl.encodedPath + "?" + builtUrl.encodedQuery))
            } catch (e: Exception) {
                return all
            }
            all.addAll(data.content)
            hasNext = !data.last
            page++
        }
        return all
    }

    fun getSeriesById(seriesId: String): SeriesDto? =
        try {
            json.decodeFromString<SeriesDto>(execute("/api/v1/series/$seriesId"))
        } catch (e: Exception) {
            null
        }

    fun getBooksBySeries(
        seriesId: String,
        page: Int = 0,
        size: Int = 25,
    ): Pair<List<BookDto>, Boolean> {
        val path = "/api/v1/series/$seriesId/books?page=$page&size=$size&media_status=READY&deleted=false"
        val data = json.decodeFromString<PageWrapperDto<BookDto>>(execute(path))
        return data.content to !data.last
    }

    fun getAllBooksBySeries(seriesId: String): List<BookDto> {
        val all = mutableListOf<BookDto>()
        var page = 0
        var hasNext = true
        while (hasNext) {
            val (books, next) = getBooksBySeries(seriesId, page, 50)
            all.addAll(books)
            hasNext = next
            page++
        }
        return all
    }

    fun getBookById(bookId: String): BookDto? =
        try {
            json.decodeFromString<BookDto>(execute("/api/v1/books/$bookId"))
        } catch (e: Exception) {
            null
        }

    fun getPages(bookId: String): List<PageDto> =
        try {
            json.decodeFromString<List<PageDto>>(execute("/api/v1/books/$bookId/pages"))
        } catch (e: Exception) {
            emptyList()
        }

    fun getCollections(): List<CollectionDto> =
        try {
            json.decodeFromString<PageWrapperDto<CollectionDto>>(execute("/api/v1/collections?unpaged=true")).content
        } catch (e: Exception) {
            emptyList()
        }

    fun testConnection(): Boolean =
        try {
            execute("/api/v1/libraries")
            true
        } catch (e: Exception) {
            false
        }

    companion object {
        const val SUPPORTED_IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/jxl", "image/heif", "image/avif")
    }
}

fun SeriesDto.toSManga(baseUrl: String): SManga = SManga.create().apply {
    title = metadata.title.ifBlank { name }
    url = "$baseUrl/api/v1/series/$id"
    thumbnail_url = "$baseUrl/api/v1/series/$id/thumbnail"
    status = when {
        metadata.status == "ENDED" && metadata.totalBookCount != null && booksCount < metadata.totalBookCount -> SManga.PUBLISHING_FINISHED
        metadata.status == "ENDED" -> SManga.COMPLETED
        metadata.status == "ONGOING" -> SManga.ONGOING
        metadata.status == "ABANDONED" -> SManga.CANCELLED
        metadata.status == "HIATUS" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }
    genre = (metadata.genres + metadata.tags + booksMetadata.tags).sorted().distinct().joinToString(", ")
    description = metadata.summary.ifBlank { booksMetadata.summary }
    booksMetadata.authors.groupBy({ it.role }, { it.name }).let { map ->
        author = map["writer"]?.distinct()?.joinToString()
        artist = map["penciller"]?.distinct()?.joinToString()
    }
    initialized = true
}

fun BookDto.toSChapter(baseUrl: String, chapterNameTemplate: String = "{number} - {title} ({size})"): SChapter =
    SChapter.create().apply {
        chapter_number = metadata.numberSort
        url = "$baseUrl/api/v1/books/$id"
        name = buildString {
            val num = metadata.number.ifBlank { number.toInt().toString() }
            append(num).append(" - ").append(metadata.title.ifBlank { name })
            if (size.isNotBlank()) append(" ($size)")
        }
        scanlator = metadata.authors.filter { it.role == "translator" }.joinToString { it.name }
        date_upload = when {
            metadata.releaseDate != null -> parseDate(metadata.releaseDate)
            created != null -> parseDateTime(created)
            else -> 0L
        }
    }

private fun parseDate(dateStr: String): Long {
    return try {
        java.time.LocalDate.parse(dateStr).atStartOfDay(java.time.ZoneOffset.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        0L
    }
}

private fun parseDateTime(dateTimeStr: String): Long {
    return try {
        java.time.OffsetDateTime.parse(dateTimeStr).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try {
            java.time.LocalDateTime.parse(dateTimeStr).atZone(java.time.ZoneOffset.systemDefault()).toInstant().toEpochMilli()
        } catch (e2: Exception) {
            0L
        }
    }
}
