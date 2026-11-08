package com.cardv.dashcam.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The camera Wi-Fi is missing, or the app cannot use it. The message is shown to the user. */
class CameraNetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Explicit no-internet Wi-Fi handling (docs/DESIGN.md §5).
 *
 * The dashcam has no internet route, so Android would otherwise prefer mobile data. For the real
 * camera address this class:
 *  1. finds the connected Wi-Fi whose link address is in `192.168.42.0/24` (or asks Android to
 *     keep a Wi-Fi network available), and
 *  2. binds the process to it for the duration of the session, and
 *  3. gives every OkHttp client a `Network.socketFactory`, so requests use that network even when
 *     process binding is unavailable (for example in a background worker after a restart).
 *
 * A debug/LAN override deliberately does not bind anything, so the fake camera on the emulator's
 * `10.0.2.2` works through the normal route.
 */
class CameraNetwork(context: Context) {
    private val connectivity: ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    suspend fun connect(baseUrl: String, bindProcess: Boolean): CameraNetworkLease {
        if (!CameraAddress.isCameraHost(baseUrl)) {
            // Debug / LAN override: normal route, no camera Wi-Fi to find.
            return CameraNetworkLease(network = null, onClose = null)
        }

        val existing = findCameraNetwork()
        var requested: RequestedNetwork? = null
        val network = existing ?: run {
            requested = requestCameraNetwork()
            requested?.network
        }
        if (network == null) {
            requested?.close()
            val host = CameraAddress.host(baseUrl) ?: "192.168.42.1"
            throw CameraNetworkException(
                "No dashcam Wi-Fi found. Join the camera's Wi-Fi network (it has no internet) " +
                    "and try again. The camera should answer at http://$host.",
            )
        }

        if (bindProcess && !connectivity.bindProcessToNetwork(network)) {
            requested?.close()
            throw CameraNetworkException(
                "Android refused to route traffic over the dashcam Wi-Fi. Disconnect mobile data " +
                    "or reconnect to the camera's Wi-Fi and try again.",
            )
        }

        return CameraNetworkLease(
            network = network,
            onClose = {
                requested?.close()
                if (bindProcess) connectivity.bindProcessToNetwork(null)
            },
        )
    }

    /** The connected Wi-Fi network with a `192.168.42.x` address, if there is one. */
    @Suppress("DEPRECATION") // allNetworks is still the only way to inspect every Wi-Fi network.
    fun findCameraNetwork(): Network? = try {
        connectivity.allNetworks.firstOrNull(::isCameraNetwork)
    } catch (_: SecurityException) {
        null
    }

    private fun isCameraNetwork(network: Network): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        val linkProperties = connectivity.getLinkProperties(network) ?: return false
        return linkProperties.linkAddresses.any { linkAddress ->
            linkAddress.address.hostAddress?.startsWith(CAMERA_SUBNET_PREFIX) == true
        }
    }

    /**
     * Ask Android to make a Wi-Fi network available, then poll briefly for the camera subnet.
     * `requestNetwork` cannot join a network by itself (that needs `WifiNetworkSpecifier`), so a
     * timeout means the user has to connect to the camera's Wi-Fi manually.
     *
     * The callback is deliberately kept until the lease closes: it keeps Android from dropping a
     * no-internet Wi-Fi that only exists because the app asked for it.
     */
    private suspend fun requestCameraNetwork(): RequestedNetwork? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {}
        try {
            connectivity.requestNetwork(request, callback)
        } catch (_: SecurityException) {
            return null
        }

        val found = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
                var network: Network? = findCameraNetwork()
                while (network == null) {
                    delay(POLL_INTERVAL_MILLIS)
                    network = findCameraNetwork()
                }
                network
            }
        } catch (_: SecurityException) {
            null
        }

        if (found == null) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            return null
        }
        return RequestedNetwork(found, callback, connectivity)
    }

    private class RequestedNetwork(
        val network: Network,
        private val callback: ConnectivityManager.NetworkCallback,
        private val connectivity: ConnectivityManager,
    ) : Closeable {
        override fun close() {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    private companion object {
        const val CAMERA_SUBNET_PREFIX = "192.168.42."
        const val REQUEST_TIMEOUT_MILLIS = 8_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}

/**
 * An active camera network (possibly none for a debug override), with OkHttp clients bound to it.
 * [close] releases the process binding; background workers create their own lease.
 */
class CameraNetworkLease internal constructor(
    val network: Network?,
    private val onClose: (() -> Unit)? = null,
) : Closeable {
    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(ContentTypeInterceptor)

        // Per-connection binding: this keeps working for WorkManager downloads and ExoPlayer even
        // when the process binding is gone.
        network?.let { builder.socketFactory(it.socketFactory) }
        builder.build()
    }

    fun okHttpClient(): OkHttpClient = client

    override fun close() {
        onClose?.invoke()
    }
}

/**
 * The camera labels MP4 files `Content-Type: video/mpeg` (docs/PROTOCOL.md). Media3 and some
 * decoders trust that header; the bytes are MP4. Rewrite it for `.MP4` URLs only.
 */
private object ContentTypeInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        val contentType = response.header("Content-Type").orEmpty()
        val isMp4 = chain.request().url.encodedPath.endsWith(".MP4", ignoreCase = true)
        if (isMp4 && contentType.startsWith("video/mpeg", ignoreCase = true)) {
            return response.newBuilder()
                .header("Content-Type", "video/mp4")
                .build()
        }
        return response
    }
}
