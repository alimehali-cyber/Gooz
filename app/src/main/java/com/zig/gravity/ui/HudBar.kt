package com.zig.gravity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.gravity.physics.EngineConstants
import com.zig.gravity.ui.theme.LocalGravityColors
import com.zig.gravity.util.PersianDigits

/**
 * §3.11 HUD — exactly the locked control set and nothing else persistent:
 * play/pause · the speed ladder · reset · trails · teaching · table colour · camera · add.
 *
 * The old dark/light theme toggle is gone: the two themes it switched between live on as the
 * `charcoal` and `paper` table surfaces, and the palette button now opens the surface picker (§6).
 */
@Composable
fun HudBar(
    paused: Boolean,
    speedIndex: Int,
    trailsVisible: Boolean,
    teachingEnabled: Boolean,
    persian: Boolean,
    onTogglePlay: () -> Unit,
    onSpeed: (Int) -> Unit,
    onReset: () -> Unit,
    onToggleTrails: () -> Unit,
    onToggleTeaching: () -> Unit,
    onOpenTableSurface: () -> Unit,
    cameraPanelOpen: Boolean = false,
    onToggleCameraPanel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = LocalGravityColors.current
    Row(
        modifier = modifier
            .clip(squircleShape(22.dp))
            .background(c.chrome)
            .border(1.dp, c.chromeBorder, squircleShape(22.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("gravity_hud"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        HudIcon(
            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            active = !paused,
            description = if (persian) "پخش یا مکث" else "Play or pause",
            tag = "hud_play_pause",
            onClick = onTogglePlay
        )

        Row(
            modifier = Modifier
                .clip(squircleShape(14.dp))
                .background(c.onSurface.copy(alpha = 0.05f))
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in EngineConstants.SPEEDS.indices) {
                val selected = i == speedIndex
                Box(
                    modifier = Modifier
                        .clip(squircleShape(11.dp))
                        .background(if (selected) c.accent.copy(alpha = 0.20f) else Color.Transparent)
                        .clickableTag("hud_speed_$i") { onSpeed(i) }
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = speedLabel(i, persian),
                        color = if (selected) c.accent else c.onSurfaceDim,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        HudIcon(Icons.Filled.Refresh, false, if (persian) "شروع دوباره" else "Reset", "hud_reset", onReset)
        HudIcon(Icons.Filled.Timeline, trailsVisible, if (persian) "رد حرکت" else "Trails", "hud_trails", onToggleTrails)
        HudIcon(Icons.Filled.School, teachingEnabled, if (persian) "آموزش" else "Teaching", "hud_teaching", onToggleTeaching)
        HudIcon(
            icon = Icons.Filled.Palette,
            active = false,
            description = if (persian) "رنگ میز" else "Table colour",
            tag = "hud_btn_table",
            onClick = onOpenTableSurface
        )
        HudIcon(
            icon = Icons.Filled.CenterFocusStrong,
            active = cameraPanelOpen,
            description = if (persian) "دوربین" else "Camera",
            tag = "hud_camera",
            onClick = onToggleCameraPanel
        )
    }
}

/** §3.6b: speeds are labelled honestly — they multiply simulated time, never the timestep. */
private fun speedLabel(index: Int, persian: Boolean): String {
    val raw = EngineConstants.SPEED_LABELS[index]
    return if (persian) PersianDigits.convert(raw.replace("x", "×")) else raw
}

@Composable
private fun HudIcon(
    icon: ImageVector,
    active: Boolean,
    description: String,
    tag: String,
    onClick: () -> Unit,
    emphasised: Boolean = false
) {
    val c = LocalGravityColors.current
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                when {
                    emphasised -> c.accent.copy(alpha = 0.16f)
                    active -> c.accent.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .clickableTag(tag, onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active || emphasised) c.accent else c.onSurfaceDim,
            modifier = Modifier.size(19.dp)
        )
    }
}
