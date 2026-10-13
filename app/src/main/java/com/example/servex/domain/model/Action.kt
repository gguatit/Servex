package com.example.servex.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ActionRequest(val action: String, val args: Map<String, String> = emptyMap())

@Serializable
data class ActionResult(val ok: Boolean = false, val output: String? = null)
