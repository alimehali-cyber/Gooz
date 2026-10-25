package com.alijafari.red.astronomy.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alijafari.red.astronomy.R
import com.alijafari.red.astronomy.astro_engine.*
import com.alijafari.red.astronomy.astro_engine.CoordinateEngineLegacy
import com.alijafari.red.astronomy.data.catalog.AstronomyCatalog
import com.alijafari.red.astronomy.domain.*
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.MainViewModel
import com.alijafari.red.astronomy.ui.components.EclipseDetailModal
import com.alijafari.red.astronomy.ui.components.HeroSkyCanvas
import com.alijafari.red.astronomy.ui.components.MoonPhaseRender
import com.alijafari.red.astronomy.ui.components.SkyCanvasPresentation
import com.alijafari.red.astronomy.ui.theme.*
import com.alijafari.red.astronomy.ui.theme.RedShapes
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.alijafari.red.astronomy.util.toPersianDigits
import kotlin.math.roundToInt

// ------------------------------------------------------------------------------------------------
// Recompute budgets for the Home screen
//
// HomeScreen now reads the shared 1 Hz clock (MainViewModel.skyLiveClock) so that live mode
// actually advances. Several of its derivations are far too expensive to repeat every second
// on the main thread, so each one is keyed on a coarse bucket of that clock instead of on the
// clock itself. These bucket sizes are engineering estimates chosen so that the visible value
// still refreshes faster than it meaningfully changes; they are NOT benchmarked measurements,
// and they should be profiled on a low-end device before being tightened.
// ------------------------------------------------------------------------------------------------

/** MoonEngine.calculateMoon runs ELP2000 plus an iterative MoonTimes rise/set solve. */
private const val MOON_REFRESH_MS = 60_000L

/** AstronomyCatalog.getAllObjects pushes all ~336 objects through calculateState, then Home maps and sorts them. */
private const val CATALOG_REFRESH_MS = 60_000L

/**
 * EclipseEngine.getNextEclipseSeries resolves the next 3 solar and next 3 lunar eclipses.
 * Each solar eclipse costs ~421 ephemeris evaluations (a 7 h scan at 1-minute steps, refined
 * to 10 s) and each lunar eclipse ~121, so one call is roughly 1260 solar + 360 lunar
 * evaluations — about 3x the cost of resolving a single eclipse of each kind. The eclipses
 * on offer only change every few months, so an hourly cadence is already generous.
 */
private const val ECLIPSE_REFRESH_MS = 3_600_000L

/**
 * Floors [timeMs] to a whole multiple of [bucketMs], so a `remember` keyed on the result
 * changes at most once per bucket.
 *
 * Simulation time is exempt: it only ever moves when the user moves the time machine, so
 * bucketing it would change existing behaviour for no benefit.
 */
private fun bucketMs(timeMs: Long, isSimulation: Boolean, bucketMs: Long): Long =
    if (isSimulation || bucketMs <= 0L) timeMs else timeMs - java.lang.Math.floorMod(timeMs, bucketMs)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onNavigateToTab: (Int) -> Unit
) {
    val isFa = uiState.language == AppLanguage.PERSIAN

    // --- Time selection ----------------------------------------------------------------
    //
    // LIVE mode now reads the single shared clock (MainViewModel.skyLiveClock) that
    // HeroSkyCanvas and LiveSkyBackdrop already use. It used to capture
    // System.currentTimeMillis() inside a remember() keyed on timeMachineState, so in
    // live mode Home froze at whatever instant it was composed at and never advanced:
    // HomeScreen has no ticker of its own, no observable time state, and nothing in
    // MainViewModel updates _uiState periodically. Reading a StateFlow inside
    // HeroSkyCanvas cannot help either, because that composable has its own restart
    // group and invalidating it never invalidates HomeScreen.
    //
    // SIMULATION mode is unchanged: it still follows timeMachineState.simulationTimeMs
    // and still only moves when the user moves the time machine.
    val liveTimeMs by viewModel.skyLiveClock.timeMs.collectAsState()
    val isSimulation = uiState.timeMachineState.mode == TimeMachineMode.SIMULATION
    val activeTimeMs = if (isSimulation) {
        uiState.timeMachineState.simulationTimeMs
    } else {
        liveTimeMs
    }

    // Now that activeTimeMs advances every second, the expensive derivations must NOT
    // key off it directly, or a 1 Hz clock would become a 1 Hz whole-catalog pass, a
    // 1 Hz iterative Moon rise/set solve and a 1 Hz eclipse search. Each is keyed on a
    // coarse bucket of the clock instead. See MOON_REFRESH_MS / CATALOG_REFRESH_MS /
    // ECLIPSE_REFRESH_MS: those numbers are conservative engineering estimates,
    // NOT measurements — they have not been benchmarked on a device.
    val moonKeyMs = bucketMs(activeTimeMs, isSimulation, MOON_REFRESH_MS)
    val catalogKeyMs = bucketMs(activeTimeMs, isSimulation, CATALOG_REFRESH_MS)
    val eclipseKeyMs = bucketMs(activeTimeMs, isSimulation, ECLIPSE_REFRESH_MS)

    // Cheap, and correct to keep live: this is the clock the user reads, and the
    // instant the hero canvas draws the sky for.
    val jd = remember(activeTimeMs) { TimeEngine.getJulianDate(activeTimeMs) }

    // Whole-catalog pass: ~336 objects through AstroDispatchEngine.calculateState.
    val catalogJd = remember(catalogKeyMs) { TimeEngine.getJulianDate(catalogKeyMs) }
    val allObjects = remember(catalogJd) { AstronomyCatalog.getAllObjects(catalogJd) }
    val catalogLastDeg = remember(catalogJd, uiState.userLocation) {
        TimeEngine.getLAST(catalogJd, uiState.userLocation.longitude)
    }

    // Sun altitude for the twilight phase.
    //
    // Keyed on the CATALOG bucket, NOT on the 1 Hz `jd`. `sunHoriz` is also an input to
    // sortedObjectsWithObs below, so deriving it from the live clock would drag the
    // whole ~336-object filter/map/sort along at 1 Hz and silently defeat
    // CATALOG_REFRESH_MS. Bucketing costs nothing observable: solar altitude moves by
    // at most ~15 deg/hour (0.25 deg/min), while the twilight bands it selects between
    // (0, -6, -12, -18 deg) are 6 deg apart, so a 60 s snapshot is finer than the
    // output resolution by more than an order of magnitude. It also removes a 1 Hz
    // SunEngine.calculatePosition and a 1 Hz TimeEngine.getLAST.
    val sunPos = remember(catalogJd) { SunEngine.calculatePosition(catalogJd) }
    val sunEquatorial = CoordinateEngine.Equatorial(sunPos.raDeg, sunPos.decDeg)
    val sunHoriz = remember(sunPos, catalogLastDeg, uiState.userLocation) {
        CoordinateEngine.equatorialToHorizontal(sunEquatorial, catalogLastDeg, uiState.userLocation.latitude)
    }

    // Moon data — expensive: ELP2000 plus an iterative MoonTimes rise/set solve.
    val moonJd = remember(moonKeyMs) { TimeEngine.getJulianDate(moonKeyMs) }
    val moonData = remember(moonJd, uiState.userLocation) {
        MoonEngine.calculateMoon(moonJd, uiState.userLocation.latitude, uiState.userLocation.longitude)
    }

    val sortedObjectsWithObs = remember(allObjects, catalogLastDeg, sunHoriz, moonData, uiState.userLocation, uiState.bortleClass) {
        allObjects
            .filter { it.id != "planet_earth" && it.id != "sat_iss" && it.type != ObjectType.SATELLITE }
            .map { obj ->
                val eq = if (obj.id == "moon") {
                    CoordinateEngine.geocentricToTopocentric(
                        geocentric = CoordinateEngine.Equatorial(moonData.raDeg, moonData.decDeg),
                        geocentricDistanceKm = moonData.distanceKm,
                        lastDeg = catalogLastDeg,
                        latitudeDeg = uiState.userLocation.latitude,
                        elevationM = uiState.userLocation.elevationMeters
                    )
                } else {
                    CoordinateEngine.Equatorial(obj.raDeg, obj.decDeg)
                }
                val horiz = CoordinateEngine.equatorialToHorizontal(
                    eq,
                    catalogLastDeg,
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

    // The Moon card and the Moon window scrub through one shared day offset owned by the ViewModel, so
    // dragging either one moves both. Leaving Home resets it, exactly as the Moon screen used to reset
    // when you navigated away from it.
    // Bucketed so an idle tick never re-solves MoonTimes, while a user scrub still
    // moves the card as soon as the offset crosses a bucket boundary.
    val moonCardJd = TimeEngine.getJulianDate(
        bucketMs(activeTimeMs, isSimulation, MOON_REFRESH_MS) +
            (uiState.moonDayOffset.toDouble() * 86_400_000.0).toLong()
    )
    val moonCardData = remember(moonCardJd, uiState.userLocation) {
        MoonEngine.calculateMoon(moonCardJd, uiState.userLocation.latitude, uiState.userLocation.longitude)
    }
    val moonCardSunHoriz = remember(moonCardJd, uiState.userLocation) {
        val scrubSun = SunEngine.calculatePosition(moonCardJd)
        val scrubLast = TimeEngine.getLAST(moonCardJd, uiState.userLocation.longitude)
        CoordinateEngine.equatorialToHorizontal(
            equatorial = CoordinateEngine.Equatorial(scrubSun.raDeg, scrubSun.decDeg),
            lastDeg = scrubLast,
            latitudeDeg = uiState.userLocation.latitude
        )
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.setMoonDayOffset(0f) }
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

    // The eclipse search resolves 3 solar + 3 lunar eclipses synchronously during
    // composition (~1260 + ~360 ephemeris evaluations). It is keyed on eclipseKeyMs so
    // it runs at most once per ECLIPSE_REFRESH_MS rather than once per tick.
    // NOTE: WhatsUpTonightEngine below performs a second, identical search. Removing
    // that duplicate needs an API change to pass the result in, so it is left as-is
    // here and recorded as follow-up work.
    val (nextSolarEclipses, nextLunarEclipses) = remember(
        uiState.userLocation,
        eclipseKeyMs,
        uiState.calendarSystem
    ) {
        EclipseEngine.getNextEclipseSeries(
            nowMs = eclipseKeyMs,
            userLatDeg = uiState.userLocation.latitude,
            userLonDeg = uiState.userLocation.longitude,
            elevationM = uiState.userLocation.elevationMeters,
            timezoneId = uiState.userLocation.timezoneId,
            calendarSystem = uiState.calendarSystem,
            count = 3
        )
    }

    // Which eclipse family the "Upcoming Eclipses" card is listing. It defaults to
    // whichever family has the soonest event at the user's location, and flips when
    // the Solar/Lunar chips below (or a "Tonight" eclipse row) are tapped.
    var eclipseKindIsSolar by remember {
        mutableStateOf(
            (nextSolarEclipses.firstOrNull()?.localPeakTimeMs ?: Long.MAX_VALUE) <=
                (nextLunarEclipses.firstOrNull()?.localPeakTimeMs ?: Long.MAX_VALUE)
        )
    }
    val listedEclipses = if (eclipseKindIsSolar) nextSolarEclipses else nextLunarEclipses

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

    // The whole screen is a BoxWithConstraints so the scene can claim a fixed share of the viewport: a
    // LazyColumn item is measured with an unbounded height, so the height has to be decided out here.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sceneHeight = (maxHeight * 0.56f).coerceIn(320.dp, 600.dp)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_screen_column"),
            contentPadding = PaddingValues(bottom = RedSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(RedSpacing.lg)
        ) {
            // 0. THE SKY — the full-bleed scene. It starts at the very top of the window, runs behind the
            //    status bar, carries the header and the readout, and the Moon card overlaps its bottom edge.
            //    Everything else on Home starts below it.
            item {
                HomeSkyHero(
                    uiState = uiState,
                    viewModel = viewModel,
                    isFa = isFa,
                    sceneHeight = sceneHeight,
                    moonData = moonCardData,
                    sunHoriz = moonCardSunHoriz,
                    moonJd = moonCardJd,
                    onScrubDays = { delta -> viewModel.nudgeMoonDayOffset(delta) },
                    onOpenSearch = { isSearchOpen = true }
                )
            }

            // 2. WHAT'S UP TONIGHT? — Level 2: Contextual Astronomical Information
            item {
                val tonightEvents = remember(uiState.userLocation, catalogJd, isFa) {
                    WhatsUpTonightEngine.calculateTonightEvents(
                        jd = catalogJd,
                        userLatDeg = uiState.userLocation.latitude,
                        userLonDeg = uiState.userLocation.longitude,
                        isFa = isFa
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = RedSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                ) {
                    RedSectionHeader(
                        title = if (isFa) "امشب در آسمان" else "What's Up Tonight",
                        subtitle = if (isFa) "رویدادهای شاخص نجومی برای مختصات شما"
                        else "Key celestial events for your coordinates"
                    )

                    if (tonightEvents.isEmpty()) {
                        LiquidGlassSurface(
                            shape = squircleShape(RedCornerRadius.lg),
                            fallbackColor = RedTheme.colors.surfaceElevated,
                            fallbackBorder = BorderStroke(0.75.dp, RedTheme.colors.border),
                            modifier = Modifier.fillMaxWidth(),
                            style = LiquidGlassDefaults.Card,
                            fallbackShadowElevation = RedElevation.none,
                        ) {
                            Text(
                                text = if (isFa) "امشب رویداد شاخصی ثبت نشده است." else "No major celestial events tonight.",
                                style = RedTypographyTokens.bodySecondary,
                                color = RedTheme.colors.textSecondary,
                                modifier = Modifier.padding(RedSpacing.lg)
                            )
                        }
                    } else {
                        LiquidGlassSurface(
                            shape = squircleShape(RedCornerRadius.xl),
                            fallbackColor = RedTheme.colors.surfaceElevated,
                            fallbackBorder = BorderStroke(0.75.dp, RedTheme.colors.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("home_highlights_card"),
                            style = LiquidGlassDefaults.Card,
                            fallbackShadowElevation = RedElevation.none,
                        ) {
                            Column(modifier = Modifier.padding(vertical = RedSpacing.xs)) {
                                tonightEvents.take(5).forEachIndexed { idx, event ->
                                    TonightEventRow(
                                        event = event,
                                        isTopRanked = idx == 0,
                                        isFa = isFa,
                                        onClick = {
                                            when {
                                                event.id.startsWith("solar_") -> {
                                                    eclipseKindIsSolar = true
                                                    selectedEclipseResult = nextSolarEclipses.firstOrNull()
                                                }
                                                event.id.startsWith("lunar_") -> {
                                                    eclipseKindIsSolar = false
                                                    selectedEclipseResult = nextLunarEclipses.firstOrNull()
                                                }
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

            // 3. UPCOMING ECLIPSES SECTION — Level 3: Supporting Astronomical Events
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = RedSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                ) {
                    RedSectionHeader(
                        title = if (isFa) "گرفتگی‌های نجومی" else "Upcoming Eclipses",
                        subtitle = if (isFa) "محاسبه دقیق کسوف و خسوف بر اساس موقعیت شما"
                        else "Calculated precisely for your location"
                    )

                    LiquidGlassSurface(
                        shape = squircleShape(RedCornerRadius.xl),
                        fallbackColor = RedTheme.colors.surfaceElevated,
                        fallbackBorder = BorderStroke(0.75.dp, RedTheme.colors.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_next_eclipses_card"),
                        style = LiquidGlassDefaults.Card,
                        fallbackShadowElevation = RedElevation.none,
                    ) {
                        Column(modifier = Modifier.padding(vertical = RedSpacing.xs)) {
                            // Solar / Lunar selector — each family lists its own next 3 eclipses,
                            // every one of them evaluated for the user's location.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = RedSpacing.md, vertical = RedSpacing.xs),
                                horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                            ) {
                                EclipseFamilyChip(
                                    selected = eclipseKindIsSolar,
                                    onClick = { eclipseKindIsSolar = true },
                                    label = if (isFa) "☀️ خورشیدگرفتگی" else "☀️ Solar",
                                    modifier = Modifier.testTag("eclipse_family_solar")
                                )
                                EclipseFamilyChip(
                                    selected = !eclipseKindIsSolar,
                                    onClick = { eclipseKindIsSolar = false },
                                    label = if (isFa) "🌙 ماه‌گرفتگی" else "🌙 Lunar",
                                    modifier = Modifier.testTag("eclipse_family_lunar")
                                )
                            }

                            // The next 3 eclipses of the selected family, chronological.
                            listedEclipses.forEachIndexed { idx, eclipse ->
                                if (idx > 0) {
                                    RedHairlineDivider(modifier = Modifier.padding(horizontal = RedSpacing.md))
                                }
                                EclipseItemRow(
                                    isSolar = eclipse.event.isSolar,
                                    isPrimary = idx == 0,
                                    title = if (isFa) eclipse.event.nameFa else eclipse.event.nameEn,
                                    dateStr = if (isFa) eclipse.formattedDateFa else eclipse.formattedDateEn,
                                    visibilityInfo = if (isFa) eclipse.localVisibilityTextFa else eclipse.localVisibilityTextEn,
                                    isLocallyVisible = eclipse.isLocallyVisible,
                                    visibilityGrade = eclipse.visibility.grade,
                                    isFa = isFa,
                                    onClick = { selectedEclipseResult = eclipse }
                                )
                            }
                        }
                    }
                }
            }

            // Bottom spacing to completely clear the floating bottom navigation bar
            item {
                Spacer(modifier = Modifier.height(112.dp))
            }
        }

        // 4. SKY SEARCH OVERLAY — opened by the search action in the header above. It floats over the
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
                catalogJd = catalogJd,
                onDismiss = { closeSkySearch() }
            )
        }

        // 5. FULLSCREEN SKY VIEWER — the same sky canvas, landscape and edge to edge. The open flag is
        //    ViewModel state so the landscape rotation cannot close it (see MainUiState.showSkyViewer).
        if (uiState.showSkyViewer) {
            FullscreenSkyViewer(
                uiState = uiState,
                viewModel = viewModel,
                onDismiss = { viewModel.setShowSkyViewer(false) }
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
    /**
     * Julian date on the CATALOG bucket, not the 1 Hz clock. The rise/set strings below
     * are recomputed per result row, so they must sit on the same coarse snapshot as
     * [results] (which derives from the catalog-bucketed `sortedObjectsWithObs`) rather
     * than being re-solved every second.
     */
    catalogJd: Double,
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
            LiquidGlassSurface(
                shape = squircleShape(RedCornerRadius.window),
                style = LiquidGlassDefaults.Window,
                fallbackColor = RedTheme.colors.surfaceElevated,
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
                        shape = squircleShape(RedCornerRadius.lg),
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
                                val riseSetStr = remember(obj, moonData, uiState.userLocation, catalogJd, isFa) {
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
                                            jd = catalogJd,
                                            isFa = isFa,
                                            timezoneId = uiState.userLocation.timezoneId,
                                            horizonAltDeg = CoordinateEngineLegacy.horizonAltDegFor(obj.id)
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

/**
 * Solar / Lunar selector chip for the "Upcoming Eclipses" card.
 *
 * Uses the app's red accent for the selected family so the chip reads as part of the
 * same visual language as [EclipseItemRow]'s primary-row icon.
 */
@Composable
private fun EclipseFamilyChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Medium)
            )
        },
        modifier = modifier,
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = RedTheme.colors.surfaceGrouped,
            labelColor = RedTheme.colors.textSecondary,
            selectedContainerColor = RedTheme.colors.accentRed.copy(alpha = 0.16f),
            selectedLabelColor = RedTheme.colors.accentRed
        )
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
    /** Verdict from [EclipseVisibility]; drives the badge shown on the right. */
    visibilityGrade: EclipseVisibility.Grade = EclipseVisibility.Grade.NOT_OBSERVABLE,
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

        // The list is already filtered to observable events (see EclipseVisibility),
        // so an event can still be "in the sky" yet have nothing worth seeing — that
        // is the fallback case, and it must not wear the same badge as a good one.
        when {
            visibilityGrade == EclipseVisibility.Grade.SPECTACULAR -> {
                RedStatusBadge(
                    text = if (isFa) "دیدنی و بی‌نظیر" else "Spectacular",
                    statusColor = RedTheme.colors.statusSuccess,
                    containerColor = RedTheme.colors.statusSuccessContainer
                )
            }
            visibilityGrade == EclipseVisibility.Grade.EXCELLENT ||
                visibilityGrade == EclipseVisibility.Grade.GOOD -> {
                RedStatusBadge(
                    text = if (isFa) visibilityGrade.labelFa else visibilityGrade.labelEn,
                    statusColor = RedTheme.colors.statusSuccess,
                    containerColor = RedTheme.colors.statusSuccessContainer
                )
            }
            visibilityGrade == EclipseVisibility.Grade.MARGINAL -> {
                RedStatusBadge(
                    text = if (isFa) visibilityGrade.labelFa else visibilityGrade.labelEn,
                    statusColor = RedTheme.colors.statusWarning,
                    containerColor = RedTheme.colors.statusWarningContainer
                )
            }
            isLocallyVisible -> {
                RedBadge(
                    text = if (isFa) "تقریباً دیده نمی‌شود" else "Barely Visible",
                    isAccent = true
                )
            }
            else -> {
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

/**
 * The full-bleed Home scene: the sky canvas, the header that rides on it, the expand affordance, and the
 * Moon card that overlaps its lower edge.
 *
 * This is the screen's whole top band. It is edge to edge — no horizontal padding, no rounded corners, and
 * it starts at y = 0 so the sky runs under the status bar — which is what makes it read as a place rather
 * than as a card. The feed begins underneath it (see the LazyColumn in [HomeScreen]).
 *
 * The scene's own layers live in [HeroSkyCanvas] with [SkyCanvasPresentation.SCENE]: a low horizon, the
 * layered ground band, drifting cloud veils, the observer, and the time/condition readout. The header here
 * is only the app chrome: brand, actions, location.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSkyHero(
    uiState: MainUiState,
    viewModel: MainViewModel,
    isFa: Boolean,
    sceneHeight: Dp,
    moonData: MoonEngine.MoonData,
    sunHoriz: CoordinateEngine.Horizontal,
    moonJd: Double,
    onScrubDays: (Float) -> Unit,
    onOpenSearch: () -> Unit
) {
    val cardHeight = 132.dp
    // How far the Moon card rides up over the scene. The scene stays a full rectangle; only the card moves.
    // A generous overlap keeps the ground to a strip: with the horizon at 0.70 of the scene, the visible
    // ground is about 26 dp tall and everything else is under the card.
    val cardOverlap = 96.dp

    val locationName = if (isFa) uiState.userLocation.cityNameFa else uiState.userLocation.cityNameEn
    val coordsText = remember(uiState.userLocation, isFa) {
        val latHem = if (uiState.userLocation.latitude >= 0.0) "N" else "S"
        val lonHem = if (uiState.userLocation.longitude >= 0.0) "E" else "W"
        val raw = String.format(
            java.util.Locale.US,
            "%.2f°%s, %.2f°%s",
            kotlin.math.abs(uiState.userLocation.latitude),
            latHem,
            kotlin.math.abs(uiState.userLocation.longitude),
            lonHem
        )
        if (isFa) raw.toPersianDigits() else raw
    }
    val scrim = Color.Black

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(sceneHeight + cardHeight - cardOverlap)
            .testTag("home_sky_scene")
    ) {
        HeroSkyCanvas(
            uiState = uiState,
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxWidth()
                .height(sceneHeight),
            presentation = SkyCanvasPresentation.SCENE
        )

        // The top scrim. Clouds drift under this band, so the status bar and the header keep their contrast
        // on every sky the app can draw — the failure mode of the reference design that put moving clouds
        // behind the city name.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sceneHeight * 0.34f)
                .background(
                    Brush.verticalGradient(
                        0.0f to scrim.copy(alpha = 0.38f),
                        0.55f to scrim.copy(alpha = 0.14f),
                        1.0f to Color.Transparent
                    )
                )
        )

        // --- HEADER: brand, actions, location ------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = RedSpacing.lg, vertical = RedSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
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
                        tint = Color.White,
                        modifier = Modifier.size(RedIconSize.sm)
                    )
                }

                Text(
                    text = if (isFa) "زیگ" else "ZIG",
                    style = TextStyle(
                        fontFamily = LocalAppFontFamily.current,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = if (isFa) 0.sp else 3.sp,
                        color = Color.White,
                        shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 8f)
                    ),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("home_red_title")
                )

                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenSearch,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .testTag("top_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = if (isFa) "جستجوی اجرام آسمان" else "Search the sky",
                            tint = Color.White,
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
                            tint = Color.White,
                            modifier = Modifier.size(RedIconSize.sm)
                        )
                    }
                }
            }

            // Location sits under the brand, on the sky, the way a weather app puts the city above the
            // temperature — the readout itself is drawn by the canvas, just above the horizon.
            Surface(
                onClick = { viewModel.setShowLocationSelector(true) },
                shape = RedShapes.capsule,
                color = scrim.copy(alpha = 0.26f),
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.30f)),
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
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = locationName,
                        style = RedTypographyTokens.bodySecondary.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        ),
                        color = Color.White
                    )
                    Text(
                        text = "•",
                        style = RedTypographyTokens.caption,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                    Text(
                        text = coordsText,
                        style = RedTypographyTokens.caption.copy(fontSize = 11.sp),
                        color = Color.White.copy(alpha = 0.72f)
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Change Location",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // --- EXPAND: open the fullscreen landscape sky viewer --------------------------------
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = RedSpacing.lg, bottom = cardHeight - cardOverlap + RedSpacing.md)
                .size(38.dp)
                .liquidGlass(
                    shape = CircleShape,
                    style = LiquidGlassDefaults.Pill,
                    clearGlass = true,
                    fallbackColor = Color.Black.copy(alpha = 0.38f),
                    fallbackBorder = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.25f)),
                    glassBorder = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.32f))
                )
                .clickable { viewModel.setShowSkyViewer(true) }
                .testTag("home_sky_expand_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.FitScreen,
                contentDescription = if (isFa) "نمایش تمام‌صفحه آسمان" else "Open the sky fullscreen",
                tint = Color(0xFFF8FAFF),
                modifier = Modifier.size(17.dp)
            )
        }

        // --- THE MOON CARD: the first card of the feed, riding on the scene ------------------
        MoonPhaseCard(
            uiState = uiState,
            viewModel = viewModel,
            moonData = moonData,
            sunHoriz = sunHoriz,
            jd = moonJd,
            onScrubDays = onScrubDays,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = RedSpacing.lg)
                .fillMaxWidth()
                .height(cardHeight)
        )
    }
}

/**
 * The Moon card: a wide glass row carrying the Moon's true phase and the date it is drawn for.
 *
 * Drag the Moon to scrub days — the same ±30 day gesture the Moon screen had — and tap the card to open the
 * Moon window with everything else. The render's dark side is transparent, so no black disc sits behind the
 * Moon; the card's glass shows straight through it.
 */
@Composable
private fun MoonPhaseCard(
    uiState: MainUiState,
    viewModel: MainViewModel,
    moonData: MoonEngine.MoonData,
    sunHoriz: CoordinateEngine.Horizontal,
    jd: Double,
    onScrubDays: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isFa = uiState.language == AppLanguage.PERSIAN

    // The label follows the same instant the render is drawn for — the app's effective time (Time Machine
    // included) plus the scrub offset — never the wall clock. Otherwise a simulated night would draw one
    // Moon under a label for another date.
    val dateText = remember(jd, uiState.calendarSystem, isFa) {
        TimeEngine.formatDate(AstroTime.fromJd(jd).utcMs, uiState.calendarSystem, isFa).let {
            if (isFa) it.toPersianDigits() else it
        }
    }
    val phaseName = if (isFa) moonData.phaseNameFa else moonData.phaseNameEn
    val illuminationText = if (isFa) {
        "${moonData.illuminationPercent.roundToInt()}٪ روشن".toPersianDigits()
    } else {
        "${moonData.illuminationPercent.roundToInt()}% illuminated"
    }

    LiquidGlassSurface(
        modifier = modifier.testTag("home_moon_card"),
        shape = squircleShape(RedCornerRadius.window),
        style = LiquidGlassDefaults.Card,
        fallbackColor = RedTheme.colors.surfaceElevated,
        onClick = { viewModel.setShowMoonWindow(true) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(RedSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RedSpacing.md)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            onScrubDays(dragAmount / 16f)
                        }
                    }
                    .testTag("home_moon_card_render"),
                contentAlignment = Alignment.Center
            ) {
                MoonPhaseRender(
                    moonData = moonData,
                    sunHoriz = sunHoriz,
                    latitude = uiState.userLocation.latitude,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = phaseName,
                    style = RedTypographyTokens.bodyPrimary.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = RedTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = illuminationText,
                    style = RedTypographyTokens.caption.copy(fontSize = 12.sp),
                    color = RedTheme.colors.textSecondary,
                    maxLines = 1
                )
                Text(
                    text = dateText,
                    style = RedTypographyTokens.caption.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    ),
                    color = RedTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The fullscreen sky viewer: the same [HeroSkyCanvas], horizontal and edge to edge, with its pills back.
 * Landscape is locked only while this is open, and the previous orientation is restored on the way out.
 */
@Composable
private fun FullscreenSkyViewer(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val isFa = uiState.language == AppLanguage.PERSIAN
    // Not LocalContext: the app replaces it with a locale-configured context that wraps no Activity.
    val hostView = LocalView.current
    val activity = remember(hostView) { hostView.context.findActivity() }

    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    BackHandler { onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("sky_canvas_fullscreen")
        ) {
            HeroSkyCanvas(
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(RedSpacing.md)
                    .size(38.dp)
                    .liquidGlass(
                        shape = CircleShape,
                        style = LiquidGlassDefaults.Pill,
                        clearGlass = true,
                        fallbackColor = Color.Black.copy(alpha = 0.38f),
                        fallbackBorder = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.25f)),
                        glassBorder = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.32f))
                    )
                    .clickable(onClick = onDismiss)
                    .testTag("sky_canvas_fullscreen_close"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = if (isFa) "بستن نمای تمام‌صفحه" else "Close fullscreen sky",
                    tint = Color(0xFFF8FAFF),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Walks a possibly wrapped [Context] up to the [Activity] that owns it, or null if there is none. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
