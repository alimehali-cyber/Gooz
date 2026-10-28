package com.alijafari.red.astronomy.ui.backdrop

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveSkyPreferenceTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The same file the ViewModel uses.
        prefs = context.getSharedPreferences("astro_app_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove(LiveSkyPolicy.PREF_KEY).commit()
    }

    @Test
    fun missingPreferenceLoadsAsOff() {
        assertFalse(LiveSkyPolicy.loadEnabled(prefs))
    }

    @Test
    fun enablingIsPersistedUnderTheSharedKey() {
        LiveSkyPolicy.saveEnabled(prefs, true)
        assertTrue(LiveSkyPolicy.loadEnabled(prefs))
        assertTrue(prefs.getBoolean(LiveSkyPolicy.PREF_KEY, false))
    }

    @Test
    fun disablingIsPersistedAndRestoresOff() {
        LiveSkyPolicy.saveEnabled(prefs, true)
        LiveSkyPolicy.saveEnabled(prefs, false)
        assertFalse(LiveSkyPolicy.loadEnabled(prefs))
        assertEquals(false, prefs.getBoolean(LiveSkyPolicy.PREF_KEY, true))
    }
}
