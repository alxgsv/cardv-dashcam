package com.cardv.shared

import com.cardv.shared.download.DownloadRanges
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Range and resume semantics against the fake camera, which implements `Range`, `Content-Range`
 * and the same uppercase response headers as the real firmware.
 */
class DownloadRangeFakeCameraTest {
    private val clipPath = "/SD/MOVIE/20220707_162655_002088.MP4"

    @Test
    fun resumingFromTheDownloadedLengthProducesTheWholeFile() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1").use { camera ->
            val http = HttpClient(OkHttp.create())
            try {
                val url = camera.baseUrl + clipPath

                val firstResponse = http.get(url) {
                    header(HttpHeaders.Range, "bytes=0-999")
                }
                assertEquals(206, firstResponse.status.value)
                val firstRange = DownloadRanges.parseContentRange(
                    firstResponse.headers[HttpHeaders.ContentRange],
                )
                assertNotNull(firstRange)
                assertEquals(0L, firstRange.start)
                assertNotNull(firstRange.totalBytes)
                val firstPart = firstResponse.body<ByteArray>()
                assertEquals(1000, firstPart.size)

                val resumeHeader = assertNotNull(
                    DownloadRanges.requestHeader(DownloadRanges.resumeOffset(firstPart.size.toLong(), firstRange.totalBytes)),
                )
                val resumeResponse = http.get(url) {
                    header(HttpHeaders.Range, resumeHeader)
                }
                assertEquals(206, resumeResponse.status.value)

                val resumeRange = DownloadRanges.parseContentRange(
                    resumeResponse.headers[HttpHeaders.ContentRange],
                )
                assertNotNull(resumeRange)
                assertEquals(firstPart.size.toLong(), resumeRange.start)
                assertEquals(firstRange.totalBytes, resumeRange.totalBytes)

                val rest = resumeResponse.bodyAsBytes()
                val total = firstRange.totalBytes
                assertNotNull(total)
                assertEquals(total, (firstPart.size + rest.size).toLong())
                assertTrue(DownloadRanges.isComplete(firstPart.size + rest.size.toLong(), total))
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun aCompleteFileIsDetectedFromTheUnsatisfiedRange() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1").use { camera ->
            val http = HttpClient(OkHttp.create())
            try {
                val url = camera.baseUrl + clipPath
                val head = http.get(url) { header(HttpHeaders.Range, "bytes=0-0") }
                val range = DownloadRanges.parseContentRange(head.headers[HttpHeaders.ContentRange])
                val total = assertNotNull(range?.totalBytes)

                // Asking for the first byte past the end gives the camera's total back.
                val past = http.get(url) { header(HttpHeaders.Range, "bytes=$total-") }
                assertEquals(416, past.status.value)
                val unsatisfied = DownloadRanges.parseContentRange(
                    past.headers[HttpHeaders.ContentRange],
                )
                assertEquals(total, unsatisfied?.totalBytes)
                assertTrue(unsatisfied!!.isUnsatisfied)
                assertTrue(DownloadRanges.isComplete(total, unsatisfied.totalBytes))
            } finally {
                http.close()
            }
        }
    }
}
