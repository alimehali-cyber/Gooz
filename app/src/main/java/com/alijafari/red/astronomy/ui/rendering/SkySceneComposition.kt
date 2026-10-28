package com.alijafari.red.astronomy.ui.rendering

/**
 * Geometry of the full-bleed Home scene.
 *
 * One composition is shared by every layer that has to agree on where the ground is: the projection
 * ([SceneProjection]), the ground band ([SceneGroundRenderer]) and the cloud veils
 * ([CloudVeilRenderer]). It is pure arithmetic on the canvas size, so it can be unit-tested on the JVM
 * and used off the main thread.
 *
 * The scene is laid out the way a photograph of a sky is framed: most of the frame is sky, the horizon
 * sits low, and a band of ground fills the bottom. The numbers below are fractions of the canvas height.
 */
object SkySceneLayout {

    /**
     * Canvas fraction where altitude 0° maps on the Home scene.
     *
     * The legacy Home card keeps [HeroSkyProjection.HORIZON_FRACTION] (0.85). The scene puts its horizon at
     * 0.70 of its own height, and the scene itself takes about 0.52 of the viewport, so altitude 0° lands at
     * roughly 36 % of the screen — high enough that the sky owns the top of the phone, low enough that the
     * ground is a strip below the stars instead of an empty field.
     */
    const val HORIZON_FRACTION = 0.70f

    /** Amplitude of the distant ridge line, as a fraction of the scene height. */
    const val FAR_RIDGE_FRACTION = 0.034f

    /** Amplitude of the mid ridge line, as a fraction of the scene height. */
    const val MID_RIDGE_FRACTION = 0.020f

    /** Top of the solid ground band, below the horizon, as a fraction of the scene height. */
    const val GROUND_TOP_FRACTION = 0.052f

    /** Builds the composition for a canvas of [widthPx] x [heightPx] pixels. */
    fun composition(
        widthPx: Float,
        heightPx: Float,
        horizonFraction: Float = HORIZON_FRACTION
    ): SceneComposition {
        val w = widthPx.coerceAtLeast(1f)
        val h = heightPx.coerceAtLeast(1f)
        val horizonY = h * horizonFraction.coerceIn(0.30f, 0.92f)
        val groundTopY = horizonY + h * GROUND_TOP_FRACTION
        return SceneComposition(
            widthPx = w,
            heightPx = h,
            horizonY = horizonY,
            groundTopY = groundTopY,
            farRidgeAmplitude = h * FAR_RIDGE_FRACTION,
            midRidgeAmplitude = h * MID_RIDGE_FRACTION
        )
    }
}

/**
 * Resolved pixel geometry for one scene frame. Produced by [SkySceneLayout.composition].
 */
data class SceneComposition(
    val widthPx: Float,
    val heightPx: Float,
    /** Where altitude 0° sits, and where the distant ridge line is anchored. */
    val horizonY: Float,
    /** Where the solid ground band starts. Between [horizonY] and here is the ridge band. */
    val groundTopY: Float,
    val farRidgeAmplitude: Float,
    val midRidgeAmplitude: Float
) {
    /** Vertical extent of the sky above the horizon, in pixels. */
    val skyHeightPx: Float get() = horizonY

    /** Vertical extent of everything below the horizon, in pixels. */
    val groundHeightPx: Float get() = (heightPx - horizonY).coerceAtLeast(1f)
}
