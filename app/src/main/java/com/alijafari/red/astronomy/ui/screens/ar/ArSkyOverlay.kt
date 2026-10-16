package com.alijafari.red.astronomy.ui.screens.ar

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ar.android.ArGlyphCache
import com.alijafari.red.astronomy.ar.core.ArBodyKind
import com.alijafari.red.astronomy.ar.core.ArBodyState
import com.alijafari.red.astronomy.ar.core.ArCameraModel
import com.alijafari.red.astronomy.ar.core.ArConstellation
import com.alijafari.red.astronomy.ar.core.ArPhotometry
import com.alijafari.red.astronomy.ar.core.ArPlaced
import com.alijafari.red.astronomy.ar.core.ArRefraction
import com.alijafari.red.astronomy.ar.core.ArSkyFrame
import com.alijafari.red.astronomy.ar.core.ArSkyLighting
import com.alijafari.red.astronomy.ar.core.ArSkySnapshot
import com.alijafari.red.astronomy.ar.core.ArStarCatalog
import com.alijafari.red.astronomy.ar.core.M3
import com.alijafari.red.astronomy.ar.core.SkyAtmosphere
import com.alijafari.red.astronomy.ar.core.SkyRenderModel
import com.alijafari.red.astronomy.domain.CelestialObject
import com.alijafari.red.astronomy.domain.ObjectType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** One object as drawn this frame (for hit-testing, labels and the reticle). */
class ArDrawnItem(
    val obj: CelestialObject,
    val x: Float,
    val y: Float,
    val radiusPx: Float,
    val altDeg: Double,
    /** Lower = more important (used to pick between overlapping objects). */
    val priority: Double
)

class ArOverlayOptions(
    val cameraMode: Boolean,
    val zoom: Double,
    val density: Float,
    val showStars: Boolean,
    val showConstellations: Boolean,
    val showPlanets: Boolean,
    val showMoon: Boolean,
    val showSun: Boolean,
    val showDeepSky: Boolean,
    val showSatellites: Boolean,
    val showMeteors: Boolean,
    val showNames: Boolean,
    val isFa: Boolean,
    val timeMs: Long,
    val selectedId: String?,
    val accent: Color
)

/** Reusable per-screen state: paints and the items of the last frame. */
class ArOverlayState {
    val items = ArrayList<ArDrawnItem>(256)
    internal val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    internal val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    internal val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val rect = RectF()
    internal val path = Path()
    internal val p = FloatArray(2)
    internal val q = FloatArray(2)
    internal val r = FloatArray(2)
    internal val cam = DoubleArray(3)
    internal val cam2 = DoubleArray(3)
    internal val look = ArPhotometry.PointLook()
    internal val tmp = DoubleArray(3)

    /** The item nearest to the reticle this frame, if any. */
    var aimed: ArDrawnItem? = null
        internal set
}

object ArSkyOverlay {

    private val deepSkyTypes = setOf(
        ObjectType.DEEP_SKY, ObjectType.GALAXY, ObjectType.NEBULA, ObjectType.STAR_CLUSTER,
        ObjectType.GLOBULAR_CLUSTER, ObjectType.BLACK_HOLE
    )

    /**
     * Draws the whole AR sky overlay (everything except the GL sky background and the HUD) and records
     * the drawn items in [state] for hit-testing.
     */
    fun draw(
        scope: DrawScope,
        state: ArOverlayState,
        snapshot: ArSkySnapshot,
        lighting: ArSkyLighting?,
        camera: ArCameraModel,
        stars: ArStarCatalog?,
        constellations: List<ArConstellation>,
        catalogue: List<CelestialObject>,
        satellites: List<ArSatelliteInfo>,
        glyphs: ArGlyphCache,
        opts: ArOverlayOptions,
        textMeasurer: TextMeasurer,
        fontFamily: FontFamily,
        aimRadiusPx: Float
    ) {
        state.items.clear()
        state.aimed = null
        val w = scope.size.width
        val h = scope.size.height
        val sunAlt = snapshot.sun.placed.altDeg
        // Constellation figures and faint glyphs fade as the sky brightens.
        val darkness = if (opts.cameraMode) (1.0 - 0.6 * SkyRenderModel.smoothstep(-6.0, 2.0, sunAlt))
        else (1.0 - SkyRenderModel.smoothstep(-9.0, -1.0, sunAlt))

        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            if (opts.showConstellations) drawConstellations(nc, state, snapshot, camera, constellations, opts, darkness, w, h)
            drawHorizon(nc, state, camera, opts, w, h)
            if (opts.showDeepSky) drawDeepSky(nc, state, snapshot, lighting, camera, catalogue, glyphs, opts, w, h)
            if (opts.showStars && stars != null) drawStars(nc, state, snapshot, lighting, camera, stars, glyphs, opts, w, h)
            drawBodies(nc, state, snapshot, lighting, camera, catalogue, glyphs, opts, w, h)
            if (opts.showSatellites) drawSatellites(nc, state, snapshot, camera, catalogue, satellites, glyphs, opts, w, h)
            if (opts.showMeteors) drawRadiants(nc, state, snapshot, camera, catalogue, opts, w, h)
            collectCatalogueStars(state, snapshot, camera, catalogue, stars, opts, w, h)
        }

        // Aimed object: nearest to the reticle, ties broken by importance.
        val cx = w / 2f; val cy = h / 2f
        var best: ArDrawnItem? = null
        var bestScore = Double.MAX_VALUE
        for (it in state.items) {
            val d = hypot((it.x - cx).toDouble(), (it.y - cy).toDouble())
            if (d > aimRadiusPx + it.radiusPx * 0.5) continue
            val score = d + it.priority * 2.0
            if (score < bestScore) { bestScore = score; best = it }
        }
        state.aimed = best

        drawLabels(scope, state, snapshot, camera, constellations, opts, textMeasurer, fontFamily, darkness, w, h)
    }

    // ------------------------------------------------------------------------------------------------
    // Constellations
    // ------------------------------------------------------------------------------------------------

    private fun drawConstellations(
        nc: android.graphics.Canvas, state: ArOverlayState, snapshot: ArSkySnapshot, camera: ArCameraModel,
        constellations: List<ArConstellation>, opts: ArOverlayOptions, darkness: Double, w: Float, h: Float
    ) {
        if (darkness <= 0.02 || snapshot.starEnu.isEmpty()) return
        val paint = state.linePaint
        paint.strokeWidth = 1.05f * opts.density
        paint.color = android.graphics.Color.argb((255 * 0.38 * darkness).toInt().coerceIn(0, 255), 143, 172, 224)
        val e = snapshot.starEnu
        for (c in constellations) {
            val s = c.segments
            var k = 0
            while (k + 1 < s.size) {
                val a = s[k] * 3; val b = s[k + 1] * 3
                k += 2
                if (!opts.cameraMode && e[a + 2] < -0.004f && e[b + 2] < -0.004f) continue
                drawSegment(nc, state, camera, e[a].toDouble(), e[a + 1].toDouble(), e[a + 2].toDouble(),
                    e[b].toDouble(), e[b + 1].toDouble(), e[b + 2].toDouble(), paint, w, h)
            }
        }
    }

    /** Draws a great-circle segment (a straight line in a pinhole image), clipped at the image plane. */
    private fun drawSegment(
        nc: android.graphics.Canvas, state: ArOverlayState, camera: ArCameraModel,
        ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, paint: Paint, w: Float, h: Float
    ) {
        val ca = state.cam; val cb = state.cam2
        camera.toCamera(ax, ay, az, ca)
        camera.toCamera(bx, by, bz, cb)
        val near = 0.02
        if (ca[2] <= near && cb[2] <= near) return
        if (ca[2] <= near) {
            val t = (near - ca[2]) / (cb[2] - ca[2])
            for (i in 0..2) ca[i] = ca[i] + t * (cb[i] - ca[i])
        } else if (cb[2] <= near) {
            val t = (near - cb[2]) / (ca[2] - cb[2])
            for (i in 0..2) cb[i] = cb[i] + t * (ca[i] - cb[i])
        }
        if (!camera.projectCamera(ca[0] / ca[2], ca[1] / ca[2], state.p)) return
        if (!camera.projectCamera(cb[0] / cb[2], cb[1] / cb[2], state.q)) return
        val p = state.p; val q = state.q
        if ((p[0] < -w && q[0] < -w) || (p[0] > 2 * w && q[0] > 2 * w) || (p[1] < -h && q[1] < -h) || (p[1] > 2 * h && q[1] > 2 * h)) return
        nc.drawLine(p[0], p[1], q[0], q[1], paint)
    }

    // ------------------------------------------------------------------------------------------------
    // Horizon and cardinal points
    // ------------------------------------------------------------------------------------------------

    private fun drawHorizon(nc: android.graphics.Canvas, state: ArOverlayState, camera: ArCameraModel, opts: ArOverlayOptions, w: Float, h: Float) {
        val paint = state.linePaint
        paint.strokeWidth = (if (opts.cameraMode) 1.6f else 1.0f) * opts.density
        val c = opts.accent
        paint.color = android.graphics.Color.argb(
            if (opts.cameraMode) 150 else 70,
            (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()
        )
        var az = 0.0
        while (az < 360.0) {
            val a0 = Math.toRadians(az); val a1 = Math.toRadians(az + 4.0)
            drawSegment(nc, state, camera, sin(a0), cos(a0), 0.0, sin(a1), cos(a1), 0.0, paint, w, h)
            az += 4.0
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Stars
    // ------------------------------------------------------------------------------------------------

    private fun drawStars(
        nc: android.graphics.Canvas, state: ArOverlayState, snapshot: ArSkySnapshot, lighting: ArSkyLighting?,
        camera: ArCameraModel, stars: ArStarCatalog, glyphs: ArGlyphCache, opts: ArOverlayOptions, w: Float, h: Float
    ) {
        val sprites = glyphs.starSprites
        val bore = camera.boresightEnu(state.tmp)
        val cosLimit = cos(min(Math.PI / 2, camera.halfDiagonalFovRad() + Math.toRadians(2.0)))
        val e = snapshot.starEnu
        val sunAlt = snapshot.sun.placed.altDeg
        val cameraLimit = ArPhotometry.limitingMagnitude(0.0, opts.zoom, true, sunAlt)
        val paint = state.spritePaint
        val margin = 24f * opts.density
        val moon = snapshot.moon.placed.enu
        val moonCos = cos(Math.toRadians(snapshot.moon.angularRadiusDeg))
        for (i in 0 until stars.size) {
            val x = e[i * 3].toDouble(); val y = e[i * 3 + 1].toDouble(); val z = e[i * 3 + 2].toDouble()
            if (x * bore[0] + y * bore[1] + z * bore[2] < cosLimit) continue
            val belowHorizon = z < -0.003
            if (belowHorizon && !opts.cameraMode) continue
            val m = snapshot.starMag[i].toDouble()
            if (m > 50.0) continue
            // Occulted by the Moon's disc.
            if (x * moon[0] + y * moon[1] + z * moon[2] > moonCos) continue
            val lim = if (opts.cameraMode || lighting == null) {
                if (lighting == null && !opts.cameraMode) ArPhotometry.limitingMagnitude(21.0, opts.zoom, false, sunAlt) else cameraLimit
            } else {
                ArPhotometry.limitingMagnitude(lighting.magArcsec2At(x, y, z), opts.zoom, false, sunAlt)
            }
            if (m >= lim) continue
            if (!camera.project(x, y, z, state.p)) continue
            val px = state.p[0]; val py = state.p[1]
            if (px < -margin || px > w + margin || py < -margin || py > h + margin) continue
            ArPhotometry.pointLook(m, lim, opts.density, state.look)
            var alpha = state.look.coreAlpha
            if (alpha <= 0.01f) continue
            if (!opts.cameraMode && z < 0.45) {
                // Scintillation, strongest near the horizon (airmass-scaled), subtle and per star.
                val air = SkyAtmosphere.airmass(Math.toDegrees(kotlin.math.asin(z.coerceIn(-1.0, 1.0))))
                val amp = (0.05 * (air - 1.0)).coerceIn(0.0, 0.28)
                val t = opts.timeMs / 1000.0
                val n = sin(t * 7.1 + i * 12.9898) * 0.6 + sin(t * 13.7 + i * 78.233) * 0.4
                alpha = (alpha * (1.0 + amp * n)).toFloat().coerceIn(0f, 1f)
            }
            if (belowHorizon) alpha *= 0.3f
            val sprite = sprites[ArGlyphCache.bvToBin(stars.colorIndex(i))]
            val r = state.look.coreRadiusPx * ArGlyphCache.PSF_SPRITE_PER_CORE_RADIUS
            paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            state.rect.set(px - r, py - r, px + r, py + r)
            nc.drawBitmap(sprite, null, state.rect, paint)
            if (state.look.haloAlpha > 0f) {
                val hr = state.look.haloRadiusPx * ArGlyphCache.PSF_SPRITE_PER_CORE_RADIUS
                paint.alpha = (state.look.haloAlpha * (if (belowHorizon) 0.3f else 1f) * 255).toInt().coerceIn(0, 255)
                state.rect.set(px - hr, py - hr, px + hr, py + hr)
                nc.drawBitmap(sprite, null, state.rect, paint)
            }
        }
        paint.alpha = 255
    }

    /** Named catalogue stars are drawn by the star field; register them for tapping and labels. */
    private fun collectCatalogueStars(
        state: ArOverlayState, snapshot: ArSkySnapshot, camera: ArCameraModel, catalogue: List<CelestialObject>,
        stars: ArStarCatalog?, opts: ArOverlayOptions, w: Float, h: Float
    ) {
        if (!opts.showStars) return
        for (obj in catalogue) {
            if (obj.type != ObjectType.STAR) continue
            val idx = obj.hdId?.let { stars?.indexOfHd(it) } ?: -1
            val x: Double; val y: Double; val z: Double
            if (idx >= 0) {
                x = snapshot.starEnu[idx * 3].toDouble(); y = snapshot.starEnu[idx * 3 + 1].toDouble(); z = snapshot.starEnu[idx * 3 + 2].toDouble()
            } else {
                val pl = snapshot.placementOf(obj.id) ?: continue
                x = pl.enu[0]; y = pl.enu[1]; z = pl.enu[2]
            }
            if (!opts.cameraMode && z < -0.003) continue
            if (!camera.project(x, y, z, state.p)) continue
            if (state.p[0] < -40 || state.p[0] > w + 40 || state.p[1] < -40 || state.p[1] > h + 40) continue
            val alt = ArSkyFrame.altitudeDeg(doubleArrayOf(x, y, z))
            state.items.add(ArDrawnItem(obj, state.p[0], state.p[1], 4f * opts.density, alt, 10.0 + obj.magnitude))
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Sun, Moon, planets, Galilean moons
    // ------------------------------------------------------------------------------------------------

    private fun drawBodies(
        nc: android.graphics.Canvas, state: ArOverlayState, snapshot: ArSkySnapshot, lighting: ArSkyLighting?,
        camera: ArCameraModel, catalogue: List<CelestialObject>, glyphs: ArGlyphCache, opts: ArOverlayOptions, w: Float, h: Float
    ) {
        val byId = catalogue.associateBy { it.id }
        val ppr = camera.focalLengthViewPx() // pixels per radian at the centre
        val sunAlt = snapshot.sun.placed.altDeg
        val jupiter = snapshot.bodies.firstOrNull { it.id == "planet_jupiter" }
        var jupiterRadiusPx = 0f
        // Draw order: faint first so the Moon and Sun end on top.
        val ordered = snapshot.bodies.sortedByDescending { it.magnitude }
        for (b in ordered) {
            val obj = byId[b.id] ?: byId[galileanAlias(b.id)] ?: continue
            when (b.kind) {
                ArBodyKind.SUN -> if (!opts.showSun) continue
                ArBodyKind.MOON -> if (!opts.showMoon) continue
                ArBodyKind.PLANET -> if (!opts.showPlanets) continue
                ArBodyKind.JOVIAN_MOON -> if (!opts.showPlanets) continue
            }
            val pl = b.placed
            if (!camera.project(pl.enu[0], pl.enu[1], pl.enu[2], state.p)) continue
            val px = state.p[0]; val py = state.p[1]
            if (px < -300 || px > w + 300 || py < -300 || py > h + 300) continue
            val below = pl.altDeg < -0.3
            val belowAlpha = if (below) (if (opts.cameraMode) 0.4f else 0.25f) else 1f
            val trueRadiusPx = (Math.toRadians(b.angularRadiusDeg) * ppr).toFloat()

            if (b.kind == ArBodyKind.JOVIAN_MOON) {
                // Only once their true separation from Jupiter clears Jupiter's (magnified) glyph.
                if (jupiter == null || b.magnitude > 50.0) continue
                if (!camera.project(jupiter.placed.enu[0], jupiter.placed.enu[1], jupiter.placed.enu[2], state.q)) continue
                val sep = hypot((px - state.q[0]).toDouble(), (py - state.q[1]).toDouble()).toFloat()
                if (sep < jupiterRadiusPx + 5f * opts.density) continue
                drawPoint(nc, state, glyphs, px, py, b.magnitude, 0.3, opts, lighting, pl, snapshot, belowAlpha)
                state.items.add(ArDrawnItem(obj, px, py, 3f * opts.density, pl.altDeg, 30.0))
                continue
            }

            val minDp = when (b.id) {
                "sun" -> 17f
                "moon" -> 17f
                "planet_venus" -> 6.5f
                "planet_jupiter" -> 7.5f
                "planet_saturn" -> 4.6f
                "planet_mars" -> 5f
                "planet_mercury" -> 4.2f
                else -> 3.6f
            }
            val zoomGrow = opts.zoom.coerceAtLeast(0.5).pow(0.35).toFloat()
            val radius = max(trueRadiusPx, minDp * opts.density * zoomGrow)
            if (b.id == "planet_jupiter") jupiterRadiusPx = radius

            // Glare of bright planets drawn as the same PSF as stars, below the disc.
            if (b.kind == ArBodyKind.PLANET && b.magnitude < 1.5) {
                drawPoint(nc, state, glyphs, px, py, b.magnitude, 0.6, opts, lighting, pl, snapshot, belowAlpha * 0.8f, haloOnly = true)
            }
            val sprite = glyphs.bodySprite(b, radius)
            if (sprite != null) {
                val scale = radius / sprite.bodyRadiusPx
                val northAngle = screenAngleOfNorth(snapshot, camera, pl, state)
                val (vertAngle, squash) = if (b.kind == ArBodyKind.SUN || b.kind == ArBodyKind.MOON) {
                    refractionSquash(snapshot, camera, pl, b, state)
                } else 0f to 1f
                state.spritePaint.alpha = (255 * belowAlpha).toInt()
                nc.save()
                nc.translate(px, py)
                if (squash < 0.999f) {
                    nc.rotate(vertAngle)
                    nc.scale(1f, squash)
                    nc.rotate(northAngle - vertAngle)
                } else {
                    nc.rotate(northAngle)
                }
                nc.scale(scale, scale)
                val half = sprite.bitmap.width / 2f
                nc.drawBitmap(sprite.bitmap, -half, -half, state.spritePaint)
                nc.restore()
                state.spritePaint.alpha = 255
            } else {
                // First frames, before the sprite is ready: a plain disc in the body's colour.
                state.fillPaint.color = when (b.kind) {
                    ArBodyKind.SUN -> android.graphics.Color.rgb(255, 246, 228)
                    ArBodyKind.MOON -> android.graphics.Color.rgb(222, 222, 216)
                    else -> android.graphics.Color.rgb(236, 226, 205)
                }
                state.fillPaint.alpha = (255 * belowAlpha).toInt()
                nc.drawCircle(px, py, radius, state.fillPaint)
            }
            val priority = when (b.kind) { ArBodyKind.SUN -> 0.0; ArBodyKind.MOON -> 0.5; else -> 1.0 + b.magnitude * 0.1 }
            val extent = if (b.id == "planet_saturn") radius * 2.3f else radius
            state.items.add(ArDrawnItem(obj, px, py, extent, pl.altDeg, priority))
        }
    }

    private fun galileanAlias(id: String): String = when (id) {
        "jup_io" -> "galilean_moon_io"
        "jup_europa" -> "galilean_moon_europa"
        "jup_ganymede" -> "galilean_moon_ganymede"
        "jup_callisto" -> "galilean_moon_callisto"
        else -> id
    }

    /** Screen rotation (degrees, clockwise from up) of celestial north at [pl]. */
    private fun screenAngleOfNorth(snapshot: ArSkySnapshot, camera: ArCameraModel, pl: ArPlaced, state: ArOverlayState): Float {
        val e = pl.enu
        val pole = M3.mulVec(snapshot.frame.enuFromTod, doubleArrayOf(0.0, 0.0, 1.0), state.tmp)
        val d = pole[0] * e[0] + pole[1] * e[1] + pole[2] * e[2]
        val k = 0.002
        val nx = e[0] + k * (pole[0] - d * e[0]); val ny = e[1] + k * (pole[1] - d * e[1]); val nz = e[2] + k * (pole[2] - d * e[2])
        if (!camera.project(e[0], e[1], e[2], state.q)) return 0f
        if (!camera.project(nx, ny, nz, state.r)) return 0f
        return Math.toDegrees(atan2((state.r[0] - state.q[0]).toDouble(), -(state.r[1] - state.q[1]).toDouble())).toFloat()
    }

    /**
     * Vertical flattening of the Sun or Moon by differential refraction near the horizon:
     * (apparent vertical diameter) / (true diameter), and the screen angle of the local vertical.
     */
    private fun refractionSquash(snapshot: ArSkySnapshot, camera: ArCameraModel, pl: ArPlaced, b: ArBodyState, state: ArOverlayState): Pair<Float, Float> {
        if (pl.altDeg > 12.0 || pl.altDeg < -2.0) return 0f to 1f
        val obs = snapshot.frame.observer
        val r = b.angularRadiusDeg
        val hTrue = pl.altDeg - ArRefraction.refractionFromApparentDeg(pl.altDeg, obs.pressureHpa, obs.temperatureC)
        val top = (hTrue + r) + ArRefraction.refractionFromTrueDeg(hTrue + r, obs.pressureHpa, obs.temperatureC)
        val bottom = (hTrue - r) + ArRefraction.refractionFromTrueDeg(hTrue - r, obs.pressureHpa, obs.temperatureC)
        val squash = ((top - bottom) / (2 * r)).toFloat().coerceIn(0.6f, 1f)
        // Screen angle of "up" at the body: project a point slightly higher in altitude.
        val up = pl.enu.copyOf()
        ArSkyFrame.setAltitudeInPlace(up, pl.altDeg + 0.2)
        if (!camera.project(pl.enu[0], pl.enu[1], pl.enu[2], state.q)) return 0f to 1f
        if (!camera.project(up[0], up[1], up[2], state.r)) return 0f to 1f
        val ang = Math.toDegrees(atan2((state.r[0] - state.q[0]).toDouble(), -(state.r[1] - state.q[1]).toDouble())).toFloat()
        return ang to squash
    }

    /** Point source with the shared star photometry (planet glare, satellites, Galilean moons). */
    private fun drawPoint(
        nc: android.graphics.Canvas, state: ArOverlayState, glyphs: ArGlyphCache, px: Float, py: Float,
        mag: Double, bv: Double, opts: ArOverlayOptions, lighting: ArSkyLighting?, pl: ArPlaced,
        snapshot: ArSkySnapshot, alphaScale: Float, haloOnly: Boolean = false
    ) {
        val sunAlt = snapshot.sun.placed.altDeg
        val lim = if (opts.cameraMode || lighting == null) ArPhotometry.limitingMagnitude(0.0, opts.zoom, true, sunAlt) + 1.0
        else ArPhotometry.limitingMagnitude(lighting.magArcsec2At(pl.enu[0], pl.enu[1], pl.enu[2]), opts.zoom, false, sunAlt)
        // Bright solar-system bodies stay visible as finder marks even in daylight.
        val effLim = max(lim, mag + 1.2)
        ArPhotometry.pointLook(mag, effLim, opts.density, state.look)
        val sprite = glyphs.starSprites[ArGlyphCache.bvToBin(bv)]
        val paint = state.spritePaint
        if (!haloOnly) {
            val r = state.look.coreRadiusPx * ArGlyphCache.PSF_SPRITE_PER_CORE_RADIUS
            paint.alpha = (state.look.coreAlpha * alphaScale * 255).toInt().coerceIn(0, 255)
            state.rect.set(px - r, py - r, px + r, py + r)
            nc.drawBitmap(sprite, null, state.rect, paint)
        }
        if (state.look.haloAlpha > 0f) {
            val hr = state.look.haloRadiusPx * ArGlyphCache.PSF_SPRITE_PER_CORE_RADIUS
            paint.alpha = (state.look.haloAlpha * alphaScale * 255).toInt().coerceIn(0, 255)
            state.rect.set(px - hr, py - hr, px + hr, py + hr)
            nc.drawBitmap(sprite, null, state.rect, paint)
        }
        paint.alpha = 255
    }

    // ------------------------------------------------------------------------------------------------
    // Deep-sky objects
    // ------------------------------------------------------------------------------------------------

    private fun drawDeepSky(
        nc: android.graphics.Canvas, state: ArOverlayState, snapshot: ArSkySnapshot, lighting: ArSkyLighting?,
        camera: ArCameraModel, catalogue: List<CelestialObject>, glyphs: ArGlyphCache, opts: ArOverlayOptions, w: Float, h: Float
    ) {
        val ppd = (camera.focalLengthViewPx() * Math.PI / 180.0).toFloat()
        val paint = state.spritePaint
        for (obj in catalogue) {
            if (obj.type !in deepSkyTypes) continue
            val pl = snapshot.placementOf(obj.id) ?: continue
            if (!opts.cameraMode && pl.altDeg < -0.3) continue
            if (!camera.project(pl.enu[0], pl.enu[1], pl.enu[2], state.p)) continue
            val px = state.p[0]; val py = state.p[1]
            if (px < -120 || px > w + 120 || py < -120 || py > h + 120) continue
            val sizeArcmin = obj.angularSizeArcmin ?: 5.0
            val trueR = (sizeArcmin / 120.0).toFloat() * ppd
            val radius = max(trueR, 5.5f * opts.density)
            val kind = ArGlyphCache.dsoKind(obj)
            val sprite = glyphs.dsoSprite(kind)
            // Visibility: integrated magnitude spread over its area against the local sky.
            var alpha = if (opts.cameraMode) 0.75f else {
                val areaArcsec2 = Math.PI * (sizeArcmin * 30.0).pow(2.0)
                val sb = obj.magnitude + 2.5 * kotlin.math.log10(max(areaArcsec2, 1.0))
                val sky = lighting?.magArcsec2At(pl.enu[0], pl.enu[1], pl.enu[2]) ?: 21.0
                (0.25 + 0.75 * SkyRenderModel.smoothstep(-1.0, 2.5, sky - sb + 3.0)).toFloat()
            }
            if (pl.altDeg < -0.3) alpha *= 0.4f
            if (obj.type == ObjectType.BLACK_HOLE) {
                // Sgr A*: no visible light; a fine marker ring only.
                state.linePaint.strokeWidth = 1.2f * opts.density
                state.linePaint.color = android.graphics.Color.argb((140 * alpha).toInt(), 255, 196, 120)
                nc.drawCircle(px, py, 6f * opts.density, state.linePaint)
            } else {
                paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                state.rect.set(px - radius, py - radius, px + radius, py + radius)
                nc.drawBitmap(sprite, null, state.rect, paint)
                paint.alpha = 255
            }
            state.items.add(ArDrawnItem(obj, px, py, radius, pl.altDeg, 20.0 + obj.magnitude * 0.5))
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Satellites
    // ------------------------------------------------------------------------------------------------

    private fun drawSatellites(
        nc: android.graphics.Canvas, state: ArOverlayState, snapshot: ArSkySnapshot, camera: ArCameraModel,
        catalogue: List<CelestialObject>, satellites: List<ArSatelliteInfo>, glyphs: ArGlyphCache,
        opts: ArOverlayOptions, w: Float, h: Float
    ) {
        val byId = catalogue.associateBy { it.id }
        for (s in satellites) {
            val obj = ArScreenSupport.satelliteIdsFor(s).firstNotNullOfOrNull { byId[it] } ?: continue
            val pl = snapshot.placementOf(s.id) ?: continue
            if (pl.altDeg < -0.5 && !opts.cameraMode) continue
            if (!camera.project(pl.enu[0], pl.enu[1], pl.enu[2], state.p)) continue
            val px = state.p[0]; val py = state.p[1]
            if (px < -80 || px > w + 80 || py < -80 || py > h + 80) continue
            val below = pl.altDeg < 0.0
            if (s.sunlit) {
                // Motion streak over the last 20 s.
                state.linePaint.strokeWidth = 1.4f * opts.density
                var lastX = px; var lastY = py
                for ((k, t) in s.trail.withIndex()) {
                    val e = ArSkyFrame.enuFromAltAz(t.second, t.first)
                    if (!camera.project(e[0], e[1], e[2], state.q)) break
                    val a = (110 * (1.0 - k * 0.45) * (if (below) 0.4 else 1.0)).toInt().coerceIn(0, 255)
                    state.linePaint.color = android.graphics.Color.argb(a, 230, 238, 255)
                    nc.drawLine(lastX, lastY, state.q[0], state.q[1], state.linePaint)
                    lastX = state.q[0]; lastY = state.q[1]
                }
                drawPoint(nc, state, glyphs, px, py, s.magnitude, 0.65, opts, null, pl, snapshot, if (below) 0.4f else 1f)
            } else {
                // In the Earth's shadow: invisible in reality; a faint ring keeps it findable.
                state.linePaint.strokeWidth = 1f * opts.density
                state.linePaint.color = android.graphics.Color.argb(if (below) 50 else 110, 160, 175, 200)
                nc.drawCircle(px, py, 4f * opts.density, state.linePaint)
            }
            state.items.add(ArDrawnItem(obj, px, py, 6f * opts.density, pl.altDeg, if (s.noradId == 25544) 2.0 else 3.0))
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Meteor radiants (active showers only)
    // ------------------------------------------------------------------------------------------------

    private fun drawRadiants(
        nc: android.graphics.Canvas, state: ArOverlayState, snapshot: ArSkySnapshot, camera: ArCameraModel,
        catalogue: List<CelestialObject>, opts: ArOverlayOptions, w: Float, h: Float
    ) {
        val paint = state.linePaint
        for (obj in catalogue) {
            if (obj.type != ObjectType.METEOR_SHOWER) continue
            if (!ArScreenSupport.isShowerActive(obj, snapshot.timeMs)) continue
            val pl = snapshot.placementOf(obj.id) ?: continue
            if (!camera.project(pl.enu[0], pl.enu[1], pl.enu[2], state.p)) continue
            val px = state.p[0]; val py = state.p[1]
            if (px < -40 || px > w + 40 || py < -40 || py > h + 40) continue
            paint.strokeWidth = 1.1f * opts.density
            paint.color = android.graphics.Color.argb(if (pl.altDeg < 0) 60 else 150, 255, 214, 150)
            val r1 = 4f * opts.density; val r2 = 11f * opts.density
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0 + 22.5)
                nc.drawLine(px + (r1 * cos(a)).toFloat(), py + (r1 * sin(a)).toFloat(), px + (r2 * cos(a)).toFloat(), py + (r2 * sin(a)).toFloat(), paint)
            }
            state.items.add(ArDrawnItem(obj, px, py, r2, pl.altDeg, 40.0))
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------------------------------------

    private fun drawLabels(
        scope: DrawScope, state: ArOverlayState, snapshot: ArSkySnapshot, camera: ArCameraModel,
        constellations: List<ArConstellation>, opts: ArOverlayOptions, textMeasurer: TextMeasurer,
        fontFamily: FontFamily, darkness: Double, w: Float, h: Float
    ) {
        // Constellation names.
        if (opts.showConstellations && darkness > 0.05) {
            val labels = snapshot.constellationLabelEnu
            val color = Color(0xFF9DB6E6).copy(alpha = (0.62 * darkness).toFloat())
            for ((i, c) in constellations.withIndex()) {
                if (c.rank > 2 && opts.zoom < 1.6) continue
                val x = labels[i * 3].toDouble(); val y = labels[i * 3 + 1].toDouble(); val z = labels[i * 3 + 2].toDouble()
                if (!opts.cameraMode && z < 0.02) continue
                if (!camera.project(x, y, z, state.p)) continue
                val px = state.p[0]; val py = state.p[1]
                if (px < 0 || px > w || py < 0 || py > h) continue
                val text = if (opts.isFa) c.nameFa else c.nameEn.uppercase()
                val layout = textMeasurer.measure(
                    text = text,
                    style = TextStyle(fontFamily = fontFamily, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = if (opts.isFa) 0.sp else 1.5.sp),
                    maxLines = 1,
                    softWrap = false
                )
                scope.drawText(layout, topLeft = Offset(px - layout.size.width / 2f, py - layout.size.height / 2f))
            }
        }

        // Object labels: selected, aimed, and (with names on) the notable objects, decluttered.
        val placed = ArrayList<FloatArray>()
        val candidates = state.items.filter { item ->
            val sel = item.obj.id == opts.selectedId
            val aimed = item === state.aimed
            sel || aimed || (opts.showNames && labelWorthy(item.obj))
        }.sortedBy { it.priority }
        var count = 0
        for (item in candidates) {
            if (count >= 40) break
            val sel = item.obj.id == opts.selectedId
            val aimed = item === state.aimed
            val text = if (opts.isFa) item.obj.nameFa else item.obj.nameEn
            val layout = textMeasurer.measure(
                text = text,
                style = TextStyle(
                    fontFamily = fontFamily,
                    color = Color.White.copy(alpha = if (item.altDeg < 0) 0.55f else 0.95f),
                    fontSize = 11.sp,
                    fontWeight = if (sel || aimed) FontWeight.Bold else FontWeight.SemiBold
                ),
                maxLines = 1,
                softWrap = false
            )
            val pw = layout.size.width + 16f
            val ph = layout.size.height + 8f
            val left = item.x - pw / 2f
            val top = item.y + item.radiusPx + 6f * opts.density
            if (left > w || top > h || left + pw < 0 || top + ph < 0) continue
            // Declutter: skip labels overlapping an already placed one (selected/aimed always placed).
            if (!sel && !aimed && placed.any { r -> left < r[2] && left + pw > r[0] && top < r[3] && top + ph > r[1] }) continue
            placed.add(floatArrayOf(left, top, left + pw, top + ph))
            scope.drawRoundRect(
                color = Color.Black.copy(alpha = if (sel || aimed) 0.72f else 0.46f),
                topLeft = Offset(left, top),
                size = Size(pw, ph),
                cornerRadius = CornerRadius(ph / 2f, ph / 2f)
            )
            if (sel || aimed) {
                scope.drawRoundRect(
                    color = opts.accent.copy(alpha = 0.9f),
                    topLeft = Offset(left, top),
                    size = Size(pw, ph),
                    cornerRadius = CornerRadius(ph / 2f, ph / 2f),
                    style = Stroke(width = 1.2f * opts.density)
                )
            }
            scope.drawText(layout, topLeft = Offset(left + 8f, top + 4f))
            count++
        }

        // Selection / aim rings.
        for (item in state.items) {
            val sel = item.obj.id == opts.selectedId
            val aimed = item === state.aimed
            if (!sel && !aimed) continue
            scope.drawCircle(
                color = opts.accent.copy(alpha = if (sel) 0.95f else 0.7f),
                radius = item.radiusPx + (if (sel) 9f else 6f) * opts.density,
                center = Offset(item.x, item.y),
                style = Stroke(width = (if (sel) 2f else 1.3f) * opts.density)
            )
        }
    }

    private fun labelWorthy(obj: CelestialObject): Boolean = when (obj.type) {
        ObjectType.SUN, ObjectType.MOON, ObjectType.PLANET, ObjectType.SATELLITE -> true
        ObjectType.STAR -> obj.magnitude <= 2.6
        ObjectType.DWARF_PLANET -> false
        ObjectType.METEOR_SHOWER -> true
        else -> obj.magnitude <= 7.5
    }
}
