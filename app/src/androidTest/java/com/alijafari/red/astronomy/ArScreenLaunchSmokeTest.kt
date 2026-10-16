package com.alijafari.red.astronomy

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.ui.MainViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the AR sky screen in the real app (MainActivity → tab 3) and keeps it running long enough for
 * every start-up path to execute: sensors, GPS, the sky worker, the glyph cache, CameraX and the GL
 * sky. A crash anywhere in the app process fails the test ("Process crashed").
 *
 * The CI script `.github/scripts/ar-smoke.sh` runs each method in its own instrumentation process, with
 * and without the runtime permissions, and reports the crash buffer.
 */
@RunWith(AndroidJUnit4::class)
class ArScreenLaunchSmokeTest {

    private fun openAr(language: AppLanguage, simulatedUtcMs: Long?, seconds: Int = 25) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(2500)
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                vm.setLanguage(language)
                vm.setLocation("Tehran", "تهران", 35.6892, 51.3890, 1190.0)
                if (simulatedUtcMs != null) vm.setSimulatedTime(simulatedUtcMs) else vm.returnToLiveTime()
                vm.selectTab(3)
            }
            repeat(seconds) { SystemClock.sleep(1000) }
            assertTrue(
                "MainActivity was destroyed while the AR screen was open",
                scenario.state.isAtLeast(Lifecycle.State.CREATED)
            )
        }
    }

    @Test
    fun persianLive() = openAr(AppLanguage.PERSIAN, null)

    @Test
    fun englishLive() = openAr(AppLanguage.ENGLISH, null)

    /** 2026-10-16 18:30 UTC = 22:00 in Tehran: Sun far below the horizon, waxing Moon set. */
    @Test
    fun persianNight() = openAr(AppLanguage.PERSIAN, 1_792_175_400_000L)

    /** 2026-10-20 16:00 UTC = 19:30 in Tehran: first-quarter Moon high in a dark sky (earthshine side). */
    @Test
    fun englishMoonEvening() = openAr(AppLanguage.ENGLISH, 1_792_512_000_000L)
}
