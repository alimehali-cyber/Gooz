package com.alijafari.red.astronomy.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.astro_engine.*
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.domain.CalendarSystem
import com.alijafari.red.astronomy.domain.ThemeMode
import com.alijafari.red.astronomy.notification.AstroNotificationManager
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.MainViewModel
import com.alijafari.red.astronomy.ui.theme.*
import com.alijafari.red.astronomy.ui.theme.RedShapes
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.alijafari.red.astronomy.util.toPersianDigits
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

enum class MapTheme {
    AUTO,
    DARK,
    LIGHT
}

data class SatelliteMapPalette(
    val oceanColor: Color,
    val landColor: Color,
    val landStroke: Color,
    val gridColor: Color,
    val equatorColor: Color,
    val nightShadowColor: Color,
    val cityLightColor: Color,
    val orbitTrackColor: Color,
    val userPinColor: Color,
    val userTextColor: Color,
    val containerBg: Color,
    val containerBorder: Color,
    val isDarkBackground: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ISSScreen(
    uiState: MainUiState,
    viewModel: MainViewModel
) {
    val isFa = uiState.language == AppLanguage.PERSIAN
    // Text measured straight onto a Canvas never merges the theme's LocalTextStyle, so the locale
    // face is taken from the theme here and handed to each measure() call below.
    val canvasFontFamily = LocalAppFontFamily.current
    val context = LocalContext.current
    val isOnline = remember(context) { isNetworkAvailable(context) }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // Notification & Map Prefs
    val prefs = remember { context.getSharedPreferences("astro_prefs", Context.MODE_PRIVATE) }
    var isAutoAlertEnabled by remember {
        mutableStateOf(prefs.getBoolean("auto_satellite_alerts_enabled", prefs.getBoolean("iss_auto_alerts_enabled", false)))
    }
    var selectedLeadMinutes by remember {
        mutableStateOf(prefs.getInt("iss_alert_lead_minutes", 10))
    }
    var showLeadTimeSelectionDialog by remember { mutableStateOf(false) }

    // Map Theme State (Persisted: "AUTO", "DARK" or "LIGHT")
    val celestialLighting = LocalCelestialLighting.current
    var mapTheme by remember {
        val saved = prefs.getString("satellite_map_theme", MapTheme.AUTO.name)
        mutableStateOf(
            when (saved) {
                MapTheme.DARK.name -> MapTheme.DARK
                MapTheme.LIGHT.name -> MapTheme.LIGHT
                else -> MapTheme.AUTO
            }
        )
    }

    val activeThemeMode = when (mapTheme) {
        MapTheme.DARK -> ThemeMode.DARK
        MapTheme.LIGHT -> ThemeMode.LIGHT
        MapTheme.AUTO -> uiState.themeMode
    }

    val mapPalette = remember(activeThemeMode, celestialLighting) {
        when (activeThemeMode) {
            ThemeMode.GLASS -> {
                val isDark = celestialLighting.isDark
                SatelliteMapPalette(
                    oceanColor = celestialLighting.backgroundColor,
                    landColor = celestialLighting.surfaceColor,
                    landStroke = celestialLighting.outlineColor,
                    gridColor = celestialLighting.outlineColor.copy(alpha = if (isDark) 0.22f else 0.35f),
                    equatorColor = celestialLighting.primaryAccent.copy(alpha = 0.75f),
                    nightShadowColor = if (isDark) Color(0xCC050814) else Color(0x550F172A),
                    cityLightColor = Color(0xFFFBBF24),
                    orbitTrackColor = celestialLighting.primaryAccent,
                    userPinColor = celestialLighting.primaryAccent,
                    userTextColor = celestialLighting.textPrimaryColor,
                    containerBg = celestialLighting.surfaceVariantColor,
                    containerBorder = celestialLighting.outlineColor,
                    isDarkBackground = isDark
                )
            }
            ThemeMode.LIGHT -> {
                SatelliteMapPalette(
                    oceanColor = SoftCreamVariant,
                    landColor = SoftCreamSurface,
                    landStroke = SoftCreamCardBorder,
                    gridColor = Color(0x22182238),
                    equatorColor = OldMoneyChampagneGold,
                    nightShadowColor = Color(0x35111118),
                    cityLightColor = OldMoneyBronze,
                    orbitTrackColor = OldMoneyBurgundy,
                    userPinColor = OldMoneyNavy,
                    userTextColor = OldMoneySlate,
                    containerBg = SoftCreamSurface,
                    containerBorder = SoftCreamCardBorder,
                    isDarkBackground = false
                )
            }
            ThemeMode.DARK -> {
                SatelliteMapPalette(
                    oceanColor = Color(0xFF000000),
                    landColor = Color(0xFF101014),
                    landStroke = Color(0xFF27272A),
                    gridColor = Color(0x18FFFFFF),
                    equatorColor = Color(0xFF38BDF8),
                    nightShadowColor = Color(0xF0000000),
                    cityLightColor = Color(0xFFFFC107),
                    orbitTrackColor = AccentPrimary,
                    userPinColor = Color(0xFF00F0FF),
                    userTextColor = Color(0xFFFFFFFF),
                    containerBg = Color(0xFF000000),
                    containerBorder = Color(0xFF27272A),
                    isDarkBackground = true
                )
            }
            else -> { // DARK, GLASS, etc.
                SatelliteMapPalette(
                    oceanColor = Color(0xFF1E293B),
                    landColor = Color(0xFF334155),
                    landStroke = Color(0xFF64748B),
                    gridColor = Color(0x18FFFFFF),
                    equatorColor = Color(0x4000F0FF),
                    nightShadowColor = Color(0xCC030712),
                    cityLightColor = Color(0xFFFFC107),
                    orbitTrackColor = AccentPrimary,
                    userPinColor = Color(0xFF00F0FF),
                    userTextColor = Color(0xFF00F0FF),
                    containerBg = Color(0xFF1E293B),
                    containerBorder = Color.White.copy(alpha = 0.15f),
                    isDarkBackground = true
                )
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingNotificationAction?.invoke()
            pendingNotificationAction = null
        } else {
            pendingNotificationAction = null
            Toast.makeText(
                context,
                if (isFa) "برای دریافت هشدارهای گذر ماهواره، لطفاً مجوز اعلان را فعال کنید."
                else "Notification permission is required to receive satellite pass alerts.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val executeWithNotificationPermission: (() -> Unit) -> Unit = { action ->
        if (com.alijafari.red.astronomy.notification.NotificationPermissionHelper.hasPostNotificationPermission(context)) {
            action()
        } else {
            pendingNotificationAction = action
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Time Control & Scrubber State (-12h to +12h)
    var realTimeNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var timeOffsetHours by remember { mutableFloatStateOf(0f) } // 0 = Now
    val currentSimulationMs = remember(realTimeNowMs, timeOffsetHours) {
        realTimeNowMs + (timeOffsetHours * 3600 * 1000L).toLong()
    }

    // Real-Time Clock Ticker (updates realTimeNowMs every second when in live mode)
    LaunchedEffect(timeOffsetHours) {
        while (timeOffsetHours == 0f) {
            delay(1000L)
            realTimeNowMs = System.currentTimeMillis()
        }
    }

    val tleRepo = remember { com.alijafari.red.astronomy.data.repository.TleRepository.getInstance(context) }
    var detectedTrain by remember { mutableStateOf<SatelliteItem?>(null) }

    LaunchedEffect(Unit) {
        val initialTrain = StarlinkTrainManager.detectTrain(tleRepo.getStarlinkTles())
        detectedTrain = initialTrain

        withContext(Dispatchers.IO) {
            val updated = tleRepo.refreshTles()
            if (updated || initialTrain == null) {
                val newTrain = StarlinkTrainManager.detectTrain(tleRepo.getStarlinkTles())
                withContext(Dispatchers.Main) {
                    detectedTrain = newTrain
                }
            }
        }
    }

    // Filters
    var selectedCategory by remember { mutableStateOf(SatelliteCategory.ALL) }

    // Selected Satellite (Nullable: null = no satellite selected / no orbit line)
    var selectedSatelliteId by remember { mutableStateOf<String?>("iss_zarya") }
    var activeDetailSatellite by remember { mutableStateOf<SatelliteItem?>(null) }

    // Temporary anchored label state (shown when tapping a satellite)
    var anchoredSatLabelState by remember { mutableStateOf<SatelliteLiveState?>(null) }

    // Map Camera Gestures State
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffsetX by remember { mutableFloatStateOf(0.0f) }
    var panOffsetY by remember { mutableFloatStateOf(0.0f) }

    // Dialog Modals
    var showTutorialDialog by remember { mutableStateOf(false) }

    // Visible Satellites including dynamically detected Starlink Train
    val visibleSatellites = remember(detectedTrain) {
        SatelliteCatalog.getVisibleList(detectedTrain)
    }

    // Filtered Satellites
    val filteredSatellites = remember(selectedCategory, visibleSatellites) {
        visibleSatellites.filter { sat ->
            when (selectedCategory) {
                SatelliteCategory.ALL -> true
                SatelliteCategory.ISS -> sat.category == SatelliteCategory.ISS
                SatelliteCategory.STARLINK -> sat.category == SatelliteCategory.STARLINK || sat.isTrain
                SatelliteCategory.HUBBLE -> sat.category == SatelliteCategory.HUBBLE
                SatelliteCategory.VISIBLE -> sat.isNakedEyeCandidate
            }
        }
    }

    val selectedSatItem = remember(selectedSatelliteId, detectedTrain) {
        selectedSatelliteId?.let { id -> SatelliteCatalog.getById(id, detectedTrain) }
    }

    // Compute live positions for all filtered satellites
    val liveSatelliteStates = remember(filteredSatellites, currentSimulationMs, uiState.userLocation) {
        filteredSatellites.map { sat ->
            SatelliteEngine.calculateSatelliteState(
                satellite = sat,
                timestampMs = currentSimulationMs,
                userLatDeg = uiState.userLocation.latitude,
                userLonDeg = uiState.userLocation.longitude
            )
        }
    }

    // Handle full-screen Detail Screen navigation
    if (activeDetailSatellite != null) {
        SatelliteDetailScreen(
            satelliteItem = activeDetailSatellite!!,
            userLocation = uiState.userLocation,
            language = uiState.language,
            onBack = { activeDetailSatellite = null },
            simulationTimestampMs = currentSimulationMs
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RedSpacing.lg, vertical = RedSpacing.md),
            verticalArrangement = Arrangement.spacedBy(RedSpacing.lg)
        ) {
            // Status/Action Row (Alerts, Guide, Status)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                ) {
                    RedBadge(
                        text = if (timeOffsetHours == 0f) (if (isFa) "پایش زنده SGP4" else "Live SGP4 Orbit") else (if (isFa) "شبیه‌سازی زمانی" else "Time Simulation"),
                        isAccent = timeOffsetHours == 0f
                    )
                    if (!isOnline) {
                        RedBadge(
                            text = if (isFa) "آفلاین" else "Offline",
                            isAccent = false
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                ) {
                    // Notification Alert Toggle
                    IconButton(
                        onClick = { showLeadTimeSelectionDialog = true },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isAutoAlertEnabled) RedTheme.colors.accentRed.copy(alpha = 0.15f)
                                else RedTheme.colors.surfaceElevated
                            )
                            .border(
                                1.dp,
                                if (isAutoAlertEnabled) RedTheme.colors.accentRed.copy(alpha = 0.5f)
                                else RedTheme.colors.border,
                                CircleShape
                            )
                            .testTag("satellite_alerts_toggle")
                    ) {
                        Icon(
                            imageVector = if (isAutoAlertEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                            contentDescription = "Alerts",
                            tint = if (isAutoAlertEnabled) RedTheme.colors.accentRed else RedTheme.colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Help / Guide Button
                    IconButton(
                        onClick = { showTutorialDialog = true },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(RedTheme.colors.surfaceElevated)
                            .border(1.dp, RedTheme.colors.border, CircleShape)
                            .testTag("satellite_help_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Help",
                            tint = RedTheme.colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 1. MAIN FEATURE: SCIENTIFICALLY ACCURATE 2D EARTH MAP
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2.0f) // Strictly preserve true geographic 2:1 aspect ratio
                    .clip(squircleShape(RedCornerRadius.lg))
                    .background(mapPalette.containerBg)
                    .border(
                        1.dp,
                        RedTheme.colors.border,
                        squircleShape(RedCornerRadius.lg)
                    )
            ) {
                // Solar position for astronomical terminator & city illumination
                val subSolarPoint = remember(currentSimulationMs) {
                    SatelliteEngine.calculateSubSolarPoint(currentSimulationMs)
                }

                // Temporary label screen position tracking
                var labelPosOnScreen by remember { mutableStateOf<Offset?>(null) }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(1.0f, 6.0f)
                                if (zoomScale > 1.0f) {
                                    panOffsetX = (panOffsetX + pan.x).coerceIn(-300f * zoomScale, 300f * zoomScale)
                                    panOffsetY = (panOffsetY + pan.y).coerceIn(-150f * zoomScale, 150f * zoomScale)
                                } else {
                                    panOffsetX = 0f
                                    panOffsetY = 0f
                                }
                            }
                        }
                        .pointerInput(liveSatelliteStates, zoomScale, panOffsetX, panOffsetY) {
                            detectTapGestures { tapOffset ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val pxPerDeg = w / 360.0f
                                val centerX = w / 2f
                                val centerY = h / 2f

                                fun getMapX(lon: Double): Float =
                                    centerX + (lon.toFloat() * pxPerDeg) * zoomScale + panOffsetX

                                fun getMapY(lat: Double): Float =
                                    centerY - (lat.toFloat() * pxPerDeg) * zoomScale + panOffsetY

                                // Check tap on anchored label first
                                if (labelPosOnScreen != null && anchoredSatLabelState != null) {
                                    val lPos = labelPosOnScreen!!
                                    val labelBounds = Rect(
                                        left = lPos.x - 90f,
                                        top = lPos.y - 45f,
                                        right = lPos.x + 90f,
                                        bottom = lPos.y + 10f
                                    )
                                    if (labelBounds.contains(tapOffset)) {
                                        activeDetailSatellite = anchoredSatLabelState!!.satellite
                                        return@detectTapGestures
                                    }
                                }

                                // Check tap on satellite markers
                                var closest: SatelliteLiveState? = null
                                var minDist = with(density) { 32.dp.toPx() }

                                for (satState in liveSatelliteStates) {
                                    val sx = getMapX(satState.topocentric.subLonDeg)
                                    val sy = getMapY(satState.topocentric.subLatDeg)
                                    val dist = hypot(tapOffset.x - sx, tapOffset.y - sy)
                                    if (dist < minDist) {
                                        minDist = dist
                                        closest = satState
                                    }
                                }

                                if (closest != null) {
                                    selectedSatelliteId = closest.satellite.id
                                    anchoredSatLabelState = closest
                                } else {
                                    anchoredSatLabelState = null
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f) return@Canvas

                    // 0. Ocean Background
                    drawRect(color = mapPalette.oceanColor)

                    // Geographic Coordinate Proportions: 360° longitude, 180° latitude
                    val pxPerDeg = w / 360.0f
                    val centerX = w / 2f
                    val centerY = h / 2f

                    fun mapX(lon: Double): Float =
                        centerX + (lon.toFloat() * pxPerDeg) * zoomScale + panOffsetX

                    fun mapY(lat: Double): Float =
                        centerY - (lat.toFloat() * pxPerDeg) * zoomScale + panOffsetY

                    val landColor = mapPalette.landColor
                    val landStroke = mapPalette.landStroke
                    val gridColor = mapPalette.gridColor
                    val equatorColor = mapPalette.equatorColor
                    val nightShadowColor = mapPalette.nightShadowColor
                    val cityLightColor = mapPalette.cityLightColor

                    // 1. Geographic Lat/Lon Grid Lines
                    for (gLon in -150..150 step 30) {
                        val gx = mapX(gLon.toDouble())
                        if (gx in 0f..w) drawLine(gridColor, start = Offset(gx, 0f), end = Offset(gx, h), strokeWidth = 1f)
                    }
                    for (gLat in -60..60 step 30) {
                        val gy = mapY(gLat.toDouble())
                        if (gy in 0f..h) drawLine(gridColor, start = Offset(0f, gy), end = Offset(w, gy), strokeWidth = 1f)
                    }

                    // Equator Line
                    val eqY = mapY(0.0)
                    if (eqY in 0f..h) {
                        drawLine(
                            color = equatorColor,
                            start = Offset(0f, eqY),
                            end = Offset(w, eqY),
                            strokeWidth = 1.5f
                        )
                    }

                    // 2. Draw Accurate Earth Continents & Landmasses
                    val scaleX = (w * zoomScale).coerceAtLeast(0.001f)
                    val scaleY = (h * zoomScale).coerceAtLeast(0.001f)
                    val translateX = centerX * (1f - zoomScale) + panOffsetX
                    val translateY = centerY * (1f - zoomScale) + panOffsetY

                    drawContext.canvas.save()
                    drawContext.canvas.translate(translateX, translateY)
                    drawContext.canvas.scale(scaleX, scaleY)

                    val strokeW = ((if (mapPalette.isDarkBackground) 1.2f else 1.0f) / scaleX).coerceIn(0.001f, 5.0f)
                    for (unitPath in WorldGeographyData.normalizedPaths) {
                        drawPath(path = unitPath, color = landColor, style = Fill)
                        drawPath(
                            path = unitPath,
                            color = landStroke,
                            style = Stroke(width = strokeW)
                        )
                    }
                    drawContext.canvas.restore()

                    // 3. Astronomical Day/Night Terminator & Night Shade Region
                    val subLatRad = Math.toRadians(subSolarPoint.latDeg)
                    val tanSubLat = tan(subLatRad)

                    val nightPath = Path()
                    if (subSolarPoint.latDeg >= 0) {
                        // Sun in Northern Hemisphere -> South Pole is night
                        nightPath.moveTo(mapX(-180.0), mapY(-90.0))
                        nightPath.lineTo(mapX(180.0), mapY(-90.0))
                        for (lon in 180 downTo -180 step 4) {
                            val dLonRad = Math.toRadians(lon - subSolarPoint.lonDeg)
                            val termLatRad = atan(-cos(dLonRad) / if (abs(tanSubLat) < 1e-4) 1e-4 else tanSubLat)
                            val termLatDeg = Math.toDegrees(termLatRad)
                            nightPath.lineTo(mapX(lon.toDouble()), mapY(termLatDeg))
                        }
                    } else {
                        // Sun in Southern Hemisphere -> North Pole is night
                        nightPath.moveTo(mapX(-180.0), mapY(90.0))
                        nightPath.lineTo(mapX(180.0), mapY(90.0))
                        for (lon in 180 downTo -180 step 4) {
                            val dLonRad = Math.toRadians(lon - subSolarPoint.lonDeg)
                            val termLatRad = atan(-cos(dLonRad) / if (abs(tanSubLat) < 1e-4) 1e-4 else tanSubLat)
                            val termLatDeg = Math.toDegrees(termLatRad)
                            nightPath.lineTo(mapX(lon.toDouble()), mapY(termLatDeg))
                        }
                    }
                    nightPath.close()

                    drawPath(path = nightPath, color = nightShadowColor)

                    // 4. Night-Side City Lights (appear ONLY on night side)
                    for (city in SatelliteEngine.majorCityLights) {
                        val cLatRad = Math.toRadians(city.lat)
                        val dLonRad = Math.toRadians(city.lon - subSolarPoint.lonDeg)
                        val cosPsi = sin(cLatRad) * sin(subLatRad) + cos(cLatRad) * cos(subLatRad) * cos(dLonRad)

                        if (cosPsi < 0.0) { // On night side
                            val alpha = (abs(cosPsi) / 0.12f).coerceAtMost(1.0).toFloat()
                            val cx = mapX(city.lon)
                            val cy = mapY(city.lat)

                            if (cx in 0f..w && cy in 0f..h) {
                                drawCircle(
                                    color = cityLightColor.copy(alpha = 0.85f * alpha),
                                    radius = city.sizeDp * zoomScale * 0.75f,
                                    center = Offset(cx, cy)
                                )
                            }
                        }
                    }

                    // 5. User Location Pin & City Name Label
                    val userX = mapX(uiState.userLocation.longitude)
                    val userY = mapY(uiState.userLocation.latitude)
                    if (userX in 0f..w && userY in 0f..h) {
                        drawCircle(
                            color = mapPalette.userPinColor.copy(alpha = 0.35f),
                            radius = 12.dp.toPx(),
                            center = Offset(userX, userY)
                        )
                        drawCircle(
                            color = mapPalette.userPinColor,
                            radius = 4.dp.toPx(),
                            center = Offset(userX, userY)
                        )

                        val cityName = if (isFa) uiState.userLocation.cityNameFa else uiState.userLocation.cityNameEn
                        val textLayoutResult = textMeasurer.measure(
                            text = if (isFa) cityName.toPersianDigits() else cityName,
                            style = TextStyle(
                                fontFamily = canvasFontFamily,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = mapPalette.userTextColor
                            )
                        )
                        drawText(
                            textLayoutResult = textLayoutResult,
                            topLeft = Offset(userX - textLayoutResult.size.width / 2f, userY - 18.dp.toPx())
                        )
                    }

                    // 6. Selected Satellite Ground Track (ONLY if a satellite is selected)
                    if (selectedSatItem != null) {
                        val trackPoints = SatelliteEngine.calculateGroundTrack(
                            satellite = selectedSatItem,
                            currentTimestampMs = currentSimulationMs
                        )
                        val trackPath = Path()
                        var isFirst = true
                        var prevLon: Double? = null

                        for ((tLat, tLon) in trackPoints) {
                            if (prevLon != null && abs(tLon - prevLon) > 180.0) {
                                isFirst = true // handle antimeridian wrap
                            }
                            val tx = mapX(tLon)
                            val ty = mapY(tLat)
                            if (isFirst) {
                                trackPath.moveTo(tx, ty)
                                isFirst = false
                            } else {
                                trackPath.lineTo(tx, ty)
                            }
                            prevLon = tLon
                        }

                        drawPath(
                            path = trackPath,
                            color = mapPalette.orbitTrackColor.copy(alpha = 0.85f),
                            style = Stroke(
                                width = 2.5f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                            )
                        )
                    }

                    // 7. Filtered Satellite Markers
                    for (liveState in liveSatelliteStates) {
                        val sat = liveState.satellite
                        val isSelected = sat.id == selectedSatelliteId
                        val sx = mapX(liveState.topocentric.subLonDeg)
                        val sy = mapY(liveState.topocentric.subLatDeg)

                        if (sx in -20f..(w + 20f) && sy in -20f..(h + 20f)) {
                            val markerColor = if (isSelected) AccentPrimary else if (sat.isNakedEyeCandidate) Color(0xFF2DC653) else Color(0xFFFFB703)

                            if (isSelected) {
                                drawCircle(
                                    color = markerColor.copy(alpha = 0.25f),
                                    radius = 14.dp.toPx(),
                                    center = Offset(sx, sy)
                                )
                            }

                            drawCircle(
                                color = markerColor,
                                radius = if (isSelected) 5.5f.dp.toPx() else 4.dp.toPx(),
                                center = Offset(sx, sy)
                            )
                        }
                    }

                    // 8. Track screen position for temporary anchored label if active
                    if (anchoredSatLabelState != null) {
                        val satState = anchoredSatLabelState!!
                        val ax = mapX(satState.topocentric.subLonDeg)
                        val ay = mapY(satState.topocentric.subLatDeg)
                        labelPosOnScreen = Offset(ax, ay)
                    } else {
                        labelPosOnScreen = null
                    }
                }

                // ONLY Reset Button Overlay on Map (Top Right)
                SmallFloatingActionButton(
                    onClick = {
                        zoomScale = 1.0f
                        panOffsetX = 0f
                        panOffsetY = 0f
                    },
                    containerColor = Color.Black.copy(alpha = 0.65f),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(32.dp)
                        .testTag("map_reset_view")
                ) {
                    Icon(Icons.Default.CenterFocusWeak, contentDescription = "Reset", modifier = Modifier.size(16.dp))
                }

                // Temporary Anchored Label on Satellite Tap
                if (anchoredSatLabelState != null && labelPosOnScreen != null) {
                    val satState = anchoredSatLabelState!!
                    val lPos = labelPosOnScreen!!

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(0.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .offset(
                                    x = with(density) { (lPos.x - 75f).toDp() },
                                    y = with(density) { (lPos.y - 48f).toDp() }
                                )
                                .width(150.dp)
                                .clickable { activeDetailSatellite = satState.satellite }
                                .testTag("anchored_sat_label"),
                            shape = squircleShape(12.dp),
                            color = Color(0xF20F172A),
                            border = BorderStroke(1.dp, AccentPrimary)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SatelliteAlt,
                                    contentDescription = null,
                                    tint = AccentPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isFa) satState.satellite.nameFa else satState.satellite.nameEn,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val altStr = String.format(Locale.US, "%.0f km", satState.topocentric.satAltKm)
                                    Text(
                                        text = if (isFa) "ارتفاع $altStr".toPersianDigits() else "Alt $altStr",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        color = Color.White.copy(alpha = 0.75f)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Detail",
                                    tint = AccentPrimary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. MAP THEME & CATEGORY CONTROLS AREA (Outside Map Canvas)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category Filter Chips
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                ) {
                    items(SatelliteCategory.entries) { cat ->
                        val isSelected = cat == selectedCategory
                        Surface(
                            shape = RedShapes.capsule,
                            color = if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.surfaceElevated,
                            border = BorderStroke(1.dp, if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.border),
                            modifier = Modifier
                                .clickable { selectedCategory = cat }
                                .testTag("filter_${cat.name}")
                        ) {
                            Text(
                                text = if (isFa) cat.labelFa else cat.labelEn,
                                style = RedTypography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                color = if (isSelected) Color.White else RedTheme.colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(RedSpacing.sm))

                // Compact Map Theme Selector Outside Map
                Surface(
                    shape = RedShapes.capsule,
                    color = RedTheme.colors.surfaceElevated,
                    border = BorderStroke(1.dp, RedTheme.colors.border),
                    modifier = Modifier.testTag("map_theme_selector")
                ) {
                    Row(
                        modifier = Modifier.padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            Triple(MapTheme.AUTO, if (isFa) "خودکار" else "Auto", "map_theme_auto"),
                            Triple(MapTheme.DARK, if (isFa) "تاریک" else "Dark", "map_theme_dark"),
                            Triple(MapTheme.LIGHT, if (isFa) "روشن" else "Light", "map_theme_light")
                        ).forEach { (theme, label, _) ->
                            val isSelected = mapTheme == theme
                            Box(
                                modifier = Modifier
                                    .clip(RedShapes.capsule)
                                    .background(if (isSelected) RedTheme.colors.accentRed else Color.Transparent)
                                    .clickable {
                                        mapTheme = theme
                                        prefs.edit().putString("satellite_map_theme", theme.name).apply()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = RedTypography.labelSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                    color = if (isSelected) Color.White else RedTheme.colors.textSecondary
                                )
                            }
                        }
                    }
                }
            }

            // 3. COMPACT TIME SCRUBBER (-12h to +12h) — Liquid Glass Surface
            LiquidGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("satellite_time_scrubber_card"),
                shape = squircleShape(RedCornerRadius.lg),
                style = LiquidGlassDefaults.Card
            ) {
                Column(
                    modifier = Modifier.padding(RedSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                        ) {
                            Icon(
                                Icons.Default.AccessTime,
                                contentDescription = null,
                                tint = RedTheme.colors.accentRed,
                                modifier = Modifier.size(16.dp)
                            )

                            val isPersianCalendar = isFa || uiState.calendarSystem == CalendarSystem.SOLAR_HIJRI
                            val timeStr = remember(currentSimulationMs, isPersianCalendar, isFa) {
                                if (isPersianCalendar) {
                                    val sh = TimeEngine.toSolarHijri(currentSimulationMs, TimeEngine.TEHRAN_TIME_ZONE)
                                    val timePart = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeEngine.TEHRAN_TIME_ZONE }.format(Date(currentSimulationMs))
                                    val raw = "${sh.day} ${sh.monthNameFa}، $timePart"
                                    if (isFa) raw.toPersianDigits() else raw
                                } else {
                                    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
                                    sdf.format(Date(currentSimulationMs))
                                }
                            }
                            val offsetStr = if (timeOffsetHours == 0f) (if (isFa) "زنده" else "Live")
                            else if (timeOffsetHours > 0) {
                                val raw = "+${String.format(Locale.US, "%.1fh", timeOffsetHours)}"
                                if (isFa) raw.toPersianDigits() else raw
                            } else {
                                val raw = String.format(Locale.US, "%.1fh", timeOffsetHours)
                                if (isFa) raw.toPersianDigits() else raw
                            }

                            Text(
                                text = "$timeStr ($offsetStr)",
                                style = RedTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = RedTheme.colors.textPrimary
                            )
                        }

                        if (timeOffsetHours != 0f) {
                            Surface(
                                shape = RedShapes.capsule,
                                color = RedTheme.colors.accentRed.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, RedTheme.colors.accentRed.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .clickable { timeOffsetHours = 0f }
                                    .testTag("time_reset_now")
                            ) {
                                Text(
                                    text = if (isFa) "زنده" else "Live",
                                    style = RedTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = RedTheme.colors.accentRed,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Time Slider (-12h to +12h, 1-minute steps)
                    RedSlider(
                        value = timeOffsetHours,
                        onValueChange = { raw ->
                            val stepHours = 1.0 / 60.0 // 1 minute step
                            val rounded = (round(raw / stepHours) * stepHours).toFloat()
                            timeOffsetHours = rounded
                        },
                        valueRange = -12f..12f,
                        steps = 1439,
                        modifier = Modifier.fillMaxWidth().testTag("time_scrubber_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isFa) "۱۲ ساعت قبل" else "12h ago",
                            style = RedTypography.labelSmall,
                            color = RedTheme.colors.textSecondary
                        )
                        Text(
                            text = if (isFa) "زنده" else "Live",
                            style = RedTypography.labelSmall,
                            color = RedTheme.colors.accentRed
                        )
                        Text(
                            text = if (isFa) "۱۲ ساعت بعد" else "12h ahead",
                            style = RedTypography.labelSmall,
                            color = RedTheme.colors.textSecondary
                        )
                    }
                }
            }

            // 4. UPCOMING VISIBLE PASSES PREDICTIONS (ALL Satellites, Next 3 Days, Chronological)
            val roundedStartMs = remember(currentSimulationMs) {
                (currentSimulationMs / 60_000L) * 60_000L
            }

            var allUpcomingPasses by remember {
                mutableStateOf<List<Pair<com.alijafari.red.astronomy.astro_engine.SatelliteItem, ISSEngine.ISSPass>>?>(null)
            }

            RedSectionHeader(
                title = if (isFa) "گذرهای قابل مشاهده بعدی (۳ روز آینده)" else "Upcoming Visible Passes (Next 3 Days)",
                actionText = if (allUpcomingPasses?.isNotEmpty() == true) "${allUpcomingPasses!!.size}" else null
            )

            LaunchedEffect(uiState.userLocation, roundedStartMs, detectedTrain) {
                withContext(Dispatchers.Default) {
                    val allSats = com.alijafari.red.astronomy.astro_engine.SatelliteCatalog.getVisibleList(detectedTrain)
                    val combined = mutableListOf<Pair<com.alijafari.red.astronomy.astro_engine.SatelliteItem, ISSEngine.ISSPass>>()
                    for (sat in allSats) {
                        val passes = ISSEngine.predictPasses(
                            userLatDeg = uiState.userLocation.latitude,
                            userLonDeg = uiState.userLocation.longitude,
                            startTimestampMs = roundedStartMs,
                            tle = SatelliteEngine.getEffectiveTle(sat),
                            scanDays = 3,
                            visibleOnly = true,
                            standardMag = sat.standardMagnitude
                        )
                        for (p in passes) {
                            combined.add(Pair(sat, p))
                        }
                    }
                    val sorted = combined.sortedBy { it.second.startTimeMs }
                    withContext(Dispatchers.Main) {
                        allUpcomingPasses = sorted
                    }
                }
            }

            val currentAllPasses = allUpcomingPasses
            if (currentAllPasses == null) {
                RedElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isFa) "در حال محاسبه گذرهای ماهواره‌ها..." else "Calculating satellite passes...",
                        style = RedTypography.bodyMedium,
                        color = RedTheme.colors.textSecondary,
                        modifier = Modifier.padding(RedSpacing.lg)
                    )
                }
            } else if (currentAllPasses.isEmpty()) {
                RedElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isFa) "هیچ گذر قابل مشهودی مطابق با معیار علمی در ۳ روز آینده یافت نشد."
                        else "No visible passes meeting scientific criteria predicted in next 3 days.",
                        style = RedTypography.bodyMedium,
                        color = RedTheme.colors.textSecondary,
                        modifier = Modifier.padding(RedSpacing.lg)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)) {
                    currentAllPasses.forEach { (sat, pass) ->
                        DetailedVisiblePassCard(
                            satName = if (isFa) sat.nameFa else sat.nameEn,
                            pass = pass,
                            cityName = uiState.userLocation.cityNameFa,
                            isFa = isFa,
                            onSchedulePassReminder = { leadMins ->
                                executeWithNotificationPermission {
                                    AstroNotificationManager.scheduleSpecificPassAlarm(
                                        context = context,
                                        satellite = sat,
                                        pass = pass,
                                        userLocation = uiState.userLocation,
                                        leadMinutes = leadMins
                                    )
                                    val labelStr = if (leadMins == 1440) (if (isFa) "۱ روز" else "1 day") else (if (isFa) "$leadMins دقیقه" else "$leadMins mins")
                                    Toast.makeText(
                                        context,
                                        if (isFa) "هشدار گذر $labelStr قبل از شروع تنظیم شد!" else "Alert set $labelStr prior to pass!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }

            // 5. SATELLITE LIST / CARDS SECTION
            RedSectionHeader(
                title = if (isFa) "فهرست ماهواره‌ها" else "Tracked Satellites",
                actionText = "${filteredSatellites.size}"
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
            ) {
                liveSatelliteStates.forEach { state ->
                    val sat = state.satellite
                    val isSelected = sat.id == selectedSatelliteId

                    RedElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedSatelliteId = if (isSelected) null else sat.id
                                anchoredSatLabelState = if (isSelected) null else state
                            }
                            .testTag("sat_card_${sat.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(RedSpacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(RedSpacing.md),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.surfaceElevated)
                                        .border(1.dp, if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.border, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SatelliteAlt,
                                        contentDescription = null,
                                        tint = if (isSelected) Color.White else RedTheme.colors.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Column {
                                    Text(
                                        text = if (isFa) sat.nameFa else sat.nameEn,
                                        style = RedTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = RedTheme.colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val altStr = String.format(Locale.US, "%.0f km", state.topocentric.satAltKm)
                                    val magStr = String.format(Locale.US, "%+.1f", state.apparentMagnitude)
                                    Text(
                                        text = if (isFa) "ارتفاع $altStr • قدر $magStr".toPersianDigits() else "Alt $altStr • Mag $magStr",
                                        style = RedTypography.labelSmall,
                                        color = RedTheme.colors.textSecondary
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                            ) {
                                RedBadge(
                                    text = if (state.isNakedEyeVisible) (if (isFa) "با چشم" else "Naked Eye") else (if (isFa) "تلسکوپی" else "Telescopic"),
                                    isAccent = state.isNakedEyeVisible
                                )

                                IconButton(
                                    onClick = { activeDetailSatellite = sat },
                                    modifier = Modifier.size(28.dp).testTag("open_sat_detail_${sat.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Details",
                                        tint = RedTheme.colors.accentRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom spacing for floating navigation bar
            Spacer(modifier = Modifier.height(96.dp))
        }
    }

    // Multi-Satellite Notification State
    var selectedSatIdsForAlerts by remember {
        val savedSet = prefs.getStringSet("monitored_satellite_ids", null)
        mutableStateOf(savedSet ?: setOf("iss_zarya", "tiangong", "starlink_train", "hubble"))
    }

    // Lead Time & Multi-Satellite Notification Dialog
    if (showLeadTimeSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showLeadTimeSelectionDialog = false },
            title = {
                Text(
                    text = if (isFa) "سیستم هشدار خودکار ماهواره‌ها" else "Automated Satellite Pass Alerts",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = if (isFa) "زمان یادآوری قبل از آغاز گذر:" else "Notification timing before pass:",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = AccentPrimary
                    )

                    val leadOptions = listOf(
                        10 to (if (isFa) "۱۰ دقیقه قبل" else "10 minutes before"),
                        30 to (if (isFa) "۳۰ دقیقه قبل" else "30 minutes before"),
                        1440 to (if (isFa) "۱ روز قبل (۲۴ ساعت)" else "1 day before (24 hours)")
                    )

                    leadOptions.forEach { (mins, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedLeadMinutes = mins }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedLeadMinutes == mins,
                                onClick = { selectedLeadMinutes = mins }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                    Text(
                        text = if (isFa) "ماهواره‌های تحت پایش خودکار:" else "Monitored satellites:",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = AccentPrimary
                    )

                    com.alijafari.red.astronomy.astro_engine.SatelliteCatalog.getVisibleList(detectedTrain).forEach { sat ->
                        val isChecked = sat.id in selectedSatIdsForAlerts
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSatIdsForAlerts = if (isChecked) {
                                        selectedSatIdsForAlerts - sat.id
                                    } else {
                                        selectedSatIdsForAlerts + sat.id
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedSatIdsForAlerts = if (checked) {
                                            selectedSatIdsForAlerts + sat.id
                                        } else {
                                            selectedSatIdsForAlerts - sat.id
                                        }
                                    }
                                )
                                Text(
                                    text = if (isFa) sat.nameFa else sat.nameEn,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLeadTimeSelectionDialog = false
                        executeWithNotificationPermission {
                            isAutoAlertEnabled = true
                            prefs.edit()
                                .putBoolean("auto_satellite_alerts_enabled", true)
                                .putBoolean("iss_auto_alerts_enabled", true)
                                .putStringSet("monitored_satellite_ids", selectedSatIdsForAlerts)
                                .putInt("satellite_alert_lead_minutes", selectedLeadMinutes)
                                .putInt("iss_alert_lead_minutes", selectedLeadMinutes)
                                .putString("user_city_name_fa", uiState.userLocation.cityNameFa)
                                .putString("user_city_name_en", uiState.userLocation.cityNameEn)
                                .putFloat("user_lat", uiState.userLocation.latitude.toFloat())
                                .putFloat("user_lon", uiState.userLocation.longitude.toFloat())
                                .apply()

                            coroutineScope.launch(Dispatchers.Default) {
                                AstroNotificationManager.scheduleRollingSatellitePasses(
                                    context = context,
                                    userLocation = uiState.userLocation,
                                    selectedSatIds = selectedSatIdsForAlerts,
                                    leadMinutes = selectedLeadMinutes,
                                    scanDays = 7
                                )
                            }

                            Toast.makeText(
                                context,
                                if (isFa) "سیستم پایش خودکار ۷ روزه برای ماهواره‌های انتخاب شده فعال شد!"
                                else "7-day automated pass schedule enabled for selected satellites!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                ) {
                    Text(text = if (isFa) "فعال‌سازی سیستم هشدار" else "Enable Monitoring")
                }
            },
            dismissButton = {
                Row {
                    if (isAutoAlertEnabled) {
                        TextButton(
                            onClick = {
                                showLeadTimeSelectionDialog = false
                                isAutoAlertEnabled = false
                                prefs.edit()
                                    .putBoolean("auto_satellite_alerts_enabled", false)
                                    .putBoolean("iss_auto_alerts_enabled", false)
                                    .apply()
                                AstroNotificationManager.cancelAllPassNotifications(context)
                                Toast.makeText(
                                    context,
                                    if (isFa) "هشدارهای خودکار ماهواره غیرفعال شدند."
                                    else "Automated satellite alerts disabled.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Text(
                                text = if (isFa) "غیرفعال‌سازی" else "Disable Alerts",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    TextButton(onClick = { showLeadTimeSelectionDialog = false }) {
                        Text(text = if (isFa) "انصراف" else "Cancel")
                    }
                }
            }
        )
    }

    // Tutorial Dialog
    if (showTutorialDialog) {
        AlertDialog(
            onDismissRequest = { showTutorialDialog = false },
            title = {
                Text(
                    text = if (isFa) "راهنمای مرکز ردیابی ماهواره‌ها" else "Satellite Tracking Guide",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    TutorialBullet(
                        title = if (isFa) "نقشه دقیق زمین:" else "Accurate Earth Map:",
                        desc = if (isFa) "نمایش قاره‌ها با پروژکسیون دقیق جغرافیایی، خط مرز روز و شب واقعی و روشنایی شهرهای شب."
                        else "Displays accurate geographic continents, real astronomical terminator boundary, and night-side city lights."
                    )
                    TutorialBullet(
                        title = if (isFa) "پوسته نقشه:" else "Map Theme:",
                        desc = if (isFa) "امکان تغییر بین حالت تاریک (Dark) و روشن (Light) از کنترل‌های خارج از نقشه."
                        else "Switch between Dark and Light map themes using unobtrusive controls outside the map."
                    )
                    TutorialBullet(
                        title = if (isFa) "تعامل با ماهواره:" else "Satellite Interaction:",
                        desc = if (isFa) "با لمس هر ماهواره، برچسب شناور نام و ارتفاع ظاهر می‌شود. لمس مجدد برچسب، صفحه جزئیات کامل و خط زمانی گذر را باز می‌کند."
                        else "Tap any satellite to show an anchored label. Tap the label to open full satellite details and timeline."
                    )
                    TutorialBullet(
                        title = if (isFa) "محور زمان ۱۲ ساعته:" else "12-Hour Time Scrubber:",
                        desc = if (isFa) "بررسی و شبیه‌سازی موقعیت ماهواره‌ها از ۱۲ ساعت قبل تا ۱۲ ساعت بعد."
                        else "Scrub from -12h in the past to +12h into the future to simulate satellite motion and day/night illumination."
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showTutorialDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                ) {
                    Text(text = if (isFa) "متوجه شدم" else "Got It")
                }
            }
        )
    }
}

@Composable
private fun TutorialBullet(title: String, desc: String) {
    Column {
        Text(text = title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = AccentPrimary)
        Text(text = desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
