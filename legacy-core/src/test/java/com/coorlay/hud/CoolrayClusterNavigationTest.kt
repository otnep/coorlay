package com.coorlay.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class CoolrayClusterNavigationTest {
    @Test fun appleManeuversUseTheClusterIconsProvenByCommunity() {
        assertEquals(2, CoolrayClusterNavigation.amapIcon(1))   // left
        assertEquals(3, CoolrayClusterNavigation.amapIcon(2))   // right
        assertEquals(1, CoolrayClusterNavigation.amapIcon(3))   // straight
        assertEquals(4, CoolrayClusterNavigation.amapIcon(13))  // slight left
        assertEquals(5, CoolrayClusterNavigation.amapIcon(14))  // slight right
        assertEquals(6, CoolrayClusterNavigation.amapIcon(47))  // sharp left
        assertEquals(7, CoolrayClusterNavigation.amapIcon(48))  // sharp right
        assertEquals(8, CoolrayClusterNavigation.amapIcon(4))   // U-turn
        assertEquals(11, CoolrayClusterNavigation.amapIcon(30)) // roundabout exit 3
        assertEquals(12, CoolrayClusterNavigation.amapIcon(7))  // leave roundabout
        assertEquals(15, CoolrayClusterNavigation.amapIcon(12)) // destination
        assertEquals(0, CoolrayClusterNavigation.amapIcon(0))
    }

    @Test fun streetTypesAreShortenedForTheClusterLine() {
        assertEquals("ул. Ленина", CoolrayClusterNavigation.shortRoad(" улица Ленина "))
        assertEquals("Кутузовский пр-т", CoolrayClusterNavigation.shortRoad("Кутузовский проспект"))
    }
}
