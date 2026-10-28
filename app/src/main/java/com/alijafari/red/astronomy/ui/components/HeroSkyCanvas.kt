package com.alijafari.red.astronomy.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.astro_engine.*
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.domain.ObjectType
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import com.alijafari.red.astronomy.domain.TimeMachineMode
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.MainViewModel
import com.alijafari.red.astronomy.ui.persepolis.PersepolisFeature
import com.alijafari.red.astronomy.ui.persepolis.PersepolisLandscape
import com.alijafari.red.astronomy.ui.rendering.*
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaFeature
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaLayer
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaState
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaStatus
import androidx.compose.ui.graphics.Brush
import com.alijafari.red.astronomy.ui.theme.LiquidGlassDefaults
import com.alijafari.red.astronomy.ui.theme.LocalAppFontFamily
import com.alijafari.red.astronomy.ui.theme.RedCornerRadius
import com.alijafari.red.astronomy.ui.theme.RedShapes
import com.alijafari.red.astronomy.ui.theme.liquidGlass
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.alijafari.red.astronomy.util.toPersianDigits
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Idle frame interval of the full-bleed scene: ~24 fps. Slow cloud drift and a breathing figure need no
 * more, and the sky itself is not repainted on this clock (see the three drawing layers below).
 */
private const val SCENE_MOTION_FRAME_MS = 42L

/**
 * Quantum of the instant the sky MODEL is computed for, in live mode.
 *
 * Home used to recompute the whole scene — Moon ELP2000, every planet, every drawn star, the galactic
 * plane — once per second on the main thread. The sky rotates 15°/hour, so fifteen seconds is 0.06°:
 * well under a pixel on the Home scene at any latitude. Keying the model on a 15 s bucket instead of the
 * 1 Hz clock cuts that work by 15x with no visible change. The visible clock, the motion layer and the
 * frame counters keep their own, finer clocks.
 */
private const val SKY_MODEL_QUANTUM_MS = 15_000L

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

/** How the sky canvas is presented. The rendering is identical; the framing and the chrome change. */
enum class SkyCanvasPresentation {

    /**
     * The full-bleed Home scene: edge to edge, no rounded corners, a low horizon, the ground band, the
     * cloud veils, the observer and the typographic readout on the sky. This is the surface that reads
     * like a weather app's hero, with the sky drawn by ZIG's own astronomy.
     */
    SCENE,

    /** The fullscreen landscape sky viewer: the same sky, edge to edge, with its time and date pills. */
    VIEWER
}

/**
 * The interactive sky canvas: the shared sky renderer, tap-to-identify and finger time travel.
 *
 * The caller owns the size. [presentation] decides the framing: the full-bleed Home scene, or the
 * landscape viewer. [onExpand] adds the small expand affordance that opens the fullscreen viewer.
 *
 * The drawing is split into three layers so the moving parts never repaint the astronomy:
 *  1. the sky, on the clock tick (or per frame while the finger scrubs time);
 *  2. the scene motion — drifting cloud veils and the breathing observer — on a faster tick, scene only;
 *  3. the interaction layer — selection ring and stardust — only while the user is touching it.
 *
 * Between layers 1 and 2, the scene on the photographic themes also carries the combined
 * Persepolis + Mil-e Azhdaha relief (see [PersepolisLandscape]): a transparent GL surface standing
 * in layer 1's ground band, lit by the
 * same ephemeris, rendering one frame per sky-model change. It is a peer of the sky clock, not of the
 * motion clock, so it is not numbered with the repaint layers above.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeroSkyCanvas(
    uiState: MainUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    presentation: SkyCanvasPresentation = SkyCanvasPresentation.VIEWER,
    onExpand: (() -> Unit)? = null,
    // Scene presentation only: how far the ground's dissolved tail reaches *below* the
    // canvas's own bottom edge (see the SCENE TAIL layer below). Home passes the height
    // of the card row that follows the canvas, so the tail passes underneath the cards;
    // every other caller leaves it at zero and the canvas ends at its own edge.
    bottomTailHeight: Dp = 0.dp
) {
    val isFa = uiState.language == AppLanguage.PERSIAN
    val coroutineScope = rememberCoroutineScope()

    val isScene = presentation == SkyCanvasPresentation.SCENE
    // The scene puts the horizon low so the sky owns the frame; the viewer keeps the old 0.85.
    val horizonFraction = if (isScene) SkySceneLayout.HORIZON_FRACTION else HeroSkyProjection.HORIZON_FRACTION
    val renderTarget = if (isScene) SkyRenderTarget.HOME_SCENE else SkyRenderTarget.HOME_HERO

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

    // Two clocks. The sky clock moves on the shared 1 Hz tick (and per frame while time is being scrubbed);
    // the motion clock runs the cloud drift and the observer at a capped frame rate. Keeping them apart is
    // what lets the cheap layer animate without repainting the stars, the Moon sprite and the Milky Way.
    var skyTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var motionTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasParticles by remember { derivedStateOf { stardustParticles.isNotEmpty() } }
    val isAnimatingReturn = simulatedOffsetHoursAnim.isRunning
    val hasSelection = selectedCelestial != null

    // Steady state real-time clock: one tick per second from the shared clock, instead of every frame when idle
    LaunchedEffect(currentSystemTimeMs) {
        if (!isDragging && !simulatedOffsetHoursAnim.isRunning) {
            skyTimeMs = currentSystemTimeMs
        }
    }

    LaunchedEffect(isDragging, hasParticles, isAnimatingReturn, hasSelection, isScene) {
        // The keys above are exactly the inputs of this flag, so it is always fresh at launch: a change
        // restarts the effect.
        val interactive = isDragging || hasParticles || isAnimatingReturn || hasSelection

        fun stepParticles() {
            if (stardustParticles.isEmpty()) return
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

        while (interactive || isScene) {
            if (interactive) {
                // Anything the finger drives runs at the display rate, sky included.
                withFrameMillis { ms ->
                    motionTimeMs = ms
                    skyTimeMs = ms
                    stepParticles()
                }
            } else {
                // The idle scene wakes at ~24 Hz rather than on every vsync, and never touches the sky
                // clock: drifting veils and a breathing figure need no more, and the astronomy keeps its
                // own slow tick.
                delay(SCENE_MOTION_FRAME_MS)
                motionTimeMs = System.currentTimeMillis()
            }
        }
    }

    // Current Simulated Julian Date (Honours TimeMachineState in simulation mode, plus any finger time travel offset)
    val currentOffsetHours = simulatedOffsetHoursAnim.value
    val isSimulationMode = uiState.timeMachineState.mode == TimeMachineMode.SIMULATION
    val baseTimeMs = SkyTimeModel.baseTimeMs(
        isSimulation = isSimulationMode,
        simulationTimeMs = uiState.timeMachineState.simulationTimeMs,
        liveTimeMs = currentSystemTimeMs
    )
    // The model instant is bucketed in live mode (see SKY_MODEL_QUANTUM_MS) and exact in simulation mode,
    // where it only moves when the user moves the time machine. The drag offset is never bucketed: the
    // finger has to move the sky as it moves.
    val modelBaseTimeMs = if (isSimulationMode) {
        baseTimeMs
    } else {
        baseTimeMs - Math.floorMod(baseTimeMs, SKY_MODEL_QUANTUM_MS)
    }
    // Same effective instant the Live Sky backdrop uses (see SkyTimeModel)
    val simulatedJd = SkyTimeModel.effectiveJd(modelBaseTimeMs, currentOffsetHours)
    // The instant the READOUT shows is exact, not bucketed: the clock must never lag its own minute. Only
    // the sky model above is quantised, and 15 s of sky rotation is far below one pixel.
    val simulatedTimeMs = TimeEngine.getTimestampFromJulianDate(
        SkyTimeModel.effectiveJd(baseTimeMs, currentOffsetHours)
    )

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

    // Keep the identification visible long enough to read and open its detail card.
    LaunchedEffect(selectedCelestial) {
        if (selectedCelestial != null) {
            delay(4_000)
            selectedCelestial = null
        }
    }

    // A fresh radial bloom accompanies each selection; the continuous scene clock then carries its gentle pulse.
    val selectionBloom = remember { Animatable(1f) }
    LaunchedEffect(selectedCelestial?.id) {
        if (selectedCelestial != null) {
            selectionBloom.snapTo(0f)
            selectionBloom.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 560, easing = FastOutSlowInEasing)
            )
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

    // The photographic Moon: the Home Moon card's look with the true phase, libration and visibility.
    val moonSprites = rememberSkyMoonSprites()

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
            // The scene is full-bleed and its tail (see SCENE TAIL below) deliberately
            // paints past the bottom edge, so the scene must not be clipped to its own
            // bounds. The card and viewer keep their squircle.
            .then(if (isScene) Modifier else Modifier.clip(squircleShape(RedCornerRadius.window)))
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
                        val sunScreenPos = HeroSkyProjection.project(sun.azimuthDeg, sun.altitudeDeg, canvasW, canvasH, lat, horizonFraction)
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
                        val moonScreenPos = HeroSkyProjection.project(moon.azimuthDeg, moon.altitudeDeg, canvasW, canvasH, lat, horizonFraction)
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
                        val pPos = HeroSkyProjection.project(horiz.azimuthDeg, horiz.altitudeDeg, canvasW, canvasH, lat, horizonFraction)
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
                        val sPos = HeroSkyProjection.project(horiz.azimuthDeg, horiz.altitudeDeg, canvasW, canvasH, lat, horizonFraction)
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
                    .clip(if (isScene) RectangleShape else squircleShape(RedCornerRadius.window)),
                onStatusChanged = { status -> panoramaReady = status == SkyPanoramaStatus.READY }
            )
        }

        // Combined-landscape framing for this exact scene size (see PersepolisFraming): Persepolis
        // at half the first integration's linear size, the whole group placed left and farther
        // back in the ground plane. The sky pass recomputes the same result for its
        // footprint-local grounding paint, so the stone and painted ground meet at the same toe,
        // platform edges and tower-slope front. Null unless this is the full-bleed scene on a
        // photographic theme.
        val persepolisFraming = if (isScene && PersepolisFeature.isEnabledFor(uiState.skyCanvasTheme)) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            PersepolisFraming.forScene(
                widthPx = widthPx,
                heightPx = heightPx,
                horizonYPx = heightPx * horizonFraction,
                readoutBottomPx = heightPx * horizonFraction -
                    with(density) { PersepolisFraming.READOUT_INSET_DP.dp.toPx() },
                readoutRightPx = with(density) { PersepolisFraming.READOUT_RIGHT_DP.dp.toPx() },
                readoutClearancePx = with(density) { PersepolisFraming.READOUT_CLEARANCE_DP.dp.toPx() }
            )
        } else null

        // --- LAYER 1: THE SKY ---
        // Atmosphere, Milky Way, stars, Sun, Moon, planets, ground. The expensive pass: it repaints on the
        // clock tick, not on every animation frame.
        Canvas(modifier = Modifier.fillMaxSize()) {
            SkySceneRenderer.drawSky(
                drawScope = this,
                frame = skyFrame,
                theme = uiState.skyCanvasTheme,
                frameTimeMs = skyTimeMs,
                panoramaActive = panoramaActive,
                target = renderTarget,
                moonSprites = moonSprites,
                bortle = uiState.bortleClass
            )
        }

        // --- COMBINED LANDSCAPE: THE GROUND'S SUBJECTS ---
        // Persepolis in the left distance and Mil-e Azhdaha on its limestone hillside, as one
        // transparent GL relief set into layer 1's ground band: the ranges behind
        // them are their backdrop, the deeper field below their feet is the foreground, and the
        // localized earthwork the sky pass paints around both footprints seats them in the
        // terrain. They are lit by
        // this frame's real Sun and Moon and render on demand (per sky-model change), never on the
        // motion clock. Photographic themes only — PersepolisFeature is the gate. The relief is
        // hosted on a TextureView (see PersepolisTextureView), so it is ordinary window content:
        // layers composed after it — motion veils, the readout, in-window overlays such as the
        // sky search — cover and dim it like the painted layers, and it scrolls with the feed.
        if (persepolisFraming != null) {
            PersepolisLandscape(
                frame = skyFrame,
                framing = persepolisFraming,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("persepolis_landscape")
            )
        }

        // --- LAYER 2: SCENE MOTION ---
        // Drifting cloud veils and the observer on the grass line. Full-bleed scene only, and cheap enough
        // to run continuously.
        if (isScene) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                SkySceneRenderer.drawSceneMotion(
                    drawScope = this,
                    frame = skyFrame,
                    theme = uiState.skyCanvasTheme,
                    frameTimeMs = motionTimeMs
                )
            }
        }

        // --- LAYER 3: INTERACTION ---
        // Selection ring, tapped-object pill anchor and stardust: live only while the user is touching.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            // Tapped Celestial Target Ring Overlay (Home-only interaction layer)
            selectedCelestial?.let { sel ->
                val selPos = when {
                    sel.id == "sun" -> HeroSkyProjection.project(sunHoriz.azimuthDeg, sunHoriz.altitudeDeg, canvasW, canvasH, userLat, horizonFraction)
                    sel.id == "moon" -> HeroSkyProjection.project(moonData.azimuthDeg, moonData.altitudeDeg, canvasW, canvasH, userLat, horizonFraction)
                    sel.id.startsWith("planet_") -> {
                        val pName = sel.id.removePrefix("planet_")
                        planetPositions.find { it.first.name.equals(pName, ignoreCase = true) }?.let {
                            HeroSkyProjection.project(it.third.azimuthDeg, it.third.altitudeDeg, canvasW, canvasH, userLat, horizonFraction)
                        } ?: sel.position
                    }
                    else -> {
                        catalogStars.find { it.first.id == sel.id }?.let {
                            HeroSkyProjection.project(it.second.azimuthDeg, it.second.altitudeDeg, canvasW, canvasH, userLat, horizonFraction)
                        } ?: sel.position
                    }
                }
                // Wrapped phase: wall-clock milliseconds are too large for a meaningful Float argument to sin.
                val pulsePhase = (motionTimeMs % 4_000L).toFloat() / 4_000f * 2f * PI.toFloat()
                val pulseRing = 1.0f + 0.12f * sin(pulsePhase)
                val ringColor = when (uiState.skyCanvasTheme) {
                    SkyCanvasTheme.REAL_SKY -> Color(0xFFB4DCFF)
                    SkyCanvasTheme.ATMOSPHERIC_SKY -> Color(0xFF38BDF8)
                    SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> if (sunHoriz.altitudeDeg > 0.0) Color(0xFF18181B) else Color.White
                    SkyCanvasTheme.KIDS_WATERCOLOR -> Color(0xFFFF85A1)
                    SkyCanvasTheme.OBSERVATORY -> Color(0xFFEF4444)
                    SkyCanvasTheme.PAPERCRAFT_DIORAMA -> Color(0xFFE07A5F)
                }
                val bloom = selectionBloom.value.coerceIn(0f, 1f)
                drawCircle(
                    color = ringColor.copy(alpha = (1f - bloom) * 0.72f),
                    radius = (13.dp.toPx() + 24.dp.toPx() * bloom) * pulseRing,
                    center = selPos,
                    style = Stroke(width = (2.4f - bloom * 0.8f).dp.toPx())
                )
                drawCircle(
                    color = ringColor.copy(alpha = 0.94f),
                    radius = (12.dp.toPx() + 6.dp.toPx() * bloom),
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

        // --- SCENE TAIL (scene presentation, Home only) ---
        // The scene does not end at its bottom edge: the ground's own bottom colour
        // continues *downward and outward* below the canvas, dissolving to full
        // transparency over bottomTailHeight — the height of the Moon/eclipse card row
        // that follows the canvas on Home, so the tail passes underneath those cards.
        // Because it fades in alpha rather than toward any colour, it merges into
        // whatever backdrop is actually behind the screen — the theme background, the
        // live-sky backdrop render, or any other app backdrop — with nothing per-case
        // to adapt. The canvas itself stays crisp to its edge; nothing fades upward
        // into it. The GL monuments composite above this layer, but their stone ends
        // far above the canvas's bottom, so the tail never has stone to fade.
        if (isScene && bottomTailHeight > 0.dp) {
            // The exact colour of the canvas's bottom row: the ground gradient's last
            // stop, darkened by the ground renderer's foot fade (black at 0.26) — so
            // the seam at the canvas edge is colour-continuous before the dissolve.
            val tailColor = lerp(
                scenePalette(uiState.skyCanvasTheme, skyFrame.lightingState).groundBottom,
                Color.Black,
                0.26f
            )
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(bottomTailHeight)
                    .offset(y = bottomTailHeight)
            ) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to tailColor,
                        0.35f to tailColor.copy(alpha = 0.55f),
                        0.7f to tailColor.copy(alpha = 0.22f),
                        1.0f to tailColor.copy(alpha = 0f)
                    ),
                    size = size
                )
            }
        }

        // --- SCENE READOUT ---
        // Caption scale, on purpose. The scene is the product; the instant it is drawn for is the
        // caption. The clock is still the first thing the eye finds, but it sits at reading size with an
        // editorial hairline between it and the sky's own state, so the frame stays the sky's. The
        // numbers come from the same state the sky is drawn from, so they can never disagree with it.
        if (isScene) {
            val timeText = TimeEngine.formatTime24h(simulatedTimeMs, isFa, userTimeZone)
                .let { if (isFa) it.toPersianDigits() else it }
            val dateText = TimeEngine.formatDate(simulatedTimeMs, uiState.calendarSystem, isFa, userTimeZone)
                .let { if (isFa) it.toPersianDigits() else it }
            val conditionText = skyConditionLabel(sunHoriz.altitudeDeg, isFa)
            val moonPercent = moonData.illuminationPercent.roundToInt()
            val moonText = if (moonData.altitudeDeg > 0.0) {
                if (isFa) "ماه $moonPercent٪ بالای افق" else "Moon $moonPercent% up"
            } else {
                if (isFa) "ماه زیر افق" else "Moon below horizon"
            }.let { if (isFa) it.toPersianDigits() else it }

            // True while the finger is travelling in time: the dot says so instead of a caption nobody
            // reads mid-gesture.
            val isReadingSimulatedInstant = isDragging || abs(currentOffsetHours) > 0.05f
            val readoutAccent = when (uiState.skyCanvasTheme) {
                SkyCanvasTheme.REAL_SKY -> Color(0xFFB4DCFF)
                SkyCanvasTheme.ATMOSPHERIC_SKY -> Color(0xFFFBBF24)
                SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> if (sunHoriz.altitudeDeg > 0.0) Color(0xFF18181B) else Color.White
                SkyCanvasTheme.KIDS_WATERCOLOR -> Color(0xFFFF85A1)
                SkyCanvasTheme.OBSERVATORY -> Color(0xFFEF4444)
                SkyCanvasTheme.PAPERCRAFT_DIORAMA -> Color(0xFFE07A5F)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = (maxHeight * (1f - horizonFraction)) + 12.dp
                    )
                    .widthIn(max = maxWidth * 0.72f)
                    .testTag("home_sky_readout"),
                horizontalAlignment = Alignment.Start
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeText,
                        style = TextStyle(
                            fontFamily = LocalAppFontFamily.current,
                            fontWeight = FontWeight.Medium,
                            fontSize = 26.sp,
                            lineHeight = 28.sp,
                            letterSpacing = if (isFa) 0.sp else 0.5.sp,
                            color = Color.White,
                            shadow = Shadow(Color.Black.copy(alpha = 0.38f), Offset(0f, 1f), 6f)
                        ),
                        maxLines = 1
                    )

                    // The hairline that separates "when" from "what". One device pixel wide, the height
                    // of the clock's cap height, so it reads as a rule in a table rather than as a chip.
                    Spacer(modifier = Modifier.width(9.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(17.dp)
                            .background(Color.White.copy(alpha = 0.30f))
                    )
                    Spacer(modifier = Modifier.width(9.dp))

                    if (isReadingSimulatedInstant) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(readoutAccent)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }

                    Text(
                        text = conditionText,
                        style = TextStyle(
                            fontFamily = LocalAppFontFamily.current,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            letterSpacing = if (isFa) 0.sp else 0.2.sp,
                            color = Color.White.copy(alpha = 0.92f),
                            shadow = Shadow(Color.Black.copy(alpha = 0.34f), Offset(0f, 1f), 5f)
                        ),
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "$dateText  ·  $moonText",
                    style = TextStyle(
                        fontFamily = LocalAppFontFamily.current,
                        fontWeight = FontWeight.Normal,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        letterSpacing = if (isFa) 0.sp else 0.15.sp,
                        color = Color.White.copy(alpha = 0.66f),
                        shadow = Shadow(Color.Black.copy(alpha = 0.30f), Offset(0f, 1f), 4f)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // --- FLOATING SELECTED CELESTIAL OBJECT NAME PILL ---
        // Present in both Home and fullscreen: this is the affordance that turns identify into detail.
        AnimatedVisibility(
            visible = selectedCelestial != null,
            enter = fadeIn(tween(180)) + scaleIn(
                initialScale = 0.82f,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
            ) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 5 },
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.94f, animationSpec = tween(140)) +
                slideOutVertically(tween(140)) { -it / 8 }
        ) {
            selectedCelestial?.let { sel ->
                val canvasW = constraints.maxWidth.toFloat()
                val canvasH = constraints.maxHeight.toFloat()
                val livePos = when {
                    sel.id == "sun" -> HeroSkyProjection.project(sunHoriz.azimuthDeg, sunHoriz.altitudeDeg, canvasW, canvasH, userLat, horizonFraction)
                    sel.id == "moon" -> HeroSkyProjection.project(moonData.azimuthDeg, moonData.altitudeDeg, canvasW, canvasH, userLat, horizonFraction)
                    sel.id.startsWith("planet_") -> {
                        val pName = sel.id.removePrefix("planet_")
                        planetPositions.find { it.first.name.equals(pName, ignoreCase = true) }?.let {
                            HeroSkyProjection.project(it.third.azimuthDeg, it.third.altitudeDeg, canvasW, canvasH, userLat, horizonFraction)
                        } ?: sel.position
                    }
                    else -> {
                        catalogStars.find { it.first.id == sel.id }?.let {
                            HeroSkyProjection.project(it.second.azimuthDeg, it.second.altitudeDeg, canvasW, canvasH, userLat, horizonFraction)
                        } ?: sel.position
                    }
                }
                val xDp = with(LocalDensity.current) { livePos.x.toDp() }
                val yDp = with(LocalDensity.current) { livePos.y.toDp() }
                val bubbleMaxWidth = (maxWidth - 24.dp).coerceAtMost(240.dp)
                val maxPillX = (maxWidth - bubbleMaxWidth).coerceAtLeast(12.dp)
                val maxPillY = (maxHeight - 72.dp).coerceAtLeast(12.dp)
                val accent = when (uiState.skyCanvasTheme) {
                    SkyCanvasTheme.REAL_SKY -> Color(0xFFB4DCFF)
                    SkyCanvasTheme.ATMOSPHERIC_SKY -> Color(0xFF38BDF8)
                    SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> Color.White
                    SkyCanvasTheme.KIDS_WATERCOLOR -> Color(0xFFFF85A1)
                    SkyCanvasTheme.OBSERVATORY -> Color(0xFFEF4444)
                    SkyCanvasTheme.PAPERCRAFT_DIORAMA -> Color(0xFFE07A5F)
                }

                Surface(
                    onClick = {
                        val idToOpen = sel.id
                        selectedCelestial = null
                        if (idToOpen.isNotEmpty()) {
                            viewModel.openObjectDetailById(idToOpen, simulatedTimeMs)
                        }
                    },
                    shape = RedShapes.capsule,
                    color = Color(0xE6101727),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.72f)),
                    shadowElevation = 14.dp,
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .offset(
                            x = (xDp - 74.dp).coerceIn(12.dp, maxPillX),
                            y = (yDp - 56.dp).coerceIn(12.dp, maxPillY)
                        )
                        .testTag("selected_celestial_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(start = 13.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = sel.name,
                                style = TextStyle(
                                    fontFamily = LocalAppFontFamily.current,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = Color.White
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = sel.typeName ?: if (isFa) "مشاهده اطلاعات" else "View details",
                                style = TextStyle(
                                    fontFamily = LocalAppFontFamily.current,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 10.sp,
                                    letterSpacing = if (isFa) 0.sp else 0.3.sp,
                                    color = Color.White.copy(alpha = 0.66f)
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = if (isFa) "‹" else "›",
                            style = TextStyle(fontSize = 22.sp, lineHeight = 22.sp, color = accent),
                            modifier = Modifier.padding(start = 2.dp)
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

        // Only the fullscreen viewer has room for the time and date pills; the card and the scene do not.
        if (presentation == SkyCanvasPresentation.VIEWER) {
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
                    shape = RedShapes.capsule,
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
                    shape = RedShapes.capsule,
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




/**
 * The astronomical phase of the day, as a short label for the scene readout.
 *
 * These are the standard twilight bands, read straight from the Sun altitude the scene is drawn for — not
 * a forecast and not a weather condition.
 */
private fun skyConditionLabel(sunAltitudeDeg: Double, isFa: Boolean): String = when {
    sunAltitudeDeg > 6.0 -> if (isFa) "روز" else "Daylight"
    sunAltitudeDeg > 0.0 -> if (isFa) "ساعت طلایی" else "Golden hour"
    sunAltitudeDeg > -6.0 -> if (isFa) "شفق مدنی" else "Civil twilight"
    sunAltitudeDeg > -12.0 -> if (isFa) "شفق دریایی" else "Nautical twilight"
    sunAltitudeDeg > -18.0 -> if (isFa) "شفق نجومی" else "Astronomical twilight"
    else -> if (isFa) "شب نجومی" else "Astronomical night"
}
