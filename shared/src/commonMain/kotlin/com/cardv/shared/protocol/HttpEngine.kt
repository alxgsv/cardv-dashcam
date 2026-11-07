package com.cardv.shared.protocol

import io.ktor.client.engine.HttpClientEngine

/**
 * Platform default engine: OkHttp on Android, NSURLSession (Darwin) on iOS/macOS.
 *
 * Tests inject their own engine (MockEngine, or OkHttp against the fake camera), so this is only
 * used by production callers that construct `CameraClient()` with no arguments.
 */
internal expect fun defaultHttpClientEngine(): HttpClientEngine
