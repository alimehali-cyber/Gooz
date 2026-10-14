package com.alijafari.red.astronomy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alijafari.red.astronomy.astro_engine.EclipseEngine
import com.alijafari.red.astronomy.astro_engine.EclipseVisibility
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.ui.theme.RedCornerRadius
import com.alijafari.red.astronomy.ui.theme.LiquidGlassDefaults
import com.alijafari.red.astronomy.ui.theme.LiquidGlassSurface
import com.alijafari.red.astronomy.ui.theme.RedElevation
import com.alijafari.red.astronomy.ui.theme.StatusExcellent
import com.alijafari.red.astronomy.ui.theme.StatusGood
import com.alijafari.red.astronomy.ui.theme.StatusWarning
import com.alijafari.red.astronomy.util.toPersianDigits
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun EclipseDetailModal(
    detailedInfo: EclipseEngine.DetailedEclipseInfo,
    language: AppLanguage,
    onDismiss: () -> Unit
) {
    val isFa = language == AppLanguage.PERSIAN
    val event = detailedInfo.event
    val result = detailedInfo.result

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        LiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 16.dp)
                .clip(RoundedCornerShape(RedCornerRadius.window))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(RedCornerRadius.window)
                )
                .testTag("eclipse_detail_dialog"),
            fallbackColor = MaterialTheme.colorScheme.surface,
            style = LiquidGlassDefaults.Card,
            fallbackShadowElevation = RedElevation.none,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Bar with Icon, Title, and Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (event.isSolar) Icons.Outlined.WbSunny else Icons.Outlined.NightsStay,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column {
                            Text(
                                text = if (isFa) result.localNameFa else result.localNameEn,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isFa) result.formattedDateFa else result.formattedDateEn,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_eclipse_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                // Countdown Badge & Local Visibility Banner
                val bannerBg = if (result.isLocallyVisible) StatusExcellent.copy(alpha = 0.15f) else StatusWarning.copy(alpha = 0.15f)
                val bannerBorder = if (result.isLocallyVisible) StatusExcellent.copy(alpha = 0.5f) else StatusWarning.copy(alpha = 0.5f)
                val bannerTextColor = if (result.isLocallyVisible) StatusExcellent else StatusWarning

                LiquidGlassSurface(
                    shape = RoundedCornerShape(RedCornerRadius.window),
                    fallbackColor = bannerBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, bannerBorder, RoundedCornerShape(RedCornerRadius.window)),
                    style = LiquidGlassDefaults.Card,
                    fallbackShadowElevation = RedElevation.none,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = bannerTextColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (result.isLocallyVisible) {
                                        if (isFa) "قابل رصد از موقعیت شما" else "Visible from your Location"
                                    } else {
                                        if (isFa) "عدم رصد مستقیم در موقعیت شما" else "Not Directly Visible"
                                    },
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = bannerTextColor
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bannerTextColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                val remainingStr = when {
                                    detailedInfo.daysRemaining <= 0 -> if (isFa) "امروز / هم‌اکنون" else "Today / Active"
                                    isFa -> "${detailedInfo.daysRemaining} روز مانده".toPersianDigits()
                                    else -> "${detailedInfo.daysRemaining}d away"
                                }
                                Text(
                                    text = remainingStr,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = bannerTextColor
                                )
                            }
                        }

                        Text(
                            text = if (isFa) result.localVisibilityTextFa else result.localVisibilityTextEn,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = bannerTextColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (result.isLocallyVisible) {
                                        if (isFa) "قابل رصد از موقعیت شما" else "Visible from your Location"
                                    } else {
                                        if (isFa) "عدم رصد مستقیم در موقعیت شما" else "Not Directly Visible"
                                    },
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = bannerTextColor
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bannerTextColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                val remainingStr = when {
                                    detailedInfo.daysRemaining <= 0 -> if (isFa) "امروز / هم‌اکنون" else "Today / Active"
                                    isFa -> "${detailedInfo.daysRemaining} روز مانده".toPersianDigits()
                                    else -> "${detailedInfo.daysRemaining}d away"
                                }
                                Text(
                                    text = remainingStr,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = bannerTextColor
                                )
                            }
                        }

                        Text(
                            text = if (isFa) result.localVisibilityTextFa else result.localVisibilityTextEn,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Local Timings Grid (Start, Peak, End, Duration)
                Text(
                    text = if (isFa) "⏱️ زمان‌بندی دقیق محلی (موقعیت شما):" else "⏱️ Exact Local Timing (Your Location):",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                val startDisplay = if (isFa) detailedInfo.localStartTimeStr.toPersianDigits() else detailedInfo.localStartTimeStr
                val peakDisplay = if (isFa) detailedInfo.localPeakTimeStr.toPersianDigits() else detailedInfo.localPeakTimeStr
                val endDisplay = if (isFa) detailedInfo.localEndTimeStr.toPersianDigits() else detailedInfo.localEndTimeStr

                LiquidGlassSurface(
                    shape = RoundedCornerShape(RedCornerRadius.window),
                    fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                    style = LiquidGlassDefaults.Card,
                    fallbackShadowElevation = RedElevation.none,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TimingItem(
                                label = if (isFa) "شروع گرفتگی:" else "Eclipse Start:",
                                time = startDisplay
                            )
                            TimingItem(
                                label = if (isFa) "اوج گرفتگی (پیک):" else "Maximum Peak:",
                                time = peakDisplay,
                                isHighlight = true
                            )
                            TimingItem(
                                label = if (isFa) "پایان گرفتگی:" else "Eclipse End:",
                                time = endDisplay
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isFa) {
                                    "مدت قابل رصد در موقعیت شما: ${detailedInfo.durationTextFa}"
                                } else {
                                    "Local Observable Duration: ${detailedInfo.durationTextEn}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (event.durationTotalSeconds > 0 &&
                            (event.type == EclipseEngine.EclipseType.TOTAL_SOLAR ||
                                event.type == EclipseEngine.EclipseType.ANNULAR_SOLAR ||
                                event.type == EclipseEngine.EclipseType.TOTAL_LUNAR)
                        ) {
                            val totMin = event.durationTotalSeconds / 60
                            val totSec = event.durationTotalSeconds % 60
                            val centralDurLabel = when (event.type) {
                                EclipseEngine.EclipseType.ANNULAR_SOLAR -> if (isFa) {
                                    "مدت حلقه آتش در اوج جهانی: ${totMin} دقیقه و ${totSec} ثانیه".toPersianDigits()
                                } else {
                                    "Max Annularity Duration: ${totMin}m ${totSec}s"
                                }
                                else -> if (isFa) {
                                    "مدت گرفتگی کامل در اوج جهانی: ${totMin} دقیقه و ${totSec} ثانیه".toPersianDigits()
                                } else {
                                    "Max Totality Duration: ${totMin}m ${totSec}s"
                                }
                            }
                            Text(
                                text = centralDurLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TimingItem(
                                label = if (isFa) "شروع گرفتگی:" else "Eclipse Start:",
                                time = startDisplay
                            )
                            TimingItem(
                                label = if (isFa) "اوج گرفتگی (پیک):" else "Maximum Peak:",
                                time = peakDisplay,
                                isHighlight = true
                            )
                            TimingItem(
                                label = if (isFa) "پایان گرفتگی:" else "Eclipse End:",
                                time = endDisplay
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isFa) {
                                    "مدت قابل رصد در موقعیت شما: ${detailedInfo.durationTextFa}"
                                } else {
                                    "Local Observable Duration: ${detailedInfo.durationTextEn}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (event.durationTotalSeconds > 0 &&
                            (event.type == EclipseEngine.EclipseType.TOTAL_SOLAR ||
                                event.type == EclipseEngine.EclipseType.ANNULAR_SOLAR ||
                                event.type == EclipseEngine.EclipseType.TOTAL_LUNAR)
                        ) {
                            val totMin = event.durationTotalSeconds / 60
                            val totSec = event.durationTotalSeconds % 60
                            val centralDurLabel = when (event.type) {
                                EclipseEngine.EclipseType.ANNULAR_SOLAR -> if (isFa) {
                                    "مدت حلقه آتش در اوج جهانی: ${totMin} دقیقه و ${totSec} ثانیه".toPersianDigits()
                                } else {
                                    "Max Annularity Duration: ${totMin}m ${totSec}s"
                                }
                                else -> if (isFa) {
                                    "مدت گرفتگی کامل در اوج جهانی: ${totMin} دقیقه و ${totSec} ثانیه".toPersianDigits()
                                } else {
                                    "Max Totality Duration: ${totMin}m ${totSec}s"
                                }
                            }
                            Text(
                                text = centralDurLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Sky Position & Obscuration Coverage
                Text(
                    text = if (isFa) "موقعیت در آسمان و درصد پوشش:" else "Sky Position & Local Obscuration:",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                val isPenumbralLunar = event.type == EclipseEngine.EclipseType.PENUMBRAL_LUNAR
                val penumbralPercent = if (isPenumbralLunar && result.isLocallyVisible) {
                    (result.localMagnitude * 100.0).roundToInt().coerceIn(1, 100)
                } else 0
                val displayPercent = if (isPenumbralLunar) penumbralPercent else detailedInfo.obscurationPercent
                val barColor = if (isPenumbralLunar) StatusGood else MaterialTheme.colorScheme.primary

                LiquidGlassSurface(
                    shape = RoundedCornerShape(RedCornerRadius.window),
                    fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                    style = LiquidGlassDefaults.Card,
                    fallbackShadowElevation = RedElevation.none,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isPenumbralLunar) {
                                    if (isFa) "پوشش نیم‌سایه (۰٪ سایه تاریک):" else "Penumbral Shading (0% Umbral):"
                                } else {
                                    if (isFa) "درصد پوشش گرفتگی:" else "Local Obscuration:"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val obscurationStr = if (isFa) "%$displayPercent".toPersianDigits() else "$displayPercent%"
                            Text(
                                text = obscurationStr,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = barColor
                            )
                        }

                        LinearProgressIndicator(
                            progress = (displayPercent / 100f).coerceIn(0f, 1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = barColor,
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val altStr = if (isFa) "ارتفاع: ${detailedInfo.targetAltDeg} درجه".toPersianDigits() else "Alt: ${detailedInfo.targetAltDeg}°"
                            val azStr = if (isFa) "سمت: ${detailedInfo.targetAzDeg} درجه".toPersianDigits() else "Az: ${detailedInfo.targetAzDeg}°"
                            Text(text = altStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = azStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isPenumbralLunar) {
                                    if (isFa) "پوشش نیم‌سایه (۰٪ سایه تاریک):" else "Penumbral Shading (0% Umbral):"
                                } else {
                                    if (isFa) "درصد پوشش گرفتگی:" else "Local Obscuration:"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val obscurationStr = if (isFa) "%$displayPercent".toPersianDigits() else "$displayPercent%"
                            Text(
                                text = obscurationStr,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = barColor
                            )
                        }

                        LinearProgressIndicator(
                            progress = (displayPercent / 100f).coerceIn(0f, 1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = barColor,
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val altStr = if (isFa) "ارتفاع: ${detailedInfo.targetAltDeg} درجه".toPersianDigits() else "Alt: ${detailedInfo.targetAltDeg}°"
                            val azStr = if (isFa) "سمت: ${detailedInfo.targetAzDeg} درجه".toPersianDigits() else "Az: ${detailedInfo.targetAzDeg}°"
                            Text(text = altStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = azStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Global Path & Scientific Description
                Text(
                    text = if (isFa) "مسیر جهانی و توضیحات علمی:" else "Global Path & Scientific Details:",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val regionPrefix = when (event.type) {
                        EclipseEngine.EclipseType.TOTAL_SOLAR,
                        EclipseEngine.EclipseType.TOTAL_LUNAR,
                        EclipseEngine.EclipseType.HYBRID_SOLAR ->
                            if (isFa) "مناطق اصلی گرفتگی کامل" else "Max Totality Region"
                        EclipseEngine.EclipseType.ANNULAR_SOLAR ->
                            if (isFa) "مسیر اصلی حلقه آتش (حلقوی)" else "Annularity Path (Ring of Fire)"
                        else ->
                            if (isFa) "مناطق قابل رویت در جهان" else "Global Visibility Region"
                    }
                    val regionStr = if (isFa) {
                        "$regionPrefix: ${event.maxTotalityRegionFa}"
                    } else {
                        "$regionPrefix: ${event.maxTotalityRegionEn}"
                    }
                    Text(
                        text = regionStr,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val magStr = String.format(Locale.US, "%.3f", event.magnitude)
                    val metaStr = if (isFa) {
                        "دوره ساروس ${event.saros} • قدر جهانی: $magStr".toPersianDigits()
                    } else {
                        "Saros Series ${event.saros} • Global Magnitude: $magStr"
                    }
                    Text(
                        text = metaStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (isFa) event.descriptionFa else event.descriptionEn,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Visibility verdict: not just "is it in the sky", but "is there
                // anything to see". See EclipseVisibility for the criteria.
                val visibility = result.visibility
                val gradeColor = when (visibility.grade) {
                    EclipseVisibility.Grade.SPECTACULAR,
                    EclipseVisibility.Grade.EXCELLENT -> StatusExcellent
                    EclipseVisibility.Grade.GOOD -> StatusGood
                    EclipseVisibility.Grade.MARGINAL -> StatusWarning
                    EclipseVisibility.Grade.NOT_OBSERVABLE -> MaterialTheme.colorScheme.error
                }
                Text(
                    text = if (isFa) "ارزیابی دیدپذیری از موقعیت شما:" else "Visibility From Your Location:",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = gradeColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, gradeColor.copy(alpha = 0.5f)),
                        modifier = Modifier.testTag("eclipse_detail_visibility_grade")
                    ) {
                        Text(
                            text = if (isFa) {
                                "دیدپذیری: ${visibility.grade.labelFa}"
                            } else {
                                "Visibility: ${visibility.grade.labelEn}"
                            },
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = gradeColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Text(
                        text = if (isFa) visibility.reasonFa else visibility.reasonEn,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("eclipse_detail_visibility_reason")
                    )
                    Text(
                        text = if (isFa) visibility.altitudeNoteFa else visibility.altitudeNoteEn,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // What it is called, what it will look like, and why it matters.
                val insight = result.insight
                Text(
                    text = if (isFa) "نام و اهمیت این رخداد:" else "Name & Significance:",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (isFa) insight.nicknameFa else insight.nicknameEn,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("eclipse_detail_nickname")
                    )
                    if (insight.significanceEn.isNotEmpty()) {
                        Text(
                            text = if (isFa) insight.significanceFa else insight.significanceEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("eclipse_detail_significance")
                        )
                    }
                }

                Text(
                    text = if (isFa) "ظاهر و رنگ:" else "Appearance & Colour:",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (isFa) insight.appearanceFa else insight.appearanceEn,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("eclipse_detail_appearance")
                )

                // Safety Guidelines
                LiquidGlassSurface(
                    shape = RoundedCornerShape(RedCornerRadius.window),
                    fallbackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                    style = LiquidGlassDefaults.Card,
                    fallbackShadowElevation = RedElevation.none,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isFa) detailedInfo.safetyGuideFa else detailedInfo.safetyGuideEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isFa) detailedInfo.safetyGuideFa else detailedInfo.safetyGuideEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Close Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("dismiss_eclipse_modal_button"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = if (isFa) "متوجه شدم (بستن)" else "Close",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun TimingItem(
    label: String,
    time: String,
    isHighlight: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = time,
            fontSize = 13.sp,
            fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.SemiBold,
            color = if (isHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
