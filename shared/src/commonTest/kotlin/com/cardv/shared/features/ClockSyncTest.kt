package com.cardv.shared.features

import com.cardv.shared.CameraClient
import com.cardv.shared.CameraClientConfig
import com.cardv.shared.safety.WriteResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockSyncTest {
    private val phoneTime = LocalDateTime(2026, 11, 7, 9, 5, 3)
    private val cameraBehind = LocalDateTime(2022, 7, 20, 21, 12, 49)

    private class Recorder {
        var sets = 0
        var written: String? = null
    }

    private fun client(recorder: Recorder): CameraClient {
        val engine = MockEngine { request ->
            if (request.url.parameters["action"] == "set") {
                recorder.sets++
                recorder.written = request.url.parameters["value"]
            }
            respond(
                content = "0\nOK\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        return CameraClient(
            baseUrl = "http://camera.test",
            engine = engine,
            config = CameraClientConfig(micReapplyDelayMillis = 0),
        )
    }

    @Test
    fun syncWritesThePhoneTime() = runTest {
        val recorder = Recorder()
        val client = client(recorder)
        try {
            val outcome = real {
                ClockSync(client, now = { phoneTime }, zone = TimeZone.UTC)
                    .sync(cameraTime = cameraBehind)
            }

            assertIs<WriteResult.Applied<Unit>>(outcome.result)
            assertTrue(outcome.applied)
            assertFalse(outcome.skipped)
            assertEquals(1, recorder.sets)
            assertEquals("2026\$11\$07\$09\$05\$03", recorder.written)
            assertTrue(outcome.driftSeconds!! > 0)
        } finally {
            client.close()
        }
    }

    @Test
    fun syncIfNeededSkipsWhenTheDriftIsSmall() = runTest {
        val recorder = Recorder()
        val client = client(recorder)
        try {
            val outcome = real {
                ClockSync(client, now = { phoneTime }, zone = TimeZone.UTC)
                    .syncIfNeeded(cameraTime = LocalDateTime(2026, 11, 7, 9, 5, 10), toleranceSeconds = 30)
            }

            assertTrue(outcome.skipped)
            assertNull(outcome.result)
            assertEquals(0, recorder.sets)
            assertEquals(-7, outcome.driftSeconds)
        } finally {
            client.close()
        }
    }

    @Test
    fun syncIfNeededWritesWhenTheDriftExceedsTheTolerance() = runTest {
        val recorder = Recorder()
        val client = client(recorder)
        try {
            val outcome = real {
                ClockSync(client, now = { phoneTime }, zone = TimeZone.UTC)
                    .syncIfNeeded(cameraTime = cameraBehind, toleranceSeconds = 30)
            }

            assertFalse(outcome.skipped)
            assertIs<WriteResult.Applied<Unit>>(outcome.result)
            assertEquals(1, recorder.sets)
        } finally {
            client.close()
        }
    }

    @Test
    fun syncWritesEvenWithoutAKnownCameraTime() = runTest {
        val recorder = Recorder()
        val client = client(recorder)
        try {
            val outcome = real {
                ClockSync(client, now = { phoneTime }, zone = TimeZone.UTC).sync()
            }

            assertNull(outcome.cameraTimeBefore)
            assertNull(outcome.driftSeconds)
            assertEquals(1, recorder.sets)
        } finally {
            client.close()
        }
    }

    private suspend fun <T> real(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { block() }
}
