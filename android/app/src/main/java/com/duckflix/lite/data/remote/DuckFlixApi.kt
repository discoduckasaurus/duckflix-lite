package com.duckflix.lite.data.remote

import com.duckflix.lite.data.remote.dto.LoginRequest
import com.duckflix.lite.data.remote.dto.LoginResponse
import com.duckflix.lite.data.remote.dto.StreamUrlRequest
import com.duckflix.lite.data.remote.dto.StreamUrlResponse
import com.duckflix.lite.data.remote.dto.StreamUrlStartResponse
import com.duckflix.lite.data.remote.dto.StreamProgressResponse
import com.duckflix.lite.data.remote.dto.TmdbDetailResponse
import com.duckflix.lite.data.remote.dto.TmdbSearchResponse
import com.duckflix.lite.data.remote.dto.TmdbSeasonResponse
import com.duckflix.lite.data.remote.dto.UserResponse
import com.duckflix.lite.data.remote.dto.VodSessionCheckResponse
import com.duckflix.lite.data.remote.dto.VodHeartbeatResponse
import com.duckflix.lite.data.remote.dto.ZurgSearchResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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

    // TODO: Add remaining API endpoints (Prowlarr search UI, etc.)
}
