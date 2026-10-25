package com.alijafari.red.astronomy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alijafari.red.astronomy.astro_engine.AstroTime
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.astro_engine.MoonEngine
import com.alijafari.red.astronomy.astro_engine.SunEngine
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.MainViewModel
import com.alijafari.red.astronomy.ui.theme.*
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.alijafari.red.astronomy.util.toPersianDigits
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

/**
 * The Moon window: everything the retired Moon screen had, now opened by tapping the Moon card on Home.
 *
 * Date navigator, drag-to-scrub days, the photographic render, phase and illumination, the orbital and
 * timing ephemeris, the physical/libration block and the upcoming phase schedule — all of it. The scrub
 * state lives in [MainUiState.moonDayOffset], so the card and this window always show the same instant.
 *
 * It is a root-level sibling in MainActivity (not a child of the Crossfade), which is what lets it sample
 * the live backdrop and render real liquid glass.
 */
@Composable
fun MoonDetailWindow(
    backdrop: Backdrop?,
    baseTimeMs: Long,
    uiState: MainUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val isFa = uiState.language == AppLanguage.PERSIAN
    val dayOffsetInt = uiState.moonDayOffset.roundToInt()

    // Rise/set are wall-clock values, so they must be rendered in the OBSERVER's own
    // timezone. Falling through to TimeEngine.formatTime24h's default would pin every
    // user — in Berlin, London or New York — to Asia/Tehran, the exact defect the
    // timezoneId parameter was introduced to remove.
    val tz = TimeEngine.resolveTimeZone(uiState.userLocation.timezoneId)

    val baseJd = remember(baseTimeMs) { TimeEngine.getJulianDate(baseTimeMs) }
    val currentJd = baseJd + uiState.moonDayOffset.toDouble()

    val moonData = remember(currentJd, uiState.userLocation) {
        MoonEngine.calculateMoon(currentJd, uiState.userLocation.latitude, uiState.userLocation.longitude)
    }

    val sunHoriz = remember(currentJd, uiState.userLocation) {
        val sunPos = SunEngine.calculatePosition(currentJd)
        val lastDeg = TimeEngine.getLAST(currentJd, uiState.userLocation.longitude)
        CoordinateEngine.equatorialToHorizontal(
            equatorial = CoordinateEngine.Equatorial(sunPos.raDeg, sunPos.decDeg),
            lastDeg = lastDeg,
            latitudeDeg = uiState.userLocation.latitude
        )
    }

    val upcomingPhases = remember(baseJd) { MoonEngine.getUpcomingMajorPhases(baseJd) }

    // Same rule as the card: the date line is read off the instant being rendered, so Time Machine
    // simulation and the day scrub both move it.
    val selectedTimeMs = remember(currentJd) { AstroTime.fromJd(currentJd).utcMs }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Scrim, kept a sibling of the window so taps inside it are never swallowed.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
                    .testTag("moon_window_scrim")
            )

            LiquidGlassSurface(
                backdrop = backdrop,
                style = LiquidGlassDefaults.Window,
                shape = squircleShape(RedCornerRadius.window),
                fallbackColor = RedTheme.colors.surfaceElevated,
                modifier = Modifier
                    .align(Alignment.Center)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = RedSpacing.md, vertical = RedSpacing.lg)
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .testTag("moon_window")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = RedSpacing.lg, vertical = RedSpacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RedSpacing.lg)
                ) {
                    // --- Header ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isFa) "ماه" else "Moon",
                            style = RedTypographyTokens.sectionHeading,
                            color = RedTheme.colors.textPrimary
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .testTag("moon_window_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = if (isFa) "بستن" else "Close",
                                tint = RedTheme.colors.textPrimary,
                                modifier = Modifier.size(RedIconSize.sm)
                            )
                        }
                    }

                    // --- Date Navigator Pill ---
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(RedTheme.colors.surfaceGrouped.copy(alpha = 0.55f))
                            .border(0.75.dp, RedTheme.colors.border, CircleShape)
                            .padding(horizontal = RedSpacing.xs, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RedSpacing.xs)
                    ) {
                        IconButton(
                            onClick = { viewModel.setMoonDayOffset(uiState.moonDayOffset - 1f) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isFa) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous Day",
                                tint = RedTheme.colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        val dateStr = TimeEngine.formatDate(selectedTimeMs, uiState.calendarSystem, isFa).let {
                            if (isFa) it.toPersianDigits() else it
                        }
                        val offsetBadge = when {
                            dayOffsetInt == 0 -> if (isFa) "امروز — $dateStr" else "Today — $dateStr"
                            dayOffsetInt > 0 -> if (isFa) "+${dayOffsetInt} روز — $dateStr".toPersianDigits() else "+${dayOffsetInt}d — $dateStr"
                            else -> if (isFa) "${dayOffsetInt} روز — $dateStr".toPersianDigits() else "${dayOffsetInt}d — $dateStr"
                        }

                        Text(
                            text = offsetBadge,
                            style = RedTypographyTokens.caption.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = RedTheme.colors.textPrimary
                            ),
                            modifier = Modifier.padding(horizontal = RedSpacing.xs)
                        )

                        IconButton(
                            onClick = { viewModel.setMoonDayOffset(uiState.moonDayOffset + 1f) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isFa) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next Day",
                                tint = RedTheme.colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // --- Scrubber hint / reset ---
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(RedTheme.colors.accentRed.copy(alpha = 0.08f))
                            .border(0.75.dp, RedTheme.colors.accentRed.copy(alpha = 0.2f), CircleShape)
                            .padding(horizontal = RedSpacing.md, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RedSpacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Swipe,
                            contentDescription = null,
                            tint = RedTheme.colors.accentRed,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (isFa) "برای جابجایی روزها ماه را افقی بکشید" else "Swipe horizontally to scrub days",
                            style = RedTypographyTokens.caption.copy(fontSize = 11.sp),
                            color = RedTheme.colors.textPrimary
                        )
                        if (dayOffsetInt != 0) {
                            Surface(
                                onClick = { viewModel.setMoonDayOffset(0f) },
                                shape = squircleShape(RedCornerRadius.xs),
                                color = RedTheme.colors.accentRed.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = if (isFa) "امروز" else "Reset",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        color = RedTheme.colors.accentRed
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // --- The render, draggable exactly as it was on the Moon screen ---
                    MoonPhaseRender(
                        moonData = moonData,
                        sunHoriz = sunHoriz,
                        latitude = uiState.userLocation.latitude,
                        modifier = Modifier
                            .size(240.dp)
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { change, dragAmount ->
                                    change.consume()
                                    viewModel.nudgeMoonDayOffset(dragAmount / 16f)
                                }
                            }
                            .testTag("moon_window_render")
                    )

                    // --- Phase & illumination ---
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (isFa) moonData.phaseNameFa else moonData.phaseNameEn,
                            style = RedTypographyTokens.sectionHeading.copy(fontSize = 22.sp),
                            color = RedTheme.colors.textPrimary,
                            textAlign = TextAlign.Center
                        )
                        val illFormatted = String.format("%.0f", moonData.illuminationPercent).let {
                            if (isFa) "${it}٪ روشن".toPersianDigits() else "$it% Illuminated"
                        }
                        Text(
                            text = illFormatted,
                            style = RedTypographyTokens.bodyPrimary.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = RedTheme.colors.accentRed
                            ),
                            textAlign = TextAlign.Center
                        )
                    }

                    // --- Orbital & timing ---
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("moon_window_details"),
                        verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                    ) {
                        RedSectionHeader(
                            title = if (isFa) "مشخصات مداری و زمان‌بندی" else "Orbital & Timing Parameters",
                            subtitle = if (isFa) "طلوع، غروب، فاصله و مشخصات فیزیکی" else "Rise, set, distance & physical ephemeris"
                        )

                        val riseStr = moonData.moonriseTimeMs?.let { TimeEngine.formatTime24h(it, isFa, tz) } ?: "--:--"
                        val setStr = moonData.moonsetTimeMs?.let { TimeEngine.formatTime24h(it, isFa, tz) } ?: "--:--"
                        val distStr = String.format("%,d", moonData.distanceKm.toInt()).let {
                            if (isFa) "$it کیلومتر".toPersianDigits() else "$it km"
                        }
                        val altStr = String.format("%.1f", moonData.altitudeDeg).let {
                            if (isFa) "$it°".toPersianDigits() else "$it°"
                        }
                        val azStr = String.format("%.1f", moonData.azimuthDeg).let {
                            if (isFa) "$it°".toPersianDigits() else "$it°"
                        }
                        val ageStr = String.format("%.1f", moonData.ageDays).let {
                            if (isFa) "$it روز".toPersianDigits() else "$it days"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = RedSpacing.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MoonMetricItem(
                                icon = Icons.Outlined.WbSunny,
                                label = if (isFa) "طلوع ماه" else "Moonrise",
                                value = if (isFa) riseStr.toPersianDigits() else riseStr,
                                modifier = Modifier.weight(1f)
                            )
                            MoonMetricItem(
                                icon = Icons.Outlined.NightsStay,
                                label = if (isFa) "غروب ماه" else "Moonset",
                                value = if (isFa) setStr.toPersianDigits() else setStr,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        RedHairlineDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = RedSpacing.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MoonMetricItem(
                                icon = Icons.Outlined.Straighten,
                                label = if (isFa) "فاصله از زمین" else "Distance",
                                value = distStr,
                                modifier = Modifier.weight(1f)
                            )
                            MoonMetricItem(
                                icon = Icons.Outlined.Schedule,
                                label = if (isFa) "سن ماه" else "Lunar Age",
                                value = ageStr,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        RedHairlineDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = RedSpacing.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MoonMetricItem(
                                icon = Icons.Outlined.Navigation,
                                label = if (isFa) "ارتفاع مداری" else "Altitude",
                                value = altStr,
                                modifier = Modifier.weight(1f)
                            )
                            MoonMetricItem(
                                icon = Icons.Outlined.Explore,
                                label = if (isFa) "سمت (زاویه افقی)" else "Azimuth",
                                value = azStr,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // --- Physical & observational ---
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("moon_window_ephemeris"),
                        verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                    ) {
                        RedSectionHeader(
                            title = if (isFa) "پارامترهای فیزیکی و رصدی" else "Physical & Observational Data",
                            subtitle = if (isFa) "قطر ظاهری، رخ‌گردی و درخشش زمین‌تاب" else "Angular diameter, libration & earthshine"
                        )

                        val angDiamStr = String.format("%.1f′", moonData.angularDiameterArcmin).let {
                            if (isFa) it.toPersianDigits() else it
                        }
                        val libLonStr = String.format("%+.1f°", moonData.librationLonDeg).let {
                            if (isFa) it.toPersianDigits() else it
                        }
                        val libLatStr = String.format("%+.1f°", moonData.librationLatDeg).let {
                            if (isFa) it.toPersianDigits() else it
                        }
                        val earthshineStr = String.format("%.0f%%", moonData.earthshineFraction * 100.0).let {
                            if (isFa) it.toPersianDigits() else it
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = RedSpacing.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MoonMetricItem(
                                icon = Icons.Outlined.FitScreen,
                                label = if (isFa) "قطر زاویه‌ای" else "Angular Diameter",
                                value = angDiamStr,
                                modifier = Modifier.weight(1f)
                            )
                            MoonMetricItem(
                                icon = Icons.Outlined.LightMode,
                                label = if (isFa) "زمین‌تاب" else "Earthshine",
                                value = earthshineStr,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        RedHairlineDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = RedSpacing.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MoonMetricItem(
                                icon = Icons.Outlined.Rotate90DegreesCcw,
                                label = if (isFa) "رخ‌گردی طولی" else "Libration (Lon)",
                                value = libLonStr,
                                modifier = Modifier.weight(1f)
                            )
                            MoonMetricItem(
                                icon = Icons.Outlined.Rotate90DegreesCw,
                                label = if (isFa) "رخ‌گردی عرضی" else "Libration (Lat)",
                                value = libLatStr,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // --- Upcoming phases ---
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("moon_window_upcoming"),
                        verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                    ) {
                        RedSectionHeader(
                            title = if (isFa) "فازهای آینده" else "Upcoming Phases",
                            subtitle = if (isFa) "زمان‌بندی فازهای بعدی ماه" else "Next lunar quarters & full moon schedule"
                        )

                        upcomingPhases.forEachIndexed { idx, phase ->
                            val name = if (isFa) phase.phaseNameFa else phase.phaseNameEn
                            val dateText = TimeEngine.formatDate(phase.dateMs, uiState.calendarSystem, isFa).let {
                                if (isFa) it.toPersianDigits() else it
                            }
                            val daysText = if (isFa) "${phase.daysFromNow} روز دیگر".toPersianDigits() else "in ${phase.daysFromNow} days"

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = RedSpacing.sm),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.md)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(RedTheme.colors.accentRed.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Brightness2,
                                            contentDescription = null,
                                            tint = RedTheme.colors.accentRed,
                                            modifier = Modifier.size(RedIconSize.sm)
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = name,
                                            style = RedTypographyTokens.bodyPrimary.copy(fontWeight = FontWeight.SemiBold),
                                            color = RedTheme.colors.textPrimary
                                        )
                                        Text(
                                            text = dateText,
                                            style = RedTypographyTokens.caption,
                                            color = RedTheme.colors.textSecondary
                                        )
                                    }
                                }

                                RedBadge(
                                    text = daysText,
                                    backgroundColor = RedTheme.colors.surfaceGrouped.copy(alpha = 0.6f),
                                    textColor = RedTheme.colors.textSecondary,
                                    borderColor = RedTheme.colors.border
                                )
                            }

                            if (idx < upcomingPhases.size - 1) {
                                RedHairlineDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoonMetricItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RedSpacing.md)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(RedTheme.colors.accentRed.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = RedTheme.colors.accentRed,
                modifier = Modifier.size(RedIconSize.sm)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = RedTypographyTokens.caption,
                color = RedTheme.colors.textSecondary
            )
            Text(
                text = value,
                style = RedTypographyTokens.bodyPrimary.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                ),
                color = RedTheme.colors.textPrimary
            )
        }
    }
}
