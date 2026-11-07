package com.cardv.shared

import com.cardv.shared.features.CameraSettings
import com.cardv.shared.features.SettingsValues
import com.cardv.shared.safety.SafetyLevel
import com.cardv.shared.safety.SettingsCatalog
import com.cardv.shared.safety.WriteResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M6 standby flow against the fake camera: stop, write, verify and resume recording. */
class CameraSettingsFakeCameraTest {
    @Test
    fun standbySettingStopsAndResumesRecording() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "2").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(micReapplyDelayMillis = 0),
            ).use { client ->
                val settings = CameraSettings(client, pollDelayMillis = 0)
                val before = client.status()
                assertTrue(before.recording)
                assertEquals("ON", before.soundRecord)

                val outcome = settings.setNeedsStandby(SettingsCatalog.VIDEO_CLIP_TIME, "10MIN")

                assertIs<WriteResult.Applied<Unit>>(outcome.result)
                assertTrue(outcome.recordingBefore == true)
                assertTrue(outcome.stoppedRecording)
                assertTrue(outcome.resumedRecording)
                assertNull(outcome.verified) // verification is specific to SD formatting

                val after = client.status()
                assertTrue(after.recording)
                assertEquals("10MIN", after.videoClipTime)
                // Recording restarted, so the shared mic rule had to restore SoundRecord.
                assertEquals("ON", after.soundRecord)
            }
        }
    }

    @Test
    fun formatSdStopsVerifiesAndResumes() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(micReapplyDelayMillis = 0),
            ).use { client ->
                val outcome = CameraSettings(client, pollDelayMillis = 0)
                    .setNeedsStandby(SettingsCatalog.SD0, "format")

                assertIs<WriteResult.Applied<Unit>>(outcome.result)
                assertTrue(outcome.stoppedRecording)
                assertTrue(outcome.resumedRecording)
                assertEquals(true, outcome.verified)
                assertEquals("READY", outcome.statusAfter?.sdStatus)
                assertTrue(client.status().recording)
            }
        }
    }

    @Test
    fun safeSettingsAreNotSentThroughTheStandbyFlow() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(micReapplyDelayMillis = 0),
            ).use { client ->
                val outcome = CameraSettings(client, pollDelayMillis = 0)
                    .setNeedsStandby(SettingsCatalog.AWB, "DAYLIGHT")

                val refused = assertIs<WriteResult.Refused>(outcome.result)
                assertEquals(SafetyLevel.SAFE, refused.level)
                assertFalse(outcome.stoppedRecording)
                assertTrue(client.status().recording)
            }
        }
    }

    @Test
    fun currentValuesComeFromTheCameraStatus() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(micReapplyDelayMillis = 0),
            ).use { client ->
                val status = client.status()
                assertEquals("AUTO", SettingsValues.currentValue(status, SettingsCatalog.AWB))
                assertEquals("5MIN", SettingsValues.currentValue(status, SettingsCatalog.VIDEO_CLIP_TIME))
                assertEquals("Recording", SettingsValues.currentValue(status, SettingsCatalog.VIDEO))
                assertEquals("Camera.Menu.Q-SHOT", SettingsValues.rawKey(SettingsCatalog.Q_SHOT))
            }
        }
    }
}
