package com.alijafari.red.astronomy.ui.backdrop

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rules that the Live Sky backdrop must not break. Each check reads the production source. This is the same
 * approach ContentIntegrityAuditTest uses, and it keeps these rules enforced without any device.
 */
class LiveSkyIsolationTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "app/src/main/java/$relative")
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("Source not found: $relative (searched ${candidates.joinToString()})")
        return file.readText()
    }

    private val backdropFiles = listOf(
        "com/alijafari/red/astronomy/ui/backdrop/LiveSkyBackdrop.kt",
        "com/alijafari/red/astronomy/ui/backdrop/LiveSkyPolicy.kt",
        "com/alijafari/red/astronomy/ui/backdrop/LiveSkyFrameRequest.kt",
        "com/alijafari/red/astronomy/ui/backdrop/LiveSkyFramePipeline.kt",
        "com/alijafari/red/astronomy/ui/backdrop/SkyLiveClock.kt",
        "com/alijafari/red/astronomy/ui/rendering/SkyTimeModel.kt",
        "com/alijafari/red/astronomy/ui/rendering/SkySceneModel.kt",
        "com/alijafari/red/astronomy/ui/rendering/SkySceneRenderer.kt",
        "com/alijafari/red/astronomy/ui/rendering/SkyProjection.kt"
    )

    @Test
    fun backdropUsesTheSharedPanoramaRuleAndNoSecondGlRenderer() {
        val backdrop = source("com/alijafari/red/astronomy/ui/backdrop/LiveSkyBackdrop.kt")
        assertTrue("backdrop must mount the shared panorama layer", backdrop.contains("SkyPanoramaLayer("))
        assertTrue("backdrop must use the shared presentation rule", backdrop.contains("SkyPanoramaFeature.isPresented("))
        assertTrue("backdrop must use the shared Real Sky rule", backdrop.contains("SkyPanoramaFeature.isEnabledFor("))
        assertFalse("backdrop must not create its own GL renderer", backdrop.contains("SkyPanoramaRenderer("))
        assertFalse("backdrop must not decode the panorama itself", backdrop.contains("SkyPanoramaTextureLoader"))
        assertFalse("backdrop must not hard-code a procedural-only path", backdrop.contains("panoramaActive = false"))
    }

    @Test
    fun homeAndBackdropShareOneEffectiveTimeAndOneClock() {
        val hero = source("com/alijafari/red/astronomy/ui/components/HeroSkyCanvas.kt")
        val backdrop = source("com/alijafari/red/astronomy/ui/backdrop/LiveSkyBackdrop.kt")
        assertTrue("Home must use the shared time formula", hero.contains("SkyTimeModel.effectiveJd("))
        assertTrue("backdrop must use the shared time formula via the request", source("com/alijafari/red/astronomy/ui/backdrop/LiveSkyFrameRequest.kt").contains("SkyTimeModel.effectiveJd("))
        assertTrue("Home must read the shared live clock", hero.contains("viewModel.skyLiveClock.timeMs"))
        assertTrue("backdrop must read the shared live clock", backdrop.contains("liveTimeMs"))
        assertFalse("backdrop must not start its own clock loop", backdrop.contains("delay("))
        assertFalse("Home must not keep a second 1 Hz clock loop", hero.contains("currentSystemTimeMs = now"))
    }

    @Test
    fun liveSkyDoesNothingWhileHiddenAndExcludesLabAndArTabs() {
        val backdrop = source("com/alijafari/red/astronomy/ui/backdrop/LiveSkyBackdrop.kt")
        assertTrue("hidden backdrop must return before composing its subtree", backdrop.contains("if (!visible) return"))
        assertTrue("immersive Lab features must be read from ImmersiveScreenState", backdrop.contains("ImmersiveScreenState.active"))
        val policy = source("com/alijafari/red/astronomy/ui/backdrop/LiveSkyPolicy.kt")
        assertTrue(
            "the allow-list must stay exactly Lab, Satellites and Home (AR and the retired Moon index are excluded)",
            policy.contains("setOf(TAB_LAB, TAB_SATELLITES, TAB_HOME)")
        )
    }

    @Test
    fun backdropUsesOpenGlEsOnlyAndNoVulkan() {
        for (path in backdropFiles) {
            val text = source(path).lowercase()
            assertFalse("$path mentions Vulkan", text.contains("vulkan"))
        }
    }

    @Test
    fun backdropIsMountedInTheSharedLayerAndSettingsHasTheSwitch() {
        val main = source("com/alijafari/red/astronomy/MainActivity.kt")
        assertTrue("MainActivity must mount LiveSkyBackdrop", main.contains("LiveSkyBackdrop("))
        assertTrue("MainActivity must keep the glass layer", main.contains("Modifier.layerBackdrop(backdrop)"))

        val settings = source("com/alijafari/red/astronomy/ui/components/SettingsDialog.kt")
        assertTrue("Settings must expose the Live Sky switch", settings.contains("settings_live_sky_switch"))
        assertTrue("Settings must call the backdrop mode setter", settings.contains("setAppBackdropMode"))
        assertTrue("Settings must expose NGC 1929", settings.contains("settings_backdrop_ngc1929"))
        assertTrue("Settings must expose Cepheus", settings.contains("settings_backdrop_cepheus"))
    }

    @Test
    fun homeCardStillUsesTheSharedPipelineAndKeepsItsInteraction() {
        val hero = source("com/alijafari/red/astronomy/ui/components/HeroSkyCanvas.kt")
        assertTrue("Home must draw through SkySceneRenderer", hero.contains("SkySceneRenderer.drawSky("))
        assertTrue("Home must keep its tap-target ring overlay", hero.contains("Tapped Celestial Target Ring Overlay"))
    }

    @Test
    fun liveSkyStringsExistInEnglishAndPersian() {
        val en = resource("values/strings.xml")
        val fa = resource("values-fa/strings.xml")
        for (key in listOf("app_backdrop_title", "live_sky_setting", "live_sky_desc", "backdrop_ngc1929_setting", "backdrop_ngc1929_desc", "backdrop_cepheus_setting", "backdrop_cepheus_desc", "backdrop_off_desc")) {
            assertTrue("missing $key in values", en.contains("name=\"$key\""))
            assertTrue("missing $key in values-fa", fa.contains("name=\"$key\""))
        }
    }

    private fun resource(relative: String): String {
        val candidates = listOf("src/main/res/$relative", "app/src/main/res/$relative")
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("Resource not found: $relative")
        return file.readText()
    }
}
