package com.cardv.shared.live

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_UNSPEC
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.ETIMEDOUT
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.POLLOUT
import platform.posix.SHUT_RDWR
import platform.posix.SO_ERROR
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockopt
import platform.posix.memset
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.socket
import platform.posix.strerror
import platform.posix.timeval
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString

/**
 * POSIX sockets on iOS and macOS.
 *
 * `SO_RCVTIMEO` makes reads poll instead of block forever, which is what lets [LiveStreamClient]
 * stop promptly and send keep-alives on one thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun defaultLiveSocketFactory(): LiveSocketFactory = PosixLiveSocketFactory()

@OptIn(ExperimentalForeignApi::class)
private class PosixLiveSocketFactory : LiveSocketFactory {
    override fun connect(
        host: String,
        port: Int,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): LiveSocket {
        memScoped {
            val hints = alloc<platform.posix.addrinfo>()
            memset(hints.ptr, 0, sizeOf<platform.posix.addrinfo>().convert())
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_STREAM
            hints.ai_protocol = IPPROTO_TCP

            val result = alloc<kotlinx.cinterop.CPointerVar<platform.posix.addrinfo>>()
            val resolved = getaddrinfo(host, port.toString(), hints.ptr, result.ptr)
            if (resolved != 0) {
                val detail = strerror(resolved)?.toKString() ?: "unknown error"
                throw LiveException("Can't resolve $host: $detail")
            }
            var closest = "no address"
            try {
                var current = result.value
                while (current != null) {
                    val info = current.pointed
                    val descriptor = socket(info.ai_family, info.ai_socktype, info.ai_protocol)
                    if (descriptor < 0) {
                        closest = strerror(errno)?.toKString() ?: "socket failed"
                    } else {
                        val connectError = connectWithTimeout(
                            descriptor,
                            info.ai_addr,
                            info.ai_addrlen.toInt(),
                            connectTimeoutMillis,
                        )
                        if (connectError == 0) {
                            configure(descriptor, readTimeoutMillis)
                            return PosixLiveSocket(descriptor)
                        }
                        closest = strerror(connectError)?.toKString() ?: "connection failed"
                        close(descriptor)
                    }
                    current = info.ai_next
                }
            } finally {
                freeaddrinfo(result.value)
            }
            throw LiveException("Can't connect to $host:$port ($closest)")
        }
    }

    private fun connectWithTimeout(
        descriptor: Int,
        address: kotlinx.cinterop.CPointer<platform.posix.sockaddr>?,
        addressLength: Int,
        timeoutMillis: Int,
    ): Int {
        val flags = fcntl(descriptor, F_GETFL, 0)
        if (flags >= 0) fcntl(descriptor, F_SETFL, flags or O_NONBLOCK)

        val connected = platform.posix.connect(
            descriptor,
            address,
            addressLength.convert(),
        )
        if (connected == 0) {
            restoreBlocking(descriptor, flags)
            return 0
        }
        val connectError = errno
        if (connectError != EINPROGRESS) {
            restoreBlocking(descriptor, flags)
            return connectError
        }

        val ready = memScoped {
            val pollDescriptor = alloc<pollfd>()
            pollDescriptor.fd = descriptor
            pollDescriptor.events = POLLOUT.toShort()
            poll(pollDescriptor.ptr, 1u, timeoutMillis)
        }
        if (ready <= 0) {
            restoreBlocking(descriptor, flags)
            return if (ready == 0) ETIMEDOUT else errno
        }

        val optionError = memScoped {
            val errorOut = alloc<IntVar>()
            val errorLength = alloc<UIntVar>()
            errorLength.value = sizeOf<IntVar>().toUInt()
            val result = getsockopt(
                descriptor,
                SOL_SOCKET,
                SO_ERROR,
                errorOut.ptr,
                errorLength.ptr,
            )
            if (result != 0) errno else errorOut.value
        }
        restoreBlocking(descriptor, flags)
        return optionError
    }

    private fun restoreBlocking(descriptor: Int, flags: Int) {
        if (flags >= 0) fcntl(descriptor, F_SETFL, flags)
    }

    private fun configure(descriptor: Int, readTimeoutMillis: Int) {
        memScoped {
            val one = alloc<IntVar>()
            one.value = 1
            setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())

            val timeout = alloc<timeval>()
            timeout.tv_sec = (readTimeoutMillis / 1_000).convert()
            timeout.tv_usec = ((readTimeoutMillis % 1_000) * 1_000).convert()
            setsockopt(
                descriptor,
                SOL_SOCKET,
                SO_RCVTIMEO,
                timeout.ptr,
                sizeOf<timeval>().convert(),
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PosixLiveSocket(private var descriptor: Int) : LiveSocket {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (descriptor < 0) return -1
        while (true) {
            val count = buffer.usePinned {
                recv(descriptor, it.addressOf(offset), length.convert(), 0)
            }
            if (count > 0) return count.toInt()
            if (count == 0L) return -1
            val code = errno
            when (code) {
                EINTR -> continue
                EAGAIN, EWOULDBLOCK -> return 0
                else -> throw LiveException(
                    "Live socket read failed: ${strerror(code)?.toKString() ?: code}",
                )
            }
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (descriptor < 0) throw LiveException("The live connection is closed.")
        var sent = 0
        while (sent < length) {
            val count = buffer.usePinned {
                send(descriptor, it.addressOf(offset + sent), (length - sent).convert(), 0)
            }
            if (count > 0) {
                sent += count.toInt()
                continue
            }
            val code = errno
            if (code == EINTR) continue
            throw LiveException("Live socket write failed: ${strerror(code)?.toKString() ?: code}")
        }
    }

    override fun close() {
        val current = descriptor
        if (current < 0) return
        descriptor = -1
        runCatching { shutdown(current, SHUT_RDWR) }
        runCatching { close(current) }
    }
}
