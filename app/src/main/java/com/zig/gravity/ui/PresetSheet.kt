package com.zig.gravity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.gravity.sim.Preset
import com.zig.gravity.sim.SimulationViewModel
import com.zig.gravity.ui.theme.LocalGravityColors

/**
 * §18 preset selector.
 *
 * Choosing a preset delegates to [SimulationViewModel.loadPreset], which is the single safe
 * switching path: it pauses the simulation, rebuilds the arrays from scratch, clears trails,
 * effects and queued haptics, drops the selection and the prediction, recomputes accelerations and
 * the barycentre, and re-frames the camera. Nothing here mutates simulation state directly, so a
 * switch can never leave a stale body, trail or effect behind.
 *
 * ### Scrolling
 *
 * The list **must** be inside a scroll container. It was a plain [Column], which silently clipped
 * whatever did not fit: with nine scenes it just fitted on a tall phone, and the moment the
 * catalogue grew to fifteen the last six became unreachable — worse on a short screen, in
 * landscape, or at a large font scale, where even the original nine would have overflowed. The
 * scroll modifier is applied before the padding so the bottom inset scrolls with the content and
 * the final row can be brought clear of the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSheet(vm: SimulationViewModel, onDismiss: () -> Unit) {
    val c = LocalGravityColors.current
    val fa = vm.persian
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.chrome,
        contentColor = c.onSurface,
        modifier = Modifier.testTag("preset_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .testTag("preset_list"),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (fa) "صحنه‌ها" else "Scenes",
                color = c.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (fa)
                    "با انتخاب یک صحنه، شبیه‌سازی مکث می‌کند و از نو ساخته می‌شود. برای شروع، دکمه پخش را بزن."
                else
                    "Choosing a scene pauses the simulation and rebuilds it from scratch. Press play when you are ready.",
                color = c.onSurfaceDim,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))

            for (p in Preset.entries) {
                val selected = p == vm.preset
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(squircleShape(14.dp))
                        .background(if (selected) c.accent.copy(alpha = 0.12f) else c.onSurface.copy(alpha = 0.04f))
                        .border(
                            1.dp,
                            if (selected) c.accent.copy(alpha = 0.5f) else c.chromeBorder,
                            squircleShape(14.dp)
                        )
                        .clickableTag("preset_${p.name.lowercase()}") {
                            vm.loadPreset(p)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (fa) p.titleFa else p.titleEn,
                            color = if (selected) c.accent else c.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (fa) p.noteFa else p.noteEn,
                            color = c.onSurfaceDim,
                            fontSize = 11.sp
                        )
                    }
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.size(20.dp).clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = c.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
