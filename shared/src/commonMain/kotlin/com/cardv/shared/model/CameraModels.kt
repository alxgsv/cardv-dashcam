package com.cardv.shared.model

import kotlinx.datetime.LocalDateTime

/**
 * Folder names are case-exact. `Lock` (wrong case) silently makes the camera return the loop
 * list, so never build these names by hand — use this enum.
 */
enum class Folder(
    val property: String,
    val directory: String,
    val isVideo: Boolean,
) {
    /** Loop recordings in `/SD/MOVIE`. This is also the camera's default/fallback list. */
    DCIM("DCIM", "/SD/MOVIE", true),

    /** Automatic G-sensor events in `/SD/SOS`. */
    SOS("SOS", "/SD/SOS", true),

    /** Manually locked clips in `/SD/LOCK`, created by `Video=rec_emer`. */
    LOCK("LOCK", "/SD/LOCK", true),

    /** Snapshots in `/SD/PHOTO`, created by `Video=capture`. */
    PHOTO("Photo", "/SD/PHOTO", false),
    ;

    companion object {
        val DEFAULT: Folder = DCIM

        /** Exact, case-sensitive lookup. Returns `null` for unknown or wrongly-cased names. */
        fun fromProperty(value: String): Folder? = entries.firstOrNull { it.property == value }
    }
}

/**
 * One file from `action=dir`.
 *
 * [sizeBytes] is the camera's listing size, rounded up to 32 KB clusters; the real size comes from
 * `Content-Range` when downloading. [timestamp] is camera-local wall time.
 */
data class Clip(
    val path: String,
    val sizeBytes: Long,
    val timestamp: LocalDateTime?,
    val attribute: String? = null,
    val format: String? = null,
    val resolution: String? = null,
    val fps: Int? = null,
    val durationSeconds: Double? = null,
) {
    /** File name without the `/SD/...` directory. */
    val name: String get() = path.substringAfterLast('/')

    /** Directory part of the listed path, for example `/SD/MOVIE`. */
    val directory: String get() = path.substringBeforeLast('/', "")

    val isVideo: Boolean
        get() = format?.equals("MP4", ignoreCase = true) == true ||
            name.endsWith(".MP4", ignoreCase = true) ||
            name.endsWith(".MOV", ignoreCase = true)
}

/** Healthy answer to the two cheap reads used after a write. */
data class Health(
    val ev: String,
    val mode: String?,
    val recording: Boolean,
) {
    val isRecording: Boolean get() = recording
}

/**
 * Typed view over `action=get&property=Camera.*`.
 *
 * Several fields stay strings on purpose: the firmware reports values such as `MIDDLE` or `EV0`
 * that are not stable across models, and the UI can show them verbatim.
 */
data class CameraStatus(
    val firmware: String? = null,
    val model: String? = null,
    val sdStatus: String? = null,
    val freeMb: Long? = null,
    val totalMb: Long? = null,
    val cameraTime: LocalDateTime? = null,
    val recording: Boolean = false,
    val mode: String? = null,
    val recFileName: String? = null,
    val dbFileNum: String? = null,
    val fileTotal: Long? = null,
    val isStreaming: Boolean = false,
    val ev: String? = null,
    val awb: String? = null,
    val dateLogoStamp: String? = null,
    val soundRecord: String? = null,
    val videoClipTime: String? = null,
    val raw: Map<String, String> = emptyMap(),
) {
    fun value(key: String): String? = raw[key]
}

/** Live RTSP streams. `AV2` is video plus AAC audio and is the app default. */
enum class LiveStream(val path: String, val description: String) {
    V1("liveRTSP/v1", "video only"),
    AV2("liveRTSP/av2", "video + AAC audio"),
    AV4("liveRTSP/av4", "video + L16 PCM audio"),
}

/** Low-risk `Video=` operations. */
enum class VideoOp(val value: String) {
    RECORD("record"),
    CAPTURE("capture"),
    REC_EMER("rec_emer"),
    MUTE("mute"),
    UNMUTE("unmute"),
}
