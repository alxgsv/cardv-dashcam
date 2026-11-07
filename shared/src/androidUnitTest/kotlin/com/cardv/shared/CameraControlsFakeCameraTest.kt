package com.cardv.shared

import com.cardv.shared.features.CameraControls
import com.cardv.shared.model.Folder
import com.cardv.shared.safety.WriteResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M5 controls against the fake camera, including the asynchronous LOCK list. */
class CameraControlsFakeCameraTest {
    @Test
    fun microphoneSnapshotAndRecordToggle() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1", "--photo-files", "0").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(micReapplyDelayMillis = 0),
            ).use { client ->
                val controls = CameraControls(client, lockPollDelayMillis = 0)

                assertIs<WriteResult.Applied<Unit>>(controls.microphone(false))
                assertEquals("OFF", client.status().soundRecord)

                assertIs<WriteResult.Applied<Unit>>(controls.microphone(true))
                assertEquals("ON", client.status().soundRecord)

                assertEquals(0, client.list(Folder.PHOTO).size)
                assertIs<WriteResult.Applied<Unit>>(controls.snapshot())
                assertEquals(1, client.list(Folder.PHOTO).size)

                assertTrue(client.status().recording)
                assertIs<WriteResult.Applied<Unit>>(controls.toggleRecording())
                assertTrue(!client.status().recording)
                assertIs<WriteResult.Applied<Unit>>(controls.toggleRecording())
                assertTrue(client.status().recording)
                // The firmware resets the mic when recording restarts; the shared rule restores it.
                assertEquals("ON", client.status().soundRecord)
            }
        }
    }

    @Test
    fun lockWaitsForTheLockFolderToCatchUp() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "2", "--lock-files", "1").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(micReapplyDelayMillis = 0),
            ).use { client ->
                val before = client.list(Folder.LOCK).map { it.path }.toSet()
                assertEquals(1, before.size)

                val outcome = CameraControls(client, lockPollDelayMillis = 0)
                    .lockCurrentClip(knownLockPaths = before, attempts = 3)

                assertIs<WriteResult.Applied<Unit>>(outcome.write)
                assertTrue(outcome.applied)
                assertTrue(outcome.found)
                assertEquals(2, outcome.clips.size)
                assertEquals(1, outcome.newPaths.size)
                assertEquals(1, outcome.polls)
                assertEquals(2, client.list(Folder.LOCK).size)
            }
        }
    }

    @Test
    fun hungLockWriteIsNotRetriedOrPolled() = runBlocking<Unit> {
        FakeCameraProcess("--loop-files", "1", "--hang-on", "Video").use { camera ->
            CameraClient(
                baseUrl = camera.baseUrl,
                config = CameraClientConfig(
                    writeTimeoutMillis = 400,
                    healthTimeoutMillis = 300,
                    micReapplyDelayMillis = 0,
                ),
            ).use { client ->
                val outcome = CameraControls(client, lockPollDelayMillis = 0)
                    .lockCurrentClip(knownLockPaths = emptySet())

                assertIs<WriteResult.Hung>(outcome.write)
                assertTrue(client.isHung)
                assertEquals(0, outcome.polls)
            }
        }
    }
}
