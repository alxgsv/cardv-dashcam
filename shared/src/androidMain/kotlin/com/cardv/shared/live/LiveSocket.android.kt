package com.cardv.shared.live

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Java sockets for Android, with the same poll-with-timeout contract as the Apple socket. */
internal actual fun defaultLiveSocketFactory(): LiveSocketFactory = JavaLiveSocketFactory()

private class JavaLiveSocketFactory : LiveSocketFactory {
    override fun connect(
        host: String,
        port: Int,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): LiveSocket {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            socket.soTimeout = readTimeoutMillis
            return JavaLiveSocket(socket)
        } catch (error: IOException) {
            runCatching { socket.close() }
            throw LiveException("Can't connect to $host:$port (${error.message ?: "error"})", error)
        }
    }
}

private class JavaLiveSocket(private val socket: Socket) : LiveSocket {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        socket.getInputStream().read(buffer, offset, length)
    } catch (_: SocketTimeoutException) {
        0
    } catch (error: IOException) {
        throw LiveException("Live socket read failed: ${error.message ?: "error"}", error)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        try {
            socket.getOutputStream().write(buffer, offset, length)
            socket.getOutputStream().flush()
        } catch (error: IOException) {
            throw LiveException("Live socket write failed: ${error.message ?: "error"}", error)
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
