package com.duckflix.lite.data.remote

import com.duckflix.lite.data.remote.dto.BandwidthReportRequest
import com.duckflix.lite.data.remote.dto.BandwidthStatusResponse
import com.duckflix.lite.data.remote.dto.CollectionResponse
import com.duckflix.lite.data.remote.dto.GenresResponse
import com.duckflix.lite.data.remote.dto.ProvidersResponse
import com.duckflix.lite.data.remote.dto.FallbackRequest
import com.duckflix.lite.data.remote.dto.LiveTvChannelsResponse
import com.duckflix.lite.data.remote.dto.LoginRequest
import com.duckflix.lite.data.remote.dto.LoginResponse
import com.duckflix.lite.data.remote.dto.PlaybackSettingsResponse
import com.duckflix.lite.data.remote.dto.RecommendationsResponse
import com.duckflix.lite.data.remote.dto.StreamUrlRequest
import com.duckflix.lite.data.remote.dto.StreamUrlResponse
import com.duckflix.lite.data.remote.dto.StreamUrlStartResponse
import com.duckflix.lite.data.remote.dto.StreamProgressResponse
import com.duckflix.lite.data.remote.dto.TmdbDetailResponse
import com.duckflix.lite.data.remote.dto.TmdbSearchResponse
import com.duckflix.lite.data.remote.dto.TmdbSeasonResponse
import com.duckflix.lite.data.remote.dto.TrendingResponse
import com.duckflix.lite.data.remote.dto.UserResponse
import com.duckflix.lite.data.remote.dto.VodSessionCheckResponse
import com.duckflix.lite.data.remote.dto.VodHeartbeatResponse
import com.duckflix.lite.data.remote.dto.ZurgSearchResponse
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface DuckFlixApi {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @GET("auth/me")
    suspend fun getCurrentUser(@Header("Authorization") token: String): UserResponse

    @POST("auth/logout")
    suspend fun logout(@Header("Authorization") token: String)

    // TMDB Search
    @GET("search/tmdb")
    suspend fun searchTmdb(
        @Query("query") query: String,
        @Query("type") type: String = "movie"
    ): TmdbSearchResponse

    // TMDB Details
    @GET("search/tmdb/{id}")
    suspend fun getTmdbDetail(
        @Path("id") id: Int,
        @Query("type") type: String = "movie"
    ): TmdbDetailResponse

    // TMDB Season Details
    @GET("search/tmdb/season/{showId}/{seasonNumber}")
    suspend fun getTmdbSeason(
        @Path("showId") showId: Int,
        @Path("seasonNumber") seasonNumber: Int
    ): com.duckflix.lite.data.remote.dto.TmdbSeasonResponse

    // Zurg Search
    @GET("search/zurg")
    suspend fun searchZurg(
        @Query("title") title: String,
        @Query("year") year: String?,
        @Query("type") type: String,
        @Query("season") season: Int? = null,
        @Query("episode") episode: Int? = null,
        @Query("duration") duration: Int? = null
    ): ZurgSearchResponse

    // VOD Session Management
    @POST("vod/session/check")
    suspend fun checkVodSession(): VodSessionCheckResponse

    @POST("vod/session/heartbeat")
    suspend fun sendVodHeartbeat(): VodHeartbeatResponse

    @POST("vod/session/end")
    suspend fun endVodSession(): VodHeartbeatResponse

    // VOD Streaming
    @POST("vod/stream-url")
    suspend fun getStreamUrl(@Body request: StreamUrlRequest): StreamUrlResponse

    @POST("vod/stream-url/start")
    suspend fun startStreamUrl(@Body request: StreamUrlRequest): StreamUrlStartResponse

    @GET("vod/stream-url/progress/{jobId}")
    suspend fun getStreamProgress(@Path("jobId") jobId: String): StreamProgressResponse

    @DELETE("vod/stream-url/cancel/{jobId}")
    suspend fun cancelStream(@Path("jobId") jobId: String)

    // Adaptive Bitrate - Bandwidth Testing
    @Streaming
    @GET("bandwidth/test")
    suspend fun downloadBandwidthTest(): ResponseBody

    @POST("bandwidth/report")
    suspend fun reportBandwidth(@Body request: BandwidthReportRequest): BandwidthStatusResponse

    @GET("bandwidth/status")
    suspend fun getBandwidthStatus(): BandwidthStatusResponse

    @GET("settings/playback")
    suspend fun getPlaybackSettings(): PlaybackSettingsResponse

    @POST("vod/fallback")
    suspend fun requestFallback(@Body request: FallbackRequest): StreamUrlResponse

    // Recommendations - personalized content based on watch history
    @GET("user/recommendations")
    suspend fun getRecommendations(
        @Query("limit") limit: Int = 20
    ): RecommendationsResponse

    // Trending content from TMDB (requires authentication)
    @GET("user/trending")
    suspend fun getTrending(
        @Query("mediaType") mediaType: String = "movie", // "movie" or "tv"
        @Query("timeWindow") timeWindow: String = "week" // "day" or "week"
    ): TrendingResponse

    // Watch progress sync
    @POST("user/watch-progress")
    suspend fun syncWatchProgress(@Body request: com.duckflix.lite.data.remote.dto.WatchProgressSyncRequest)

    @DELETE("user/watch-progress/{tmdbId}/{type}")
    suspend fun deleteWatchProgress(
        @Path("tmdbId") tmdbId: Int,
        @Path("type") type: String
    )

    // Continue Watching (includes download states)
    @GET("user/watch-progress")
    suspend fun getContinueWatching(): com.duckflix.lite.data.remote.dto.ContinueWatchingResponse

    // Dismiss failed download
    @DELETE("user/failed-download/{jobId}")
    suspend fun dismissFailedDownload(@Path("jobId") jobId: String)

    // Loading phrases
    @GET("user/loading-phrases")
    suspend fun getLoadingPhrases(): com.duckflix.lite.data.remote.dto.LoadingPhrasesResponse

    // Watchlist sync
    @POST("user/watchlist")
    suspend fun addToWatchlist(@Body request: com.duckflix.lite.data.remote.dto.WatchlistSyncRequest)

    @DELETE("user/watchlist/{tmdbId}/{type}")
    suspend fun removeFromWatchlist(
        @Path("tmdbId") tmdbId: Int,
        @Path("type") type: String
    )

    // Random Episode
    @GET("content/random/episode/{tmdbId}")
    suspend fun getRandomEpisode(@Path("tmdbId") tmdbId: Int): com.duckflix.lite.data.remote.dto.RandomEpisodeResponse

    // Auto-play endpoints
    @GET("vod/next-episode/{tmdbId}/{season}/{episode}")
    suspend fun getNextEpisode(
        @Path("tmdbId") tmdbId: Int,
        @Path("season") season: Int,
        @Path("episode") episode: Int
    ): com.duckflix.lite.data.remote.dto.NextEpisodeResponse

    @GET("content/recommendations/{tmdbId}")
    suspend fun getContentRecommendations(
        @Path("tmdbId") tmdbId: Int,
        @Query("limit") limit: Int = 4
    ): com.duckflix.lite.data.remote.dto.MovieRecommendationsResponse

    // Admin endpoints
    @GET("admin/dashboard")
    suspend fun getAdminDashboard(): com.duckflix.lite.ui.screens.admin.AdminDashboard

    @GET("admin/users")
    suspend fun getAdminUsers(): List<com.duckflix.lite.ui.screens.admin.AdminUserInfo>

    @POST("admin/users/{id}/reset-password")
    suspend fun resetUserPassword(@Path("id") userId: Int)

    @POST("admin/users/{id}/disable")
    suspend fun disableUser(@Path("id") userId: Int)

    @GET("admin/failures")
    suspend fun getAdminFailures(@Query("limit") limit: Int = 50): List<com.duckflix.lite.ui.screens.admin.AdminFailureInfo>

    // Person/Actor endpoints
    @GET("search/person/{personId}")
    suspend fun getPersonDetails(@Path("personId") personId: Int): com.duckflix.lite.data.remote.dto.PersonDetailsResponse

    @GET("search/person/{personId}/credits")
    suspend fun getPersonCredits(@Path("personId") personId: Int): com.duckflix.lite.data.remote.dto.PersonCreditsResponse

    // Report bad stream
    @POST("vod/report-bad")
    suspend fun reportBadStream(@Body request: com.duckflix.lite.data.remote.dto.ReportBadRequest): com.duckflix.lite.data.remote.dto.ReportBadResponse

    // Subtitle search
    @POST("vod/subtitles/search")
    suspend fun searchSubtitles(@Body request: com.duckflix.lite.data.remote.dto.SubtitleSearchRequest): com.duckflix.lite.data.remote.dto.SubtitleSearchResponse

    // Live TV
    @GET("livetv/channels")
    suspend fun getLiveTvChannels(): LiveTvChannelsResponse

    // ===============================================
    // COLLECTION ENDPOINTS (VOD Discover Redesign)
    // ===============================================

    // Popular content
    @GET("search/collections/popular")
    suspend fun getPopular(
        @Query("type") type: String, // "movie" or "tv"
        @Query("page") page: Int = 1
    ): CollectionResponse

    // Top Rated content
    @GET("search/collections/top-rated")
    suspend fun getTopRated(
        @Query("type") type: String, // "movie" or "tv"
        @Query("page") page: Int = 1
    ): CollectionResponse

    // Now Playing (movies in theaters)
    @GET("search/collections/now-playing")
    suspend fun getNowPlaying(
        @Query("page") page: Int = 1
    ): CollectionResponse

    // Discover with filters
    @GET("search/collections/discover")
    suspend fun discover(
        @Query("type") type: String? = null, // "movie" or "tv"
        @Query("genre") genre: Int? = null,
        @Query("year") year: Int? = null,
        @Query("minRating") minRating: Float? = null,
        @Query("maxRating") maxRating: Float? = null,
        @Query("minRuntime") minRuntime: Int? = null,
        @Query("maxRuntime") maxRuntime: Int? = null,
        @Query("sortBy") sortBy: String? = null,
        @Query("watchProvider") watchProvider: Int? = null,
        @Query("page") page: Int = 1
    ): CollectionResponse

    // Genres list
    @GET("search/collections/genres")
    suspend fun getGenres(
        @Query("type") type: String // "movie" or "tv"
    ): GenresResponse

    // Streaming providers list
    @GET("search/collections/providers")
    suspend fun getWatchProviders(
        @Query("type") type: String = "movie",
        @Query("region") region: String = "US"
    ): ProvidersResponse

    // TODO: Add remaining API endpoints (Prowlarr search UI, etc.)
}
