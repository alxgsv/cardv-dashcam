package com.cardv.shared.protocol

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun defaultHttpClientEngine(): HttpClientEngine = Darwin.create()
