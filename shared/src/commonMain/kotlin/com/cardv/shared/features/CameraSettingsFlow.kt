package com.cardv.shared.features

import com.cardv.shared.CameraClient
import com.cardv.shared.model.CameraStatus
import com.cardv.shared.model.VideoOp
import com.cardv.shared.protocol.CameraHungException
import com.cardv.shared.safety.SafetyLevel
import com.cardv.shared.safety.Setting
import com.cardv.shared.safety.SettingsCatalog
import com.cardv.shared.safety.WriteResult
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime

/**
 * Result of a `NEEDS_STANDBY` write.
 *
 * The flow is deliberately explicit about what happened to recording: it may have been stopped,
 * the setting may have been applied, and recording may have been restarted. [verified] is set for
 * SD formatting only; `null` means the camera did not answer the follow-up status reads.
 */
data class StandbySettingOutcome(
    val setting: String,
    val value: String,
    val result: WriteResult<Unit>,
    val recordingBefore: Boolean? = null,
    val stoppedRecording: Boolean = false,
    val resumedRecording: Boolean = false,
    val statusAfter: CameraStatus? = null,
    val verified: Boolean? = null,
) {
    val applied: Boolean get() = result is WriteResult.Applied<*>

    /** True when recording really was stopped and could not be restarted after the write. */
    val recordingLeftStopped: Boolean
        get() = stoppedRecording && !resumedRecording && result !is WriteResult.Hung
}

/**
 * The M6 settings flow for settings the camera refuses while recording (`NEEDS_STANDBY`).
 *
 * It follows docs/PLAN.md §4:
 * 1. read the current state and stop recording when needed;
 * 2. confirm that the camera really is in Standby (`709` is otherwise likely);
 * 3. send the setting through [CameraClient.setSetting], so the catalog still validates it;
 * 4. verify SD formatting (`SD0=READY`), then resume recording, which also re-applies the mic.
 *
 * A hung camera is never retried, and recording is not restarted after a hung write.
 */
class CameraSettings(
    private val client: CameraClient,
    private val pollDelayMillis: Long = 500,
    private val verifyAttempts: Int = 8,
) {
    init {
        require(pollDelayMillis >= 0) { "pollDelayMillis must not be negative" }
        require(verifyAttempts >= 1) { "verifyAttempts must be at least 1" }
    }

    suspend fun setNeedsStandby(
        setting: Setting,
        value: String,
        resumeRecording: Boolean = true,
    ): StandbySettingOutcome {
        val level = SettingsCatalog.levelOf(setting.name)
        if (level != SafetyLevel.NEEDS_STANDBY) {
            return StandbySettingOutcome(
                setting = setting.name,
                value = value,
                result = WriteResult.Refused(
                    reason = "use the direct settings write for ${level.name} settings",
                    setting = setting.name,
                    level = level,
                ),
            )
        }

        val before = try {
            client.status()
        } catch (hung: CameraHungException) {
            return StandbySettingOutcome(
                setting = setting.name,
                value = value,
                result = WriteResult.Hung(hung.message ?: "camera is hung"),
            )
        } catch (failure: Exception) {
            return StandbySettingOutcome(
                setting = setting.name,
                value = value,
                result = WriteResult.Failed(
                    "cannot read the camera state: ${failure.message ?: "unknown error"}",
                ),
            )
        }

        var stoppedRecording = false
        if (before.recording) {
            val stop = client.video(VideoOp.RECORD)
            if (stop !is WriteResult.Applied) {
                return StandbySettingOutcome(
                    setting = setting.name,
                    value = value,
                    result = stop,
                    recordingBefore = true,
                )
            }
            stoppedRecording = true

            val afterStop = readStatusOrNull()
            if (afterStop?.recording != false) {
                return StandbySettingOutcome(
                    setting = setting.name,
                    value = value,
                    result = WriteResult.Failed(
                        "the camera is still recording; the setting was not sent",
                    ),
                    recordingBefore = true,
                    stoppedRecording = true,
                    statusAfter = afterStop,
                )
            }
        }

        val result = client.setSetting(setting, value)

        var statusAfter: CameraStatus? = null
        var verified: Boolean? = null
        if (result is WriteResult.Applied && setting.name == SettingsCatalog.SD0.name) {
            var sawStatus = false
            for (attempt in 0 until verifyAttempts) {
                val status = readStatusOrNull()
                if (status != null) {
                    sawStatus = true
                    statusAfter = status
                    if (status.sdStatus.equals("READY", ignoreCase = true)) {
                        verified = true
                        break
                    }
                }
                if (attempt < verifyAttempts - 1) delay(pollDelayMillis)
            }
            if (verified == null && sawStatus) verified = false
        }

        var resumedRecording = false
        if (before.recording && resumeRecording && result !is WriteResult.Hung) {
            resumedRecording = client.video(VideoOp.RECORD) is WriteResult.Applied
        }

        if (statusAfter == null && result !is WriteResult.Hung) {
            statusAfter = readStatusOrNull()
        }

        return StandbySettingOutcome(
            setting = setting.name,
            value = value,
            result = result,
            recordingBefore = before.recording,
            stoppedRecording = stoppedRecording,
            resumedRecording = resumedRecording,
            statusAfter = statusAfter,
            verified = verified,
        )
    }

    private suspend fun readStatusOrNull(): CameraStatus? =
        try {
            client.status()
        } catch (_: Exception) {
            null
        }
}

/** Maps catalog settings to the fields of `Camera.*` so every UI shows the same current value. */
object SettingsValues {
    fun rawKey(setting: Setting): String = "Camera.Menu.${setting.name}"

    /** Current value as reported by the camera, or `null` when the camera did not include it. */
    fun currentValue(status: CameraStatus, setting: Setting): String? = when (setting.name) {
        SettingsCatalog.TIME_SETTINGS.name -> status.cameraTime?.let(::formatCameraTime)
        SettingsCatalog.VIDEO.name -> if (status.recording) "Recording" else "Standby"
        else -> status.value(rawKey(setting))
    }

    fun formatCameraTime(value: LocalDateTime): String {
        fun two(number: Int): String = number.toString().padStart(2, '0')
        return "${value.year}-${two(value.monthNumber)}-${two(value.dayOfMonth)} " +
            "${two(value.hour)}:${two(value.minute)}:${two(value.second)}"
    }
}
