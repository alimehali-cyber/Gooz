package com.alijafari.red.astronomy.ui.persepolis

// ============================================================================
// ZIG INTEGRATION NOTE (the only changes from the file as supplied)
// ----------------------------------------------------------------------------
// This combined Persepolis + Mil-e Azhdaha file preserves the supplied
// procedural geometry, fixed camera and lighting model, with eight changes:
//
//  1. The package line above (as the original first line instructed).
//  2. [PersepolisMilAzhdahaScene.init] first drops GL resource names left
//     over from a dead EGL context (see the guard there). Without it,
//     recreating the surface — which happens whenever Home's LazyColumn
//     disposes and later recomposes the scene item — would append second
//     sets of chunks and draw stale buffer names from the previous context.
//  3. The CPU-side mesh arrays for both monument groups are built and
//     transformed once per process and memoised (see the scene's companion
//     object). Both builders are deterministic (fixed LCG seed per
//     MeshBuilder), so the cached arrays are bit-identical to fresh builds;
//     only the VBO uploads repeat per GL context.
//  4. [PersepolisMilAzhdahaView] preserves its EGL context on pause, so
//     backgrounding the app does not force VBO re-uploads when the context
//     survives. Change 2 covers the devices where preservation fails.
//  5. The renderer is hosted through the OpenGL ES 3.0 Android API and an
//     ES 3.0 EGL context. That conversion itself changes no shader source
//     or rendering operation; it only follows the app's renderer policy.
//  6. [PersepolisMilAzhdahaScene.centerFractionFromLeft] adds an optional
//     horizontal offset to the orthographic window. Its 0.5 default is the
//     supplied centred framing; ZIG passes 0.30 to place the unchanged
//     fixed-camera group in the left distance of the Home landscape.
//  7. The Mil-e Azhdaha group receives one additional Home-only +66 m
//     world-x translation when its cached mesh is baked. Persepolis' own
//     transform and every framing input are untouched; on phone-shaped
//     scenes this puts the tower at the far right and lets its upslope run
//     modestly beyond the right edge, as a naturally cropped hillside.
//  8. Persepolis' palette and fragment response are harmonized toward
//     Mil-e Azhdaha's warm limestone as ONE stone colour (see PAL_P and
//     FRAGMENT_SHADER_P): every palette entry is the same warm hue at a
//     different value — including the dark frame stone, which as a neutral
//     grey read as a second, unrelated material — all shader variation is
//     scalar (value may wander, hue may not), and a final hue-lock blend
//     guarantees the unity. The value hierarchy of frames, columns, relief
//     and platform is kept, and the added surface detail is scaled for
//     Persepolis' greater viewing distance. Mil-e Azhdaha's palette and
//     shader are unchanged.
//
// Hosting, framing and the Sun/Moon feed live outside this file: see
// PersepolisLandscape (Compose host) and
// com.alijafari.red.astronomy.ui.rendering.PersepolisFraming (placement
// math), and docs/PERSEPOLIS_LANDSCAPE.md for the integration record.
// ============================================================================

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ============================================================================
 * PERSEPOLIS + MIL-E AZHDaha — the Apadana east façade and the Nurabad
 * fire-temple tower in ONE fixed, lit 3D landscape frame for Android.
 * ============================================================================
 * Single self-contained Kotlin file. Dependencies: Android SDK only
 * (OpenGL ES 3.0; see integration change 5 above). No WebView, no Filament/SceneView, no model assets —
 * the geometry is generated procedurally below into static VBOs
 * (Persepolis 138,845 triangles / 145,449 vertices + the tower group
 * with its hillside 10,140 / 20,043 = 148,985 triangles / 165,492 vertices
 * total, measured;
 * split into ≤60k-vertex chunks so plain 16-bit indices work everywhere), so
 * the per-frame cost is a few draw calls and the view can render strictly
 * on demand ([GLSurfaceView.RENDERMODE_WHEN_DIRTY]) whenever your
 * astronomy engine moves the Sun or Moon. Ideal for a live-sky app:
 * zero battery cost between updates.
 *
 * The companion file `persepolis_mil_azhdaha_preview.html` contains the
 * same geometry builders and the same GLSL ported to WebGL — what you see there
 * (sunrise / noon / sunset / moonlight, transparent background) is what
 * this file draws, modulo the framing default (see [PersepolisMilAzhdahaScene]).
 * The standalone pairs (`Persepolis.kt`, `MilAzhdaha.kt` + their HTML previews)
 * keep each monument separate at true scale; only in this combined file is
 * the tower enlarged ≈2× and set into the Persepolis forecourt (below).
 *
 * WHAT IS MODELLED (one fixed view from the south-east, as photographed)
 * ------------------------------------------------------------------------
 * A shallow (~24 m deep) true-3D relief of the view from the east:
 *  south (image LEFT)  : monolithic stone door/window/niche frames of the
 *                        palace group south of the Apadana (Tachara type),
 *                        angle piers, lone jambs, back walls, rubble;
 *  north (image RIGHT) : the Apadana east portico's surviving fluted
 *                        columns at uneven ruined heights, and in front of
 *                        them the east-stair cheek with its three relief
 *                        registers, lion-and-bull end panel and four-stepped
 *                        crenellations;
 *  across the front    : the two-tier ashlar platform and its small portal.
 *
 *  right foreground  : MIL-E AZHDaha (the Nurabad tower, Fars) — the square
 *                        stone fire-temple/ossuary tower on its three-course
 *                        stepped socle (4.85 / 4.55 / 4.30 m), nine shaft
 *                        courses (3.92 m, then 3.86 m, then a constant 3.80 m
 *                        wide from the third course to the top, exactly as
 *                        the photographs show), its small south door
 *                        (≈0.6 × 1.1 m, sill ≈2.5 m up) opening onto the
 *                        wall-thickness stair, and the ruined top transcribed
 *                        from the photographs; rubble, fallen blocks and dry
 *                        scrub at its foot. Modelled at true scale in
 *                        `MilAzhdaha.kt`; here the tower group is enlarged
 *                        2.04× (≈15.2 m tall, brought 20 % forward) for this composition,
 *                        standing on a slice of its limestone hillside (milSlope) in front of the platform's
 *                        east wall near the Apadana stair, turned slightly
 *                        (≈11°) toward the façade.
 *
 * World axes (metres): +X = north (image right), +Y = up, +Z = east
 * (toward the fixed camera). The camera never moves: orthographic view
 * from (−17, 15.5, 110) toward (0, 9.0, 0) — ≈9° off-axis from the
 * south-east and ≈3° down, so the platform floor, the two column rows
 * and the 2.4 m-thick frame monoliths read in real depth while the
 * skyline still matches the telephoto reference photograph.
 * Because the camera is fixed, all "3D" behaviour the app needs is in the
 * lighting: every surface carries a real normal and is shaded by the
 * Sun/Moon direction you supply (Lambert + hemisphere ambient + a faint
 * specular/rim term + soft exposure, see the fragment shader below).
 * There are intentionally NO cast-shadow maps (static geometry, render on
 * demand); depth occlusion between elements is real, and contact shading
 * is baked into vertex colours (grooves, joints, recesses, incised relief
 * outlines). If you later need true column shadows, a shadow-map pass can
 * be added around [PersepolisMilAzhdahaScene.draw] without touching the geometry.
 *
 * DIMENSIONS — research anchors (X/Y at true scale; Z depth compressed)
 * ----------------------------------------------------------------------
 *  • Apadana: square hall 60.5 × 60.5 m on a podium ≈2.6–3 m above its
 *    court; 72 columns (36 hall + 3 porticoes of 12, in two rows of six);
 *    columns ≈19–19.5 m high overall (tallest modelled here: 19.2 m above
 *    the platform) and 1.60 m in diameter (R = 0.80 below), among the most
 *    slender of the ancient world. Flutes: 40–48 shallow Doric-like flutes
 *    (48 modelled). Composite capitals ≈8 m of the total height, stacked
 *    as researched: a corolla of drooping leaves, a spreading palm corolla
 *    above it, one tall square fluted zone with double volutes top and
 *    bottom, then addorsed double-bull protomes. The east-portico columns
 *    stand on circular bell-shaped bases (the hall columns used square
 *    plinths with a discoid torus); the model splits a column into
 *    ≈1.8 m two-step plinth + leaf-carved bell base, a drum-built fluted
 *    shaft (stacked drums pinned with central dowels, flutes running out
 *    into necking rings) and up to 8 m of capital.
 *    13 columns stood in 1977, a 14th was re-erected; the twelve modelled
 *    here form the portico's two depth-separated rows (2 near-complete,
 *    the rest as broken drums/imposts, the uneven skyline of the photo).
 *  • East (and north) Apadana stairways: façade 81.67 m long in three
 *    equal parts. The modelled wing follows the present-day carving: a
 *    central panel of eight antithetic Persian/Median guards beneath the
 *    winged disk and two seated sphinxes (the original enthroned-king
 *    panel was moved to the Treasury in antiquity); three relief
 *    registers bordered by 12-petalled rosette bands, each row headed by
 *    Persian guards and carrying the 23 gift-bearing delegations with
 *    cypress dividers, the top border a royal-stables register with an
 *    empty chariot; XPb trilingual inscription panels at the ends; and
 *    the lion-goring-bull group in the triangular end panel between
 *    rows of date palms and cypresses. Four-stepped crenellations crown
 *    the façade. Only one wing (31.5 m) fits this composite view.
 *    Terrace steps were deliberately shallow (≈10 cm rise, 31–40 cm
 *    treads, 111 per flight on the great terrace stair) — the treads
 *    behind the cheek use the same 10 cm rise.
 *  • Stone frames: door/window/niche surrounds were monoliths crowned by
 *    the fluted Egyptian cavetto cornice. A complete Tachara window frame
 *    measures 2.65 × 2.65 × 1.70 m (18 t, single block) — the model uses
 *    its 2.65 m opening for the window frames. Monumental doorways of the
 *    great halls were far larger (the Apadana's wooden door leaves alone
 *    reached ≈15 m), so the big doorframes here (openings 2.9–3.4 m,
 *    outer heights 9–10.8 m) follow the proportions relative to the 19 m
 *    columns in the reference photograph. The Tachara itself is 40 × 30 m
 *    on a platform 2.2–3.0 m above the Apadana court, with serrated angle
 *    piers (modelled). Stone: bright-grey local limestone, with a darker
 *    grey limestone for frames — the two-tone palette below. The mud-brick
 *    walls (>5 m thick, >20 m high) that once joined these stone frames
 *    have vanished, which is why the ruin reads as freestanding frames.
 *
 * HONEST SCOPE NOTE: in reality the southern frames and the Apadana
 * columns are tens of metres apart in depth; the reference photograph's
 * telephoto lens compresses them into one plane. This model reproduces
 * THAT VIEW: plan-true element sizes and locally correct depths (two
 * column rows ~7 m apart, frames and walls 8–12 m behind the front
 * row), but the whole group compressed into a ~24 m band, with back
 * elements nudged south to cancel the camera's parallax so the skyline
 * still matches the photograph. With the fixed camera the silhouette
 * and the lighting behaviour (east faces lit at sunrise, south faces
 * at noon, backlit silhouette at sunset) are correct; do not orbit it.
 *
 * The Mil-e Azhdaha tower in reality stands alone near Nurabad (Mamajianchi,
 * Fars), far from Persepolis; the two share this frame purely as twin
 * landscape landmarks for the app, at the composition scale noted above.
 *
 * Sources: Encyclopaedia Iranica, "Persepolis" & "Apadāna" & "Capitals" &
 * "Columns" (iranicaonline.org); UNESCO WHC 114 (whc.unesco.org/en/list/114);
 * Achaemenica, "Persepolis" & "The Apadana Reliefs" (achaemenica.org);
 * Smarthistory, "Persepolis: The Audience Hall of Darius and Xerxes".
 * Mil-e Azhdaha: Ghirshman, "La tour de Nourabad" (Syria 24, 1944); Huff,
 * "Nurabad, Dum-i Mil" (AMI 8, 1975); Wikidata Q5950677 — and measured
 * throughout against the reference photographs.
 *
 * INTEGRATION (astronomy app, Kotlin)
 * -----------------------------------
 * Option A — transparent overlay view (simplest). Put [PersepolisMilAzhdahaView] as
 * the top child of the FrameLayout that holds your sky, e.g. in XML:
 *
 *   <com.example.astronomy.PersepolisMilAzhdahaView
 *       android:id="@+id/persepolis_mil_azhdaha"
 *       android:layout_width="match_parent"
 *       android:layout_height="240dp"
 *       android:layout_gravity="bottom" />
 *
 * then, whenever your ephemeris updates (and in onResume):
 *
 *   persepolisMilAzhdahaView.setSun(azimuthDeg = sunAzimuthDeg, altitudeDeg = sunAltitudeDeg)
 *   persepolisMilAzhdahaView.setMoon(azimuthDeg = moonAzimuthDeg, altitudeDeg = moonAltitudeDeg)
 *
 * Azimuth uses the astronomical convention 0°=N, 90°=E, 180°=S, 270°=W —
 * exactly what an ephemeris produces, no conversion needed. [setSun]
 * derives colour/intensity/ambient from altitude automatically (warm low
 * sun, twilight fade below the horizon, dim blue night ambient), so one
 * call per update is enough; [PersepolisMilAzhdahaScene.setSunLight] and
 * [PersepolisMilAzhdahaScene.setAmbient] exist if you want full control.
 * Forward the lifecycle: persepolisMilAzhdahaView.onPause()/onResume() from the
 * Activity/Fragment. The view is non-clickable/non-focusable and renders
 * one frame per light change (RENDERMODE_WHEN_DIRTY) — call it on your
 * normal sky-refresh cadence; redundant calls are cheap.
 * Choose the view's aspect ≲ 3.6 : 1 (width:height) for the default
 * framing; wider/shorter strips auto-fit (see [PersepolisMilAzhdahaScene.draw])
 * by zooming out rather than cropping the columns.
 *
 * Option B — draw inside your existing GL renderer (best if your sky is
 * itself a SurfaceView/GLSurfaceView). On the GL thread: create a
 * [PersepolisMilAzhdahaScene], call [PersepolisMilAzhdahaScene.init] in onSurfaceCreated,
 * [PersepolisMilAzhdahaScene.draw] with your viewport size each frame after the
 * sky, and [PersepolisMilAzhdahaScene.release] when the context goes away. The
 * light/framing setters are thread-safe and may be called from any
 * thread. Note draw() does not clear colour (your sky is the clear);
 * it does need a depth buffer and leaves depth-test/culling enabled.
 */

private const val PLATFORM_Y = 4.35f   // top of upper platform slab (structures sit here)
private const val FRONT_Z = 7.25f      // east face of lower platform / stair cheek

/** Persepolis stone palette (indices used by [buildPersepolisScene]).
 *
 *  ZIG change 8: ONE warm limestone, read at ten depths of shade. Every entry
 *  is the same hue as index 0 (green ≈ 0.72 × red, blue ≈ 0.42 × red — the
 *  Mil-e Azhdaha limestone family of [PAL_M]) scaled to its value, so frames,
 *  recesses, columns, relief, platform and rubble can never split into two
 *  different stone colours; only brightness separates them. Index 2 keeps
 *  the *value* of the supplied dark Majdabad frame stone but in the same
 *  warm hue, index 3 is a warm near-black for voids, and index 8 (settled
 *  dust of the same crushed stone) is the base hue lightened and only
 *  slightly desaturated. Do not reintroduce neutral greys here: against the
 *  warm stone they read as a second, unrelated material. */
private val PAL_P: Array<FloatArray> = arrayOf(
    floatArrayOf(0.64f, 0.46f, 0.27f), // 0 warm limestone light (the hue anchor)
    floatArrayOf(0.52f, 0.38f, 0.22f), // 1 limestone mid
    floatArrayOf(0.38f, 0.28f, 0.17f), // 2 dark frame stone — same hue, deep shade
    floatArrayOf(0.20f, 0.15f, 0.09f), // 3 recess / shadow gap — warm near-black
    floatArrayOf(0.70f, 0.50f, 0.29f), // 4 pale relief stone
    floatArrayOf(0.46f, 0.33f, 0.19f), // 5 rubble
    floatArrayOf(0.58f, 0.42f, 0.24f), // 6 platform stone
    floatArrayOf(0.65f, 0.47f, 0.27f), // 7 column stone
    floatArrayOf(0.70f, 0.53f, 0.35f), // 8 settled dust of the same stone
    floatArrayOf(0.34f, 0.24f, 0.14f), // 9 weather stain / spall scar
)

// ============================================================================
// Growable primitive buffers (avoid boxing ~450k floats during the build)
// ============================================================================

/** Mil-e Azhdaha stone palette (photo-scanned warm limestone; indices used
 *  by [buildMilLocalScene] — copied from MilAzhdaha.kt). */
private val PAL_M: Array<FloatArray> = arrayOf(
    floatArrayOf(0.64f, 0.43f, 0.20f), // 0 limestone light (warm beige, photo-scanned)
    floatArrayOf(0.53f, 0.34f, 0.15f), // 1 limestone mid
    floatArrayOf(0.38f, 0.38f, 0.37f), // 2 dark grey frame stone (Majdabad limestone)
    floatArrayOf(0.20f, 0.19f, 0.18f), // 3 recess / shadow gap
    floatArrayOf(0.70f, 0.49f, 0.24f), // 4 pale relief stone
    floatArrayOf(0.46f, 0.30f, 0.13f), // 5 rubble
    floatArrayOf(0.58f, 0.41f, 0.21f), // 6 platform / bedrock
    floatArrayOf(0.56f, 0.35f, 0.14f), // 7 eroded warm-brown lower stone
    floatArrayOf(0.72f, 0.63f, 0.44f), // 8 wind-blown sand / settled dust
    floatArrayOf(0.32f, 0.26f, 0.19f), // 9 weather stain / spall scar
    floatArrayOf(0.24f, 0.31f, 0.15f), // 10 dry scrub
)

/** Active palette: [MeshBuilder] reads this; each top-level builder selects
 *  its own monument's palette first (mesh building is single-threaded). */
private var PAL: Array<FloatArray> = PAL_P

private class FloatBag(capacity: Int = 1 shl 16) {
    var data = FloatArray(capacity); private set
    var size = 0; private set
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun clear() { size = 0 }
    fun toFloatArray(): FloatArray = data.copyOf(size)
}

private class IntBag(capacity: Int = 1 shl 16) {
    var data = IntArray(capacity); private set
    var size = 0; private set
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun clear() { size = 0 }
    fun toIntArray(): IntArray = data.copyOf(size)
}

// ============================================================================
// Mesh builder — positions / normals / vertex colours / indices.
// The deterministic LCG below (and the exact order in which the scene
// functions consume it) is shared with the HTML preview, so both build
// the same ruin, down to the rubble.
// ============================================================================

private class MeshBuilder {
    val pos = FloatBag()
    val nor = FloatBag()
    val col = FloatBag()
    val idx = IntBag()

    private var rngState: Int = 0x2545F491
    fun rnd(): Float {
        rngState = rngState * 1664525 + 1013904223   // Int overflow wraps (same as JS Math.imul)
        return ((rngState ushr 8) and 0xFFFFFF) / 16777216f
    }
    fun rr(a: Float, b: Float): Float = a + (b - a) * rnd()

    fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: FloatArray, shade: Float = 1f): Int {
        pos.add(x); pos.add(y); pos.add(z)
        nor.add(nx); nor.add(ny); nor.add(nz)
        col.add(c[0] * shade); col.add(c[1] * shade); col.add(c[2] * shade)
        return pos.size / 3 - 1
    }

    fun tri(a: Int, b: Int, c: Int) { idx.add(a); idx.add(b); idx.add(c) }
    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    /** Box by centre + size; optional Euler rotation about its centre (rubble). */
    fun box(
        cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, pal: Int,
        flatColor: Boolean = false, shade: Float = 1f,
        rx: Float = 0f, ry: Float = 0f, rz: Float = 0f,
    ) {
        val c = PAL[pal].copyOf()
        if (!flatColor) {
            val j = rr(0.90f, 1.09f)
            c[0] *= j; c[1] *= j * rr(0.98f, 1.02f); c[2] *= j * rr(0.95f, 1.03f)
        }
        c[0] *= shade; c[1] *= shade; c[2] *= shade
        val hx = sx / 2f; val hy = sy / 2f; val hz = sz / 2f
        val crx = cos(rx); val srx = sin(rx)
        val cry = cos(ry); val sry = sin(ry)
        val crz = cos(rz); val srz = sin(rz)
        fun xf(x: Float, y: Float, z: Float): FloatArray {   // Rz * Ry * Rx
            val y1 = y * crx - z * srx; val z1 = y * srx + z * crx
            val x2 = x * cry + z1 * sry; val z2 = -x * sry + z1 * cry
            return floatArrayOf(x2 * crz - y1 * srz, x2 * srz + y1 * crz, z2)
        }
        val normals: Array<FloatArray> = arrayOf(
            floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f),
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
        )
        val corners: Array<Array<FloatArray>> = arrayOf(
            arrayOf(floatArrayOf(-hx, -hy, hz), floatArrayOf(hx, -hy, hz), floatArrayOf(hx, hy, hz), floatArrayOf(-hx, hy, hz)),
            arrayOf(floatArrayOf(hx, -hy, -hz), floatArrayOf(-hx, -hy, -hz), floatArrayOf(-hx, hy, -hz), floatArrayOf(hx, hy, -hz)),
            arrayOf(floatArrayOf(hx, -hy, hz), floatArrayOf(hx, -hy, -hz), floatArrayOf(hx, hy, -hz), floatArrayOf(hx, hy, hz)),
            arrayOf(floatArrayOf(-hx, -hy, -hz), floatArrayOf(-hx, -hy, hz), floatArrayOf(-hx, hy, hz), floatArrayOf(-hx, hy, -hz)),
            arrayOf(floatArrayOf(-hx, hy, hz), floatArrayOf(hx, hy, hz), floatArrayOf(hx, hy, -hz), floatArrayOf(-hx, hy, -hz)),
            arrayOf(floatArrayOf(-hx, -hy, -hz), floatArrayOf(hx, -hy, -hz), floatArrayOf(hx, -hy, hz), floatArrayOf(-hx, -hy, hz)),
        )
        for (f in 0 until 6) {
            val n = xf(normals[f][0], normals[f][1], normals[f][2])
            val ids = IntArray(4)
            for (k in 0 until 4) {
                val p = corners[f][k]
                val w = xf(p[0], p[1], p[2])
                ids[k] = v(cx + w[0], cy + w[1], cz + w[2], n[0], n[1], n[2], c)
            }
            quad(ids[0], ids[1], ids[2], ids[3])
        }
    }

    /** Convex 2D polygon (XY, CCW) extruded zBack..zFront; flat facets. */
    fun prism(pts: Array<FloatArray>, zBack: Float, zFront: Float, pal: Int, shade: Float = 1f) {
        val c = PAL[pal]
        val n = pts.size
        val front = IntArray(n); val back = IntArray(n)
        for (i in 0 until n) front[i] = v(pts[i][0], pts[i][1], zFront, 0f, 0f, 1f, c, shade)
        for (i in 0 until n) back[i] = v(pts[i][0], pts[i][1], zBack, 0f, 0f, -1f, c, shade * 0.9f)
        for (i in 1 until n - 1) { tri(front[0], front[i], front[i + 1]); tri(back[0], back[i + 1], back[i]) }
        for (i in 0 until n) {
            val j = (i + 1) % n; val a = pts[i]; val b = pts[j]
            val ex = b[0] - a[0]; val ey = b[1] - a[1]
            val l = sqrt(ex * ex + ey * ey).let { if (it == 0f) 1f else it }
            val nx = ey / l; val ny = -ex / l   // outward for a CCW polygon
            val q0 = v(a[0], a[1], zBack, nx, ny, 0f, c, shade)
            val q1 = v(b[0], b[1], zBack, nx, ny, 0f, c, shade)
            val q2 = v(b[0], b[1], zFront, nx, ny, 0f, c, shade)
            val q3 = v(a[0], a[1], zFront, nx, ny, 0f, c, shade)
            quad(q0, q1, q2, q3)
        }
    }

    /** Shallow relief prism against a wall: front + sides only (no back). */
    fun relief(pts: Array<FloatArray>, zWall: Float, depth: Float, pal: Int) {
        val c = PAL[pal]
        val n = pts.size
        val front = IntArray(n)
        for (i in 0 until n) front[i] = v(pts[i][0], pts[i][1], zWall + depth, 0f, 0f, 1f, c)
        for (i in 1 until n - 1) tri(front[0], front[i], front[i + 1])
        for (i in 0 until n) {
            val j = (i + 1) % n; val a = pts[i]; val b = pts[j]
            val ex = b[0] - a[0]; val ey = b[1] - a[1]
            val l = sqrt(ex * ex + ey * ey).let { if (it == 0f) 1f else it }
            val nx = ey / l; val ny = -ex / l
            val q0 = v(a[0], a[1], zWall, nx, ny, 0f, c, 0.82f)
            val q1 = v(b[0], b[1], zWall, nx, ny, 0f, c, 0.82f)
            val q2 = v(b[0], b[1], zWall + depth, nx, ny, 0f, c, 0.82f)
            val q3 = v(a[0], a[1], zWall + depth, nx, ny, 0f, c, 0.82f)
            quad(q0, q1, q2, q3)
        }
    }

    /** Surface of revolution about Y. Profile points are (radius, yOffset), bottom
     *  first. [flutes] > 0 ribs the surface (capital leaf bells). */
    fun lathe(cx: Float, y0: Float, cz: Float, profile: Array<FloatArray>, seg: Int, pal: Int, shade: Float = 1f, flutes: Int = 0, tipB: Float = 0f, tipT: Float = 0f) {
        val c = PAL[pal]
        val rings = Array(profile.size) { IntArray(seg + 1) }
        for (j in profile.indices) {
            val p0 = profile[max(0, j - 1)]; val p1 = profile[min(profile.size - 1, j + 1)]
            val slope = (p1[0] - p0[0]) / max(0.0001f, p1[1] - p0[1])   // dr/dy
            for (i in 0..seg) {
                val t = i.toFloat() / seg * (2f * Math.PI.toFloat())
                val co = cos(t); val si = sin(t)
                val nx0 = co; val ny0 = -slope; val nz0 = si
                val l = sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0)
                var r = profile[j][0]
                var sh = shade
                var g = 1f
                if (flutes > 0) {   // leaf / flute ribs, baked into radius + colour
                    g = 0.5f + 0.5f * cos(t * flutes)
                    r *= 1f - 0.05f * (1f - g).toDouble().pow(1.5).toFloat()
                    sh *= 0.8f + 0.2f * g
                }
                var yy = y0 + profile[j][1]
                if (j == 0 && tipB > 0f) yy -= tipB * g                          // pendant leaf tips, bottom rim
                if (j == profile.size - 1 && tipT > 0f) yy += tipT * g           // rising leaf tips, top rim
                rings[j][i] = v(cx + r * co, yy, cz + r * si, nx0 / l, ny0 / l, nz0 / l, c, sh)
            }
        }
        // Winding: the angle runs X→Z (clockwise about +Y), so quads go up-first,
        // otherwise the visible surface is culled and the INSIDE gets lit.
        for (j in 0 until rings.size - 1) for (i in 0 until seg)
            quad(rings[j][i], rings[j + 1][i], rings[j + 1][i + 1], rings[j][i + 1])
    }

    /** Plain / fallen cylinder along an arbitrary axis, with flat end caps.
     *  [flutes] > 0 carves shaft flutes into it (fallen column drums). */
    fun cylinder(
        cx: Float, cy: Float, cz: Float, r: Float, len: Float,
        axis: FloatArray, seg: Int, pal: Int, shade: Float = 1f, flutes: Int = 0,
    ) {
        val c = PAL[pal]
        val la = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
        val a = floatArrayOf(axis[0] / la, axis[1] / la, axis[2] / la)
        val u = if (kotlin.math.abs(a[1]) < 0.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
        var e1 = floatArrayOf(u[1] * a[2] - u[2] * a[1], u[2] * a[0] - u[0] * a[2], u[0] * a[1] - u[1] * a[0])
        val l1 = sqrt(e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2])
        e1 = floatArrayOf(e1[0] / l1, e1[1] / l1, e1[2] / l1)
        val e2 = floatArrayOf(a[1] * e1[2] - a[2] * e1[1], a[2] * e1[0] - a[0] * e1[2], a[0] * e1[1] - a[1] * e1[0])
        fun groove(t: Float): Float = if (flutes > 0) (0.5f + 0.5f * cos(t * flutes)).toDouble().pow(1.3).toFloat() else 0f
        fun radAt(t: Float): Float = if (flutes > 0) r * (1f - 0.03f * groove(t)) else r
        val rings = Array(2) { IntArray(seg + 1) }
        for (s in 0 until 2) {
            val h = if (s == 0) -len / 2f else len / 2f
            for (i in 0..seg) {
                val t = i.toFloat() / seg * (2f * Math.PI.toFloat())
                val co = cos(t); val si = sin(t)
                val rad = radAt(t)
                val nx = e1[0] * co + e2[0] * si
                val ny = e1[1] * co + e2[1] * si
                val nz = e1[2] * co + e2[2] * si
                val sh = shade * (if (flutes > 0) 0.8f + 0.2f * (1f - groove(t)) else 1f)
                rings[s][i] = v(cx + a[0] * h + nx * rad, cy + a[1] * h + ny * rad, cz + a[2] * h + nz * rad, nx, ny, nz, c, sh)
            }
        }
        for (i in 0 until seg) quad(rings[0][i], rings[0][i + 1], rings[1][i + 1], rings[1][i])
        // End caps — duplicated ring with CAP normals (flat, not dome-shaded)
        for (s in 0 until 2) {
            val h = if (s == 1) len / 2f else -len / 2f
            val sg = if (s == 1) 1f else -1f
            val cen = v(cx + a[0] * h, cy + a[1] * h, cz + a[2] * h, a[0] * sg, a[1] * sg, a[2] * sg, c, 0.92f)
            val cap = IntArray(seg + 1)
            for (i in 0..seg) {
                val t = i.toFloat() / seg * (2f * Math.PI.toFloat())
                val co = cos(t); val si = sin(t)
                val rad = radAt(t)
                val nx = e1[0] * co + e2[0] * si
                val ny = e1[1] * co + e2[1] * si
                val nz = e1[2] * co + e2[2] * si
                cap[i] = v(cx + a[0] * h + nx * rad, cy + a[1] * h + ny * rad, cz + a[2] * h + nz * rad, a[0] * sg, a[1] * sg, a[2] * sg, c, 0.92f)
            }
            for (i in 0 until seg) if (s == 1) tri(cen, cap[i], cap[i + 1]) else tri(cen, cap[i + 1], cap[i])
        }
    }
}

// ============================================================================
// Architectural elements
// ============================================================================

/** Egyptian cavetto cornice: five courses flaring outward (widest at the top)
 *  over a torus "hollow-and-roll" moulding, with vertical fluting. */
private fun cornice(b: MeshBuilder, x: Float, yTop: Float, z: Float, w: Float, d: Float, pal: Int) {
    val prof = arrayOf(
        floatArrayOf(0.72f, 0.62f), floatArrayOf(0.54f, 0.52f), floatArrayOf(0.34f, 0.38f),
        floatArrayOf(0.16f, 0.20f), floatArrayOf(0.00f, 0.00f),
    )
    for (i in prof.indices)
        b.box(x, yTop - 0.16f - i * 0.24f, z, w + prof[i][0], 0.26f, d + prof[i][1], pal)
    // torus roll under the flare, front and back
    b.cylinder(x, yTop - 1.28f, z + (d + 0.62f) / 2f - 0.05f, 0.11f, w + 0.55f, floatArrayOf(1f, 0f, 0f), 10, pal)
    b.cylinder(x, yTop - 1.28f, z - (d + 0.62f) / 2f + 0.05f, 0.11f, w + 0.55f, floatArrayOf(1f, 0f, 0f), 10, pal)
    val n = max(5, floor((w / 0.5f).toDouble()).toInt())
    for (i in 0 until n)   // vertical fluting grooves on the cornice face
        b.box(x - w / 2f + 0.35f + i * (w - 0.7f) / (n - 1), yTop - 0.62f, z + (d + 0.62f) / 2f + 0.012f, 0.05f, 1.05f, 0.02f, pal, flatColor = true, shade = 0.52f)
    for (s in intArrayOf(-1, 1))   // inscription ticks on the top-course ends
        for (i in 0 until 4)
            b.box(x + s * (w / 2f - 0.15f - i * 0.16f), yTop - 0.16f, z + (d + 0.62f) / 2f + 0.012f, 0.06f, 0.16f, 0.02f, 3, flatColor = true)
}

/** Rows of tiny wedge ticks suggesting cuneiform inscription bands (front faces). */
private fun inscription(b: MeshBuilder, x: Float, y: Float, z: Float, w: Float, rows: Int) {
    for (r in 0 until rows) {
        val n = max(3, floor((w / 0.34f).toDouble()).toInt())
        for (i in 0 until n) {
            if (b.rnd() < 0.18f) continue   // weathered losses
            b.box(x - w / 2f + 0.2f + i * (w - 0.4f) / max(1, n - 1), y - r * 0.17f, z, 0.075f, 0.11f, 0.02f, 3, flatColor = true, shade = 0.85f)
        }
    }
}

private enum class FrameMode { OPEN, WINDOW, NICHE }

/** Monolithic stone door / window / niche frame. */
private fun frame(
    b: MeshBuilder, x: Float, z: Float, w: Float, h: Float, openingW: Float,
    mode: FrameMode, breakRight: Float, baseY: Float = PLATFORM_Y,
) {
    val pal = 2
    val d = 2.4f
    val y0 = baseY
    val corH = 1.15f
    val headY = y0 + h - corH
    val jambW = (w - openingW) / 2f
    val sillH = if (mode == FrameMode.WINDOW) h * 0.34f else 0f
    // Triple-recessed jambs: three stepped monolith planes narrowing toward
    // the opening, each in stacked courses — the deep reveals of the photo.
    // Layer = (inset from opening edge, width factor, depth, z shift).
    val layers = arrayOf(
        floatArrayOf(0f, 1.0f, d, 0f),
        floatArrayOf(0.20f, 0.74f, d * 0.84f, 0.06f),
        floatArrayOf(0.40f, 0.5f, d * 0.68f, 0.12f),
    )
    for (side in intArrayOf(-1, 1)) {
        val hJ = (headY - y0) * (if (side < 0) 1f else breakRight)
        val segs = max(3, (hJ / 2.1f).roundToInt())
        for (lay in layers) {
            val lw = max(0.34f, jambW * lay[1])
            val lx = x + side * (openingW / 2f + lay[0] + lw / 2f)
            for (s in 0 until segs) {
                val jx = lx + b.rr(-0.015f, 0.015f)
                val jz = z + lay[3] + b.rr(-0.015f, 0.015f)
                b.box(jx, y0 + (s + 0.5f) * hJ / segs, jz, lw, hJ / segs - 0.04f, lay[2], pal)
            }
        }
        // Carved figure on the inner front band: on the great doorways the
        // king himself (fluted tiara, larger scale) faces an attendant, as
        // on the Tachara door jambs.
        if (h > 6.2f && breakRight > 0.75f) {
            val fx = x + side * (openingW / 2f + 0.52f)
            val fz = z + d * 0.34f + 0.16f
            val fh = if (h > 9f) min(3.4f, hJ * 0.5f) else min(2.6f, hJ * 0.42f)
            reliefFigure(b, fx, y0 + max(sillH, 0.35f), fz, fh, (side * -1).toFloat(), if (h > 9f && side < 0) 1 else 0)
            inscription(b, x + side * (openingW / 2f + jambW * 0.55f), headY - 0.55f, z + d / 2f + 0.012f, min(1.1f, jambW * 0.8f), 3)
        }
        for (k in 0 until 2) {
            val cx = x + side * (openingW / 2f + b.rr(0.3f, jambW - 0.2f))
            val cy = y0 + b.rr(1.2f, max(1.4f, hJ - 1.2f))
            val ch = b.rr(0.7f, 1.6f)
            val tilt = b.rr(-0.06f, 0.06f)
            b.box(cx, cy, z + d / 2f + 0.012f, 0.035f, ch, 0.02f, 3, flatColor = true, shade = 0.8f, rz = tilt)
        }
        // Rain-wash stain streaking from under the cornice + a chipped outer corner
        val so = b.rr(0.25f, jambW * 0.8f)
        val sy = headY - b.rr(0.9f, 1.7f)
        val sh2 = b.rr(1.3f, 2.4f)
        b.box(x + side * (openingW / 2f + so), sy, z + d / 2f + 0.012f, 0.15f, sh2, 0.02f, 9, flatColor = true, shade = 0.92f)
        val cy2 = y0 + b.rr(0.5f, max(0.8f, hJ * 0.7f))
        val ch2 = b.rr(0.22f, 0.48f)
        b.box(x + side * (w / 2f - 0.05f), cy2, z + d / 2f - 0.03f, 0.17f, ch2, 0.12f, 9, flatColor = true, shade = 0.85f)
    }
    // Recessed double moulding around the opening (front face) + lintel inscription
    if (breakRight == 1f) {
        for (m in floatArrayOf(0.10f, 0.34f)) {
            b.box(x - openingW / 2f - m, y0 + sillH + (headY - y0 - sillH) / 2f, z + d / 2f + 0.03f, 0.13f, headY - y0 - sillH, 0.06f, 1)
            b.box(x + openingW / 2f + m, y0 + sillH + (headY - y0 - sillH) / 2f, z + d / 2f + 0.03f, 0.13f, headY - y0 - sillH, 0.06f, 1)
            b.box(x, headY + m - 0.12f, z + d / 2f + 0.03f, openingW + 2f * m + 0.13f, 0.13f, 0.06f, 1)
        }
        inscription(b, x, headY + 0.42f, z + d / 2f + 0.012f, min(w - 1.2f, openingW + 1.6f), 2)
    }
    if (breakRight < 1f) {   // jagged broken top of the right jamb
        for (i in 0 until 3) {
            val bx = x + openingW / 2f + b.rr(0.2f, jambW - 0.2f)
            val bz = z + b.rr(-0.5f, 0.5f)
            val bw = b.rr(0.5f, 1.0f); val bd = b.rr(0.8f, 1.5f)
            b.box(bx, y0 + (headY - y0) * breakRight + 0.18f * i, bz, bw, 0.36f, bd, pal)
        }
    } else {
        b.box(x, headY + corH / 2f - 0.28f, z, w, corH - 0.1f, d, pal)   // lintel body
        cornice(b, x, y0 + h, z, w, d, pal)
    }
    if (sillH > 0f) b.box(x, y0 + sillH / 2f, z, openingW, sillH, d, pal)   // window sill block
    b.box(x, y0 + 0.13f, z + 0.25f, w + 0.5f, 0.26f, d + 0.7f, 1)           // threshold step
    // Every frame pierces clean through: the mud-brick walls that once stood
    // behind these monoliths vanished millennia ago, so doorways, niches and
    // windows all open to the sky in the ruin as photographed today.
    if (sillH == 0f) {   // door pivot socket + foot-worn hollow in the threshold
        b.cylinder(x - openingW / 2f + 0.3f, y0 + 0.27f, z + 0.15f, 0.095f, 0.035f, floatArrayOf(0f, 1f, 0f), 10, 3)
        b.box(x, y0 + 0.265f, z + 0.25f, openingW * 0.78f, 0.014f, 0.34f, 3, flatColor = true, shade = 0.85f)
    }
    if (mode == FrameMode.WINDOW) inscription(b, x, y0 + sillH * 0.6f, z + d / 2f + 0.012f, min(1.5f, openingW * 0.7f), 2)   // inscribed sill front
}

/** Ashlar masonry wall: shaded backing + individual front blocks with joints. */
private fun ashlar(
    b: MeshBuilder, x0: Float, x1: Float, y0: Float, y1: Float,
    zFront: Float, depth: Float, bw: Float, bh: Float, pal: Int,
) {
    b.box((x0 + x1) / 2f, (y0 + y1) / 2f, zFront - depth / 2f, x1 - x0, y1 - y0, depth, pal, flatColor = true, shade = 0.52f)
    val rows = max(1, ((y1 - y0) / bh).roundToInt())
    for (r in 0 until rows) {
        val y = y0 + (r + 0.5f) * (y1 - y0) / rows
        val hh = (y1 - y0) / rows - 0.06f
        var x = x0 + (if (r % 2 != 0) -bw * 0.5f else 0f)
        while (x < x1 - 0.3f) {
            val w = min(bw * b.rr(0.85f, 1.15f), x1 - x - 0.05f)
            var bx = x; var bw2 = w
            if (bx < x0) { bw2 = w - (x0 - bx); bx = x0 }   // bonded end: stagger lead-in clipped flush at the wall end
            b.box(bx + bw2 / 2f, y, zFront - 0.09f, bw2 - 0.06f, hh, 0.18f, pal)
            x += w
        }
    }
}

/** Ruined wall with an irregular broken top: block columns of uneven height,
 *  occasional missing blocks, loose top blocks, return thickness at the ends. */
private fun ruinedWall(
    b: MeshBuilder, x0: Float, x1: Float, y0: Float, yTop: Float,
    zFront: Float, depth: Float, pal: Int,
) {
    val bw = 1.65f
    val drops = floatArrayOf(0f, 0f, 0.42f, 0.85f, 1.3f)
    var x = x0
    while (x < x1 - 0.4f) {
        val w = min(bw, x1 - x)
        val top = yTop - drops[floor((b.rnd() * 5f).toDouble()).toInt()]
        val courses = max(2, ((top - y0) / 0.78f).roundToInt())
        for (r in 0 until courses) {
            if (r > 0 && b.rnd() < 0.045f) continue   // missing block
            b.box(x + w / 2f, y0 + (r + 0.5f) * (top - y0) / courses, zFront - depth / 2f, w - 0.07f, (top - y0) / courses - 0.06f, depth, pal)
        }
        if (b.rnd() < 0.5f) {
            val tx = x + w / 2f + b.rr(-0.2f, 0.2f)
            val tw = w * b.rr(0.4f, 0.75f)
            val tilt = b.rr(-0.04f, 0.04f)
            b.box(tx, top + 0.14f, zFront - depth / 2f, tw, 0.28f, depth * 0.9f, pal, rz = tilt)
        }
        x += bw
    }
    b.box(x0 + 0.35f, (y0 + yTop) / 2f, zFront - depth / 2f, 0.7f, yTop - y0, depth + 0.5f, pal)   // end returns
    b.box(x1 - 0.35f, (y0 + yTop) / 2f, zFront - depth / 2f, 0.7f, yTop - y0, depth + 0.5f, pal)
}

/**
 * Fluted Achaemenid shaft built from stacked DRUMS, as the real columns are:
 * 48 shallow flutes with sharp arrises that run out into plain necking
 * (astragal) rings at both ends, a recessed shadow joint between drums,
 * slight per-drum shifts and tone changes (weathering, re-erected drums),
 * spall chips on the weather faces, and a rough broken top where truncated.
 */
private fun shaft(b: MeshBuilder, x: Float, y0: Float, z: Float, r: Float, height: Float, brokenTop: Boolean) {
    val seg = 192          // 4 samples per flute
    val flutes = 48f
    val c = PAL[7]
    val joints = ArrayList<Float>()
    var k = 1
    while (1.45f * k < height - 0.551f) { joints.add(1.45f * k); k++ }   // epsilon: identical joint count in float32 here and float64 (JS)
    // Per drum: [tone, radius jitter, x shift, z shift]
    val drums = Array(joints.size + 1) { floatArrayOf(b.rr(0.92f, 1.02f), b.rr(0.995f, 1.005f), b.rr(-0.01f, 0.01f), b.rr(-0.01f, 0.01f)) }
    // Rings: [y, drum index, isJointGroove]
    val lv = ArrayList<Triple<Float, Int, Int>>()
    lv.add(Triple(0f, 0, 0)); lv.add(Triple(0.24f, 0, 0))
    for (k in joints.indices) {
        val yj = joints[k]
        lv.add(Triple(yj - 0.045f, k, 0)); lv.add(Triple(yj, k, 1)); lv.add(Triple(yj + 0.045f, k + 1, 0))
    }
    lv.add(Triple(height - 0.24f, joints.size, 0)); lv.add(Triple(height, joints.size, 0))
    val rings = Array(lv.size) { IntArray(seg + 1) }
    for (li in lv.indices) {
        val (y, di, gr) = lv[li]
        val dm = drums[di]
        val taper = 1f - 0.10f * (y / height)                                   // gentle entasis taper
        val fade = max(0f, min(1f, min(y, height - y) / 0.24f))                 // flutes run out at the ends
        for (i in 0..seg) {
            val t = i.toFloat() / seg * (2f * Math.PI.toFloat())
            val g0 = 0.5f + 0.5f * cos(t * flutes)
            val groove = g0.toDouble().pow(1.8).toFloat() * fade
            var rad = r * taper * dm[1] * (1f - 0.036f * groove) * (if (gr == 1) 0.968f else 1f)   // sharp-arris flutes; recessed joint
            var topJit = 0f
            if (brokenTop && y == height) { rad *= b.rr(0.9f, 1.0f); topJit = b.rr(-0.3f, 0.05f) }
            val drdt = r * taper * dm[1] * (0.036f * 1.8f * flutes * g0.toDouble().pow(0.8).toFloat() * (0.5f * sin(t * flutes))) * fade
            val co = cos(t); val si = sin(t)
            var nx = co + (drdt / rad) * si     // e_r − (r′/r)·e_θ
            var nz = si - (drdt / rad) * co
            val l = sqrt(nx * nx + nz * nz)
            nx /= l; nz /= l
            rings[li][i] = b.v(x + dm[2] + rad * co, y0 + y + topJit, z + dm[3] + rad * si, nx, 0f, nz, c, (0.72f + 0.28f * (1f - groove)) * dm[0] * (if (gr == 1) 0.64f else 1f))
        }
    }
    for (j in 0 until rings.size - 1) for (i in 0 until seg)
        b.quad(rings[j][i], rings[j + 1][i], rings[j + 1][i + 1], rings[j][i + 1])   // up-first (see lathe)
    if (brokenTop) {
        val top = rings[rings.size - 1]
        val cen = b.v(x, y0 + height - 0.1f, z, 0f, 1f, 0f, c, 0.9f)
        for (i in 0 until seg) b.tri(cen, top[i + 1], top[i])
    }
    // Necking rings where the flutes run out (top ring lost on broken shafts)
    fun prof(vararg xy: Float): Array<FloatArray> = Array(xy.size / 2) { i -> floatArrayOf(xy[2 * i], xy[2 * i + 1]) }
    fun neckRing(yy: Float, rt: Float) {
        b.lathe(x, yy, z, prof(rt * 1.004f, 0f, rt * 1.05f, 0.045f, rt * 1.05f, 0.09f, rt * 1.004f, 0.135f), 64, 7)
    }
    neckRing(y0 + 0.03f, r)
    if (!brokenTop && height > 1.2f) neckRing(y0 + height - 0.165f, r * 0.905f)
    // Spall chips / weathering scars on the weather (east + south) faces
    if (height > 1.2f) {
        val n = if (height > 5f) 4 else 2
        for (i in 0 until n) {
            val cy = y0 + b.rr(0.5f, height - 0.5f)
            val d = b.rr(-0.3f, 0.3f)
            val w = b.rr(0.1f, 0.22f)
            val h = b.rr(0.35f, 0.9f)
            val rt = r * (1f - 0.10f * ((cy - y0) / height)) * 0.985f
            val s = sqrt(max(0.05f, rt * rt - d * d))
            if (i % 2 == 0) b.box(x + d, cy, z + s - 0.012f, w, h, 0.05f, 7, flatColor = true, shade = 0.7f)
            else b.box(x - s + 0.012f, cy, z + d, 0.05f, h, w, 7, flatColor = true, shade = 0.7f)
        }
    }
}

/**
 * Composite capital as the real stack (Encyclopaedia Iranica, CAPITALS;
 * Wikipedia "Persian column"): at the top of the round shaft a DOUBLE
 * COROLLA — lower section of leaves DROOPING downward, upper a PALM capital
 * spreading as it rises — then one tall SQUARE fluted zone carrying double
 * volutes at its bottom (inverted) and top, then the addorsed animal
 * protomes. Full capital ≈8 m on a ≈19 m column. [capH] = how much
 * survives; breaks fall between tiers. mode: 0 = none, 1 = broken stack,
 * 2 = weathered protome remnant on top.
 */
private fun capital(b: MeshBuilder, x: Float, y0: Float, z: Float, r: Float, mode: Int, capH: Float): Float {
    if (mode == 0 || capH <= 0.3f) return y0
    var y = y0
    fun prof(vararg xy: Float): Array<FloatArray> = Array(xy.size / 2) { i -> floatArrayOf(xy[2 * i], xy[2 * i + 1]) }
    // Leaf corolla: fluted bell whose rim is scalloped into pointed leaf tips
    // (hanging at the bottom rim of the drooping corolla, rising at the top
    // rim of the palm corolla) — the Louvre campaniform carving.
    fun bell(yy: Float, r0: Float, r1: Float, hh: Float, tipB: Float, tipT: Float) {
        b.lathe(x, yy, z, prof(r0, 0f, r0 * 0.99f, hh * 0.25f, r1, hh * 0.85f, r1 * 1.01f, hh), 60, 7, flutes = 20, tipB = tipB, tipT = tipT)
    }
    fun voluteZone(yy: Float, hh: Float, off: Float, w: Float, full: Boolean) {
        b.box(x + off, yy + hh / 2f, z, w, hh, w, 7)
        for (i in -3..3) {   // vertical fluting of the square zone, all four faces
            b.box(x + off + i * w / 7.4f, yy + hh / 2f, z + w / 2f + 0.015f, 0.045f, hh * 0.9f, 0.03f, 7, flatColor = true, shade = 0.72f)
            b.box(x + off + i * w / 7.4f, yy + hh / 2f, z - w / 2f - 0.015f, 0.045f, hh * 0.9f, 0.03f, 7, flatColor = true, shade = 0.72f)
            b.box(x + off + w / 2f + 0.015f, yy + hh / 2f, z + i * w / 7.4f, 0.03f, hh * 0.9f, 0.045f, 7, flatColor = true, shade = 0.72f)
            b.box(x + off - w / 2f - 0.015f, yy + hh / 2f, z + i * w / 7.4f, 0.03f, hh * 0.9f, 0.045f, 7, flatColor = true, shade = 0.72f)
        }
        val levels = if (full) floatArrayOf(yy + 0.45f, yy + hh - 0.45f) else floatArrayOf(yy + 0.45f)
        for (ly in levels) {   // double volutes, bottom (inverted) and top
            for (fz in intArrayOf(-1, 1)) {
                b.box(x + off, ly, z + fz * (w / 2f + 0.02f), w * 0.66f, 0.13f, 0.05f, 7, flatColor = true, shade = 0.78f)   // channel linking the scroll pair
                for (fx in intArrayOf(-1, 1)) {   // stepped spiral scrolls, Louvre proportions
                    b.cylinder(x + off + fx * w * 0.29f, ly, z + fz * (w / 2f + 0.04f), 0.33f, 0.16f, floatArrayOf(0f, 0f, 1f), 18, 7)
                    b.cylinder(x + off + fx * w * 0.29f, ly, z + fz * (w / 2f + 0.10f), 0.21f, 0.08f, floatArrayOf(0f, 0f, 1f), 14, 7, shade = 0.9f)
                    b.cylinder(x + off + fx * w * 0.29f, ly, z + fz * (w / 2f + 0.14f), 0.09f, 0.06f, floatArrayOf(0f, 0f, 1f), 10, 7, shade = 0.72f)
                }
            }
            for (fx in intArrayOf(-1, 1)) {
                b.box(x + off + fx * (w / 2f + 0.02f), ly, z, 0.05f, 0.13f, w * 0.66f, 7, flatColor = true, shade = 0.78f)
                for (fz in intArrayOf(-1, 1)) {
                    b.cylinder(x + off + fx * (w / 2f + 0.04f), ly, z + fz * w * 0.29f, 0.33f, 0.16f, floatArrayOf(1f, 0f, 0f), 18, 7)
                    b.cylinder(x + off + fx * (w / 2f + 0.14f), ly, z + fz * w * 0.29f, 0.09f, 0.06f, floatArrayOf(1f, 0f, 0f), 10, 7, shade = 0.72f)
                }
                b.cylinder(x + off + fx * (w / 2f + 0.03f), ly, z, 0.14f, 0.08f, floatArrayOf(1f, 0f, 0f), 10, 7, shade = 0.85f)   // side rosette boss
            }
            rosetteBand(b, x + off, ly, z + w / 2f - 0.005f, 0.2f)                                       // carved rosette between the scrolls
            b.cylinder(x + off, ly, z - (w / 2f + 0.03f), 0.14f, 0.08f, floatArrayOf(0f, 0f, 1f), 10, 7, shade = 0.85f)   // boss on the back face
        }
        b.box(x + off, yy + 0.09f, z, w + 0.14f, 0.16f, w + 0.14f, 7)
        b.box(x + off, yy + hh - 0.09f, z, w + 0.14f, 0.16f, w + 0.14f, 7)
        inscription(b, x + off, yy + hh - 0.85f, z + w / 2f + 0.03f, w * 0.62f, 1)
    }
    fun rubbleTop(yy: Float, w: Float): Float {
        for (i in 0 until 3) {
            val bx = x + b.rr(-w / 2f, w / 2f)
            val bz = z + b.rr(-w / 2f, w / 2f)
            val bw = b.rr(0.4f, 0.8f); val bd = b.rr(0.4f, 0.8f)
            val tilt = b.rr(-0.08f, 0.08f)
            b.box(bx, yy + 0.16f, bz, bw, 0.34f, bd, 7, rz = tilt)
        }
        return yy + 0.34f
    }
    bell(y, r * 1.22f, r * 0.95f, 1.05f, 0.075f, 0f); y += 1.05f       // drooping-leaf corolla (flare + leaf tips at the bottom)
    if (capH < 1.5f) return rubbleTop(y, 1.2f)
    b.lathe(x, y - 0.02f, z, prof(r * 0.96f, 0f, r * 1.03f, 0.05f, r * 1.03f, 0.1f, r * 0.97f, 0.15f), 60, 7)   // binding ring between the corollas
    bell(y, r * 0.98f, r * 1.32f, 1.15f, 0f, 0.075f); y += 1.15f       // palm corolla, spreading as it rises, tips at the top rim
    if (capH < 2.6f) return rubbleTop(y, 1.0f)
    val zoneH = min(3.9f, capH - 2.2f)
    voluteZone(y, zoneH, if (zoneH >= 3.9f) b.rr(-0.05f, 0.05f) else 0f, 2.1f, zoneH >= 3.9f); y += zoneH
    if (mode == 1 || zoneH < 3.9f) return rubbleTop(y, 1.4f)
    if (capH < 7.4f) {
        val yaw = b.rr(-0.1f, 0.1f)
        b.box(x, y + 0.28f, z, 1.45f, 0.56f, 1.45f, 7, ry = yaw)
        return rubbleTop(y + 0.56f, 1.0f)
    }
    // Weathered double bull protome (axis E–W, the beam direction): coupled
    // forequarters with folded forelegs and hooves at both ends, the central
    // beam saddle, the east head still legible — forward-curving horns, ears,
    // raised round eyes, forelock curls, nostrils, dewlap — the west head
    // broken away, as the surviving Persepolis/Susa capitals read.
    b.box(x, y + 0.55f, z, 1.18f, 0.95f, 2.9f, 7)                                    // coupled forequarters
    b.box(x, y + 1.14f, z, 0.95f, 0.3f, 1.15f, 7)                                    // beam saddle between the protomes
    b.box(x, y + 1.31f, z, 0.7f, 0.06f, 0.9f, 7, flatColor = true, shade = 0.8f)     // worn beam seat
    for (sd in intArrayOf(1, -1)) {                                                  // necks, folded forelegs, hooves
        b.box(x, y + 0.82f, z + sd * 1.12f, 0.98f, 1.0f, 0.7f, 7, rx = sd * 0.38f)
        b.box(x - 0.42f, y + 0.22f, z + sd * 1.15f, 0.3f, 0.5f, 0.85f, 7, rx = sd * 0.5f)
        b.box(x + 0.42f, y + 0.22f, z + sd * 1.15f, 0.3f, 0.5f, 0.85f, 7, rx = sd * 0.5f)
        b.box(x - 0.42f, y + 0.07f, z + sd * 1.52f, 0.26f, 0.15f, 0.32f, 7, shade = 0.82f)
        b.box(x + 0.42f, y + 0.07f, z + sd * 1.52f, 0.26f, 0.15f, 0.32f, 7, shade = 0.82f)
    }
    b.box(x, y + 0.95f, z + 1.5f, 0.9f, 1.05f, 0.82f, 7, rx = 0.15f)                 // east head, worn
    b.box(x, y + 0.62f, z + 1.98f, 0.6f, 0.5f, 0.44f, 7)                             // muzzle
    b.box(x - 0.16f, y + 0.66f, z + 2.21f, 0.09f, 0.08f, 0.03f, 7, flatColor = true, shade = 0.5f)    // nostrils
    b.box(x + 0.16f, y + 0.66f, z + 2.21f, 0.09f, 0.08f, 0.03f, 7, flatColor = true, shade = 0.5f)
    b.box(x, y + 0.48f, z + 2.2f, 0.42f, 0.04f, 0.03f, 7, flatColor = true, shade = 0.55f)            // mouth line
    b.box(x, y + 0.32f, z + 1.72f, 0.42f, 0.5f, 0.5f, 7, rx = 0.3f)                  // dewlap
    for (sd in intArrayOf(-1, 1)) {
        b.cylinder(x + sd * 0.46f, y + 1.12f, z + 1.62f, 0.095f, 0.05f, floatArrayOf(1f, 0f, 0f), 10, 7, shade = 0.92f)   // raised round eye
        b.box(x + sd * 0.46f, y + 1.27f, z + 1.6f, 0.05f, 0.07f, 0.3f, 7, flatColor = true, shade = 0.8f)                 // brow ridge
        b.box(x + sd * 0.55f, y + 1.3f, z + 1.24f, 0.24f, 0.1f, 0.36f, 7, rz = -sd * 0.35f)                              // ear
        b.box(x + sd * 0.38f, y + 1.62f, z + 1.48f, 0.16f, 0.44f, 0.16f, 7, rz = -sd * 0.45f)                            // horn rising outward
        b.box(x + sd * 0.56f, y + 1.82f, z + 1.6f, 0.11f, 0.36f, 0.11f, 7, rx = 0.55f)                                   // horn tip curving forward
    }
    b.box(x, y + 1.5f, z + 1.66f, 0.5f, 0.16f, 0.34f, 7)                             // forelock between the horns
    for (k in -1..1) b.cylinder(x + k * 0.15f, y + 1.52f, z + 1.84f, 0.07f, 0.05f, floatArrayOf(0f, 0f, 1f), 8, 7, shade = 0.85f)   // forelock curls
    b.box(x, y + 0.7f, z - 1.45f, 0.84f, 0.7f, 0.72f, 7, rx = -0.3f)                 // west head, broken away lower
    b.box(x - 0.3f, y + 1.08f, z - 1.48f, 0.13f, 0.3f, 0.13f, 7, rz = 0.4f)          // its one horn stub + ear
    b.box(x + 0.5f, y + 0.88f, z - 1.36f, 0.2f, 0.1f, 0.3f, 7)
    return y + 1.9f
}

private fun column(b: MeshBuilder, x: Float, z: Float, flutedH: Float, capMode: Int, r: Float, capH: Float) {
    val y0 = PLATFORM_Y
    // East-portico base (Iranica, APADANA; Louvre campaniform base from Susa):
    // a bell carved with two tiers of pendant pointed palm leaves under a fat
    // discoid torus, on a two-step square plinth inscribed with the king's name.
    b.box(x, y0 + 0.075f, z, 2.72f, 0.15f, 2.72f, 1)                       // plinth, lower step
    b.box(x, y0 + 0.225f, z, 2.45f, 0.15f, 2.45f, 1)                       // plinth, upper step
    b.lathe(x, y0, z, arrayOf(floatArrayOf(1.98f, 0f), floatArrayOf(1.78f, 0.05f), floatArrayOf(1.58f, 0.1f), floatArrayOf(1.44f, 0.16f)), 40, 8)   // wind-blown sand collar against the plinth
    inscription(b, x, y0 + 0.24f, z + 1.24f, 1.6f, 1)
    b.lathe(x, y0 + 0.3f, z, arrayOf(
        floatArrayOf(1.26f, 0f), floatArrayOf(1.22f, 0.14f), floatArrayOf(1.10f, 0.42f), floatArrayOf(1.04f, 0.62f),
        floatArrayOf(1.08f, 0.78f), floatArrayOf(0.98f, 0.9f), floatArrayOf(1.04f, 1.02f), floatArrayOf(1.06f, 1.12f),
        floatArrayOf(0.96f, 1.24f), floatArrayOf(0.88f, 1.34f), floatArrayOf(0.86f, 1.47f)), 60, 7, flutes = 20, tipB = 0.085f)
    if (flutedH > 0.4f) shaft(b, x, y0 + 1.77f, z, r, flutedH, capMode == 0)
    capital(b, x, y0 + 1.77f + max(flutedH, 0f), z, r, capMode, capH)
}

/**
 * Processional figure in relief. variant: 0 = Median round cap with offering
 * bowl · 1 = Persian fluted tiara with bowl and spear · 2 = Persian guard
 * (tiara, grounded spear, shield) · 3 = Median guard (round cap, spear,
 * shield). Guards head each register and form the central panel, as carved.
 * Fine carving is only emitted where it can resolve at landscape scale.
 */
private fun reliefFigure(b: MeshBuilder, x: Float, y: Float, zWall: Float, h: Float, dir: Float, variant: Int = 0) {
    val w = h * 0.30f
    val o = 0.045f
    val guard = variant >= 2
    val tiara = variant == 1 || variant == 2
    fun p(vararg xy: Float): Array<FloatArray> = Array(xy.size / 2) { i -> floatArrayOf(xy[2 * i], xy[2 * i + 1]) }
    fun r(pts: Array<FloatArray>, z: Float, d: Float, pal: Int) {   // dir-safe: restore CCW winding when mirrored
        b.relief(if (dir < 0) pts.reversedArray() else pts, z, d, pal)
    }
    // Incised dark outline (baked AO) so the carving reads at landscape size
    b.relief(p(x - w - o, y - 0.02f, x + w + o, y - 0.02f, x + w * 0.72f + o, y + h * 0.70f, x - w * 0.72f - o, y + h * 0.70f), zWall, 0.04f, 3)
    b.relief(p(x - w, y, x + w, y, x + w * 0.72f, y + h * 0.68f, x - w * 0.72f, y + h * 0.68f), zWall, 0.075f, 4)       // robe
    if (h >= 0.75f) {
        for (i in -1..1)   // robe pleats
            b.relief(p(x + i * w * 0.42f - 0.022f, y + h * 0.06f, x + i * w * 0.42f + 0.022f, y + h * 0.06f, x + i * w * 0.34f + 0.022f, y + h * 0.62f, x + i * w * 0.34f - 0.022f, y + h * 0.62f), zWall + 0.075f, 0.02f, 3)
        b.relief(p(x - w * 0.8f, y + h * 0.62f, x + w * 0.8f, y + h * 0.62f, x + w * 0.8f, y + h * 0.7f, x - w * 0.8f, y + h * 0.7f), zWall, 0.085f, 4)   // belt
    }
    if (tiara) {             // fluted Persian tiara
        b.relief(p(x - w * 0.42f, y + h * 0.68f, x + w * 0.5f, y + h * 0.68f, x + w * 0.44f, y + h * 0.99f, x - w * 0.34f, y + h * 0.99f), zWall, 0.09f, 4)
        if (h >= 0.75f) for (i in 0 until 3)
            b.relief(p(x - w * 0.3f + i * w * 0.32f, y + h * 0.72f, x - w * 0.3f + i * w * 0.32f + 0.03f, y + h * 0.72f, x - w * 0.28f + i * w * 0.3f + 0.03f, y + h * 0.95f, x - w * 0.28f + i * w * 0.3f, y + h * 0.95f), zWall + 0.09f, 0.018f, 3)
    } else {                 // round Median cap + head (stays CCW under mirroring; not reversed)
        b.relief(p(x - w * 0.5f, y + h * 0.68f, x + w * 0.62f, y + h * 0.68f, x + w * 0.62f + dir * h * 0.07f, y + h * 0.82f, x + w * 0.32f, y + h, x - w * 0.30f, y + h), zWall, 0.085f, 4)
    }
    if (guard) {
        // Grounded spear, butt resting on the forward foot; wicker shield
        r(p(x + dir * w * 0.95f, y, x + dir * w * 1.05f, y, x + dir * w * 1.02f, y + h * 1.04f, x + dir * w * 0.92f, y + h * 1.04f), zWall, 0.055f, 4)
        r(p(x + dir * w * 0.45f, y + h * 0.16f, x + dir * w * 0.95f, y + h * 0.16f, x + dir * w * 0.95f, y + h * 0.56f, x + dir * w * 0.45f, y + h * 0.56f), zWall + 0.02f, 0.06f, 4)
        r(p(x + dir * w * 0.5f, y + h * 0.34f, x + dir * w * 0.9f, y + h * 0.34f, x + dir * w * 0.9f, y + h * 0.4f, x + dir * w * 0.5f, y + h * 0.4f), zWall + 0.08f, 0.02f, 3)   // shield band
    } else {
        r(p(x + dir * w * 0.7f, y + h * 0.50f, x + dir * w * 1.55f, y + h * 0.58f, x + dir * w * 1.6f, y + h * 0.66f, x + dir * w * 0.72f, y + h * 0.60f), zWall, 0.06f, 4)            // offering arm
        r(p(x + dir * w * 1.45f, y + h * 0.62f, x + dir * w * 1.85f, y + h * 0.62f, x + dir * w * 1.78f, y + h * 0.74f, x + dir * w * 1.52f, y + h * 0.74f), zWall, 0.07f, 4)          // bowl
        if (variant == 1)
            r(p(x - dir * w * 0.95f, y + h * 0.1f, x - dir * w * 0.85f, y + h * 0.1f, x - dir * w * 0.8f, y + h * 0.95f, x - dir * w * 0.9f, y + h * 0.95f), zWall, 0.05f, 4)          // spear
    }
}

/** Tribute animal in relief. kind: 0 horse, 1 camel, 2 bull. Height [h], facing [dir]. */
private fun reliefAnimal(b: MeshBuilder, x: Float, y: Float, zWall: Float, h: Float, kind: Int, dir: Float) {
    val s = h / 1.15f
    fun p(vararg xy: Float): Array<FloatArray> = Array(xy.size / 2) { i -> floatArrayOf(xy[2 * i], xy[2 * i + 1]) }
    fun d(pts: Array<FloatArray>, dep: Float, pal: Int = 4) {
        b.relief(Array(pts.size) { i -> floatArrayOf(x + dir * pts[i][0] * s, y + pts[i][1] * s) }, zWall, dep, pal)
    }
    d(p(-0.85f, 0.42f, 0.55f, 0.42f, 0.66f, 0.78f, -0.66f, 0.82f), 0.06f, 3)                     // incised body outline
    d(p(-0.8f, 0.45f, 0.5f, 0.45f, 0.6f, 0.76f, -0.62f, 0.79f), 0.085f)                          // body
    for (lx in floatArrayOf(-0.62f, -0.4f, 0.28f, 0.46f))                                        // legs
        d(p(lx, 0.02f, lx + 0.13f, 0.02f, lx + 0.13f, 0.47f, lx, 0.47f), 0.08f)
    d(p(-0.82f, 0.5f, -1.0f, 0.22f, -0.9f, 0.18f, -0.68f, 0.46f), 0.07f)                         // tail
    when (kind) {
        0 -> {        // horse: crested neck, head, ear, harness
            d(p(0.45f, 0.72f, 0.86f, 1.02f, 1.02f, 0.94f, 0.66f, 0.6f), 0.09f)
            d(p(0.84f, 1.0f, 1.18f, 0.96f, 1.2f, 0.84f, 0.88f, 0.84f), 0.09f)
            d(p(0.88f, 1.0f, 0.94f, 1.16f, 1.0f, 1.02f), 0.08f)
            d(p(0.52f, 0.66f, 0.9f, 0.9f, 0.95f, 0.84f, 0.58f, 0.6f), 0.1f, 3)
        }
        1 -> {        // camel: two humps, long neck
            d(p(-0.45f, 0.76f, -0.2f, 0.94f, 0.02f, 0.78f), 0.09f)
            d(p(0.06f, 0.78f, 0.3f, 0.95f, 0.5f, 0.76f), 0.09f)
            d(p(0.48f, 0.7f, 0.78f, 1.06f, 0.9f, 1.0f, 0.66f, 0.62f), 0.09f)
            d(p(0.76f, 1.04f, 1.06f, 1.08f, 1.08f, 0.96f, 0.82f, 0.92f), 0.09f)
        }
        else -> {     // bull: hump, lowered head, horn, dewlap
            d(p(0.18f, 0.76f, 0.44f, 0.9f, 0.6f, 0.74f), 0.09f)
            d(p(0.5f, 0.66f, 0.92f, 0.6f, 0.98f, 0.38f, 0.6f, 0.36f), 0.09f)
            d(p(0.86f, 0.58f, 1.06f, 0.76f, 1.12f, 0.68f, 0.94f, 0.52f), 0.08f)
            d(p(0.58f, 0.4f, 0.74f, 0.4f, 0.7f, 0.16f, 0.6f, 0.16f), 0.07f)
        }
    }
}

/** Rosette border ornament of the stair façades (four broad petals + boss). */
private fun rosette(b: MeshBuilder, x: Float, y: Float, zWall: Float, r: Float) {
    val oct = Array(8) { i ->
        val a = (i / 8f * 2f * Math.PI.toFloat()) + (Math.PI.toFloat() / 8f)
        floatArrayOf(x + cos(a) * r, y + sin(a) * r)
    }
    b.relief(oct, zWall, 0.035f, 3)
    for (i in 0 until 4) {
        val a = i / 4f * 2f * Math.PI.toFloat()
        b.relief(arrayOf(
            floatArrayOf(x, y),
            floatArrayOf(x + cos(a - 0.5f) * r * 0.8f, y + sin(a - 0.5f) * r * 0.8f),
            floatArrayOf(x + cos(a) * r * 0.9f, y + sin(a) * r * 0.9f),
            floatArrayOf(x + cos(a + 0.5f) * r * 0.8f, y + sin(a + 0.5f) * r * 0.8f),
        ), zWall + 0.02f, 0.04f, 4)
    }
    val c = Array(8) { i ->
        val a = i / 8f * 2f * Math.PI.toFloat()
        floatArrayOf(x + cos(a) * r * 0.3f, y + sin(a) * r * 0.3f)
    }
    b.relief(c, zWall + 0.045f, 0.03f, 4)
}

/** Cheap 12-petal rosette for the separator bands between registers
 *  (octagon boss + inner octagon only — reads identically at landscape scale). */
private fun rosetteBand(b: MeshBuilder, x: Float, y: Float, zWall: Float, r: Float) {
    val oct = Array(8) { i ->
        val a = i / 8f * 2f * Math.PI.toFloat() + Math.PI.toFloat() / 8f
        floatArrayOf(x + cos(a) * r, y + sin(a) * r)
    }
    b.relief(oct, zWall, 0.03f, 4)
    val c = Array(8) { i ->
        val a = i / 8f * 2f * Math.PI.toFloat()
        floatArrayOf(x + cos(a) * r * 0.45f, y + sin(a) * r * 0.45f)
    }
    b.relief(c, zWall + 0.015f, 0.03f, 3)
}

/** Date palm in relief (rows of palms frame the lion-and-bull panels). */
private fun palmTree(b: MeshBuilder, x: Float, y: Float, zWall: Float, h: Float) {
    fun p(vararg xy: Float): Array<FloatArray> = Array(xy.size / 2) { i -> floatArrayOf(xy[2 * i], xy[2 * i + 1]) }
    b.relief(p(x - 0.05f, y, x + 0.05f, y, x + 0.09f, y + h * 0.62f, x - 0.01f, y + h * 0.62f), zWall, 0.06f, 5)
    for (k in -2..2) {
        val a = k * 0.5f
        val tx = x + sin(a) * h * 0.45f
        val ty = y + h * 0.62f + cos(a) * h * 0.3f - kotlin.math.abs(k) * h * 0.06f
        b.relief(p(x - 0.045f, y + h * 0.6f, x + 0.045f, y + h * 0.6f, tx, ty), zWall, 0.055f, 5)
    }
    b.relief(p(x - 0.09f, y + h * 0.52f, x + 0.11f, y + h * 0.52f, x + 0.09f, y + h * 0.63f, x - 0.07f, y + h * 0.63f), zWall, 0.065f, 4)   // date cluster
}

/** Seated sphinx in relief (lion body, human head, raised foreleg), facing [dir]. */
private fun sphinx(b: MeshBuilder, x: Float, y: Float, zWall: Float, h: Float, dir: Float) {
    fun p(vararg xy: Float): Array<FloatArray> = Array(xy.size / 2) { i -> floatArrayOf(xy[2 * i], xy[2 * i + 1]) }
    fun d(pts: Array<FloatArray>, dep: Float) {
        val q = Array(pts.size) { i -> floatArrayOf(x + dir * pts[i][0] * h, y + pts[i][1] * h) }
        b.relief(if (dir < 0) q.reversedArray() else q, zWall, dep, 4)
    }
    d(p(-0.62f, 0.06f, 0.3f, 0.06f, 0.36f, 0.42f, -0.5f, 0.44f), 0.08f)        // body
    d(p(0.24f, 0.4f, 0.52f, 0.4f, 0.5f, 0.78f, 0.26f, 0.78f), 0.085f)           // human head + headdress
    d(p(-0.5f, 0.42f, 0.02f, 0.88f, 0.14f, 0.44f), 0.075f)                      // wing
    d(p(0.3f, 0.08f, 0.62f, 0.3f, 0.68f, 0.22f, 0.38f, 0.02f), 0.07f)           // foreleg raised in respect
    d(p(-0.62f, 0.1f, -0.78f, 0.34f, -0.68f, 0.4f, -0.52f, 0.16f), 0.06f)       // tail
}

/** The winged disk (Ahuramazda symbol) hovering over the central guards. */
private fun wingedDisk(b: MeshBuilder, x: Float, y: Float, zWall: Float, s: Float) {
    fun p(vararg xy: Float): Array<FloatArray> = Array(xy.size / 2) { i -> floatArrayOf(xy[2 * i], xy[2 * i + 1]) }
    val disc = Array(10) { i ->
        val a = i / 10f * 2f * Math.PI.toFloat()
        floatArrayOf(x + cos(a) * 0.3f * s, y + sin(a) * 0.3f * s)
    }
    b.relief(disc, zWall, 0.07f, 4)
    val ring = Array(10) { i ->
        val a = i / 10f * 2f * Math.PI.toFloat()
        floatArrayOf(x + cos(a) * 0.17f * s, y + sin(a) * 0.17f * s)
    }
    b.relief(ring, zWall + 0.04f, 0.04f, 3)
    for (sd in intArrayOf(-1, 1)) for (f in 0 until 3) {   // three feather rows per wing
        val y0 = y + (0.12f - f * 0.13f) * s
        val len = (1.45f - f * 0.22f) * s
        val rise = (0.3f - f * 0.1f) * s
        val pts = p(x + sd * 0.22f * s, y0, x + sd * len, y0 + rise, x + sd * len, y0 + rise + 0.1f * s, x + sd * 0.22f * s, y0 + 0.11f * s)
        b.relief(if (sd < 0) pts.reversedArray() else pts, zWall, 0.06f, 4)
    }
    b.relief(p(x - 0.2f * s, y - 0.28f * s, x + 0.2f * s, y - 0.28f * s, x + 0.34f * s, y - 0.72f * s, x - 0.34f * s, y - 0.72f * s), zWall, 0.06f, 4)   // tail fan
}

/** Royal chariot in relief: spoked wheel, cab, driver, horse team. */
private fun reliefChariot(b: MeshBuilder, x: Float, y: Float, zWall: Float, h: Float) {
    fun p(vararg xy: Float): Array<FloatArray> = Array(xy.size / 2) { i -> floatArrayOf(xy[2 * i], xy[2 * i + 1]) }
    for (i in 0 until 8) {   // wheel: outer octagon ring in segments + spokes
        val aa = i / 8f * 2f * Math.PI.toFloat()
        val bb = (i + 1) / 8f * 2f * Math.PI.toFloat()
        b.relief(p(
            x + cos(aa) * h * 0.3f, y + h * 0.32f + sin(aa) * h * 0.3f,
            x + cos(bb) * h * 0.3f, y + h * 0.32f + sin(bb) * h * 0.3f,
            x + cos(bb) * h * 0.36f, y + h * 0.32f + sin(bb) * h * 0.36f,
            x + cos(aa) * h * 0.36f, y + h * 0.32f + sin(aa) * h * 0.36f), zWall, 0.08f, 4)
        b.relief(p(
            x - 0.03f, y + h * 0.3f, x + 0.03f, y + h * 0.3f,
            x + cos(aa) * h * 0.3f, y + h * 0.32f + sin(aa) * h * 0.3f,
            x + cos(aa) * h * 0.3f - 0.05f, y + h * 0.32f + sin(aa) * h * 0.3f), zWall, 0.06f, 4)
    }
    b.relief(p(x - h * 0.44f, y + h * 0.55f, x + h * 0.1f, y + h * 0.55f, x + h * 0.1f, y + h * 0.86f, x - h * 0.44f, y + h * 0.86f), zWall, 0.085f, 4)   // cab
    reliefFigure(b, x - h * 0.18f, y + h * 0.72f, zWall + 0.04f, h * 0.42f, 1f, 1)                                                                            // driver
    b.relief(p(x + h * 0.08f, y + h * 0.5f, x + h * 0.75f, y + h * 0.62f, x + h * 0.75f, y + h * 0.68f, x + h * 0.08f, y + h * 0.58f), zWall, 0.06f, 4)       // shaft
    reliefAnimal(b, x + h * 1.05f, y + h * 0.08f, zWall, h * 0.72f, 0, 1f)
}

// ============================================================================
// Scene assembly — element tables (positions/sizes in metres)
// ============================================================================

private data class FrameSpec(
    val x: Float, val z: Float, val w: Float, val h: Float, val openingW: Float,
    val mode: FrameMode, val breakRight: Float, val baseY: Float = PLATFORM_Y,
)

// Element positions/sizes are mapped from the reference photograph's
// silhouette. Depths (z) are spaced realistically — two portico column rows
// ~7 m apart, background frames and walls 8–12 m behind the front row — and
// back elements are shifted a touch south in x to cancel the parallax of the
// slightly angled camera, so the skyline still matches the photo.
private val FRAMES: Array<FrameSpec> = arrayOf(
    FrameSpec(-39.7f, 4.9f, 4.9f, 5.6f, 1.9f, FrameMode.OPEN, 1.0f, 2.85f),   // far-left frame on the lower ledge (photo left edge)
    FrameSpec(-25.6f, -3.4f, 6.1f, 10.4f, 2.15f, FrameMode.OPEN, 1.0f),       // great doorway, tall narrow opening, sky through it
    FrameSpec(-19.6f, -6.0f, 5.4f, 7.9f, 2.2f, FrameMode.OPEN, 0.55f),        // ruined frame, right jamb cut down
    FrameSpec(-12.4f, -6.8f, 4.6f, 6.1f, 2.65f, FrameMode.WINDOW, 1.0f),      // 2.65 m monolithic window (Tachara measure)
    FrameSpec(-5.9f, -4.2f, 5.3f, 7.8f, 2.2f, FrameMode.OPEN, 1.0f),
    FrameSpec(1.4f, -3.0f, 6.0f, 9.9f, 2.55f, FrameMode.NICHE, 1.0f),         // central niche with inner door
    FrameSpec(9.6f, -7.4f, 5.2f, 7.4f, 2.2f, FrameMode.NICHE, 0.8f),
    FrameSpec(16.4f, -8.2f, 4.6f, 6.4f, 2.65f, FrameMode.WINDOW, 1.0f),
    FrameSpec(-16.6f, -10.5f, 4.2f, 5.2f, 2.0f, FrameMode.WINDOW, 1.0f),      // background frames, lens-compressed cluster
    FrameSpec(3.7f, -10.5f, 4.4f, 5.7f, 2.0f, FrameMode.WINDOW, 1.0f),
    FrameSpec(23.0f, -10.5f, 5.0f, 6.6f, 2.2f, FrameMode.NICHE, 0.85f),
    FrameSpec(-34.4f, -9.5f, 5.2f, 7.0f, 2.2f, FrameMode.OPEN, 0.7f),
)

// Lone surviving jamb monoliths between the frames: (x, z, width, height)
private val JAMBS: Array<FloatArray> = arrayOf(
    floatArrayOf(-16.4f, -1.6f, 1.5f, 6.6f), floatArrayOf(-9.6f, -3.2f, 1.3f, 5.2f),
    floatArrayOf(-3.2f, -9.0f, 1.6f, 7.6f), floatArrayOf(5.7f, -3.6f, 1.35f, 6.0f),
    floatArrayOf(13.5f, -2.6f, 1.25f, 4.7f), floatArrayOf(20.3f, -9.2f, 1.5f, 5.8f),
    floatArrayOf(-30.7f, -7.5f, 1.3f, 5.4f), floatArrayOf(34.8f, -9.4f, 1.4f, 4.3f),
    floatArrayOf(-22.8f, -9.2f, 1.2f, 4.4f), floatArrayOf(14.9f, -8.8f, 1.2f, 5.5f),
)

private data class ColumnSpec(val x: Float, val z: Float, val shaftH: Float, val capMode: Int, val capH: Float)

// Surviving columns of the east portico (two rows of six in reality). Total
// heights above the platform = 1.77 (plinth+base) + shaft + capital, matched
// to the photo: tallest 19.2 m, its pair 15.7 m, centre stack 12.9 m.
private val COLUMNS: Array<ColumnSpec> = arrayOf(
    ColumnSpec(8.9f, -1.6f, 5.6f, 1, 5.2f),     // centre broken stack (photo x≈0.60)
    ColumnSpec(10.4f, -8.6f, 4.6f, 1, 4.6f),    // back row
    ColumnSpec(15.5f, -1.8f, 6.4f, 0, 0f),      // plain broken shafts (photo x≈0.69 pair)
    ColumnSpec(17.3f, -8.8f, 7.1f, 0, 0f),
    ColumnSpec(20.2f, -1.6f, 3.4f, 0, 0f),
    ColumnSpec(21.7f, -8.6f, 7.2f, 0, 0f),      // stacked-drum broken shaft (photo x≈0.76)
    ColumnSpec(26.2f, -2.0f, 9.35f, 2, 9.3f),   // TALLEST: 19.2 m above the platform (photo x≈0.80)
    ColumnSpec(29.3f, -2.8f, 6.9f, 2, 6.9f),    // second tall (photo x≈0.84)
    ColumnSpec(32.4f, -8.6f, 4.9f, 0, 0f),
    ColumnSpec(36.9f, -2.2f, 1.4f, 1, 5.2f),
    ColumnSpec(39.3f, -8.4f, 2.3f, 1, 4.5f),    // far isolated column (photo x≈0.97)
    ColumnSpec(2.9f, -8.8f, 2.4f, 0, 0f),
)

private fun buildPersepolisScene(): MeshBuilder {
    PAL = PAL_P   // combined scene: Persepolis palette
    val b = MeshBuilder()   // RNG re-seeded in its constructor → identical every build

    fun pts(vararg xy: Float): Array<FloatArray> = Array(xy.size / 2) { i -> floatArrayOf(xy[2 * i], xy[2 * i + 1]) }

    /* ---- terraces / platform (a real floor ~24 m deep, read at the angle) ----
   Combined-scene edit: the platform's empty side wings — beyond the outermost
   structures/pavement (measured content x −42.6 … +46.0) — are cut back by
   80 % on each side, so the platform ends show within the frame. */
    b.box(1.36f, 0.25f, -1f, 97.27f, 0.5f, 24f, 6)                                                    // forecourt plinth slab (empty wings cut back — see note below)
    ashlar(b, -46.47f, 49.2f, 0.5f, 2.6f, FRONT_Z, 1.4f, 3.1f, 0.72f, 6)                              // lower front wall (podium 2.6 m)
    b.box(1.36f, 2.72f, 6.2f, 96.07f, 0.24f, 3.4f, 6)                                                 // lower ledge
    b.box(1.36f, (0.5f + PLATFORM_Y) / 2f, -4.4f, 95.67f, PLATFORM_Y - 0.5f, 17.2f, 1)                // upper platform body (front z=4.2, back z=-13)
    b.box(1.36f, PLATFORM_Y - 0.12f, -4.15f, 96.07f, 0.24f, 17.7f, 6)                                 // upper slab (front edge z=4.7)
    var jx = -42f
    while (jx <= 42f) {                                                                          // paving-slab joints on the platform top
        b.box(jx, PLATFORM_Y + 0.006f, -4.1f, 0.07f, 0.012f, 17.0f, 3, flatColor = true, shade = 0.85f)
        jx += 3.5f
    }
    var jz = -11f
    while (jz <= 4f) {
        b.box(1.36f, PLATFORM_Y + 0.006f, jz, 95.67f, 0.012f, 0.07f, 3, flatColor = true, shade = 0.85f)
        jz += 3.2f
    }
    b.box(24f, PLATFORM_Y + 0.05f, -5.0f, 44f, 0.1f, 11.5f, 6)                                   // east-portico pavement under the two column rows
    b.box(24f, PLATFORM_Y + 0.02f, 0.95f, 44f, 0.18f, 0.5f, 1)                                   // its front step edge
    ashlar(b, -46.07f, 48.8f, 2.85f, 4.12f, 4.24f, 0.5f, 2.9f, 0.62f, 1)                               // ashlar facing of upper platform front
    // Processional frieze + inscription band on the upper platform front
    var fx = -41f; var fi = 0
    while (fx < 10f) {
        if (!(fx > -27f && fx < -18f)) {                                                         // portal pier interrupts it
            if (fi % 4 == 3) reliefAnimal(b, fx + 0.5f, 3.0f, 4.27f, 0.62f, fi % 3, 1f)
            else reliefFigure(b, fx, 3.0f, 4.27f, 0.66f, 1f, fi % 2)
        }
        fx += 2.35f; fi++
    }
    inscription(b, -32f, 4.02f, 4.26f, 14f, 1)
    inscription(b, -8f, 4.02f, 4.26f, 16f, 1)
    // Low soldier relief band along the foot of the front wall (right of portal)
    var sx = -16f; var si = 0
    while (sx < 42f) { reliefFigure(b, sx, 0.62f, FRONT_Z + 0.02f, 0.52f, 1f, if (si % 2 != 0) 3 else 2); sx += 1.35f; si++ }

    /* Portal in the UPPER platform front (centre-left of the photo, below the
       great doorway): its sill sits on the 2.6 m ledge, behind the lower
       foreground wall, exactly as photographed. */
    b.box(-22.6f, (2.6f + PLATFORM_Y) / 2f, 5.0f, 6.4f, PLATFORM_Y - 2.6f, 1.8f, 1)              // pier body (front z=5.9)
    ashlar(b, -25.8f, -24.2f, 2.6f, 4.12f, 6.2f, 0.3f, 1.3f, 0.6f, 1)                              // pier front, either side of the door
    ashlar(b, -21.0f, -19.4f, 2.6f, 4.12f, 6.2f, 0.3f, 1.3f, 0.6f, 1)
    b.box(-22.6f, 3.32f, 5.92f, 2.0f, 1.34f, 0.14f, 3, flatColor = true)                          // dark recess
    b.box(-22.6f, 3.26f, 6.02f, 1.22f, 1.18f, 0.08f, 2)                                            // carved inner door slab
    reliefFigure(b, -22.6f, 2.74f, 6.08f, 1.02f, 1f, 1)
    for (s in intArrayOf(-1, 1)) {                                                                 // stepped jambs
        b.box(-22.6f + s * 1.38f, 3.36f, 6.12f, 0.72f, 1.52f, 0.4f, 2)
        b.box(-22.6f + s * 1.06f, 3.36f, 6.2f, 0.34f, 1.52f, 0.34f, 2)
    }
    b.box(-22.6f, 4.2f, 6.12f, 4.1f, 0.3f, 0.45f, 2)                                               // lintel + cap course to platform top
    b.box(-22.6f, 4.33f, 6.08f, 4.4f, 0.12f, 0.55f, 2)
    inscription(b, -22.6f, 4.02f, 6.36f, 2.4f, 1)
    b.box(-22.6f, 2.66f, 6.05f, 3.4f, 0.12f, 0.7f, 6)                                              // threshold on the ledge
    b.box(-34f, 2.0f, 4.5f, 2.0f, 3.0f, 0.9f, 1)
    b.box(-34f, 3.72f, 4.5f, 2.3f, 0.44f, 1.1f, 1)                                                 // projecting pier, left

    /* ---- Apadana east-stair façade (right): reliefs + stepped crenellations ---- */
    val stx0 = 12f; val stx1 = 43.5f
    // The real façade is a tall RECTANGULAR relief wall; only its right end is
    // cut by the sloping stair cheek that carries the lion-and-bull panel.
    fun slopeY(x: Float): Float = if (x <= 36f) 4.35f else 4.35f - (x - 36f) / 7.5f * 1.75f
    b.prism(pts(stx0, 0.5f, stx1, 0.5f, stx1, 2.6f, 36f, 4.35f, stx0, 4.35f), 1.5f, FRONT_Z, 6)
    var seamX = stx0 + 2.7f
    while (seamX < stx1 - 1f) {                                                                    // panel seams of the façade blocks
        b.box(seamX, (0.55f + slopeY(seamX)) / 2f, FRONT_Z + 0.01f, 0.055f, slopeY(seamX) - 0.6f, 0.025f, 3, flatColor = true, shade = 0.8f)
        seamX += 2.7f
    }
    /* Central panel of the façade (the present-day carving): two antithetic
       groups of four Persian/Median guards at attention around a blank space,
       the winged disk hovering above, flanked by two seated sphinxes. */
    b.box(16f, 2.45f, FRONT_Z + 0.012f, 6.6f, 3.5f, 0.025f, 4, flatColor = true, shade = 0.82f)
    b.box(12.72f, 2.45f, FRONT_Z + 0.02f, 0.1f, 3.5f, 0.03f, 3, flatColor = true)
    b.box(19.28f, 2.45f, FRONT_Z + 0.02f, 0.1f, 3.5f, 0.03f, 3, flatColor = true)
    wingedDisk(b, 16f, 3.66f, FRONT_Z + 0.035f, 1.15f)
    sphinx(b, 13.95f, 2.58f, FRONT_Z + 0.035f, 0.8f, 1f)
    sphinx(b, 18.05f, 2.58f, FRONT_Z + 0.035f, 0.8f, -1f)
    for (i in 0 until 4) {
        reliefFigure(b, 13.35f + i * 0.68f, 0.78f, FRONT_Z + 0.035f, 1.72f, 1f, if (i % 2 != 0) 3 else 2)    // left guards, facing centre
        reliefFigure(b, 18.65f - i * 0.68f, 0.78f, FRONT_Z + 0.035f, 1.72f, -1f, if (i % 2 != 0) 2 else 3)   // right guards, facing centre
    }
    val regY = floatArrayOf(0.78f, 1.74f, 2.86f)                                // three relief registers
    val regH = floatArrayOf(0.8f, 0.95f, 0.8f)
    for (reg in 0 until 3) {
        val ry = regY[reg]; val rh = regH[reg]
        var rx0 = stx1 - 8.5f
        while (rx0 > stx0 + 1f && ry + rh < slopeY(rx0 - 1f) - 0.3f) rx0 -= 1f
        rx0 = max(rx0, 19.9f)                                                     // registers begin east of the central panel
        b.box((rx0 + stx1 - 8.5f) / 2f, ry + rh / 2f, FRONT_Z + 0.012f, max(1f, stx1 - 8.5f - rx0), rh + 0.2f, 0.025f, 4, flatColor = true, shade = 0.8f)
        b.box((stx0 + stx1) / 2f, ry - 0.08f, FRONT_Z + 0.015f, stx1 - stx0, 0.08f, 0.03f, 3, flatColor = true)
        var gx = rx0 + 0.7f; var idx = 0
        while (gx < stx1 - 9.4f) {
            when {
                idx < 3 -> { reliefFigure(b, gx, ry, FRONT_Z + 0.03f, rh, 1f, if (idx % 2 != 0) 3 else 2); gx += 1.12f }                                    // guard file heading the register
                reg == 2 && idx == 4 -> { reliefChariot(b, gx + 0.7f, ry + 0.04f, FRONT_Z + 0.03f, rh * 0.95f); gx += 4.4f }                                 // royal stable register (top)
                reg == 2 && idx % 4 == 3 -> { reliefAnimal(b, gx + 0.75f, ry + 0.05f, FRONT_Z + 0.03f, rh * 0.92f, 0, 1f); gx += 2.6f }                     // king's horses
                reg == 1 && idx % 3 == 2 -> { reliefAnimal(b, gx + 0.75f, ry + 0.05f, FRONT_Z + 0.03f, rh * 0.92f, (idx / 3) % 3, 1f); gx += 2.7f }
                reg == 0 && idx % 5 == 4 -> { reliefAnimal(b, gx + 0.65f, ry + 0.05f, FRONT_Z + 0.03f, rh * 0.9f, (idx + reg) % 3, 1f); gx += 2.5f }
                else -> { reliefFigure(b, gx, ry, FRONT_Z + 0.03f, rh, 1f, idx % 2); gx += 1.5f }
            }
            if (idx % 6 == 5 && ry + rh < slopeY(gx) - 0.3f)                                       // cypress between delegations
                b.relief(pts(gx - 0.18f, ry, gx + 0.18f, ry, gx, ry + rh), FRONT_Z + 0.03f, 0.07f, 5)
            idx++
        }
    }
    for (bandY in floatArrayOf(1.66f, 2.78f)) {                                   // 12-petal rosette bands separating the registers
        var bx = 20.2f
        while (bx < 34.8f) { rosetteBand(b, bx, bandY, FRONT_Z + 0.03f, 0.085f); bx += 0.62f }
    }
    var rosX = stx0 + 1.6f
    while (rosX < stx1 - 1.2f) { rosette(b, rosX, slopeY(rosX) - 0.3f, FRONT_Z + 0.03f, 0.16f); rosX += 1.55f }   // rosette border along the top edge
    for (pxp in floatArrayOf(21.0f, 33.6f)) {                                     // XPb inscription panels at the façade ends
        b.box(pxp, 3.98f, FRONT_Z + 0.014f, 1.7f, 0.62f, 0.03f, 2, flatColor = true, shade = 0.9f)
        inscription(b, pxp, 4.2f, FRONT_Z + 0.035f, 1.45f, 3)
    }
    // Lion-attacking-bull end panel, built from convex relief pieces
    val lz = FRONT_Z + 0.03f
    b.relief(pts(35.85f, 0.95f, 42.75f, 0.95f, 42.75f, 2.55f, 35.85f, 2.75f), FRONT_Z + 0.012f, 0.03f, 6)   // panel ground
    b.relief(pts(36.1f, 1.5f, 38.2f, 1.5f, 38.35f, 2.02f, 36.4f, 2.08f), lz, 0.085f, 4)                      // lion body
    b.relief(pts(37.95f, 1.62f, 38.75f, 1.6f, 38.85f, 2.35f, 38.0f, 2.32f), lz, 0.075f, 4)                   // lion mane
    b.relief(pts(38.45f, 1.78f, 39.15f, 1.84f, 39.05f, 2.2f, 38.5f, 2.16f), lz + 0.03f, 0.08f, 4)            // lion head, jaws at the bull
    b.relief(pts(36.35f, 1.02f, 36.62f, 1.02f, 36.62f, 1.52f, 36.35f, 1.52f), lz, 0.08f, 4)                  // legs
    b.relief(pts(37.8f, 1.02f, 38.07f, 1.02f, 38.07f, 1.52f, 37.8f, 1.52f), lz, 0.08f, 4)
    b.relief(pts(38.35f, 1.3f, 39.05f, 1.18f, 39.1f, 1.32f, 38.45f, 1.46f), lz, 0.08f, 4)                    // paw raking the bull
    b.relief(pts(35.55f, 1.95f, 36.15f, 2.08f, 36.2f, 1.9f, 35.62f, 1.78f), lz, 0.07f, 4)                    // tail
    b.relief(pts(39.35f, 1.52f, 41.5f, 1.52f, 41.65f, 2.1f, 39.6f, 2.14f), lz, 0.085f, 4)                    // bull body
    b.relief(pts(41.5f, 1.9f, 42.25f, 2.0f, 42.3f, 2.5f, 41.6f, 2.42f), lz, 0.09f, 4)                        // bull head, turned back
    b.relief(pts(42.22f, 2.42f, 42.62f, 2.66f, 42.72f, 2.56f, 42.36f, 2.34f), lz, 0.07f, 4)                  // horn
    b.relief(pts(39.65f, 1.04f, 39.92f, 1.04f, 39.92f, 1.54f, 39.65f, 1.54f), lz, 0.08f, 4)                  // legs
    b.relief(pts(41.05f, 1.04f, 41.32f, 1.04f, 41.32f, 1.54f, 41.05f, 1.54f), lz, 0.08f, 4)
    palmTree(b, 35.35f, 0.95f, FRONT_Z + 0.03f, 1.9f)                                                // date palms framing the panel
    palmTree(b, 43.0f, 0.95f, FRONT_Z + 0.03f, 1.5f)
    for (cx in floatArrayOf(36.3f, 37.5f, 38.7f))                                                    // terraced cypress row above it
        b.relief(pts(cx - 0.16f, 2.82f, cx + 0.16f, 2.82f, cx, 3.4f), FRONT_Z + 0.03f, 0.07f, 5)
    for (i in 0 until 6) {                                                                                 // broad treads on the sloping cheek
        val tx = 36.6f + i * 1.35f; val ty = slopeY(tx)
        b.box(tx, ty - 0.02f, 4.2f, 1.3f, 0.16f, 5.4f, 6)
        b.box(tx - 0.62f, ty - 0.2f, 4.2f, 0.12f, 0.34f, 5.4f, 6)
    }
    var mx = stx0 + 0.8f; var mi = 0
    while (mx < stx1 - 0.5f) {                                                                             // four-stepped crenellation merlons (some lost)
        if (mi % 7 != 5) {
            val y = slopeY(mx)
            val broken = if (mi % 4 == 2) 0.55f else 1f
            b.box(mx, y + 0.2f, 6.75f, 1.95f, 0.4f, 0.75f, 1)
            b.box(mx, y + 0.56f, 6.75f, 1.5f, 0.34f, 0.75f, 1)
            b.box(mx, y + 0.88f * broken, 6.75f, 1.05f, 0.3f * broken + 0.08f, 0.75f, 1)
            if (broken == 1f) b.box(mx, y + 1.16f, 6.75f, 0.6f, 0.28f, 0.75f, 1)
        }
        mx += 3.15f; mi++
    }

    /* ---- back walls, the right-hand back building, angle piers ---- */
    ruinedWall(b, -38f, -14f, PLATFORM_Y, PLATFORM_Y + 2.9f, -11.6f, 1.2f, 1)
    ruinedWall(b, -9f, 13f, PLATFORM_Y, PLATFORM_Y + 2.3f, -12.1f, 1.2f, 1)
    ruinedWall(b, -36f, -13f, PLATFORM_Y, PLATFORM_Y + 1.55f, -2.6f, 0.9f, 1)                              // low connecting walls
    ruinedWall(b, -10f, 8f, PLATFORM_Y, PLATFORM_Y + 1.25f, -3.2f, 0.9f, 1)
    // Rectangular back building behind the tall columns (photo right):
    // corniced wall slab pierced by three small dark openings
    ashlar(b, 27f, 42.5f, PLATFORM_Y, PLATFORM_Y + 3.7f, -11.7f, 1.3f, 2.6f, 0.85f, 1)
    b.box(34.75f, PLATFORM_Y + 3.88f, -12.3f, 16.2f, 0.36f, 1.7f, 1)
    for (ox in floatArrayOf(29.6f, 34.6f, 39.8f)) {
        b.box(ox, PLATFORM_Y + 1.35f, -11.62f, 1.5f, 1.9f, 0.14f, 3, flatColor = true)
        b.box(ox - 0.95f, PLATFORM_Y + 1.35f, -11.58f, 0.3f, 2.1f, 0.12f, 2)
        b.box(ox + 0.95f, PLATFORM_Y + 1.35f, -11.58f, 0.3f, 2.1f, 0.12f, 2)
        b.box(ox, PLATFORM_Y + 2.5f, -11.58f, 2.2f, 0.34f, 0.12f, 2)
    }
    inscription(b, 34.75f, PLATFORM_Y + 3.25f, -11.6f, 9f, 1)
    for (px in floatArrayOf(-16.5f, 12.8f)) {                                                              // serrated angle piers (Tachara type)
        b.box(px, PLATFORM_Y + 3.4f, -6.2f, 1.7f, 6.8f, 1.7f, 2)
        b.box(px, PLATFORM_Y + 3.4f, -5.3f, 1.2f, 6.8f, 0.25f, 2)                                          // front pilaster strip
        inscription(b, px, PLATFORM_Y + 5.6f, -5.16f, 0.9f, 4)
        for (i in 0 until 4) b.box(px, PLATFORM_Y + 6.95f + i * 0.22f, -6.2f, 1.5f - i * 0.28f, 0.2f, 1.5f - i * 0.28f, 2)
    }

    /* ---- door / window frames and lone jambs ---- */
    for (f in FRAMES) frame(b, f.x, f.z, f.w, f.h, f.openingW, f.mode, f.breakRight, f.baseY)
    for (j in JAMBS) {                                                                                     // lone jambs, jagged tops, banded faces
        b.box(j[0], PLATFORM_Y + j[3] / 2f, j[1], j[2], j[3], 1.8f, 2)
        b.box(j[0] - j[2] / 2f + 0.12f, PLATFORM_Y + j[3] / 2f, j[1] + 0.92f, 0.16f, j[3], 0.08f, 1)
        b.box(j[0] + j[2] / 2f - 0.12f, PLATFORM_Y + j[3] / 2f, j[1] + 0.92f, 0.16f, j[3], 0.08f, 1)
        inscription(b, j[0], PLATFORM_Y + j[3] - 0.6f, j[1] + 0.92f, min(0.95f, j[2] - 0.3f), 3)
        if (j[3] > 5.4f) reliefFigure(b, j[0], PLATFORM_Y + 0.4f, j[1] + 0.93f, 2.1f, 1f, 1)
        val jx = j[0] + b.rr(-0.2f, 0.2f)
        val tilt = b.rr(-0.05f, 0.05f)
        b.box(jx, PLATFORM_Y + j[3] + 0.14f, j[1], j[2] * 0.72f, 0.3f, 1.4f, 2, rz = tilt)
        b.box(j[0], PLATFORM_Y + 0.13f, j[1] + 0.2f, j[2] + 0.44f, 0.26f, 2.3f, 1)
    }

    /* ---- columns of the east portico (two rows of six in reality) ---- */
    for (c in COLUMNS) column(b, c.x, c.z, c.shaftH, c.capMode, 0.80f, c.capH)                             // Ø 1.60 m

    /* ---- rubble: the dense debris field along the platform edge ---- */
    val drums = arrayOf(
        floatArrayOf(-24.5f, 2.6f, 0.4f, 2.2f), floatArrayOf(-8f, 0.5f, 1.2f, 2.6f),
        floatArrayOf(-4.5f, -3.0f, 2.8f, 1.9f), floatArrayOf(7.5f, 3.2f, 0.9f, 2.4f),
        floatArrayOf(18f, -1.5f, 2.2f, 2.0f), floatArrayOf(31f, 2.8f, 1.7f, 2.5f),
        floatArrayOf(38f, -4.0f, 0.2f, 1.8f), floatArrayOf(-31f, 1.0f, 2.5f, 2.1f),
    )
    for (dr in drums) {   // fallen FLUTED drums, flutes and all
        val ax = floatArrayOf(cos(dr[2]), 0.05f, sin(dr[2]))
        val la = sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2])
        b.cylinder(dr[0], PLATFORM_Y + 0.78f, dr[1], 0.78f, dr[3], ax, 96, 7, flutes = 48)
        // central dowel hole on the exposed drum end (drums were pinned, not mortared)
        b.cylinder(dr[0] + ax[0] / la * (dr[3] / 2f), PLATFORM_Y + 0.78f + ax[1] / la * (dr[3] / 2f), dr[1] + ax[2] / la * (dr[3] / 2f), 0.09f, 0.06f, ax, 10, 3)
    }
    // The great slanted carved slab, centre-right of the photo
    b.box(6.2f, PLATFORM_Y + 0.8f, 2.9f, 5.4f, 0.85f, 2.1f, 1, ry = 0.1f, rz = -0.16f)
    inscription(b, 6.2f, PLATFORM_Y + 1.15f, 3.98f, 3.6f, 2)
    reliefAnimal(b, 4.6f, PLATFORM_Y + 0.35f, 3.99f, 0.85f, 2, 1f)
    // Fallen capital fragments: volute blocks and a protome head
    for (cf in arrayOf(floatArrayOf(-13.5f, 2.8f), floatArrayOf(13.8f, 3.2f), floatArrayOf(24.5f, -2.5f), floatArrayOf(-36f, 2.9f))) {
        val yaw = b.rr(0f, 3f); val tilt = b.rr(-0.1f, 0.1f)
        b.box(cf[0], PLATFORM_Y + 0.65f, cf[1], 1.5f, 1.3f, 1.5f, 7, ry = yaw, rz = tilt)
        b.cylinder(cf[0] + 0.5f, PLATFORM_Y + 0.75f, cf[1] + 0.78f, 0.24f, 0.12f, floatArrayOf(0f, 0f, 1f), 14, 7)
        b.cylinder(cf[0] - 0.45f, PLATFORM_Y + 0.5f, cf[1] + 0.78f, 0.24f, 0.12f, floatArrayOf(0f, 0f, 1f), 14, 7)
    }
    b.box(11.6f, PLATFORM_Y + 0.55f, 3.6f, 1.7f, 1.0f, 1.1f, 7, ry = 0.5f, rz = 0.35f)                     // protome head on its side
    b.box(12.4f, PLATFORM_Y + 0.4f, 3.95f, 0.55f, 0.5f, 0.5f, 7, ry = 0.5f)
    val piles = floatArrayOf(-37f, -28f, -20f, -12f, -3f, 5f, 13f, 21f, 29f, 37f)
    for (i in 0 until 165) {
        val px = piles[i % piles.size] + b.rr(-3.6f, 3.6f)
        val z = b.rr(-4.5f, 4.0f)
        val s = b.rr(0.3f, 1.25f)
        val layer = if (i % 5 == 0) 1f else 0f
        val sw = s * b.rr(0.8f, 1.8f)
        val yaw = b.rr(0f, 3.14f); val tilt = b.rr(-0.14f, 0.14f)
        b.box(px, PLATFORM_Y + s * 0.3f + layer * 0.55f, z, sw, s * 0.6f, s, if (i % 3 != 0) 5 else 1, ry = yaw, rz = tilt)
    }
    for (i in 0 until 30) {                                                                                // forecourt debris
        val x = b.rr(-42f, 42f)
        val s = b.rr(0.3f, 1.0f)
        val z = b.rr(8.2f, 9.4f)
        val yaw = b.rr(0f, 3.14f)
        b.box(x, 0.5f + s * 0.3f, z, s * 1.4f, s * 0.6f, s, 5, ry = yaw)
    }
    // Wind-blown sand drifts banked against the walls (semi-desert site):
    // [x, z, groundY, length] — along the platform toe, the back walls, frames.
    val drifts = arrayOf(
        floatArrayOf(-30f, 7.75f, 0.5f, 7f), floatArrayOf(-12f, 7.7f, 0.5f, 6f), floatArrayOf(4f, 7.75f, 0.5f, 8f),
        floatArrayOf(16f, 7.7f, 0.5f, 5f), floatArrayOf(28f, 7.75f, 0.5f, 7f), floatArrayOf(40f, 7.7f, 0.5f, 6f),
        floatArrayOf(-24f, 5.2f, PLATFORM_Y, 5f), floatArrayOf(-2f, -2.0f, PLATFORM_Y, 6f),
        floatArrayOf(20f, -10.9f, PLATFORM_Y, 7f), floatArrayOf(-32f, -10.8f, PLATFORM_Y, 6f),
        floatArrayOf(8f, 1.7f, PLATFORM_Y, 4f), floatArrayOf(35f, -2.5f, PLATFORM_Y, 5f),
    )
    for (df in drifts) {
        val dx = df[0] + b.rr(-0.6f, 0.6f)
        val dz = df[1] + b.rr(-0.2f, 0.2f)
        val len = df[3] * b.rr(0.8f, 1.2f)
        val dep = b.rr(0.6f, 1.0f)
        val yaw = b.rr(-0.09f, 0.09f)
        b.box(dx, df[2] + 0.05f, dz, len, 0.14f, dep, 8, ry = yaw)
    }
    return b
}


// ============================================================================
// Combined-scene helpers: extract a builder range subset, then bake a
// scale / Y-rotation / translation into a built mesh (Mil-e Azhdaha group).
// ============================================================================

private const val MIL_GROUP_SCALE = 2.04f
private const val MIL_GROUP_ROTY = -0.20f
private const val MIL_GROUP_X = 40.7f
private const val MIL_HOME_RIGHT_SHIFT_M = 66f   // ZIG change 7: tower to the far right; Persepolis unchanged
private const val MIL_GROUP_Y = 1.1f
private const val MIL_GROUP_Z = 15.6f
private const val PERSEPOLIS_X = -9.7f   // combined scene: Persepolis pushed to the far left of the frame

private fun extractRanges(b: MeshBuilder, ranges: Array<IntArray>): MeshBuilder {
    val o = MeshBuilder()
    val bp = b.pos.data; val bn = b.nor.data; val bc = b.col.data; val bi = b.idx.data
    for (r in ranges) {
        val base = o.pos.size / 3
        for (v in r[0] until r[2]) for (c in 0..2) {
            o.pos.add(bp[3 * v + c]); o.nor.add(bn[3 * v + c]); o.col.add(bc[3 * v + c])
        }
        for (i in r[1] until r[3]) o.idx.add(bi[i] - r[0] + base)
    }
    return o
}

private fun transformMesh(b: MeshBuilder, s: Float, ry: Float, tx: Float, ty: Float, tz: Float): MeshBuilder {
    val c = cos(ry); val sn = sin(ry)
    val p = b.pos.data; val n = b.nor.data
    var i = 0
    while (i < b.pos.size) {
        val x = p[i] * s; val y = p[i + 1] * s; val z = p[i + 2] * s
        p[i] = x * c + z * sn + tx; p[i + 1] = y + ty; p[i + 2] = -x * sn + z * c + tz
        val nx = n[i]; val nz = n[i + 2]
        n[i] = nx * c + nz * sn; n[i + 2] = -nx * sn + nz * c
        i += 3
    }
    return b
}

// ============================================================================
// Mil-e Azhdaha (Nurabad tower) — builders copied unchanged from
// MilAzhdaha.kt, except that buildMilLocalScene() returns only the tower +
// its local foot group (see the extractRanges call at its end). The group is
// then placed in the Persepolis forecourt by transformMesh() above.
// ============================================================================

private const val MIL_T = 0.62f                       // wall thickness
private val MIL_FR: Array<FloatArray> = arrayOf(      // front/back block-joint patterns (rotate by course)
    floatArrayOf(0f, 0.20f, 0.37f, 0.58f, 0.74f, 0.89f, 1f),
    floatArrayOf(0f, 0.13f, 0.33f, 0.50f, 0.69f, 0.84f, 1f),
    floatArrayOf(0f, 0.24f, 0.41f, 0.63f, 0.79f, 1f),
    floatArrayOf(0f, 0.16f, 0.30f, 0.52f, 0.70f, 0.87f, 1f),
)
private val MIL_SD: Array<FloatArray> = arrayOf(      // side-face joint patterns
    floatArrayOf(0f, 0.25f, 0.47f, 0.66f, 0.83f, 1f),
    floatArrayOf(0f, 0.18f, 0.40f, 0.60f, 0.80f, 1f),
)
private val MIL_F9: Array<FloatArray> = arrayOf(      // front-face joint fractions per shaft course, measured on photos (ci 0 = bottom; ci 6/7 unused — door courses below)
    floatArrayOf(0f, 0.27f, 0.55f, 0.79f, 1f),
    floatArrayOf(0f, 0.24f, 0.48f, 0.71f, 0.87f, 1f),
    floatArrayOf(0f, 0.19f, 0.36f, 0.60f, 0.80f, 1f),
    floatArrayOf(0f, 0.25f, 0.38f, 0.56f, 0.68f, 0.87f, 1f),
    floatArrayOf(0f, 0.20f, 0.43f, 0.61f, 0.78f, 0.91f, 1f),
    floatArrayOf(0f, 0.14f, 0.34f, 0.57f, 0.77f, 0.90f, 1f),
    floatArrayOf(),
    floatArrayOf(),
    floatArrayOf(0f, 0.13f, 0.35f, 0.83f, 1f),        // ci 8 = top course, wide block over door
)
private const val MIL_DCX = 0.20f                     // door centre (south face, offset toward the west corner)
private const val MIL_DW = 0.62f                      // door width

/** One masonry course: four faces of individual blocks (fi: 0 = front/south,
 *  1 = back, 2 = west, 3 = east), each block carrying a proud dressed face
 *  slab with its own tone, over a darker block body so joints read as shadow. */
private fun milCourse(b: MeshBuilder, yb: Float, hh: Float, w: Float, ci: Int) {
    val jit = if (ci == 0) 0.034f else if (ci < 3) 0.026f else 0.011f   // lower courses laid rougher (photos)
    val gp = if (ci == 0) 0.040f else if (ci < 3) 0.030f else 0.018f    // mortar gap: hairline joints up top
    val frP = MIL_FR[ci % 4]
    val sdP = MIL_SD[ci % 2]
    fun segs(fr: FloatArray): Array<FloatArray> {
        val o = ArrayList<FloatArray>()
        for (i in 0 until fr.size - 1) o.add(floatArrayOf(fr[i], fr[i + 1]))
        return o.toTypedArray()
    }
    val dx0 = MIL_DCX - MIL_DW / 2f
    val dx1 = MIL_DCX + MIL_DW / 2f
    val front: Array<FloatArray> = when {
        ci == 6 -> arrayOf(                           // door courses — doorway left open, joints from photos
            floatArrayOf(0f, 0.12f), floatArrayOf(0.12f, 0.31f),
            floatArrayOf(0.31f, dx0 / w + 0.5f), floatArrayOf(dx1 / w + 0.5f, 0.81f),
            floatArrayOf(0.81f, 1f),
        )
        ci == 7 -> arrayOf(
            floatArrayOf(0f, 0.12f), floatArrayOf(0.12f, 0.33f),
            floatArrayOf(0.33f, dx0 / w + 0.5f), floatArrayOf(dx1 / w + 0.5f, 0.82f),
            floatArrayOf(0.82f, 1f),
        )
        ci in 0..8 -> segs(MIL_F9[ci])                // shaft front: transcribed joints
        ci == 20 -> segs(floatArrayOf(0f, 0.30f, 0.64f, 1f))   // socle: long bottom slabs
        ci == 22 -> segs(floatArrayOf(0f, 0.22f, 0.47f, 0.57f, 0.79f, 0.89f, 1f))   // socle top course, from photos
        else -> segs(frP)
    }
    val rows: Array<Array<FloatArray>> = arrayOf(front, segs(frP), segs(sdP), segs(sdP))
    for (fi in 0..3) {
        val ss = rows[fi]
        for (si in ss.indices) {
            if (fi == 2 && ci == 7 && si == 1) continue   // small square hole, west face (corner photos)
            val pal = when {   // stone warms/darkens downward (photos)
                ci == 20 -> if (si % 2 != 0) 0 else 1
                ci == 21 -> if (si % 2 != 0) 4 else 0
                ci == 22 -> 4
                ci == 0 -> if (si % 3 == 0) 5 else 7
                ci == 1 -> if ((si + ci) % 2 != 0) 7 else 1
                ci <= 3 -> if ((si + ci) % 2 != 0) 0 else 1
                ci <= 5 -> 0
                else -> 4
            }
            val spalled = (ci < 4 && ((ci * 11 + si * 5 + fi * 3) % 7 == 0)) ||
                (fi == 0 && ((ci == 3 && si == 2) || (ci == 6 && si == 0) || (ci == 7 && si == 0)))   // eroded blocks seen in photos
            val j1 = b.rr(-jit, jit)
            val j2 = b.rr(-jit, jit)
            val blockShade = b.rr(0.74f, 0.90f)           // darker block body -> joints read as shadow
            if (fi == 0 || fi == 1) {
                val fz = if (fi == 0) 1f else -1f
                val a = (ss[si][0] - 0.5f) * w
                val bb = (ss[si][1] - 0.5f) * w
                val lz = fz * (w / 2f - MIL_T / 2f)
                val bx = (a + bb) / 2f + j1
                val len = (bb - a) - gp
                b.box(bx, yb + hh / 2f, lz + j2, len, hh - gp, MIL_T, pal, shade = blockShade)
                if (spalled) {
                    b.box(
                        bx + (if ((ci + si) % 2 != 0) 0.07f else -0.07f), yb + hh / 2f,
                        fz * (w / 2f) + (if (fz > 0f) 0.006f else -0.006f),
                        len * 0.3f, hh * 0.36f, 0.03f, 9, flatColor = true, shade = 0.7f,
                    )
                } else {
                    val pr = if (ci < 3) b.rr(-0.008f, 0.034f) else b.rr(0.0f, 0.012f)
                    val spal = if (b.rr(0f, 1f) < 0.18f) (when (pal) { 4 -> 0; 0 -> 4; 7 -> 1; 5 -> 7; else -> 0 }) else pal
                    val si2 = b.rr(0.012f, 0.045f)
                    val scx = bx + b.rr(-0.012f, 0.012f)
                    val scy = yb + hh / 2f + j2 + b.rr(-0.012f, 0.012f)
                    val sfz = fz * (w / 2f + 0.010f + pr)
                    val ssh = b.rr(0.88f, 1.08f)
                    val srz = b.rr(-0.008f, 0.008f)
                    b.box(scx, scy, sfz, len - si2, hh - si2, 0.025f, spal, shade = ssh, rz = srz)   // dressed face, own tone
                    if (ci == 0 || ci == 20)
                        b.box(scx, scy, sfz + fz * 0.032f, (len - si2) * 0.55f, (hh - si2) * 0.60f, 0.04f, spal, shade = 1.04f)   // rusticated boss, bottom course
                    val hv = ci * 7 + si * 11 + fi * 5
                    if (hv % 4 == 0) {
                        val cn = (ci + si) % 4
                        val cw = (len - si2) * 0.20f
                        val ch = (hh - si2) * 0.24f
                        b.box(
                            scx + (if (cn == 0 || cn == 3) -1f else 1f) * ((len - si2) / 2f - cw / 2f + 0.01f),
                            scy + (if (cn < 2) 1f else -1f) * ((hh - si2) / 2f - ch / 2f + 0.01f),
                            sfz + fz * 0.016f, cw, ch, 0.02f, pal, shade = blockShade * 0.9f,
                        )   // chipped corner: body stone shows
                    }
                    if ((ci * 13 + si * 7 + fi) % 9 == 0)
                        b.box(scx, scy - (hh - si2) / 2f + 0.02f, sfz + fz * 0.014f, (len - si2) * 0.55f, 0.045f, 0.018f, 4, flatColor = true, shade = 1.12f)   // gypsum mortar squeeze-out
                }
            } else {
                val fx = if (fi == 2) -1f else 1f
                val iw = w - 2f * MIL_T
                val a = (ss[si][0] - 0.5f) * iw
                val bb = (ss[si][1] - 0.5f) * iw
                val lx = fx * (w / 2f - MIL_T / 2f)
                val bz = (a + bb) / 2f + j2
                val len = (bb - a) - gp
                b.box(lx + j1, yb + hh / 2f, bz, MIL_T, hh - gp, len, pal, shade = blockShade)
                if (spalled) {
                    b.box(
                        fx * (w / 2f) + (if (fx > 0f) 0.006f else -0.006f), yb + hh / 2f,
                        bz + (if ((ci + si) % 2 != 0) 0.07f else -0.07f),
                        0.03f, hh * 0.36f, len * 0.3f, 9, flatColor = true, shade = 0.7f,
                    )
                } else {
                    val pr = if (ci < 3) b.rr(-0.008f, 0.034f) else b.rr(0.0f, 0.012f)
                    val spal = if (b.rr(0f, 1f) < 0.18f) (when (pal) { 4 -> 0; 0 -> 4; 7 -> 1; 5 -> 7; else -> 0 }) else pal
                    val si2 = b.rr(0.012f, 0.045f)
                    val scy = yb + hh / 2f + j1 + b.rr(-0.012f, 0.012f)
                    val sfx = fx * (w / 2f + 0.004f + pr * 0.4f)
                    val ssh = b.rr(0.88f, 1.08f)
                    val srz = b.rr(-0.008f, 0.008f)
                    b.box(sfx, scy, bz, 0.025f, hh - si2, len - si2, spal, shade = ssh, rz = srz)
                    if (ci == 0 || ci == 20)
                        b.box(sfx + fx * 0.032f, scy, bz, 0.04f, (hh - si2) * 0.60f, (len - si2) * 0.55f, spal, shade = 1.04f)   // rusticated boss
                    val hv = ci * 7 + si * 11 + fi * 5
                    if (hv % 4 == 0) {
                        val cn = (ci + si) % 4
                        val cw = (len - si2) * 0.20f
                        val ch = (hh - si2) * 0.24f
                        b.box(
                            sfx + fx * 0.016f,
                            scy + (if (cn < 2) 1f else -1f) * ((hh - si2) / 2f - ch / 2f + 0.01f),
                            bz + (if (cn == 0 || cn == 3) -1f else 1f) * ((len - si2) / 2f - cw / 2f + 0.01f),
                            0.02f, ch, cw, pal, shade = blockShade * 0.9f,
                        )   // chipped corner
                    }
                    if ((ci * 13 + si * 7 + fi) % 9 == 0)
                        b.box(sfx + fx * 0.014f, scy - (hh - si2) / 2f + 0.02f, bz, 0.018f, 0.045f, (len - si2) * 0.55f, 4, flatColor = true, shade = 1.12f)   // mortar squeeze-out
                }
            }
        }
    }
}

/** The tower itself: socle, nine shaft courses, doorway + internal stair,
 *  weather streaks and cracks, and the ruined top from the photographs. */
private fun milTower(b: MeshBuilder) {
    val sBase = 1.00f                                        // top of socle
    val courseH = floatArrayOf(0.46f, 0.50f, 0.46f, 0.60f, 0.48f, 0.56f, 0.54f, 0.56f, 0.54f)   // 9 courses, heights measured on photos
    val shaftH = courseH.sum()                               // 4.70 m
    // Photo-measured width profile: only the first two shaft courses step
    // in; from the third shaft course upward the square side is constant.
    fun shaftW(ci: Int): Float = if (ci == 0) 3.92f else if (ci == 1) 3.86f else 3.80f
    fun widthAt(y: Float): Float =
        if (y < sBase + courseH[0]) shaftW(0) else if (y < sBase + courseH[0] + courseH[1]) shaftW(1) else shaftW(2)
    // socle: three stepped block courses over solid cores (Roman Ghirshman)
    b.box(0f, 0.13f, 0f, 3.6f, 0.26f, 3.6f, 1, shade = 0.68f)
    b.box(0f, 0.41f, 0f, 3.3f, 0.30f, 3.3f, 1, shade = 0.68f)
    b.box(0f, 0.78f, 0f, 3.05f, 0.44f, 3.05f, 1, shade = 0.68f)
    milCourse(b, 0.00f, 0.26f, 4.85f, 20)
    milCourse(b, 0.26f, 0.30f, 4.55f, 21)
    milCourse(b, 0.56f, 0.44f, 4.30f, 22)
    // solid lower core (tower is solid below the stair) + dark upper well
    b.box(0f, sBase + 1.35f, 0f, 2.95f, 2.7f, 2.95f, 1, shade = 0.6f)
    b.box(0f, sBase + 3.35f, -0.28f, 2.65f, 1.4f, 2.35f, 1, shade = 0.55f)
    b.box(0f, sBase + shaftH - 0.35f, -0.61f, 2.46f, 0.5f, 1.29f, 1, shade = 0.7f)   // chamber floor remnant under the ruin
    // shaft courses
    var yb = sBase
    var sillY = 0f
    for (ci in courseH.indices) {
        if (ci == 6) sillY = yb
        milCourse(b, yb, courseH[ci], shaftW(ci), ci)
        yb += courseH[ci]
    }
    val topY = yb
    // door furniture: proud sill, stair flight inside ascending along the east wall
    b.box(MIL_DCX, sillY + 0.02f, widthAt(sillY) / 2f - MIL_T / 2f + 0.05f, 0.85f, 0.11f, 0.75f, 4, shade = 0.75f)
    b.box(MIL_DCX, sillY + 0.51f, widthAt(sillY) / 2f - MIL_T + 0.02f, 0.66f, 1.06f, 0.06f, 3, shade = 0.3f)   // unlit doorway depth
    for (k in 0 until 7)
        b.box(0.88f, sillY + 0.10f + k * 0.235f, widthAt(sillY) / 2f - MIL_T - 0.30f - k * 0.30f, 0.60f, 0.11f, 0.34f, 1, shade = 0.45f)
    b.box(MIL_DCX, sillY - 0.34f, widthAt(sillY - 0.34f) / 2f + 0.012f, 0.05f, 0.6f, 0.03f, 1, flatColor = true, shade = 0.92f)   // faint streak below door
    for (k in 0 until 2) {                                   // rain streaks from the top course, dashed, kept clear of the door
        val sx = if (k == 0) b.rr(-1.5f, -0.7f) else b.rr(0.75f, 1.5f)
        val sh = b.rr(0.8f, 1.5f)
        for (d in 0 until 3) {
            val dl = sh / 3f
            val dcy = topY - dl * (d + 0.5f)
            val dx = sx + b.rr(-0.008f, 0.008f)
            b.box(dx, dcy, widthAt(dcy) / 2f + 0.012f, 0.032f, dl - 0.03f, 0.03f, 1, flatColor = true, shade = 0.93f - d * 0.04f)
        }
    }
    for (k in 0 until 3)                                     // stepped vertical cracks (widened joints in photos)
        b.box(-1.02f + k * 0.05f, sBase + 0.55f + k * 0.70f, widthAt(sBase + 0.55f + k * 0.70f) / 2f + 0.01f, 0.035f, 0.76f, 0.025f, 3, flatColor = true, shade = 0.8f, rz = 0.02f)
    for (k in 0 until 2)
        b.box(1.38f - k * 0.04f, sBase + 1.05f + k * 0.70f, widthAt(sBase + 1.05f + k * 0.70f) / 2f + 0.01f, 0.035f, 0.75f, 0.025f, 3, flatColor = true, shade = 0.8f, rz = -0.015f)
    // Ruined top, transcribed block-by-block from the front photograph:
    // the surviving front wall of the top chamber, shifted east of centre
    // over the stairwell — chamber mouth with a fallen slab, lintel tier,
    // wide slot window, two-block cap — plus the SE corner tooth and the
    // rubble slope down the west wall head. The transcribed plan
    // coordinates below were normalised to a 3.40 m top; ruinW scales
    // those plan coordinates/sizes onto the photo-measured constant
    // 3.80 m shaft (heights unchanged). dy = height above the top course.
    val ruinW = 3.80f / 3.40f
    fun r(
        lx: Float, dy: Float, lz: Float, sx: Float, sy: Float, sz: Float, pal: Int,
        flatColor: Boolean = false, shade: Float = 1f, rz: Float = 0f,
    ) {
        b.box(lx * ruinW, topY + dy, lz * ruinW, sx * ruinW, sy, sz * ruinW, pal, flatColor = flatColor, shade = shade, rz = rz)
    }
    fun rf(   // front-wall block + dressed face slab
        lx: Float, dy: Float, lz: Float, sx: Float, sy: Float, sz: Float, pal: Int,
        rz: Float = 0f,
    ) {
        r(lx, dy, lz, sx, sy, sz, pal, rz = rz)
        val ssh = b.rr(0.95f, 1.05f)
        b.box(lx * ruinW, topY + dy, (lz + sz / 2f) * ruinW + 0.012f, sx * ruinW - 0.05f, sy - 0.05f, 0.025f, pal, shade = ssh, rz = rz)
    }
    rf(-0.87f, 0.23f, 1.40f, 0.38f, 0.47f, 0.60f, 4)         // chamber mouth: left pier, two blocks
    rf(-0.50f, 0.23f, 1.40f, 0.37f, 0.47f, 0.60f, 4)
    rf(0.78f, 0.23f, 1.40f, 0.49f, 0.47f, 0.60f, 4)          // right pier
    r(0.11f, 0.23f, 0.82f, 0.90f, 0.48f, 0.06f, 3, shade = 0.3f)   // unlit chamber behind the mouth
    r(0.13f, 0.22f, 1.02f, 0.82f, 0.15f, 0.14f, 5, shade = 0.35f, rz = -0.38f)   // fallen diagonal slab inside, in shadow
    r(-0.49f, 0.54f, 1.12f, 0.36f, 0.22f, 0.45f, 0, shade = 0.72f, rz = 0.05f)   // set-back block over the left pier
    r(-0.68f, 0.45f, 1.05f, 0.30f, 0.18f, 0.40f, 5, shade = 0.7f, rz = -0.08f)   // rubble behind the left pier
    rf(-0.19f, 0.69f, 1.40f, 0.30f, 0.45f, 0.60f, 4)         // lintel tier over the mouth
    rf(0.29f, 0.69f, 1.40f, 0.65f, 0.45f, 0.60f, 4)
    rf(0.82f, 0.69f, 1.40f, 0.42f, 0.45f, 0.60f, 0)          // right stack, middle block
    rf(-0.17f, 1.07f, 1.40f, 0.18f, 0.31f, 0.60f, 4)         // slot window: narrow left fin
    rf(0.75f, 1.10f, 1.40f, 0.55f, 0.37f, 0.60f, 4)          // right jamb (slot 0.56 x 0.31 left open)
    rf(-0.06f, 1.45f, 1.27f, 0.36f, 0.44f, 0.85f, 0, rz = -0.03f)   // cap, left block (deep roofing slab, greyer weathered stone)
    rf(0.51f, 1.46f, 1.27f, 0.77f, 0.37f, 0.85f, 0, rz = -0.015f)  // cap, right block
    r(0.82f, 1.70f, 1.25f, 0.26f, 0.10f, 0.40f, 0, shade = 0.9f, rz = 0.08f)   // loose stones on the cap
    r(1.15f, 0.19f, 1.40f, 0.24f, 0.38f, 0.55f, 0, shade = 0.88f, rz = 0.05f)  // low rubble toward the corner
    rf(1.49f, 0.28f, 1.40f, 0.44f, 0.56f, 0.60f, 4)          // SE corner tooth
    r(1.08f, 0.66f, 1.05f, 0.22f, 0.34f, 0.40f, 5, shade = 0.7f, rz = -0.10f)   // broken east edge, set back + dark
    r(1.06f, 0.96f, 1.02f, 0.18f, 0.26f, 0.35f, 9, shade = 0.75f, rz = 0.15f)
    r(-1.34f, 0.07f, 1.42f, 0.34f, 0.14f, 0.50f, 9, shade = 0.7f, rz = 0.10f)   // dark rubble slope, west wall head (photo)
    r(-1.20f, 0.13f, 1.44f, 0.34f, 0.20f, 0.52f, 9, shade = 0.75f, rz = -0.08f)
    r(-1.08f, 0.19f, 1.42f, 0.32f, 0.22f, 0.50f, 5, shade = 0.7f, rz = 0.14f)
    r(-1.00f, 0.30f, 1.40f, 0.24f, 0.24f, 0.48f, 5, shade = 0.8f, rz = -0.10f)
    r(-1.40f, 0.08f, -0.10f, 0.55f, 0.16f, 2.20f, 0, shade = 0.9f)   // side + back wall heads, low (corner photos)
    r(-1.35f, 0.14f, -1.32f, 0.45f, 0.28f, 0.35f, 4, shade = 0.9f)   // NW corner remnant
    r(0.15f, 0.12f, -1.40f, 0.95f, 0.24f, 0.55f, 0)
    r(1.40f, 0.08f, -0.45f, 0.55f, 0.16f, 0.75f, 4, shade = 0.9f)
    r(1.42f, 0.13f, -1.35f, 0.50f, 0.26f, 0.40f, 0, shade = 0.85f)
    for (i in 0 until 4) {                                   // loose blocks on the wall head
        val lx = b.rr(-1.45f, 1.45f)
        val lz = b.rr(-1.34f, 1.01f)
        val sx = b.rr(0.39f, 0.67f)
        val sz = b.rr(0.39f, 0.61f)
        val ryv = b.rr(0f, 3f)
        val rzv = b.rr(-0.06f, 0.06f)
        val shv = b.rr(0.75f, 0.95f)
        b.box(lx, topY + 0.14f, lz, sx, 0.26f, sz, if (i % 2 != 0) 5 else 0, shade = shv, ry = ryv, rz = rzv)
    }
}

// The tower's limestone hillside (combined scene only; see
// mil-azhdaha-slope-report.md). Built in Mil local coords so it inherits
// the group's bake. The scene has no occluding ground plane (the preview
// grass is backdrop, the app is a transparent overlay), so NOTHING may
// dip below the world ground line: local y = -0.54 is world y = 0 and
// every piece bottoms out exactly there. The outcrop is therefore built
// as the bedded limestone ledges of the photographs: a near-level
// cracked-pavement bench under the tower (existing foot items stay
// seated), four steps rising to +x and exiting the frame on the right
// (scarp fronts staggered), a two-step feather dying into the forecourt
// to -x, and two shallow steps down toward the viewer in front.
private fun milSlope(b: MeshBuilder) {
    // bench + stepped sheets (fixed; bottoms all at local ground -0.54)
    b.box(0f, -0.295f, 0.65f, 13.6f, 0.49f, 12.3f, 6, shade = 0.94f)                        // bench, top -0.05
    b.box(8.0f, 0.015f, 1.65f, 2.4f, 1.11f, 14.3f, 6, shade = 0.91f)                        // upslope step 1, top 0.57
    b.box(10.4f, 0.325f, 1.2f, 2.4f, 1.73f, 13.4f, 6, shade = 0.90f)                         // step 2, top 1.19
    b.box(12.8f, 0.635f, 1.9f, 2.4f, 2.35f, 14.8f, 6, shade = 0.91f)                         // step 3, top 1.81
    b.box(15.2f, 0.945f, 1.4f, 2.4f, 2.97f, 13.8f, 6, shade = 0.90f)                         // step 4, top 2.43 (exits frame)
    b.box(-8.6f, -0.415f, 0.65f, 3.6f, 0.25f, 12.3f, 6, shade = 0.90f)                       // downslope step, top -0.29
    b.box(-11.3f, -0.49f, 1.5f, 2.2f, 0.10f, 8.0f, 6, shade = 0.87f)                         // downslope snout, top -0.44
    b.box(2.0f, -0.415f, 8.1f, 17f, 0.25f, 2.6f, 6, shade = 0.90f)                           // front step 1, top -0.29
    b.box(2.0f, -0.49f, 10.7f, 14f, 0.10f, 2.6f, 6, shade = 0.87f)                           // front step 2, top -0.44
    for (i in 0 until 10) {                                                                  // pavement slabs on the bench
        val ang = b.rr(0f, 6.28f); val rad = b.rr(3.6f, 6.1f); val w = b.rr(0.8f, 2.0f)
        val d = b.rr(0.7f, 1.7f); val ryv = b.rr(0f, 3.14f); val shv = b.rr(0.8f, 1.02f)
        b.box(cos(ang) * rad, -0.005f, sin(ang) * rad * 0.8f, w, 0.09f, d, if (i % 3 != 0) 6 else 1, shade = shv, ry = ryv)
    }
    val tops = floatArrayOf(0.57f, 1.19f, 1.81f, 2.43f)
    for (i in 0 until 16) {                                                                  // slabs on the upslope steps
        val kk = i / 4
        val x = b.rr(6.95f + kk * 2.4f, 9.05f + kk * 2.4f); val z = b.rr(-4.5f, 7.0f); val w = b.rr(0.9f, 2.4f)
        val d = b.rr(0.8f, 2.0f); val ryv = b.rr(0f, 3.14f); val shv = b.rr(0.78f, 1.02f)
        b.box(x, tops[kk] + 0.045f, z, w, 0.09f, d, if (i % 3 != 0) 6 else 1, shade = shv, ry = ryv)
    }
    for (i in 0 until 8) {                                                                   // slabs on the front steps
        val x = if (i < 5) b.rr(-6f, 10f) else b.rr(-4.5f, 8.5f)
        val z = if (i < 5) b.rr(7.0f, 9.2f) else b.rr(9.6f, 11.8f)
        val w = b.rr(0.9f, 2.4f); val d = b.rr(0.8f, 2.0f); val ryv = b.rr(0f, 3.14f); val shv = b.rr(0.78f, 1.0f)
        b.box(x, (if (i < 5) -0.29f else -0.44f) + 0.045f, z, w, 0.09f, d, if (i % 3 != 0) 6 else 1, shade = shv, ry = ryv)
    }
    for (i in 0 until 4) {                                                                   // slabs on the downslope step
        val x = b.rr(-10.2f, -7.0f); val z = b.rr(-4f, 5.5f); val w = b.rr(0.9f, 2.2f)
        val d = b.rr(0.8f, 1.8f); val ryv = b.rr(0f, 3.14f); val shv = b.rr(0.78f, 1.0f)
        b.box(x, -0.245f, z, w, 0.09f, d, if (i % 3 != 0) 6 else 1, shade = shv, ry = ryv)
    }
    for (i in 0 until 8) {                                                                   // cracks, upslope steps
        val kk = i / 2
        val x = b.rr(7.0f + kk * 2.4f, 9.0f + kk * 2.4f); val z = b.rr(-4f, 6.5f)
        val len = b.rr(1.5f, 3.5f); val ryv = b.rr(0f, 3.14f)
        b.box(x, tops[kk] + 0.015f, z, len, 0.015f, 0.05f, 3, flatColor = true, shade = 0.8f, ry = ryv)
    }
    for (i in 0 until 6) {                                                                   // cracks, front steps
        val x = if (i < 3) b.rr(-5.5f, 9.5f) else b.rr(-4f, 8f)
        val z = if (i < 3) b.rr(7.1f, 9.1f) else b.rr(9.7f, 11.7f)
        val len = b.rr(1.5f, 3.5f); val ryv = b.rr(0f, 3.14f)
        b.box(x, (if (i < 3) -0.29f else -0.44f) + 0.015f, z, len, 0.015f, 0.05f, 3, flatColor = true, shade = 0.8f, ry = ryv)
    }
    for (i in 0 until 6) {                                                                   // talus blocks at the bench scarp foot
        val x = -5f + i * 3.0f + b.rr(-0.8f, 0.8f); val z = b.rr(7.3f, 8.6f); val sx = b.rr(1.2f, 2.6f)
        val sy = b.rr(0.5f, 1.0f); val sz = b.rr(0.8f, 1.4f); val ryv = b.rr(0f, 3.14f)
        val rzv = b.rr(-0.08f, 0.08f); val shv = b.rr(0.78f, 1.0f)
        b.box(x, -0.29f + sy / 2f - 0.06f, z, sx, sy, sz, if (i % 2 != 0) 5 else 4, shade = shv, ry = ryv, rz = rzv)
    }
    for (i in 0 until 5) {                                                                   // boulders on the lower front step
        val x = b.rr(-4f, 9.5f); val z = b.rr(9.6f, 11.6f); val sx = b.rr(0.9f, 1.8f)
        val sy = b.rr(0.5f, 0.9f); val sz = b.rr(0.7f, 1.3f); val ryv = b.rr(0f, 3.14f)
        val rzv = b.rr(-0.08f, 0.08f); val shv = b.rr(0.8f, 1.0f)
        b.box(x, -0.44f + sy / 2f - 0.05f, z, sx, sy, sz, if (i % 2 != 0) 5 else 4, shade = shv, ry = ryv, rz = rzv)
    }
    for (i in 0 until 4) {                                                                   // boulders on the upslope steps
        val x = b.rr(7.1f + i * 2.4f, 8.9f + i * 2.4f); val z = b.rr(-3f, 6.5f); val sx = b.rr(0.9f, 1.8f)
        val sy = b.rr(0.5f, 0.9f); val sz = b.rr(0.7f, 1.3f); val ryv = b.rr(0f, 3.14f)
        val rzv = b.rr(-0.06f, 0.06f); val shv = b.rr(0.8f, 1.0f)
        b.box(x, tops[i] + sy / 2f - 0.06f, z, sx, sy, sz, if (i % 2 != 0) 5 else 4, shade = shv, ry = ryv, rz = rzv)
    }
    b.box(7.4f, 0.68f, 5.6f, 1.5f, 0.75f, 1.1f, 5, shade = 0.88f, ry = 0.5f, rz = 0.06f)     // hinge boulders on step 1
    b.box(7.9f, 0.57f, -3.8f, 1.1f, 0.6f, 0.9f, 4, shade = 0.95f, ry = -0.7f, rz = -0.05f)
    for (i in 0 until 3) {                                                                   // sand drifts on the steps
        val x = if (i < 2) b.rr(-3f, 8f) else b.rr(9.8f, 11.4f)
        val z = if (i < 2) b.rr(7.2f, 9.0f) else b.rr(-2f, 4f)
        val len = b.rr(1.8f, 3.0f); val dep = b.rr(0.6f, 1.0f); val ryv = b.rr(-0.3f, 0.3f)
        b.box(x, (if (i < 2) -0.29f else 1.19f) + 0.05f, z, len, 0.12f, dep, 8, ry = ryv)
    }
    for (cp in arrayOf(floatArrayOf(10.5f, 3.5f, 1.19f), floatArrayOf(13f, -2.5f, 1.81f))) { // dry scrub on the upslope
        for (kk in 0 until 4) {
            val bx = cp[0] + b.rr(-0.4f, 0.4f); val bz = cp[1] + b.rr(-0.4f, 0.4f)
            val sx = b.rr(0.4f, 0.7f); val sy = b.rr(0.26f, 0.44f); val sz = b.rr(0.4f, 0.7f)
            val ryv = b.rr(0f, 3.14f); val rzv = b.rr(-0.08f, 0.08f); val shv = b.rr(0.55f, 0.9f)
            b.box(bx, cp[2] + sy / 2f - 0.04f, bz, sx, sy, sz, 10, shade = shv, ry = ryv, rz = rzv)
        }
    }
}

private fun buildMilLocalScene(): MeshBuilder {
    PAL = PAL_M   // combined scene: Mil-e Azhdaha palette
    val b = MeshBuilder()                                    // fresh RNG state -> identical every build
    // bedrock pavement the tower stands on (cracked limestone outcrop)
    b.box(0f, -0.19f, 0f, 34f, 0.38f, 24f, 6, shade = 0.94f)
    b.box(-7.5f, -0.55f, 13.2f, 8f, 0.8f, 3.0f, 6, shade = 0.84f, ry = 0.02f)   // broken rock ledge segments in front
    b.box(0.5f, -0.62f, 13.7f, 9f, 0.85f, 3.4f, 6, shade = 0.80f, ry = -0.015f)
    b.box(8.0f, -0.50f, 13.0f, 7f, 0.75f, 2.8f, 6, shade = 0.87f, ry = 0.025f)
    for (i in 0 until 5) {                                   // tilted rock sheets leaning on the edge
        val sx = b.rr(-9f, 9f)
        val sw = b.rr(1.6f, 3.2f)
        val sh2 = b.rr(0.7f, 1.3f)
        val rxv = b.rr(-0.28f, -0.12f)
        val ryv = b.rr(-0.06f, 0.06f)
        val shv = b.rr(0.8f, 1.0f)
        b.box(sx, -0.15f, 12.35f, sw, sh2, 0.22f, if (i % 2 != 0) 6 else 1, shade = shv, rx = rxv, ry = ryv)
    }
    for (i in 0 until 34) {                                  // surface rock slabs
        val ang = b.rr(0f, 6.28f)
        val rad = b.rr(3.1f, 10.0f)
        val w = b.rr(0.8f, 2.2f)
        val d = b.rr(0.7f, 1.8f)
        val ryv = b.rr(0f, 3.14f)
        val shv = b.rr(0.78f, 1.02f)
        b.box(cos(ang) * rad, 0.038f, sin(ang) * rad * 0.8f, w, 0.085f, d, if (i % 3 != 0) 6 else 1, shade = shv, ry = ryv)
    }
    for (i in 0 until 18) {                                  // pavement cracks
        val cx = b.rr(-9f, 9f)
        val cz = b.rr(-6f, 7f)
        val len = b.rr(1.5f, 4f)
        val ryv = b.rr(0f, 3.14f)
        b.box(cx, 0.012f, cz, len, 0.015f, 0.05f, 3, flatColor = true, shade = 0.8f, ry = ryv)
    }
    for (i in 0 until 24) {                                  // pebbles / gravel on the pavement
        val ang = b.rr(0f, 6.28f)
        val rad = b.rr(2.9f, 9.5f)
        val ps = b.rr(0.09f, 0.24f)
        val ryv = b.rr(0f, 3.14f)
        val rzv = b.rr(-0.1f, 0.1f)
        b.box(cos(ang) * rad, ps / 2f - 0.01f, sin(ang) * rad * 0.85f, ps, ps * 0.7f, ps * 0.85f, if (i % 3 != 0) 5 else 6, ry = ryv, rz = rzv)
    }
    val m1v = b.pos.size / 3; val m1i = b.idx.size   // combined scene: local group starts here (far bedrock/ledge groundwork excluded)
    for (i in 0 until 16) {                                  // rubble apron at the socle foot
        val ang = b.rr(0f, 6.28f)
        val rad = b.rr(2.75f, 3.5f)
        val sx = b.rr(0.18f, 0.42f)
        val sy = b.rr(0.12f, 0.26f)
        val sz = b.rr(0.16f, 0.36f)
        val ryv = b.rr(0f, 3.14f)
        val rzv = b.rr(-0.12f, 0.12f)
        val shv = b.rr(0.75f, 1.0f)
        b.box(cos(ang) * rad, sy / 2f - 0.02f, sin(ang) * rad, sx, sy, sz, if (i % 2 != 0) 5 else 1, shade = shv, ry = ryv, rz = rzv)
    }
    b.lathe(0f, 0f, 0f, arrayOf(floatArrayOf(3.45f, 0f), floatArrayOf(3.1f, 0.06f), floatArrayOf(2.8f, 0.13f)), 48, 8)   // sand collar against the socle
    milTower(b)
    // fallen blocks + boulders (photo foreground)
    for (i in 0 until 8) {
        val ang = b.rr(0f, 6.28f)
        val rad = b.rr(3.2f, 6.4f)
        val sx = b.rr(0.5f, 1.1f)
        val sz = b.rr(0.45f, 0.9f)
        val sy = b.rr(0.3f, 0.6f)
        val ryv = b.rr(0f, 3.14f)
        val rzv = b.rr(-0.05f, 0.05f)
        val shv = b.rr(0.8f, 1.05f)
        b.box(cos(ang) * rad, sy / 2f - 0.03f, sin(ang) * rad, sx, sy, sz, if (i % 2 != 0) 5 else 4, shade = shv, ry = ryv, rz = rzv)
    }
    b.box(-3.6f, 0.32f, 3.4f, 1.25f, 0.65f, 1.0f, 5, shade = 0.88f, ry = 0.4f, rz = 0.06f)   // big foreground boulders
    b.box(-2.5f, 0.24f, 4.3f, 0.8f, 0.5f, 0.7f, 4, shade = 0.95f, ry = -0.3f)
    b.box(4.3f, 0.30f, 2.6f, 1.1f, 0.6f, 0.9f, 5, shade = 0.82f, ry = 0.9f, rz = -0.05f)
    val m2v = b.pos.size / 3; val m2i = b.idx.size   // combined scene: ledge boulders excluded
    b.box(-5.5f, -0.02f, 13.1f, 1.3f, 0.55f, 1.0f, 5, shade = 0.85f, ry = 0.5f)   // boulders on the ledge edge
    b.box(2.8f, -0.08f, 13.8f, 1.0f, 0.5f, 0.8f, 4, shade = 0.9f, ry = -0.4f)
    b.box(8.6f, 0.0f, 12.9f, 0.9f, 0.45f, 0.75f, 5, shade = 0.8f, ry = 1.1f)
    val m3v = b.pos.size / 3; val m3i = b.idx.size
    // fallen dressed ashlar blocks (face slabs still on them) near the base
    for (fa in arrayOf(floatArrayOf(-3.05f, 3.15f, 0.5f), floatArrayOf(2.7f, 3.6f, -0.7f), floatArrayOf(-1.9f, 4.6f, 1.15f))) {
        val cs = cos(fa[2])
        val sn = sin(fa[2])
        b.box(fa[0], 0.19f, fa[1], 0.85f, 0.38f, 0.55f, 4, shade = 0.9f, ry = fa[2])
        b.box(fa[0] + sn * 0.287f, 0.19f, fa[1] + cs * 0.287f, 0.78f, 0.31f, 0.025f, 4, shade = 1.02f, ry = fa[2])
    }
    // dry scrub bushes at the foot
    for (bp in arrayOf(floatArrayOf(-3.3f, 2.6f), floatArrayOf(3.9f, -1.6f), floatArrayOf(-2.9f, -3.3f), floatArrayOf(4.6f, 3.1f))) {
        for (k in 0 until 5) {
            val bx = bp[0] + b.rr(-0.4f, 0.4f)
            val by = b.rr(0.13f, 0.23f)
            val bz = bp[1] + b.rr(-0.4f, 0.4f)
            val sx = b.rr(0.4f, 0.7f)
            val sy = b.rr(0.26f, 0.44f)
            val sz = b.rr(0.4f, 0.7f)
            val ryv = b.rr(0f, 3.14f)
            val rzv = b.rr(-0.08f, 0.08f)
            val shv = b.rr(0.55f, 0.9f)
            b.box(bx, by, bz, sx, sy, sz, 10, shade = shv, ry = ryv, rz = rzv)
        }
    }
    // sand drifts against the socle
    for (df in arrayOf(floatArrayOf(-1.5f, 3.1f), floatArrayOf(2.2f, 2.9f), floatArrayOf(3.0f, -2.2f), floatArrayOf(-3.1f, -1.8f))) {
        val len = b.rr(1.6f, 2.6f)
        val dep = b.rr(0.5f, 0.8f)
        val ryv = b.rr(-0.3f, 0.3f)
        b.box(df[0], 0.05f, df[1], len, 0.13f, dep, 8, ry = ryv)
    }
    milSlope(b)   // combined scene: the tower's limestone hillside
    // Combined scene: keep only the tower + its local foot group (the wide
    // bedrock pavement / ledge set belongs to the standalone framing).
    return extractRanges(b, arrayOf(intArrayOf(m1v, m1i, m2v, m2i), intArrayOf(m3v, m3i, b.pos.size / 3, b.idx.size)))
}

// ============================================================================
// Shaders (GLSL ES 1.00 on the ES 3.0 context; Persepolis harmonized in ZIG change 8)
// ============================================================================

private const val VERTEX_SHADER = """
attribute vec3 aPos; attribute vec3 aNormal; attribute vec3 aColor;
uniform mat4 uMVP;
varying vec3 vN; varying vec3 vC; varying vec3 vW;
void main(){ vN=aNormal; vC=aColor; vW=aPos; gl_Position=uMVP*vec4(aPos,1.0); }"""

private const val FRAGMENT_SHADER_P = """
precision mediump float;
uniform vec3 uSunDir;   uniform vec3 uSunColor;    // direction TO sun, colour*intensity
uniform vec3 uMoonDir;  uniform vec3 uMoonColor;
uniform vec3 uSkyAmb;   uniform vec3 uGroundAmb;   // hemisphere ambient
uniform vec3 uCamPos;
varying vec3 vN; varying vec3 vC; varying vec3 vW;
float hash13(vec3 p){ return fract(sin(dot(p, vec3(127.1,311.7,74.7)))*43758.5453); }
float vnoise(vec3 p){
  vec3 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
  return mix(mix(mix(hash13(i),hash13(i+vec3(1,0,0)),f.x),
                 mix(hash13(i+vec3(0,1,0)),hash13(i+vec3(1,1,0)),f.x),f.y),
             mix(mix(hash13(i+vec3(0,0,1)),hash13(i+vec3(1,0,1)),f.x),
                 mix(hash13(i+vec3(0,1,1)),hash13(i+vec3(1,1,1)),f.x),f.y),f.z);
}
float fbm(vec3 p){ return 0.52*vnoise(p)+0.28*vnoise(p*2.13)+0.14*vnoise(p*4.7)+0.06*vnoise(p*9.3); }
void main(){
  vec3 N = normalize(vN);
  // ZIG change 8: a restrained, distance-scaled relative of the Mil stone
  // response. The bump is deliberately much weaker than the foreground
  // tower's, so dressed faces lose their CG flatness without turning the
  // distant relief into high-frequency noise.
  float _e=0.07;
  float _h0=vnoise(vW*3.0);
  vec3 _g=vec3(vnoise((vW+vec3(_e,0.0,0.0))*3.0)-_h0,
               vnoise((vW+vec3(0.0,_e,0.0))*3.0)-_h0,
               vnoise((vW+vec3(0.0,0.0,_e))*3.0)-_h0)/_e;
  float bumpAmt = mix(0.075, 0.030, smoothstep(4.35,14.0,vW.y));
  N = normalize(N - bumpAmt*(_g - N*dot(_g,N)));
  // Warm procedural limestone: broad mineral drift, multi-scale grain,
  // rain streaks, iron staining and ground grime. No texture assets needed.
  vec3 albedo = vC;
  float patch = vnoise(vW*0.5);
  float grain = fbm(vW*3.6);
  float streak = vnoise(vec3(vW.x*6.0, vW.y*0.32, vW.z*6.0));
  float tone = vnoise(vW*0.16+4.7);
  albedo *= 0.96 + 0.08*tone;  // slow tonal drift — SCALAR only: value may wander, hue may not
  albedo *= 0.78 + 0.34*patch + 0.14*grain;
  albedo *= 0.90 + 0.14*streak;
  albedo *= 0.94 + 0.10*hash13(floor(vW*7.0));
  albedo *= 1.0 - 0.18*smoothstep(2.4,0.0,vW.y);
  float iron = smoothstep(0.68,0.84,vnoise(vW*0.85+19.1)) *
      (0.25+0.75*smoothstep(10.0,4.35,vW.y));
  albedo = mix(albedo, albedo*vec3(0.96,0.74,0.52), iron*0.22);   // iron stain: the stone's own hue, deepened
  float bio = smoothstep(0.72,0.95,vnoise(vW*1.25+7.3)) * smoothstep(7.0,2.5,vW.y);
  albedo *= 1.0 - 0.16*bio;                                       // dry staining low down: shade only, no green
  float strata = vnoise(vec3(vW.x*0.22, vW.y*1.35, vW.z*0.22));   // limestone bedding planes
  albedo *= 0.96 + 0.08*strata;
  float pit = step(0.965, hash13(floor(vW*18.0)));                 // sparse weather pits
  albedo *= 1.0 - 0.14*pit;
  float dust = smoothstep(0.55,0.92,N.y) * (0.5+0.5*vnoise(vW*1.7+3.1));  // settled dust on up-faces
  albedo = mix(albedo, vec3(0.70,0.53,0.35), dust*0.30);  // dust = palette index 8: same stone, powdered
  float splash = exp(-max(vW.y-4.35,0.0)*1.9) * step(4.34,vW.y);  // rain-splash grime above the platform
  albedo *= 1.0 - 0.10*splash*(0.6+0.4*grain);
  // Hue lock (ZIG colour unification): whatever the stains above did, pull
  // every fragment back toward the ONE limestone hue line at its own
  // luminance. Value contrast (frames vs fields, relief vs ground) is
  // preserved exactly; hue divergence is not allowed to survive.
  float _lum = dot(albedo, vec3(0.299,0.587,0.114));
  albedo = mix(albedo, vec3(0.64,0.46,0.27)*(_lum/0.492), 0.30);
  vec3 amb = mix(uGroundAmb, uSkyAmb, N.y*0.5+0.5);
  vec3 col = albedo * amb;
  float ds = max(dot(N, normalize(uSunDir)), 0.0);
  col += albedo * uSunColor * ds;
  float dm = max(dot(N, normalize(uMoonDir)), 0.0);
  col += albedo * uMoonColor * dm;
  vec3 V = normalize(uCamPos - vW);
  vec3 H = normalize(normalize(uSunDir) + V);
  col += uSunColor * pow(max(dot(N,H),0.0), 24.0) * 0.030;   // faint stone sheen
  float rim = pow(1.0 - max(dot(N,V),0.0), 3.0);
  col += uSunColor * rim * 0.065;                             // restrained backlit edge response
  float aerial = (1.0-smoothstep(-14.0,18.0,vW.z)) * 0.04;   // a trace of distance haze at the back
  col = mix(col, amb*0.72, aerial);
  col = vec3(1.0) - exp(-col * 1.55);                         // soft exposure (no clipped stone)
  col = pow(col, vec3(0.4545));                               // linear → sRGB-ish
  gl_FragColor = vec4(col, 1.0);
}"""

/** Direction TO a body, from astronomical azimuth/altitude, in world axes (N, up, E) = (X, Y, Z). */
// Mil-e Azhdaha fragment shader (from MilAzhdaha.kt) — same uniforms and
// light rig as FRAGMENT_SHADER_P, warmer close-range stone response.
private const val FRAGMENT_SHADER_M = """
precision mediump float;
uniform vec3 uSunDir;   uniform vec3 uSunColor;    // direction TO sun, colour*intensity
uniform vec3 uMoonDir;  uniform vec3 uMoonColor;
uniform vec3 uSkyAmb;   uniform vec3 uGroundAmb;   // hemisphere ambient
uniform vec3 uCamPos;
varying vec3 vN; varying vec3 vC; varying vec3 vW;
float hash13(vec3 p){ return fract(sin(dot(p, vec3(127.1,311.7,74.7)))*43758.5453); }
float vnoise(vec3 p){
  vec3 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
  return mix(mix(mix(hash13(i),hash13(i+vec3(1,0,0)),f.x),
                 mix(hash13(i+vec3(0,1,0)),hash13(i+vec3(1,1,0)),f.x),f.y),
             mix(mix(hash13(i+vec3(0,0,1)),hash13(i+vec3(1,0,1)),f.x),
                 mix(hash13(i+vec3(0,1,1)),hash13(i+vec3(1,1,1)),f.x),f.y),f.z);
}
float fbm(vec3 p){ return 0.52*vnoise(p)+0.28*vnoise(p*2.13)+0.14*vnoise(p*4.7)+0.06*vnoise(p*9.3); }
void main(){
  vec3 N = normalize(vN);
  // Noise-gradient bump: makes flat block faces read as hand-dressed stone
  float _e=0.055;
  float _h0=fbm(vW*3.0);
  vec3 _g=vec3(fbm((vW+vec3(_e,0.0,0.0))*3.0)-_h0, fbm((vW+vec3(0.0,_e,0.0))*3.0)-_h0, fbm((vW+vec3(0.0,0.0,_e))*3.0)-_h0)/_e;
  float bumpAmt = mix(0.21, 0.06, smoothstep(0.0,6.0,vW.y));   // base stone far rougher than the dressed top
  N = normalize(N - bumpAmt*(_g - N*dot(_g,N)));
  // Procedural limestone: broad tonal patches, fine grain, rain streaks,
  // ground grime and a whisper of moss low down. No texture assets needed.
  vec3 albedo = vC;
  float patch = vnoise(vW*0.5);
  float grain = fbm(vW*3.4);
  float streak = vnoise(vec3(vW.x*9.0, vW.y*0.38, vW.z*9.0));
  float tone = vnoise(vW*0.16+4.7);
  albedo *= vec3(0.92+0.14*tone, 0.97+0.05*tone, 1.05-0.10*tone);   // slow warm/cool drift across the stone
  albedo *= 0.74 + 0.38*patch + 0.16*grain;
  albedo *= 0.905 + 0.135*streak;   // broken up below by grain so no clean bands
  albedo *= 0.90 + 0.17*hash13(floor(vW*17.0));
  float tool = sin((vW.x+vW.z)*34.0 + grain*7.0) * (1.0-abs(N.y)) * smoothstep(2.0,5.0,vW.y);
  albedo *= 1.0 + 0.028*tool;                                       // diagonal chisel tooling on dressed faces
  albedo *= 1.0 - 0.15*smoothstep(2.4,0.0,vW.y);
  albedo *= 0.84 + 0.16*smoothstep(0.0,0.45,vW.y);                  // ground-contact darkening
  float moss = smoothstep(0.66,0.92,vnoise(vW*1.25+7.3)) * smoothstep(8.0,2.5,vW.y);
  albedo = mix(albedo, vec3(0.16,0.22,0.09), moss*0.28);
  float strata = vnoise(vec3(vW.x*0.22, vW.y*1.35, vW.z*0.22));   // limestone bedding planes
  albedo *= 0.96 + 0.08*strata;
  float pit = step(0.94, hash13(floor(vW*34.0)));                  // weather pits (honeycombed low courses)
  albedo *= 1.0 - 0.26*pit*(0.65+0.85*(1.0-smoothstep(0.6,2.6,vW.y)));
  float varn = smoothstep(0.80,0.88, vnoise(vW*5.5+23.7));        // black lichen / desert varnish speckle
  albedo = mix(albedo, vec3(0.13,0.12,0.11), varn*0.25);
  float dust = smoothstep(0.55,0.92,N.y) * (0.5+0.5*vnoise(vW*1.7+3.1));  // settled dust on up-faces
  albedo = mix(albedo, vec3(0.72,0.63,0.44), dust*0.38);
  float splash = exp(-max(vW.y,0.0)*1.4);                          // rain-splash grime at the foot
  albedo *= 1.0 - 0.13*splash*(0.6+0.4*grain);
  float rust = smoothstep(0.62,0.78, vnoise(vW*0.9+19.1)) * (0.35+0.65*smoothstep(5.5,1.0,vW.y));
  albedo = mix(albedo, vec3(0.45,0.26,0.13), rust*0.28);           // iron-oxide staining, stronger low down
  float salt = smoothstep(0.55,0.75, vnoise(vW*1.4+31.3)) * exp(-max(vW.y,0.0)*0.9);
  albedo = mix(albedo, vec3(0.78,0.74,0.66), salt*0.30);           // salt efflorescence near the base
  float vein = pow(1.0-abs(2.0*vnoise(vec3(vW.x*1.1, vW.y*2.4, vW.z*1.1))-1.0), 7.0);   // white calcite veining
  albedo += vein*0.13;
  float lich = smoothstep(0.74,0.82, vnoise(vW*2.2+11.7));                               // orange lichen spots
  albedo = mix(albedo, vec3(0.52,0.31,0.13), lich*0.15);
  vec3 amb = mix(uGroundAmb, uSkyAmb, N.y*0.5+0.5);
  vec3 col = albedo * amb;
  float ds = max(dot(N, normalize(uSunDir)), 0.0);
  col += albedo * uSunColor * ds;
  float dm = max(dot(N, normalize(uMoonDir)), 0.0);
  col += albedo * uMoonColor * dm;
  vec3 V = normalize(uCamPos - vW);
  vec3 H = normalize(normalize(uSunDir) + V);
  col += uSunColor * pow(max(dot(N,H),0.0), 32.0) * 0.06;   // faint stone sheen
  float rim = pow(1.0 - max(dot(N,V),0.0), 3.0);
  col += uSunColor * rim * 0.12;                              // edge glow (strong when backlit)
  col = vec3(1.0) - exp(-col * 1.55);                         // soft exposure (no clipped stone)
  col = pow(col, vec3(0.4545));                               // linear → sRGB-ish
  gl_FragColor = vec4(col, 1.0);
}"""

private fun dirFromAzAlt(azimuthDeg: Float, altitudeDeg: Float): FloatArray {
    val az = Math.toRadians(azimuthDeg.toDouble())
    val al = Math.toRadians(altitudeDeg.toDouble())
    return floatArrayOf(
        (cos(al) * cos(az)).toFloat(),
        sin(al).toFloat(),
        (cos(al) * sin(az)).toFloat(),
    )
}

private fun lerp3(a: FloatArray, b: FloatArray, f: Float): FloatArray =
    floatArrayOf(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f)

// ============================================================================
// Scene: GL resources + light state + static chunked VBOs.
// init()/draw()/release() MUST run on the GL thread; the setters are
// synchronised and may be called from any thread.
// ============================================================================

class PersepolisMilAzhdahaScene {

    /** World width (m) the fixed camera tries to show. 116 (default) reproduces
     *  the HTML preview: the façade at the far left (platform wings cut back)
     *  plus the Mil-e Azhdaha tower at the right foreground. */
    @Volatile var visibleWidthMeters: Float = 116f

    /** Where the platform's front toe (world y=0, z=8) sits in the viewport, as
     *  a fraction from the top. 0.80 (default) reproduces the HTML preview's
     *  grass-foreground framing; raise toward 0.995 to plant the building's
     *  base on the bottom edge of an overlay strip. */
    @Volatile var baseFractionFromTop: Float = 0.80f

    /** Where the combined frame's anchor (0,0,-2) sits in the viewport, as
     *  a fraction of the width from the left. 0.5 (default) preserves the
     *  supplied centred framing exactly; smaller values shift the unchanged
     *  fixed-camera view toward the left by moving the orthographic window
     *  in view space. */
    @Volatile var centerFractionFromLeft: Float = 0.5f

    /** If true (default), the camera zooms out on very wide/short viewports
     *  so the 19 m columns are never cropped. */
    @Volatile var autoFitHeight: Boolean = true

    private val lock = Any()
    private var sunDir = dirFromAzAlt(267.8f, 2.5f)
    private var sunColor = floatArrayOf(0f, 0f, 0f)
    private var moonDir = dirFromAzAlt(120f, 35f)
    private var moonColor = floatArrayOf(0f, 0f, 0f)
    private var skyAmb = floatArrayOf(0.055f, 0.065f, 0.095f)
    private var groundAmb = floatArrayOf(0.03f, 0.03f, 0.035f)

    init { setSun(267.8f, 2.5f) }   // sunset, as in the reference photograph

    /**
     * Move the Sun. Astronomical azimuth (0°=N, 90°=E, 180°=S, 270°=W).
     * Colour, intensity and hemisphere ambient are derived from altitude:
     * saturated orange near the horizon, near-white when high, fading
     * through twilight to a dim blue night ambient by −12°. This is the
     * only call a typical astronomy app needs per ephemeris update.
     */
    fun setSun(azimuthDeg: Float, altitudeDeg: Float) {
        synchronized(lock) {
            sunDir = dirFromAzAlt(azimuthDeg, altitudeDeg)
            if (altitudeDeg > -0.833f) {
                val alt = max(altitudeDeg, 0f)
                val warm = (1.0 - min(alt / 28f, 1f)).pow(1.35).toFloat()
                val inten = 0.25f + 1.05f * min(sin(Math.toRadians(alt.toDouble())).toFloat() * 2.4f, 1f)
                sunColor = floatArrayOf(inten, inten * (1f - 0.38f * warm), inten * (1f - 0.72f * warm))
                val a = 0.30f + 0.26f * min(alt / 40f, 1f)
                skyAmb = floatArrayOf(a * (1.02f + 0.22f * warm), a * (0.98f - 0.05f * warm), a * (1.05f - 0.38f * warm))
                groundAmb = floatArrayOf(a * 0.66f, a * 0.57f, a * 0.44f)
            } else {
                // Twilight → night: residual warm glow toward the set Sun, blue ambient
                val f = ((altitudeDeg + 12f) / 11.167f).coerceIn(0f, 1f)
                sunColor = floatArrayOf(0.55f * f * f, 0.22f * f * f, 0.06f * f * f)
                skyAmb = lerp3(floatArrayOf(0.055f, 0.065f, 0.095f), floatArrayOf(0.34f, 0.25f, 0.18f), f)
                groundAmb = lerp3(floatArrayOf(0.03f, 0.03f, 0.035f), floatArrayOf(0.20f, 0.15f, 0.10f), f)
            }
        }
    }

    /** Explicit Sun light (colour already multiplied by intensity), bypassing
     *  the derivation in [setSun]. Does not touch the ambient terms. */
    fun setSunLight(azimuthDeg: Float, altitudeDeg: Float, r: Float, g: Float, b: Float) {
        synchronized(lock) {
            sunDir = dirFromAzAlt(azimuthDeg, altitudeDeg)
            sunColor = floatArrayOf(r, g, b)
        }
    }

    /** Move the Moon (second directional light, cool blue-grey). [intensity]
     *  ≈ 0.22 is a bright full Moon; scale it by the illuminated fraction.
     *  Below the horizon the light switches off automatically. */
    fun setMoon(azimuthDeg: Float, altitudeDeg: Float, intensity: Float = 0.22f) {
        synchronized(lock) {
            moonDir = dirFromAzAlt(azimuthDeg, altitudeDeg)
            val fade = (sin(Math.toRadians(max(altitudeDeg, 0f).toDouble())) * 3.0).coerceIn(0.0, 1.0).toFloat()
            val i = if (altitudeDeg <= 0f) 0f else intensity * fade
            moonColor = floatArrayOf(0.68f * i, 0.76f * i, 1.0f * i)
        }
    }

    /** Override the hemisphere ambient (sky colour from above, ground bounce
     *  from below). Note [setSun] recomputes it, so call this after setSun. */
    fun setAmbient(skyR: Float, skyG: Float, skyB: Float, groundR: Float, groundG: Float, groundB: Float) {
        synchronized(lock) {
            skyAmb = floatArrayOf(skyR, skyG, skyB)
            groundAmb = floatArrayOf(groundR, groundG, groundB)
        }
    }

    // ---- GL resources (GL thread only) ----
    // Each mesh (Persepolis 145,449 vertices, tower group 20,043) is split
    // into ≤60k-vertex chunks at upload so plain 16-bit indices work on
    // every target device — no extension needed.
    private class Chunk(val buffers: IntArray, val indexCount: Int)   // pos, normal, colour, index
    // Two programs share the vertex shader and the light uniforms but keep
    // each monument's own fragment shader, exactly as in the HTML preview.
    private class Prog(val id: Int) {
        var uMVP = -1; var uSunDir = -1; var uSunColor = -1
        var uMoonDir = -1; var uMoonColor = -1
        var uSkyAmb = -1; var uGroundAmb = -1; var uCamPos = -1
    }

    /** A built mesh as plain arrays — memoised once per process (see companion). */
    private class MeshArrays(
        val positions: FloatArray,
        val normals: FloatArray,
        val colours: FloatArray,
        val indices: IntArray,
    )
    private var progP: Prog? = null        // Persepolis
    private var progM: Prog? = null        // Mil-e Azhdaha
    private val chunksP = ArrayList<Chunk>()
    private val chunksM = ArrayList<Chunk>()

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw RuntimeException("PersepolisMilAzhdaha shader compile failed: $log")
        }
        return s
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }

    private fun makeProgram(fragmentSrc: String): Prog {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glBindAttribLocation(id, 0, "aPos")
        GLES30.glBindAttribLocation(id, 1, "aNormal")
        GLES30.glBindAttribLocation(id, 2, "aColor")
        GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("PersepolisMilAzhdaha program link failed: ${GLES30.glGetProgramInfoLog(id)}")
        val p = Prog(id)
        p.uMVP = GLES30.glGetUniformLocation(id, "uMVP")
        p.uSunDir = GLES30.glGetUniformLocation(id, "uSunDir")
        p.uSunColor = GLES30.glGetUniformLocation(id, "uSunColor")
        p.uMoonDir = GLES30.glGetUniformLocation(id, "uMoonDir")
        p.uMoonColor = GLES30.glGetUniformLocation(id, "uMoonColor")
        p.uSkyAmb = GLES30.glGetUniformLocation(id, "uSkyAmb")
        p.uGroundAmb = GLES30.glGetUniformLocation(id, "uGroundAmb")
        p.uCamPos = GLES30.glGetUniformLocation(id, "uCamPos")
        return p
    }

    private fun upload(mesh: MeshArrays, out: ArrayList<Chunk>) {
        val positions = mesh.positions
        val normals = mesh.normals
        val colours = mesh.colours
        val indices = mesh.indices

        // Remap into ≤60k-vertex chunks (see the Chunk note above). Primitives
        // are emitted with local vertices, so chunk-boundary duplication is
        // negligible; the HashMap keeps this correct for any mesh topology.
        val map = HashMap<Int, Int>()
        val cPos = FloatBag(); val cNor = FloatBag(); val cCol = FloatBag(); val cIdx = IntBag()
        fun flushChunk() {
            if (cIdx.size == 0) return
            val b = IntArray(4)
            GLES30.glGenBuffers(4, b, 0)
            val p = cPos.toFloatArray(); val n = cNor.toFloatArray(); val c = cCol.toFloatArray()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, p.size * 4, floatBuffer(p), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[1])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n.size * 4, floatBuffer(n), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[2])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, c.size * 4, floatBuffer(c), GLES30.GL_STATIC_DRAW)
            val ib = cIdx.toIntArray()
            val sb: ShortBuffer = ByteBuffer.allocateDirect(ib.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (i in ib) sb.put(i.toShort())   // bit pattern preserved; read back as UNSIGNED_SHORT
            sb.position(0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, b[3])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib.size * 2, sb, GLES30.GL_STATIC_DRAW)
            out.add(Chunk(b, ib.size))
            map.clear(); cPos.clear(); cNor.clear(); cCol.clear(); cIdx.clear()
        }
        var ti = 0
        while (ti < indices.size) {
            var fresh = 0
            for (k in 0..2) if (!map.containsKey(indices[ti + k])) fresh++
            if (cIdx.size > 0 && map.size + fresh > 60000) flushChunk()
            for (k in 0..2) {
                val vi = indices[ti + k]
                var ni = map[vi]
                if (ni == null) {
                    ni = map.size; map[vi] = ni
                    for (c in 0..2) {
                        cPos.add(positions[vi * 3 + c]); cNor.add(normals[vi * 3 + c]); cCol.add(colours[vi * 3 + c])
                    }
                }
                cIdx.add(ni)
            }
            ti += 3
        }
        flushChunk()
    }

    /** Build both meshes and upload them. Call once per GL context (onSurfaceCreated). */
    fun init() {
        // onSurfaceCreated also runs when a fresh EGL context replaces a dead
        // one (surface recreated, context-preservation failure). Resource
        // names from the dead context are meaningless — and, worse, numeric
        // names may be recycled — so drop every stale handle before
        // rebuilding. The arrays themselves are memoised (see companion):
        // both builders are deterministic, so a rebuild is bit-identical.
        if (progP != null || progM != null || chunksP.isNotEmpty() || chunksM.isNotEmpty()) release()
        progP = makeProgram(FRAGMENT_SHADER_P)
        progM = makeProgram(FRAGMENT_SHADER_M)
        upload(persepolisMeshArrays, chunksP)
        upload(milMeshArrays, chunksM)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun drawMesh(p: Prog, chunks: ArrayList<Chunk>) {
        GLES30.glUseProgram(p.id)
        GLES30.glUniformMatrix4fv(p.uMVP, 1, false, mvp, 0)
        synchronized(lock) {
            GLES30.glUniform3fv(p.uSunDir, 1, sunDir, 0)
            GLES30.glUniform3fv(p.uSunColor, 1, sunColor, 0)
            GLES30.glUniform3fv(p.uMoonDir, 1, moonDir, 0)
            GLES30.glUniform3fv(p.uMoonColor, 1, moonColor, 0)
            GLES30.glUniform3fv(p.uSkyAmb, 1, skyAmb, 0)
            GLES30.glUniform3fv(p.uGroundAmb, 1, groundAmb, 0)
        }
        GLES30.glUniform3fv(p.uCamPos, 1, CAM, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        for (chunk in chunks) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, chunk.buffers[0])
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, chunk.buffers[1])
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, chunk.buffers[2])
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, chunk.buffers[3])
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, chunk.indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        }
    }

    /**
     * Draw one frame into the current viewport. Does not clear colour (the
     * sky behind shows through); pass [clear] = true for a standalone
     * transparent surface. Safe to call between your own GL work: it binds
     * its own program/buffers and enables depth test + back-face culling.
     */
    fun draw(width: Int, height: Int, clear: Boolean = false) {
        val pP = progP ?: return
        val pM = progM ?: return
        if (width <= 0 || height <= 0) return
        GLES30.glViewport(0, 0, width, height)
        if (clear) {
            GLES30.glClearColor(0f, 0f, 0f, 0f)   // transparent: composites over the sky
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)

        val aspect = width.toFloat() / height.toFloat()
        var visW = visibleWidthMeters
        if (autoFitHeight) {
            // Ensure the full 25 m from ground to column tops fits between the
            // top of the viewport and the ground line: visH ≥ 25 / baseFraction.
            val needW = aspect * (25f / max(baseFractionFromTop, 0.05f))
            if (needW > visW) visW = needW
        }
        val visH = visW / aspect
        Matrix.setLookAtM(view, 0, CAM[0], CAM[1], CAM[2], 0f, CAM_TARGET_Y, 0f, 0f, 1f, 0f)
        // Ortho bounds live in VIEW space, and the camera is off-axis, so solve
        // the centre by projecting real world points through the view matrix:
        // X is centred on the platform centroid (0,0,-2); the ground line is
        // the platform's FRONT TOE (0,0,8), which projects lowest at the angle.
        val cv = FloatArray(4)
        Matrix.multiplyMV(cv, 0, view, 0, floatArrayOf(0f, 0f, -2f, 1f), 0)
        val centreX = cv[0] + (0.5f - centerFractionFromLeft.coerceIn(0f, 1f)) * visW
        Matrix.multiplyMV(cv, 0, view, 0, floatArrayOf(0f, 0f, 8f, 1f), 0)
        // Centre that puts the front toe at baseFractionFromTop (NDC = 1 − 2·fraction).
        val centreY = cv[1] + (2f * baseFractionFromTop - 1f) * visH / 2f
        Matrix.orthoM(proj, 0, centreX - visW / 2f, centreX + visW / 2f, centreY - visH / 2f, centreY + visH / 2f, 10f, 300f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        drawMesh(pP, chunksP)   // Persepolis first…
        drawMesh(pM, chunksM)   // …then the tower group standing in front of it

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Delete GL resources. Call on the GL thread when the context is lost/finished. */
    fun release() {
        for (chunk in chunksP + chunksM) if (chunk.buffers[0] != 0) GLES30.glDeleteBuffers(4, chunk.buffers, 0)
        chunksP.clear(); chunksM.clear()
        progP?.let { GLES30.glDeleteProgram(it.id) }; progP = null
        progM?.let { GLES30.glDeleteProgram(it.id) }; progM = null
    }

    companion object {
        // Fixed camera (metres): south-east of the façade, ≈9° off-axis and
        // slightly above, so floor planes, column rows and wall thicknesses
        // read in depth. Still one fixed view — no orbit, no zoom.
        private val CAM = floatArrayOf(-17f, 15.5f, 110f)
        private const val CAM_TARGET_Y = 9.0f

        // The deterministic builders run once per process, in the order below;
        // only the VBO uploads repeat per GL context (see [init]).
        private val persepolisMeshArrays: MeshArrays by lazy {
            meshArraysOf(
                transformMesh(buildPersepolisScene(), 1f, 0f, PERSEPOLIS_X, 0f, 0f)
            )
        }
        private val milMeshArrays: MeshArrays by lazy {
            meshArraysOf(
                transformMesh(
                    buildMilLocalScene(),
                    MIL_GROUP_SCALE,
                    MIL_GROUP_ROTY,
                    MIL_GROUP_X + MIL_HOME_RIGHT_SHIFT_M,
                    MIL_GROUP_Y,
                    MIL_GROUP_Z,
                )
            )
        }

        private fun meshArraysOf(mesh: MeshBuilder): MeshArrays = MeshArrays(
            positions = mesh.pos.toFloatArray(),
            normals = mesh.nor.toFloatArray(),
            colours = mesh.col.toFloatArray(),
            indices = mesh.idx.toIntArray(),
        )
    }
}

// ============================================================================
// Ready-made transparent overlay view (Option A in the header docs).
// Non-interactive, renders ONE frame per light/framing change.
// Remember to forward onPause()/onResume() from your Activity/Fragment.
// ============================================================================

class PersepolisMilAzhdahaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    /** The scene, exposed for framing controls and explicit-light APIs. */
    val scene = PersepolisMilAzhdahaScene()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)   // RGBA8888 + depth, for alpha compositing
        // ZIG integration (change 4 in the header note): keep the static VBOs across
        // Activity pauses when the driver allows it; init()'s guard covers the rest.
        setPreserveEGLContextOnPause(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)                     // surface above the sky beneath it
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = scene.init()
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = Unit
            override fun onDrawFrame(gl: GL10?) = scene.draw(width, height, clear = true)
        })
        renderMode = RENDERMODE_WHEN_DIRTY       // draw only when the light moves
        isClickable = false
        isFocusable = false
    }

    /** Astronomical azimuth/altitude; colour & ambient derived automatically. */
    fun setSun(azimuthDeg: Float, altitudeDeg: Float) {
        scene.setSun(azimuthDeg, altitudeDeg)
        requestRender()
    }

    /** Moon as second light; scale [intensity] by illuminated fraction (≈0.22 = full). */
    fun setMoon(azimuthDeg: Float, altitudeDeg: Float, intensity: Float = 0.22f) {
        scene.setMoon(azimuthDeg, altitudeDeg, intensity)
        requestRender()
    }

    /** Framing: visible world width in metres (116 = the preview look), the
     *  ground-line position as a fraction of the view height from the top
     *  (0.80 = the preview look; 0.995 plants the base on the bottom edge), and
     *  optionally the combined frame's anchor x as a fraction from the left. */
    fun setFraming(
        visibleWidthMeters: Float,
        baseFractionFromTop: Float,
        centerFractionFromLeft: Float = 0.5f,
    ) {
        scene.visibleWidthMeters = visibleWidthMeters
        scene.baseFractionFromTop = baseFractionFromTop
        scene.centerFractionFromLeft = centerFractionFromLeft
        requestRender()
    }
}
