package com.alijafari.red.astronomy.ui.backdrop

import android.content.SharedPreferences
import com.alijafari.red.astronomy.R

/**
 * Mutually exclusive full-window app backdrop. Default is [NONE] (theme background only).
 * [LIVE_SKY] keeps the existing live-sky preference for backward compatibility.
 * Photo modes store a [drawable-nodpi] resource that [PhotoBackdrop] crops to fill the window.
 */
enum class AppBackdropMode(val photoResId: Int? = null) {
    NONE,
    LIVE_SKY,
    NGC_1929(R.drawable.backdrop_ngc1929),
    CEPHEUS(R.drawable.backdrop_cepheus);

    val isPhoto: Boolean get() = photoResId != null

    companion object {
        const val PREF_KEY: String = "app_backdrop_mode"

        fun fromPrefs(prefs: SharedPreferences): AppBackdropMode {
            val stored = prefs.getString(PREF_KEY, null)
            if (stored != null) {
                return entries.find { it.name == stored } ?: NONE
            }
            return if (LiveSkyPolicy.loadEnabled(prefs)) LIVE_SKY else NONE
        }

        fun save(prefs: SharedPreferences, mode: AppBackdropMode) {
            prefs.edit()
                .putString(PREF_KEY, mode.name)
                .putBoolean(LiveSkyPolicy.PREF_KEY, mode == LIVE_SKY)
                .apply()
        }
    }
}
