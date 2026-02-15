package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TmdbSearchResponse(
    val results: List<TmdbSearchResult>,
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
    val hasMore: Boolean = false
)

@JsonClass(generateAdapter = true)
data class TmdbSearchResult(
    val id: Int,
    val title: String,
    val year: String?,
    @Json(name = "posterPath") val posterPath: String?,
    val overview: String?,
    @Json(name = "voteAverage") val voteAverage: Double?,
    @Json(name = "relevanceScore") val relevanceScore: Double? = null,
    @Json(name = "mediaType") val type: String = "movie" // Map server's mediaType to type field
) {
    val posterUrl: String?
        get() = posterPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }
}

@JsonClass(generateAdapter = true)
data class TmdbDetailResponse(
    val id: Int,
    val title: String,
    val overview: String?,
    @Json(name = "posterPath") val posterPath: String?,
    @Json(name = "backdropPath") val backdropPath: String?,
    @Json(name = "logoPath") val logoPath: String?, // English logo (transparent PNG)
    @Json(name = "releaseDate") val releaseDate: String?,
    @Json(name = "voteAverage") val voteAverage: Double?,
    val runtime: Int?,
    val genres: List<GenreDto>?,
    val cast: List<CastDto>?,
    val seasons: List<SeasonInfoDto>?,
    @Json(name = "numberOfSeasons") val numberOfSeasons: Int?,
    @Json(name = "originalLanguage") val originalLanguage: String?, // ISO 639-1 code: "en", "ja", "fr", etc.
    @Json(name = "spokenLanguages") val spokenLanguages: List<SpokenLanguageDto>?
) {
    val posterUrl: String?
        get() = posterPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }

    val backdropUrl: String?
        get() = backdropPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w1280$it"  // TMDB path, prepend base URL
            }
        }

    val logoUrl: String?
        get() = logoPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }

    // Prefer logo over poster for title display (logos are transparent PNGs)
    val titleImageUrl: String?
        get() = logoUrl ?: posterUrl

    val year: String?
        get() = releaseDate?.substring(0, 4)

    val genreText: String
        get() = genres?.joinToString(", ") { it.name } ?: ""
}

@JsonClass(generateAdapter = true)
data class SpokenLanguageDto(
    @Json(name = "iso6391") val iso6391: String, // "en"
    val name: String // "English"
)

@JsonClass(generateAdapter = true)
data class GenreDto(
    val id: Int,
    val name: String
)

@JsonClass(generateAdapter = true)
data class CastDto(
    val id: Int,
    val name: String,
    val character: String?,
    @Json(name = "profilePath") val profilePath: String?
) {
    val profileUrl: String?
        get() = profilePath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w185$it"  // TMDB path, prepend base URL
            }
        }
}

@JsonClass(generateAdapter = true)
data class SeasonInfoDto(
    val id: Int,
    val name: String,
    @Json(name = "seasonNumber") val seasonNumber: Int,
    @Json(name = "posterPath") val posterPath: String?,
    @Json(name = "episodeCount") val episodeCount: Int?,
    val overview: String?
) {
    val posterUrl: String?
        get() = posterPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }
}

@JsonClass(generateAdapter = true)
data class TmdbSeasonResponse(
    val id: Int,
    val name: String,
    @Json(name = "season_number") val seasonNumber: Int,
    @Json(name = "poster_path") val posterPath: String?,
    val overview: String?,
    val episodes: List<EpisodeDto>?
) {
    val posterUrl: String?
        get() = posterPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }
}

@JsonClass(generateAdapter = true)
data class EpisodeDto(
    val id: Int,
    val name: String,
    @Json(name = "episode_number") val episodeNumber: Int,
    @Json(name = "season_number") val seasonNumber: Int,
    @Json(name = "still_path") val stillPath: String?,
    val overview: String?,
    @Json(name = "air_date") val airDate: String?,
    val runtime: Int?
) {
    val stillUrl: String?
        get() = stillPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }
}

// Trending API DTOs
@JsonClass(generateAdapter = true)
data class TrendingResult(
    val id: Int,
    val title: String,
    @Json(name = "poster_path") val posterPath: String?,
    val overview: String?,
    @Json(name = "vote_average") val voteAverage: Double?,
    val year: String?,
    @Json(name = "media_type") val mediaType: String = "movie"
) {
    val posterUrl: String?
        get() = posterPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }
}

@JsonClass(generateAdapter = true)
data class TrendingResponse(
    val results: List<TrendingResult>,
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
    val hasMore: Boolean = false
)

// Recommendations API DTOs
@JsonClass(generateAdapter = true)
data class RecommendationItem(
    val tmdbId: Int,
    val title: String,
    val type: String, // "movie" or "tv"
    val posterPath: String?,
    val releaseDate: String?,
    val voteAverage: Double?
) {
    val posterUrl: String?
        get() = posterPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }

    val year: String?
        get() = releaseDate?.take(4)

    // Map to id and mediaType for compatibility with existing UI code
    val id: Int
        get() = tmdbId

    val mediaType: String
        get() = type
}

@JsonClass(generateAdapter = true)
data class RecommendationsResponse(
    val recommendations: List<RecommendationItem>,
    val total: Int,
    val page: Int,
    val totalPages: Int,
    val hasMore: Boolean
)

@JsonClass(generateAdapter = true)
data class WatchProgressSyncRequest(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val posterPath: String?,
    val logoPath: String? = null,  // Title logo for loading screen
    val releaseDate: String?,
    val position: Long,
    val duration: Long,
    val season: Int?,
    val episode: Int?
)

@JsonClass(generateAdapter = true)
data class EpisodeProgressItem(
    val season: Int,
    val episode: Int,
    val position: Long,
    val duration: Long,
    val completed: Boolean = false,
    val updatedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class EpisodeProgressResponse(
    val episodes: List<EpisodeProgressItem>
)

@JsonClass(generateAdapter = true)
data class WatchlistSyncRequest(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val posterPath: String?,
    val logoPath: String? = null,  // Title logo for loading screen
    val releaseDate: String?,
    val voteAverage: Double?
)

@JsonClass(generateAdapter = true)
data class WatchlistResponse(
    val watchlist: List<WatchlistItem>
)

@JsonClass(generateAdapter = true)
data class WatchlistItem(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val posterPath: String?,
    val releaseDate: String?,
    val voteAverage: Double?,
    val addedAt: String?  // ISO date string from server
)

@JsonClass(generateAdapter = true)
data class RandomEpisodeResponse(
    val season: Int,
    val episode: Int,
    val title: String
)

@JsonClass(generateAdapter = true)
data class RTScoresResponse(
    val tmdbId: Int,
    val criticsScore: Int?,
    val audienceScore: Int?,
    val rtUrl: String?,
    val available: Boolean,
    val cached: Boolean
)

// Person/Actor DTOs
@JsonClass(generateAdapter = true)
data class PersonDetailsResponse(
    val id: Int,
    val name: String,
    val biography: String?,
    val birthday: String?,
    @Json(name = "placeOfBirth") val placeOfBirth: String?,
    @Json(name = "profilePath") val profilePath: String?,
    @Json(name = "knownForDepartment") val knownForDepartment: String?
) {
    val profileUrl: String?
        get() = profilePath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }
}

@JsonClass(generateAdapter = true)
data class PersonCreditsResponse(
    val personId: Int,
    val personName: String,
    val results: List<PersonCreditItem>
)

@JsonClass(generateAdapter = true)
data class PersonCreditItem(
    val id: Int,
    val title: String,
    val year: Int?,
    @Json(name = "posterPath") val posterPath: String?,
    val overview: String?,
    @Json(name = "voteAverage") val voteAverage: Double?,
    @Json(name = "voteCount") val voteCount: Int?,
    @Json(name = "mediaType") val mediaType: String, // "movie" or "tv"
    val character: String?,
    @Json(name = "releaseDate") val releaseDate: String?,
    @Json(name = "combinedScore") val combinedScore: Double?,
    @Json(name = "recencyScore") val recencyScore: Double?,
    @Json(name = "ratingScore") val ratingScore: Double?
) {
    val posterUrl: String?
        get() = posterPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }
}

// Prefetch DTOs for seamless auto-play
@JsonClass(generateAdapter = true)
data class PrefetchNextRequest(
    val tmdbId: Int,
    val title: String,
    val year: String?,
    val type: String,
    val currentSeason: Int,
    val currentEpisode: Int,
    val mode: String = "sequential"  // "sequential" or "random"
)

@JsonClass(generateAdapter = true)
data class PrefetchNextResponse(
    val hasNext: Boolean,
    val jobId: String?,
    val nextEpisode: PrefetchEpisodeInfo?
)

@JsonClass(generateAdapter = true)
data class PrefetchEpisodeInfo(
    val season: Int,
    val episode: Int,
    val title: String?
)

@JsonClass(generateAdapter = true)
data class PrefetchPromoteResponse(
    val success: Boolean = false,  // Default to false for error responses
    val status: String = "failed",  // "searching", "downloading", "completed", "failed"
    val streamUrl: String? = null,
    val progress: Int? = null,
    val message: String? = null,
    val error: String? = null,  // Server error message
    // New fields for autoplay chain continuation
    val hasNext: Boolean = false,  // Whether there's a next episode after the promoted one
    val nextEpisode: PrefetchEpisodeInfo? = null,  // Info for the next episode (for autoplay UI)
    val contentInfo: PromotedContentInfo? = null,  // Info about the promoted episode (for next prefetch)
    val skipMarkers: com.duckflix.lite.data.remote.dto.SkipMarkers? = null  // Intro/recap/credits skip timestamps
)

@JsonClass(generateAdapter = true)
data class PromotedContentInfo(
    val tmdbId: Int,
    val title: String,
    val year: String?,
    val type: String,
    val season: Int,
    val episode: Int
)
