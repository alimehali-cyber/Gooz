package com.zig.gravity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.gravity.sim.SimulationViewModel
import com.zig.gravity.ui.theme.GravitySurface
import com.zig.gravity.ui.theme.LocalGravityColors
import com.zig.gravity.ui.theme.TableSurfaces
import com.zig.gravity.ui.theme.brushFor

/**
 * §6 — the table-colour picker.
 *
 * Seven material finishes in a three-column grid. Each swatch miniature-renders the *real* surface
 * spec through the same [brushFor] and [SurfacePrint] the canvas uses, so a swatch can never drift
 * from the table it stands for. Choosing one applies instantly through
 * [SimulationViewModel.setTableSurface] and closes the sheet.
 *
 * The content sits in a scroll container for the same reason every other sheet here does: seven
 * swatches plus a header can overflow a short screen, and a row that cannot be reached cannot be
 * tapped. The bottom inset is applied *after* the scroll modifier so the last row clears the
 * navigation bar.
 */
private const val COLUMNS = 3

/** 6–8 tiny static dots on a starred swatch: enough to read as a print, few enough to stay calm. */
private const val SWATCH_DOTS = 7

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableSurfaceSheet(vm: SimulationViewModel, onDismiss: () -> Unit) {
    val c = LocalGravityColors.current
    val fa = vm.persian
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.chrome,
        contentColor = c.onSurface,
        modifier = Modifier.testTag("table_surface_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .testTag("table_surface_list"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (fa) "رنگ میز" else "Table colour",
                color = c.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (fa)
                    "سطح میز را انتخاب کن؛ فوراً عوض می‌شود و رنگ بدنه، سایه‌ها و نوار ابزار با آن تنظیم می‌شوند."
                else
                    "Pick a table surface. It applies at once, and the chrome, shadows and trails follow it.",
                color = c.onSurfaceDim,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(2.dp))

            // The catalog is enumerated rather than listed by hand, so a new surface can never be
            // added to the catalog and forgotten in the sheet.
            for (row in TableSurfaces.all.chunked(COLUMNS)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (surface in row) {
                        SurfaceSwatch(
                            surface = surface,
                            selected = surface.key == vm.tableSurface,
                            fa = fa,
                            onClick = {
                                vm.setTableSurface(surface.key)
                                onDismiss()
                            }
                        )
                    }
                    // Keep the three-column rhythm on the last, short row.
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SurfaceSwatch(
    surface: GravitySurface,
    selected: Boolean,
    fa: Boolean,
    onClick: () -> Unit
) {
    val c = LocalGravityColors.current
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(squircleShape(16.dp))
            .background(if (selected) c.accent.copy(alpha = 0.12f) else c.onSurface.copy(alpha = 0.04f))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) c.accent else c.chromeBorder,
                squircleShape(16.dp)
            )
            .clickableTag("table_surface_${surface.key}", onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(squircleShape(16.dp))
                .testTag("table_swatch_${surface.key}")
                .drawWithCache {
                    val gradient = surface.gradient.brushFor(size)
                    val print = surface.pattern?.let { SurfacePrint.of(it, size, this, SWATCH_DOTS) }
                    onDrawBehind {
                        drawRect(gradient)
                        print?.draw(this)
                    }
                }
        )
        Text(
            text = surface.title(fa),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) c.accent else c.onSurfaceDim,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
