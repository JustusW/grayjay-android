package com.futo.platformplayer.subfeed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A server answer. [code] 0 never happens: a failed connection throws [IOException] instead. */
class SubfeedResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
    /** Worth retrying later (server trouble), as opposed to a request the server will never accept. */
    val retryable: Boolean get() = code >= 500 || code == 408 || code == 429
    fun json(): JsonElement? = try { if (body.isBlank()) null else Json.parseToJsonElement(body) } catch (e: Throwable) { null }
}

/**
 * Plain JSON over HTTP to the subfeed server. No authentication: the server knows the owner's phone by its tailnet
 * address.
 */
class SubfeedClient(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')

    @Throws(IOException::class)
    fun request(method: String, path: String, body: JsonElement? = null): SubfeedResponse {
        val builder = Request.Builder().url(base + path).header("Accept", "application/json")
        val requestBody = body?.let { Json.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody(JSON))
            "PUT" -> builder.put(requestBody ?: "{}".toRequestBody(JSON))
            else -> throw IllegalArgumentException("Unsupported method $method")
        }
        http.newCall(builder.build()).execute().use { response ->
            return SubfeedResponse(response.code, response.body.string())
        }
    }

    fun get(path: String) = request("GET", path)
    fun post(path: String, body: JsonElement) = request("POST", path, body)
    fun put(path: String, body: JsonElement) = request("PUT", path, body)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        const val SNAPSHOT = "/api/v1/sync/snapshot"
        const val SNAPSHOTS = "/api/v1/sync/snapshots"
        const val SUBSCRIPTIONS = "/api/v1/sync/subscriptions"
        const val HISTORY = "/api/v1/sync/history"
        const val QUEUE = "/api/v1/queue"
        const val QUEUE_IMPORT = "/api/v1/queue/import"
        const val QUEUE_ADD = "/api/v1/queue/add"
        const val QUEUE_REMOVE = "/api/v1/queue/remove"
        const val PRIORITY_NEW = "/api/v1/priority/new"
    }
}
