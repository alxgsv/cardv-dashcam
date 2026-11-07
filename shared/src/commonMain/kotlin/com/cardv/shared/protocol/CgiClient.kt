package com.cardv.shared.protocol

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/**
 * Thin HTTP layer for `/cgi-bin/Config.cgi` plus file range reads.
 *
 * It deliberately does **not** serialize commands; that is [com.cardv.shared.safety.CommandQueue]'s
 * job. Keeping the transport dumb makes it testable with Ktor's MockEngine and with the fake camera.
 */
class CgiClient(
    private val client: HttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    private val defaultTimeoutMillis: Long = 10_000,
    private val path: String = "/cgi-bin/Config.cgi",
) {
    val baseUrl: String = baseUrl.trimEnd('/')

    /** `action=get&property=<property>`; throws [CameraResultException] for non-zero result codes. */
    suspend fun get(property: String, timeoutMillis: Long = defaultTimeoutMillis): KvDocument {
        val response = execute(
            action = "get",
            parameters = mapOf("property" to property),
            timeoutMillis = timeoutMillis,
        )
        return parseCommandResponse(response)
    }

    /** `action=set&property=<property>&value=<value>`; throws [CameraResultException] for non-zero codes. */
    suspend fun set(
        property: String,
        value: String,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): KvDocument {
        val response = execute(
            action = "set",
            parameters = mapOf("property" to property, "value" to value),
            timeoutMillis = timeoutMillis,
        )
        return parseCommandResponse(response)
    }

    /**
     * `action=dir`; returns the raw XML body.
     *
     * A non-zero result code (for example `703 Unknown CGI-PARAM`) is turned into
     * [CameraResultException] instead of being handed to the XML parser.
     */
    suspend fun dir(
        property: String,
        count: Int,
        from: Int,
        format: String = "all",
        timeoutMillis: Long = defaultTimeoutMillis,
    ): String {
        val response = execute(
            action = "dir",
            parameters = mapOf(
                "property" to property,
                "format" to format,
                "count" to count.toString(),
                "from" to from.toString(),
            ),
            timeoutMillis = timeoutMillis,
        )
        ensureHttpOk(response)
        val text = response.bodyAsText().removePrefix("\uFEFF")
        if (text.trimStart().startsWith("<")) return text

        // Not XML: the camera used a command-style error response (for example 703).
        val document = runCatching { KvParser.parse(text) }.getOrNull()
            ?: throw MalformedResponseException("expected XML directory listing, got: ${text.take(80)}")
        if (!document.result.isSuccess) throw CameraResultException(document.result, document.message)
        throw MalformedResponseException("directory response had no XML: ${text.take(80)}")
    }

    /**
     * GET a byte range of a media file. Returns `null` for 404/416.
     *
     * Thumbnails are a file read, not a CGI command: callers run this outside the command queue so
     * it can overlap with other downloads (verified on the real camera).
     */
    suspend fun fetchRange(
        filePath: String,
        start: Long,
        endInclusive: Long,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): ByteArray? {
        val requestUrl = baseUrl + normalizePath(filePath)
        val response = client.get(requestUrl) {
            header(HttpHeaders.Range, "bytes=$start-$endInclusive")
            timeout { requestTimeoutMillis = timeoutMillis }
        }
        val status = response.status.value
        if (status == 404 || status == 416) return null
        ensureHttpOk(response)

        // Defensive: never pull a whole multi-hundred-MB clip if a server ignores Range.
        if (status == 200) {
            val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val requested = endInclusive - start + 1
            if (contentLength != null && contentLength > requested) {
                throw CameraHttpException(200, "camera ignored Range and returned $contentLength bytes")
            }
        }
        return response.body<ByteArray>()
    }

    /** True only when the camera answers a cheap read with a zero result code. */
    suspend fun isHealthy(timeoutMillis: Long = defaultTimeoutMillis): Boolean =
        try {
            get("Camera.Menu.EV", timeoutMillis)
            true
        } catch (_: Exception) {
            false
        }

    private suspend fun execute(
        action: String,
        parameters: Map<String, String>,
        timeoutMillis: Long,
    ): HttpResponse {
        val requestUrl = baseUrl + path
        return client.get(requestUrl) {
            url {
                this.parameters.append("action", action)
                for ((key, value) in parameters) {
                    this.parameters.append(key, value)
                }
            }
            timeout { requestTimeoutMillis = timeoutMillis }
        }
    }

    private suspend fun parseCommandResponse(response: HttpResponse): KvDocument {
        ensureHttpOk(response)
        val text = response.bodyAsText()
        val document = KvParser.parse(text)
        if (!document.result.isSuccess) {
            throw CameraResultException(document.result, document.message)
        }
        return document
    }

    private fun ensureHttpOk(response: HttpResponse) {
        val status = response.status.value
        if (status !in 200..299) {
            throw CameraHttpException(status, "unexpected status from the camera")
        }
    }

    private fun normalizePath(filePath: String): String =
        if (filePath.startsWith('/')) filePath else "/$filePath"

    companion object {
        const val DEFAULT_BASE_URL: String = "http://192.168.42.1"
    }
}
