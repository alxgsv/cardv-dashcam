package com.cardv.dashcam.ui

import com.cardv.shared.safety.SafetyLevel
import com.cardv.shared.safety.SettingsCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings UI must never surface a property the shared catalog forbids. */
class SettingsGroupsTest {
    @Test
    fun noGroupContainsForbiddenProperties() {
        val shown = SettingsGroups.allShown(includeExperimental = true)
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.all { SettingsCatalog.levelOf(it.name) != SafetyLevel.FORBIDDEN })
        for (name in listOf("Flicker", "Net", "WiFi", "action=flash")) {
            assertFalse(shown.any { it.name == name }, name)
        }
    }

    @Test
    fun experimentalIsHiddenUntilOptedIn() {
        val hidden = SettingsGroups.safe(includeExperimental = false).map { it.name }
        val shown = SettingsGroups.safe(includeExperimental = true).map { it.name }
        assertFalse("GSensor" in hidden)
        assertTrue("GSensor" in shown)
        assertTrue(SettingsGroups.experimental().all { it.level == SafetyLevel.EXPERIMENTAL })
    }

    @Test
    fun standbyAndDestructiveSectionsAreSplitByCatalogLevel() {
        assertTrue(SettingsGroups.standby().all { it.level == SafetyLevel.NEEDS_STANDBY })
        assertEquals(
            setOf("SD0", "FactoryReset"),
            SettingsGroups.destructive().map { it.name }.toSet(),
        )
        assertFalse(SettingsGroups.standby().any { it.name == "SD0" })
        assertFalse(SettingsGroups.standby().any { it.name == "FactoryReset" })
    }

    @Test
    fun dedicatedRowsAreNotRepeatedInTheGenericList() {
        val names = SettingsGroups.safe(includeExperimental = false).map { it.name }
        assertFalse("TimeSettings" in names)
        assertFalse("Video" in names)
        assertTrue("AWB" in names)
        assertTrue("EV" in names)
        assertTrue("DateLogoStamp" in names)
    }
}
