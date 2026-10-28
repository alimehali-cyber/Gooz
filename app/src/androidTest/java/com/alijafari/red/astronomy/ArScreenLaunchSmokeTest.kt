package com.alijafari.red.astronomy

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.ui.MainViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens the AR sky screen in the real app (MainActivity → tab 3) and keeps it running long enough for
 * every start-up path to execute: sensors, GPS, the sky worker, the glyph cache, CameraX and the GL
 * sky. A crash anywhere in the app process fails the test ("Process crashed"); so does a main thread
 * that stops responding for 5 s (Android's "Application Not Responding" threshold), measured from
 * inside the process by [MainThreadProfiler], which also reports where the main thread spends its time.
 *
 * Once the AR screen is up nothing here waits for an idle main looper (ActivityScenario.state,
 * onActivity and close all do): the AR screen redraws continuously while sensors and the sky update,
 * and on a slow software-rendered emulator the looper may never be idle even though it responds.
 *
 * The CI script `.github/scripts/ar-smoke.sh` runs each method in its own instrumentation process, with
 * and without the runtime permissions, and reports the crash buffer and the profiles.
 */
@RunWith(AndroidJUnit4::class)
class ArScreenLaunchSmokeTest {

    /** Runs [block] on the main thread; false if it did not run within [timeoutMs]. */
    private fun onMain(timeoutMs: Long = 20_000L, block: () -> Unit): Boolean {
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                done.countDown()
            }
        }
        val ran = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        error.get()?.let { throw it }
        return ran
    }

    private fun openAr(label: String, language: AppLanguage, simulatedUtcMs: Long?, seconds: Int = 25) {
        val profiler = MainThreadProfiler(label).start()
        try {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            val activityRef = AtomicReference<Activity?>(null)
            scenario.onActivity { activityRef.set(it) } // the Home screen: idle is reached here
            SystemClock.sleep(2500)
            val opened = onMain {
                val activity = checkNotNull(activityRef.get())
                val vm = ViewModelProvider(activity as MainActivity)[MainViewModel::class.java]
                vm.setLanguage(language)
                vm.setLocation("Tehran", "تهران", 35.6892, 51.3890, 1190.0)
                if (simulatedUtcMs != null) vm.setSimulatedTime(simulatedUtcMs) else vm.returnToLiveTime()
                vm.selectTab(3)
            }
            assertTrue("The main thread did not take the request to open the AR screen within 20 s", opened)
            repeat(seconds) { SystemClock.sleep(1000) }

            // A language change recreates the activity: look the current instance up again.
            val alive = AtomicReference(false)
            val answered = onMain {
                val current = currentActivity()
                alive.set(current != null && !current.isFinishing && !current.isDestroyed)
            }
            val report = profiler.emit("final")
            assertTrue("The main thread did not answer within 20 s\n$report", answered)
            assertTrue("MainActivity was gone while the AR screen was open\n$report", alive.get())
            assertTrue(
                "The main thread was blocked for ${profiler.maxBlockMs} ms (Android reports ANR after 5000 ms)\n$report",
                profiler.maxBlockMs < 5000L
            )

            // Leave the AR screen and finish the activity without ActivityScenario.close() (idle wait).
            onMain {
                currentActivity()?.let { a ->
                    ViewModelProvider(a as MainActivity)[MainViewModel::class.java].selectTab(0)
                }
            }
            SystemClock.sleep(1500)
            onMain { currentActivity()?.finish() }
            SystemClock.sleep(1000)
        } finally {
            profiler.close()
        }
    }

    /** The visible MainActivity instance (paused while a permission dialog covers it). Main thread only. */
    private fun currentActivity(): Activity? {
        val registry = ActivityLifecycleMonitorRegistry.getInstance()
        for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED)) {
            registry.getActivitiesInStage(stage).firstOrNull { it is MainActivity }?.let { return it }
        }
        return null
    }

    @Test
    fun persianLive() = openAr("persianLive", AppLanguage.PERSIAN, null)

    @Test
    fun englishLive() = openAr("englishLive", AppLanguage.ENGLISH, null)

    /** 2026-10-16 18:30 UTC = 22:00 in Tehran: Sun far below the horizon, waxing Moon set. */
    @Test
    fun persianNight() = openAr("persianNight", AppLanguage.PERSIAN, 1_792_175_400_000L)

    /**
     * 2026-10-20 16:00 UTC = 19:30 in Tehran: waxing gibbous Moon (phase angle 68°, 69 % lit, two days after
     * first quarter) 38° up near the meridian, Sun 26.5° below the horizon.
     */
    @Test
    fun englishMoonEvening() = openAr("englishMoonEvening", AppLanguage.ENGLISH, 1_792_512_000_000L)

    /**
     * 2026-10-14 15:00 UTC = 18:30 in Tehran: four-day crescent (phase angle 134°) 8° up in the west-southwest,
     * Sun 12.8° below the horizon — the earthlit night side is visible.
     */
    @Test
    fun persianCrescentEarthshine() = openAr("persianCrescentEarthshine", AppLanguage.PERSIAN, 1_791_990_000_000L)
}
