package com.cardv.shared.safety

/** How safe a camera setting is to send. Order matters: the default UI exposes only [SAFE]. */
enum class SafetyLevel {
    /** Verified on this camera; can be written while recording. */
    SAFE,

    /** Valid, but the camera answers 709 while recording. Stop recording first, then restore it. */
    NEEDS_STANDBY,

    /** Untested and may hang the single-threaded CGI server. Hidden unless explicitly enabled. */
    EXPERIMENTAL,

    /** Never send. These can wedge the camera until the car is restarted. */
    FORBIDDEN,
}

/**
 * One writable camera property, with the values we know and its safety level.
 *
 * [values] is empty for free-form properties such as `TimeSettings`; otherwise only the listed
 * values are sent.
 */
data class Setting(
    val name: String,
    val level: SafetyLevel,
    val values: List<String> = emptyList(),
    val description: String = "",
) {
    fun accepts(value: String): Boolean = values.isEmpty() || value in values
}

/**
 * The single source of truth for which settings the app may send.
 *
 * Derived from docs/PROTOCOL.md. When in doubt a property is [SafetyLevel.FORBIDDEN], never
 * [SafetyLevel.SAFE]: an unknown property may make the CGI server wait forever.
 */
object SettingsCatalog {
    val TIME_SETTINGS = Setting(
        name = "TimeSettings",
        level = SafetyLevel.SAFE,
        description = "Camera clock, value YYYY\$MM\$DD\$hh\$mm\$ss (camera-local time)",
    )

    val VIDEO = Setting(
        name = "Video",
        level = SafetyLevel.SAFE,
        values = listOf("record", "capture", "rec_emer", "mute", "unmute"),
        description = "Recording, snapshot, manual lock and microphone",
    )

    val AWB = Setting(
        name = "AWB",
        level = SafetyLevel.SAFE,
        values = listOf("AUTO", "DAYLIGHT", "CLOUDY", "FLUORESCENT1", "FLUORESCENT2", "FLUORESCENT3", "INCANDESCENT"),
    )

    val EV = Setting(
        name = "EV",
        level = SafetyLevel.SAFE,
        values = listOf(
            "EVN200", "EVN167", "EVN150", "EVN133", "EVN100", "EVN067", "EVN050", "EVN033",
            "EV0",
            "EVP033", "EVP067", "EVP100", "EVP133", "EVP150", "EVP167", "EVP200",
        ),
    )

    val DATE_LOGO_STAMP = Setting(
        name = "DateLogoStamp",
        level = SafetyLevel.SAFE,
        values = listOf("ON", "OFF"),
    )

    val SD0 = Setting(
        name = "SD0",
        level = SafetyLevel.NEEDS_STANDBY,
        values = listOf("format"),
        description = "Formats the SD card; destructive and refused while recording",
    )

    val FACTORY_RESET = Setting(
        name = "FactoryReset",
        level = SafetyLevel.NEEDS_STANDBY,
        values = listOf("Camera"),
        description = "Factory reset; refused while recording",
    )

    val VIDEO_CLIP_TIME = Setting(
        name = "VideoClipTime",
        level = SafetyLevel.NEEDS_STANDBY,
        values = listOf("OFF", "6SEC", "1MIN", "2MIN", "3MIN", "5MIN", "10MIN", "25MIN", "30MIN"),
    )

    val LOOPING_VIDEO = Setting(
        name = "LoopingVideo",
        level = SafetyLevel.NEEDS_STANDBY,
        values = listOf("OFF", "6SEC", "1MIN", "2MIN", "3MIN", "5MIN", "10MIN", "25MIN", "30MIN"),
    )

    val HDR = Setting(
        name = "HDR",
        level = SafetyLevel.NEEDS_STANDBY,
        values = listOf("ON", "OFF"),
    )

    val Q_SHOT = Setting(
        name = "Q-SHOT",
        level = SafetyLevel.NEEDS_STANDBY,
        values = listOf("ON", "OFF"),
    )

    val VIDEORES = Setting(
        name = "Videores",
        level = SafetyLevel.NEEDS_STANDBY,
        values = listOf("1080P30", "1080P30fps", "1080P25", "720P60", "720P30", "720P25"),
    )

    val GSENSOR = Setting(
        name = "GSensor",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf("OFF", "LEVEL0", "LEVEL1", "LEVEL2", "LEVEL3", "LEVEL4", "MIDDLE"),
        description = "Geely reports MIDDLE but the SDK values differ",
    )

    val MTD = Setting(
        name = "MTD",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf("OFF", "LOW", "MIDDLE", "HIGH"),
    )

    val POWER_OFF_DELAY = Setting(
        name = "PowerOffDelay",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf(
            "OFF", "5SEC", "10SEC", "15SEC", "30SEC", "60SEC",
            "2MIN", "3MIN", "5MIN", "15MIN", "30MIN", "60MIN",
        ),
    )

    val TV_SYSTEM = Setting(
        name = "TVSystem",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf("NTSC", "PAL"),
    )

    val UPSIDE_DOWN = Setting(
        name = "UpsideDown",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf("Normal", "Upsidedown", "Mirror", "Colrow"),
    )

    val SPOT_METER = Setting(
        name = "SpotMeter",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf("ON", "OFF"),
    )

    val STATUS_LIGHTS = Setting(
        name = "StatusLights",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf("ON", "OFF"),
    )

    val SOUND_INDICATOR = Setting(
        name = "SoundIndicator",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf("ON", "OFF"),
        description = "Beep",
    )

    /** Some firmware exposes the same beep setting under the alias `Beep`; not enabled to send. */
    val BEEP = Setting(
        name = "Beep",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf("ON", "OFF"),
        description = "Alias of SoundIndicator",
    )

    val POWER_SAVING = Setting(
        name = "PowerSaving",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf("OFF", "1MIN", "2MIN", "5MIN"),
    )

    val PARKING_MONITOR = Setting(
        name = "ParkingMonitor",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf("ON", "OFF"),
        description = "Geely-specific, not in the public SDK",
    )

    val SOUND_RECORD = Setting(
        name = "SoundRecord",
        level = SafetyLevel.EXPERIMENTAL,
        values = listOf("ON", "OFF"),
        description = "Prefer Video=mute/unmute; recording restarts reset this to OFF",
    )

    val FLICKER = Setting(
        name = "Flicker",
        level = SafetyLevel.FORBIDDEN,
        values = listOf("50Hz", "60Hz"),
        description = "Hangs the CGI server until power-cycle",
    )

    val NET = Setting(
        name = "Net",
        level = SafetyLevel.FORBIDDEN,
        values = listOf("reset", "findme"),
        description = "Network reset / find-me, never needed by the app",
    )

    val WIFI = Setting(
        name = "WiFi",
        level = SafetyLevel.FORBIDDEN,
        description = "Wi-Fi name/password; changing it would cut the app off the camera",
    )

    /** `action=flash` is not a property, but the catalog keeps it here so no UI can miss it. */
    val FLASH = Setting(
        name = "action=flash",
        level = SafetyLevel.FORBIDDEN,
        description = "Firmware upload; never sent",
    )

    /** Every writable property documented in PROTOCOL.md, in a stable display order. */
    val all: List<Setting> = listOf(
        TIME_SETTINGS,
        VIDEO,
        AWB,
        EV,
        DATE_LOGO_STAMP,
        SD0,
        FACTORY_RESET,
        VIDEO_CLIP_TIME,
        LOOPING_VIDEO,
        HDR,
        Q_SHOT,
        VIDEORES,
        GSENSOR,
        MTD,
        POWER_OFF_DELAY,
        TV_SYSTEM,
        UPSIDE_DOWN,
        SPOT_METER,
        STATUS_LIGHTS,
        SOUND_INDICATOR,
        BEEP,
        POWER_SAVING,
        PARKING_MONITOR,
        SOUND_RECORD,
        FLICKER,
        NET,
        WIFI,
        FLASH,
    )

    /** Exact, case-sensitive lookup. */
    fun find(name: String): Setting? = all.firstOrNull { it.name == name }

    /** Unknown properties are treated as forbidden, never as safe. */
    fun levelOf(name: String): SafetyLevel = find(name)?.level ?: SafetyLevel.FORBIDDEN

    /**
     * Settings a UI may show. By default this is only [SafetyLevel.SAFE]; pass
     * `includeExperimental = true` after the user opts in. `NEEDS_STANDBY` settings are not part of
     * the default list and should come from a dedicated flow that stops recording first.
     */
    fun visible(includeExperimental: Boolean = false): List<Setting> {
        val levels = buildSet {
            add(SafetyLevel.SAFE)
            if (includeExperimental) add(SafetyLevel.EXPERIMENTAL)
        }
        return all.filter { it.level in levels }
    }

    fun byLevel(level: SafetyLevel): List<Setting> = all.filter { it.level == level }

    /** True for `action=flash` (and the catalog entry that represents it). */
    fun isForbiddenAction(action: String): Boolean =
        action.equals("flash", ignoreCase = true) || action.equals("action=flash", ignoreCase = true)
}
