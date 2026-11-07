package com.cardv.shared

import com.cardv.shared.features.ClockSync
import com.cardv.shared.safety.WriteResult
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Clock sync against the fake camera: a real write, then a skip when already close. */
class ClockSyncFakeCameraTest {
    @Test
    fun syncsTheFakeCameraClockAndThenSkips() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(micReapplyDelayMillis = 0),
            ).use { client ->
                val target = LocalDateTime(2026, 11, 7, 9, 5, 3)
                val outcome = ClockSync(client, now = { target }, zone = TimeZone.UTC).sync()

                assertIs<WriteResult.Applied<Unit>>(outcome.result)
                assertTrue(outcome.applied)
                assertEquals("2026\$11\$07\$09\$05\$03", client.status().cameraTime?.let {
                    "${it.year}\$${it.monthNumber.toString().padStart(2, '0')}" +
                        "\$${it.dayOfMonth.toString().padStart(2, '0')}" +
                        "\$${it.hour.toString().padStart(2, '0')}" +
                        "\$${it.minute.toString().padStart(2, '0')}" +
                        "\$${it.second.toString().padStart(2, '0')}"
                })

                val after = client.status().cameraTime
                val skip = ClockSync(client, now = { target }, zone = TimeZone.UTC)
                    .syncIfNeeded(after, toleranceSeconds = 30)

                assertTrue(skip.skipped)
                assertNull(skip.result)
                assertEquals(0, skip.driftSeconds)
            }
        }
    }
}
