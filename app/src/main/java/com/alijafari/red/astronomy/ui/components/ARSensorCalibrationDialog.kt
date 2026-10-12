package com.alijafari.red.astronomy.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.astro_engine.CalibrationState
import com.alijafari.red.astronomy.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * RED AR Automatic Sensor Calibration Modal Dialog.
 *
 * Prompts the user with an intuitive Figure-8 (∞) motion guide when magnetic / compass sensor
 * accuracy is low, displaying real-time live sensor calibration state from OrientationProvider.
 * Auto-dismisses upon achieving adequate calibration (GOOD or EXCELLENT).
 */
@Composable
fun ARSensorCalibrationDialog(
    calibrationState: CalibrationState,
    isFa: Boolean,
    onDismiss: () -> Unit,
    onDisableAutoPrompt: (() -> Unit)? = null
) {
    val isAdequate = calibrationState == CalibrationState.GOOD || calibrationState == CalibrationState.EXCELLENT

    // Auto-dismiss after achieving adequate calibration
    LaunchedEffect(isAdequate) {
        if (isAdequate) {
            delay(1400)
            onDismiss()
        }
    }

    // Outer backdrop box (tapping outside dismisses the dialog)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .testTag("ar_sensor_calibration_dialog_scrim"),
        contentAlignment = Alignment.Center
    ) {
        // Main dialog surface (intercepts clicks so interior touches don't dismiss)
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // Intercept clicks
                )
                .testTag("ar_sensor_calibration_dialog"),
            shape = RoundedCornerShape(RedCornerRadius.xxl),
            color = RedTheme.colors.surfaceElevated.copy(alpha = 0.96f),
            border = BorderStroke(
                1.dp,
                if (isAdequate) StatusGood.copy(alpha = 0.6f) else RedTheme.colors.border
            ),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header: Icon + Title + Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isAdequate) StatusGood.copy(alpha = 0.15f) else RedTheme.colors.accentRed.copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isAdequate) Icons.Default.CheckCircle else Icons.Default.Explore,
                                    contentDescription = null,
                                    tint = if (isAdequate) StatusGood else RedTheme.colors.accentRed,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = if (isFa) "کالیبراسیون قطب‌نما و سنسورها" else "Compass Sensor Calibration",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = RedTheme.colors.textPrimary
                            )
                            Text(
                                text = if (isFa) "بهبود دقت نشانه‌روی اجرام آسمانی" else "Improves AR celestial pointing accuracy",
                                style = MaterialTheme.typography.labelSmall,
                                color = RedTheme.colors.textSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("ar_sensor_calibration_dismiss_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = RedTheme.colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                HorizontalDivider(color = RedTheme.colors.border.copy(alpha = 0.5f), thickness = 0.5.dp)

                // Figure-8 Animation Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(
                            color = RedTheme.colors.surfaceGrouped.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(RedCornerRadius.lg)
                        )
                        .testTag("ar_sensor_calibration_figure8_canvas"),
                    contentAlignment = Alignment.Center
                ) {
                    FigureEightCanvas(isAdequate = isAdequate)
                }

                // Step-by-Step Instructions
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CalibrationInstructionStep(
                        stepNum = "۱",
                        stepEn = "1",
                        textFa = "گوشی را به صورت عادی در دست بگیرید.",
                        textEn = "Hold your phone normally in hand.",
                        isFa = isFa
                    )
                    CalibrationInstructionStep(
                        stepNum = "۲",
                        stepEn = "2",
                        textFa = "دستگاه را چندین بار با حرکت ملایم به شکل عدد ۸ انگلیسی (∞) در هوا بچرخانید.",
                        textEn = "Move the phone through several smooth figure-8 patterns in the air.",
                        isFa = isFa
                    )
                    CalibrationInstructionStep(
                        stepNum = "۳",
                        stepEn = "3",
                        textFa = "حرکت را تا بهبود وضعیت کیفیت سنسور ادامه دهید.",
                        textEn = "Continue until the live calibration status improves.",
                        isFa = isFa
                    )
                }

                // Live Sensor Quality Indicator Bar
                LiveSensorQualityBadge(
                    calibrationState = calibrationState,
                    isFa = isFa
                )

                // Success / Confirmation Banner or Quick Action
                AnimatedVisibility(
                    visible = isAdequate,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(RedCornerRadius.window),
                        color = StatusGood.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, StatusGood.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ar_sensor_calibration_success_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StatusGood,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (isFa) "سنسورها با موفقیت کالیبره شدند! در حال بازگشت..." else "Sensors calibrated successfully! Returning...",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = StatusGood
                            )
                        }
                    }
                }

                // Action Buttons / Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onDisableAutoPrompt != null) {
                        TextButton(
                            onClick = {
                                onDisableAutoPrompt()
                                onDismiss()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ar_sensor_calibration_dont_show_btn")
                        ) {
                            Text(
                                text = if (isFa) "عدم نمایش خودکار" else "Don't show again",
                                style = MaterialTheme.typography.labelSmall,
                                color = RedTheme.colors.textSecondary
                            )
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ar_sensor_calibration_got_it_btn"),
                        shape = RoundedCornerShape(RedCornerRadius.md),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAdequate) StatusGood else RedTheme.colors.accentRed
                        )
                    ) {
                        Text(
                            text = if (isFa) "متوجه شدم" else "Got it",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationInstructionStep(
    stepNum: String,
    stepEn: String,
    textFa: String,
    textEn: String,
    isFa: Boolean
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = CircleShape,
            color = RedTheme.colors.accentRed.copy(alpha = 0.15f),
            modifier = Modifier.size(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (isFa) stepNum else stepEn,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RedTheme.colors.accentRed
                )
            }
        }
        Text(
            text = if (isFa) textFa else textEn,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = RedTheme.colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

private data class SensorQualityInfo(
    val statusLabelFa: String,
    val statusLabelEn: String,
    val statusColor: Color,
    val activeBars: Int
)

/**
 * Real-time dynamic visual indicator for Android sensor accuracy state.
 * Truthfully visualizes the discrete hardware states (Unreliable, Low, Medium, High).
 */
@Composable
private fun LiveSensorQualityBadge(
    calibrationState: CalibrationState,
    isFa: Boolean
) {
    val info = when (calibrationState) {
        CalibrationState.EXCELLENT -> SensorQualityInfo("عالی (دقت بالا)", "Excellent (High)", StatusGood, 4)
        CalibrationState.GOOD -> SensorQualityInfo("خوب (کافی)", "Good (Adequate)", StatusGood, 3)
        CalibrationState.POOR -> SensorQualityInfo("ضعیف (دقت کم)", "Poor (Low Accuracy)", Color(0xFFF59E0B), 2)
        CalibrationState.NEEDS_CALIBRATION -> SensorQualityInfo("نیازمند کالیبراسیون", "Needs Calibration", RedTheme.colors.accentRed, 1)
        CalibrationState.UNCALIBRATED -> SensorQualityInfo("در حال تشخیص...", "Uncalibrated", Color(0xFF94A3B8), 1)
    }

    Surface(
        shape = RoundedCornerShape(RedCornerRadius.window),
        color = info.statusColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, info.statusColor.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ar_sensor_calibration_status_badge")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = info.statusColor,
                    modifier = Modifier.size(8.dp)
                ) {}
                Column {
                    Text(
                        text = if (isFa) "وضعیت زنده سنسور: ${info.statusLabelFa}" else "Live Sensor Status: ${info.statusLabelEn}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = info.statusColor
                    )
                    Text(
                        text = if (isFa) "سنسور میدان مغناطیسی دستگاه" else "Device Geomagnetic Sensor",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = RedTheme.colors.textSecondary
                    )
                }
            }

            // 4-Stage Segmented Accuracy Meter
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (i in 1..4) {
                    val isActive = i <= info.activeBars
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(14.dp)
                            .background(
                                color = if (isActive) info.statusColor else RedTheme.colors.border.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }
    }
}

/**
 * Animated Figure-8 Lemniscate canvas demonstrating the 3D calibration motion.
 */
@Composable
private fun FigureEightCanvas(
    isAdequate: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "figure8")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "t"
    )

    val primaryColor = if (isAdequate) StatusGood else RedTheme.colors.accentRed
    val pathColor = primaryColor.copy(alpha = 0.35f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val a = w * 0.32f
        val b = h * 0.38f

        // Draw Lemniscate of Gerono (figure-8 path): x = a * sin(t), y = b * sin(t)*cos(t)
        val path = Path()
        val steps = 80
        for (i in 0..steps) {
            val t = (i.toFloat() / steps) * (2 * PI).toFloat()
            val px = cx + a * sin(t)
            val py = cy + b * sin(t) * cos(t)
            if (i == 0) {
                path.moveTo(px, py)
            } else {
                path.lineTo(px, py)
            }
        }
        path.close()

        // Draw guide path
        drawPath(
            path = path,
            color = pathColor,
            style = Stroke(
                width = 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                cap = StrokeCap.Round
            )
        )

        // Draw moving animated device particle along the figure-8
        val px = cx + a * sin(progress)
        val py = cy + b * sin(progress) * cos(progress)

        // Glowing outer halo
        drawCircle(
            color = primaryColor.copy(alpha = 0.25f),
            radius = 16.dp.toPx(),
            center = Offset(px, py)
        )
        // Core particle
        drawCircle(
            color = primaryColor,
            radius = 6.dp.toPx(),
            center = Offset(px, py)
        )
        // Center white dot
        drawCircle(
            color = Color.White,
            radius = 2.5.dp.toPx(),
            center = Offset(px, py)
        )
    }
}
