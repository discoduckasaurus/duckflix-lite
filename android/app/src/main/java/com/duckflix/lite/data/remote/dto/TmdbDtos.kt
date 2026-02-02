package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TmdbSearchResponse(
    val results: List<TmdbSearchResult>
)

@JsonClass(generateAdapter = true)
data class TmdbSearchResult(
    val id: Int,
    val title: String,
    val year: String?,
    @Json(name = "posterPath") val posterPath: String?,
    val overview: String?,
    @Json(name = "voteAverage") val voteAverage: Double?,
    val type: String = "movie" // "movie" or "tv"
) {
    val posterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
}

@JsonClass(generateAdapter = true)
data class TmdbDetailResponse(
    val id: Int,
    val title: String,
    val overview: String?,
    @Json(name = "posterPath") val posterPath: String?,
    @Json(name = "backdropPath") val backdropPath: String?,
    @Json(name = "releaseDate") val releaseDate: String?,
    @Json(name = "voteAverage") val voteAverage: Double?,
    val runtime: Int?,
    val genres: List<GenreDto>?,
    val cast: List<CastDto>?,
    val seasons: List<SeasonInfoDto>?,
    @Json(name = "numberOfSeasons") val numberOfSeasons: Int?
) {
    val posterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

    val backdropUrl: String?
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }

    val year: String?
        get() = releaseDate?.substring(0, 4)

    val genreText: String
        get() = genres?.joinToString(", ") { it.name } ?: ""
}

@JsonClass(generateAdapter = true)
data class GenreDto(
    val id: Int,
    val name: String
)

@JsonClass(generateAdapter = true)
data class CastDto(
    val name: String,
    val character: String?,
    @Json(name = "profilePath") val profilePath: String?
) {
    val profileUrl: String?
        get() = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
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
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
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
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
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
        get() = stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
}
