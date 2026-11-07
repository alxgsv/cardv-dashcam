package com.cardv.shared.safety

import com.cardv.shared.protocol.CameraHungException
import com.cardv.shared.protocol.CameraResultException
import com.cardv.shared.protocol.ResultCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandQueueTest {
    @Test
    fun defaultWriteTimeoutIsFifteenSeconds() {
        assertEquals(15_000, CommandQueueConfig().writeTimeoutMillis)
        assertEquals(15_000, com.cardv.shared.CameraClientConfig().writeTimeoutMillis)
    }

    @Test
    fun serializesConcurrentCommands() = runTest {
        val queue = CommandQueue(healthCheck = { true })
        var active = 0
        var maxActive = 0
        val jobs = (1..4).map {
            launch {
                queue.read {
                    active++
                    maxActive = maxOf(maxActive, active)
                    delay(100)
                    active--
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1, maxActive)
        assertEquals(0, active)
    }

    @Test
    fun successfulWriteIsFollowedByHealthCheck() = runTest {
        var healthChecks = 0
        val queue = CommandQueue(healthCheck = { healthChecks++; true })

        val result = queue.write { "written" }

        val applied = assertIs<WriteResult.Applied<String>>(result)
        assertEquals("written", applied.value)
        assertEquals(1, healthChecks)
        assertFalse(queue.isHung)
    }

    @Test
    fun nonZeroResultCodeIsRejectedButDoesNotHangWhenHealthy() = runTest {
        var healthChecks = 0
        val queue = CommandQueue(healthCheck = { healthChecks++; true })

        val result = queue.write {
            throw CameraResultException(ResultCode.REFUSED, "Refused while recording")
        }

        val rejected = assertIs<WriteResult.Rejected>(result)
        assertEquals(ResultCode.REFUSED, rejected.result)
        assertEquals(1, healthChecks)
        assertFalse(queue.isHung)
    }

    @Test
    fun timeoutMarksCameraHungAndStopsRetrying() = runTest {
        val queue = CommandQueue(
            config = CommandQueueConfig(
                readTimeoutMillis = 100,
                writeTimeoutMillis = 100,
                healthTimeoutMillis = 50,
            ),
            healthCheck = { false },
        )
        var blocksRun = 0

        val first = queue.write {
            blocksRun++
            delay(10_000)
        }
        assertIs<WriteResult.Hung>(first)
        assertTrue(queue.isHung)

        val second = queue.write { blocksRun++ }
        assertIs<WriteResult.Hung>(second)
        assertEquals(1, blocksRun)

        val readFailure = runCatching { queue.read { 1 } }.exceptionOrNull()
        assertIs<CameraHungException>(readFailure)
    }

    @Test
    fun timedOutWriteThatStillPassesHealthIsOnlyFailed() = runTest {
        val queue = CommandQueue(
            config = CommandQueueConfig(
                readTimeoutMillis = 100,
                writeTimeoutMillis = 100,
                healthTimeoutMillis = 50,
            ),
            healthCheck = { true },
        )

        val result = queue.write { delay(10_000) }

        assertIs<WriteResult.Failed>(result)
        assertFalse(queue.isHung)
    }

    @Test
    fun failedHealthAfterRejectedWriteStillHangs() = runTest {
        val queue = CommandQueue(healthCheck = { false })

        val result = queue.write {
            throw CameraResultException(ResultCode.REFUSED, "Refused")
        }

        assertIs<WriteResult.Hung>(result)
        assertTrue(queue.isHung)
    }

    @Test
    fun callerCancellationDoesNotCancelAWriteMidFlight() = runTest {
        var finished = false
        var healthChecks = 0
        val queue = CommandQueue(
            config = CommandQueueConfig(
                readTimeoutMillis = 100,
                writeTimeoutMillis = 10_000,
                healthTimeoutMillis = 1_000,
            ),
            healthCheck = { healthChecks++; true },
        )

        val job = launch {
            queue.write {
                delay(1_000)
                finished = true
            }
        }
        advanceTimeBy(100)
        job.cancel()
        advanceUntilIdle()

        assertTrue(finished, "write should finish even though the caller navigated away")
        assertEquals(1, healthChecks)
    }
}
