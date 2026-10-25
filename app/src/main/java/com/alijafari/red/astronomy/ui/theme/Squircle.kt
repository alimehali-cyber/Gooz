package com.alijafari.red.astronomy.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * THE SQUIRCLE — one continuous-curvature corner for every rounded rectangle in the app.
 *
 * ## Why this exists
 *
 * `RoundedCornerShape` replaces each corner with a **circular arc**. Every straight edge has
 * curvature `0` and a circle has curvature `1/r` everywhere, so at the eight tangent points where an
 * arc meets an edge the curvature jumps from `0` to `1/r` instantly. That is a **G1** join: positions
 * and tangents match, curvature does not. The eye reads the jump as a small, abrupt "event" at the
 * join, and a screen full of such corners accumulates that noise.
 *
 * A **squircle** — Lamé's superellipse, `|x/a|^n + |y/a|^n = 1` — runs its curvature continuously
 * from `0` at the edge to a maximum on the corner diagonal: a **G2** join. There is no point at which
 * the flat part ends and the curve begins, which is why such corners read as "natural" rather than
 * as a rectangle with surgery performed on it.
 *
 * `n = 2` is a circle, `n = 4` is Lamé's squircle proper, `n → ∞` is a square. This file uses
 * **[Squircle.EXPONENT] = 5** everywhere, so every container in the app shares a single curve.
 * Exponent is the one dial that changes how *heavy* a corner reads at a fixed radius: the curve still
 * runs the full radius along each edge, but at `n = 5` it holds the edge longer and turns later, so
 * the corner carries more mass. Two measured effects on the unit corner box — curvature at the 45°
 * point, which is what the eye reads as corner strength, rises from `2.5227` at `n = 4` to `3.2490`
 * (**+28.8 %**), and the diagonal crossing moves out from `0.8409 r` to `0.8706 r`. Past `n ≈ 6` the
 * corner starts to read as a clipped box rather than a curve, so this is the heaviest setting the
 * system uses.
 *
 * ## How it is drawn
 *
 * The superellipse has no closed-form cubic equivalent, so a corner is a chain of **four cubic
 * Béziers** fitted to it:
 *
 * ```
 * edge ── G2 ── cubic ── G2 ── cubic ── 45° diagonal ── cubic ── G2 ── cubic ── G2 ── edge
 * ```
 *
 * The fit is done in **arc length** — not in the superellipse parameter, which clusters points at
 * the ends — and every interior node is solved as a cubic Hermite with *prescribed* endpoint
 * positions, tangent directions and curvatures. Measured against the analytic curve on the unit
 * corner box:
 *
 *  - maximum positional deviation **0.0081 % of the radius** — 0.01 px at the app's 40 dp radius on
 *    a 3x display, i.e. two orders of magnitude below one pixel;
 *  - residual curvature discontinuity at the interior nodes **< 5e-6 relative** (single-precision
 *    noise; the float64 fit is exact to 1e-14);
 *  - curvature at the two edge joins is **exactly 0**, so those joins are G2 as well;
 *  - both ends are exactly tangent to their edges, so the curve meets each edge at G1 by
 *    construction.
 *
 * Only the first half of the corner is authored ([Squircle.CORNER_HALF]); the second half is its
 * exact mirror through the diagonal (`(x, y) → (1 - y, 1 - x)`), so the corner cannot drift out of
 * symmetry. `SquircleGeometryTest` re-derives the analytic superellipse and asserts all of the above
 * on the JVM, so these numbers are verified on every build rather than trusted.
 *
 * ## Capsules are not rounded rectangles
 *
 * A capsule is a rectangle whose radius has reached half its short side: its ends are *semicircular
 * by definition*. Smoothing that corner would visibly flatten the ends of every pill, badge and
 * avatar, so capsules keep circular ends ([RedShapes.capsule]). That is the one deliberate
 * exception — the same one every squircle-based design system makes.
 */
object Squircle {

    /**
     * Lamé exponent. `2` = circle, `4` = the squircle proper, `5` = the heavy squircle this app wears,
     * `∞` = square.
     *
     * One value for the whole app: a corner should look the same at every size, so there is no
     * per-radius tuning knob that could drift. Raising it is how a corner gains weight without
     * gaining radius — see the file header for the measured numbers.
     */
    const val EXPONENT: Float = 5f

    /**
     * Where a corner crosses its own diagonal, as a multiple of its radius: `2^(-1/n)`.
     *
     * This is the number that makes a squircle read squarer than a circle at the same radius. A
     * circular corner turns through 45° at `0.7071 r` from the edge join and is already standing
     * vertical by `1.0 r`. The squircle is still only at `0.8706 r` when it reaches the diagonal, so
     * its curve keeps running outward toward the box corner instead of leaving the edge early — and
     * then it comes back to vertical over the remaining `0.1294 r`. Same radius, visibly squarer,
     * with no kink anywhere.
     */
    const val EXTENT_RATIO: Float = 0.8705506f

    /**
     * The first half of the corner as two cubic Béziers, in the canonical unit corner box.
     *
     * `x` runs along the edge the corner starts on, `y` runs into the corner. The curve begins at
     * `(0, 0)` with a horizontal tangent and ends at the 45° point `(0.8408964, 0.1591036)` with a
     * 45° tangent. Stored `[p0x, p0y, p1x, p1y, p2x, p2y, p3x, p3y]` per segment.
     */
    @PublishedApi
    internal val CORNER_HALF: FloatArray = floatArrayOf(
        // Segment A — leaves the edge with zero curvature, so the edge join is G2.
        0.000000000000000f, 0.000000000000000f,
        0.252049456513361f, 0.000000000000000f,
        0.360353846483799f, 0.000000000000000f,
        0.449610105958251f, 0.003701909074992f,

        // Segment B — arrives on the 45° diagonal with matching curvature to its mirror image.
        0.449610105958251f, 0.003701909074992f,
        0.632252803152206f, 0.011277026632061f,
        0.777198453623326f, 0.036097426266564f,
        0.870550447618998f, 0.129449321026811f
    )

    /**
     * The whole corner: `[A, B, mirror(B), mirror(A)]` flattened to the twelve control points a
     * `Path` needs, as `x, y` pairs. Precomputed once because the mirror is exact by construction.
     */
    @PublishedApi
    internal val CORNER: FloatArray = floatArrayOf(
        // Segment A: its three control points (p1, p2, p3); p0 is the corner's start on the edge.
        0.252049456513361f, 0.000000000000000f,
        0.360353846483799f, 0.000000000000000f,
        0.449610105958251f, 0.003701909074992f,
        // Segment B.
        0.632252803152206f, 0.011277026632061f,
        0.777198453623326f, 0.036097426266564f,
        0.870550447618998f, 0.129449321026811f,
        // Segment C = mirror(Segment B): (x, y) -> (1 - y, 1 - x), walked back from the diagonal.
        1f - 0.036097426266564f, 1f - 0.777198453623326f,
        1f - 0.011277026632061f, 1f - 0.632252803152206f,
        1f - 0.003701909074992f, 1f - 0.449610105958251f,
        // Segment D = mirror(Segment A).
        1f - 0.000000000000000f, 1f - 0.360353846483799f,
        1f - 0.000000000000000f, 1f - 0.252049456513361f,
        1f - 0.000000000000000f, 1f - 0.000000000000000f
    )
}

/**
 * A rounded rectangle with continuous-curvature (squircle) corners.
 *
 * Drop-in replacement for [RoundedCornerShape]: same arguments, same RTL behaviour, same nesting, but
 * the corner is a superellipse instead of a circular arc. See [Squircle] for the geometry.
 *
 * It extends [CornerBasedShape] — the same base class [RoundedCornerShape] uses — so it is accepted
 * anywhere Material expects a corner-based shape (including `MaterialTheme.shapes`) and inherits the
 * start/end-to-left/right mirroring rather than reimplementing it.
 *
 * The outline is an [Outline.Generic] path rather than [Outline.Rounded]: a squircle is not a round
 * rect, so it cannot use the hardware round-rect clip. Sixteen cubic segments per outline cost far
 * less than one frame of this app's sky, and Compose resolves the shape once per layout size.
 */
class SquircleShape(
    topStart: CornerSize = CornerSize(0.dp),
    topEnd: CornerSize = CornerSize(0.dp),
    bottomEnd: CornerSize = CornerSize(0.dp),
    bottomStart: CornerSize = CornerSize(0.dp)
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ): Outline {
        if (size.minDimension <= 0f) return Outline.Generic(Path())
        // The base class resolves CornerSize to pixels and clamps overlapping corners; it leaves the
        // start/end -> left/right mapping to us, so that is done here, once, exactly as
        // RoundedCornerShape does it.
        val ltr = layoutDirection == LayoutDirection.Ltr
        return Outline.Generic(
            buildSquirclePath(
                size = size,
                tl = if (ltr) topStart else topEnd,
                tr = if (ltr) topEnd else topStart,
                br = if (ltr) bottomEnd else bottomStart,
                bl = if (ltr) bottomStart else bottomEnd
            )
        )
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize
    ): SquircleShape = SquircleShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SquircleShape) return false
        return topStart == other.topStart &&
            topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd &&
            bottomStart == other.bottomStart
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return result
    }

    override fun toString(): String =
        "SquircleShape(topStart=$topStart, topEnd=$topEnd, bottomEnd=$bottomEnd, " +
            "bottomStart=$bottomStart)"
}

/**
 * Emits the outline of a squircle-cornered rectangle as primitive path commands.
 *
 * This is the geometry of [buildSquirclePath] with the `Path` taken out, so a plain JVM test can
 * assert every emitted coordinate — clamping, corner placement, tangency and the RTL mirror — with no
 * Android graphics involved. Radii are clamped to half the shorter side, exactly as
 * [RoundedCornerShape] does.
 *
 * @param tl top-left radius, [tr] top-right, [br] bottom-right, [bl] bottom-left, in pixels.
 */
internal fun emitSquircleOutline(
    size: Size,
    tl: Float,
    tr: Float,
    br: Float,
    bl: Float,
    moveTo: (Float, Float) -> Unit,
    lineTo: (Float, Float) -> Unit,
    cubicTo: (Float, Float, Float, Float, Float, Float) -> Unit,
    close: () -> Unit
) {
    val maxRadius = size.minDimension / 2f
    val rtl = tl.coerceIn(0f, maxRadius)
    val rtr = tr.coerceIn(0f, maxRadius)
    val rbr = br.coerceIn(0f, maxRadius)
    val rbl = bl.coerceIn(0f, maxRadius)

    val right = size.width
    val bottom = size.height

    if (rtl <= 0f && rtr <= 0f && rbr <= 0f && rbl <= 0f) {
        moveTo(0f, 0f)
        lineTo(right, 0f)
        lineTo(right, bottom)
        lineTo(0f, bottom)
        close()
        return
    }

    moveTo(rtl, 0f)

    // Top edge -> top-right corner -> right edge.
    lineTo(right - rtr, 0f)
    appendSquircleCorner(right - rtr, 0f, rtr, 0f, 0f, rtr, cubicTo)

    // Right edge -> bottom-right corner -> bottom edge.
    lineTo(right, bottom - rbr)
    appendSquircleCorner(right, bottom - rbr, 0f, rbr, -rbr, 0f, cubicTo)

    // Bottom edge -> bottom-left corner -> left edge.
    lineTo(rbl, bottom)
    appendSquircleCorner(rbl, bottom, -rbl, 0f, 0f, -rbl, cubicTo)

    // Left edge -> top-left corner -> back to the start.
    lineTo(0f, rtl)
    appendSquircleCorner(0f, rtl, 0f, -rtl, rtl, 0f, cubicTo)

    close()
}

/**
 * Builds the outline of a squircle-cornered rectangle as a Compose [Path], optionally shifted by
 * [origin] so the same geometry can be placed anywhere on a canvas without a matrix transform.
 */
internal fun buildSquirclePath(
    size: Size,
    tl: Float,
    tr: Float,
    br: Float,
    bl: Float,
    origin: Offset = Offset.Zero
): Path {
    val dx = origin.x
    val dy = origin.y
    val path = Path()
    emitSquircleOutline(
        size = size,
        tl = tl,
        tr = tr,
        br = br,
        bl = bl,
        moveTo = { x, y -> path.moveTo(dx + x, dy + y) },
        lineTo = { x, y -> path.lineTo(dx + x, dy + y) },
        cubicTo = { x1, y1, x2, y2, x3, y3 ->
            path.cubicTo(dx + x1, dy + y1, dx + x2, dy + y2, dx + x3, dy + y3)
        },
        close = { path.close() }
    )
    return path
}

/**
 * Appends one squircle corner.
 *
 * The canonical curve lives in a `1 x 1` corner box. [originX]/[originY] is where the corner starts
 * on its incoming edge, `A = (ax, ay)` is the box's `x` axis and `B = (bx, by)` its `y` axis, so a
 * canonical point maps as `P(X, Y) = origin + A * X + B * Y`. Passing A and B per corner lets all
 * four corners share one set of curve constants; a collapsed corner box emits nothing.
 */
private inline fun appendSquircleCorner(
    originX: Float,
    originY: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    cubicTo: (Float, Float, Float, Float, Float, Float) -> Unit
) {
    if (ax == 0f && ay == 0f && bx == 0f && by == 0f) return
    val c = Squircle.CORNER
    var i = 0
    while (i < c.size) {
        cubicTo(
            originX + ax * c[i] + bx * c[i + 1],
            originY + ay * c[i] + by * c[i + 1],
            originX + ax * c[i + 2] + bx * c[i + 3],
            originY + ay * c[i + 2] + by * c[i + 3],
            originX + ax * c[i + 4] + bx * c[i + 5],
            originY + ay * c[i + 4] + by * c[i + 5]
        )
        i += 6
    }
}

// ---------------------------------------------------------------------------------------------
// Call-site API — mirrors RoundedCornerShape's overload set, so a screen swaps
// `RoundedCornerShape(x)` for `squircleShape(x)` and gets continuous corners with no other change.
// ---------------------------------------------------------------------------------------------

/** A squircle with the same radius on all four corners. */
fun squircleShape(radius: Dp): SquircleShape =
    SquircleShape(
        topStart = CornerSize(radius),
        topEnd = CornerSize(radius),
        bottomEnd = CornerSize(radius),
        bottomStart = CornerSize(radius)
    )

/** A squircle with independent corner radii. */
fun squircleShape(
    topStart: Dp = 0.dp,
    topEnd: Dp = 0.dp,
    bottomEnd: Dp = 0.dp,
    bottomStart: Dp = 0.dp
): SquircleShape = SquircleShape(
    topStart = CornerSize(topStart),
    topEnd = CornerSize(topEnd),
    bottomEnd = CornerSize(bottomEnd),
    bottomStart = CornerSize(bottomStart)
)

/**
 * The radius a child corner needs to stay concentric with a squircle parent.
 *
 * Concentricity is about corner *boxes*, not curves: a child inset by [gap] from its parent needs a
 * corner box inset by the same [gap], so its radius is `outer - gap`. Because both corners are the
 * same shape scaled to their own box, subtracting the gap keeps the two curves parallel for any
 * exponent — which is the same rule a circular system uses, and the reason a squircle does not need
 * a fudge factor here.
 */
fun squircleConcentric(outerRadius: Dp, gap: Dp): Dp {
    val inner = outerRadius.value - gap.value
    return if (inner <= 0f) 0.dp else inner.dp
}

/** Named shapes, so a call site says what it means instead of repeating a magic radius. */
object RedShapes {

    /**
     * A capsule: a rectangle whose corners have reached half its short side, so its ends are
     * semicircular. Deliberately *not* a squircle — see the note on [Squircle].
     */
    val capsule: Shape = RoundedCornerShape(percent = 50)

    /** A perfectly round shape, for dots, avatars and thumbs that are circular by nature. */
    val circle: Shape = RoundedCornerShape(percent = 50)

    /** The standard squircle container: cards, sheets, windows and bars. */
    val container: Shape = squircleShape(RedCornerRadius.window)

    /** A squircle at the app's small-control radius. */
    val control: Shape = squircleShape(RedCornerRadius.md)

    /** A squircle at the app's inner-item radius. */
    val item: Shape = squircleShape(RedCornerRadius.sm)

    /**
     * The Material 3 shape set, built from the same corners.
     *
     * Components that do not take an explicit `shape` read `MaterialTheme.shapes`, so this is what
     * keeps menus, dialogs, sheets, text fields and chips on the app's one corner geometry. The
     * steps mirror Material's own scale around [RedCornerRadius], so an extra-small chip is still
     * tighter than a large sheet.
     */
    val material: androidx.compose.material3.Shapes = androidx.compose.material3.Shapes(
        extraSmall = squircleShape(RedCornerRadius.xs),
        small = squircleShape(RedCornerRadius.sm),
        medium = squircleShape(RedCornerRadius.md),
        large = squircleShape(RedCornerRadius.lg),
        extraLarge = squircleShape(RedCornerRadius.xl)
    )
}

/** A squircle rectangle for `DrawScope` code that cannot use a Compose `Shape`. */
fun squirclePath(
    size: Size,
    cornerRadius: Float,
    topLeft: Offset = Offset.Zero
): Path = buildSquirclePath(
    size = size,
    tl = cornerRadius,
    tr = cornerRadius,
    br = cornerRadius,
    bl = cornerRadius,
    origin = topLeft
)

/** A squircle rectangle for `DrawScope` code, positioned by [rect]. */
fun squirclePath(rect: Rect, cornerRadius: Float): Path =
    squirclePath(rect.size, cornerRadius, rect.topLeft)
