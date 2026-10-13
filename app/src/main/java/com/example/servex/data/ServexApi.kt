package com.example.servex.data

import com.example.servex.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

object HttpClients {
    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun forServer(server: Server, base: OkHttpClient = shared): OkHttpClient {
        val fp = server.certFingerprint
        if (fp.isNullOrBlank()) return base
        val host = server.baseUrl.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
        val pinner = CertificatePinner.Builder().add(host, "sha256/$fp").build()
        return base.newBuilder().certificatePinner(pinner).build()
    }
}

class ServexApi(private val client: OkHttpClient) : ServexApiContract {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun clientFor(server: Server): OkHttpClient = HttpClients.forServer(server, client)

    private suspend fun get(server: Server, token: String, path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(server.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")
            .get().build()
        clientFor(server).newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, body.ifBlank { resp.message })
            body
        }
    }

    override suspend fun summary(server: Server, token: String): ServerSummary =
        json.decodeFromString(get(server, token, "/api/v1/summary"))

    override suspend fun metrics(server: Server, token: String): MetricsSnapshot =
        json.decodeFromString(get(server, token, "/api/v1/metrics"))

    override suspend fun history(server: Server, token: String, metric: String, points: Int): HistoryResponse =
        json.decodeFromString(get(server, token, "/api/v1/history?metric=$metric&points=$points"))

    override suspend fun health(server: Server, token: String): Boolean =
        runCatching { get(server, token, "/api/v1/health") }.isSuccess

    override suspend fun action(server: Server, token: String, action: String, args: Map<String, String>): ActionResult =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(ActionRequest.serializer(), ActionRequest(action, args))
            val req = Request.Builder()
                .url(server.baseUrl.trimEnd('/') + "/api/v1/action")
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            clientFor(server).newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(resp.code, body.ifBlank { resp.message })
                json.decodeFromString(body)
            }
        }
}
