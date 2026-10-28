package com.alijafari.red.astronomy.ui.backdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSkyPolicyTest {

    @Test
    fun liveSkyDefaultsOffWithStablePreferenceKey() {
        assertFalse(LiveSkyPolicy.DEFAULT_ENABLED)
        assertEquals("live_sky_backdrop_enabled", LiveSkyPolicy.PREF_KEY)
    }

    @Test
    fun tabIndicesMatchTheRootNavigation() {
        // These values mirror FloatingBottomBar's targetTabIndex and MainActivity's `when (tab)`.
        assertEquals(0, LiveSkyPolicy.TAB_LAB)
        assertEquals(1, LiveSkyPolicy.TAB_SATELLITES)
        assertEquals(3, LiveSkyPolicy.TAB_AR)
        assertEquals(4, LiveSkyPolicy.TAB_HOME)
    }

    @Test
    fun disabledNeverShowsOnAnyTabOrState() {
        for (tab in 0..5) {
            for (immersive in listOf(false, true)) {
                assertFalse(
                    "tab=$tab immersive=$immersive",
                    LiveSkyPolicy.isBackdropVisible(enabled = false, selectedTab = tab, immersiveActive = immersive)
                )
            }
        }
    }

    @Test
    fun enabledShowsOnEligibleRootTabs() {
        val eligible = listOf(
            LiveSkyPolicy.TAB_HOME,
            LiveSkyPolicy.TAB_SATELLITES,
            LiveSkyPolicy.TAB_LAB
        )
        for (tab in eligible) {
            assertTrue("tab=$tab", LiveSkyPolicy.isBackdropVisible(enabled = true, selectedTab = tab, immersiveActive = false))
        }
    }

    @Test
    fun arCompassTabIsExcludedEvenWhenEnabled() {
        assertFalse(LiveSkyPolicy.isBackdropVisible(enabled = true, selectedTab = LiveSkyPolicy.TAB_AR, immersiveActive = false))
        assertFalse(LiveSkyPolicy.isEligibleTab(LiveSkyPolicy.TAB_AR))
    }

    @Test
    fun immersiveLabSubFeaturesAreExcluded() {
        // Gravity Sandbox, Gargantua and Chal are Lab sub-features. They set ImmersiveScreenState.active while open,
        // and that alone must hide the backdrop, whatever the tab index says.
        assertFalse(LiveSkyPolicy.isBackdropVisible(enabled = true, selectedTab = LiveSkyPolicy.TAB_LAB, immersiveActive = true))
        assertFalse(LiveSkyPolicy.isBackdropVisible(enabled = true, selectedTab = LiveSkyPolicy.TAB_HOME, immersiveActive = true))
    }

    @Test
    fun unknownTabsStayExcludedUntilReviewed() {
        assertFalse(LiveSkyPolicy.isBackdropVisible(enabled = true, selectedTab = 5, immersiveActive = false))
        assertFalse(LiveSkyPolicy.isBackdropVisible(enabled = true, selectedTab = -1, immersiveActive = false))
    }

    @Test
    fun theRetiredMoonTabIndexStaysExcluded() {
        // Index 2 was the Moon tab. The Moon screen became a card on Home, and the index is not reused.
        assertFalse(LiveSkyPolicy.isEligibleTab(2))
        assertFalse(LiveSkyPolicy.isBackdropVisible(enabled = true, selectedTab = 2, immersiveActive = false))
    }
}
