package com.alijafari.red.astronomy.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ar.core.HeadingFusion
import com.alijafari.red.astronomy.astro_engine.ArSensorStatus
import com.alijafari.red.astronomy.astro_engine.CalibrationState
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.ui.theme.LiquidGlassDefaults
import com.alijafari.red.astronomy.ui.theme.LiquidGlassSurface
import com.alijafari.red.astronomy.ui.theme.RedCornerRadius
import com.alijafari.red.astronomy.ui.theme.RedElevation
import com.alijafari.red.astronomy.ui.theme.RedTheme
import com.alijafari.red.astronomy.ui.theme.StatusGood
import com.alijafari.red.astronomy.ui.theme.squircleShape
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compass (figure-8) calibration window for the AR sky.
 *
 * It stays open until the user closes it (close button, "Done", or a tap outside the card) and shows
 * the real, live sensor state: Android's magnetometer accuracy, the measured field strength and dip
 * against the World Magnetic Model 2025 at the user's position, interference, the fused heading
 * uncertainty, the declination applied, the attitude source, sensor rates, the barometer used for
 * refraction and how much of the figure-8 motion has been covered.
 */
@Composable
fun ARSensorCalibrationDialog(
    calibrationState: CalibrationState,
    isFa: Boolean,
    onDismiss: () -> Unit,
    onDisableAutoPrompt: (() -> Unit)? = null,
    status: ArSensorStatus = ArSensorStatus()
) {
    val isAdequate = calibrationState == CalibrationState.GOOD || calibrationState == CalibrationState.EXCELLENT
    val accent = when (calibrationState) {
        CalibrationState.EXCELLENT, CalibrationState.GOOD -> StatusGood
        CalibrationState.POOR -> Color(0xFFF59E0B)
        CalibrationState.NEEDS_CALIBRATION -> RedTheme.colors.accentRed
        CalibrationState.UNCALIBRATED -> Color(0xFF94A3B8)
    }

    // Scrim: a tap outside the card closes the window (a user action, never a timer).
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
        LiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 720.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .testTag("ar_sensor_calibration_dialog"),
            shape = squircleShape(RedCornerRadius.xxl),
            fallbackColor = RedTheme.colors.surfaceElevated.copy(alpha = 0.97f),
            fallbackBorder = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
            fallbackShadowElevation = 16.dp,
            style = LiquidGlassDefaults.Card,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = CircleShape, color = accent.copy(alpha = 0.15f), modifier = Modifier.size(38.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isAdequate) Icons.Default.CheckCircle else Icons.Default.Explore,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = if (isFa) "کالیبراسیون قطب‌نما" else "Compass Calibration",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = RedTheme.colors.textPrimary
                            )
                            Text(
                                text = verdictText(calibrationState, isFa),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = accent
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp).testTag("ar_sensor_calibration_dismiss_btn")) {
                        Icon(Icons.Default.Close, contentDescription = if (isFa) "بستن" else "Close", tint = RedTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
                    }
                }

                HorizontalDivider(color = RedTheme.colors.border.copy(alpha = 0.5f), thickness = 0.5.dp)

                // Figure-8 guide with live coverage
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .background(RedTheme.colors.surfaceGrouped.copy(alpha = 0.5f), squircleShape(RedCornerRadius.lg))
                        .testTag("ar_sensor_calibration_figure8_canvas"),
                    contentAlignment = Alignment.Center
                ) {
                    FigureEightCanvas(color = accent, coverage = status.fusion.coverage.toFloat())
                    Text(
                        text = (if (isFa) "پوشش حرکت: " else "Motion coverage: ") + percent(status.fusion.coverage, isFa),
                        style = MaterialTheme.typography.labelSmall,
                        color = RedTheme.colors.textSecondary,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                    )
                }

                Text(
                    text = if (isFa)
                        "گوشی را چند بار به شکل عدد ۸ در هوا بچرخانید و هم‌زمان آن را حول هر سه محور کج کنید، دور از اجسام فلزی، آهن‌ربا و خودرو."
                    else
                        "Sweep the phone through several figure-8 loops while tilting it about all three axes, away from metal, magnets and cars.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RedTheme.colors.textPrimary
                )

                // Live sensor status
                LiveStatusCard(status, calibrationState, isFa)

                // Footer
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
                            modifier = Modifier.weight(1f).testTag("ar_sensor_calibration_dont_show_btn")
                        ) {
                            Text(
                                text = if (isFa) "عدم نمایش خودکار" else "Don't show automatically",
                                style = MaterialTheme.typography.labelSmall,
                                color = RedTheme.colors.textSecondary
                            )
                        }
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).testTag("ar_sensor_calibration_got_it_btn"),
                        shape = squircleShape(RedCornerRadius.md),
                        colors = ButtonDefaults.buttonColors(containerColor = if (isAdequate) StatusGood else RedTheme.colors.accentRed)
                    ) {
                        Text(
                            text = if (isFa) "تمام" else "Done",
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
private fun LiveStatusCard(status: ArSensorStatus, state: CalibrationState, isFa: Boolean) {
    val f = status.fusion
    LiquidGlassSurface(
        shape = squircleShape(RedCornerRadius.window),
        fallbackColor = RedTheme.colors.surfaceGrouped.copy(alpha = 0.55f),
        fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().testTag("ar_sensor_calibration_status_badge"),
        style = LiquidGlassDefaults.Card,
        fallbackShadowElevation = RedElevation.none,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                text = if (isFa) "وضعیت زنده حسگرها" else "Live sensor status",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = RedTheme.colors.textPrimary
            )
            AccuracyBarsRow(f.magAccuracyStatus, isFa)

            val fieldOk = f.fieldMeasuredUt.isFinite() && f.fieldExpectedUt.isFinite()
            val dev = if (fieldOk) (f.fieldMeasuredUt - f.fieldExpectedUt) / f.fieldExpectedUt * 100.0 else Double.NaN
            StatusRow(
                label = if (isFa) "شدت میدان مغناطیسی" else "Field strength",
                value = if (fieldOk) "${num(f.fieldMeasuredUt, 1, isFa)} µT / ${num(f.fieldExpectedUt, 1, isFa)} µT (${signed(dev, 0, isFa)}%)"
                else if (f.fieldMeasuredUt.isFinite()) "${num(f.fieldMeasuredUt, 1, isFa)} µT" else "—",
                level = if (!fieldOk) Level.UNKNOWN else if (abs(dev) < 6) Level.GOOD else if (abs(dev) < 15) Level.WARN else Level.BAD,
                isFa = isFa
            )
            val dipOk = f.dipMeasuredDeg.isFinite() && f.dipExpectedDeg.isFinite()
            val dDip = if (dipOk) f.dipMeasuredDeg - f.dipExpectedDeg else Double.NaN
            StatusRow(
                label = if (isFa) "زاویه شیب میدان" else "Magnetic dip",
                value = if (dipOk) "${num(f.dipMeasuredDeg, 1, isFa)}° / ${num(f.dipExpectedDeg, 1, isFa)}° (${signed(dDip, 1, isFa)}°)" else "—",
                level = if (!dipOk) Level.UNKNOWN else if (abs(dDip) < 3) Level.GOOD else if (abs(dDip) < 8) Level.WARN else Level.BAD,
                isFa = isFa
            )
            StatusRow(
                label = if (isFa) "تداخل مغناطیسی" else "Magnetic interference",
                value = if (f.interference) (if (isFa) "شناسایی شد — از فلز فاصله بگیرید" else "Detected — move away from metal")
                else (if (isFa) "ندارد" else "None"),
                level = if (f.interference) Level.BAD else if (f.mode == HeadingFusion.Mode.NONE) Level.UNKNOWN else Level.GOOD,
                isFa = isFa
            )
            StatusRow(
                label = if (isFa) "عدم قطعیت جهت" else "Heading uncertainty",
                value = if (f.headingSigmaDeg.isFinite()) "±${num(f.headingSigmaDeg, 1, isFa)}°" +
                    (if (f.rotationVectorAccuracyDeg.isFinite()) (if (isFa) " (اندروید: ±" else " (Android: ±") + num(f.rotationVectorAccuracyDeg, 1, isFa) + "°)" else "")
                else "—",
                level = if (!f.headingSigmaDeg.isFinite()) Level.UNKNOWN else if (f.headingSigmaDeg < 2) Level.GOOD else if (f.headingSigmaDeg < 5) Level.WARN else Level.BAD,
                isFa = isFa
            )
            StatusRow(
                label = if (isFa) "انحراف مغناطیسی (WMM2025)" else "Declination (WMM2025)",
                value = if (f.declinationDeg.isFinite()) "${signed(f.declinationDeg, 2, isFa)}° ±${num(f.declinationSigmaDeg, 2, isFa)}°" else (if (isFa) "در انتظار موقعیت" else "Waiting for location"),
                level = if (f.declinationDeg.isFinite()) Level.GOOD else Level.UNKNOWN,
                isFa = isFa
            )
            StatusRow(
                label = if (isFa) "منبع جهت‌یابی" else "Attitude source",
                value = modeText(f.mode, isFa),
                level = when (f.mode) {
                    HeadingFusion.Mode.FUSED -> if (f.converged) Level.GOOD else Level.WARN
                    HeadingFusion.Mode.ROTATION_VECTOR -> Level.WARN
                    HeadingFusion.Mode.NONE -> Level.UNKNOWN
                    else -> Level.WARN
                },
                isFa = isFa
            )
            StatusRow(
                label = if (isFa) "نرخ حسگرها" else "Sensor rates",
                value = (if (isFa) "وضعیت " else "attitude ") + num(status.attitudeRateHz, 0, isFa) + " Hz · " +
                    (if (isFa) "قطب‌نما " else "compass ") + num(status.magnetometerRateHz, 0, isFa) + " Hz",
                level = if (status.attitudeRateHz > 20) Level.GOOD else if (status.attitudeRateHz > 0) Level.WARN else Level.UNKNOWN,
                isFa = isFa
            )
            val atm = status.atmosphere
            StatusRow(
                label = if (isFa) "فشار و دما (شکست نور)" else "Pressure & temperature (refraction)",
                value = if (atm != null) "${num(atm.pressureHpa, 1, isFa)} hPa" + (if (atm.pressureMeasured) (if (isFa) " (فشارسنج)" else " (barometer)") else (if (isFa) " (استاندارد)" else " (standard)")) +
                    " · ${num(atm.temperatureC, 0, isFa)}°C" + (if (atm.temperatureMeasured) "" else (if (isFa) " (استاندارد)" else " (standard)"))
                else "—",
                level = if (atm?.pressureMeasured == true) Level.GOOD else Level.UNKNOWN,
                isFa = isFa
            )
            if (abs(status.alignmentOffsetDeg) > 0.0) {
                StatusRow(
                    label = if (isFa) "تصحیح تراز ستاره‌ای" else "Star-alignment correction",
                    value = "${signed(status.alignmentOffsetDeg, 1, isFa)}°",
                    level = Level.GOOD,
                    isFa = isFa
                )
            }
            Text(
                text = sensorsLine(status, isFa),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = RedTheme.colors.textSecondary
            )
            if (state == CalibrationState.NEEDS_CALIBRATION || f.interference) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                    Text(
                        text = if (isFa) "میدان اندازه‌گیری‌شده با مدل جهانی مغناطیسی همخوانی ندارد؛ جهت‌ها ممکن است چند درجه خطا داشته باشند."
                        else "The measured field disagrees with the World Magnetic Model here; directions may be off by several degrees.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF59E0B)
                    )
                }
            }
        }
    }
}

private enum class Level { GOOD, WARN, BAD, UNKNOWN }

@Composable
private fun levelColor(level: Level): Color = when (level) {
    Level.GOOD -> StatusGood
    Level.WARN -> Color(0xFFF59E0B)
    Level.BAD -> RedTheme.colors.accentRed
    Level.UNKNOWN -> Color(0xFF94A3B8)
}

@Composable
private fun StatusRow(label: String, value: String, level: Level, isFa: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(0.45f)) {
            Surface(shape = CircleShape, color = levelColor(level), modifier = Modifier.size(7.dp)) {}
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = RedTheme.colors.textSecondary)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = RedTheme.colors.textPrimary,
            modifier = Modifier.weight(0.55f)
        )
    }
}

@Composable
private fun AccuracyBarsRow(status: Int, isFa: Boolean) {
    val (label, bars, level) = when (status) {
        3 -> Triple(if (isFa) "بالا" else "High", 4, Level.GOOD)
        2 -> Triple(if (isFa) "متوسط" else "Medium", 3, Level.GOOD)
        1 -> Triple(if (isFa) "کم" else "Low", 2, Level.WARN)
        0 -> Triple(if (isFa) "نامعتبر" else "Unreliable", 1, Level.BAD)
        else -> Triple(if (isFa) "در حال سنجش…" else "Measuring…", 0, Level.UNKNOWN)
    }
    val color = levelColor(level)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = (if (isFa) "دقت قطب‌نمای اندروید: " else "Android compass accuracy: ") + label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 1..4) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(14.dp)
                        .background(if (i <= bars) color else RedTheme.colors.border.copy(alpha = 0.3f), squircleShape(2.dp))
                )
            }
        }
    }
}

private fun verdictText(state: CalibrationState, isFa: Boolean): String = when (state) {
    CalibrationState.EXCELLENT -> if (isFa) "عالی — آماده رصد" else "Excellent — ready to observe"
    CalibrationState.GOOD -> if (isFa) "خوب — دقت کافی" else "Good — adequate accuracy"
    CalibrationState.POOR -> if (isFa) "ضعیف — حرکت شکل ۸ را ادامه دهید" else "Poor — keep doing figure-8s"
    CalibrationState.NEEDS_CALIBRATION -> if (isFa) "نیازمند کالیبراسیون" else "Calibration needed"
    CalibrationState.UNCALIBRATED -> if (isFa) "در حال سنجش حسگرها…" else "Measuring sensors…"
}

private fun modeText(mode: HeadingFusion.Mode, isFa: Boolean): String = when (mode) {
    HeadingFusion.Mode.FUSED -> if (isFa) "ژیروسکوپ + قطب‌نمای کنترل‌شده با WMM" else "Gyro + WMM-gated compass"
    HeadingFusion.Mode.ROTATION_VECTOR -> if (isFa) "بردار چرخش اندروید" else "Android rotation vector"
    HeadingFusion.Mode.GYRO_ONLY -> if (isFa) "فقط ژیروسکوپ (تراز ستاره‌ای لازم است)" else "Gyro only (star alignment needed)"
    HeadingFusion.Mode.ACCEL_MAG -> if (isFa) "شتاب‌سنج + قطب‌نما (بدون ژیروسکوپ)" else "Accelerometer + compass (no gyro)"
    HeadingFusion.Mode.NONE -> if (isFa) "در انتظار داده" else "Waiting for data"
}

private fun sensorsLine(s: ArSensorStatus, isFa: Boolean): String {
    fun mark(b: Boolean) = if (b) "✓" else "✗"
    return if (isFa) {
        "ژیروسکوپ ${mark(s.hasGyroscope)}  قطب‌نما ${mark(s.hasMagnetometer)}  شتاب‌سنج ${mark(s.hasAccelerometer)}  فشارسنج ${mark(s.hasBarometer)}  دماسنج ${mark(s.hasThermometer)}"
    } else {
        "Gyroscope ${mark(s.hasGyroscope)}  Magnetometer ${mark(s.hasMagnetometer)}  Accelerometer ${mark(s.hasAccelerometer)}  Barometer ${mark(s.hasBarometer)}  Thermometer ${mark(s.hasThermometer)}"
    }
}

private fun num(v: Double, decimals: Int, isFa: Boolean): String {
    if (!v.isFinite()) return "—"
    val s = String.format(Locale.US, "%.${decimals}f", v)
    return if (isFa) TimeEngine.formatPersianNumbers(s) else s
}

private fun signed(v: Double, decimals: Int, isFa: Boolean): String {
    if (!v.isFinite()) return "—"
    val s = String.format(Locale.US, "%+.${decimals}f", v)
    return if (isFa) TimeEngine.formatPersianNumbers(s) else s
}

private fun percent(v: Double, isFa: Boolean): String {
    val s = String.format(Locale.US, "%.0f%%", (v * 100.0).coerceIn(0.0, 100.0))
    return if (isFa) TimeEngine.formatPersianNumbers(s) else s
}

/** Animated figure-8 (lemniscate of Gerono) with a coverage arc. */
@Composable
private fun FigureEightCanvas(color: Color, coverage: Float) {
    val transition = rememberInfiniteTransition(label = "figure8")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 2800, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "t"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f - 6.dp.toPx()
        val a = w * 0.30f
        val b = h * 0.34f
        val path = Path()
        val steps = 96
        for (i in 0..steps) {
            val t = (i.toFloat() / steps) * (2 * PI).toFloat()
            val px = cx + a * sin(t)
            val py = cy + b * sin(t) * cos(t)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = color.copy(alpha = 0.35f),
            style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f), cap = StrokeCap.Round)
        )
        // Coverage arc around the right lobe.
        val r = 16.dp.toPx()
        drawArc(
            color = color.copy(alpha = 0.25f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(w - r * 2 - 12.dp.toPx(), 10.dp.toPx()),
            size = Size(r * 2, r * 2),
            style = Stroke(width = 3.dp.toPx())
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * coverage.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(w - r * 2 - 12.dp.toPx(), 10.dp.toPx()),
            size = Size(r * 2, r * 2),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
        val px = cx + a * sin(progress)
        val py = cy + b * sin(progress) * cos(progress)
        drawCircle(color = color.copy(alpha = 0.25f), radius = 16.dp.toPx(), center = Offset(px, py))
        drawCircle(color = color, radius = 6.dp.toPx(), center = Offset(px, py))
        drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = Offset(px, py))
    }
}
