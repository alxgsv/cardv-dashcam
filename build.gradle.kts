import java.io.File

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// One version file feeds Gradle, the shared BuildInfo and Xcode (through Signing.xcconfig).
val versionFile = rootProject.file("version.xcconfig")

fun readVersionFile(file: File): Map<String, String> =
    file.readLines()
        .map { it.substringBefore("//").trim() }
        .filter { it.contains('=') }
        .associate { line ->
            line.substringBefore('=').trim() to line.substringAfter('=').trim()
        }

val version = readVersionFile(versionFile)
fun versionValue(key: String): String =
    version[key]?.takeIf { it.isNotBlank() }
        ?: error("Missing $key in ${versionFile.path}")

extra["cardvVersionName"] = versionValue("MARKETING_VERSION")
extra["cardvBuildNumber"] = versionValue("CURRENT_PROJECT_VERSION")
extra["cardvAndroidVersionCode"] = versionValue("ANDROID_VERSION_CODE").toInt()
