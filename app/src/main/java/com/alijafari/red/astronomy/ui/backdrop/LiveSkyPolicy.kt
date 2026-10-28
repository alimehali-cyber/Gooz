package com.alijafari.red.astronomy.ui.backdrop

import android.content.SharedPreferences

/**
 * Preference and visibility rules for the Live Sky app backdrop. This is pure logic: no Compose and no Android UI.
 *
 * The backdrop is shown only when all of these hold:
 *  1. The user has enabled Live Sky in Settings (default OFF).
 *  2. The selected root tab is one of the eligible tabs in [ELIGIBLE_TABS]. The AR / compass tab is excluded.
 *  3. No immersive Lab sub-feature is open. Gravity Sandbox, Gargantua and Chal are Lab sub-features, not
 *     tabs. They announce themselves through [com.zig.gravity.ui.ImmersiveScreenState], so they are excluded
 *     by that signal rather than by a tab index.
 *
 * Nothing here depends on the panorama feature gate in the skypanorama package. The backdrop draws its own sky
 * through the shared renderer, so it does not rely on that gate.
 */
object LiveSkyPolicy {

    /** SharedPreferences key in the app prefs file (`astro_app_prefs`). */
    const val PREF_KEY: String = "live_sky_backdrop_enabled"

    /** Live Sky is OFF unless the user turns it on. */
    const val DEFAULT_ENABLED: Boolean = false

    // Root tab indices. These match FloatingBottomBar's NavItem targetTabIndex values and the `when` in MainActivity.
    // Index 2 is retired and deliberately not reused: it was the Moon tab, and the Moon now lives on Home.
    const val TAB_LAB: Int = 0
    const val TAB_SATELLITES: Int = 1
    const val TAB_AR: Int = 3
    const val TAB_HOME: Int = 4

    /** Allow-list. Any tab not listed here is excluded, so a new tab stays excluded until it is reviewed. */
    private val ELIGIBLE_TABS: Set<Int> = setOf(TAB_LAB, TAB_SATELLITES, TAB_HOME)

    fun isEligibleTab(selectedTab: Int): Boolean = selectedTab in ELIGIBLE_TABS

    /**
     * Single source of truth for whether the backdrop is visible.
     *
     * @param enabled the Settings preference.
     * @param selectedTab the root tab index in `MainUiState.selectedTab`.
     * @param immersiveActive true while an immersive Lab sub-feature (Gravity Sandbox, Gargantua, Chal) is composed.
     */
    fun isBackdropVisible(enabled: Boolean, selectedTab: Int, immersiveActive: Boolean): Boolean {
        if (!enabled) return false
        if (immersiveActive) return false
        return isEligibleTab(selectedTab)
    }

    fun loadEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_KEY, DEFAULT_ENABLED)

    fun saveEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(PREF_KEY, enabled).apply()
    }
}
