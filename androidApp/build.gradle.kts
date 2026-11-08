import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Release signing material never lives in the repository. Put the four values in the
// gitignored local.properties, or set the CARDV_RELEASE_* environment variables (CI does
// the latter). Without them, assembleRelease still works but produces an unsigned APK.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun releaseValue(localKey: String, environmentKey: String): String? =
    System.getenv(environmentKey)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(localKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseValue("cardv.release.storeFile", "CARDV_RELEASE_STORE_FILE")
val releaseStorePassword = releaseValue("cardv.release.storePassword", "CARDV_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseValue("cardv.release.keyAlias", "CARDV_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseValue("cardv.release.keyPassword", "CARDV_RELEASE_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

tasks.register("requireReleaseSigning") {
    doLast {
        check(releaseSigningReady) {
            "Release signing is not configured. Set CARDV_RELEASE_STORE_FILE, " +
                "CARDV_RELEASE_STORE_PASSWORD, CARDV_RELEASE_KEY_ALIAS and " +
                "CARDV_RELEASE_KEY_PASSWORD (or the cardv.release.* values in local.properties)."
        }
    }
}

android {
    namespace = "com.cardv.dashcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cardv.dashcam"
        minSdk = 26
        targetSdk = 36
        versionCode = rootProject.extra["cardvAndroidVersionCode"] as Int
        versionName = rootProject.extra["cardvVersionName"] as String
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)

    // M2/M4: streaming playback with seeking, live RTSP, explicit network binding.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
