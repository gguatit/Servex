package com.example.servex.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.servex.data.ServerRepository
import com.example.servex.domain.model.Server
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Accepts a bare host, host:port, or a full http(s) URL.
 * A missing scheme becomes http:// and a missing port becomes :8765.
 * Returns null when the address cannot be used.
 */
fun normalizeBaseUrl(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.contains("://") -> return null
        else -> "http://$trimmed"
    }
    val authority = withScheme.substringAfter("://").substringBefore('/')
    return when {
        authority.contains(':') -> withScheme
        withScheme.startsWith("http://") -> "$withScheme:8765"
        else -> withScheme
    }
}

fun validateServer(name: String, baseUrl: String): String? = when {
    name.isBlank() -> "err_name"
    normalizeBaseUrl(baseUrl) == null -> "err_url"
    else -> null
}

data class EditUiState(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val token: String = "",
    val certFingerprint: String = "",
    val pollIntervalS: Int = 5,
    val errorKey: String? = null,
    val testResult: String? = null,
    val saving: Boolean = false,
)

class EditServerViewModel(
    private val repository: ServerRepository,
    private val serverId: String?,
) : ViewModel() {
    private val state = MutableStateFlow(EditUiState())
    val uiState: StateFlow<EditUiState> = state.asStateFlow()

    init {
        if (serverId != null) viewModelScope.launch {
            repository.servers.first().firstOrNull { it.id == serverId }?.let { s ->
                state.value = EditUiState(
                    id = s.id, name = s.name, baseUrl = s.baseUrl,
                    certFingerprint = s.certFingerprint.orEmpty(), pollIntervalS = s.pollIntervalS,
                )
            }
        }
    }

    fun onName(v: String) = state.update { it.copy(name = v) }
    fun onUrl(v: String) = state.update { it.copy(baseUrl = v) }
    fun onToken(v: String) = state.update { it.copy(token = v) }
    fun onFingerprint(v: String) = state.update { it.copy(certFingerprint = v) }
    fun onPoll(v: Int) = state.update { it.copy(pollIntervalS = v) }

    fun testConnection() {
        val s = state.value
        viewModelScope.launch {
            repository.testConnection(s.toServer(), s.token).fold(
                onSuccess = { state.update { it.copy(testResult = "ok") } },
                onFailure = { e -> state.update { it.copy(testResult = e.message ?: "fail") } },
            )
        }
    }

    fun save(onDone: () -> Unit) {
        val s = state.value
        val err = validateServer(s.name, s.baseUrl)
        if (err != null) {
            state.update { it.copy(errorKey = err) }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(saving = true) }
            repository.save(s.toServer(), s.token.ifBlank { null })
            state.update { it.copy(saving = false) }
            onDone()
        }
    }

    private fun EditUiState.toServer() = Server(
        id = id, name = name.trim(),
        baseUrl = normalizeBaseUrl(baseUrl) ?: baseUrl.trim(),
        certFingerprint = certFingerprint.trim().ifBlank { null }, pollIntervalS = pollIntervalS,
    )
}
