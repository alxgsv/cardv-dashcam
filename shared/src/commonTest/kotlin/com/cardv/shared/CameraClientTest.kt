package com.cardv.shared

import com.cardv.shared.model.Folder
import com.cardv.shared.model.LiveStream
import com.cardv.shared.model.VideoOp
import com.cardv.shared.protocol.CameraResultException
import com.cardv.shared.protocol.ResultCode
import com.cardv.shared.safety.SafetyLevel
import com.cardv.shared.safety.SettingsCatalog
import com.cardv.shared.safety.WriteResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraClientTest {
    private val baseUrl = "http://camera.test"

    private fun client(
        config: CameraClientConfig = CameraClientConfig(micReapplyDelayMillis = 0),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): CameraClient = CameraClient(
        baseUrl = baseUrl,
        engine = MockEngine(handler),
        config = config,
    )

    @Test
    fun healthAndStatusAreTyped() = runTest {
        val client = client { request ->
            when (request.url.parameters["property"]) {
                "Camera.Menu.EV" -> textResponse("0\nOK\nCamera.Menu.EV=EV0\n")
                "Camera.Preview.MJPEG.status.*" -> textResponse(
                    "0\nOK\nCamera.Preview.MJPEG.status.mode=Videomode\n" +
                        "Camera.Preview.MJPEG.status.record=Recording\n",
                )
                "Camera.*" -> textResponse(
                    """0
OK
Camera.Menu.FWversion=MS;ms8339DEW;GEELY_Z3152_V1.2
Camera.Menu.SD0=READY
Camera.Menu.CardInfo.Free=12345
Camera.Menu.CardInfo.Total=61000
Camera.TimeDate=2022/7/20 21/12/49
Camera.Preview.MJPEG.status.record=Recording
Camera.Preview.MJPEG.status.mode=Videomode
Camera.RecFileName=SD:\MOVIE\20220707_162655_002088.MP4
Camera.DBFileNum=54+125
Camera.File.Total=179
Camera.Menu.IsStreaming=NO
Camera.Menu.EV=EV0
Camera.Menu.AWB=AUTO
Camera.Menu.DateLogoStamp=ON
Camera.Menu.SoundRecord=ON
Camera.Menu.VideoClipTime=5MIN
""",
                )
                else -> error("unexpected property ${request.url.parameters["property"]}")
            }
        }

        val health = real { client.health() }
        assertEquals("EV0", health.ev)
        assertEquals("Videomode", health.mode)
        assertTrue(health.recording)

        val status = real { client.status() }
        assertEquals("MS;ms8339DEW;GEELY_Z3152_V1.2", status.firmware)
        assertEquals("READY", status.sdStatus)
        assertEquals(12_345L, status.freeMb)
        assertEquals(61_000L, status.totalMb)
        assertEquals(2022, status.cameraTime?.year)
        assertTrue(status.recording)
        assertEquals("54+125", status.dbFileNum)
        assertEquals(179L, status.fileTotal)
        assertFalse(status.isStreaming)
        assertEquals("5MIN", status.videoClipTime)
        client.close()
    }

    @Test
    fun listUsesCount100AndStopsWhenAmountIsShort() = runTest {
        var requests = 0
        val client = client { request ->
            requests++
            assertEquals("dir", request.url.parameters["action"])
            assertEquals("DCIM", request.url.parameters["property"])
            assertEquals("100", request.url.parameters["count"])
            assertEquals("all", request.url.parameters["format"])
            assertEquals("0", request.url.parameters["from"])
            textResponse(dirXml("DCIM", (1..3).map { "/SD/MOVIE/20220707_16265${it}_00208${it}.MP4" }))
        }

        val clips = real { client.list(Folder.DCIM) }
        assertEquals(3, clips.size)
        assertEquals(1, requests)
        client.close()
    }

    @Test
    fun listPaginatesUntilAPageIsShort() = runTest {
        val paths = (1..5).map { "/SD/MOVIE/20220707_16265${it}_00208${it}.MP4" }
        val froms = mutableListOf<String>()
        val client = client(config = CameraClientConfig(pageSize = 2, micReapplyDelayMillis = 0)) { request ->
            val from = request.url.parameters["from"]!!.toInt()
            val count = request.url.parameters["count"]!!.toInt()
            froms += from.toString()
            textResponse(dirXml("DCIM", paths.drop(from).take(count)))
        }

        val clips = real { client.list(Folder.DCIM) }
        assertEquals(paths, clips.map { it.path })
        assertEquals(listOf("0", "2", "4"), froms)
        client.close()
    }

    @Test
    fun thumbnailReadsRangeAndSlicesTheSkipBox() = runTest {
        val bytes = ByteArray(128)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        putU32(bytes, 56, jpeg.size)
        jpeg.copyInto(bytes, 60)

        val client = client { request ->
            assertEquals("bytes=0-65535", request.headers[HttpHeaders.Range])
            respond(
                content = bytes,
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentType, "video/mpeg"),
            )
        }

        val clip = clip("/SD/MOVIE/20220707_162655_002088.MP4")
        val thumbnail = real { client.thumbnail(clip) }
        assertEquals(jpeg.toList(), thumbnail?.toList())
        client.close()
    }

    @Test
    fun thumbnailRejectsInconsistentHeader() = runTest {
        val bytes = ByteArray(128)
        putU32(bytes, 56, 1_000)
        val client = client {
            respond(content = bytes, status = HttpStatusCode.PartialContent)
        }
        assertNull(real { client.thumbnail(clip("/SD/MOVIE/a.MP4")) })
        client.close()
    }

    @Test
    fun nonZeroResultCodeOnHttpGetThrowsTypedError() = runTest {
        val client = client {
            textResponse("703\nUnknown CGI-PARAM: Property\n")
        }
        val error = runCatching { real { client.health() } }.exceptionOrNull()
        assertIs<CameraResultException>(error)
        assertEquals(ResultCode.UNKNOWN_PROPERTY, error.result)
        client.close()
    }

    @Test
    fun forbiddenSettingIsRefusedWithoutAnyRequest() = runTest {
        var requests = 0
        val client = client {
            requests++
            textResponse("0\nOK\n")
        }

        val result = real { client.setSetting(SettingsCatalog.FLICKER, "50Hz") }

        val refused = assertIs<WriteResult.Refused>(result)
        assertEquals(SafetyLevel.FORBIDDEN, refused.level)
        assertEquals(0, requests)
        client.close()
    }

    @Test
    fun experimentalSettingNeedsOptIn() = runTest {
        var setRequests = 0
        val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
            when (request.url.parameters["action"]) {
                "set" -> {
                    setRequests++
                    textResponse("0\nOK\n")
                }
                else -> textResponse("0\nOK\nCamera.Menu.EV=EV0\n")
            }
        }

        val strict = client(handler = handler)
        assertIs<WriteResult.Refused>(real { strict.setSetting(SettingsCatalog.GSENSOR, "LOW") })
        assertEquals(0, setRequests)
        strict.close()

        val permissive = client(
            config = CameraClientConfig(micReapplyDelayMillis = 0, allowExperimental = true),
            handler = handler,
        )
        assertIs<WriteResult.Applied<Unit>>(real { permissive.setSetting(SettingsCatalog.GSENSOR, "LEVEL2") })
        assertEquals(1, setRequests)
        permissive.close()
    }

    @Test
    fun experimentalOptInCanFollowTheUserWithoutReconnecting() = runTest {
        var setRequests = 0
        val client = client { request ->
            if (request.url.parameters["action"] == "set") {
                setRequests++
                textResponse("0\nOK\n")
            } else {
                textResponse("0\nOK\nCamera.Menu.EV=EV0\n")
            }
        }

        assertIs<WriteResult.Refused>(real { client.setSetting(SettingsCatalog.GSENSOR, "LOW") })
        client.setExperimentalAllowed(true)
        assertIs<WriteResult.Applied<Unit>>(
            real { client.setSetting(SettingsCatalog.GSENSOR, "LEVEL2") },
        )
        client.setExperimentalAllowed(false)
        assertIs<WriteResult.Refused>(real { client.setSetting(SettingsCatalog.GSENSOR, "OFF") })
        assertEquals(1, setRequests)
        client.close()
    }

    @Test
    fun needsStandbyIsRefusedWhileRecording() = runTest {
        var setRequests = 0
        val client = client { request ->
            when (request.url.parameters["property"]) {
                "Camera.Preview.MJPEG.status.record" -> textResponse(
                    "0\nOK\nCamera.Preview.MJPEG.status.record=Recording\n",
                )
                else -> {
                    if (request.url.parameters["action"] == "set") setRequests++
                    textResponse("0\nOK\n")
                }
            }
        }

        val result = real { client.setSetting(SettingsCatalog.VIDEO_CLIP_TIME, "5MIN") }
        val refused = assertIs<WriteResult.Refused>(result)
        assertEquals(SafetyLevel.NEEDS_STANDBY, refused.level)
        assertEquals(0, setRequests)
        client.close()
    }

    @Test
    fun invalidValueIsRefusedWithoutRequest() = runTest {
        var requests = 0
        val client = client {
            requests++
            textResponse("0\nOK\n")
        }
        val result = real { client.setSetting(SettingsCatalog.EV, "NOT_A_VALUE") }
        assertIs<WriteResult.Refused>(result)
        assertEquals(0, requests)
        client.close()
    }

    @Test
    fun rejectedWriteIsTypedAndKeepsCameraUsable() = runTest {
        var setRequests = 0
        val client = client { request ->
            when (request.url.parameters["action"]) {
                "set" -> {
                    setRequests++
                    textResponse("709\nRefused while recording\n")
                }
                else -> textResponse("0\nOK\nCamera.Menu.EV=EV0\n")
            }
        }

        val result = real { client.setSetting(SettingsCatalog.AWB, "AUTO") }
        val rejected = assertIs<WriteResult.Rejected>(result)
        assertEquals(ResultCode.REFUSED, rejected.result)
        assertFalse(client.isHung)
        assertEquals(1, setRequests)
        client.close()
    }

    @Test
    fun failedHealthCheckMarksCameraHungAndStopsFurtherWrites() = runTest {
        var setRequests = 0
        val client = client { request ->
            when (request.url.parameters["action"]) {
                "set" -> {
                    setRequests++
                    textResponse("0\nOK\n")
                }
                else -> throw RuntimeException("camera unresponsive")
            }
        }

        assertIs<WriteResult.Hung>(real { client.setSetting(SettingsCatalog.AWB, "AUTO") })
        assertTrue(client.isHung)

        assertIs<WriteResult.Hung>(real { client.setSetting(SettingsCatalog.AWB, "DAYLIGHT") })
        assertEquals(1, setRequests)
        client.close()
    }

    @Test
    fun recordingRestartReappliesMicrophone() = runTest {
        var soundRecord = "ON"
        val setValues = mutableListOf<String>()
        val client = client { request ->
            when {
                request.url.parameters["property"] == "Camera.Menu.SoundRecord" ->
                    textResponse("0\nOK\nCamera.Menu.SoundRecord=$soundRecord\n")
                request.url.parameters["property"] == "Camera.Preview.MJPEG.status.record" ->
                    textResponse("0\nOK\nCamera.Preview.MJPEG.status.record=Recording\n")
                request.url.parameters["action"] == "set" -> {
                    val value = request.url.parameters["value"]!!
                    setValues += value
                    when (value) {
                        "record" -> soundRecord = "OFF" // camera resets mic on restart
                        "unmute" -> soundRecord = "ON"
                    }
                    textResponse("0\nOK\n")
                }
                else -> textResponse("0\nOK\nCamera.Menu.EV=EV0\n")
            }
        }

        val result = real { client.video(VideoOp.RECORD) }

        assertIs<WriteResult.Applied<Unit>>(result)
        assertEquals(listOf("record", "unmute"), setValues)
        assertEquals("ON", soundRecord)
        client.close()
    }

    @Test
    fun syncClockFormatsTimeSettings() = runTest {
        var written: String? = null
        val client = client { request ->
            when (request.url.parameters["action"]) {
                "set" -> {
                    assertEquals("TimeSettings", request.url.parameters["property"])
                    written = request.url.parameters["value"]
                    textResponse("0\nOK\n")
                }
                else -> textResponse("0\nOK\nCamera.Menu.EV=EV0\n")
            }
        }

        val result = real { client.syncClock(LocalDateTime(2026, 11, 7, 9, 5, 3)) }

        assertIs<WriteResult.Applied<Unit>>(result)
        assertEquals("2026\$11\$07\$09\$05\$03", written)
        client.close()
    }

    @Test
    fun urlsPointAtTheCamera() {
        val client = client { textResponse("0\nOK\n") }
        assertEquals(
            "$baseUrl/SD/MOVIE/20220707_162655_002088.MP4",
            client.clipUrl(clip("/SD/MOVIE/20220707_162655_002088.MP4")),
        )
        assertEquals("rtsp://camera.test:554/liveRTSP/av2", client.liveUrl())
        assertEquals("rtsp://camera.test:554/liveRTSP/v1", client.liveUrl(LiveStream.V1))
        client.close()
    }

    private suspend fun <T> real(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { block() }

    private fun clip(path: String) = com.cardv.shared.model.Clip(
        path = path,
        sizeBytes = 1,
        timestamp = null,
    )

    private fun MockRequestHandleScope.textResponse(body: String): HttpResponseData = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
    )

    private fun dirXml(property: String, paths: List<String>): String = buildString {
        append("<$property>")
        for (path in paths) {
            append(
                "<file><name>$path</name>" +
                    "<format size=\"1920x1080\" fps=\"30\" time=\"300.2\">MP4</format>" +
                    "<size>1000</size><attr>RW</attr><time>2022-07-07 16:26:55</time></file>",
            )
        }
        append("<amount>${paths.size}</amount></$property>")
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
