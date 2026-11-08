package com.cardv.shared.apple

import com.cardv.shared.CameraClient
import com.cardv.shared.CameraClientConfig
import com.cardv.shared.features.CameraControls
import com.cardv.shared.features.CameraSettings
import com.cardv.shared.features.ClockSync
import com.cardv.shared.features.LockOutcome
import com.cardv.shared.safety.WriteResult

/**
 * Swift-facing factories.
 *
 * Kotlin default arguments are not exported to Swift, so every constructor whose defaults matter
 * would otherwise need an awkward argument from Swift: `CameraClient` would require an
 * `HttpClientEngine`, and `CameraControls`, `CameraSettings` and `ClockSync` would require their
 * tuning parameters. Keeping the construction here means the Apple apps and the Android app use
 * exactly the same safety defaults.
 */
object AppleCamera {
    fun create(baseUrl: String, allowExperimental: Boolean): CameraClient =
        CameraClient(
            baseUrl = baseUrl,
            config = CameraClientConfig(
                micReapplyDelayMillis = 0,
                allowExperimental = allowExperimental,
            ),
        )

    fun controls(client: CameraClient): CameraControls = CameraControls(client)

    fun settings(client: CameraClient): CameraSettings = CameraSettings(client)

    fun clock(client: CameraClient): ClockSync = ClockSync(client)

    /** [CameraControls.lockCurrentClip] with its tuned re-poll defaults, which Swift cannot omit. */
    suspend fun lockCurrentClip(
        controls: CameraControls,
        knownLockPaths: Set<String>,
    ): LockOutcome = controls.lockCurrentClip(knownLockPaths)
}

/** One [WriteResult] flattened for Swift, which cannot exhaustively match the Kotlin sealed class. */
data class AppleWriteOutcome(
    val applied: Boolean,
    val hung: Boolean,
    /** A message to show when the write was not applied; `null` on success. */
    val failure: String?,
)

object AppleWriteResult {
    fun outcome(result: WriteResult<Unit>): AppleWriteOutcome = when (result) {
        is WriteResult.Applied -> AppleWriteOutcome(applied = true, hung = false, failure = null)
        is WriteResult.Rejected -> AppleWriteOutcome(
            applied = false,
            hung = false,
            failure = "Camera refused: ${result.detail} (${result.result.code})",
        )

        is WriteResult.Refused -> AppleWriteOutcome(
            applied = false,
            hung = false,
            failure = result.reason,
        )

        is WriteResult.Failed -> AppleWriteOutcome(
            applied = false,
            hung = false,
            failure = result.reason,
        )

        is WriteResult.Hung -> AppleWriteOutcome(
            applied = false,
            hung = true,
            failure = "The camera stopped responding. Restart the car to reset it.",
        )
    }

    /** Like [outcome], but keeps a missing result (`null`) distinguishable from a refusal. */
    fun outcomeOrNull(result: WriteResult<Unit>?): AppleWriteOutcome? = result?.let(::outcome)
}
