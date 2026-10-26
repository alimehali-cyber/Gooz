package com.alijafari.red.astronomy.ui.persepolis

import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import com.alijafari.red.astronomy.ui.rendering.PersepolisFraming
import com.alijafari.red.astronomy.ui.rendering.SkySceneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks for the Persepolis landscape's placement inside the full-bleed Home scene.
 *
 * The ruin's own camera and lighting live in Persepolis.kt and need a GL context; what ZIG decides —
 * and what can therefore be proven on the JVM — is where that camera's output lands in the scene:
 * exactly half its first linear size, in the left distance, with the whole façade on screen, the
 * platform in the ground band, the columns against the sky, and no stone over the readout. Those
 * are the assertions below, over a matrix of scene sizes spanning the HomeScreen clamp (52 % of
 * the viewport, 312–560 dp tall) from a small phone to a tablet.
 *
 * Sizes are physical pixels at the density noted per case, because that is what the GL viewport and
 * the sky canvas are both measured in. The full lookAt/ortho projection of landmark points is checked
 * offline by tools/persepolis/verify_framing.py; this test pins the pure-Kotlin framing rules.
 */
class PersepolisFramingTest {

    private data class SceneSize(val widthPx: Float, val heightPx: Float, val density: Float, val label: String)

    private val sizes = listOf(
        SceneSize(960f, 1140f, 3f, "small phone 320x380dp"),
        SceneSize(1080f, 1260f, 3f, "phone 360x420dp"),
        SceneSize(1290f, 1440f, 3f, "large phone 430x480dp"),
        SceneSize(1080f, 936f, 3f, "short phone scene 360x312dp"),
        SceneSize(2400f, 1120f, 2f, "tablet 1200x560dp"),
        SceneSize(2400f, 936f, 3f, "wide short scene 800x312dp")
    )

    private fun framingFor(size: SceneSize): PersepolisFraming.Result {
        val horizonY = size.heightPx * SkySceneLayout.HORIZON_FRACTION
        return PersepolisFraming.forScene(
            widthPx = size.widthPx,
            heightPx = size.heightPx,
            horizonYPx = horizonY,
            readoutBottomPx = horizonY - PersepolisFraming.READOUT_INSET_DP * size.density,
            readoutRightPx = PersepolisFraming.READOUT_RIGHT_DP * size.density,
            readoutClearancePx = PersepolisFraming.READOUT_CLEARANCE_DP * size.density
        )
    }

    @Test
    fun theRuinRendersAtExactlyHalfItsFirstLinearSize() {
        sizes.forEach { size ->
            val framing = framingFor(size)
            val aspect = size.widthPx / size.heightPx
            // The first integration's framing, recomputed: the model window, widened by the old cap.
            val referenceVisibleWidth = maxOf(
                PersepolisFraming.DEFAULT_VISIBLE_WIDTH_M,
                aspect * PersepolisFraming.BUILDING_HEIGHT_M /
                    PersepolisFraming.REFERENCE_MAX_BUILDING_HEIGHT_FRACTION
            )
            assertEquals(
                "${size.label}: twice the reference visible width",
                referenceVisibleWidth / PersepolisFraming.LINEAR_SCALE_FRACTION,
                framing.visibleWidthMeters,
                0.01f
            )
            assertEquals(
                "${size.label}: half the reference pixels per metre",
                0.5f * size.widthPx / referenceVisibleWidth,
                framing.pxPerMeter,
                0.001f
            )
        }
        // On phone-shaped scenes that is concretely the 178 m window (twice the model's 89 m).
        sizes.filter { it.widthPx / it.heightPx < 1.2f }.forEach { size ->
            assertEquals(
                "${size.label}: half-scale phone window",
                178f,
                framingFor(size).visibleWidthMeters,
                0.001f
            )
        }
    }

    @Test
    fun theFacadeSitsInTheLeftOfTheLandscape() {
        sizes.forEach { size ->
            val framing = framingFor(size)
            assertEquals(
                "${size.label}: centroid on the left third",
                PersepolisFraming.CENTER_FRACTION_FROM_LEFT,
                framing.centerFractionFromLeft,
                0.0001f
            )
            assertEquals(
                "${size.label}: model centre column",
                size.widthPx * PersepolisFraming.CENTER_FRACTION_FROM_LEFT,
                framing.modelCenterXPx,
                0.5f
            )
            // The whole detailed façade remains in frame, entirely in the left ~62 % of the scene,
            // and the wider platform ends by ~68 % — open landscape to the right.
            assertTrue("${size.label}: façade left in frame", framing.facadeLeftXPx >= 0f)
            assertTrue(
                "${size.label}: façade confined to the left (right=${framing.facadeRightXPx / size.widthPx})",
                framing.facadeRightXPx <= size.widthPx * 0.62f
            )
            assertTrue(
                "${size.label}: platform ends in the left (right=${framing.platformRightXPx / size.widthPx})",
                framing.platformRightXPx <= size.widthPx * 0.68f
            )
            assertTrue(
                "${size.label}: footprint ordering",
                framing.platformLeftXPx < framing.facadeLeftXPx &&
                    framing.facadeLeftXPx < framing.modelCenterXPx &&
                    framing.modelCenterXPx < framing.facadeRightXPx &&
                    framing.facadeRightXPx < framing.platformRightXPx
            )
        }
    }

    @Test
    fun theToeSitsInTheGroundBandWithForegroundBelowIt() {
        sizes.forEach { size ->
            val framing = framingFor(size)
            val composition = SkySceneLayout.composition(size.widthPx, size.heightPx)
            assertTrue(
                "${size.label}: toe below the solid ground's top",
                framing.toeYPx > composition.groundTopY
            )
            assertTrue(
                "${size.label}: toe is farther back, with a deeper foreground below it",
                framing.toeYPx >= composition.horizonY + size.heightPx * 0.095f &&
                    framing.toeYPx <= size.heightPx * 0.86f &&
                    framing.toeYPx <= size.heightPx * PersepolisFraming.MAX_TOE_FRACTION + 0.5f &&
                    framing.toeYPx < size.heightPx
            )
            assertEquals(
                "${size.label}: base fraction is the toe row",
                framing.toeYPx / size.heightPx,
                framing.baseFractionFromTop,
                0.0001f
            )
        }
    }

    @Test
    fun thePlatformIsInTheGroundAndTheColumnsAreAgainstTheSky() {
        sizes.forEach { size ->
            val framing = framingFor(size)
            val horizonY = size.heightPx * SkySceneLayout.HORIZON_FRACTION
            assertTrue(
                "${size.label}: platform top below the horizon",
                framing.platformTopYPx > horizonY
            )
            assertTrue(
                "${size.label}: tallest column rises above the horizon (top=${framing.tallestColumnTopYPx}, horizon=$horizonY)",
                framing.tallestColumnTopYPx < horizonY
            )
            // And never up into the header band (the top scrim is 0.34 of the scene; the brand and
            // actions live above roughly the first quarter).
            assertTrue(
                "${size.label}: tallest column clears the header band",
                framing.tallestColumnTopYPx > size.heightPx * 0.36f
            )
            val buildingFraction = (framing.toeYPx - framing.tallestColumnTopYPx) / size.heightPx
            assertTrue(
                "${size.label}: building height capped ($buildingFraction)",
                buildingFraction <= PersepolisFraming.MAX_BUILDING_HEIGHT_FRACTION + 0.001f
            )
        }
    }

    @Test
    fun noStoneReachesTheReadout() {
        // The GL surface composites above the Compose readout inside its bounds. Moving the ruin
        // left puts more than the great doorway under the caption, so the guard is the tallest
        // structure whose projected span actually enters the protected readout width at this size.
        sizes.forEach { size ->
            val framing = framingFor(size)
            val readoutBottom = size.heightPx * SkySceneLayout.HORIZON_FRACTION -
                PersepolisFraming.READOUT_INSET_DP * size.density
            assertTrue(
                "${size.label}: guarded structure (${framing.readoutGuardTopYPx}) below readout bottom ($readoutBottom)",
                framing.readoutGuardTopYPx >= readoutBottom +
                    PersepolisFraming.READOUT_CLEARANCE_DP * size.density - 0.5f
            )
            assertTrue(
                "${size.label}: doorway cornice also below readout bottom",
                framing.greatDoorwayTopYPx >= readoutBottom +
                    PersepolisFraming.READOUT_CLEARANCE_DP * size.density - 0.5f
            )
        }
    }

    @Test
    fun theScenesOwnAutoFitNeverWidensTheChosenWidth() {
        // PersepolisScene.draw() widens the view when aspect * 25 / baseFraction exceeds the chosen
        // width. That must never trigger, or the scale the ground painter assumes would not be the
        // scale the GL camera uses and the grounding seam would drift from the stone.
        sizes.forEach { size ->
            val framing = framingFor(size)
            val aspect = size.widthPx / size.heightPx
            val autoFitNeed = aspect * (25f / framing.baseFractionFromTop)
            assertTrue(
                "${size.label}: chosen width ${framing.visibleWidthMeters} >= auto-fit need $autoFitNeed",
                framing.visibleWidthMeters >= autoFitNeed
            )
        }
    }

    @Test
    fun onlyThePhotographicThemesCarryTheRuin() {
        assertTrue(PersepolisFeature.isEnabledFor(SkyCanvasTheme.REAL_SKY))
        assertTrue(PersepolisFeature.isEnabledFor(SkyCanvasTheme.ATMOSPHERIC_SKY))
        // The flat art-directed themes — and Observatory, whose red output is functional — keep
        // their existing ground exactly as it was.
        assertFalse(PersepolisFeature.isEnabledFor(SkyCanvasTheme.MONOCHROME_SCIENTIFIC))
        assertFalse(PersepolisFeature.isEnabledFor(SkyCanvasTheme.KIDS_WATERCOLOR))
        assertFalse(PersepolisFeature.isEnabledFor(SkyCanvasTheme.OBSERVATORY))
        assertFalse(PersepolisFeature.isEnabledFor(SkyCanvasTheme.PAPERCRAFT_DIORAMA))
    }

    @Test
    fun moonlightScalesWithTheIlluminatedFraction() {
        assertEquals(0.22f, persepolisMoonIntensity(100.0), 0.0001f)
        assertEquals(0.11f, persepolisMoonIntensity(50.0), 0.0001f)
        assertEquals(0f, persepolisMoonIntensity(0.0), 0.0001f)
        // Out-of-range engine values can never overdrive the model's full-Moon intensity.
        assertEquals(0.22f, persepolisMoonIntensity(140.0), 0.0001f)
        assertEquals(0f, persepolisMoonIntensity(-5.0), 0.0001f)
    }
}
