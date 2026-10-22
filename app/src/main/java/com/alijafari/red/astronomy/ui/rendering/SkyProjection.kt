package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaFraming
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaMath
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Maps sky directions (azimuth, altitude) to pixels for one render target. The sky computation itself is the same for
 * every target; only this mapping differs.
 */
interface SkyProjection {

    /**
     * Screen position of a sky direction. Points behind the camera return [OFF_CANVAS]. Points below the horizon are
     * still placed, so that glow which spills above the horizon can be drawn.
     */
    fun project(azimuthDeg: Double, altitudeDeg: Double, width: Float, height: Float, latitudeDeg: Double): Offset

    /** Position for a drawable object. Objects this target hides return [OFF_CANVAS] and draw nothing. */
    fun objectPosition(azimuthDeg: Double, altitudeDeg: Double, width: Float, height: Float, latitudeDeg: Double): Offset

    /** Lowest pixel row where objects may be drawn. Everything below it is clipped. */
    fun clipBottomPx(height: Float): Float

    /**
     * Pixel row where altitude 0° sits. Layers that need to agree with the horizon — the atmosphere
     * gradients, the ground band, the mist shelf — read it from here instead of assuming a fraction, so a
     * surface can move its horizon without leaving a stale line behind.
     */
    fun horizonY(width: Float, height: Float): Float = height * HeroSkyProjection.HORIZON_FRACTION

    /** Vertical anchor of the anti-solar Belt of Venus glow, given the projected anti-solar point. */
    fun beltAnchorY(antiCenter: Offset, horizonY: Float): Float

    /**
     * Screen angle, in degrees and in canvas coordinates (y down), from the Moon's centre toward the Sun. The Moon
     * renderer rotates its lit limb to this angle.
     */
    fun moonLimbAngleDeg(
        moonAzimuthDeg: Double,
        moonAltitudeDeg: Double,
        sunAzimuthDeg: Double,
        sunAltitudeDeg: Double,
        width: Float,
        height: Float,
        latitudeDeg: Double
    ): Double

    companion object {
        /** A position far outside any canvas. Drawing there is clipped, so it draws nothing. */
        val OFF_CANVAS: Offset = Offset(-1_000_000f, -1_000_000f)
    }
}

/**
 * The Home Sky Canvas mapping, with a configurable horizon.
 *
 * The same cylindrical, equator-facing mapping as [HeroSkyProjection], but the row that altitude 0° maps to
 * is an argument: [horizonFraction] of the canvas height. The full-bleed Home scene uses a low horizon
 * ([SkySceneLayout.HORIZON_FRACTION]) so the sky dominates the frame; the legacy Home card keeps 0.85.
 */
class SceneProjection(
    private val horizonFraction: Float = HeroSkyProjection.HORIZON_FRACTION
) : SkyProjection {

    override fun project(azimuthDeg: Double, altitudeDeg: Double, width: Float, height: Float, latitudeDeg: Double): Offset =
        HeroSkyProjection.project(azimuthDeg, altitudeDeg, width, height, latitudeDeg, horizonFraction)

    // The horizon clip hides below-horizon objects, so the object position is the plain projection.
    override fun objectPosition(azimuthDeg: Double, altitudeDeg: Double, width: Float, height: Float, latitudeDeg: Double): Offset =
        project(azimuthDeg, altitudeDeg, width, height, latitudeDeg)

    override fun clipBottomPx(height: Float): Float = height * horizonFraction

    override fun horizonY(width: Float, height: Float): Float = height * horizonFraction

    override fun beltAnchorY(antiCenter: Offset, horizonY: Float): Float = horizonY - 18f

    override fun moonLimbAngleDeg(
        moonAzimuthDeg: Double,
        moonAltitudeDeg: Double,
        sunAzimuthDeg: Double,
        sunAltitudeDeg: Double,
        width: Float,
        height: Float,
        latitudeDeg: Double
    ): Double = CoordinateEngine.calculateMoonLimbScreenAngleDeg(
        moonAzimuthDeg = moonAzimuthDeg,
        moonAltitudeDeg = moonAltitudeDeg,
        sunAzimuthDeg = sunAzimuthDeg,
        sunAltitudeDeg = sunAltitudeDeg
    )
}

/**
 * The Home Sky Canvas card mapping. Every call forwards to the existing [HeroSkyProjection] and the existing engine,
 * so the Home hero draws exactly what it drew before this interface existed.
 */
object HeroProjection : SkyProjection by SceneProjection()

/**
 * The projection of the panorama camera. A sky direction lands on the pixel where the photograph shows that same
 * equatorial point, because it uses the same camera basis, the same field of view and the same
 * [SkyPanoramaMath.directionFromHorizontal] as the panorama shader's inputs.
 *
 * The camera is fixed by [lstDeg], [latitudeDeg] and [framing]. The viewport size is an argument of each call, so the
 * projection follows the window's aspect ratio without being rebuilt.
 */
class CameraProjection(
    private val lstDeg: Double,
    private val latitudeDeg: Double,
    framing: SkyPanoramaFraming
) : SkyProjection {

    private val basis = framing.basis(lstDeg, latitudeDeg)
    private val tanHalfY = tan(Math.toRadians(framing.fovYDeg.toDouble()) / 2.0)

    /** Projects an equatorial unit vector to pixels, or returns null when it lies behind the camera. */
    private fun projectDirection(d: DoubleArray, width: Float, height: Float): DoubleArray? {
        val depth = dot(d, basis.forward)
        if (depth <= 1e-9) return null
        val tanHalfX = tanHalfY * width / height
        val ndcX = dot(d, basis.right) / (depth * tanHalfX)
        val ndcY = dot(d, basis.up) / (depth * tanHalfY)
        return doubleArrayOf((ndcX + 1.0) / 2.0 * width, (1.0 - ndcY) / 2.0 * height)
    }

    override fun project(azimuthDeg: Double, altitudeDeg: Double, width: Float, height: Float, latitudeDeg: Double): Offset {
        if (width <= 0f || height <= 0f) return SkyProjection.OFF_CANVAS
        val d = SkyPanoramaMath.directionFromHorizontal(azimuthDeg, altitudeDeg, this.latitudeDeg, lstDeg)
        val p = projectDirection(d, width, height) ?: return SkyProjection.OFF_CANVAS
        return Offset(p[0].toFloat(), p[1].toFloat())
    }

    // The camera's view runs below the geometric horizon, where the photograph shows sky but the object is hidden.
    // Objects under the horizon are therefore dropped here. Home drops them through its horizon clip.
    override fun objectPosition(azimuthDeg: Double, altitudeDeg: Double, width: Float, height: Float, latitudeDeg: Double): Offset {
        if (altitudeDeg < 0.0) return SkyProjection.OFF_CANVAS
        return project(azimuthDeg, altitudeDeg, width, height, latitudeDeg)
    }

    // No horizon clip is needed: the photograph covers the whole screen, and objects are culled by altitude above.
    override fun clipBottomPx(height: Float): Float = height

    override fun horizonY(width: Float, height: Float): Float {
        if (width <= 0f || height <= 0f) return height
        return project(0.0, 0.0, width, height, latitudeDeg).y.coerceIn(0f, height)
    }

    override fun beltAnchorY(antiCenter: Offset, horizonY: Float): Float = antiCenter.y

    override fun moonLimbAngleDeg(
        moonAzimuthDeg: Double,
        moonAltitudeDeg: Double,
        sunAzimuthDeg: Double,
        sunAltitudeDeg: Double,
        width: Float,
        height: Float,
        latitudeDeg: Double
    ): Double {
        if (width <= 0f || height <= 0f) return 0.0
        val moon = SkyPanoramaMath.directionFromHorizontal(moonAzimuthDeg, moonAltitudeDeg, this.latitudeDeg, lstDeg)
        val sun = SkyPanoramaMath.directionFromHorizontal(sunAzimuthDeg, sunAltitudeDeg, this.latitudeDeg, lstDeg)
        // Tangent at the Moon that points toward the Sun along the great circle between them.
        val along = dot(sun, moon)
        val tangent = DoubleArray(3) { i -> sun[i] - along * moon[i] }
        val length = sqrt(dot(tangent, tangent))
        if (length < 1e-12) return 0.0
        val step = 1e-6
        val stepped = DoubleArray(3) { i -> moon[i] + step * tangent[i] / length }
        val from = projectDirection(moon, width, height) ?: return 0.0
        val to = projectDirection(stepped, width, height) ?: return 0.0
        return Math.toDegrees(atan2(to[1] - from[1], to[0] - from[0]))
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

/**
 * Which surface is drawing the sky. The target chooses everything that differs between the Home hero and the
 * full-screen backdrop: the landscape, the camera and the panorama framing. The sky model is shared.
 */
enum class SkyRenderTarget(
    /** Whether the horizon landscape silhouette is drawn. Home keeps it; the backdrop has none. */
    val drawsLandscape: Boolean,
    /** The camera used by the panorama layer on this surface. */
    val panoramaFraming: SkyPanoramaFraming,
    /**
     * Whether this target is the full-bleed Home scene: a low horizon, the layered ground band, the cloud
     * veils and the observer. The card and the backdrop keep the older single-silhouette landscape.
     */
    val drawsScene: Boolean = false
) {
    HOME_HERO(drawsLandscape = true, panoramaFraming = SkyPanoramaFraming.HOME_ZENITH),
    /** The full-bleed Home scene behind the header and the feed. */
    HOME_SCENE(drawsLandscape = true, panoramaFraming = SkyPanoramaFraming.HOME_ZENITH, drawsScene = true),
    APP_BACKDROP(drawsLandscape = false, panoramaFraming = SkyPanoramaFraming.APP_BACKDROP);

    /** The projection for a computed frame on this target. */
    fun projectionFor(frame: SkySceneFrame): SkyProjection = when (this) {
        HOME_HERO -> HeroProjection
        HOME_SCENE -> sceneProjection
        APP_BACKDROP -> CameraProjection(frame.lastDeg, frame.latitudeDeg, panoramaFraming)
    }

    companion object {
        /** One immutable projection for every scene frame: only the canvas size varies. */
        val sceneProjection: SceneProjection = SceneProjection(SkySceneLayout.HORIZON_FRACTION)
    }
}
