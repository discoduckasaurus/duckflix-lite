package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ZurgSearchResponse(
    val match: ZurgMatch?,
    val fallback: ZurgMatch?,
    val error: String?
)

@JsonClass(generateAdapter = true)
data class ZurgMatch(
    @Json(name = "filePath") val filePath: String,
    @Json(name = "fileName") val fileName: String,
    val size: Long?,
    @Json(name = "mbPerMinute") val mbPerMinute: Double?
)
