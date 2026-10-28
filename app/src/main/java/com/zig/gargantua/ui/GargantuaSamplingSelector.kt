package com.zig.gargantua.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.gargantua.renderer.GargantuaCoarseSampling
import com.zig.gargantua.renderer.GargantuaSurfaceView

/**
 * Temporary uniform sampling diagnostic control.
 *
 * This intentionally lives in the main source set so it is available in the release APK while the
 * physical-device sampling investigation is active. It only edits the existing render state.
 */
@Composable
internal fun GargantuaSamplingSelector(
    surfaceView: GargantuaSurfaceView?,
    modifier: Modifier = Modifier
) {
    val state = surfaceView?.renderer?.stateHolder?.getState()
    val currentMode = GargantuaCoarseSampling.sanitizeBlockSize(
        state?.debugCoarseSamplingBlockSize ?: GargantuaCoarseSampling.BASELINE_BLOCK_SIZE
    )
    val selectedMode = remember { mutableStateOf(currentMode) }
    LaunchedEffect(currentMode) {
        selectedMode.value = currentMode
    }
    val modes = GargantuaCoarseSampling.supportedBlockSizes().sortedDescending()

    Surface(
        modifier = modifier
            .border(1.dp, Color(0x66446688), squircleShape(8.dp))
            .testTag("gargantua_sampling_selector"),
        color = Color(0xDD101722),
        contentColor = Color.White,
        shape = squircleShape(8.dp),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = "SAMPLE",
                color = Color(0xFF9FB5D1),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            modes.forEach { mode ->
                TextButton(
                    onClick = {
                        selectedMode.value = mode
                        surfaceView?.renderer?.stateHolder?.updateState { current ->
                            if (current.debugCoarseSamplingBlockSize == mode) {
                                current
                            } else {
                                current.copy(debugCoarseSamplingBlockSize = mode)
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minWidth = 0.dp, minHeight = 44.dp)
                        .background(
                            color = if (selectedMode.value == mode) Color(0xCCEF8F2F) else Color.Transparent,
                            shape = squircleShape(5.dp)
                        )
                        .testTag("gargantua_sampling_mode_$mode"),
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "${mode}×${mode}",
                        color = if (selectedMode.value == mode) Color.White else Color(0xFFD7E3F4),
                        fontSize = 10.sp,
                        fontWeight = if (selectedMode.value == mode) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
