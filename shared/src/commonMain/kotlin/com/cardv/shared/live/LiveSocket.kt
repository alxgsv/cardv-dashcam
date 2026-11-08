package com.cardv.shared.live

/**
 * Minimal blocking TCP socket, so the RTSP client is plain common code.
 *
 * The RTSP reader polls with a short timeout instead of depending on cancellation: `read`
 * returns 0 when no bytes arrived, -1 at end of stream, and throws [LiveException] on a real
 * socket error.
 */
interface LiveSocket {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun write(buffer: ByteArray, offset: Int, length: Int)

    fun close()
}

interface LiveSocketFactory {
    fun connect(
        host: String,
        port: Int,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): LiveSocket
}

internal expect fun defaultLiveSocketFactory(): LiveSocketFactory
