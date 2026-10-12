package com.alijafari.red.astronomy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.R
import com.alijafari.red.astronomy.astro_engine.*
import com.alijafari.red.astronomy.data.catalog.AstronomyCatalog
import com.alijafari.red.astronomy.domain.*
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.MainViewModel
import com.alijafari.red.astronomy.ui.components.HeroSkyCanvas
import com.alijafari.red.astronomy.ui.components.EclipseDetailModal
import com.alijafari.red.astronomy.ui.components.MoonPhaseRender
import com.alijafari.red.astronomy.ui.theme.*
import com.alijafari.red.astronomy.util.toPersianDigits
import java.util.Calendar

/**
 * The Moon card is a square whose corner radius is deliberately very heavy, so the radius itself reads
 * as the card's shape. The render is inset to [MoonRenderFillFraction] of the card, which keeps the
 * corners visible instead of being covered by the lunar disc.
 */
private val MoonCardCornerRadius = 56.dp
private const val MoonRenderFillFraction = 0.78f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onNavigateToTab: (Int) -> Unit
) {
    val isFa = uiState.language == AppLanguage.PERSIAN
    val activeTimeMs = remember(uiState.timeMachineState) {
        if (uiState.timeMachineState.mode == TimeMachineMode.SIMULATION) {
            uiState.timeMachineState.simulationTimeMs
        } else {
            System.currentTimeMillis()
        }
    }
    val jd = remember(activeTimeMs) { TimeEngine.getJulianDate(activeTimeMs) }
    val sunPos = remember(jd) { SunEngine.calculatePosition(jd) }
    val lastDeg = remember(jd, uiState.userLocation) {
        TimeEngine.getLAST(jd, uiState.userLocation.longitude)
    }

    // Sun altitude for twilight phase
    val sunEquatorial = CoordinateEngine.Equatorial(sunPos.raDeg, sunPos.decDeg)
    val sunHoriz = remember(sunPos, lastDeg, uiState.userLocation) {
        CoordinateEngine.equatorialToHorizontal(sunEquatorial, lastDeg, uiState.userLocation.latitude)
    }

    // Moon data
    val moonData = remember(jd, uiState.userLocation) {
        MoonEngine.calculateMoon(jd, uiState.userLocation.latitude, uiState.userLocation.longitude)
    }

    val allObjects = remember(jd) { AstronomyCatalog.getAllObjects(jd) }

    val sortedObjectsWithObs = remember(allObjects, lastDeg, sunHoriz, moonData, uiState.userLocation, uiState.bortleClass) {
        allObjects
            .filter { it.id != "planet_earth" && it.id != "sat_iss" && it.type != ObjectType.SATELLITE }
            .map { obj ->
                val eq = if (obj.id == "moon") {
                    CoordinateEngine.geocentricToTopocentric(
                        geocentric = CoordinateEngine.Equatorial(moonData.raDeg, moonData.decDeg),
                        geocentricDistanceKm = moonData.distanceKm,
                        lastDeg = lastDeg,
                        latitudeDeg = uiState.userLocation.latitude,
                        elevationM = uiState.userLocation.elevationMeters
                    )
                } else {
                    CoordinateEngine.Equatorial(obj.raDeg, obj.decDeg)
                }
                val horiz = CoordinateEngine.equatorialToHorizontal(
                    eq,
                    lastDeg,
                    uiState.userLocation.latitude,
                    uiState.userLocation.elevationMeters
                )
                val obs = ObservabilityEngine.calculateObservability(
                    altitudeDeg = horiz.altitudeDeg,
                    sunAltitudeDeg = sunHoriz.altitudeDeg,
                    moonIlluminationPercent = moonData.illuminationPercent,
                    objectMagnitude = obj.magnitude,
                    bortleClass = uiState.bortleClass,
                    objectType = obj.type,
                    objectId = obj.id
                )
                Triple(obj, horiz, obs)
            }
            .sortedWith(compareByDescending<Triple<CelestialObject, CoordinateEngine.Horizontal, ObservabilityEngine.ObservabilityResult>> { it.third.scorePercent }
                .thenByDescending { it.second.altitudeDeg })
    }

    // Astronomy Predictive Search State. The search lives in a header action and opens as an overlay,
    // so this only tracks the query plus whether the overlay is currently open.
    var searchQuery by remember { mutableStateOf("") }
    var isSearchOpen by remember { mutableStateOf(false) }
    val closeSkySearch = {
        isSearchOpen = false
        searchQuery = ""
    }
    BackHandler(enabled = isSearchOpen) { closeSkySearch() }
    var selectedEclipseResult by remember { mutableStateOf<EclipseEngine.EclipseResult?>(null) }

    val (solarEclipse, lunarEclipse) = remember(
        uiState.userLocation,
        activeTimeMs,
        uiState.calendarSystem
    ) {
        EclipseEngine.getNextEclipses(
            nowMs = activeTimeMs,
            userLatDeg = uiState.userLocation.latitude,
            userLonDeg = uiState.userLocation.longitude,
            elevationM = uiState.userLocation.elevationMeters,
            timezoneId = uiState.userLocation.timezoneId,
            calendarSystem = uiState.calendarSystem
        )
    }

    val filteredSearchResults = remember(searchQuery, sortedObjectsWithObs) {
        val q = searchQuery.trim().lowercase()
        sortedObjectsWithObs.filter { (obj, _, _) ->
            if (q.isEmpty()) true else {
                obj.nameFa.lowercase().contains(q) ||
                obj.nameEn.lowercase().contains(q) ||
                obj.constellationFa.lowercase().contains(q) ||
                obj.constellationEn.lowercase().contains(q) ||
                obj.category.lowercase().contains(q)
            }
        }
    }

    selectedEclipseResult?.let { eclipseRes ->
        val detailedInfo = remember(eclipseRes, uiState.userLocation) {
            EclipseEngine.computeDetailedInfo(
                result = eclipseRes,
                userLatDeg = uiState.userLocation.latitude,
                userLonDeg = uiState.userLocation.longitude,
                timezoneId = uiState.userLocation.timezoneId
            )
        }
        EclipseDetailModal(
            detailedInfo = detailedInfo,
            language = uiState.language,
            onDismiss = { selectedEclipseResult = null }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_screen_column"),
            contentPadding = PaddingValues(horizontal = RedSpacing.lg, vertical = RedSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(RedSpacing.lg)
        ) {
            // 0. HOME HEADER — Centered RED Title, Contextual Location Pill & Essential Actions
            item {
                val locationName = if (isFa) uiState.userLocation.cityNameFa else uiState.userLocation.cityNameEn
                val latHem = if (uiState.userLocation.latitude >= 0.0) "N" else "S"
                val lonHem = if (uiState.userLocation.longitude >= 0.0) "E" else "W"
                val coordsRaw = String.format(
                    java.util.Locale.US,
                    "%.2f°%s, %.2f°%s",
                    kotlin.math.abs(uiState.userLocation.latitude),
                    latHem,
                    kotlin.math.abs(uiState.userLocation.longitude),
                    lonHem
                )
                val coordsText = if (isFa) coordsRaw.toPersianDigits() else coordsRaw

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = RedSpacing.md, vertical = RedSpacing.xs)
                        .testTag("home_red_header"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RedSpacing.xs)
                ) {
                        // Top Action Bar with Centered "RED"
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            // Favorites bookmark action (Start)
                            IconButton(
                                onClick = { viewModel.setShowFavoritesDialog(true) },
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .testTag("top_favorites_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.BookmarkBorder,
                                    contentDescription = stringResource(R.string.favorites_and_history),
                                    tint = RedTheme.colors.textPrimary,
                                    modifier = Modifier.size(RedIconSize.sm)
                                )
                            }

                            // Centered brand title
                            Text(
                                text = if (isFa) "زیگ" else "ZIG",
                                style = TextStyle(
                                    fontFamily = LocalAppFontFamily.current,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    letterSpacing = if (isFa) 0.sp else 3.sp
                                ),
                                color = RedTheme.colors.textPrimary,
                                modifier = Modifier.testTag("home_red_title")
                            )

                            // Essential actions (End): the Sky Search action opens the search overlay,
                            // Settings opens the settings sheet.
                            Row(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { isSearchOpen = true },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .testTag("top_search_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Search,
                                        contentDescription = if (isFa) "جستجوی اجرام آسمان" else "Search the sky",
                                        tint = RedTheme.colors.textPrimary,
                                        modifier = Modifier.size(RedIconSize.sm)
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.setShowSettingsDialog(true) },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .testTag("top_settings_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Settings,
                                        contentDescription = stringResource(R.string.settings),
                                        tint = RedTheme.colors.textPrimary,
                                        modifier = Modifier.size(RedIconSize.sm)
                                    )
                                }
                            }
                        }

                        // Understated, quiet location selector beneath RED
                        Surface(
                            onClick = { viewModel.setShowLocationSelector(true) },
                            shape = RoundedCornerShape(RedCornerRadius.pill),
                            color = RedTheme.colors.surfaceElevated.copy(alpha = 0.7f),
                            border = BorderStroke(0.75.dp, RedTheme.colors.border.copy(alpha = 0.5f)),
                            modifier = Modifier.testTag("home_location_selector_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = RedSpacing.md, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.LocationOn,
                                    contentDescription = "Location",
                                    tint = RedTheme.colors.accentRed,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = locationName,
                                    style = RedTypographyTokens.bodySecondary.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    ),
                                    color = RedTheme.colors.textPrimary
                                )
                                Text(
                                    text = "•",
                                    style = RedTypographyTokens.caption,
                                    color = RedTheme.colors.textTertiary
                                )
                                Text(
                                    text = coordsText,
                                    style = RedTypographyTokens.caption.copy(fontSize = 11.sp),
                                    color = RedTheme.colors.textSecondary
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Change Location",
                                    tint = RedTheme.colors.textTertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            // 1. MOON CARD — the Moon render on its own square, heavily rounded card. This is the whole
            //    of the retired Moon screen: the live render, and nothing else.
            item {
                Surface(
                    shape = RoundedCornerShape(MoonCardCornerRadius),
                    color = RedTheme.colors.surfaceElevated,
                    border = BorderStroke(0.75.dp, RedTheme.colors.border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .testTag("home_moon_card")
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        MoonPhaseRender(
                            moonData = moonData,
                            sunHoriz = sunHoriz,
                            latitude = uiState.userLocation.latitude,
                            modifier = Modifier.fillMaxSize(MoonRenderFillFraction)
                        )
                    }
                }
            }

            // 2. HERO SKY CANVAS — Level 1: Hero Content
            item {
                HeroSkyCanvas(
                    uiState = uiState,
                    viewModel = viewModel,
                    modifier = Modifier.testTag("home_header_card")
                )
            }

            // 3. WHAT'S UP TONIGHT? — Level 2: Contextual Astronomical Information
            item {
                val tonightEvents = remember(uiState.userLocation, jd, isFa) {
                    WhatsUpTonightEngine.calculateTonightEvents(
                        jd = jd,
                        userLatDeg = uiState.userLocation.latitude,
                        userLonDeg = uiState.userLocation.longitude,
                        isFa = isFa
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                ) {
                    RedSectionHeader(
                        title = if (isFa) "امشب در آسمان" else "What's Up Tonight",
                        subtitle = if (isFa) "رویدادهای شاخص نجومی برای مختصات شما"
                        else "Key celestial events for your coordinates"
                    )

                    if (tonightEvents.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(RedCornerRadius.lg),
                            color = RedTheme.colors.surfaceElevated,
                            border = BorderStroke(0.75.dp, RedTheme.colors.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isFa) "امشب رویداد شاخصی ثبت نشده است." else "No major celestial events tonight.",
                                style = RedTypographyTokens.bodySecondary,
                                color = RedTheme.colors.textSecondary,
                                modifier = Modifier.padding(RedSpacing.lg)
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(RedCornerRadius.xl),
                            color = RedTheme.colors.surfaceElevated,
                            border = BorderStroke(0.75.dp, RedTheme.colors.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("home_highlights_card")
                        ) {
                            Column(modifier = Modifier.padding(vertical = RedSpacing.xs)) {
                                tonightEvents.take(5).forEachIndexed { idx, event ->
                                    TonightEventRow(
                                        event = event,
                                        isTopRanked = idx == 0,
                                        isFa = isFa,
                                        onClick = {
                                            when {
                                                event.id.startsWith("solar_") -> selectedEclipseResult = solarEclipse
                                                event.id.startsWith("lunar_") -> selectedEclipseResult = lunarEclipse
                                                else -> event.targetObject?.let { obj ->
                                                    viewModel.openObjectDetail(obj)
                                                }
                                            }
                                        }
                                    )
                                    if (idx < minOf(4, tonightEvents.size - 1)) {
                                        RedHairlineDivider(modifier = Modifier.padding(horizontal = RedSpacing.md))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. UPCOMING ECLIPSES SECTION — Level 3: Supporting Astronomical Events
            item {
                val (primaryEclipse, secondaryEclipse) = if (solarEclipse.localPeakTimeMs <= lunarEclipse.localPeakTimeMs) {
                    solarEclipse to lunarEclipse
                } else {
                    lunarEclipse to solarEclipse
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                ) {
                    RedSectionHeader(
                        title = if (isFa) "گرفتگی‌های نجومی" else "Upcoming Eclipses",
                        subtitle = if (isFa) "محاسبه دقیق کسوف و خسوف بر اساس موقعیت شما"
                        else "Calculated precisely for your location"
                    )

                    Surface(
                        shape = RoundedCornerShape(RedCornerRadius.xl),
                        color = RedTheme.colors.surfaceElevated,
                        border = BorderStroke(0.75.dp, RedTheme.colors.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_next_eclipses_card")
                    ) {
                        Column(modifier = Modifier.padding(vertical = RedSpacing.xs)) {
                            // Nearest / Primary Eclipse (chronologically first)
                            EclipseItemRow(
                                isSolar = primaryEclipse.event.isSolar,
                                isPrimary = true,
                                title = if (isFa) primaryEclipse.event.nameFa else primaryEclipse.event.nameEn,
                                dateStr = if (isFa) primaryEclipse.formattedDateFa else primaryEclipse.formattedDateEn,
                                visibilityInfo = if (isFa) primaryEclipse.localVisibilityTextFa else primaryEclipse.localVisibilityTextEn,
                                isLocallyVisible = primaryEclipse.isLocallyVisible,
                                isFa = isFa,
                                onClick = { selectedEclipseResult = primaryEclipse }
                            )

                            RedHairlineDivider(modifier = Modifier.padding(horizontal = RedSpacing.md))

                            // Secondary Eclipse (chronologically second)
                            EclipseItemRow(
                                isSolar = secondaryEclipse.event.isSolar,
                                isPrimary = false,
                                title = if (isFa) secondaryEclipse.event.nameFa else secondaryEclipse.event.nameEn,
                                dateStr = if (isFa) secondaryEclipse.formattedDateFa else secondaryEclipse.formattedDateEn,
                                visibilityInfo = if (isFa) secondaryEclipse.localVisibilityTextFa else secondaryEclipse.localVisibilityTextEn,
                                isLocallyVisible = secondaryEclipse.isLocallyVisible,
                                isFa = isFa,
                                onClick = { selectedEclipseResult = secondaryEclipse }
                            )
                        }
                    }
                }
            }

            // Bottom spacing to completely clear the floating bottom navigation bar
            item {
                Spacer(modifier = Modifier.height(112.dp))
            }
        }

        // 5. SKY SEARCH OVERLAY — opened by the search action in the header above. It floats over the
        // feed instead of scrolling with it, so the search bar always sits at the very top of the screen.
        AnimatedVisibility(
            visible = isSearchOpen,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 6 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it / 6 }
        ) {
            SkySearchOverlay(
                searchQuery = searchQuery,
                onQueryChange = { searchQuery = it },
                results = filteredSearchResults,
                isFa = isFa,
                uiState = uiState,
                viewModel = viewModel,
                moonData = moonData,
                jd = jd,
                onDismiss = { closeSkySearch() }
            )
        }
    }
}

/**
 * Full-screen Sky Search overlay.
 *
 * The search used to be a section inside the Home feed. It now opens from the search action in the
 * header, so the bar always sits at the very top of the screen next to Settings. The dimmed feed is a
 * sibling of the panel rather than a parent, so a tap outside the panel closes the search while taps
 * inside it are never swallowed.
 */
@Composable
private fun SkySearchOverlay(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    results: List<Triple<CelestialObject, CoordinateEngine.Horizontal, ObservabilityEngine.ObservabilityResult>>,
    isFa: Boolean,
    uiState: MainUiState,
    viewModel: MainViewModel,
    moonData: MoonEngine.MoonData,
    jd: Double,
    onDismiss: () -> Unit
) {
    // The bar is the reason the overlay exists, so it takes focus as soon as it is composed.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val displayedPredictiveItems = remember(results) { results.take(6) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_astronomy_search_overlay")
    ) {
        // Scrim: taps on the dimmed feed dismiss the search.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .testTag("home_astronomy_search_scrim")
        )

        // Inset by the keyboard so the results stay reachable while the field is focused.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                shape = RoundedCornerShape(RedCornerRadius.xl),
                color = RedTheme.colors.surfaceElevated,
                border = BorderStroke(0.75.dp, RedTheme.colors.border),
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(RedSpacing.md)
                    .fillMaxWidth()
                    .testTag("home_astronomy_search_card")
            ) {
                Column(modifier = Modifier.padding(vertical = RedSpacing.xs)) {
                    OutlinedTextField(
                        textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                        value = searchQuery,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = RedSpacing.sm)
                            .focusRequester(focusRequester)
                            .testTag("home_astronomy_search_input"),
                        placeholder = {
                            Text(
                                text = if (isFa) "نام سیاره، ستاره، سحابی..." else "Search planets, stars, nebulae...",
                                style = RedTypographyTokens.bodySecondary,
                                color = RedTheme.colors.textTertiary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                tint = RedTheme.colors.accentRed,
                                modifier = Modifier.size(RedIconSize.sm)
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onQueryChange("") }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = if (isFa) "پاک کردن" else "Clear",
                                            tint = RedTheme.colors.textSecondary,
                                            modifier = Modifier.size(RedIconSize.sm)
                                        )
                                    }
                                }
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        imageVector = Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = if (isFa) "بستن جستجو" else "Close search",
                                        tint = RedTheme.colors.textSecondary,
                                        modifier = Modifier.size(RedIconSize.md)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(RedCornerRadius.lg),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RedTheme.colors.accentRed,
                            unfocusedBorderColor = RedTheme.colors.border,
                            focusedContainerColor = RedTheme.colors.surfaceElevated,
                            unfocusedContainerColor = RedTheme.colors.surfaceElevated
                        )
                    )

                    if (displayedPredictiveItems.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = RedSpacing.lg),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isFa) "هیچ جرم متناسبی پیدا نشد." else "No matching celestial object found.",
                                style = RedTypographyTokens.bodySecondary,
                                color = RedTheme.colors.textTertiary
                            )
                        }
                    } else {
                        Column(modifier = Modifier.padding(top = RedSpacing.xs)) {
                            displayedPredictiveItems.forEachIndexed { idx, (obj, horiz, obs) ->
                                val riseSetStr = remember(obj, moonData, uiState.userLocation, jd, isFa) {
                                    if (obj.id == "moon") {
                                        val tz = TimeEngine.resolveTimeZone(uiState.userLocation.timezoneId)
                                        val rStr = TimeEngine.formatTime24h(moonData.moonriseTimeMs ?: 0L, isFa, tz)
                                        val sStr = TimeEngine.formatTime24h(moonData.moonsetTimeMs ?: 0L, isFa, tz)
                                        if (isFa) "طلوع: $rStr | غروب: $sStr" else "Rise: $rStr | Set: $sStr"
                                    } else {
                                        val rst = CoordinateEngine.calculateRiseSetTransit(
                                            raDeg = obj.raDeg,
                                            decDeg = obj.decDeg,
                                            latDeg = uiState.userLocation.latitude,
                                            lonDeg = uiState.userLocation.longitude,
                                            jd = jd,
                                            isFa = isFa
                                        )
                                        if (isFa) "طلوع: ${rst.riseTimeStr} | غروب: ${rst.setTimeStr}" else "Rise: ${rst.riseTimeStr} | Set: ${rst.setTimeStr}"
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.openObjectDetail(obj) }
                                        .padding(horizontal = RedSpacing.md, vertical = RedSpacing.sm)
                                        .testTag("predictive_search_item_${obj.id}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(RedSpacing.md),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        CelestialObjectIcon(
                                            type = obj.type,
                                            tint = RedTheme.colors.accentRed,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                            Text(
                                                text = if (isFa) obj.nameFa else obj.nameEn,
                                                style = RedTypographyTokens.bodyPrimary.copy(fontWeight = FontWeight.SemiBold),
                                                color = RedTheme.colors.textPrimary
                                            )
                                            Text(
                                                text = riseSetStr,
                                                style = RedTypographyTokens.caption.copy(fontSize = 11.sp),
                                                color = RedTheme.colors.accentRed
                                            )
                                        }
                                    }

                                    val altInt = horiz.altitudeDeg.toInt()
                                    val statusBadge = if (horiz.altitudeDeg > 0.0) {
                                        if (isFa) "ارتفاع ${altInt}°".toPersianDigits() else "Alt ${altInt}°"
                                    } else {
                                        if (isFa) "زیر افق" else "Below Horizon"
                                    }

                                    if (horiz.altitudeDeg > 0.0) {
                                        RedStatusBadge(
                                            text = statusBadge,
                                            statusColor = RedTheme.colors.statusSuccess,
                                            containerColor = RedTheme.colors.statusSuccessContainer
                                        )
                                    } else {
                                        RedBadge(
                                            text = statusBadge,
                                            backgroundColor = RedTheme.colors.surfaceGrouped,
                                            textColor = RedTheme.colors.textTertiary,
                                            borderColor = RedTheme.colors.border
                                        )
                                    }
                                }

                                if (idx < displayedPredictiveItems.size - 1) {
                                    RedHairlineDivider(modifier = Modifier.padding(horizontal = RedSpacing.md))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CelestialObjectIcon(
    type: ObjectType?,
    modifier: Modifier = Modifier,
    tint: Color = RedTheme.colors.accentRed
) {
    val icon = when (type) {
        ObjectType.PLANET, ObjectType.DWARF_PLANET -> Icons.Outlined.Public
        ObjectType.MOON -> Icons.Outlined.NightsStay
        ObjectType.SUN -> Icons.Outlined.WbSunny
        ObjectType.STAR, ObjectType.ASTERISM -> Icons.Outlined.Star
        ObjectType.DEEP_SKY, ObjectType.GALAXY, ObjectType.NEBULA,
        ObjectType.STAR_CLUSTER, ObjectType.GLOBULAR_CLUSTER, ObjectType.BLACK_HOLE -> Icons.Outlined.AutoAwesome
        ObjectType.SATELLITE -> Icons.Outlined.Sensors
        ObjectType.METEOR_SHOWER -> Icons.Outlined.FlashOn
        ObjectType.CONSTELLATION -> Icons.Outlined.Grain
        ObjectType.REFERENCE_POINT, null -> Icons.Outlined.Place
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier
    )
}

@Composable
private fun EclipseItemRow(
    isSolar: Boolean,
    isPrimary: Boolean,
    title: String,
    dateStr: String,
    visibilityInfo: String,
    isLocallyVisible: Boolean,
    isFa: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = RedSpacing.md, vertical = RedSpacing.sm)
            .testTag("eclipse_item_row_${title.lowercase().replace(' ', '_')}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RedSpacing.md),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isSolar) Icons.Outlined.WbSunny else Icons.Outlined.NightsStay,
                contentDescription = null,
                tint = if (isPrimary) RedTheme.colors.accentRed else RedTheme.colors.textSecondary,
                modifier = Modifier.size(22.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    style = RedTypographyTokens.bodyPrimary.copy(
                        fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = RedTheme.colors.textPrimary
                )
                Text(
                    text = dateStr,
                    style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Medium),
                    color = if (isPrimary) RedTheme.colors.accentRed else RedTheme.colors.textSecondary
                )
                Text(
                    text = visibilityInfo,
                    style = RedTypographyTokens.caption.copy(fontSize = 11.sp),
                    color = RedTheme.colors.textSecondary
                )
            }
        }

        if (isLocallyVisible) {
            RedStatusBadge(
                text = if (isFa) "قابل رصد" else "Locally Visible",
                statusColor = RedTheme.colors.statusSuccess,
                containerColor = RedTheme.colors.statusSuccessContainer
            )
        } else {
            RedBadge(
                text = if (isFa) "غیرقابل رصد" else "Not Visible",
                backgroundColor = RedTheme.colors.surfaceGrouped,
                textColor = RedTheme.colors.textTertiary,
                borderColor = RedTheme.colors.border
            )
        }
    }
}

@Composable
private fun TonightEventRow(
    event: WhatsUpTonightEngine.TonightEvent,
    isTopRanked: Boolean,
    isFa: Boolean,
    onClick: () -> Unit
) {
    val eventIcon = when {
        event.targetObject != null -> when (event.targetObject.type) {
            ObjectType.PLANET, ObjectType.DWARF_PLANET -> Icons.Outlined.Public
            ObjectType.MOON -> Icons.Outlined.NightsStay
            ObjectType.SUN -> Icons.Outlined.WbSunny
            ObjectType.STAR, ObjectType.ASTERISM -> Icons.Outlined.Star
            ObjectType.DEEP_SKY, ObjectType.GALAXY, ObjectType.NEBULA,
            ObjectType.STAR_CLUSTER, ObjectType.GLOBULAR_CLUSTER, ObjectType.BLACK_HOLE -> Icons.Outlined.AutoAwesome
            ObjectType.SATELLITE -> Icons.Outlined.Sensors
            ObjectType.METEOR_SHOWER -> Icons.Outlined.FlashOn
            ObjectType.CONSTELLATION -> Icons.Outlined.Grain
            ObjectType.REFERENCE_POINT -> Icons.Outlined.Place
        }
        event.id.contains("solar") -> Icons.Outlined.WbSunny
        event.id.contains("lunar") -> Icons.Outlined.NightsStay
        event.id.contains("meteor") -> Icons.Outlined.FlashOn
        event.id.contains("iss") -> Icons.Outlined.Sensors
        else -> Icons.Outlined.AutoAwesome
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = RedSpacing.md, vertical = RedSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RedSpacing.md),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = eventIcon,
                contentDescription = null,
                tint = if (isTopRanked) RedTheme.colors.accentRed else RedTheme.colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = if (isFa) event.titleFa else event.titleEn,
                    style = RedTypographyTokens.bodyPrimary.copy(
                        fontWeight = if (isTopRanked) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = RedTheme.colors.textPrimary
                )
                Text(
                    text = if (isFa) event.explanationFa else event.explanationEn,
                    style = RedTypographyTokens.caption,
                    color = RedTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isFa) event.timeOrDateStrFa else event.timeOrDateStrEn,
                        style = RedTypographyTokens.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = RedTheme.colors.accentRed
                    )
                    Text(
                        text = "•",
                        style = RedTypographyTokens.caption,
                        color = RedTheme.colors.textTertiary
                    )
                    Text(
                        text = if (isFa) event.visibilityTextFa else event.visibilityTextEn,
                        style = RedTypographyTokens.caption.copy(fontSize = 11.sp),
                        color = RedTheme.colors.textSecondary
                    )
                }
            }
        }

        when (event.visibilityStatus) {
            WhatsUpTonightEngine.EventVisibilityStatus.OPTIMAL -> {
                RedStatusBadge(
                    text = if (isFa) "عالی" else "Optimal",
                    statusColor = RedTheme.colors.statusSuccess,
                    containerColor = RedTheme.colors.statusSuccessContainer
                )
            }
            WhatsUpTonightEngine.EventVisibilityStatus.GOOD -> {
                RedStatusBadge(
                    text = if (isFa) "خوب" else "Good",
                    statusColor = RedTheme.colors.accentRed,
                    containerColor = RedTheme.colors.accentRed.copy(alpha = 0.12f)
                )
            }
            WhatsUpTonightEngine.EventVisibilityStatus.MARGINAL -> {
                RedStatusBadge(
                    text = if (isFa) "متوسط" else "Marginal",
                    statusColor = RedTheme.colors.statusWarning,
                    containerColor = RedTheme.colors.statusWarningContainer
                )
            }
            WhatsUpTonightEngine.EventVisibilityStatus.NOT_VISIBLE -> {
                RedBadge(
                    text = if (isFa) "غیرقابل رصد" else "Not Visible",
                    backgroundColor = RedTheme.colors.surfaceGrouped,
                    textColor = RedTheme.colors.textTertiary,
                    borderColor = RedTheme.colors.border
                )
            }
        }
    }
}
