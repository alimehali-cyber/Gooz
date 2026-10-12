package com.alijafari.red.astronomy.ui.components

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.R
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.domain.CalendarSystem
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import com.alijafari.red.astronomy.domain.ThemeMode
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.MainViewModel
import com.alijafari.red.astronomy.ui.backdrop.AppBackdropMode
import com.alijafari.red.astronomy.ui.theme.*

/**
 * Data structure for the Bortle Dark-Sky scale reference guide.
 */
private data class BortleClassInfo(
    val classNumber: Int,
    val nameEn: String,
    val nameFa: String,
    val descEn: String,
    val descFa: String
)

private val BORTLE_CLASSES = listOf(
    BortleClassInfo(
        classNumber = 1,
        nameEn = "Excellent Dark Sky",
        nameFa = "آسمان کاملاً تاریک و بکر",
        descEn = "Zodiacal light visible and bright, Milky Way casts shadows, M33 directly naked-eye.",
        descFa = "نور منطقه‌البروجی درخشان، کهکشان راه شیری سایه می‌اندازد، کهکشان M33 با چشم غیرمسلح پیداست."
    ),
    BortleClassInfo(
        classNumber = 2,
        nameEn = "Typical Truly Dark Sky",
        nameFa = "آسمان تاریک واقعی",
        descEn = "Milky Way highly structured, zodiacal light prominent, M33 easily seen with naked eye.",
        descFa = "راه شیری با ساختار پیچیده و پرجزئیات، نور زودیاک نمایان، M33 به سادگی با چشم غیرمسلح دیده می‌شود."
    ),
    BortleClassInfo(
        classNumber = 3,
        nameEn = "Rural Sky",
        nameFa = "آسمان روستایی",
        descEn = "Milky Way shows rich detail, light pollution faint at horizon, M33 glimpsed with averted vision.",
        descFa = "راه شیری جزئیات خوبی دارد، آلودگی نوری خفیف در افق، M33 با نگاه مایل دیده می‌شود."
    ),
    BortleClassInfo(
        classNumber = 4,
        nameEn = "Rural / Suburban Transition",
        nameFa = "مرز روستایی و حومه شهر",
        descEn = "Milky Way visible above 40°, light domes obvious in several directions, M33 in binoculars.",
        descFa = "راه شیری بالای ۴۰ درجه افق پیداست، گنبدهای نوری در چند جهت، M33 در دوچشمی پیداست."
    ),
    BortleClassInfo(
        classNumber = 5,
        nameEn = "Suburban Sky",
        nameFa = "آسمان حومه شهر",
        descEn = "Milky Way washed out and faint at zenith, light pollution visible in most directions.",
        descFa = "راه شیری در سرسو بسیار کم‌فروغ، آلودگی نوری در بیشتر جهات افق به وضوح پیداست."
    ),
    BortleClassInfo(
        classNumber = 6,
        nameEn = "Bright Suburban Sky",
        nameFa = "آسمان روشن حومه شهر",
        descEn = "Milky Way only faintly perceptible overhead, sky glow bright within 35° of horizon.",
        descFa = "راه شیری فقط با زحمت در سرسو دیده می‌شود، درخشش آسمان تا ۳۵ درجه از افق بالا آمده."
    ),
    BortleClassInfo(
        classNumber = 7,
        nameEn = "Suburban / Urban Transition",
        nameFa = "مرز حومه و شهر",
        descEn = "Milky Way invisible, sky background has strong grey/yellow tint, brightest DSOs barely visible.",
        descFa = "راه شیری اصلاً دیده نمی‌شود، زمینه آسمان خاکستری-زرد، فقط اجرام بسیار روشن با تلسکوپ پیداست."
    ),
    BortleClassInfo(
        classNumber = 8,
        nameEn = "City Sky",
        nameFa = "آسمان شهری",
        descEn = "Sky glows whitish-grey, stars forming constellation outlines hard to see, faint stars lost.",
        descFa = "آسمان سفید-خاکستری می‌درخشد، فقط ستاره‌های پرنور صور فلکی اصلی قابل تشخیص‌اند."
    ),
    BortleClassInfo(
        classNumber = 9,
        nameEn = "Inner-City Sky",
        nameFa = "مرکز کلان‌شهر",
        descEn = "Only brightest stars and planets visible, sky brilliantly illuminated by urban lighting.",
        descFa = "فقط سیارات و پرنورترین ستارگان (مانند شباهنگ) قابل رویت هستند؛ زمینه آسمان روشن است."
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isFa = uiState.language == AppLanguage.PERSIAN

    // Expandable section states (all collapsed by default, tapped to expand/collapse)
    var isLanguageExpanded by remember { mutableStateOf(false) }
    var isCalendarExpanded by remember { mutableStateOf(false) }
    var isAppThemeExpanded by remember { mutableStateOf(false) }
    var isSkyCanvasThemeExpanded by remember { mutableStateOf(false) }
    var isLiquidGlassExpanded by remember { mutableStateOf(false) }
    var isAppBackdropExpanded by remember { mutableStateOf(false) }
    var isBortleExpanded by remember { mutableStateOf(false) }

    // Formatted collapsed labels
    val languageLabel = when (uiState.language) {
        AppLanguage.PERSIAN -> if (isFa) "فارسی" else "Persian"
        AppLanguage.ENGLISH -> if (isFa) "انگلیسی" else "English"
    }

    val calendarLabel = when (uiState.calendarSystem) {
        CalendarSystem.SOLAR_HIJRI -> if (isFa) "هجری شمسی" else "Solar Hijri"
        CalendarSystem.GREGORIAN -> if (isFa) "میلادی" else "Gregorian"
    }

    val appThemeLabel = when (uiState.themeMode) {
        ThemeMode.DARK_NAVY -> if (isFa) "سرمه‌ای" else "Deep Navy"
        ThemeMode.OLED_BLACK -> if (isFa) "مشکی خالص OLED" else "Pitch OLED"
        ThemeMode.DYNAMIC_SILK -> if (isFa) "ابریشم پویا (Silk)" else "Dynamic Silk"
        ThemeMode.DYNAMIC_SKY -> if (isFa) "آسمان پویا" else "Dynamic Sky"
        ThemeMode.LIGHT -> if (isFa) "روشن" else "Light"
        ThemeMode.PAPERCRAFT_PASTEL -> if (isFa) "کاغذ دست‌ساز" else "Papercraft"
    }

    val skyCanvasThemeLabel = when (uiState.skyCanvasTheme) {
        SkyCanvasTheme.REAL_SKY -> if (isFa) "آسمان واقعی" else "Real Sky"
        SkyCanvasTheme.ATMOSPHERIC_SKY -> if (isFa) "آسمان جوی" else "Atmospheric"
        SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> if (isFa) "تک‌رنگ" else "Monochrome"
        SkyCanvasTheme.KIDS_WATERCOLOR -> if (isFa) "آبرنگ" else "WaterColor"
        SkyCanvasTheme.OBSERVATORY -> if (isFa) "رصدخانه" else "Observatory"
        SkyCanvasTheme.PAPERCRAFT_DIORAMA -> if (isFa) "دیوراما کاغذ برجسته" else "Papercraft"
    }

    val liquidGlassStatusLabel = if (uiState.isLiquidGlassEnabled) {
        if (isFa) "فعال" else "On"
    } else {
        if (isFa) "خاموش" else "Off"
    }

    val liveSkyStatusLabel = when (uiState.appBackdropMode) {
        AppBackdropMode.LIVE_SKY -> stringResource(R.string.live_sky_setting)
        AppBackdropMode.NGC_1929 -> stringResource(R.string.backdrop_ngc1929_setting)
        AppBackdropMode.CEPHEUS -> stringResource(R.string.backdrop_cepheus_setting)
        AppBackdropMode.NONE -> if (isFa) "خاموش" else "Off"
    }

    val bortleLabel = if (isFa) "کلاس ${uiState.bortleClass}" else "Class ${uiState.bortleClass}"

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_dialog"),
        shape = RoundedCornerShape(RedCornerRadius.xl),
        containerColor = RedTheme.colors.surfaceElevated,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)
            ) {
                Surface(
                    shape = CircleShape,
                    color = RedTheme.colors.accentRed.copy(alpha = 0.12f),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = RedTheme.colors.accentRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.app_settings),
                    style = RedTypographyTokens.sectionHeading,
                    color = RedTheme.colors.textPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RedSpacing.sm + 2.dp)
            ) {
                // 1. Language Dropdown Section
                ExpandableSettingsSection(
                    title = stringResource(R.string.application_language),
                    currentValueLabel = languageLabel,
                    icon = Icons.Default.Language,
                    isExpanded = isLanguageExpanded,
                    onToggle = { isLanguageExpanded = !isLanguageExpanded },
                    testTag = "settings_section_language"
                ) {
                    SettingsOptionRow(
                        title = stringResource(R.string.language_persian),
                        subtitle = "فارسی (Persian)",
                        isSelected = uiState.language == AppLanguage.PERSIAN,
                        onClick = { viewModel.setLanguage(AppLanguage.PERSIAN) }
                    )
                    SettingsOptionRow(
                        title = stringResource(R.string.language_english),
                        subtitle = "English (US)",
                        isSelected = uiState.language == AppLanguage.ENGLISH,
                        onClick = { viewModel.setLanguage(AppLanguage.ENGLISH) }
                    )
                }

                // 2. Calendar System Dropdown Section
                ExpandableSettingsSection(
                    title = stringResource(R.string.calendar_system),
                    currentValueLabel = calendarLabel,
                    icon = Icons.Default.CalendarToday,
                    isExpanded = isCalendarExpanded,
                    onToggle = { isCalendarExpanded = !isCalendarExpanded },
                    testTag = "settings_section_calendar"
                ) {
                    SettingsOptionRow(
                        title = stringResource(R.string.calendar_solar_hijri),
                        subtitle = if (isFa) "تقویم خورشیدی رسمی ایران" else "Solar Hijri Calendar",
                        isSelected = uiState.calendarSystem == CalendarSystem.SOLAR_HIJRI,
                        onClick = { viewModel.setCalendarSystem(CalendarSystem.SOLAR_HIJRI) }
                    )
                    SettingsOptionRow(
                        title = stringResource(R.string.calendar_gregorian),
                        subtitle = if (isFa) "تقویم بین‌المللی میلادی" else "Gregorian International Calendar",
                        isSelected = uiState.calendarSystem == CalendarSystem.GREGORIAN,
                        onClick = { viewModel.setCalendarSystem(CalendarSystem.GREGORIAN) }
                    )
                }

                // 3. App Theme Dropdown Section
                ExpandableSettingsSection(
                    title = if (isFa) "پوسته برنامه" else "App Theme",
                    currentValueLabel = appThemeLabel,
                    icon = Icons.Default.Palette,
                    isExpanded = isAppThemeExpanded,
                    onToggle = { isAppThemeExpanded = !isAppThemeExpanded },
                    testTag = "settings_section_app_theme"
                ) {
                    SettingsOptionRow(
                        title = if (isFa) "سرمه‌ای (پیش‌فرض زیگ)" else "Deep Navy (ZIG Default)",
                        isSelected = uiState.themeMode == ThemeMode.DARK_NAVY,
                        onClick = { viewModel.setThemeMode(ThemeMode.DARK_NAVY) }
                    )
                    SettingsOptionRow(
                        title = if (isFa) "مشکی خالص OLED" else "Pitch OLED",
                        isSelected = uiState.themeMode == ThemeMode.OLED_BLACK,
                        onClick = { viewModel.setThemeMode(ThemeMode.OLED_BLACK) }
                    )
                    SettingsOptionRow(
                        title = if (isFa) "ابریشم پویا (Silk)" else "Dynamic Silk",
                        isSelected = uiState.themeMode == ThemeMode.DYNAMIC_SILK,
                        onClick = { viewModel.setThemeMode(ThemeMode.DYNAMIC_SILK) }
                    )
                    SettingsOptionRow(
                        title = if (isFa) "آسمان پویا" else "Dynamic Sky",
                        isSelected = uiState.themeMode == ThemeMode.DYNAMIC_SKY,
                        onClick = { viewModel.setThemeMode(ThemeMode.DYNAMIC_SKY) }
                    )
                    SettingsOptionRow(
                        title = if (isFa) "روشن (Light)" else "Light",
                        isSelected = uiState.themeMode == ThemeMode.LIGHT,
                        onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) }
                    )
                    SettingsOptionRow(
                        title = if (isFa) "کاغذ دست‌ساز (Papercraft)" else "Papercraft",
                        isSelected = uiState.themeMode == ThemeMode.PAPERCRAFT_PASTEL,
                        onClick = { viewModel.setThemeMode(ThemeMode.PAPERCRAFT_PASTEL) }
                    )
                }

                // 4. Sky Canvas Theme Dropdown Section
                ExpandableSettingsSection(
                    title = if (isFa) "سبک هنری آسمان (RMAE)" else "Sky Canvas Theme",
                    currentValueLabel = skyCanvasThemeLabel,
                    icon = Icons.Default.AutoAwesome,
                    isExpanded = isSkyCanvasThemeExpanded,
                    onToggle = { isSkyCanvasThemeExpanded = !isSkyCanvasThemeExpanded },
                    testTag = "settings_section_sky_theme"
                ) {
                    SettingsOptionRow(
                        title = if (isFa) "آسمان واقعی (Real Sky)" else "Real Sky",
                        isSelected = uiState.skyCanvasTheme == SkyCanvasTheme.REAL_SKY,
                        onClick = { viewModel.setSkyCanvasTheme(SkyCanvasTheme.REAL_SKY) }
                    )
                    SettingsOptionRow(
                        title = if (isFa) "آسمان جوی (Atmospheric)" else "Atmospheric Sky",
                        isSelected = uiState.skyCanvasTheme == SkyCanvasTheme.ATMOSPHERIC_SKY,
                        onClick = { viewModel.setSkyCanvasTheme(SkyCanvasTheme.ATMOSPHERIC_SKY) }
                    )
                    SettingsOptionRow(
                        title = if (isFa) "تک‌رنگ علمی (Monochrome)" else "Monochrome Scientific",
                        isSelected = uiState.skyCanvasTheme == SkyCanvasTheme.MONOCHROME_SCIENTIFIC,
                        onClick = { viewModel.setSkyCanvasTheme(SkyCanvasTheme.MONOCHROME_SCIENTIFIC) }
                    )
                    SettingsOptionRow(
                        title = if (isFa) "آبرنگ کودکانه (Watercolor)" else "Kids Watercolor",
                        isSelected = uiState.skyCanvasTheme == SkyCanvasTheme.KIDS_WATERCOLOR,
                        onClick = { viewModel.setSkyCanvasTheme(SkyCanvasTheme.KIDS_WATERCOLOR) }
                    )
                    SettingsOptionRow(
                        title = if (isFa) "رصدخانه (Observatory)" else "Observatory",
                        isSelected = uiState.skyCanvasTheme == SkyCanvasTheme.OBSERVATORY,
                        onClick = { viewModel.setSkyCanvasTheme(SkyCanvasTheme.OBSERVATORY) }
                    )
                    SettingsOptionRow(
                        title = if (isFa) "دیوراما کاغذ برجسته (Papercraft)" else "Papercraft Diorama",
                        isSelected = uiState.skyCanvasTheme == SkyCanvasTheme.PAPERCRAFT_DIORAMA,
                        onClick = { viewModel.setSkyCanvasTheme(SkyCanvasTheme.PAPERCRAFT_DIORAMA) }
                    )
                }

                // 5. Liquid Glass Dedicated Section
                ExpandableSettingsSection(
                    title = if (isFa) "Liquid Glass / شیشه مایع" else "Liquid Glass / شیشه مایع",
                    currentValueLabel = liquidGlassStatusLabel,
                    icon = Icons.Default.BlurOn,
                    isExpanded = isLiquidGlassExpanded,
                    onToggle = { isLiquidGlassExpanded = !isLiquidGlassExpanded },
                    statusColor = if (uiState.isLiquidGlassEnabled) RedTheme.colors.accentRed else RedTheme.colors.textSecondary,
                    testTag = "settings_section_liquid_glass"
                ) {
                    // Switch Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = RedSpacing.sm)
                        ) {
                            Text(
                                text = if (isFa) "شیشه مایع و فیزیک اپتیکی" else stringResource(R.string.liquid_glass_setting),
                                style = RedTypographyTokens.bodySecondary.copy(fontWeight = FontWeight.SemiBold),
                                color = RedTheme.colors.textPrimary
                            )
                            Text(
                                text = if (isFa) "شکست نور، کژی فیزیکی، بازتاب و شفافیت شیشه‌ای (اندروید ۱۳+)" else stringResource(R.string.liquid_glass_desc),
                                style = RedTypographyTokens.caption,
                                color = RedTheme.colors.textSecondary
                            )
                        }
                        Switch(
                            checked = uiState.isLiquidGlassEnabled,
                            onCheckedChange = { viewModel.setLiquidGlassEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = RedTheme.colors.accentRed,
                                uncheckedThumbColor = RedTheme.colors.textSecondary,
                                uncheckedTrackColor = RedTheme.colors.border
                            ),
                            modifier = Modifier.testTag("settings_liquid_glass_switch")
                        )
                    }

                    // Liquid Glass Fine-Tuning Controls (when enabled)
                    if (uiState.isLiquidGlassEnabled) {
                        val glassConfig = uiState.liquidGlassConfig

                        HorizontalDivider(color = RedTheme.colors.border.copy(alpha = 0.5f), thickness = 0.5.dp)

                        // Section Header & Reset
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isFa) "⚙️ شخصی‌سازی اپتیک شیشه" else "⚙️ Optical Parameters",
                                style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Bold),
                                color = RedTheme.colors.textPrimary
                            )
                            TextButton(
                                onClick = { viewModel.resetLiquidGlassConfig() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = if (isFa) "بازنشانی" else "Reset",
                                    style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Bold),
                                    color = RedTheme.colors.accentRed
                                )
                            }
                        }

                        // 1. Clarity
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isFa) "شفافیت و زلالی لنز (Clarity)" else "Lens Clarity",
                                    style = RedTypographyTokens.caption,
                                    color = RedTheme.colors.textPrimary
                                )
                                Text(
                                    text = "${(glassConfig.clarity * 100).toInt()}%",
                                    style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Bold),
                                    color = RedTheme.colors.accentRed
                                )
                            }
                            RedSlider(
                                value = glassConfig.clarity,
                                onValueChange = { viewModel.updateLiquidGlassConfig(glassConfig.copy(clarity = it)) },
                                valueRange = 0.0f..1.0f,
                                modifier = Modifier.testTag("liquid_glass_clarity_slider")
                            )
                        }

                        // 2. Glass Tint — the soft white film every glass card wears.
                        //    The Sky Canvas card is exempt: it stays optically pure at any setting.
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isFa) "ته‌رنگ سفید شیشه (Glass Tint)" else "Glass Tint",
                                    style = RedTypographyTokens.caption,
                                    color = RedTheme.colors.textPrimary
                                )
                                Text(
                                    text = "${(glassConfig.tintAlpha * 100).toInt()}%",
                                    style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Bold),
                                    color = RedTheme.colors.accentRed
                                )
                            }
                            RedSlider(
                                value = glassConfig.tintAlpha,
                                onValueChange = { viewModel.updateLiquidGlassConfig(glassConfig.copy(tintAlpha = it)) },
                                valueRange = 0.0f..0.4f,
                                modifier = Modifier.testTag("liquid_glass_tint_slider")
                            )
                            Text(
                                text = if (isFa) "یک لایه سفید بسیار ملایم روی کارت‌های شیشه‌ای؛ کارت آسمان همیشه شفاف می‌ماند."
                                else "A very soft white film on the glass cards. The Sky Canvas card always stays clear.",
                                style = RedTypographyTokens.caption.copy(fontSize = 10.sp),
                                color = RedTheme.colors.textTertiary
                            )
                        }

                        // 3. Refraction Depth
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isFa) "عمق شکست فیزیکی (Refraction Depth)" else "Refraction Depth",
                                    style = RedTypographyTokens.caption,
                                    color = RedTheme.colors.textPrimary
                                )
                                Text(
                                    text = "${glassConfig.refractionHeightDp.toInt()} dp",
                                    style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Bold),
                                    color = RedTheme.colors.accentRed
                                )
                            }
                            RedSlider(
                                value = glassConfig.refractionHeightDp,
                                onValueChange = { viewModel.updateLiquidGlassConfig(glassConfig.copy(refractionHeightDp = it)) },
                                valueRange = 0f..60f,
                                modifier = Modifier.testTag("liquid_glass_refraction_slider")
                            )
                        }

                        // 4. Refraction Warping ("خمش و پراش نور" wording)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isFa) "خمش و پراش نور (Refraction Warping)" else "Refraction Warping",
                                    style = RedTypographyTokens.caption,
                                    color = RedTheme.colors.textPrimary
                                )
                                Text(
                                    text = "${glassConfig.refractionAmountDp.toInt()} dp",
                                    style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Bold),
                                    color = RedTheme.colors.accentRed
                                )
                            }
                            RedSlider(
                                value = glassConfig.refractionAmountDp,
                                onValueChange = { viewModel.updateLiquidGlassConfig(glassConfig.copy(refractionAmountDp = it)) },
                                valueRange = 0f..60f,
                                modifier = Modifier.testTag("liquid_glass_warping_slider")
                            )
                        }

                        // 5. Gaussian Blur Radius
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isFa) "میزان تاری پس‌زمینه (Blur)" else "Background Blur",
                                    style = RedTypographyTokens.caption,
                                    color = RedTheme.colors.textPrimary
                                )
                                Text(
                                    text = "${glassConfig.blurRadiusDp.toInt()} dp",
                                    style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Bold),
                                    color = RedTheme.colors.accentRed
                                )
                            }
                            RedSlider(
                                value = glassConfig.blurRadiusDp,
                                onValueChange = { viewModel.updateLiquidGlassConfig(glassConfig.copy(blurRadiusDp = it)) },
                                valueRange = 0f..24f,
                                modifier = Modifier.testTag("liquid_glass_blur_slider")
                            )
                        }

                        HorizontalDivider(color = RedTheme.colors.border.copy(alpha = 0.5f), thickness = 0.5.dp)

                        // Optical Toggles Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = glassConfig.chromaticAberration,
                                onClick = { viewModel.updateLiquidGlassConfig(glassConfig.copy(chromaticAberration = !glassConfig.chromaticAberration)) },
                                label = { Text(text = if (isFa) "شکست رنگی" else "Chromatic", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RedTheme.colors.accentRed,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = glassConfig.hasHighlight,
                                onClick = { viewModel.updateLiquidGlassConfig(glassConfig.copy(hasHighlight = !glassConfig.hasHighlight)) },
                                label = { Text(text = if (isFa) "هایلایت لبه" else "Highlights", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RedTheme.colors.accentRed,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = glassConfig.hasShadow,
                                onClick = { viewModel.updateLiquidGlassConfig(glassConfig.copy(hasShadow = !glassConfig.hasShadow)) },
                                label = { Text(text = if (isFa) "سایه عمق" else "Shadow", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RedTheme.colors.accentRed,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 5b. App Backdrop Section. Single option, Live Sky, default OFF.
                ExpandableSettingsSection(
                    title = stringResource(R.string.app_backdrop_title),
                    currentValueLabel = liveSkyStatusLabel,
                    icon = Icons.Outlined.NightsStay,
                    isExpanded = isAppBackdropExpanded,
                    onToggle = { isAppBackdropExpanded = !isAppBackdropExpanded },
                    statusColor = if (uiState.appBackdropMode != AppBackdropMode.NONE) RedTheme.colors.accentRed else RedTheme.colors.textSecondary,
                    testTag = "settings_section_app_backdrop"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                        ) {
                            FilterChip(
                                selected = uiState.appBackdropMode == AppBackdropMode.NONE,
                                onClick = { viewModel.setAppBackdropMode(AppBackdropMode.NONE) },
                                label = { Text(text = if (isFa) "خاموش" else "Off", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RedTheme.colors.accentRed,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.testTag("settings_backdrop_off")
                            )
                            FilterChip(
                                selected = uiState.appBackdropMode == AppBackdropMode.LIVE_SKY,
                                onClick = { viewModel.setAppBackdropMode(AppBackdropMode.LIVE_SKY) },
                                label = { Text(text = stringResource(R.string.live_sky_setting), fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RedTheme.colors.accentRed,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_live_sky_switch")
                            )
                            FilterChip(
                                selected = uiState.appBackdropMode == AppBackdropMode.NGC_1929,
                                onClick = { viewModel.setAppBackdropMode(AppBackdropMode.NGC_1929) },
                                label = { Text(text = stringResource(R.string.backdrop_ngc1929_setting), fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RedTheme.colors.accentRed,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_backdrop_ngc1929")
                            )
                        }
                        FilterChip(
                            selected = uiState.appBackdropMode == AppBackdropMode.CEPHEUS,
                            onClick = { viewModel.setAppBackdropMode(AppBackdropMode.CEPHEUS) },
                            label = { Text(text = stringResource(R.string.backdrop_cepheus_setting), fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = RedTheme.colors.accentRed,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_backdrop_cepheus")
                        )
                        Text(
                            text = when (uiState.appBackdropMode) {
                                AppBackdropMode.LIVE_SKY -> stringResource(R.string.live_sky_desc)
                                AppBackdropMode.NGC_1929 -> stringResource(R.string.backdrop_ngc1929_desc)
                                AppBackdropMode.CEPHEUS -> stringResource(R.string.backdrop_cepheus_desc)
                                AppBackdropMode.NONE -> stringResource(R.string.backdrop_off_desc)
                            },
                            style = RedTypographyTokens.caption,
                            color = RedTheme.colors.textSecondary
                        )
                    }
                }

                // 6. Bortle Light-Pollution Dedicated Section & Guide
                ExpandableSettingsSection(
                    title = if (isFa) "آلودگی نوری (مقیاس بورتل)" else "Light Pollution (Bortle Scale)",
                    currentValueLabel = bortleLabel,
                    icon = Icons.Default.Brightness6,
                    isExpanded = isBortleExpanded,
                    onToggle = { isBortleExpanded = !isBortleExpanded },
                    testTag = "settings_section_bortle"
                ) {
                    // Bortle Slider Row
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isFa) "کلاس انتخابی بورتل:" else "Selected Bortle Class:",
                                style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = RedTheme.colors.textPrimary
                            )
                            val activeInfo = BORTLE_CLASSES.getOrNull(uiState.bortleClass - 1)
                            Text(
                                text = "${uiState.bortleClass} - ${if (isFa) activeInfo?.nameFa ?: "" else activeInfo?.nameEn ?: ""}",
                                style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Bold),
                                color = RedTheme.colors.accentRed
                            )
                        }

                        RedSlider(
                            value = uiState.bortleClass.toFloat(),
                            onValueChange = { viewModel.setBortleClass(it.toInt()) },
                            valueRange = 1f..9f,
                            steps = 7,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bortle_class_slider")
                        )
                    }

                    HorizontalDivider(color = RedTheme.colors.border.copy(alpha = 0.5f), thickness = 0.5.dp)

                    // Concise Bortle Scale Identification Guide
                    Text(
                        text = if (isFa) "راهنمای تطبیق وضعیت آسمان شب:" else "Sky Appearance Identification Guide:",
                        style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Bold),
                        color = RedTheme.colors.textSecondary
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        BORTLE_CLASSES.forEach { info ->
                            val isSelected = uiState.bortleClass == info.classNumber
                            LiquidGlassSurface(
                                shape = RoundedCornerShape(RedCornerRadius.window),
                                fallbackColor = if (isSelected) RedTheme.colors.accentRed.copy(alpha = 0.15f) else Color.Transparent,
                                fallbackBorder = BorderStroke(
                                    0.5.dp,
                                    if (isSelected) RedTheme.colors.accentRed.copy(alpha = 0.4f) else RedTheme.colors.border.copy(alpha = 0.2f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setBortleClass(info.classNumber) },
                                style = LiquidGlassDefaults.Card,
                                fallbackShadowElevation = RedElevation.none,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Class Number Badge
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.surfaceElevated,
                                        modifier = Modifier.padding(top = 1.dp)
                                    ) {
                                        Text(
                                            text = "${info.classNumber}",
                                            style = RedTypographyTokens.caption.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            ),
                                            color = if (isSelected) Color.White else RedTheme.colors.textSecondary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }

                                    // Name & Concise Description
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isFa) info.nameFa else info.nameEn,
                                            style = RedTypographyTokens.caption.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                            ),
                                            color = if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.textPrimary
                                        )
                                        Text(
                                            text = if (isFa) info.descFa else info.descEn,
                                            style = RedTypographyTokens.caption.copy(fontSize = 10.sp),
                                            color = RedTheme.colors.textSecondary,
                                            lineHeight = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 7. Developer Credit Card — Liquid Glass with Premium Fallback
                LiquidGlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RedSpacing.xs)
                        .testTag("developer_credit_card"),
                    shape = RoundedCornerShape(RedCornerRadius.lg),
                    style = LiquidGlassDefaults.Card,
                    fallbackColor = RedTheme.colors.surfaceGrouped
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(RedSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RedSpacing.md)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = RedTheme.colors.accentRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, RedTheme.colors.accentRed.copy(alpha = 0.3f)),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                SafeAppLogo(
                                    modifier = Modifier.size(30.dp),
                                    cornerRadius = 8.dp
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (isFa) "توسعه‌دهنده: " else "Developed by ",
                                    style = RedTypographyTokens.caption,
                                    color = RedTheme.colors.textSecondary
                                )
                                Text(
                                    text = "علی جعفری",
                                    style = RedTypographyTokens.bodyPrimary.copy(fontWeight = FontWeight.Bold),
                                    color = RedTheme.colors.textPrimary
                                )
                            }

                            Text(
                                text = "Ali Jafari • ZIG Astronomy Engine",
                                style = RedTypographyTokens.caption,
                                color = RedTheme.colors.textSecondary
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            // Telegram Glass Badge
                            val intentContext = context
                            Surface(
                                shape = RoundedCornerShape(RedCornerRadius.xs),
                                color = RedTheme.colors.accentRed.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, RedTheme.colors.accentRed.copy(alpha = 0.3f)),
                                modifier = Modifier.clickable {
                                    try {
                                        val tgIntent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://t.me/EdisonWasAThief")
                                        )
                                        intentContext.startActivity(tgIntent)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "✈️",
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "@EdisonWasAThief",
                                        style = RedTypographyTokens.caption.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = RedTheme.colors.accentRed
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(RedCornerRadius.md),
                colors = ButtonDefaults.buttonColors(containerColor = RedTheme.colors.accentRed)
            ) {
                Text(text = stringResource(R.string.ok), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * Reusable iOS-style expandable settings container with clean status badge and animated arrow.
 */
@Composable
private fun ExpandableSettingsSection(
    title: String,
    currentValueLabel: String,
    icon: ImageVector,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    statusColor: Color? = null,
    testTag: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    val rotationState by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "expand_rotation"
    )

    LiquidGlassSurface(
        shape = RoundedCornerShape(RedCornerRadius.window),
        fallbackColor = RedTheme.colors.surfaceGrouped,
        fallbackBorder = BorderStroke(
            1.dp,
            if (isExpanded) RedTheme.colors.accentRed.copy(alpha = 0.35f) else RedTheme.colors.border
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        style = LiquidGlassDefaults.Card,
        fallbackShadowElevation = RedElevation.none,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row (Clickable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = RedSpacing.md, vertical = RedSpacing.sm + 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isExpanded) RedTheme.colors.accentRed else RedTheme.colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = title,
                        style = RedTypographyTokens.bodyPrimary.copy(fontWeight = FontWeight.SemiBold),
                        color = RedTheme.colors.textPrimary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(RedCornerRadius.xs),
                        color = (statusColor ?: RedTheme.colors.accentRed).copy(alpha = 0.12f),
                        border = BorderStroke(0.5.dp, (statusColor ?: RedTheme.colors.accentRed).copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = currentValueLabel,
                            style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Bold),
                            color = statusColor ?: RedTheme.colors.accentRed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = RedTheme.colors.textSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(rotationState)
                    )
                }
            }

            // Expanded Options Area
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RedTheme.colors.surfaceVariant.copy(alpha = 0.35f))
                        .padding(horizontal = RedSpacing.md, vertical = RedSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RedSpacing.xs + 2.dp),
                    content = content
                )
            }
        }
    }
}

/**
 * Compact selectable option row with subtle highlight and checkmark.
 */
@Composable
private fun SettingsOptionRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    LiquidGlassSurface(
        shape = RoundedCornerShape(RedCornerRadius.window),
        fallbackColor = if (isSelected) RedTheme.colors.accentRed.copy(alpha = 0.12f) else Color.Transparent,
        fallbackBorder = BorderStroke(
            0.5.dp,
            if (isSelected) RedTheme.colors.accentRed.copy(alpha = 0.4f) else RedTheme.colors.border.copy(alpha = 0.2f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        style = LiquidGlassDefaults.Card,
        fallbackShadowElevation = RedElevation.none,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RedSpacing.sm + 2.dp, vertical = RedSpacing.xs + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = RedTypographyTokens.bodySecondary.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.textPrimary
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = RedTypographyTokens.caption.copy(fontSize = 11.sp),
                        color = RedTheme.colors.textSecondary
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = RedTheme.colors.accentRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
