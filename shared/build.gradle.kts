import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

// BuildInfo.VERSION comes from the root version.xcconfig, the same file Android and Xcode use.
val generatedVersionDir = layout.buildDirectory.dir("generated/version/commonMain/kotlin")

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val versionName = rootProject.extra["cardvVersionName"] as String
    inputs.property("version", versionName)
    outputs.dir(generatedVersionDir)
    doLast {
        val file = generatedVersionDir.get()
            .file("com/cardv/shared/GeneratedBuildInfo.kt")
            .asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.cardv.shared

            // Generated from version.xcconfig by :shared:generateBuildInfo. Do not edit.
            internal object GeneratedBuildInfo {
                const val VERSION: String = "$versionName"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // One framework target per Apple platform; iosX64/macosX64 keep Intel Macs working.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
        macosArm64(),
        macosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        getByName("commonMain").kotlin.srcDir(
            generateBuildInfo.map { generatedVersionDir.get().asFile },
        )
        commonMain.dependencies {
            // HttpClientEngine and LocalDateTime appear in the public CameraClient API.
            api(libs.ktor.client.core)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

android {
    namespace = "com.cardv.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
