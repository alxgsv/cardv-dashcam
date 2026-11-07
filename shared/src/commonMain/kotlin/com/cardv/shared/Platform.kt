package com.cardv.shared

/** Human-readable name of the platform the shared core is running on. */
expect val platformName: String

/** Swift-friendly access to [platformName] (top-level properties are awkward to import). */
object PlatformInfo {
    val name: String get() = platformName
}
