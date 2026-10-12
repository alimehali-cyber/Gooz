package com.alijafari.red.astronomy.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.astro_engine.*
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.domain.ObjectType
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import com.alijafari.red.astronomy.domain.TimeMachineMode
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.MainViewModel
import com.alijafari.red.astronomy.ui.rendering.*
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaFeature
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaLayer
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaState
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaStatus
import com.alijafari.red.astronomy.ui.theme.LiquidGlassDefaults
import com.alijafari.red.astronomy.ui.theme.LocalAppFontFamily
import com.alijafari.red.astronomy.ui.theme.RedCornerRadius
import com.alijafari.red.astronomy.ui.theme.liquidGlass
import com.alijafari.red.astronomy.util.toPersianDigits
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

data class StardustParticle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var size: Float,
    var alpha: Float,
    val color: Color
)

data class SelectedCelestialInfo(
    val id: String = "",
    val name: String,
    val position: Offset,
    val typeName: String? = null
)

/**
 * The interactive sky canvas: the shared sky renderer, tap-to-identify and finger time travel.
 *
 * The caller owns the size. It renders as a small square card on Home ([compact] = true, no pills) and
 * fills the screen in the landscape sky viewer. [onExpand] adds the small expand affordance that opens
 * that fullscreen viewer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeroSkyCanvas(
    uiState: MainUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onExpand: (() -> Unit)? = null
) {
    val isFa = uiState.language == AppLanguage.PERSIAN
    val coroutineScope = rememberCoroutineScope()

    // Live clock shared with the Live Sky backdrop (one timer, owned by MainViewModel, running only while collected).
    val currentSystemTimeMs by viewModel.skyLiveClock.timeMs.collectAsState()

    // Direct Finger Time Travel state
    val simulatedOffsetHoursAnim = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Publish the drag offset (including the animated return) so the Live Sky backdrop follows the same instant.
    // It is reset when Home leaves, exactly as this local offset is.
    LaunchedEffect(Unit) {
        snapshotFlow { simulatedOffsetHoursAnim.value }.collect { viewModel.setSkyDragOffsetHours(it) }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.setSkyDragOffsetHours(0f) }
    }

    // Stardust Particles list
    val stardustParticles = remember { mutableStateListOf<StardustParticle>() }

    // Selected Tapped Celestial Object
    var selectedCelestial by remember { mutableStateOf<SelectedCelestialInfo?>(null) }

    // Active particle & interactive animation loop (only active when interacting, returning, or selection active)
    var frameTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasParticles by remember { derivedStateOf { stardustParticles.isNotEmpty() } }
    val isAnimatingReturn = simulatedOffsetHoursAnim.isRunning
    val hasSelection = selectedCelestial != null

    // Steady state real-time clock: one tick per second from the shared clock, instead of every frame when idle
    LaunchedEffect(currentSystemTimeMs) {
        if (!isDragging && stardustParticles.isEmpty() && !simulatedOffsetHoursAnim.isRunning && selectedCelestial == null) {
            frameTimeMs = currentSystemTimeMs
        }
    }

    LaunchedEffect(isDragging, hasParticles, isAnimatingReturn, hasSelection) {
        while (isDragging || stardustParticles.isNotEmpty() || simulatedOffsetHoursAnim.isRunning || selectedCelestial != null) {
            withFrameMillis { ms ->
                frameTimeMs = ms
                // Update stardust particles
                if (stardustParticles.isNotEmpty()) {
                    val iter = stardustParticles.iterator()
                    while (iter.hasNext()) {
                        val p = iter.next()
                        p.x += p.vx
                        p.y += p.vy
                        p.alpha -= 0.025f
                        if (p.alpha <= 0f) {
                            iter.remove()
                        }
                    }
                }
            }
        }
    }

    // Current Simulated Julian Date (Honours TimeMachineState in simulation mode, plus any finger time travel offset)
    val currentOffsetHours = simulatedOffsetHoursAnim.value
    val baseTimeMs = SkyTimeModel.baseTimeMs(
        isSimulation = uiState.timeMachineState.mode == TimeMachineMode.SIMULATION,
        simulationTimeMs = uiState.timeMachineState.simulationTimeMs,
        liveTimeMs = currentSystemTimeMs
    )
    // Same effective instant the Live Sky backdrop uses (see SkyTimeModel)
    val simulatedJd = SkyTimeModel.effectiveJd(baseTimeMs, currentOffsetHours)
    val simulatedTimeMs = TimeEngine.getTimestampFromJulianDate(simulatedJd)

    // Astro computations
    val userLat = uiState.userLocation.latitude
    val userLon = uiState.userLocation.longitude
    val userElev = uiState.userLocation.elevationMeters
    val userTimeZone = remember(uiState.userLocation.timezoneId) {
        TimeEngine.resolveTimeZone(uiState.userLocation.timezoneId)
    }
    // Astro computations: one shared scene model (also used by the Live Sky app backdrop)
    val skyFrame = remember(simulatedJd, userLat, userLon, userElev) {
        SkySceneModel.compute(simulatedJd, userLat, userLon, userElev)
    }
    val lastDeg = skyFrame.lastDeg
    val sunHoriz = skyFrame.sunHoriz
    val moonData = skyFrame.moonData
    val planetPositions = skyFrame.planetPositions
    val galacticPlanePoints = skyFrame.galacticPlanePoints
    val catalogStars = skyFrame.catalogStars
    val isSolarEclipse = skyFrame.isSolarEclipse
    val isLunarEclipse = skyFrame.isLunarEclipse
    val lightingState = skyFrame.lightingState

    // Auto-return job
    var autoReturnJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // 5-second automatic dismiss timer for the selected celestial object pill
    LaunchedEffect(selectedCelestial) {
        if (selectedCelestial != null) {
            delay(5000)
            selectedCelestial = null
        }
    }

    // Fresh state holders for pointerInput handlers so gestures are never cancelled mid-tap or stuck with stale captures
    val currentIsFa by rememberUpdatedState(isFa)
    val currentCatalogStars by rememberUpdatedState(catalogStars)
    val currentPlanetPositions by rememberUpdatedState(planetPositions)
    val currentSunHoriz by rememberUpdatedState(sunHoriz)
    val currentMoonData by rememberUpdatedState(moonData)
    val currentUserLat by rememberUpdatedState(userLat)
    val currentSkyTheme by rememberUpdatedState(uiState.skyCanvasTheme)

    // Photographic panorama (Phase 1). Only active behind the internal SkyPanoramaFeature switch.
    // Until the panorama reports READY, the legacy atmosphere and procedural Milky Way below stay
    // visible as the fallback, so there is no black or empty flash. Its state is derived only when
    // the feature is enabled (see the SkyPanoramaLayer call below).
    var panoramaReady by remember { mutableStateOf(false) }
    // The panorama replaces the Real Sky background only for that theme; other themes keep their look.
    val panoramaEnabled = SkyPanoramaFeature.isEnabledFor(uiState.skyCanvasTheme)
    val panoramaActive = SkyPanoramaFeature.isPresented(uiState.skyCanvasTheme, panoramaReady)

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(RedCornerRadius.window))
            .testTag("hero_sky_canvas_container")
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val canvasW = size.width.toFloat()
                    val canvasH = size.height.toFloat()
                    val touchRadius = 36.dp.toPx()

                    val fa = currentIsFa
                    val lat = currentUserLat
                    val sun = currentSunHoriz
                    val moon = currentMoonData
                    val planets = currentPlanetPositions
                    val stars = currentCatalogStars

                    var closestObj: SelectedCelestialInfo? = null
                    var minDistance = Float.MAX_VALUE

                    // Check Sun
                    if (sun.altitudeDeg > -12.0) {
                        val sunScreenPos = HeroSkyProjection.project(sun.azimuthDeg, sun.altitudeDeg, canvasW, canvasH, lat)
                        val dist = HeroSkyProjection.screenDistance(tapOffset, sunScreenPos, canvasW)
                        if (dist < touchRadius && dist < minDistance) {
                            minDistance = dist
                            closestObj = SelectedCelestialInfo(
                                id = "sun",
                                name = if (fa) "خورشید" else "Sun",
                                position = sunScreenPos,
                                typeName = if (fa) "ستاره مرکزی منظومه شمسی" else "Central Star"
                            )
                        }
                    }

                    // Check Moon
                    if (moon.altitudeDeg > -12.0) {
                        val moonScreenPos = HeroSkyProjection.project(moon.azimuthDeg, moon.altitudeDeg, canvasW, canvasH, lat)
                        val dist = HeroSkyProjection.screenDistance(tapOffset, moonScreenPos, canvasW)
                        if (dist < touchRadius && dist < minDistance) {
                            minDistance = dist
                            closestObj = SelectedCelestialInfo(
                                id = "moon",
                                name = if (fa) "ماه" else "Moon",
                                position = moonScreenPos,
                                typeName = if (fa) moon.phaseNameFa else moon.phaseNameEn
                            )
                        }
                    }

                    // Check Planets
                    planets.forEach { (pType, _, horiz) ->
                        val pPos = HeroSkyProjection.project(horiz.azimuthDeg, horiz.altitudeDeg, canvasW, canvasH, lat)
                        val dist = HeroSkyProjection.screenDistance(tapOffset, pPos, canvasW)
                        if (dist < touchRadius && dist < minDistance) {
                            minDistance = dist
                            val planetId = "planet_${pType.name.lowercase()}"
                            closestObj = SelectedCelestialInfo(
                                id = planetId,
                                name = if (fa) pType.nameFa else pType.nameEn,
                                position = pPos,
                                typeName = if (fa) "سیاره" else "Planet"
                            )
                        }
                    }

                    // Check Catalog Stars
                    stars.forEach { (celestialObj, horiz) ->
                        val sPos = HeroSkyProjection.project(horiz.azimuthDeg, horiz.altitudeDeg, canvasW, canvasH, lat)
                        val dist = HeroSkyProjection.screenDistance(tapOffset, sPos, canvasW)
                        if (dist < touchRadius && dist < minDistance) {
                            minDistance = dist
                            closestObj = SelectedCelestialInfo(
                                id = celestialObj.id,
                                name = if (fa) celestialObj.nameFa else celestialObj.nameEn,
                                position = sPos,
                                typeName = if (fa) celestialObj.type.nameFa else celestialObj.type.nameEn
                            )
                        }
                    }

                    selectedCelestial = closestObj
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        isDragging = true
                        autoReturnJob?.cancel()
                    },
                    onDragEnd = {
                        isDragging = false
                        autoReturnJob = coroutineScope.launch {
                            delay(5000)
                            simulatedOffsetHoursAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(2000, easing = FastOutSlowInEasing)
                            )
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        autoReturnJob = coroutineScope.launch {
                            delay(5000)
                            simulatedOffsetHoursAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(2000, easing = FastOutSlowInEasing)
                            )
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val canvasWidthPx = size.width.toFloat().coerceAtLeast(1f)
                        val deltaHours = -(dragAmount / canvasWidthPx) * 24.0f
                        val newOffset = (simulatedOffsetHoursAnim.value + deltaHours).coerceIn(-12.0f, 12.0f)

                        coroutineScope.launch {
                            simulatedOffsetHoursAnim.snapTo(newOffset)
                        }

                        // Emit Stardust particles along finger path styled by active theme
                        val particleColor = when (currentSkyTheme) {
                            SkyCanvasTheme.REAL_SKY -> if (Random.nextBoolean()) Color(0xFFFFF8EB) else Color(0xFF93C5FD)
                            SkyCanvasTheme.ATMOSPHERIC_SKY -> if (Random.nextBoolean()) Color(0xFF2DD4BF) else Color(0xFFFBBF24)
                            SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> if (currentSunHoriz.altitudeDeg > 0.0) Color(0xFF18181B) else Color.White
                            SkyCanvasTheme.KIDS_WATERCOLOR -> if (Random.nextBoolean()) Color(0xFFFF85A1) else Color(0xFF70D6FF)
                            SkyCanvasTheme.OBSERVATORY -> Color(0xFFEF4444)
                            SkyCanvasTheme.PAPERCRAFT_DIORAMA -> if (Random.nextBoolean()) Color(0xFFE07A5F) else Color(0xFF81B29A)
                        }
                        repeat(3) {
                            stardustParticles.add(
                                StardustParticle(
                                    x = change.position.x + Random.nextFloat() * 20f - 10f,
                                    y = change.position.y + Random.nextFloat() * 20f - 10f,
                                    vx = Random.nextFloat() * 4f - 2f,
                                    vy = Random.nextFloat() * -3f - 1f,
                                    size = Random.nextFloat() * 5f + 3f,
                                    alpha = 1.0f,
                                    color = particleColor
                                )
                            )
                        }
                    }
                )
            }
    ) {
        if (panoramaEnabled) {
            val skyPanoramaState = remember(skyFrame) { SkyPanoramaState.fromSceneFrame(skyFrame) }
            SkyPanoramaLayer(
                state = skyPanoramaState,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(RedCornerRadius.window)),
                onStatusChanged = { status -> panoramaReady = status == SkyPanoramaStatus.READY }
            )
        }

        // --- GPU CANVAS RENDERING PIPELINE ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            // Shared Sky Canvas pipeline: atmosphere, Milky Way, stars, Sun, Moon, planets, horizon landscape
            SkySceneRenderer.drawSky(
                drawScope = this,
                frame = skyFrame,
                theme = uiState.skyCanvasTheme,
                frameTimeMs = frameTimeMs,
                panoramaActive = panoramaActive
            )

            // Tapped Celestial Target Ring Overlay (Home-only interaction layer)
            selectedCelestial?.let { sel ->
                val selPos = when {
                    sel.id == "sun" -> HeroSkyProjection.project(sunHoriz.azimuthDeg, sunHoriz.altitudeDeg, canvasW, canvasH, userLat)
                    sel.id == "moon" -> HeroSkyProjection.project(moonData.azimuthDeg, moonData.altitudeDeg, canvasW, canvasH, userLat)
                    sel.id.startsWith("planet_") -> {
                        val pName = sel.id.removePrefix("planet_")
                        planetPositions.find { it.first.name.equals(pName, ignoreCase = true) }?.let {
                            HeroSkyProjection.project(it.third.azimuthDeg, it.third.altitudeDeg, canvasW, canvasH, userLat)
                        } ?: sel.position
                    }
                    else -> {
                        catalogStars.find { it.first.id == sel.id }?.let {
                            HeroSkyProjection.project(it.second.azimuthDeg, it.second.altitudeDeg, canvasW, canvasH, userLat)
                        } ?: sel.position
                    }
                }
                val pulseRing = 1.0f + 0.12f * sin(frameTimeMs * 0.005f).toFloat()
                val ringColor = when (uiState.skyCanvasTheme) {
                    SkyCanvasTheme.REAL_SKY -> Color(0xFFB4DCFF)
                    SkyCanvasTheme.ATMOSPHERIC_SKY -> Color(0xFF38BDF8)
                    SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> if (sunHoriz.altitudeDeg > 0.0) Color(0xFF18181B) else Color.White
                    SkyCanvasTheme.KIDS_WATERCOLOR -> Color(0xFFFF85A1)
                    SkyCanvasTheme.OBSERVATORY -> Color(0xFFEF4444)
                    SkyCanvasTheme.PAPERCRAFT_DIORAMA -> Color(0xFFE07A5F)
                }
                drawCircle(
                    color = ringColor.copy(alpha = 0.5f),
                    radius = 28.dp.toPx() * pulseRing,
                    center = selPos,
                    style = Stroke(width = 1.5.dp.toPx())
                )
                drawCircle(
                    color = ringColor,
                    radius = 18.dp.toPx(),
                    center = selPos,
                    style = Stroke(width = 1.8.dp.toPx())
                )
            }

            // 8. Particle Renderer (Stardust particles)
            ParticleRenderer.drawStardust(
                drawScope = this,
                particles = stardustParticles
            )
        }

        // --- FLOATING SELECTED CELESTIAL OBJECT NAME PILL ---
        // The compact card is too small for the pill, so it stays exclusive to the fullscreen viewer.
        val pillTarget = if (compact) null else selectedCelestial
        pillTarget?.let { sel ->
            val canvasW = constraints.maxWidth.toFloat()
            val canvasH = constraints.maxHeight.toFloat()
            val livePos = when {
                sel.id == "sun" -> HeroSkyProjection.project(sunHoriz.azimuthDeg, sunHoriz.altitudeDeg, canvasW, canvasH, userLat)
                sel.id == "moon" -> HeroSkyProjection.project(moonData.azimuthDeg, moonData.altitudeDeg, canvasW, canvasH, userLat)
                sel.id.startsWith("planet_") -> {
                    val pName = sel.id.removePrefix("planet_")
                    planetPositions.find { it.first.name.equals(pName, ignoreCase = true) }?.let {
                        HeroSkyProjection.project(it.third.azimuthDeg, it.third.altitudeDeg, canvasW, canvasH, userLat)
                    } ?: sel.position
                }
                else -> {
                    catalogStars.find { it.first.id == sel.id }?.let {
                        HeroSkyProjection.project(it.second.azimuthDeg, it.second.altitudeDeg, canvasW, canvasH, userLat)
                    } ?: sel.position
                }
            }
            val xDp = with(LocalDensity.current) { livePos.x.toDp() }
            val yDp = with(LocalDensity.current) { livePos.y.toDp() }
            val maxPillX = (maxWidth - 160.dp).coerceAtLeast(12.dp)
            val maxPillY = (maxHeight - 84.dp).coerceAtLeast(12.dp)

            Surface(
                onClick = {
                    val idToOpen = sel.id
                    selectedCelestial = null
                    if (idToOpen.isNotEmpty()) {
                        viewModel.openObjectDetailById(idToOpen, simulatedTimeMs)
                    }
                },
                shape = RoundedCornerShape(RedCornerRadius.window),
                color = Color(0xEE0F172A),
                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.7f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .offset(
                        x = (xDp - 70.dp).coerceIn(12.dp, maxPillX),
                        y = (yDp - 54.dp).coerceIn(12.dp, maxPillY)
                    )
                    .testTag("selected_celestial_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF38BDF8))
                    )
                    Text(
                        text = sel.name,
                        style = TextStyle(
                            fontFamily = LocalAppFontFamily.current,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    )
                    if (sel.typeName != null) {
                        Text(
                            text = "• ${sel.typeName}",
                            style = TextStyle(
                                fontFamily = LocalAppFontFamily.current,
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        )
                    }
                }
            }
        }

        // --- BOTTOM PILLS (Current / Simulated Time Pill & Date Pill) ---
        val isTimeOffsetActive = isDragging || abs(currentOffsetHours) > 0.05f
        val timeText = TimeEngine.formatTime24h(simulatedTimeMs, isFa, userTimeZone)
        val totalOffsetMinutes = (currentOffsetHours * 60f).roundToInt()
        val offsetText = if (abs(totalOffsetMinutes) >= 1) {
            val sign = if (totalOffsetMinutes > 0) "+" else "-"
            val absMins = abs(totalOffsetMinutes)
            val hrs = absMins / 60
            val mins = absMins % 60
            val rawStr = String.format(Locale.US, "%s%d:%02dh", sign, hrs, mins)
            if (isFa) rawStr.toPersianDigits() else rawStr
        } else ""

        val accentDotColor = if (isTimeOffsetActive) {
            when (uiState.skyCanvasTheme) {
                SkyCanvasTheme.REAL_SKY -> Color(0xFFB4DCFF)
                SkyCanvasTheme.ATMOSPHERIC_SKY -> Color(0xFFFBBF24)
                SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> Color.White
                SkyCanvasTheme.KIDS_WATERCOLOR -> Color(0xFFFF85A1)
                SkyCanvasTheme.OBSERVATORY -> Color(0xFFEF4444)
                SkyCanvasTheme.PAPERCRAFT_DIORAMA -> Color(0xFFE07A5F)
            }
        } else {
            Color(0xFF2DD4BF)
        }

        // The compact card shows the sky only; the time and date pills need the room.
        if (!compact) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stationary Current / Simulated Time pill
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0x66000000),
                    border = BorderStroke(
                        width = if (isTimeOffsetActive) 0.8.dp else 0.5.dp,
                        color = if (isTimeOffsetActive) accentDotColor.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.testTag("time_travel_bubble")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(accentDotColor)
                        )
                        if (isTimeOffsetActive) {
                            Text(
                                text = timeText,
                                style = TextStyle(
                                    fontFamily = LocalAppFontFamily.current,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = Color(0xFFF9FAFB)
                                )
                            )
                            if (offsetText.isNotEmpty()) {
                                Text(
                                    text = "($offsetText)",
                                    style = TextStyle(
                                        fontFamily = LocalAppFontFamily.current,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 10.sp,
                                        color = Color(0xFFE2E8F0)
                                    )
                                )
                            }
                        } else {
                            val livePrefix = if (uiState.timeMachineState.mode == TimeMachineMode.SIMULATION) {
                                if (isFa) "شبیه‌سازی" else "Simulated"
                            } else {
                                if (isFa) "آسمان زنده" else "Live sky"
                            }
                            Text(
                                text = "$livePrefix • $timeText",
                                style = TextStyle(
                                    fontFamily = LocalAppFontFamily.current,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 11.sp,
                                    color = Color(0xFFF9FAFB)
                                )
                            )
                        }
                    }
                }

                // Minimalistic Date pill
                val formattedDate = TimeEngine.formatDate(simulatedTimeMs, uiState.calendarSystem, isFa, userTimeZone).let {
                    if (isFa) it.toPersianDigits() else it
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0x66000000),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.25f))
                ) {
                    Text(
                        text = formattedDate,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = TextStyle(
                            fontFamily = LocalAppFontFamily.current,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            color = Color(0xFFF9FAFB)
                        )
                    )
                }
            }
        }

        // --- EXPAND AFFORDANCE: opens the fullscreen landscape sky viewer ---
        if (onExpand != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(34.dp)
                    // Clear glass over a dark scrim: the affordance has to read on a noon sky and on a
                    // moonless one alike, so it never takes the white card tint.
                    .liquidGlass(
                        shape = CircleShape,
                        style = LiquidGlassDefaults.Pill,
                        clearGlass = true,
                        fallbackColor = Color.Black.copy(alpha = 0.38f),
                        fallbackBorder = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.25f)),
                        glassBorder = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.32f))
                    )
                    .clickable(onClick = onExpand)
                    .testTag("sky_canvas_expand_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.FitScreen,
                    contentDescription = if (isFa) "نمایش تمام‌صفحه آسمان" else "Open the sky fullscreen",
                    tint = Color(0xFFF8FAFF),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}


