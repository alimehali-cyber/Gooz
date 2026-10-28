package com.alijafari.red.astronomy

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level regression guards for the Home sky canvas' Compose/window interactions. */
class SkyCanvasInteractionRegressionTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "app/src/main/java/$relative")
        return candidates.map(::File).firstOrNull { it.exists() }?.readText()
            ?: error("Source not found: $relative")
    }

    @Test
    fun aSelectionShowsAnAnimatedNameBubbleInBothCanvasPresentationsAndOpensDetails() {
        val hero = source("com/alijafari/red/astronomy/ui/components/HeroSkyCanvas.kt")
        assertTrue("selection must reveal the name bubble", hero.contains("visible = selectedCelestial != null"))
        assertTrue("the name bubble must animate into place", hero.contains("scaleIn("))
        assertTrue("the name bubble must open the selected object's information", hero.contains("viewModel.openObjectDetailById(idToOpen, simulatedTimeMs)"))
        assertTrue("the tap target gets a radial bloom", hero.contains("selectionBloom.animateTo("))
        assertTrue("the Home and viewer share the same selection bubble", hero.contains("Present in both Home and fullscreen"))
        assertTrue("the name bubble auto-dismisses after four seconds", hero.contains("delay(4_000)"))
    }

    @Test
    fun fullscreenSkyUsesImmersiveCutoutAwareWindowAndSplashSurvivesRotation() {
        val home = source("com/alijafari/red/astronomy/ui/screens/HomeScreen.kt")
        assertTrue("the fullscreen viewer hides status and navigation bars", home.contains("insetsController.hide(WindowInsetsCompat.Type.systemBars())"))
        assertTrue("the viewer draws into edge-to-edge window space", home.contains("WindowCompat.setDecorFitsSystemWindows(window, false)"))
        assertTrue("the viewer adapts to landscape display cutouts", home.contains("LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES"))
        assertTrue("orientation is restored only when the viewer exits, not on rotation", home.contains("activity?.isChangingConfigurations != true"))
        assertTrue("the original orientation survives rotation", home.contains("val originalOrientation = rememberSaveable"))

        val activity = source("com/alijafari/red/astronomy/MainActivity.kt")
        assertTrue("the intro must not replay when fullscreen rotation recreates the Activity", activity.contains("rememberSaveable { mutableStateOf(true) }"))
    }

    @Test
    fun expandedHomeEclipseListIsRemovedButEclipseScienceAndHeroTileRemain() {
        val home = source("com/alijafari/red/astronomy/ui/screens/HomeScreen.kt")
        assertTrue("the eclipse series calculation remains intact", home.contains("EclipseEngine.getNextEclipseSeries("))
        assertTrue("the compact eclipse card beside the Moon remains", home.contains(".testTag(\"home_eclipse_card\")"))
        assertTrue("the expanded upcoming-eclipses feed card is removed", !home.contains("home_next_eclipses_card"))
    }
}
