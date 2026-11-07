package com.cardv.shared.features

import com.cardv.shared.CameraClient
import com.cardv.shared.model.Clip
import com.cardv.shared.model.Folder
import com.cardv.shared.model.VideoOp
import com.cardv.shared.protocol.CameraHungException
import com.cardv.shared.safety.WriteResult
import kotlinx.coroutines.delay

/**
 * What happened when the current clip was locked with `Video=rec_emer`.
 *
 * The camera moves the clip to `/SD/LOCK` asynchronously, and the folder can stay empty for a
 * moment right after the write (docs/PROTOCOL.md). [polls] is how many `dir property=LOCK` reads it
 * took to see the new file; [found] is false when the folder never caught up.
 */
data class LockOutcome(
    val write: WriteResult<Unit>,
    val clips: List<Clip> = emptyList(),
    val newPaths: List<String> = emptyList(),
    val polls: Int = 0,
) {
    val applied: Boolean get() = write is WriteResult.Applied<*>
    val found: Boolean get() = newPaths.isNotEmpty()
}

/**
 * The M5 camera controls, on top of [CameraClient.video].
 *
 * Only low-risk `Video=` operations and the LOCK re-poll live here. Every write still goes through
 * the shared `CommandQueue`, including its timeout, health check and mic re-apply rule.
 */
class CameraControls(
    private val client: CameraClient,
    private val lockPollDelayMillis: Long = 1_000,
    private val lockPollAttempts: Int = 6,
) {
    init {
        require(lockPollDelayMillis >= 0) { "lockPollDelayMillis must not be negative" }
        require(lockPollAttempts >= 1) { "lockPollAttempts must be at least 1" }
    }

    /** `Video=unmute` / `Video=mute`; the camera reports the result in `Camera.Menu.SoundRecord`. */
    suspend fun microphone(on: Boolean): WriteResult<Unit> =
        client.video(if (on) VideoOp.UNMUTE else VideoOp.MUTE)

    /** `Video=capture`; the new JPEG appears in `/SD/PHOTO` for the caller to re-list. */
    suspend fun snapshot(): WriteResult<Unit> = client.video(VideoOp.CAPTURE)

    /**
     * Toggle Recording ↔ Standby with `Video=record`.
     *
     * [CameraClient.video] re-applies the microphone after a restart, because the firmware resets
     * `SoundRecord` to OFF when a new clip starts.
     */
    suspend fun toggleRecording(): WriteResult<Unit> = client.video(VideoOp.RECORD)

    /**
     * Lock the clip currently being recorded (`Video=rec_emer`) and re-poll LOCK for it.
     *
     * [knownLockPaths] should be the LOCK listing before the write, so a file that was already
     * there is not mistaken for the new one. A failed [write] is returned without polling; a hung
     * camera is never retried.
     */
    suspend fun lockCurrentClip(
        knownLockPaths: Set<String> = emptySet(),
        attempts: Int = lockPollAttempts,
    ): LockOutcome {
        require(attempts >= 1) { "attempts must be at least 1" }
        val write = client.video(VideoOp.REC_EMER)
        if (write !is WriteResult.Applied) return LockOutcome(write)

        var lastClips: List<Clip> = emptyList()
        var polls = 0
        repeat(attempts) {
            polls++
            lastClips = try {
                client.list(Folder.LOCK)
            } catch (_: CameraHungException) {
                return LockOutcome(write, lastClips, emptyList(), polls)
            } catch (_: Exception) {
                // A transient read failure: the folder may still catch up on the next poll.
                lastClips
            }

            val newPaths = lastClips.map { it.path }.filterNot { it in knownLockPaths }
            if (newPaths.isNotEmpty()) {
                return LockOutcome(write, lastClips, newPaths, polls)
            }
            if (polls < attempts) delay(lockPollDelayMillis)
        }
        return LockOutcome(write, lastClips, emptyList(), polls)
    }
}
