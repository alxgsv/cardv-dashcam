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

/** Turn protocol/transport failures into something a driver can act on. */
fun Throwable.friendlyCameraMessage(baseUrl: String): String = when (this) {
    is CameraNetworkException -> message ?: "The dashcam Wi-Fi is not available."
    is CameraHungException -> "The dashcam stopped responding. Restart the car to reset it."
    is CameraException -> message ?: "The dashcam returned an unexpected answer."
    is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException,
    is JavaSocketTimeoutException, is ConnectException, is UnknownHostException,
    -> "Cannot reach the dashcam at $baseUrl. Join the camera's Wi-Fi (it has no internet) and retry."

    is IOException -> "Network error while talking to $baseUrl: ${message ?: "connection failed"}."
    else -> message ?: this::class.simpleName ?: "Unexpected error"
}
