package com.example.servex.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Server(
    val id: String,
    val name: String,
    val baseUrl: String,
    val certFingerprint: String? = null,
    val pollIntervalS: Int = 5,
    val enabled: Boolean = true,
)
