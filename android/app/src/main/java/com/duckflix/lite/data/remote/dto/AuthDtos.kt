package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val username: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    val token: String,
    val user: UserDto
)

@JsonClass(generateAdapter = true)
data class UserDto(
    val id: Int,
    val username: String,
    @Json(name = "isAdmin") val isAdmin: Boolean,
    @Json(name = "rdExpiryDate") val rdExpiryDate: String?
)

@JsonClass(generateAdapter = true)
data class UserResponse(
    val user: UserDto
)

@JsonClass(generateAdapter = true)
data class LoadingPhrasesResponse(
    val phrasesA: List<String>,
    val phrasesB: List<String>
)
