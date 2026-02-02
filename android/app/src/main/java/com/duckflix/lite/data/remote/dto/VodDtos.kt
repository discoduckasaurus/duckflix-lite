package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VodSessionCheckResponse(
    val success: Boolean,
    val message: String?
)

@JsonClass(generateAdapter = true)
data class VodHeartbeatResponse(
    val success: Boolean
)
