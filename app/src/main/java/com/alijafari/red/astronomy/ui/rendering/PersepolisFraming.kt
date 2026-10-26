package com.alijafari.red.astronomy.ui.rendering

import kotlin.math.max
import kotlin.math.min

/**
 * Placement of the Persepolis relief inside the full-bleed Home scene.
 *
 * The ruin itself ([com.alijafari.red.astronomy.ui.persepolis.PersepolisScene]) owns its fixed camera
 * and takes three framing inputs: how many metres of world width the viewport shows
 * (`visibleWidthMeters`), where the platform's front toe — world point (0, 0, 8) — lands vertically
 * (`baseFractionFromTop`), and where the platform centroid lands horizontally
 * (`centerFractionFromLeft`). This object computes those numbers from the scene size, plus the pixel
 * landmarks the Compose-side ground needs: the toe row and the platform's left/right footprint for the
 * localized earth, sand and contact-shadow work in [SceneGroundRenderer].
 *
 * Like [SkySceneLayout] it is pure arithmetic on pixel sizes: no Compose state, no drawing,
 * unit-testable on the JVM. The same result is computed independently by the sky pass (for the
 * grounding paint) and by the GL host (for `setFraming`), so both layers always agree on where the
 * stone meets the ground.
 *
 * Composition rules:
 *
 *  1. **Half the first integration's linear size.** The first framing used the model's own 89 m window
 *     on phones (and an aspect-grown window on wide scenes). The visible width is now exactly that
 *     reference width divided by [LINEAR_SCALE_FRACTION] = 0.5, so every screen dimension of the ruin
 *     — façade width, column height, platform depth — is half what it was, at every scene size.
 *  2. **A left-hand, distant subject.** The platform centroid sits at [CENTER_FRACTION_FROM_LEFT]
 *     (30 % across). On a phone the detailed façade then spans roughly 6–54 % of the width, its
 *     platform running off the left edge and ending around two-thirds across; the remainder of the
 *     landscape stays open terrain and sky. The visible width at half scale is wide enough that the
 *     whole modelled façade is still inside the frame.
 *  3. **Farther back in the ground plane.** The toe targets [DISTANT_TOE_BELOW_HORIZON_FRACTION] of
 *     the scene below the horizon (about 0.798 of the scene) instead of the first framing's 0.84
 *     floor: more foreground field in front of the stone, the platform top still below the horizon,
 *     and only the tallest columns breaking the horizon line — the depth ordering of a ruin seen
 *     across a plain rather than stood in front of the viewer.
 *  4. **Stone never covers the readout.** The caption block sits bottom-start above the horizon, and
 *     moving the model left places more of it under that block. The toe is therefore also pushed down
 *     until the tallest structure that actually falls inside the readout's protected width
 *     ([READOUT_RIGHT_DP] from the left) clears the readout's bottom edge. Which structure that is
 *     depends on the scene width: the 19.2 m columns on phones, only the door frames on tablets.
 *
 * Scale note: the scene's orthographic width is measured in camera view space, and the fixed camera
 * is ≈9° off-axis and ≈3° down, so a world metre maps to `widthPx / visibleWidthMeters` pixels with
 * a cos-factor error of at most ~1.2 %, plus a sub-metre coupling from each structure's depth. The
 * pixel landmarks use the plain isotropic scale anchored at the toe (whose row is exact by
 * construction); `tools/persepolis/verify_framing.py` checks the landmarks against the file's full
 * lookAt/ortho matrices. The GL side always does the exact projection.
 *
 * With these values, the scene's own `autoFitHeight` never widens the view further (its requirement,
 * `aspect × 25 / baseFraction`, is always below the width chosen here), so the scale above is the
 * scale the GL camera actually uses.
 */
object PersepolisFraming {

    /** The model's own default visible width: the whole façade plus a margin of platform either side. */
    const val DEFAULT_VISIBLE_WIDTH_M = 89f

    /**
     * Linear size of the integrated ruin relative to the first integration's framing. 0.5 = every
     * screen dimension halved; implemented by dividing the reference visible width by this fraction.
     */
    const val LINEAR_SCALE_FRACTION = 0.5f

    /** Building-height cap of the reference (full-size) framing, kept so the half-scale is auditable. */
    const val REFERENCE_MAX_BUILDING_HEIGHT_FRACTION = 0.34f

    /** The building may occupy at most this fraction of the scene height (half the reference cap). */
    const val MAX_BUILDING_HEIGHT_FRACTION = REFERENCE_MAX_BUILDING_HEIGHT_FRACTION * LINEAR_SCALE_FRACTION

    /** World height of the whole group above the toe: 4.35 m of platform + the 19.2 m tallest column. */
    const val BUILDING_HEIGHT_M = 23.6f

    /** Top of the upper platform slab above the toe (`PLATFORM_Y` in Persepolis.kt). */
    const val PLATFORM_TOP_M = 4.35f

    /**
     * Top of the tallest left-hand frame above the toe: the great doorway at x = −25.6 m stands
     * 10.4 m on the platform.
     */
    const val GREAT_DOORWAY_TOP_M = PLATFORM_TOP_M + 10.4f

    /** Half the forecourt/platform extent in world x (the modelled slab spans x ≈ −66…+66 m). */
    const val PLATFORM_HALF_WIDTH_M = 66f

    /** Half the detailed façade's extent in world x (structures span x ≈ −42…+43.5 m). */
    const val FACADE_HALF_WIDTH_M = 43.5f

    /** Where the platform centroid lands in the viewport, as a fraction of the width from the left. */
    const val CENTER_FRACTION_FROM_LEFT = 0.30f

    /**
     * How far below the horizon the toe aims to sit, as a fraction of the scene height: the "farther
     * back" placement. The readout rule below may push it lower on narrow scenes.
     */
    const val DISTANT_TOE_BELOW_HORIZON_FRACTION = 0.098f

    /** Lowest the toe may be pushed (for readout clearance), as a fraction of the scene height. */
    const val MAX_TOE_FRACTION = 0.92f

    /**
     * Gap between the scene readout's bottom edge and the horizon, in dp: the readout column in
     * HeroSkyCanvas is padded `groundHeight + 12 dp` from the bottom, so its bottom edge is
     * `horizonY − 12 dp`. Kept here so both framing call sites convert the same number.
     */
    const val READOUT_INSET_DP = 12f

    /**
     * Minimum clear space between the readout's bottom edge and the tallest stone under it, in dp.
     * Sized to absorb the depth-coupling of the real projection: at this half scale a structure
     * behind the toe projects at most ~0.7 m (≈1.5 dp on a phone) higher than the isotropic estimate
     * used here, leaving ≈3.5 dp of true clearance — see tools/persepolis/verify_framing.py, which
     * checks every guarded structure under the file's exact matrices. Keeping this lean is what lets
     * the distant columns still break the horizon on small phones.
     */
    const val READOUT_CLEARANCE_DP = 5f

    /**
     * Protected readout width from the physical left edge, in dp: the caption block's start padding
     * (20 dp) plus a generous measure of its text. Only structures whose screen span enters this zone
     * constrain the toe; stone to its right may rise past the readout's baseline freely.
     */
    const val READOUT_RIGHT_DP = 250f

    /** Resolved framing for one scene size. Pixel rows are measured from the top of the scene. */
    data class Result(
        /** Passed straight to `PersepolisView.setFraming`. */
        val visibleWidthMeters: Float,
        /** Passed straight to `PersepolisView.setFraming`: [toeYPx] as a fraction of the scene height. */
        val baseFractionFromTop: Float,
        /** Passed straight to `PersepolisView.setFraming`: the platform centroid's viewport column. */
        val centerFractionFromLeft: Float,
        /** Pixel row of the platform's front toe. Exact: the GL camera solves for this point. */
        val toeYPx: Float,
        /** Approximate pixels per world metre (see the scale note above). */
        val pxPerMeter: Float,
        /** Pixel row of the platform top, approximate (± the depth coupling). */
        val platformTopYPx: Float,
        /** Pixel row of the tallest column's top, approximate. */
        val tallestColumnTopYPx: Float,
        /** Pixel row of the great doorway's cornice, approximate. */
        val greatDoorwayTopYPx: Float,
        /** Pixel column of the platform centroid, approximate. */
        val modelCenterXPx: Float,
        /** Pixel columns where the platform/forecourt ends, approximate; may fall off-canvas. */
        val platformLeftXPx: Float,
        val platformRightXPx: Float,
        /** Pixel columns bounding the detailed façade, approximate. */
        val facadeLeftXPx: Float,
        val facadeRightXPx: Float,
        /** Pixel column of the tallest column (x = +26.2 m), approximate. */
        val tallestColumnXPx: Float,
        /** Pixel column of the great doorway (x = −25.6 m), approximate. */
        val greatDoorwayXPx: Float,
        /** Pixel row of the tallest structure inside the readout's protected width, approximate. */
        val readoutGuardTopYPx: Float
    )

    /** A structure that can constrain the toe if it lands under the readout: centre x, top, half-width (m). */
    private data class GuardStructure(val worldX: Float, val topM: Float, val halfWidthM: Float)

    // The tall silhouettes, left to right, tops measured above the toe (platform top + structure).
    private val READOUT_GUARD_STRUCTURES = listOf(
        GuardStructure(-39.7f, 8.45f, 2.5f),                 // far-left frame on its lowered ledge
        GuardStructure(-34.4f, 9.0f, 2.7f),                  // background broken frame
        GuardStructure(-25.6f, GREAT_DOORWAY_TOP_M, 3.1f),   // the great doorway
        GuardStructure(-5.9f, PLATFORM_TOP_M + 7.8f, 2.7f),  // mid door frame
        GuardStructure(1.4f, PLATFORM_TOP_M + 9.9f, 3.0f),   // central niche frame
        GuardStructure(8.9f, 16.92f, 1.4f),                  // centre broken column stack
        GuardStructure(26.2f, BUILDING_HEIGHT_M, 1.1f),      // tallest column, 19.2 m over the platform
        GuardStructure(29.3f, PLATFORM_TOP_M + 15.7f, 1.1f)  // its pair
    )

    /**
     * Framing for a scene of [widthPx] × [heightPx].
     *
     * [horizonYPx] is the pixel row of altitude 0°; [readoutBottomPx] the readout's bottom edge
     * (`horizonY − READOUT_INSET_DP`); [readoutRightPx] the protected readout width
     * ([READOUT_RIGHT_DP] converted); [readoutClearancePx] the converted [READOUT_CLEARANCE_DP].
     * All density-dependent values are caller-converted so this object stays free of density and Dp.
     */
    fun forScene(
        widthPx: Float,
        heightPx: Float,
        horizonYPx: Float,
        readoutBottomPx: Float,
        readoutRightPx: Float,
        readoutClearancePx: Float
    ): Result {
        val w = widthPx.coerceAtLeast(1f)
        val h = heightPx.coerceAtLeast(1f)
        val aspect = w / h

        // Rule 1: half the reference framing's linear size, at every aspect.
        val referenceVisibleWidth = max(
            DEFAULT_VISIBLE_WIDTH_M,
            aspect * BUILDING_HEIGHT_M / REFERENCE_MAX_BUILDING_HEIGHT_FRACTION
        )
        val visibleWidth = referenceVisibleWidth / LINEAR_SCALE_FRACTION
        val pxPerMeter = w / visibleWidth
        val modelCenterX = w * CENTER_FRACTION_FROM_LEFT

        // Rule 4 (evaluated before the toe is placed): the tallest stone that lands under the readout.
        val readoutGuardTopM = READOUT_GUARD_STRUCTURES
            .filter { modelCenterX + (it.worldX - it.halfWidthM) * pxPerMeter < readoutRightPx }
            .maxOfOrNull { it.topM } ?: 0f

        // Rules 3+4: the distant toe, lowered only as far as the readout requires.
        val distantToeY = horizonYPx + h * DISTANT_TOE_BELOW_HORIZON_FRACTION
        val toeForReadout = readoutBottomPx + readoutClearancePx + readoutGuardTopM * pxPerMeter
        val toeY = min(max(distantToeY, toeForReadout), h * MAX_TOE_FRACTION)

        return Result(
            visibleWidthMeters = visibleWidth,
            baseFractionFromTop = toeY / h,
            centerFractionFromLeft = CENTER_FRACTION_FROM_LEFT,
            toeYPx = toeY,
            pxPerMeter = pxPerMeter,
            platformTopYPx = toeY - PLATFORM_TOP_M * pxPerMeter,
            tallestColumnTopYPx = toeY - BUILDING_HEIGHT_M * pxPerMeter,
            greatDoorwayTopYPx = toeY - GREAT_DOORWAY_TOP_M * pxPerMeter,
            modelCenterXPx = modelCenterX,
            platformLeftXPx = modelCenterX - PLATFORM_HALF_WIDTH_M * pxPerMeter,
            platformRightXPx = modelCenterX + PLATFORM_HALF_WIDTH_M * pxPerMeter,
            facadeLeftXPx = modelCenterX - FACADE_HALF_WIDTH_M * pxPerMeter,
            facadeRightXPx = modelCenterX + FACADE_HALF_WIDTH_M * pxPerMeter,
            tallestColumnXPx = modelCenterX + 26.2f * pxPerMeter,
            greatDoorwayXPx = modelCenterX - 25.6f * pxPerMeter,
            readoutGuardTopYPx = toeY - readoutGuardTopM * pxPerMeter
        )
    }
}
