package com.cardv.shared.protocol

/** Base type for every failure that comes from the camera or from its protocol. */
open class CameraException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The camera answered with HTTP 200 but the first line was a non-zero result code.
 *
 * This is the normal way the camera reports errors, so callers must not treat HTTP status alone as
 * success. [detail] is the human-readable line the camera sent after the code.
 */
class CameraResultException(
    val result: ResultCode,
    val detail: String,
) : CameraException("camera returned $result: ${detail.ifBlank { "no detail" }}")

/** HTTP transport failed or returned an unexpected status. */
class CameraHttpException(
    val status: Int,
    message: String,
) : CameraException("HTTP $status: $message")

/** The camera answered, but the body did not have the expected shape. */
class MalformedResponseException(message: String) : CameraException(message)

/**
 * The command queue has marked the camera as hung.
 *
 * After a write whose follow-up health check failed, retrying is unsafe: the camera may stay
 * unresponsive until the car (and therefore the camera) is restarted.
 */
class CameraHungException(
    message: String = "The dashcam stopped responding. Restart the car to reset it.",
) : CameraException(message)
