package com.alijafari.red.astronomy.ui.rendering

import kotlin.math.max
import kotlin.math.min

/**
 * Placement of the Persepolis relief inside the full-bleed Home scene.
 *
 * The ruin itself ([com.alijafari.red.astronomy.ui.persepolis.PersepolisScene]) owns its fixed camera
 * and takes exactly two framing inputs: how many metres of world width the viewport shows
 * (`visibleWidthMeters`) and where the platform's front toe — world point (0, 0, 8) — lands in the
 * viewport (`baseFractionFromTop`). This object computes those two numbers from the scene size, plus
 * the resulting pixel landmarks the Compose-side ground needs (the toe row, for the contact shadow and
 * the sand apron in [SceneGroundRenderer]).
 *
 * Like [SkySceneLayout] it is pure arithmetic on pixel sizes: no Compose state, no drawing, unit-testable
 * on the JVM. The same result is computed independently by the sky pass (for the grounding paint) and by
 * the GL host (for `setFraming`), so both layers always agree on where the stone meets the ground.
 *
 * Composition rules, in priority order:
 *
 *  1. **The whole façade fits.** On phone-shaped scenes the visible width stays at the model's own
 *     default, [DEFAULT_VISIBLE_WIDTH_M] (89 m): every modelled structure — the far-left frame on its
 *     ledge at x ≈ −40 m through the stair façade's lion-and-bull panel ending at x ≈ +43.5 m — is on
 *     screen, and the platform runs off both edges exactly as the model intends.
 *  2. **The ruin never swallows the sky.** On wide scenes (tablets, landscape) a fixed 89 m window would
 *     make the 23.6 m building more than half the scene tall, up into the header. The visible width
 *     therefore grows with aspect so the building is never more than [MAX_BUILDING_HEIGHT_FRACTION] of
 *     the scene height; the platform (124–132 m of it) still fills the width.
 *  3. **Columns against the sky, platform in the ground band.** The toe sits at [MIN_TOE_FRACTION] of
 *     the scene or lower — inside the ground band, with a strip of Compose-drawn foreground below it —
 *     which puts the platform top (4.35 m) under the horizon and the 19.2 m columns through it.
 *  4. **Stone never covers the readout.** The caption block (clock, condition, date) sits bottom-start,
 *     just above the horizon. The tallest structure on that side is the great doorway at x = −25.6 m,
 *     [GREAT_DOORWAY_TOP_M] above the toe. The toe is pushed down as far as [MAX_TOE_FRACTION] so that
 *     doorway's cornice stays below the readout's bottom edge. This matters because the GL surface
 *     composites above the Compose layers inside its bounds (see PersepolisLandscape): opaque stone
 *     would paint over the text, so the geometry is arranged to make that impossible instead.
 *
 * Scale note: the scene's orthographic width is measured in camera view space, and the fixed camera is
 * ≈9° off-axis and ≈3° down, so a world metre maps to `widthPx / visibleWidthMeters` pixels with a
 * cos-factor error of at most ~1.2 %, plus a sub-metre height coupling from each structure's depth
 * (farther structures project slightly higher). The pixel landmarks below use the plain isotropic scale,
 * anchored at the toe (whose row is exact by construction); the readout clearance includes headroom for
 * that coupling, and `tools/persepolis/verify_framing.py` checks the approximation against the file's
 * full lookAt/ortho matrices. The GL side always does the exact projection — this object only decides
 * the framing and tells the ground painter where the contact line is.
 *
 * With the values this object returns, [com.alijafari.red.astronomy.ui.persepolis.PersepolisScene]'s own
 * `autoFitHeight` never needs to widen the view further (its requirement is
 * `aspect × 25 / baseFraction`, always below the width chosen here), so the scale above is the scale
 * the GL camera actually uses.
 */
object PersepolisFraming {

    /** The model's own default visible width: the whole façade plus a margin of platform either side. */
    const val DEFAULT_VISIBLE_WIDTH_M = 89f

    /** World height of the whole group above the toe: 4.35 m of platform + the 19.2 m tallest column. */
    const val BUILDING_HEIGHT_M = 23.6f

    /** Top of the upper platform slab above the toe (`PLATFORM_Y` in Persepolis.kt). */
    const val PLATFORM_TOP_M = 4.35f

    /**
     * Top of the tallest left-hand structure above the toe: the great doorway at x = −25.6 m stands
     * 10.4 m on the platform. This is the structure that could reach the readout, so it is the one the
     * toe placement protects.
     */
    const val GREAT_DOORWAY_TOP_M = PLATFORM_TOP_M + 10.4f

    /** Highest the toe may sit, as a fraction of the scene height. Below this row is foreground. */
    const val MIN_TOE_FRACTION = 0.84f

    /** Lowest the toe may be pushed (for readout clearance), as a fraction of the scene height. */
    const val MAX_TOE_FRACTION = 0.96f

    /** The building may occupy at most this fraction of the scene height (the wide-screen cap). */
    const val MAX_BUILDING_HEIGHT_FRACTION = 0.34f

    /**
     * Gap between the scene readout's bottom edge and the horizon, in dp: the readout column in
     * HeroSkyCanvas is padded `groundHeight + 12 dp` from the bottom, so its bottom edge is
     * `horizonY − 12 dp`. Kept here so both framing call sites (the GL host and the sky pass) convert
     * the same number; if the readout padding changes, this changes with it.
     */
    const val READOUT_INSET_DP = 12f

    /**
     * Minimum clear space between the readout's bottom edge and the great doorway's cornice, in dp.
     * Sized to absorb the depth-coupling of the real projection (the doorway stands ~11 m behind the
     * toe, so it projects up to ~0.7 m higher than the isotropic estimate used here).
     */
    const val READOUT_CLEARANCE_DP = 10f

    /** Resolved framing for one scene size. Pixel rows are measured from the top of the scene. */
    data class Result(
        /** Passed straight to `PersepolisView.setFraming`. */
        val visibleWidthMeters: Float,
        /** Passed straight to `PersepolisView.setFraming`: [toeYPx] as a fraction of the scene height. */
        val baseFractionFromTop: Float,
        /** Pixel row of the platform's front toe. Exact: the GL camera solves for this point. */
        val toeYPx: Float,
        /** Approximate pixels per world metre (see the scale note above). */
        val pxPerMeter: Float,
        /** Pixel row of the platform top, approximate (± the depth coupling). */
        val platformTopYPx: Float,
        /** Pixel row of the tallest column's top, approximate. */
        val tallestColumnTopYPx: Float,
        /** Pixel row of the great doorway's cornice, approximate (the readout constraint). */
        val greatDoorwayTopYPx: Float
    )

    /**
     * Framing for a scene of [widthPx] × [heightPx].
     *
     * [readoutBottomPx] is the pixel row of the readout's bottom edge (`horizonY − READOUT_INSET_DP`)
     * and [readoutClearancePx] the converted [READOUT_CLEARANCE_DP]; both are caller-converted so this
     * object stays free of density and Dp.
     */
    fun forScene(
        widthPx: Float,
        heightPx: Float,
        readoutBottomPx: Float,
        readoutClearancePx: Float
    ): Result {
        val w = widthPx.coerceAtLeast(1f)
        val h = heightPx.coerceAtLeast(1f)
        val aspect = w / h

        // Rules 1+2: full façade on phones; widen on wide scenes so the building's height is capped.
        val visibleWidth = max(
            DEFAULT_VISIBLE_WIDTH_M,
            aspect * BUILDING_HEIGHT_M / MAX_BUILDING_HEIGHT_FRACTION
        )
        val pxPerMeter = w / visibleWidth

        // Rules 3+4: the toe as high as allowed, but low enough that the great doorway's cornice
        // clears the readout; never below MAX_TOE_FRACTION.
        val toeForReadout = readoutBottomPx + readoutClearancePx + GREAT_DOORWAY_TOP_M * pxPerMeter
        val toeY = min(max(h * MIN_TOE_FRACTION, toeForReadout), h * MAX_TOE_FRACTION)

        return Result(
            visibleWidthMeters = visibleWidth,
            baseFractionFromTop = toeY / h,
            toeYPx = toeY,
            pxPerMeter = pxPerMeter,
            platformTopYPx = toeY - PLATFORM_TOP_M * pxPerMeter,
            tallestColumnTopYPx = toeY - BUILDING_HEIGHT_M * pxPerMeter,
            greatDoorwayTopYPx = toeY - GREAT_DOORWAY_TOP_M * pxPerMeter
        )
    }
}
