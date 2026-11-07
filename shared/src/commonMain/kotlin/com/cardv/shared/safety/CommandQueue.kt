package com.cardv.shared.safety

import com.cardv.shared.protocol.CameraHungException
import com.cardv.shared.protocol.CameraResultException
import com.cardv.shared.protocol.ResultCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile

data class CommandQueueConfig(
    val readTimeoutMillis: Long = 10_000,
    /** The firmware itself needs up to 6 s; 15 s leaves room without hanging the UI forever. */
    val writeTimeoutMillis: Long = 15_000,
    /** A follow-up `get Camera.Menu.EV` should answer immediately; 5 s is already generous. */
    val healthTimeoutMillis: Long = 5_000,
) {
    init {
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(writeTimeoutMillis > 0) { "writeTimeoutMillis must be positive" }
        require(healthTimeoutMillis > 0) { "healthTimeoutMillis must be positive" }
    }
}

/**
 * Outcome of a camera write.
 *
 * The important distinction is [Hung]: the write may or may not have reached the camera, but the
 * follow-up health check failed. Retrying is unsafe, so the UI must ask for a restart.
 */
sealed class WriteResult<out T> {
    data class Applied<T>(val value: T) : WriteResult<T>()

    /** The camera answered with a non-zero result code (for example 709 while recording). */
    data class Rejected(val result: ResultCode, val detail: String) : WriteResult<Nothing>()

    /** The safety catalog blocked the write before anything was sent. */
    data class Refused(
        val reason: String,
        val setting: String? = null,
        val level: SafetyLevel? = null,
    ) : WriteResult<Nothing>()

    /** A health check after a write failed: do not retry until the camera is restarted. */
    data class Hung(val reason: String) : WriteResult<Nothing>()

    /** The write failed on the wire, but the camera still answered the health check. */
    data class Failed(val reason: String) : WriteResult<Nothing>()
}

/**
 * Serializes every `Config.cgi` command and implements the camera safety rules:
 *
 * 1. one command in flight (a [Mutex]);
 * 2. writes have a generous timeout and are not cancelled when the caller navigates away
 *    ([NonCancellable] around the network call);
 * 3. after every write, `get Camera.Menu.EV` is checked. If it fails, the queue latches
 *    [isHung] and refuses further writes instead of retrying;
 * 4. file downloads and RTSP do not go through this queue.
 */
class CommandQueue(
    private val config: CommandQueueConfig = CommandQueueConfig(),
    private val healthCheck: suspend () -> Boolean,
) {
    private val mutex = Mutex()

    @Volatile
    var isHung: Boolean = false
        private set

    /**
     * Run a read command. Reads are serialized too, but unlike writes they may be cancelled by the
     * caller — a cancelled read cannot leave a setting half-applied.
     */
    suspend fun <T> read(
        timeoutMillis: Long = config.readTimeoutMillis,
        block: suspend () -> T,
    ): T {
        if (isHung) throw CameraHungException()
        return mutex.withLock {
            if (isHung) throw CameraHungException()
            withTimeout(timeoutMillis) { block() }
        }
    }

    /**
     * Run a write command with the safety rules above.
     *
     * The block is expected to perform exactly one `action=set`. It runs under [NonCancellable], so
     * a user leaving the screen cannot cancel a partly-sent write; only the internal timeout can.
     */
    suspend fun <T> write(block: suspend () -> T): WriteResult<T> {
        if (isHung) return WriteResult.Hung("camera already marked hung")
        return mutex.withLock {
            if (isHung) return@withLock WriteResult.Hung("camera already marked hung")

            var produced: T? = null
            var hasValue = false
            var failure: WriteResult<Nothing>? = null

            try {
                val value = withContext(NonCancellable) {
                    withTimeout(config.writeTimeoutMillis) { block() }
                }
                produced = value
                hasValue = true
            } catch (e: CameraResultException) {
                failure = WriteResult.Rejected(e.result, e.detail)
            } catch (e: TimeoutCancellationException) {
                failure = WriteResult.Failed(
                    "write timed out after ${config.writeTimeoutMillis} ms",
                )
            } catch (e: CancellationException) {
                // NonCancellable keeps user navigation from reaching here; this is a defensive branch.
                failure = WriteResult.Failed("write was cancelled: ${e.message ?: "no detail"}")
            } catch (e: Exception) {
                failure = WriteResult.Failed(e.message ?: e::class.simpleName ?: "write failed")
            }

            // Health check after EVERY write, including rejected ones: a 709 can still leave the
            // command server busy in some firmware builds.
            val healthy = try {
                withContext(NonCancellable) {
                    withTimeout(config.healthTimeoutMillis) { healthCheck() }
                }
            } catch (_: Exception) {
                false
            }

            if (!healthy) {
                isHung = true
                return@withLock WriteResult.Hung(
                    "health check failed after write; restart the camera before trying again",
                )
            }

            if (!hasValue) return@withLock failure ?: WriteResult.Failed("write failed")
            @Suppress("UNCHECKED_CAST")
            WriteResult.Applied(produced as T)
        }
    }
}
