package com.cardv.dashcam.ui

import com.cardv.shared.safety.SafetyLevel
import com.cardv.shared.safety.Setting
import com.cardv.shared.safety.SettingsCatalog

/**
 * Which catalog entries the settings screen may show, and in which section.
 *
 * This is presentation only: every group is derived from [SettingsCatalog], so a property that is
 * `FORBIDDEN` in the shared catalog cannot appear here even if someone adds a section by mistake.
 * `TimeSettings` and `Video` are excluded from the generic rows because they have dedicated
 * controls (clock sync and the camera controls screen).
 */
object SettingsGroups {
    fun safe(includeExperimental: Boolean): List<Setting> =
        SettingsCatalog.visible(includeExperimental)
            .filterNot { it.name == SettingsCatalog.TIME_SETTINGS.name }
            .filterNot { it.name == SettingsCatalog.VIDEO.name }

    fun experimental(): List<Setting> =
        SettingsCatalog.byLevel(SafetyLevel.EXPERIMENTAL)

    fun standby(): List<Setting> =
        SettingsCatalog.byLevel(SafetyLevel.NEEDS_STANDBY)
            .filterNot { it.name == SettingsCatalog.SD0.name }
            .filterNot { it.name == SettingsCatalog.FACTORY_RESET.name }

    /** The two typed-confirmation actions. Kept separate from ordinary standby settings. */
    fun destructive(): List<Setting> = listOf(
        SettingsCatalog.SD0,
        SettingsCatalog.FACTORY_RESET,
    )

    fun allShown(includeExperimental: Boolean): List<Setting> = buildList {
        addAll(safe(includeExperimental))
        if (includeExperimental) addAll(experimental())
        addAll(standby())
        addAll(destructive())
    }
}
