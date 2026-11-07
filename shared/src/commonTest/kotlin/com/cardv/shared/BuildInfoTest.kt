package com.cardv.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test
    fun versionLooksLikeSemver() {
        assertTrue(
            BuildInfo.VERSION.matches(Regex("""\d+\.\d+\.\d+""")),
            "unexpected version: ${BuildInfo.VERSION}",
        )
    }

    @Test
    fun appNameIsNotBlank() {
        assertTrue(BuildInfo.APP_NAME.isNotBlank())
    }

    @Test
    fun platformNameIsNotBlank() {
        assertTrue(platformName.isNotBlank())
    }
}
