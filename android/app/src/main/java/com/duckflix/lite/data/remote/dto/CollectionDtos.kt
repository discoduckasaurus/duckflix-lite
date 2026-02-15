package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.JsonClass

/**
 * DTOs for the VOD collection endpoints (trending, popular, top-rated, by-genre, etc.)
 */

/**
 * Item from collection endpoints. Server returns camelCase fields.
 */
@JsonClass(generateAdapter = true)
data class CollectionItem(
    val id: Int,
    val title: String,
    val posterPath: String?, // Server uses camelCase
    val overview: String?,
    val voteAverage: Double?, // Server uses camelCase
    val releaseDate: String? = null, // Optional - some endpoints use 'year' instead
    val year: String? = null, // Some endpoints return year directly
    val mediaType: String = "movie"
) {
    val posterUrl: String?
        get() = posterPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }

    // Get year from either the year field or releaseDate
    val displayYear: String?
        get() = year ?: releaseDate?.take(4)
}

/**
 * Response from collection endpoints (popular, top-rated, now-playing, discover).
 * Server returns both camelCase and snake_case pagination fields plus hasMore boolean.
 */
@JsonClass(generateAdapter = true)
data class CollectionResponse(
    val results: List<CollectionItem>,
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
    val hasMore: Boolean = false
)

@JsonClass(generateAdapter = true)
data class GenresResponse(
    val genres: List<GenreDto>
)

/**
 * Watch provider from the /collections/providers endpoint.
 * Server returns: { id, name, logo (full URL or null), priority }
 */
@JsonClass(generateAdapter = true)
data class WatchProvider(
    val id: Int,
    val name: String,
    val logo: String?, // Server returns full URL directly
    val priority: Int?
) {
    // Compatibility properties for existing code
    val providerId: Int get() = id
    val providerName: String get() = name
    val logoUrl: String? get() = logo
    val displayPriority: Int? get() = priority
}

/**
 * Response from /collections/providers endpoint.
 * Server returns: { providers: [...], region: "US" }
 */
@JsonClass(generateAdapter = true)
data class ProvidersResponse(
    val providers: List<WatchProvider>,
    val region: String? = null
)
