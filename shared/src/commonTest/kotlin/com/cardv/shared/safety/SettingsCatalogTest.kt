package com.cardv.shared.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsCatalogTest {
    @Test
    fun onlySafeSettingsAreVisibleByDefault() {
        val visible = SettingsCatalog.visible()
        assertTrue(visible.isNotEmpty())
        assertTrue(visible.all { it.level == SafetyLevel.SAFE })
        assertEquals(
            setOf("TimeSettings", "Video", "AWB", "EV", "DateLogoStamp"),
            visible.map { it.name }.toSet(),
        )
    }

    @Test
    fun experimentalSettingsNeedAnOptIn() {
        val names = SettingsCatalog.visible(includeExperimental = true).map { it.name }.toSet()
        assertTrue("GSensor" in names)
        assertTrue("MTD" in names)
        assertFalse("Flicker" in names)
        assertFalse("SD0" in names) // needs standby, separate flow
    }

    @Test
    fun forbiddenPropertiesAreNeverSafe() {
        for (name in listOf("Flicker", "Net", "WiFi", "action=flash")) {
            assertEquals(SafetyLevel.FORBIDDEN, SettingsCatalog.levelOf(name), name)
        }
        assertTrue(SettingsCatalog.isForbiddenAction("flash"))
        assertTrue(SettingsCatalog.isForbiddenAction("action=flash"))
        assertFalse(SettingsCatalog.isForbiddenAction("get"))
    }

    @Test
    fun unknownPropertiesAreForbidden() {
        assertEquals(SafetyLevel.FORBIDDEN, SettingsCatalog.levelOf("DefinitelyNotAProperty"))
        assertEquals(SafetyLevel.FORBIDDEN, SettingsCatalog.levelOf("flicker"))
        assertTrue(SettingsCatalog.find("DefinitelyNotAProperty") == null)
    }

    @Test
    fun standbySettingsAreSeparate() {
        assertEquals(SafetyLevel.NEEDS_STANDBY, SettingsCatalog.levelOf("SD0"))
        assertEquals(SafetyLevel.NEEDS_STANDBY, SettingsCatalog.levelOf("FactoryReset"))
        assertEquals(SafetyLevel.NEEDS_STANDBY, SettingsCatalog.levelOf("VideoClipTime"))
        assertEquals(SafetyLevel.NEEDS_STANDBY, SettingsCatalog.levelOf("LoopingVideo"))
        assertEquals(SafetyLevel.NEEDS_STANDBY, SettingsCatalog.levelOf("Videores"))
        assertEquals(SafetyLevel.NEEDS_STANDBY, SettingsCatalog.levelOf("HDR"))
        assertEquals(SafetyLevel.NEEDS_STANDBY, SettingsCatalog.levelOf("Q-SHOT"))
    }

    @Test
    fun coversEveryDocumentedWriteProperty() {
        val documented = listOf(
            "TimeSettings", "SD0", "FactoryReset", "Video", "Net", "AWB", "EV", "DateLogoStamp",
            "Flicker", "GSensor", "MTD", "VideoClipTime", "LoopingVideo", "PowerOffDelay",
            "TVSystem", "UpsideDown", "HDR", "Q-SHOT", "SpotMeter", "StatusLights",
            "SoundIndicator", "PowerSaving", "Videores", "ParkingMonitor", "SoundRecord",
            "WiFi", "action=flash",
        )
        for (name in documented) {
            assertNotNull(SettingsCatalog.find(name), "missing catalog entry: $name")
        }
    }

    @Test
    fun valuesAreValidated() {
        assertTrue(SettingsCatalog.EV.accepts("EV0"))
        assertTrue(SettingsCatalog.EV.accepts("EVN200"))
        assertFalse(SettingsCatalog.EV.accepts("ev0"))
        assertFalse(SettingsCatalog.VIDEO.accepts("factory_reset"))
        assertTrue(SettingsCatalog.TIME_SETTINGS.accepts("2026\$11\$07\$09\$05\$03"))
    }
}
