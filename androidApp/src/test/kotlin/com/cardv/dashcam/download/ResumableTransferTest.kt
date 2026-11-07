package com.cardv.dashcam.download

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** JVM tests for the worker's transfer arithmetic, using a range-capable mock HTTP server. */
class ResumableTransferTest {
    private val content = ByteArray(8_192) { (it % 251).toByte() }
    private val client = OkHttpClient()
    private val tempDirectories = mutableListOf<File>()
    private val servers = mutableListOf<MockWebServer>()

    @AfterTest
    fun cleanUp() {
        servers.forEach { runCatching { it.shutdown() } }
        tempDirectories.forEach { it.deleteRecursively() }
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun resumesAtThePartialFileLength() = runBlocking<Unit> {
        val url = startServer(ignoreRange = false)
        val directory = temporaryDirectory()
        val part = File(directory, "clip.part").apply {
            writeBytes(content.copyOfRange(0, 3_000))
        }
        val totalFile = File(directory, "clip.part.total")

        val progress = mutableListOf<Long>()
        val total = ResumableTransfer(client).download(
            url = url,
            part = part,
            totalFile = totalFile,
            onProgress = { bytes, _ -> progress += bytes },
        )

        assertEquals(content.size.toLong(), total)
        assertContentEquals(content, part.readBytes())
        assertEquals(content.size.toString(), totalFile.readText())
        assertEquals(content.size.toLong(), progress.last())
    }

    @Test
    fun aServerThatIgnoresRangeRestartsTheFile() = runBlocking<Unit> {
        val url = startServer(ignoreRange = true)
        val directory = temporaryDirectory()
        val part = File(directory, "clip.part").apply {
            writeBytes(ByteArray(1_000) { 0x7F })
        }
        val totalFile = File(directory, "clip.part.total")

        val total = ResumableTransfer(client).download(url, part, totalFile)

        assertEquals(content.size.toLong(), total)
        assertContentEquals(content, part.readBytes())
    }

    @Test
    fun anUnsatisfiedRangeMeansThePartIsAlreadyComplete() = runBlocking<Unit> {
        val url = startServer(ignoreRange = false, alwaysUnsatisfied = true)
        val directory = temporaryDirectory()
        val part = File(directory, "clip.part").apply { writeBytes(content) }
        val totalFile = File(directory, "clip.part.total")

        val total = assertNotNull(
            ResumableTransfer(client).download(url, part, totalFile),
        )

        assertEquals(content.size.toLong(), total)
        assertEquals(content.size.toString(), totalFile.readText())
        assertContentEquals(content, part.readBytes())
    }

    private fun temporaryDirectory(): File =
        Files.createTempDirectory("cardv-transfer").toFile().also { tempDirectories += it }

    private fun startServer(
        ignoreRange: Boolean,
        alwaysUnsatisfied: Boolean = false,
    ): String {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                val start = range
                    ?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: 0

                if (alwaysUnsatisfied || start >= content.size) {
                    return MockResponse()
                        .setResponseCode(416)
                        .setHeader("Content-Range", "bytes */${content.size}")
                }

                if (ignoreRange || range == null) {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "video/mp4")
                        .setBody(Buffer().write(content))
                }

                val slice = content.copyOfRange(start, content.size)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                    .setBody(Buffer().write(slice))
            }
        }
        server.start()
        servers += server
        return server.url("/clip.MP4").toString()
    }
}
