package com.cardv.shared

import com.cardv.shared.model.Folder
import com.cardv.shared.protocol.CgiClient
import com.cardv.shared.safety.CommandQueue
import com.cardv.shared.safety.CommandQueueConfig
import com.cardv.shared.safety.SettingsCatalog
import com.cardv.shared.safety.WriteResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests against tools/fake-camera/fake_camera.py.
 *
 * These live in androidUnitTest because that is the JVM test task CI already runs
 * (`:shared:testDebugUnitTest`); no extra JVM target is needed for the fake camera.
 */
class FakeCameraIntegrationTest {
    @Test
    fun listsAllFoldersAndBuildsUrls() = runBlocking<Unit> {
        FakeCameraProcess(
            "--loop-files", "12",
            "--sos-files", "7",
            "--lock-files", "2",
            "--photo-files", "1",
        ).use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(micReapplyDelayMillis = 0),
            ).use { client ->
                val loop = client.list(Folder.DCIM)
                assertEquals(12, loop.size)
                assertEquals("/SD/MOVIE/20220707_162655_002088.MP4", loop.first().path)
                assertEquals("/SD/MOVIE/20220707_172155_002099.MP4", loop.last().path)
                assertEquals("20220707_162655_002088.MP4", loop.first().name)

                val sos = client.list(Folder.SOS)
                assertEquals(7, sos.size)
                assertTrue(sos.all { it.path.startsWith("/SD/SOS/") })

                assertEquals(2, client.list(Folder.LOCK).size)
                assertEquals(1, client.list(Folder.PHOTO).size)

                assertEquals(
                    camera.baseUrl + "/SD/MOVIE/20220707_162655_002088.MP4",
                    client.clipUrl(loop.first()),
                )
                assertEquals("rtsp://127.0.0.1:554/liveRTSP/av2", client.liveUrl())
            }
        }
    }

    @Test
    fun thumbnailComesFromTheSkipBox() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(micReapplyDelayMillis = 0),
            ).use { client ->
                val clip = client.list(Folder.DCIM).single()
                val jpeg = assertNotNull(client.thumbnail(clip))

                assertTrue(jpeg.size > 4)
                assertEquals(0xFF.toByte(), jpeg[0])
                assertEquals(0xD8.toByte(), jpeg[1])
                assertEquals(0xFF.toByte(), jpeg[jpeg.size - 2])
                assertEquals(0xD9.toByte(), jpeg[jpeg.size - 1])
            }
        }
    }

    @Test
    fun healthStatusAndClockSync() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(micReapplyDelayMillis = 0),
            ).use { client ->
                val health = client.health()
                assertEquals("EV0", health.ev)
                assertTrue(health.recording)

                val status = client.status()
                assertTrue(status.firmware!!.contains("GEELY_Z3152_V1.2"))
                assertEquals("READY", status.sdStatus)
                assertEquals(12_345L, status.freeMb)
                assertEquals(61_000L, status.totalMb)

                val sync = client.syncClock(LocalDateTime(2026, 11, 7, 9, 5, 3))
                assertIs<WriteResult.Applied<Unit>>(sync)
                val after = client.status()
                assertEquals(2026, after.cameraTime?.year)
                assertEquals(11, after.cameraTime?.monthNumber)
                assertEquals(7, after.cameraTime?.dayOfMonth)
                assertEquals(9, after.cameraTime?.hour)
                assertEquals(5, after.cameraTime?.minute)
                assertEquals(3, after.cameraTime?.second)
            }
        }
    }

    @Test
    fun sosPaginationReturnsEveryClip() = runBlocking<Unit> {
        FakeCameraProcess("--sos-files", "12").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(pageSize = 5, micReapplyDelayMillis = 0),
            ).use { client ->
                val sos = client.list(Folder.SOS)
                assertEquals(12, sos.size)
                assertEquals(sos.map { it.path }.sorted(), sos.map { it.path })
            }
        }
    }

    @Test
    fun forbiddenSettingNeverReachesTheCamera() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(micReapplyDelayMillis = 0),
            ).use { client ->
                assertIs<WriteResult.Refused>(client.setSetting(SettingsCatalog.FLICKER, "50Hz"))

                // The camera is still healthy: nothing was sent.
                assertEquals("EV0", client.health().ev)
            }
        }
    }

    @Test
    fun flickerHangMarksCameraHungWithShortTimeout() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1", "--hang-on", "Flicker").use { camera ->
            val http = HttpClient(OkHttp.create()) {
                expectSuccess = false
                install(HttpTimeout)
            }
            try {
                val cgi = CgiClient(http, camera.baseUrl, defaultTimeoutMillis = 300)
                val queue = CommandQueue(
                    config = CommandQueueConfig(
                        readTimeoutMillis = 300,
                        writeTimeoutMillis = 400,
                        healthTimeoutMillis = 300,
                    ),
                    healthCheck = { cgi.isHealthy(300) },
                )

                val started = System.nanoTime()
                val result = queue.write { cgi.set("Flicker", "50Hz", 400) }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000

                assertIs<WriteResult.Hung>(result)
                assertTrue(queue.isHung)
                assertTrue(elapsedMs < 5_000, "hang path took ${elapsedMs} ms")

                // The queue must not send anything else after the hang.
                assertIs<WriteResult.Hung>(queue.write { cgi.set("EV", "EV0", 400) })
            } finally {
                http.close()
            }
        }
    }
}
