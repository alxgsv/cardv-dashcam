package com.cardv.shared

import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Starts `tools/fake-camera/fake_camera.py` on a free port for JVM integration tests.
 *
 * The script is not a real media server, but it reproduces the camera's HTTP quirks, lists and
 * Range behaviour closely enough to test the shared core end to end.
 */
internal class FakeCameraProcess(vararg extraArgs: String) : AutoCloseable {
    private val process: Process
    val port: Int
    val baseUrl: String

    init {
        assumeTrue("python3 is required for fake-camera integration tests", pythonAvailable())
        port = ServerSocket(0).use { it.localPort }
        baseUrl = "http://127.0.0.1:$port"

        val script = repoRoot.resolve("tools/fake-camera/fake_camera.py")
        check(script.isFile) { "fake camera script not found at $script" }
        val command = mutableListOf(
            "python3", script.absolutePath,
            "--host", "127.0.0.1",
            "--port", port.toString(),
        )
        command.addAll(extraArgs)

        process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        // Drain the pipe so verbose fake-camera output can never block the process.
        Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { } }
        }.apply { isDaemon = true }.start()
        try {
            awaitReady()
        } catch (failure: Throwable) {
            process.destroyForcibly()
            throw failure
        }
    }

    private fun awaitReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                error("fake camera exited with code ${process.exitValue()}")
            }
            try {
                Socket("127.0.0.1", port).use { return }
            } catch (_: IOException) {
                Thread.sleep(50)
            }
        }
        error("fake camera did not listen on port $port within 10 s")
    }

    override fun close() {
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
    }

    private companion object {
        val repoRoot: File by lazy {
            var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (directory != null) {
                val current: File = directory
                if (File(current, "tools/fake-camera/fake_camera.py").isFile) return@lazy current
                directory = current.parentFile
            }
            error("repository root not found from ${System.getProperty("user.dir")}")
        }

        fun pythonAvailable(): Boolean = runCatching {
            val process = ProcessBuilder("python3", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }
}
