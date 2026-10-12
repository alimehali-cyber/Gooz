package com.alijafari.red.astronomy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.astro_engine.DistanceUnit
import com.alijafari.red.astronomy.astro_engine.RelativisticEngine
import com.alijafari.red.astronomy.data.catalog.AstronomyCatalog
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.domain.CelestialObject
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.theme.*
import com.alijafari.red.astronomy.util.toPersianDigits
import java.util.Locale

enum class SpeedUnit(val labelEn: String, val labelFa: String) {
    PERCENT_C("% c", "درصد سرعت نور (% c)"),
    KM_H("km/h", "کیلومتر بر ساعت (km/h)"),
    M_S("m/s", "متر بر ثانیه (m/s)")
}

data class SpeedPreset(
    val titleEn: String,
    val titleFa: String,
    val speedMs: Double,
    val icon: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDilationCalculatorScreen(
    uiState: MainUiState,
    onBackToLab: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Intercept Android System Back button/gesture to return to Lab
    BackHandler(enabled = true) {
        onBackToLab()
    }

    val isFa = uiState.language == AppLanguage.PERSIAN
    val allCatalogObjects = remember { AstronomyCatalog.getAllObjects() }

    // Pre-select default Start (Earth) and Destination (Proxima Centauri or Mars)
    var startObject by remember {
        mutableStateOf(allCatalogObjects.firstOrNull { it.id == "planet_earth" } ?: allCatalogObjects.first())
    }
    var destObject by remember {
        mutableStateOf(
            allCatalogObjects.firstOrNull { it.id == "star_cma_sirius" || it.id.contains("proxima") || it.id == "planet_mars" }
                ?: allCatalogObjects.getOrNull(2) ?: allCatalogObjects.last()
        )
    }

    // Object Selector Dialog State
    var selectingForStart by remember { mutableStateOf<Boolean?>(null) } // true for start, false for dest, null for closed

    // Distance Display Unit
    var distanceUnit by remember { mutableStateOf(DistanceUnit.AUTO) }

    // Speed Controls
    var speedUnit by remember { mutableStateOf(SpeedUnit.PERCENT_C) }
    var rawSpeedInput by remember { mutableStateOf("90") } // Default 90% c

    // Speed Presets
    val speedPresets = remember {
        listOf(
            SpeedPreset("Walking", "پیاده‌روی", 1.38889, "🚶"),
            SpeedPreset("Sound", "سرعت صوت", 343.0, "🔊"),
            SpeedPreset("Fighter Jet", "جت جنگنده", 686.11, "✈️"),
            SpeedPreset("Spacecraft", "فضاپیمای وویجر", 17000.0, "🛸"),
            SpeedPreset("Starship Rocket", "موشک استارشیپ", 75000.0, "🚀"),
            SpeedPreset("10% c", "۱۰٪ سرعت نور", 0.10 * RelativisticEngine.SPEED_OF_LIGHT_MS, "⚡"),
            SpeedPreset("50% c", "۵۰٪ سرعت نور", 0.50 * RelativisticEngine.SPEED_OF_LIGHT_MS, "💫"),
            SpeedPreset("90% c", "۹۰٪ سرعت نور", 0.90 * RelativisticEngine.SPEED_OF_LIGHT_MS, "🌌"),
            SpeedPreset("99% c", "۹۹٪ سرعت نور", 0.99 * RelativisticEngine.SPEED_OF_LIGHT_MS, "✨"),
            SpeedPreset("Speed of Light (c)", "سرعت نور (c)", 1.00 * RelativisticEngine.SPEED_OF_LIGHT_MS, "☀️")
        )
    }

    // Convert raw input to m/s based on unit
    val speedInMs = remember(rawSpeedInput, speedUnit) {
        val num = RelativisticEngine.parseLocalizedDouble(rawSpeedInput) ?: 0.0
        when (speedUnit) {
            SpeedUnit.PERCENT_C -> (num / 100.0) * RelativisticEngine.SPEED_OF_LIGHT_MS
            SpeedUnit.KM_H -> num * 1000.0 / 3600.0
            SpeedUnit.M_S -> num
        }
    }

    // Relativity Toggles
    var isAccelerationOn by remember { mutableStateOf(false) }
    var accelerationValueInG by remember { mutableStateOf("1.0") } // Default 1.0 g
    var isLengthContractionOn by remember { mutableStateOf(true) }

    // How It Works Physics Dialog
    var showHowItWorksDialog by remember { mutableStateOf(false) }

    // Convert acceleration input to m/s^2
    val accelInMs2 = remember(accelerationValueInG) {
        val numG = RelativisticEngine.parseLocalizedDouble(accelerationValueInG) ?: 1.0
        numG * RelativisticEngine.STANDARD_G_MS2
    }

    // Journey Result
    val journeyResult = remember(startObject, destObject, speedInMs, isAccelerationOn, accelInMs2, isLengthContractionOn) {
        RelativisticEngine.calculateJourney(
            startObject = startObject,
            destinationObject = destObject,
            speedMs = speedInMs,
            isAccelerationOn = isAccelerationOn,
            accelerationMs2 = accelInMs2,
            isLengthContractionOn = isLengthContractionOn
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("time_dilation_screen"),
        containerColor = Color.Transparent,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RedSpacing.lg, vertical = RedSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.md)
                ) {
                    IconButton(
                        onClick = onBackToLab,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(RedTheme.colors.surfaceElevated.copy(alpha = 0.7f))
                            .border(0.5.dp, RedTheme.colors.border.copy(alpha = 0.4f), CircleShape)
                            .testTag("time_dilation_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Lab",
                            tint = RedTheme.colors.textPrimary,
                            modifier = Modifier.size(RedIconSize.sm)
                        )
                    }

                    Column {
                        Text(
                            text = if (isFa) "محاسبه‌گر انقباض زمان" else "Time Dilation Calculator",
                            style = RedTypographyTokens.sectionHeading,
                            color = RedTheme.colors.textPrimary
                        )
                        Text(
                            text = if (isFa) "سفر بین‌ستاره‌ای با نسبیت خاص" else "Relativistic Journey Simulator",
                            style = RedTypographyTokens.caption,
                            color = RedTheme.colors.textSecondary
                        )
                    }
                }

                IconButton(
                    onClick = { showHowItWorksDialog = true },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(RedTheme.colors.accentRed.copy(alpha = 0.12f))
                        .border(0.5.dp, RedTheme.colors.accentRed.copy(alpha = 0.3f), CircleShape)
                        .testTag("time_dilation_info_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = "How it works",
                        tint = RedTheme.colors.accentRed,
                        modifier = Modifier.size(RedIconSize.sm)
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = RedSpacing.lg, vertical = RedSpacing.md),
            verticalArrangement = Arrangement.spacedBy(RedSpacing.lg)
        ) {
            // STEP 1 & 2: OBJECT SELECTORS (START & DESTINATION)
            item {
                RedElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(RedSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(RedSpacing.md)
                    ) {
                        RedSectionHeader(
                            title = if (isFa) "۱. انتخاب مبدأ و مقصد سفر" else "1. Select Route (Origin & Destination)"
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Start Selector Card
                            ObjectSelectionChip(
                                modifier = Modifier.weight(1f),
                                label = if (isFa) "مبدأ" else "Start",
                                selectedObject = startObject,
                                isFa = isFa,
                                onClick = { selectingForStart = true },
                                testTag = "select_start_object_button"
                            )

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "To",
                                tint = RedTheme.colors.accentRed,
                                modifier = Modifier.size(18.dp)
                            )

                            // Destination Selector Card
                            ObjectSelectionChip(
                                modifier = Modifier.weight(1f),
                                label = if (isFa) "مقصد" else "Destination",
                                selectedObject = destObject,
                                isFa = isFa,
                                onClick = { selectingForStart = false },
                                testTag = "select_dest_object_button"
                            )
                        }

                        // Swap Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                shape = RoundedCornerShape(RedCornerRadius.full),
                                color = RedTheme.colors.surfaceElevated,
                                border = BorderStroke(1.dp, RedTheme.colors.border),
                                modifier = Modifier
                                    .clickable {
                                        val temp = startObject
                                        startObject = destObject
                                        destObject = temp
                                    }
                                    .testTag("swap_route_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SwapHoriz,
                                        contentDescription = "Swap",
                                        tint = RedTheme.colors.accentRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (isFa) "تعویض مبدأ و مقصد" else "Swap Origin & Destination",
                                        style = RedTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = RedTheme.colors.accentRed
                                    )
                                }
                            }
                        }

                        // Distance Card with Unit Selector Carousel
                        val distRes = journeyResult.distance
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(RedCornerRadius.lg),
                            color = RedTheme.colors.surfaceElevated,
                            border = BorderStroke(1.dp, RedTheme.colors.border)
                        ) {
                            Column(
                                modifier = Modifier.padding(RedSpacing.md),
                                verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.xs)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Straighten,
                                        contentDescription = null,
                                        tint = RedTheme.colors.accentRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (isFa) "فاصله بین دو جرم:" else "Calculated Distance:",
                                        style = RedTypography.labelMedium,
                                        color = RedTheme.colors.textSecondary
                                    )
                                }

                                // Distance Unit Selector Carousel
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.xs),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(DistanceUnit.entries) { unit ->
                                        val isSelected = distanceUnit == unit
                                        Surface(
                                            shape = RoundedCornerShape(RedCornerRadius.full),
                                            color = if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.surfaceElevated,
                                            border = BorderStroke(1.dp, if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.border),
                                            modifier = Modifier.clickable { distanceUnit = unit }
                                        ) {
                                            Text(
                                                text = if (isFa) unit.labelFa else unit.labelEn,
                                                style = RedTypography.labelSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                                color = if (isSelected) Color.White else RedTheme.colors.textSecondary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = RelativisticEngine.formatDistance(distRes.distanceMeters, unit = distanceUnit, isFa = isFa),
                                    style = RedTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = RedTheme.colors.accentRed
                                )

                                // Explicit Dual-Unit Display (km and light-years)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${if (isFa) "کیلومتر: " else "km: "}${RelativisticEngine.formatDistance(distRes.distanceMeters, DistanceUnit.KM, isFa)}",
                                        style = RedTypography.labelSmall,
                                        color = RedTheme.colors.textSecondary
                                    )
                                    Text(
                                        text = "•",
                                        style = RedTypography.labelSmall,
                                        color = RedTheme.colors.border
                                    )
                                    Text(
                                        text = "${if (isFa) "سال نوری: " else "Light-years: "}${RelativisticEngine.formatDistance(distRes.distanceMeters, DistanceUnit.LIGHT_YEARS, isFa)}",
                                        style = RedTypography.labelSmall,
                                        color = RedTheme.colors.textSecondary
                                    )
                                }

                                Text(
                                    text = if (isFa) distRes.noteFa else distRes.noteEn,
                                    style = RedTypography.labelSmall,
                                    color = RedTheme.colors.textSecondary.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }

            // STEP 3: TRAVEL SPEED & PRESETS
            item {
                RedElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(RedSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(RedSpacing.md)
                    ) {
                        RedSectionHeader(
                            title = if (isFa) "۲. تعیین سرعت حرکت" else "2. Travel Speed & Presets"
                        )

                        // Unit Selector Tabs
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(RedSpacing.xs)
                        ) {
                            items(SpeedUnit.entries) { unit ->
                                val isSelected = speedUnit == unit
                                Surface(
                                    shape = RoundedCornerShape(RedCornerRadius.full),
                                    color = if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.surfaceElevated,
                                    border = BorderStroke(1.dp, if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.border),
                                    modifier = Modifier.clickable {
                                        speedUnit = unit
                                        rawSpeedInput = when (unit) {
                                            SpeedUnit.PERCENT_C -> "90"
                                            SpeedUnit.KM_H -> "971280000"
                                            SpeedUnit.M_S -> "269813212"
                                        }
                                    }
                                ) {
                                    Text(
                                        text = if (isFa) unit.labelFa else unit.labelEn,
                                        style = RedTypography.labelSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                        color = if (isSelected) Color.White else RedTheme.colors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // Speed Presets Scrollable Row
                        Text(
                            text = if (isFa) "نمونه‌های آماده سرعت:" else "Preset Speed Examples:",
                            style = RedTypography.labelMedium,
                            color = RedTheme.colors.textSecondary
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(speedPresets) { preset ->
                                Surface(
                                    onClick = {
                                        when (speedUnit) {
                                            SpeedUnit.PERCENT_C -> {
                                                val pct = (preset.speedMs / RelativisticEngine.SPEED_OF_LIGHT_MS) * 100.0
                                                rawSpeedInput = if (pct >= 1.0) String.format(Locale.US, "%.0f", pct) else String.format(Locale.US, "%.6f", pct)
                                            }
                                            SpeedUnit.KM_H -> {
                                                val kmh = preset.speedMs * 3.6
                                                rawSpeedInput = String.format(Locale.US, "%.0f", kmh)
                                            }
                                            SpeedUnit.M_S -> {
                                                rawSpeedInput = String.format(Locale.US, "%.0f", preset.speedMs)
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(RedCornerRadius.window),
                                    color = RedTheme.colors.surfaceElevated,
                                    border = BorderStroke(1.dp, RedTheme.colors.border)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(text = preset.icon, fontSize = 14.sp)
                                        Text(
                                            text = if (isFa) preset.titleFa else preset.titleEn,
                                            style = RedTypography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                            color = RedTheme.colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }

                        // Custom Numeric Speed Input
                        OutlinedTextField(
                            textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                            value = rawSpeedInput,
                            onValueChange = { rawSpeedInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_speed_input"),
                            label = {
                                Text(
                                    text = if (isFa) "ورود سرعت دلخواه (${if (isFa) speedUnit.labelFa else speedUnit.labelEn})"
                                    else "Custom Speed Input (${speedUnit.labelEn})"
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(RedCornerRadius.md),
                            trailingIcon = {
                                Text(
                                    text = if (isFa) speedUnit.labelFa else speedUnit.labelEn,
                                    style = RedTypography.labelSmall,
                                    color = RedTheme.colors.accentRed,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                            }
                        )

                        // Superluminal / Light Speed Warnings
                        if (journeyResult.isSuperluminal) {
                            Surface(
                                shape = RoundedCornerShape(RedCornerRadius.window),
                                color = RedTheme.colors.accentRed.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, RedTheme.colors.accentRed)
                            ) {
                                Row(
                                    modifier = Modifier.padding(RedSpacing.md),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.md)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Warning",
                                        tint = RedTheme.colors.accentRed
                                    )
                                    Text(
                                        text = if (isFa) "هشدار فیزیکی: سرعت $rawSpeedInput بیشتر از سرعت نور (v > c) است! طبق اصل نسبیت خاص آینشتاین، هیچ ذره دارای جرمی نمی‌تواند به سرعت نور یا بالاتر از آن دست یابد."
                                        else "Physical Constraint Violation: Speed exceeds light speed (v > c). Superluminal travel is forbidden by Special Relativity as mass becomes infinite.",
                                        style = RedTypography.labelMedium,
                                        color = RedTheme.colors.accentRed
                                    )
                                }
                            }
                        } else if (journeyResult.isSpeedOfLight) {
                            Surface(
                                shape = RoundedCornerShape(RedCornerRadius.window),
                                color = RedTheme.colors.accentRed.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, RedTheme.colors.accentRed)
                            ) {
                                Row(
                                    modifier = Modifier.padding(RedSpacing.md),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.md)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Info",
                                        tint = RedTheme.colors.accentRed
                                    )
                                    Text(
                                        text = if (isFa) "در سرعت نور (v = c): زمان اختصاصی برای فوتون یا مسافر برابر با ۰ است (عامل لورنتس بی‌نهایت). اجرام جرم‌دار برای رسیدن به این سرعت نیازمند انرژی بی‌نهایت هستند."
                                        else "At speed of light (v = c): Proper time for traveller = 0 (infinite Lorentz factor). Massive particles require infinite energy to reach c.",
                                        style = RedTypography.labelMedium,
                                        color = RedTheme.colors.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // STEP 4: ACCELERATION MODE & LENGTH CONTRACTION TOGGLES
            item {
                RedElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(RedSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(RedSpacing.md)
                    ) {
                        RedSectionHeader(
                            title = if (isFa) "۳. تنظیمات شتاب و انقباض طول" else "3. Relativistic Options & Kinematics"
                        )

                        // Acceleration Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isFa) "حالت شتاب نسبیتی ثابت (Acceleration)" else "Relativistic Acceleration Mode",
                                    style = RedTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = RedTheme.colors.textPrimary
                                )
                                Text(
                                    text = if (isFa) "شتاب‌گیری تا نیمه راه و شتاب‌کاهی تا مقصد (Brachistochrone)"
                                    else "Accelerate to midpoint, decelerate to destination",
                                    style = RedTypography.labelSmall,
                                    color = RedTheme.colors.textSecondary
                                )
                            }

                            Switch(
                                checked = isAccelerationOn,
                                onCheckedChange = { isAccelerationOn = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = RedTheme.colors.accentRed
                                ),
                                modifier = Modifier.testTag("acceleration_toggle")
                            )
                        }

                        if (isAccelerationOn) {
                            OutlinedTextField(
                                textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                                value = accelerationValueInG,
                                onValueChange = { accelerationValueInG = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("acceleration_input"),
                                label = {
                                    Text(text = if (isFa) "شتاب اختصاصی مسافر بر حسب g (۱g = ۹.۸۱ m/s²)" else "Proper Acceleration in g (1g = 9.81 m/s²)")
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(RedCornerRadius.md),
                                trailingIcon = {
                                    Text(
                                        text = "g",
                                        style = RedTypography.labelMedium,
                                        color = RedTheme.colors.accentRed,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                }
                            )

                            // Acceleration breakdown display
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(RedCornerRadius.window),
                                color = RedTheme.colors.surfaceElevated,
                                border = BorderStroke(1.dp, RedTheme.colors.border)
                            ) {
                                Column(
                                    modifier = Modifier.padding(RedSpacing.md),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (isFa) "بیشینه سرعت رسیده شده:" else "Max Velocity Reached:",
                                            style = RedTypography.labelMedium,
                                            color = RedTheme.colors.textSecondary
                                        )
                                        val maxVelVal = String.format(Locale.US, "%.2f", journeyResult.maxVelocityFractionOfC * 100)
                                        val maxVelFormatted = if (isFa) "$maxVelVal٪ c".toPersianDigits() else "$maxVelVal% c"
                                        Text(
                                            text = maxVelFormatted,
                                            style = RedTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = RedTheme.colors.accentRed
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (isFa) "زمان فاز شتاب‌گیری (مرجع زمین):" else "Acc. Phase Duration (Earth):",
                                            style = RedTypography.labelSmall,
                                            color = RedTheme.colors.textSecondary
                                        )
                                        Text(
                                            text = RelativisticEngine.formatDuration(journeyResult.accelerationPhaseEarthSeconds, isFa),
                                            style = RedTypography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = RedTheme.colors.textPrimary
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (isFa) "زمان فاز کروز / سرعت ثابت:" else "Cruise Phase Duration:",
                                            style = RedTypography.labelSmall,
                                            color = RedTheme.colors.textSecondary
                                        )
                                        Text(
                                            text = RelativisticEngine.formatDuration(journeyResult.cruisePhaseEarthSeconds, isFa),
                                            style = RedTypography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = RedTheme.colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = RedTheme.colors.border)

                        // Length Contraction Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isFa) "محاسبه انقباض طول نسبیتی (Length Contraction)" else "Relativistic Length Contraction",
                                    style = RedTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = RedTheme.colors.textPrimary
                                )
                                Text(
                                    text = if (isFa) "محاسبه انقباض فاصله مسیر در دستگاه مختصات ناظر متحرک (L' = L/γ)"
                                    else "Calculate contracted journey length in moving frame (L' = L/γ)",
                                    style = RedTypography.labelSmall,
                                    color = RedTheme.colors.textSecondary
                                )
                            }

                            Switch(
                                checked = isLengthContractionOn,
                                onCheckedChange = { isLengthContractionOn = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = RedTheme.colors.accentRed
                                ),
                                modifier = Modifier.testTag("length_contraction_toggle")
                            )
                        }
                    }
                }
            }

            // STEP 5: RELATIVISTIC RESULTS COMPARISON
            item {
                RedElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(RedSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(RedSpacing.md)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timelapse,
                                contentDescription = null,
                                tint = RedTheme.colors.accentRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = if (isFa) "نتایج و مقایسه زمان نسبیتی" else "Relativistic Results Comparison",
                                style = RedTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = RedTheme.colors.textPrimary
                            )
                        }

                        if (!journeyResult.isSuperluminal) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(RedSpacing.md)
                            ) {
                                // Earth Frame Time Card
                                ResultComparisonTile(
                                    modifier = Modifier.weight(1f),
                                    title = if (isFa) "دید زمین" else "Earth / Ref.",
                                    timeValueStr = RelativisticEngine.formatDuration(journeyResult.earthTimeSeconds, isFa),
                                    badgeText = if (isFa) "زمان ناظر (t)" else "Coord Time (t)",
                                    isAccent = false
                                )

                                // Traveller Proper Time Card
                                ResultComparisonTile(
                                    modifier = Modifier.weight(1f),
                                    title = if (isFa) "دید مسافر" else "Traveller Proper",
                                    timeValueStr = RelativisticEngine.formatDuration(journeyResult.travellerTimeSeconds, isFa),
                                    badgeText = if (isFa) "اختصاصی (τ)" else "Proper (τ)",
                                    isAccent = true
                                )
                            }

                            // Summary metrics grid
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(RedCornerRadius.window),
                                color = RedTheme.colors.surfaceElevated,
                                border = BorderStroke(1.dp, RedTheme.colors.border)
                            ) {
                                Column(
                                    modifier = Modifier.padding(RedSpacing.md),
                                    verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                                ) {
                                    MetricRow(
                                        label = if (isFa) "اختلاف زمان (کند شدن زمان):" else "Time Difference (Dilation):",
                                        value = RelativisticEngine.formatDuration(journeyResult.timeDifferenceSeconds, isFa),
                                        highlight = true
                                    )

                                    val gammaRaw = if (journeyResult.lorentzFactorPeak.isInfinite()) "∞"
                                    else if (journeyResult.lorentzFactorPeak.isNaN()) "—"
                                    else String.format(Locale.US, "%.4f", journeyResult.lorentzFactorPeak)
                                    val gammaStr = if (isFa) gammaRaw.toPersianDigits() else gammaRaw

                                    MetricRow(
                                        label = if (isFa) "عامل لورنتس (Lorentz Factor γ):" else "Lorentz Factor (γ):",
                                        value = "γ = $gammaStr",
                                        highlight = false
                                    )

                                    val pctRaw = if (journeyResult.percentageTimeDifference.isNaN()) "0%"
                                    else String.format(Locale.US, "%.2f%%", journeyResult.percentageTimeDifference)
                                    val pctStr = if (isFa) pctRaw.toPersianDigits() else pctRaw

                                    MetricRow(
                                        label = if (isFa) "درصد انقباض زمان نسبت به زمین:" else "Percentage Time Dilation:",
                                        value = pctStr,
                                        highlight = false
                                    )

                                    if (isLengthContractionOn) {
                                        val contractedLyStr = RelativisticEngine.formatDistance(
                                            journeyResult.contractedDistanceMeters,
                                            unit = distanceUnit,
                                            isFa = isFa
                                        )
                                        MetricRow(
                                            label = if (isFa) "طول انقباض یافته از دید مسافر:" else "Contracted Distance (Traveller):",
                                            value = contractedLyStr,
                                            highlight = true
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = if (isFa) "سرعت غیرمجاز فوق‌نور. محاسبات نسبیتی معتبر نیستند."
                                else "Superluminal velocity input. Relativistic physics equations do not apply.",
                                style = RedTypography.bodyMedium,
                                color = RedTheme.colors.accentRed,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Bottom clearance for floating navigation bar
            item {
                Spacer(modifier = Modifier.height(112.dp))
            }
        }
    }

    // SEARCHABLE OBJECT SELECTOR DIALOG
    if (selectingForStart != null) {
        val isStart = selectingForStart == true
        ObjectSearchModal(
            title = if (isStart) (if (isFa) "انتخاب جرم مبدأ" else "Select Origin Object")
            else (if (isFa) "انتخاب جرم مقصد" else "Select Destination Object"),
            allObjects = allCatalogObjects,
            isFa = isFa,
            onObjectSelected = { obj ->
                if (isStart) {
                    startObject = obj
                } else {
                    destObject = obj
                }
                selectingForStart = null
            },
            onDismiss = { selectingForStart = null }
        )
    }

    // HOW IT WORKS PHYSICS EXPLANATION DIALOG
    if (showHowItWorksDialog) {
        AlertDialog(
            onDismissRequest = { showHowItWorksDialog = false },
            shape = RoundedCornerShape(RedCornerRadius.xl),
            containerColor = RedTheme.colors.surfaceElevated,
            confirmButton = {
                TextButton(onClick = { showHowItWorksDialog = false }) {
                    Text(
                        text = if (isFa) "متوجه شدم" else "Got It",
                        color = RedTheme.colors.accentRed,
                        style = RedTypography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Science,
                    contentDescription = null,
                    tint = RedTheme.colors.accentRed,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = if (isFa) "فیزیک کند شدن زمان و نسبیت خاص" else "How Relativistic Time Dilation Works",
                    style = RedTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = RedTheme.colors.textPrimary
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(RedSpacing.md),
                    modifier = Modifier.padding(vertical = RedSpacing.xs)
                ) {
                    Text(
                        text = if (isFa)
                            "بر اساس نظریه نسبیت خاص آینشتاین (۱۹۰۵)، سرعت نور در خلاء (c ≈ ۳۰۰,۰۰۰ km/s) برای تمامی ناظرها یکسان و ثابت است. برای حفظ این ثبات، زمان و مکان نسبی می‌شوند."
                        else
                            "According to Einstein's Theory of Special Relativity (1905), the speed of light in vacuum (c ≈ 300,000 km/s) is invariant across all inertial reference frames.",
                        style = RedTypography.bodyMedium,
                        color = RedTheme.colors.textSecondary
                    )

                    Surface(
                        shape = RoundedCornerShape(RedCornerRadius.window),
                        color = RedTheme.colors.surfaceElevated,
                        border = BorderStroke(1.dp, RedTheme.colors.border)
                    ) {
                        Text(
                            text = if (isFa) "فرمول عامل لورنتس:\nγ = 1 / √(1 - v²/c²)\n\nزمان ناظر ساکن (زمین):\nt = γ × τ"
                            else "Lorentz Factor Equation:\nγ = 1 / √(1 - v²/c²)\n\nReference Frame Time:\nt = γ × τ",
                            modifier = Modifier.padding(RedSpacing.md),
                            style = RedTypography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = RedTheme.colors.accentRed
                        )
                    }

                    Text(
                        text = if (isFa)
                            "• زمان اختصاصی (Proper Time τ): زمانی است که توسط ساعت همراه مسافر اندازه‌گیری می‌شود.\n\n• انقباض طول (Length Contraction): مسیر حرکت از دید مسافر متحرک کوتاه می‌شود (L' = L/γ).\n\n• در سرعت‌های معمولی، γ بسیار نزدیک به ۱ است و محاسبات با فیزیک نیوتنی کلاسیک یکی می‌شود."
                        else
                            "• Proper Time (τ): Time measured by the traveller's onboard clock.\n\n• Length Contraction: Distances shorten in the direction of motion for the moving traveller (L' = L/γ).\n\n• At low velocities, γ ≈ 1, converging naturally to classical Newtonian physics.",
                        style = RedTypography.bodySmall,
                        color = RedTheme.colors.textSecondary
                    )
                }
            }
        )
    }
}

@Composable
private fun ObjectSelectionChip(
    modifier: Modifier = Modifier,
    label: String,
    selectedObject: CelestialObject,
    isFa: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(RedCornerRadius.lg))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(RedCornerRadius.lg),
        color = RedTheme.colors.surfaceElevated,
        border = BorderStroke(1.dp, RedTheme.colors.border)
    ) {
        Column(
            modifier = Modifier.padding(RedSpacing.md),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = RedTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = RedTheme.colors.accentRed
            )

            Text(
                text = if (isFa) selectedObject.nameFa else selectedObject.nameEn,
                style = RedTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = RedTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = selectedObject.category,
                style = RedTypography.labelSmall,
                color = RedTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ResultComparisonTile(
    modifier: Modifier = Modifier,
    title: String,
    timeValueStr: String,
    badgeText: String,
    isAccent: Boolean
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(RedCornerRadius.lg),
        color = if (isAccent) RedTheme.colors.accentRed.copy(alpha = 0.12f) else RedTheme.colors.surfaceElevated,
        border = BorderStroke(1.dp, if (isAccent) RedTheme.colors.accentRed.copy(alpha = 0.4f) else RedTheme.colors.border)
    ) {
        Column(
            modifier = Modifier.padding(RedSpacing.md),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = badgeText,
                style = RedTypography.labelSmall,
                color = if (isAccent) RedTheme.colors.accentRed else RedTheme.colors.textSecondary
            )

            Text(
                text = title,
                style = RedTypography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = RedTheme.colors.textPrimary
            )

            Text(
                text = timeValueStr,
                style = RedTypography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = if (isAccent) RedTheme.colors.accentRed else RedTheme.colors.textPrimary
            )
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    highlight: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = RedTypography.bodySmall,
            color = RedTheme.colors.textSecondary
        )
        Text(
            text = value,
            style = RedTypography.bodyMedium.copy(
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal
            ),
            color = if (highlight) RedTheme.colors.accentRed else RedTheme.colors.textPrimary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ObjectSearchModal(
    title: String,
    allObjects: List<CelestialObject>,
    isFa: Boolean,
    onObjectSelected: (CelestialObject) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, allObjects) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allObjects else {
            allObjects.filter {
                it.nameFa.lowercase().contains(q) ||
                        it.nameEn.lowercase().contains(q) ||
                        it.category.lowercase().contains(q) ||
                        it.constellationFa.lowercase().contains(q) ||
                        it.constellationEn.lowercase().contains(q)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RedTheme.colors.surfaceElevated,
        shape = RoundedCornerShape(topStart = RedCornerRadius.xl, topEnd = RedCornerRadius.xl)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RedSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RedSpacing.md)
        ) {
            Text(
                text = title,
                style = RedTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = RedTheme.colors.textPrimary
            )

            OutlinedTextField(
                textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("object_search_dialog_input"),
                placeholder = {
                    Text(
                        text = if (isFa) "جستجوی جرم (زمین، خورشید، ماه، مریخ، آندرومدا...)" else "Search objects (Earth, Moon, Sun, Mars...)",
                        style = RedTypography.bodyMedium,
                        color = RedTheme.colors.textSecondary
                    )
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = RedTheme.colors.accentRed)
                },
                singleLine = true,
                shape = RoundedCornerShape(RedCornerRadius.md)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
            ) {
                items(filtered) { obj ->
                    Surface(
                        onClick = { onObjectSelected(obj) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_dialog_item_${obj.id}"),
                        shape = RoundedCornerShape(RedCornerRadius.window),
                        color = RedTheme.colors.surfaceElevated,
                        border = BorderStroke(1.dp, RedTheme.colors.border)
                    ) {
                        Row(
                            modifier = Modifier.padding(RedSpacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = if (isFa) obj.nameFa else obj.nameEn,
                                    style = RedTypography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = RedTheme.colors.textPrimary
                                )
                                Text(
                                    text = "${obj.type.nameEn} • ${obj.category}",
                                    style = RedTypography.labelSmall,
                                    color = RedTheme.colors.textSecondary
                                )
                            }

                            val distLyStr = if (obj.distanceLightYears < 0.01) {
                                if (isFa) "منظومه شمسی" else "Solar System"
                            } else {
                                "${String.format(Locale.US, "%.1f", obj.distanceLightYears)} ly"
                            }

                            Text(
                                text = distLyStr,
                                style = RedTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = RedTheme.colors.accentRed
                            )
                        }
                    }
                }
            }
        }
    }
}
