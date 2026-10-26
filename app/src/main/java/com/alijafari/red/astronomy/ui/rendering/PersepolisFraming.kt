package com.alijafari.red.astronomy.ui.rendering

import kotlin.math.max
import kotlin.math.min

/**
 * Placement of the combined Persepolis + Mil-e Azhdaha relief inside the full-bleed Home scene.
 *
 * The combined model
 * ([com.alijafari.red.astronomy.ui.persepolis.PersepolisMilAzhdahaScene]) owns its fixed camera
 * and takes three framing inputs: how many metres of world width the viewport shows
 * (`visibleWidthMeters`), where the Persepolis platform's front toe — world point (0, 0, 8) —
 * lands vertically (`baseFractionFromTop`), and where the combined frame's anchor, world point
 * (0, 0, −2), lands horizontally (`centerFractionFromLeft`). This object computes those numbers
 * from the scene size, plus the pixel landmarks the Compose-side ground needs: the toe row, the
 * cut-back platform's footprint, and the Mil-e Azhdaha hillside's projected footprint for the
 * localized earth, sand and contact-shadow work in [SceneGroundRenderer].
 *
 * Like [SkySceneLayout] it is pure arithmetic on pixel sizes: no Compose state, no drawing,
 * unit-testable on the JVM. The same result is computed independently by the sky pass (for the
 * grounding paint) and by the GL host (for `setFraming`), so both layers always agree on where
 * the stone meets the ground.
 *
 * Composition rules:
 *
 *  1. **Half the first Persepolis integration's linear size, retained.** The replacement file's
 *     own 116 m default would make the Persepolis group far larger than the approved Home
 *     framing, so the existing reference-width formula is deliberately kept unchanged: the first
 *     integration's 89 m phone window (or aspect-grown equivalent) divided by
 *     [LINEAR_SCALE_FRACTION] = 0.5. Persepolis therefore lands at exactly its established
 *     half size; the combined file's own 2.04× Mil-e Azhdaha bake is then read at that same
 *     scene scale.
 *  2. **A left-hand, distant group.** The combined-frame anchor sits at
 *     [CENTER_FRACTION_FROM_LEFT] (30 % across). Persepolis, already baked 9.7 m left in the
 *     combined file, occupies the left distance; the tower stands near the middle on its
 *     stepped limestone slope; and the right-hand quarter remains open terrain and sky. On a
 *     phone the slope spans roughly 40–73 % of the width, with the tower centre near 54 %.
 *  3. **Farther back in the ground plane.** The Persepolis toe targets
 *     [DISTANT_TOE_BELOW_HORIZON_FRACTION] of the scene below the horizon (about 0.798 of the
 *     scene): more foreground field in front of the stone, the platform top still below the
 *     horizon, and only the tallest columns breaking the horizon line. The tower slope's front
 *     step projects about 1.55 view-metres lower than that toe, which is why the grounding
 *     gets its own slope-front landmark instead of pretending both monuments share one edge.
 *  4. **Stone never covers the readout.** The caption block sits bottom-start above the
 *     horizon. The toe is therefore also pushed down until the tallest structure that actually
 *     falls inside the readout's protected width ([READOUT_RIGHT_DP] from the left) clears the
 *     readout's bottom edge. Every Persepolis guard below is in combined-scene world x — the
 *     builder's local x plus the file's baked −9.7 m shift. The tower needs no separate guard:
 *     it is always to the right of, and lower than, the tallest Persepolis column, so whenever
 *     it could enter the protected width that taller column already constrains the toe more.
 *
 * Scale note: the scene's orthographic width is measured in camera view space, and the fixed
 * camera is ≈9° off-axis and ≈3° down. Persepolis landmarks use the plain isotropic scale
 * `widthPx / visibleWidthMeters`, as before; their error is sub-metre to about a metre. The
 * Mil group is 15.6 m toward the viewer and its slope reaches 43.2 m in world z, where that
 * approximation would misplace the footprint by several metres, so its centre and slope bounds
 * below are precomputed *view-space* offsets under the file's fixed camera. Multiplication by
 * the same pixels-per-metre then gives their projected columns/rows. `tools/persepolis/
 * verify_framing.py` checks both landmark sets against the full lookAt/ortho matrices; the GL
 * side always does the exact projection.
 *
 * With these values, the scene's own `autoFitHeight` never widens the view further (its
 * requirement, `aspect × 25 / baseFraction`, is always below the width chosen here), so the
 * scale above is the scale the GL camera actually uses.
 */
object PersepolisFraming {

    /** The first model's default visible width, retained as the auditable half-scale reference. */
    const val DEFAULT_VISIBLE_WIDTH_M = 89f

    /**
     * Linear size relative to the first Persepolis integration's framing. 0.5 = every screen
     * dimension halved; implemented by dividing the reference visible width by this fraction.
     */
    const val LINEAR_SCALE_FRACTION = 0.5f

    /** Building-height cap of the reference (full-size) framing, kept so the half-scale is auditable. */
    const val REFERENCE_MAX_BUILDING_HEIGHT_FRACTION = 0.34f

    /** The building may occupy at most this fraction of the scene height (half the reference cap). */
    const val MAX_BUILDING_HEIGHT_FRACTION = REFERENCE_MAX_BUILDING_HEIGHT_FRACTION * LINEAR_SCALE_FRACTION

    /** World height of the tallest Persepolis silhouette: 4.35 m platform + its 19.2 m column. */
    const val BUILDING_HEIGHT_M = 23.6f

    /** Top of the upper platform slab above the toe (`PLATFORM_Y` in the combined model). */
    const val PLATFORM_TOP_M = 4.35f

    /**
     * Top of the tallest left-hand frame above the toe: the great doorway, at combined-scene
     * world x = −35.3 m, stands 10.4 m on the platform.
     */
    const val GREAT_DOORWAY_TOP_M = PLATFORM_TOP_M + 10.4f

    /** Combined-scene world x of the great doorway (builder-local −25.6 m plus the −9.7 m bake). */
    const val GREAT_DOORWAY_X_M = -35.3f

    /** Combined-scene world x of the tallest column (builder-local +26.2 m plus the −9.7 m bake). */
    const val TALLEST_COLUMN_X_M = 16.5f

    /**
     * Outer forecourt extent in combined-scene world x. The replacement builder deliberately
     * cuts the empty platform wings back: its forecourt slab is centred at local x = +1.36 m
     * with width 97.27 m, so after the −9.7 m bake it spans −56.975…+40.295 m.
     */
    const val PLATFORM_LEFT_X_M = -56.98f
    const val PLATFORM_RIGHT_X_M = 40.30f

    /**
     * Detailed Persepolis structural extent in combined-scene world x: the builder's measured
     * content spans local −42.6…+46.0 m, hence −52.3…+36.3 m after the bake.
     */
    const val FACADE_LEFT_X_M = -52.3f
    const val FACADE_RIGHT_X_M = 36.3f

    /**
     * Mil-e Azhdaha tower centre as a view-space x offset from the combined-frame anchor.
     * The baked world centre is (40.7, 15.6) in XZ; under the off-axis fixed camera that
     * projects to +42.91 view-metres, not +40.7.
     */
    const val MIL_TOWER_CENTER_VIEW_OFFSET_M = 42.91f

    /**
     * Tower top as a view-space height above the Persepolis toe. The ruined stone reaches
     * world y ≈ 16.30 m; at the tower's nearer depth it projects to +16.19 view-metres.
     */
    const val MIL_TOWER_TOP_VIEW_HEIGHT_M = 16.19f

    /**
     * Projected bounds of the nine fixed sheets of the tower's stepped limestone hillside,
     * after the file's baked 2.04× scale, −0.20 rad yaw and (40.7, 1.1, 15.6) translation.
     * In world coordinates those sheets span x 13.68…75.72 m, y 0…6.06 m and z 0.39…43.24 m;
     * these are the corresponding fixed-camera view-space offsets used for grounding.
     */
    const val MIL_SLOPE_LEFT_VIEW_OFFSET_M = 17.12f
    const val MIL_SLOPE_RIGHT_VIEW_OFFSET_M = 76.85f
    const val MIL_SLOPE_TOP_VIEW_HEIGHT_M = 6.55f
    const val MIL_SLOPE_FRONT_BELOW_TOE_VIEW_M = 1.55f

    /** Where the combined-frame anchor lands in the viewport, as a fraction from the left. */
    const val CENTER_FRACTION_FROM_LEFT = 0.30f

    /**
     * How far below the horizon the toe aims to sit, as a fraction of the scene height: the
     * "farther back" placement. The readout rule below may push it lower on narrow scenes.
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
     * Minimum clear space between the readout's bottom edge and the tallest stone under it, in
     * dp. Sized to absorb the depth-coupling of the real projection: at this half scale a
     * Persepolis structure behind the toe projects at most ~0.7 m (≈1.5 dp on a phone) higher
     * than the isotropic estimate used here, leaving ≈3.5 dp of true clearance — see
     * tools/persepolis/verify_framing.py, which checks every guarded structure under the
     * file's exact matrices.
     */
    const val READOUT_CLEARANCE_DP = 5f

    /**
     * Protected readout width from the physical left edge, in dp: the caption block's start
     * padding (20 dp) plus a generous measure of its text. Only structures whose screen span
     * enters this zone constrain the toe; stone to its right may rise past the readout's
     * baseline freely.
     */
    const val READOUT_RIGHT_DP = 250f

    /** Resolved framing for one scene size. Pixel rows are measured from the top of the scene. */
    data class Result(
        /** Passed straight to `PersepolisMilAzhdahaView.setFraming`. */
        val visibleWidthMeters: Float,
        /** Passed straight to `setFraming`: [toeYPx] as a fraction of the scene height. */
        val baseFractionFromTop: Float,
        /** Passed straight to `setFraming`: the combined-frame anchor's viewport column. */
        val centerFractionFromLeft: Float,
        /** Pixel row of the Persepolis platform's front toe. Exact: the GL camera solves for it. */
        val toeYPx: Float,
        /** Pixels per view-space metre (see the scale note above). */
        val pxPerMeter: Float,
        /** Pixel row of the Persepolis platform top, approximate (± the depth coupling). */
        val platformTopYPx: Float,
        /** Pixel row of the tallest Persepolis column's top, approximate. */
        val tallestColumnTopYPx: Float,
        /** Pixel row of the great doorway's cornice, approximate. */
        val greatDoorwayTopYPx: Float,
        /** Pixel row of Mil-e Azhdaha's ruined top, from its fixed-camera view height. */
        val milTowerTopYPx: Float,
        /** Pixel column of the combined-frame anchor (0, 0, −2). Exact by construction. */
        val modelCenterXPx: Float,
        /** Pixel columns where the cut-back Persepolis forecourt ends; may fall off-canvas. */
        val platformLeftXPx: Float,
        val platformRightXPx: Float,
        /** Pixel columns bounding the detailed Persepolis façade, approximate. */
        val facadeLeftXPx: Float,
        val facadeRightXPx: Float,
        /** Pixel column of the tallest Persepolis column (world x = +16.5 m), approximate. */
        val tallestColumnXPx: Float,
        /** Pixel column of the great doorway (world x = −35.3 m), approximate. */
        val greatDoorwayXPx: Float,
        /** Pixel column of the Mil-e Azhdaha tower centre, from its view-space offset. */
        val milTowerCenterXPx: Float,
        /** Pixel columns bounding the fixed sheets of the tower's limestone slope. */
        val milSlopeLeftXPx: Float,
        val milSlopeRightXPx: Float,
        /** Pixel row of the highest fixed slope sheet, from its view-space height. */
        val milSlopeTopYPx: Float,
        /** Pixel row of the slope's front step — lower on screen than the Persepolis toe. */
        val milSlopeFrontYPx: Float,
        /** Pixel row of the tallest structure inside the readout's protected width, approximate. */
        val readoutGuardTopYPx: Float
    )

    /** A structure that can constrain the toe if it lands under the readout: centre x, top, half-width (m). */
    private data class GuardStructure(val worldX: Float, val topM: Float, val halfWidthM: Float)

    // The tall Persepolis silhouettes, left to right, in combined-scene world x (each builder
    // x shifted by the file's baked PERSEPOLIS_X = −9.7 m); tops measured above the toe.
    private val READOUT_GUARD_STRUCTURES = listOf(
        GuardStructure(-49.4f, 8.45f, 2.5f),                 // far-left frame on its lowered ledge
        GuardStructure(-44.1f, 9.0f, 2.7f),                  // background broken frame
        GuardStructure(GREAT_DOORWAY_X_M, GREAT_DOORWAY_TOP_M, 3.1f),
        GuardStructure(-15.6f, PLATFORM_TOP_M + 7.8f, 2.7f), // mid door frame
        GuardStructure(-8.3f, PLATFORM_TOP_M + 9.9f, 3.0f),  // central niche frame
        GuardStructure(-0.8f, 16.92f, 1.4f),                 // centre broken column stack
        GuardStructure(TALLEST_COLUMN_X_M, BUILDING_HEIGHT_M, 1.1f),
        GuardStructure(19.6f, PLATFORM_TOP_M + 15.7f, 1.1f)  // the tallest column's pair
    )

    /**
     * Framing for a scene of [widthPx] × [heightPx].
     *
     * [horizonYPx] is the pixel row of altitude 0°; [readoutBottomPx] the readout's bottom
     * edge (`horizonY − READOUT_INSET_DP`); [readoutRightPx] the protected readout width
     * ([READOUT_RIGHT_DP] converted); [readoutClearancePx] the converted
     * [READOUT_CLEARANCE_DP]. All density-dependent values are caller-converted so this
     * object stays free of density and Dp.
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

        // Rule 1: half the first Persepolis framing's linear size, at every aspect.
        val referenceVisibleWidth = max(
            DEFAULT_VISIBLE_WIDTH_M,
            aspect * BUILDING_HEIGHT_M / REFERENCE_MAX_BUILDING_HEIGHT_FRACTION
        )
        val visibleWidth = referenceVisibleWidth / LINEAR_SCALE_FRACTION
        val pxPerMeter = w / visibleWidth
        val modelCenterX = w * CENTER_FRACTION_FROM_LEFT

        // Rule 4 (evaluated before the toe is placed): the tallest stone under the readout.
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
            milTowerTopYPx = toeY - MIL_TOWER_TOP_VIEW_HEIGHT_M * pxPerMeter,
            modelCenterXPx = modelCenterX,
            platformLeftXPx = modelCenterX + PLATFORM_LEFT_X_M * pxPerMeter,
            platformRightXPx = modelCenterX + PLATFORM_RIGHT_X_M * pxPerMeter,
            facadeLeftXPx = modelCenterX + FACADE_LEFT_X_M * pxPerMeter,
            facadeRightXPx = modelCenterX + FACADE_RIGHT_X_M * pxPerMeter,
            tallestColumnXPx = modelCenterX + TALLEST_COLUMN_X_M * pxPerMeter,
            greatDoorwayXPx = modelCenterX + GREAT_DOORWAY_X_M * pxPerMeter,
            milTowerCenterXPx = modelCenterX + MIL_TOWER_CENTER_VIEW_OFFSET_M * pxPerMeter,
            milSlopeLeftXPx = modelCenterX + MIL_SLOPE_LEFT_VIEW_OFFSET_M * pxPerMeter,
            milSlopeRightXPx = modelCenterX + MIL_SLOPE_RIGHT_VIEW_OFFSET_M * pxPerMeter,
            milSlopeTopYPx = toeY - MIL_SLOPE_TOP_VIEW_HEIGHT_M * pxPerMeter,
            milSlopeFrontYPx = toeY + MIL_SLOPE_FRONT_BELOW_TOE_VIEW_M * pxPerMeter,
            readoutGuardTopYPx = toeY - readoutGuardTopM * pxPerMeter
        )
    }
}
