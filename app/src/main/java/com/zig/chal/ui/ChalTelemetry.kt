package com.zig.chal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ui.theme.RedCornerRadius
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.chal.render.ChalRenderer
import java.util.Locale

/** Wrapping, readable HUD. Rendering scale and quality describe the actual backend, not a request. */
@Composable
fun ChalTelemetry(snapshot: ChalRenderer.ChalSnapshot, isPersian: Boolean, isXapkRenderer: Boolean = false,
    modifier: Modifier = Modifier, compact: Boolean = true) {
    val mass = snapshot.params.mass.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val items = buildList {
        add(Triple(if (isPersian) "فریم/ثانیه" else "FPS", if (snapshot.params.paused) {
            if (isPersian) "متوقف" else "Paused"
        } else snapshot.currentFps.toString(), ""))
        add(Triple(if (isPersian) "کیفیت" else "Quality", snapshot.quality.uiLabel(isPersian, isXapkRenderer), ""))
        add(Triple(if (isPersian) "وضوح واقعی" else "Actual scale", "${Math.round(snapshot.actualRenderScale * 100)}%", ""))
        if (!compact) {
            add(Triple(if (isPersian) "افق r₊" else "Horizon r₊", fixedWidth(snapshot.eventHorizonRadius / mass, 0, 2), "r_g"))
            add(Triple(if (isPersian) "مدار فوتونی" else "Photon orbit", fixedWidth(snapshot.photonSphereRadius / mass, 0, 2), "r_g"))
            add(Triple(if (isPersian) "آخرین مدار پایدار" else "ISCO", fixedWidth(snapshot.iscoRadius / mass, 0, 2), "r_g"))
            if (!isXapkRenderer) {
                add(Triple(if (isPersian) "نرخ ساعت (تخمینی)" else "Clock rate (approx.)", fixedWidth(snapshot.timeDilation, 0, 3), "×"))
                add(Triple(if (isPersian) "انتقال به سرخ (تخمینی)" else "Redshift (approx.)", "z=${fixedWidth(snapshot.redshift, 0, 3)}", ""))
            }
            add(Triple(if (isPersian) "فاصلهٔ زمانی فریم‌ها" else "Frame interval", if (snapshot.params.paused) "—" else fixedWidth(snapshot.frameTimeMs, 0, 1), "ms"))
            if (!isXapkRenderer) add(Triple(if (isPersian) "هدف نرخ فریم" else "Pacing target", snapshot.targetFps.toString(), if (isPersian) "فریم/ثانیه" else "FPS"))
        }
    }
    Column(modifier.testTag("chal_telemetry"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChalWrappingRow(Modifier.fillMaxWidth().clip(squircleShape(RedCornerRadius.sm))
            .background(Color.Black.copy(alpha = 0.76f)).padding(8.dp)) {
            items.forEach { (label, value, unit) ->
                Column(Modifier.widthIn(min = 72.dp).semantics(mergeDescendants = true) { contentDescription = "$label: $value $unit" },
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, color = Color.White.copy(alpha = 0.87f), fontSize = 12.sp)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(value, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            if (unit.isNotEmpty()) Text(unit, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (!compact && !snapshot.params.paused && snapshot.budgetUsage > 0.0) {
            val budget = snapshot.budgetUsage.takeIf { it.isFinite() } ?: 0.0
            Text((if (isPersian) "فاصلهٔ فریم نسبت به هدف: " else "Frame interval / pacing target: ") + "${Math.round(budget)}%",
                color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            Box(Modifier.fillMaxWidth().height(5.dp).clip(squircleShape(8.dp)).background(Color.White.copy(alpha = 0.15f))) {
                Spacer(Modifier.fillMaxWidth((budget / 100.0).coerceIn(0.0, 1.0).toFloat()).height(5.dp)
                    .background(if (budget > 105) Color(0xFFE8A16E) else Color.White.copy(alpha = 0.7f)))
            }
        }
    }
}

internal fun fixedWidth(value: Double, totalWidth: Int, decimals: Int): String {
    if (value.isNaN()) return "—"
    if (value.isInfinite()) return if (value > 0) "∞" else "−∞"
    return String.format(Locale.US, "%.${decimals}f", value).padStart(totalWidth, ' ')
}

/** Stable Layout API avoids the FlowRow ABI mismatch between this app's and test runner's BOMs. */
@Composable
private fun ChalWrappingRow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val horizontalGap = 16.dp.roundToPx()
        val verticalGap = 8.dp.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
            else placeables.sumOf { it.width } + horizontalGap * (placeables.size - 1).coerceAtLeast(0)
        val positions = ArrayList<IntOffset>(placeables.size)
        var x = 0
        var y = 0
        var rowHeight = 0
        for (item in placeables) {
            if (x > 0 && x + item.width > width) { x = 0; y += rowHeight + verticalGap; rowHeight = 0 }
            positions += IntOffset(x, y)
            rowHeight = maxOf(rowHeight, item.height)
            x += item.width + horizontalGap
        }
        layout(constraints.constrainWidth(width), constraints.constrainHeight(y + rowHeight)) {
            placeables.forEachIndexed { i, item -> item.placeRelative(positions[i].x, positions[i].y) }
        }
    }
}
