package com.zig.gravity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.gravity.physics.EngineConstants
import com.zig.gravity.sim.BodyCatalog
import com.zig.gravity.sim.SimulationViewModel
import com.zig.gravity.ui.theme.LocalGravityColors
import com.zig.gravity.util.SandboxFormat

/**
 * §3.11 "Long-press empty / HUD +" -> the add catalog, with the 20-body cap and a friendly
 * Persian-first notice when the table is full.
 *
 * Every entry creates a **real simulation body** (unique id, type, mass, size, position,
 * velocity, acceleration, flags) — never a decoration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBodySheet(
    vm: SimulationViewModel,
    sceneX: Double?,
    sceneY: Double?,
    onDismiss: () -> Unit
) {
    val c = LocalGravityColors.current
    val fa = vm.persian
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val used = vm.snapshot.n

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.chrome,
        contentColor = c.onSurface,
        modifier = Modifier.testTag("add_body_sheet")
    ) {
        // The catalogue grid scrolls on its own, but the header above it and the scale note below
        // it can still be pushed off a short screen or a large font scale. The outer scroller
        // guarantees the last line is reachable; it only engages when the content really overflows.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (fa) "چه چیزی روی میز بگذاریم؟" else "What shall we put on the table?",
                    color = c.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = SandboxFormat.integer(used.toLong(), fa) + " / " +
                            SandboxFormat.integer(EngineConstants.MAX_BODIES.toLong(), fa),
                    color = if (used >= EngineConstants.MAX_BODIES) c.accent else c.onSurfaceDim,
                    fontSize = 12.sp
                )
            }

            if (used >= EngineConstants.MAX_BODIES) {
                Text(
                    text = if (fa)
                        "میز آزمایش پر است — حداکثر ۲۰ جسم جا می‌شود. برای افزودن جسم تازه، اول یکی را بردار."
                    else
                        "The experiment table is full — 20 bodies maximum. Remove one to make room.",
                    color = c.accent,
                    fontSize = 12.sp
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(BodyCatalog.all) { entry ->
                    val needed = if (entry.isPair) 2 else 1
                    val enabled = used + needed <= EngineConstants.MAX_BODIES
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(squircleShape(14.dp))
                            .background(c.onSurface.copy(alpha = if (enabled) 0.05f else 0.02f))
                            .border(1.dp, c.chromeBorder, squircleShape(14.dp))
                            .clickableTag("add_${entry.key}") {
                                if (vm.addFromCatalog(entry.key, sceneX, sceneY)) onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size((entry.dp.coerceIn(8.0, 22.0)).dp)
                                .clip(CircleShape)
                                .background(
                                    c.bodyTone(entry.colorArgb)
                                        .copy(alpha = if (enabled) 1f else 0.4f)
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (fa) entry.nameFa else entry.nameEn,
                                color = if (enabled) c.onSurface else c.onSurfaceDim,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (entry.isPair) {
                                    if (fa) "یک جفت دهانه" else "a linked pair"
                                } else {
                                    SandboxFormat.mass(entry.massKg, fa)
                                },
                                color = c.onSurfaceDim,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            Text(
                text = if (fa)
                    "فاصله‌ها و اندازه‌ها در این میز به مقیاس واقعی نیستند تا همه‌چیز دیده شود؛ اما جرم‌ها، ثابت گرانش و سرعت‌ها واقعی‌اند، پس رفتاری که می‌بینی درست است."
                else
                    "Distances and sizes on this table are not to scale, so everything stays visible. Masses, G and velocities are real, so the behaviour you see is genuine.",
                color = c.onSurfaceDim,
                fontSize = 10.sp,
                textAlign = TextAlign.Start
            )
        }
    }
}
