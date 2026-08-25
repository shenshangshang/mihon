package tachiyomi.source.komga.dto

import kotlinx.serialization.Serializable

@Serializable
class PageWrapperDto<T>(
    val content: List<T> = emptyList(),
    val last: Boolean = true,
)

@Serializable
class LibraryDto(
    val id: String,
    val name: String,
)

@Serializable
class SeriesDto(
    val id: String,
    val libraryId: String,
    val name: String,
    val url: String? = null,
    val created: String? = null,
    val lastModified: String? = null,
    val fileLastModified: String? = null,
    val booksCount: Int = 0,
    val metadata: SeriesMetadataDto = SeriesMetadataDto(),
    val booksMetadata: BookMetadataAggregationDto = BookMetadataAggregationDto(),
)

@Serializable
class SeriesMetadataDto(
    val status: String = "ONGOING",
    val title: String = "",
    val titleSort: String = "",
    val summary: String = "",
    val publisher: String = "",
    val language: String = "",
    val genres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val totalBookCount: Int? = null,
)

@Serializable
class BookMetadataAggregationDto(
    val authors: List<AuthorDto> = emptyList(),
    val tags: Set<String> = emptySet(),
    val releaseDate: String? = null,
    val summary: String = "",
)

@Serializable
class BookDto(
    val id: String,
    val seriesId: String,
    val seriesTitle: String = "",
    val name: String,
    val number: Float = 0f,
    val created: String? = null,
    val lastModified: String? = null,
    val fileLastModified: String? = null,
    val sizeBytes: Long = 0,
    val size: String = "",
    val directoryPath: String? = null,
    val media: MediaDto = MediaDto(),
    val metadata: BookMetadataDto = BookMetadataDto(),
)

@Serializable
class MediaDto(
    val status: String = "UNKNOWN",
    val mediaType: String = "",
    val pagesCount: Int = 0,
    val mediaProfile: String = "DIVINA",
    val epubDivinaCompatible: Boolean = false,
)

@Serializable
class BookMetadataDto(
    val title: String = "",
    val number: String = "",
    val numberSort: Float = 0f,
    val releaseDate: String? = null,
    val authors: List<AuthorDto> = emptyList(),
    val tags: Set<String> = emptySet(),
    val summary: String = "",
)

@Serializable
class AuthorDto(
    val name: String,
    val role: String,
)

@Serializable
class PageDto(
    val number: Int,
    val fileName: String = "",
    val mediaType: String = "",
)

@Serializable
class CollectionDto(
    val id: String,
    val name: String,
)
