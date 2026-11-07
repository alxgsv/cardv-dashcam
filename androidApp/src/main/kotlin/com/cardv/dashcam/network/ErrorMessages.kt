package com.cardv.dashcam.network

import com.cardv.shared.protocol.CameraException
import com.cardv.shared.protocol.CameraHungException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException as JavaSocketTimeoutException
import java.net.UnknownHostException

/** Short, human copy for the connect screen; no addresses, ports or protocol codes. */
fun Throwable.friendlyCameraMessage(@Suppress("UNUSED_PARAMETER") baseUrl: String): String = when (this) {
    is CameraNetworkException -> "No dashcam Wi-Fi yet. Join the camera's network, then try again."
    is CameraHungException -> "The dashcam stopped responding. Restart the car, then try again."
    is CameraException -> "The camera answered in an unexpected way. Try reconnecting."
    is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException,
    is JavaSocketTimeoutException, is ConnectException, is UnknownHostException,
    -> "Can't reach the dashcam. Check that it is on and your phone is on its Wi-Fi."

    is IOException -> "The connection dropped. Reconnect to the camera's Wi-Fi and try again."
    else -> message ?: "Something went wrong. Try again."
}
