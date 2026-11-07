package com.cardv.shared.features

import com.cardv.shared.CameraClient
import com.cardv.shared.safety.WriteResult
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

/**
 * What happened during a clock sync.
 *
 * [driftSeconds] is phone time minus camera time before the write; positive means the camera was
 * behind. It is `null` when the camera did not report a time.
 */
data class ClockSyncOutcome(
    val phoneTime: LocalDateTime,
    val cameraTimeBefore: LocalDateTime?,
    val driftSeconds: Long?,
    val skipped: Boolean,
    val result: WriteResult<Unit>?,
) {
    val applied: Boolean get() = result is WriteResult.Applied<*>
}

/**
 * Automatic camera clock sync (PROTOCOL.md: the camera clock drifts or resets to 2022, so it should
 * be set on every connect).
 *
 * The write itself goes through [CameraClient.syncClock] and therefore through the shared
 * `CommandQueue` with its timeout and health check. This class only decides whether a write is
 * needed and describes the drift for the UI.
 */
class ClockSync(
    private val client: CameraClient,
    private val now: () -> LocalDateTime = {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    },
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    /**
     * Sync only when the camera clock differs from the phone by more than [toleranceSeconds]
     * (or when the camera did not report a time).
     */
    suspend fun syncIfNeeded(
        cameraTime: LocalDateTime?,
        toleranceSeconds: Long = 30,
    ): ClockSyncOutcome {
        require(toleranceSeconds >= 0) { "toleranceSeconds must not be negative" }
        val phone = now()
        val drift = cameraTime?.let { driftSeconds(phone, it) }

        if (drift != null && abs(drift) <= toleranceSeconds) {
            return ClockSyncOutcome(
                phoneTime = phone,
                cameraTimeBefore = cameraTime,
                driftSeconds = drift,
                skipped = true,
                result = null,
            )
        }
        return write(phone, cameraTime, drift)
    }

    /** Always set the camera clock, which is what a connect flow should do. */
    suspend fun sync(cameraTime: LocalDateTime? = null): ClockSyncOutcome {
        val phone = now()
        return write(phone, cameraTime, cameraTime?.let { driftSeconds(phone, it) })
    }

    private suspend fun write(
        phone: LocalDateTime,
        cameraTime: LocalDateTime?,
        drift: Long?,
    ): ClockSyncOutcome = ClockSyncOutcome(
        phoneTime = phone,
        cameraTimeBefore = cameraTime,
        driftSeconds = drift,
        skipped = false,
        result = client.syncClock(phone),
    )

    private fun driftSeconds(phone: LocalDateTime, camera: LocalDateTime): Long {
        val phoneMillis = phone.toInstant(zone).toEpochMilliseconds()
        val cameraMillis = camera.toInstant(zone).toEpochMilliseconds()
        return (phoneMillis - cameraMillis) / 1_000
    }
}
