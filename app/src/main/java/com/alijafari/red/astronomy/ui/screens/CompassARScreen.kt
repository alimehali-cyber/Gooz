package com.alijafari.red.astronomy.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alijafari.red.astronomy.astro_engine.*
import com.alijafari.red.astronomy.data.catalog.AstronomyCatalog
import com.alijafari.red.astronomy.data.catalog.SolarSystemCatalog
import com.alijafari.red.astronomy.domain.*
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.MainViewModel
import com.alijafari.red.astronomy.ui.components.ARSensorCalibrationDialog
import com.alijafari.red.astronomy.ui.components.TimeMachineControlBar
import com.alijafari.red.astronomy.ui.rendering.*
import com.alijafari.red.astronomy.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.rotate
import com.alijafari.red.astronomy.R
import java.util.Locale
import kotlin.math.*

enum class ArExpandedPanel {
    SEARCH, TIME_MACHINE, FILTERS, SENSORS
}

private fun formatObjectDistance(
    obj: CelestialObject,
    isFa: Boolean,
    calculatedState: CalculatedAstroState? = null
): String = ARDistanceFormatter.formatDistance(obj, isFa, calculatedState)

@Composable
private fun ArSmartPill(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    isHighlighted: Boolean = false,
    testTag: String? = null,
    onClick: () -> Unit
) {
    LiquidGlassSurface(
        onClick = onClick,
        shape = CircleShape,
        style = LiquidGlassDefaults.Pill,
        fallbackColor = if (isActive) RedTheme.colors.accentRed else if (isHighlighted) RedTheme.colors.surfaceElevated.copy(alpha = 0.95f) else RedTheme.colors.surfaceElevated.copy(alpha = 0.85f),
        fallbackBorder = BorderStroke(
            1.dp,
            if (isActive) Color.Transparent else if (isHighlighted) RedTheme.colors.accentRed.copy(alpha = 0.6f) else RedTheme.colors.border
        ),
        fallbackShadowElevation = RedElevation.floating,
        modifier = Modifier
            .height(36.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) Color.White else if (isHighlighted) RedTheme.colors.accentRed else RedTheme.colors.textPrimary,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = label,
                style = RedTypographyTokens.caption.copy(
                    fontWeight = if (isActive || isHighlighted) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.sp
                ),
                color = if (isActive) Color.White else RedTheme.colors.textPrimary
            )
        }
    }
}

private data class ArVisibleRenderItem(
    val obj: CelestialObject,
    val horiz: CoordinateEngine.Horizontal,
    val px: Float,
    val py: Float,
    val plan: CelestialObjectSizes.ArRenderPlan
)

private data class ArEdgeIndicator(
    val obj: CelestialObject,
    val xPx: Float,
    val yPx: Float,
    val angleDeg: Float,
    val priority: Double
)

private data class ArLabelCluster(
    val objects: List<CelestialObject>,
    val centerX: Float,
    val centerY: Float
)

private enum class ArHealthLevel { GREEN, AMBER, RED, GRAY }

private data class ArSubsystemHealth(
    val level: ArHealthLevel,
    val titleEn: String,
    val titleFa: String,
    val detailEn: String,
    val detailFa: String
)

private fun arHealthColor(level: ArHealthLevel): Color = when (level) {
    ArHealthLevel.GREEN -> StatusGood
    ArHealthLevel.AMBER -> Color(0xFFF59E0B)
    ArHealthLevel.RED -> AccentPrimary
    ArHealthLevel.GRAY -> Color(0xFF94A3B8)
}

private fun worstHealthLevel(levels: List<ArHealthLevel>): ArHealthLevel {
    return when {
        levels.any { it == ArHealthLevel.RED } -> ArHealthLevel.RED
        levels.any { it == ArHealthLevel.AMBER } -> ArHealthLevel.AMBER
        levels.any { it == ArHealthLevel.GRAY } -> ArHealthLevel.GRAY
        else -> ArHealthLevel.GREEN
    }
}

private fun isDeepSkyType(type: ObjectType): Boolean {
    return type == ObjectType.DEEP_SKY ||
            type == ObjectType.GALAXY ||
            type == ObjectType.NEBULA ||
            type == ObjectType.STAR_CLUSTER ||
            type == ObjectType.GLOBULAR_CLUSTER ||
            type == ObjectType.BLACK_HOLE
}

private fun isVisibleByArFilter(
    obj: CelestialObject,
    filterStars: Boolean,
    filterSun: Boolean,
    filterMoons: Boolean,
    filterPlanets: Boolean,
    filterSatellites: Boolean,
    filterDeepSky: Boolean,
    filterMeteorShowers: Boolean
): Boolean {
    // Not a sky object at all, so not something the visibility filters can ever switch on.
    if (ARSkyCatalog.isExcludedFromArSky(obj)) return false
    return when (obj.type) {
        ObjectType.STAR, ObjectType.ASTERISM -> filterStars
        ObjectType.SUN -> filterSun
        ObjectType.MOON -> filterMoons
        ObjectType.PLANET, ObjectType.DWARF_PLANET -> filterPlanets
        ObjectType.SATELLITE -> filterSatellites
        ObjectType.METEOR_SHOWER -> filterMeteorShowers
        ObjectType.DEEP_SKY, ObjectType.GALAXY, ObjectType.NEBULA,
        ObjectType.STAR_CLUSTER, ObjectType.GLOBULAR_CLUSTER, ObjectType.BLACK_HOLE -> filterDeepSky
        else -> true
    }
}

private fun normalizeDeltaDegrees(delta: Double): Double {
    var d = delta
    while (d > 180.0) d -= 360.0
    while (d < -180.0) d += 360.0
    return d
}

private fun shouldShowArLabel(
    obj: CelestialObject,
    isSelected: Boolean,
    isAimed: Boolean,
    filterObjectNames: Boolean,
    labelEligible: Boolean
): Boolean {
    // Tap / centre-reticle targeting behaves exactly as before, at any tier.
    if (isSelected || isAimed) return true
    // A dot-tier object has not been resolved into its shape yet, so it is never labelled
    // ambiently at any zoom level; ambient labels start when the object crosses into shape tier.
    if (!labelEligible) return false
    return if (filterObjectNames) {
        obj.magnitude <= CelestialObjectSizes.LABEL_SHOW_MAGNITUDE_THRESHOLD || obj.type != ObjectType.STAR
    } else {
        false
    }
}

/**
 * Dot-tier tint: a neutral point of light, nudged toward the colour the object's full glyph will
 * use once it resolves, so growth into the shape tier reads as the same object brightening rather
 * than as a different marker appearing. Intensity itself is handled by the plan's dot alpha.
 */
private fun arDotColor(obj: CelestialObject): Color = when (obj.type) {
    ObjectType.SUN -> Color(0xFFFFCC00)
    ObjectType.MOON -> Color(0xFFF1FAEE)
    ObjectType.PLANET, ObjectType.DWARF_PLANET -> when (obj.id) {
        "planet_mars" -> Color(0xFFEF4444)
        "planet_venus" -> Color(0xFFFEF08A)
        "planet_uranus", "planet_neptune" -> Color(0xFF38BDF8)
        else -> Color(0xFFFFD166)
    }
    ObjectType.SATELLITE -> Color(0xFF9BD5FE)
    ObjectType.GALAXY, ObjectType.BLACK_HOLE -> Color(0xFFC084FC)
    ObjectType.NEBULA -> Color(0xFFF472B6)
    ObjectType.STAR_CLUSTER, ObjectType.GLOBULAR_CLUSTER -> Color(0xFFE0AAFF)
    ObjectType.DEEP_SKY -> when {
        obj.category.contains("Nebula", ignoreCase = true) -> Color(0xFFF472B6)
        obj.category.contains("Galaxy", ignoreCase = true) -> Color(0xFFC084FC)
        else -> Color(0xFFE0AAFF)
    }
    else -> when {
        obj.magnitude <= 0.5 -> Color(0xFFA5F3FC)
        obj.magnitude <= 2.5 -> Color(0xFFE2E8F0)
        else -> Color(0xFFCBD5E1)
    }
}

private fun buildLabelClusters(
    labelItems: List<ArVisibleRenderItem>,
    minDistancePx: Float,
    maxClusterRadiusPx: Float
): List<ArLabelCluster> {
    if (labelItems.size < 3) return emptyList()
    val visited = BooleanArray(labelItems.size)
    val clusters = mutableListOf<ArLabelCluster>()
    for (i in labelItems.indices) {
        if (visited[i]) continue
        val seed = labelItems[i]
        val members = mutableListOf(seed)
        visited[i] = true
        for (j in i + 1 until labelItems.size) {
            if (visited[j]) continue
            val other = labelItems[j]
            val dist = hypot((other.px - seed.px).toDouble(), (other.py - seed.py).toDouble()).toFloat()
            if (dist <= minDistancePx) {
                members.add(other)
                visited[j] = true
            }
        }
        if (members.size >= 3) {
            val cx = members.map { it.px }.average().toFloat()
            val cy = members.map { it.py }.average().toFloat()
            val tight = members.all { hypot((it.px - cx).toDouble(), (it.py - cy).toDouble()) <= maxClusterRadiusPx }
            if (tight) {
                clusters.add(ArLabelCluster(members.map { it.obj }, cx, cy))
            } else {
                members.drop(1).forEach { member ->
                    val idx = labelItems.indexOf(member)
                    if (idx >= 0) visited[idx] = false
                }
            }
        }
    }
    return clusters
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassARScreen(
    uiState: MainUiState,
    viewModel: MainViewModel
) {
    val isFa = uiState.language == AppLanguage.PERSIAN
    // Text measured straight onto a Canvas never merges the theme's LocalTextStyle, so the locale
    // face is taken from the theme here and handed to each measure() call below.
    val canvasFontFamily = LocalAppFontFamily.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val textMeasurer = rememberTextMeasurer()

    // Keep screen awake
    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Sensor Fusion & Orientation Provider
    LaunchedEffect(context) {
        ARCalibrationManager.init(context)
    }
    val orientationProvider = remember { OrientationProvider(context) }
    val skyOrientation by orientationProvider.orientation.collectAsState()
    val calibrationState by orientationProvider.calibrationState.collectAsState()
    val magneticInterferenceDetected by orientationProvider.magneticInterference.collectAsState()
    val arCalibrationOffsets by ARCalibrationManager.calibrationFlow.collectAsState()
    val autoPromptEnabled by ARCalibrationManager.autoPromptEnabledFlow.collectAsState()
    var isAlignmentMode by remember { mutableStateOf(false) }
    var autoPromptDismissedThisSession by remember { mutableStateOf(false) }
    var showManualSensorPrompt by remember { mutableStateOf(false) }
    var alignmentConfirmationDeg by remember { mutableStateOf<Float?>(null) }
    var recalibrationPromptDismissedThisSession by remember { mutableStateOf(false) }

    // Auto-dismiss the "Aligned: offset applied" confirmation pill
    LaunchedEffect(alignmentConfirmationDeg) {
        if (alignmentConfirmationDeg != null) {
            delay(2600L)
            alignmentConfirmationDeg = null
        }
    }

    // Camera Intrinsics & Geometry Engine (Hardware calibration, sensor size, active array)
    val cameraIntrinsics = remember(context) { ARProjectionEngine.getCameraIntrinsics(context) }
    var previewViewInstance by remember { mutableStateOf<PreviewView?>(null) }

    val displayRotationDegrees = remember(context) {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                context.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
            when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    // Camera & Location State
    var isCameraEnabled by remember { mutableStateOf(true) }
    var isGpsActive by remember { mutableStateOf(true) }
    var gpsAccuracyMeters by remember { mutableStateOf<Float?>(null) }
    var lastFixTimestampMs by remember { mutableStateOf<Long?>(null) }
    var isSensorActive by remember { mutableStateOf(true) }
    var cameraStreaming by remember { mutableStateOf(false) }
    var cameraBindFailed by remember { mutableStateOf(false) }
    var userToggledCameraOff by remember { mutableStateOf(false) }
    var arViewportSize by remember { mutableStateOf(IntSize.Zero) }
    val cameraPrefs = remember { context.getSharedPreferences("ar_camera_permission_prefs", Context.MODE_PRIVATE) }
    var cameraPermissionAsked by remember { mutableStateOf(cameraPrefs.getBoolean("camera_permission_asked", false)) }
    var cameraBannerDismissed by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    fun isCameraPermanentlyDenied(): Boolean {
        val activityContext = context as? Activity ?: return false
        return cameraPermissionAsked &&
                !hasCameraPermission &&
                !activityContext.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        cameraPermissionAsked = true
        cameraPrefs.edit().putBoolean("camera_permission_asked", true).apply()
        hasCameraPermission = isGranted
        if (isGranted) {
            isCameraEnabled = true
            userToggledCameraOff = false
            cameraBannerDismissed = false
        } else {
            cameraStreaming = false
        }
    }

    LaunchedEffect(hasCameraPermission, isCameraEnabled) {
        if (!hasCameraPermission || !isCameraEnabled) {
            cameraStreaming = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                hasCameraPermission = cameraGranted
                if (!cameraGranted) cameraStreaming = false
                hasLocationPermission =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val isGranted = fine || coarse
        hasLocationPermission = isGranted
        if (isGranted) {
            isGpsActive = true
        }
    }

    // Live GPS Listener for maximum precision and real sky alignment
    DisposableEffect(isGpsActive, hasLocationPermission) {
        if (isGpsActive && hasLocationPermission) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val lat = location.latitude
                    val lon = location.longitude
                    val alt = location.altitude
                    gpsAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
                    lastFixTimestampMs = if (location.time > 0L) location.time else System.currentTimeMillis()

                    viewModel.setLocation(
                        cityEn = "Live GPS",
                        cityFa = "GPS زنده",
                        lat = lat,
                        lon = lon,
                        elevationMeters = alt
                    )

                    // Pass exact location + altitude to orientation provider for magnetic declination correction
                    orientationProvider.updateLocation(lat, lon, alt)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    lastFixTimestampMs = null
                }
            }

            try {
                // Last known location for instant startup
                val lastGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                lastGps?.let { loc ->
                    gpsAccuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null
                    lastFixTimestampMs = if (loc.time > 0L) loc.time else System.currentTimeMillis()
                    viewModel.setLocation("Live GPS", "GPS زنده", loc.latitude, loc.longitude, loc.altitude)
                    orientationProvider.updateLocation(loc.latitude, loc.longitude, loc.altitude)
                }

                if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        1.0f,
                        locationListener
                    )
                } else if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000L,
                        1.0f,
                        locationListener
                    )
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            onDispose {
                try {
                    locationManager?.removeUpdates(locationListener)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            onDispose { }
        }
    }

    // Manual Drag Fallback when sensors paused
    var manualAzimuthOffset by remember { mutableStateOf(0.0) }
    var manualPitchOffset by remember { mutableStateOf(0.0) }

    val currentAzimuth = if (isSensorActive) skyOrientation.azimuth.toDouble() else manualAzimuthOffset
    val currentAltitude = if (isSensorActive) skyOrientation.pitch.toDouble() else manualPitchOffset

    DisposableEffect(uiState.userLocation, isSensorActive) {
        orientationProvider.updateLocation(
            latitude = uiState.userLocation.latitude,
            longitude = uiState.userLocation.longitude
        )
        if (isSensorActive) {
            orientationProvider.start()
        } else {
            orientationProvider.stop()
        }
        onDispose {
            orientationProvider.stop()
        }
    }

    // Time Machine State & Ticking Handlers
    val timeMachineState = uiState.timeMachineState
    val coroutineScope = rememberCoroutineScope()

    // Smooth playback ticking loop when simulation mode is active and playing
    LaunchedEffect(timeMachineState.mode, timeMachineState.isPlaying, timeMachineState.speed, timeMachineState.isReverse) {
        if (timeMachineState.mode == TimeMachineMode.SIMULATION && timeMachineState.isPlaying) {
            var lastFrameNanos = 0L
            var currentSimMs = timeMachineState.simulationTimeMs
            while (true) {
                withFrameNanos { frameTimeNanos ->
                    if (lastFrameNanos == 0L) {
                        lastFrameNanos = frameTimeNanos
                    } else {
                        val deltaRealSec = (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
                        lastFrameNanos = frameTimeNanos

                        val multiplier = timeMachineState.speed.multiplier
                        val direction = if (timeMachineState.isReverse) -1.0 else 1.0
                        val deltaSimMs = (deltaRealSec * multiplier * direction * 1000.0).toLong()

                        if (deltaSimMs != 0L) {
                            currentSimMs = (currentSimMs + deltaSimMs).coerceIn(
                                TimeMachineState.MIN_TIMESTAMP_MS,
                                TimeMachineState.MAX_TIMESTAMP_MS
                            )
                            viewModel.setSimulatedTime(currentSimMs, timeMachineState.eventName, timeMachineState.isBirthdayMode)
                        }
                    }
                }
            }
        }
    }

    // Ticking loop when in LIVE mode to keep real-time sky moving
    var liveTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timeMachineState.mode) {
        if (timeMachineState.mode == TimeMachineMode.LIVE) {
            while (true) {
                liveTimeMs = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }

    // Coroutine for smooth ~1s animated transition back to LIVE mode
    val handleReturnToLive = {
        coroutineScope.launch {
            val startSimMs = timeMachineState.simulationTimeMs
            val targetLiveMs = System.currentTimeMillis()
            val animDurationMs = 1000L
            val startTime = System.currentTimeMillis()

            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= animDurationMs) {
                    viewModel.returnToLiveTime()
                    break
                }
                val fraction = elapsed.toFloat() / animDurationMs.toFloat()
                val easedFraction = (1.0 - cos(fraction * PI)) / 2.0
                val currentSim = (startSimMs + (targetLiveMs - startSimMs) * easedFraction).toLong()
                viewModel.setSimulatedTime(currentSim, timeMachineState.eventName, timeMachineState.isBirthdayMode)
                delay(16L)
            }
        }
    }

    val activeTimeMs = if (timeMachineState.mode == TimeMachineMode.LIVE) liveTimeMs else timeMachineState.simulationTimeMs

    // Astronomy Calculations
    val jd = remember(activeTimeMs) { TimeEngine.getJulianDate(activeTimeMs) }
    val lastDeg = remember(uiState.userLocation, jd) {
        TimeEngine.getLAST(jd, uiState.userLocation.longitude)
    }

    val sunHoriz = remember(jd, uiState.userLocation) {
        SunEngine.getSunAltAz(jd, uiState.userLocation.latitude, uiState.userLocation.longitude)
    }

    val moonData = remember(jd, uiState.userLocation) {
        MoonEngine.calculateMoon(
            jd = jd,
            latitude = uiState.userLocation.latitude,
            longitude = uiState.userLocation.longitude,
            elevationM = uiState.userLocation.elevationMeters
        )
    }
    val moonHoriz = remember(moonData) {
        CoordinateEngine.Horizontal(
            azimuthDeg = moonData.azimuthDeg,
            altitudeDeg = moonData.altitudeDeg
        )
    }

    // Live TLE background fetch for ISS orbital precision
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ISSEngine.fetchLatestTLE()
        }
    }

    // Real-Time Satellite Topocentric Positions for all catalog satellites
    val satellitePositions = remember(activeTimeMs, uiState.userLocation) {
        SatelliteEngine.calculateAllSatellitePositions(
            timestampMs = activeTimeMs,
            userLatDeg = uiState.userLocation.latitude,
            userLonDeg = uiState.userLocation.longitude,
            userAltMeters = uiState.userLocation.elevationMeters
        )
    }

    // Single AR-facing view of the catalogue. Everything downstream of this val — the canvas render
    // loop, the tap hit-test, the off-screen edge indicators and label clustering — reads the sky
    // through it, so the AR-scoped exclusions (Earth) apply to all of them at once.
    val allCatalog = remember(jd) { ARSkyCatalog.arSkyObjects(AstronomyCatalog.getAllObjects(jd)) }

    val gpsFixAgeMs = lastFixTimestampMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
    val gpsHealth = remember(isGpsActive, hasLocationPermission, gpsAccuracyMeters, gpsFixAgeMs) {
        val acc = gpsAccuracyMeters
        val age = gpsFixAgeMs
        when {
            !isGpsActive || !hasLocationPermission || acc == null || age == null -> ArSubsystemHealth(
                ArHealthLevel.RED,
                "GPS unavailable",
                "GPS در دسترس نیست",
                "No live location fix",
                "موقعیت زنده دریافت نشده است"
            )
            acc < 10f && age < 30_000L -> ArSubsystemHealth(
                ArHealthLevel.GREEN,
                "GPS precise",
                "GPS دقیق",
                "${String.format(Locale.US, "%.0f", acc)} m • ${age / 1000}s ago",
                "${TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.0f", acc))} متر • ${TimeEngine.formatPersianNumbers("${age / 1000}")} ثانیه پیش"
            )
            acc <= 50f && age <= 120_000L -> ArSubsystemHealth(
                ArHealthLevel.AMBER,
                "GPS reduced accuracy",
                "دقت GPS متوسط",
                "${String.format(Locale.US, "%.0f", acc)} m • ${age / 1000}s ago",
                "${TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.0f", acc))} متر • ${TimeEngine.formatPersianNumbers("${age / 1000}")} ثانیه پیش"
            )
            else -> ArSubsystemHealth(
                ArHealthLevel.RED,
                "GPS signal weak",
                "سیگنال GPS ضعیف",
                "${String.format(Locale.US, "%.0f", acc)} m • stale ${age / 1000}s",
                "${TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.0f", acc))} متر • کهنه ${TimeEngine.formatPersianNumbers("${age / 1000}")} ثانیه"
            )
        }
    }

    val sensorHealth = remember(isSensorActive, calibrationState) {
        if (!isSensorActive) {
            ArSubsystemHealth(ArHealthLevel.GRAY, "Sensors off", "حسگرها خاموش", "Manual pointing mode", "حالت نشانه‌روی دستی")
        } else {
            when (calibrationState) {
                CalibrationState.EXCELLENT, CalibrationState.GOOD -> ArSubsystemHealth(ArHealthLevel.GREEN, "Sensors calibrated", "حسگرها کالیبره", calibrationState.name, calibrationState.name)
                CalibrationState.POOR -> ArSubsystemHealth(ArHealthLevel.AMBER, "Sensor accuracy low", "دقت حسگر متوسط", calibrationState.name, calibrationState.name)
                CalibrationState.NEEDS_CALIBRATION, CalibrationState.UNCALIBRATED -> ArSubsystemHealth(ArHealthLevel.RED, "Sensors need calibration", "حسگرها نیازمند کالیبراسیون", calibrationState.name, calibrationState.name)
            }
        }
    }

    val cameraHealth = remember(hasCameraPermission, isCameraEnabled, cameraStreaming, cameraBindFailed, userToggledCameraOff) {
        when {
            userToggledCameraOff && !isCameraEnabled -> ArSubsystemHealth(ArHealthLevel.GRAY, "Camera off", "دوربین خاموش", "Catalogue sky on black", "آسمان دسته‌اجرام روی زمینه سیاه")
            !hasCameraPermission -> ArSubsystemHealth(ArHealthLevel.RED, "Camera permission denied", "مجوز دوربین رد شده", "Showing catalogue sky on black", "نمایش آسمان دسته‌اجرام روی زمینه سیاه")
            cameraBindFailed -> ArSubsystemHealth(ArHealthLevel.RED, "Camera failed", "خطای دوربین", "CameraX binding failed", "اتصال CameraX ناموفق بود")
            cameraStreaming -> ArSubsystemHealth(ArHealthLevel.GREEN, "Camera streaming", "دوربین فعال", "Live preview active", "نمای زنده فعال است")
            else -> ArSubsystemHealth(ArHealthLevel.AMBER, "Camera starting", "دوربین در حال راه‌اندازی", "Waiting for preview stream", "در انتظار جریان تصویر")
        }
    }

    val overallHealth = remember(gpsHealth, sensorHealth, cameraHealth) {
        val level = worstHealthLevel(listOf(gpsHealth.level, sensorHealth.level, cameraHealth.level))
        val problematic = listOf(gpsHealth, sensorHealth, cameraHealth).filter { it.level == level && level != ArHealthLevel.GREEN }
        val en = if (problematic.isEmpty()) "All systems operational" else problematic.joinToString(" • ") { it.titleEn }
        val fa = if (problematic.isEmpty()) "تمام سامانه‌ها آماده‌اند" else problematic.joinToString(" • ") { it.titleFa }
        ArSubsystemHealth(level, en, fa, en, fa)
    }

    val recalibrationDecision = remember(arCalibrationOffsets, uiState.userLocation, lastFixTimestampMs) {
        ARCalibrationManager.shouldSuggestRecalibration(
            currentTimeMs = System.currentTimeMillis(),
            currentLatitude = uiState.userLocation.latitude,
            currentLongitude = uiState.userLocation.longitude
        )
    }

    // Guided 1-Point Reference Alignment: dynamic bright-target recommendation.
    // Recomputed whenever time/location change; default selection = brightest target.
    val alignmentTargets = remember(jd, uiState.userLocation) {
        ARCalibrationManager.computeAlignmentTargets(
            jd = jd,
            latitude = uiState.userLocation.latitude,
            longitude = uiState.userLocation.longitude,
            elevationM = uiState.userLocation.elevationMeters
        )
    }
    var selectedAlignmentTargetId by remember { mutableStateOf<String?>(null) }
    val selectedAlignmentTarget = remember(alignmentTargets, selectedAlignmentTargetId) {
        alignmentTargets.firstOrNull { it.id == selectedAlignmentTargetId }
            ?: alignmentTargets.firstOrNull()
    }
    var alignmentArrowsVisible by remember { mutableStateOf(false) }
    val alignmentGuidance = remember(selectedAlignmentTarget, currentAzimuth, currentAltitude, alignmentArrowsVisible) {
        selectedAlignmentTarget?.let { target ->
            ARCalibrationManager.computeGuidance(
                targetAzimuthDeg = target.azimuthDeg,
                targetAltitudeDeg = target.altitudeDeg,
                phoneAzimuthDeg = currentAzimuth,
                phoneAltitudeDeg = currentAltitude,
                arrowsCurrentlyVisible = alignmentArrowsVisible
            )
        }
    }
    // Sync arrow-visibility hysteresis state (converges immediately, no loop)
    LaunchedEffect(alignmentGuidance?.arrowsVisible) {
        alignmentGuidance?.let { alignmentArrowsVisible = it.arrowsVisible }
    }

    // Short haptic tick for alignment confirmation
    fun triggerAlignmentHaptic(milliseconds: Long = 120L) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(milliseconds)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Search and Finder State
    var searchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var selectedTarget by remember { mutableStateOf<CelestialObject?>(null) }
    var showArrivalDialog by remember { mutableStateOf(false) }
    var hasVibratedForArrival by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.selectedTargetObject) {
        uiState.selectedTargetObject?.let { target ->
            // A "locate in AR" hand-off from another screen must not reintroduce an object that the
            // AR sky deliberately does not render (Earth is the observer, not a sky object).
            if (!ARSkyCatalog.isExcludedFromArSky(target)) {
                selectedTarget = target
                hasVibratedForArrival = false
            }
        }
    }

    val searchResults = remember(searchQuery, uiState.userLocation, jd) {
        if (searchQuery.isNotBlank()) {
            CelestialSearchEngine.search(
                query = searchQuery,
                userLat = uiState.userLocation.latitude,
                userLon = uiState.userLocation.longitude,
                jd = jd
            )
        } else {
            emptyList()
        }
    }

    // Finder Guidance Calculations
    val finderData = remember(selectedTarget, currentAzimuth, currentAltitude, uiState.userLocation, jd) {
        selectedTarget?.let { target ->
            FinderEngine.calculateFinderData(
                target = target,
                phoneAzimuthDeg = currentAzimuth,
                phoneAltitudeDeg = currentAltitude,
                userLat = uiState.userLocation.latitude,
                userLon = uiState.userLocation.longitude,
                userAltMeters = uiState.userLocation.elevationMeters,
                jd = jd
            )
        }
    }

    // Handle Arrival Celebration & Vibration
    LaunchedEffect(finderData?.isArrived) {
        if (finderData?.isArrived == true && !hasVibratedForArrival) {
            hasVibratedForArrival = true
            showArrivalDialog = true

            // Trigger Vibration
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vibratorManager?.defaultVibrator?.vibrate(
                        VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(300)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (finderData?.isArrived == false) {
            hasVibratedForArrival = false
        }
    }

    // Focus Mode Smart Panel State
    var activeExpandedPanel by remember { mutableStateOf<ArExpandedPanel?>(null) }

    // AR Object Visibility Filters Persistence
    val prefs = remember { context.getSharedPreferences("ar_filters_prefs", Context.MODE_PRIVATE) }
    var filterStars by remember { mutableStateOf(prefs.getBoolean("filter_stars", true)) }
    var filterConstellations by remember { mutableStateOf(prefs.getBoolean("filter_constellations", true)) }
    var filterPlanets by remember { mutableStateOf(prefs.getBoolean("filter_planets", true)) }
    var filterMoons by remember { mutableStateOf(prefs.getBoolean("filter_moons", true)) }
    var filterSun by remember { mutableStateOf(prefs.getBoolean("filter_sun", true)) }
    var filterDeepSky by remember { mutableStateOf(prefs.getBoolean("filter_deepsky", true)) }
    var filterSatellites by remember { mutableStateOf(prefs.getBoolean("filter_satellites", true)) }
    var filterMeteorShowers by remember { mutableStateOf(prefs.getBoolean("filter_meteor_showers", true)) }
    var filterObjectNames by remember { mutableStateOf(prefs.getBoolean("filter_object_names", false)) }

    fun updateFilter(key: String, value: Boolean, setter: (Boolean) -> Unit) {
        setter(value)
        prefs.edit().putBoolean(key, value).apply()
    }

    fun setAllFilters(value: Boolean) {
        filterStars = value
        filterConstellations = value
        filterPlanets = value
        filterMoons = value
        filterSun = value
        filterDeepSky = value
        filterSatellites = value
        filterMeteorShowers = value
        filterObjectNames = value
        prefs.edit()
            .putBoolean("filter_stars", value)
            .putBoolean("filter_constellations", value)
            .putBoolean("filter_planets", value)
            .putBoolean("filter_moons", value)
            .putBoolean("filter_sun", value)
            .putBoolean("filter_deepsky", value)
            .putBoolean("filter_satellites", value)
            .putBoolean("filter_meteor_showers", value)
            .putBoolean("filter_object_names", value)
            .apply()
    }

    // AR Sky Zoom State
    var zoomFactor by remember { mutableFloatStateOf(1.0f) }
    var showZoomIndicator by remember { mutableStateOf(false) }

    // Dot → shape tier memory, keyed by object id. The hysteresis band in
    // CelestialObjectSizes.resolveArRenderPlan needs the previous tier to decide which side of the
    // band a borderline object stays on. A plain map rather than snapshot state on purpose: it is
    // written and consumed inside the draw pass, so it never schedules a recomposition by itself,
    // while the zoom gesture that changed a tier already recomposes the canvas.
    val arTierMemory = remember { mutableMapOf<String, Boolean>() }

    LaunchedEffect(zoomFactor) {
        if (zoomFactor != 1.0f) {
            showZoomIndicator = true
            delay(1500L)
            showZoomIndicator = false
        }
    }

    // Dynamic state wrappers for gesture detector inside pointerInput(Unit)
    val curAzimuthState by rememberUpdatedState(currentAzimuth)
    val curAltitudeState by rememberUpdatedState(currentAltitude)
    val zoomFactorState by rememberUpdatedState(zoomFactor)
    val skyOrientationState by rememberUpdatedState(skyOrientation)
    val currentDensity = LocalDensity.current.density
    val densityState by rememberUpdatedState(currentDensity)
    val filterStarsState by rememberUpdatedState(filterStars)
    val filterSunState by rememberUpdatedState(filterSun)
    val filterMoonsState by rememberUpdatedState(filterMoons)
    val filterPlanetsState by rememberUpdatedState(filterPlanets)
    val filterSatellitesState by rememberUpdatedState(filterSatellites)
    val filterDeepSkyState by rememberUpdatedState(filterDeepSky)
    val filterMeteorShowersState by rememberUpdatedState(filterMeteorShowers)
    val filterObjectNamesState by rememberUpdatedState(filterObjectNames)
    val sunHorizState by rememberUpdatedState(sunHoriz)
    val moonHorizState by rememberUpdatedState(moonHoriz)
    val satellitePositionsState by rememberUpdatedState(satellitePositions)
    val lastDegState by rememberUpdatedState(lastDeg)
    val userLatState by rememberUpdatedState(uiState.userLocation.latitude)
    val allCatalogState by rememberUpdatedState(allCatalog)
    val isSensorActiveState by rememberUpdatedState(isSensorActive)
    val isAlignmentModeState by rememberUpdatedState(isAlignmentMode)

    // AR Info Card Object State & Auto-Dismiss Timer
    var tappedObject by remember { mutableStateOf<CelestialObject?>(null) }
    var expandedLabelCluster by remember { mutableStateOf<List<CelestialObject>>(emptyList()) }

    LaunchedEffect(tappedObject) {
        if (tappedObject != null) {
            delay(6000L)
            tappedObject = null
        }
    }

    // Active Orbit Object (Target or tapped object)
    val activeOrbitObject = selectedTarget ?: tappedObject

    // Real-Time Trajectory Points for activeOrbitObject (hidden by default, shown when target or tap card active)
    val (orbitPastPoints, orbitFuturePoints) = remember(
        activeOrbitObject?.id,
        activeTimeMs,
        uiState.userLocation
    ) {
        val targetObj = activeOrbitObject ?: return@remember Pair(emptyList<CoordinateEngine.Horizontal>(), emptyList<CoordinateEngine.Horizontal>())
        val pastList = mutableListOf<CoordinateEngine.Horizontal>()
        val futureList = mutableListOf<CoordinateEngine.Horizontal>()

        if (targetObj.type == ObjectType.SATELLITE || targetObj.id.startsWith("sat_")) {
            val satItem = SatelliteEngine.resolveSatelliteItem(targetObj.id)
            if (satItem != null) {
                val (past, future) = SatelliteEngine.generateOrbitTrajectory(
                    satellite = satItem,
                    currentTimestampMs = activeTimeMs,
                    userLatDeg = uiState.userLocation.latitude,
                    userLonDeg = uiState.userLocation.longitude,
                    userAltMeters = uiState.userLocation.elevationMeters,
                    pastMinutes = 45,
                    futureMinutes = 45,
                    stepSeconds = 30
                )
                pastList.addAll(past)
                futureList.addAll(future)
            }
        } else if (targetObj.id == "moon") {
            val activeJd = TimeEngine.getJulianDate(activeTimeMs)
            for (step in -48..0) {
                val hJd = activeJd + (step * 0.25 / 24.0)
                val hLast = TimeEngine.getLAST(hJd, uiState.userLocation.longitude)
                val hMoon = MoonEngine.calculateMoon(hJd)
                val hHoriz = CoordinateEngine.equatorialToHorizontal(
                    CoordinateEngine.Equatorial(hMoon.raDeg, hMoon.decDeg),
                    hLast,
                    uiState.userLocation.latitude
                )
                pastList.add(hHoriz)
            }
            for (step in 0..48) {
                val hJd = activeJd + (step * 0.25 / 24.0)
                val hLast = TimeEngine.getLAST(hJd, uiState.userLocation.longitude)
                val hMoon = MoonEngine.calculateMoon(hJd)
                val hHoriz = CoordinateEngine.equatorialToHorizontal(
                    CoordinateEngine.Equatorial(hMoon.raDeg, hMoon.decDeg),
                    hLast,
                    uiState.userLocation.latitude
                )
                futureList.add(hHoriz)
            }
        } else if (targetObj.id.startsWith("galilean_moon_")) {
            val activeJd = TimeEngine.getJulianDate(activeTimeMs)
            for (step in -48..0) {
                val hJd = activeJd + (step * 0.25 / 24.0)
                val hLast = TimeEngine.getLAST(hJd, uiState.userLocation.longitude)
                val moons = SolarSystemCatalog.getGalileanMoons(hJd)
                val mObj = moons.firstOrNull { it.id == targetObj.id } ?: targetObj
                val hHoriz = CoordinateEngine.equatorialToHorizontal(
                    CoordinateEngine.Equatorial(mObj.raDeg, mObj.decDeg),
                    hLast,
                    uiState.userLocation.latitude
                )
                pastList.add(hHoriz)
            }
            for (step in 0..48) {
                val hJd = activeJd + (step * 0.25 / 24.0)
                val hLast = TimeEngine.getLAST(hJd, uiState.userLocation.longitude)
                val moons = SolarSystemCatalog.getGalileanMoons(hJd)
                val mObj = moons.firstOrNull { it.id == targetObj.id } ?: targetObj
                val hHoriz = CoordinateEngine.equatorialToHorizontal(
                    CoordinateEngine.Equatorial(mObj.raDeg, mObj.decDeg),
                    hLast,
                    uiState.userLocation.latitude
                )
                futureList.add(hHoriz)
            }
        } else if (targetObj.type == ObjectType.SUN) {
            val activeJd = TimeEngine.getJulianDate(activeTimeMs)
            for (step in -48..0) {
                val hJd = activeJd + (step * 0.25 / 24.0)
                val hHoriz = SunEngine.getSunAltAz(hJd, uiState.userLocation.latitude, uiState.userLocation.longitude)
                pastList.add(hHoriz)
            }
            for (step in 0..48) {
                val hJd = activeJd + (step * 0.25 / 24.0)
                val hHoriz = SunEngine.getSunAltAz(hJd, uiState.userLocation.latitude, uiState.userLocation.longitude)
                futureList.add(hHoriz)
            }
        } else if (targetObj.type != ObjectType.REFERENCE_POINT) {
            val activeJd = TimeEngine.getJulianDate(activeTimeMs)
            for (step in -48..0) {
                val hJd = activeJd + (step * 0.25 / 24.0)
                val hLast = TimeEngine.getLAST(hJd, uiState.userLocation.longitude)
                val hHoriz = CoordinateEngine.equatorialToHorizontal(
                    CoordinateEngine.Equatorial(targetObj.raDeg, targetObj.decDeg),
                    hLast,
                    uiState.userLocation.latitude
                )
                pastList.add(hHoriz)
            }
            for (step in 0..48) {
                val hJd = activeJd + (step * 0.25 / 24.0)
                val hLast = TimeEngine.getLAST(hJd, uiState.userLocation.longitude)
                val hMoon = CoordinateEngine.equatorialToHorizontal(
                    CoordinateEngine.Equatorial(targetObj.raDeg, targetObj.decDeg),
                    hLast,
                    uiState.userLocation.latitude
                )
                futureList.add(hMoon)
            }
        }

        Pair(pastList, futureList)
    }

    // Auto-Hide AR UI State & 5-second Inactivity Timer
    var isControlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val resetControlsTimer = remember {
        {
            lastInteractionTimeMs = System.currentTimeMillis()
            if (!isControlsVisible) {
                isControlsVisible = true
            }
        }
    }

    LaunchedEffect(lastInteractionTimeMs, activeExpandedPanel, isSearchFocused, searchQuery, selectedTarget, tappedObject, isAlignmentMode) {
        if (activeExpandedPanel != null || isSearchFocused || searchQuery.isNotEmpty() || tappedObject != null || isAlignmentMode) {
            isControlsVisible = true
            return@LaunchedEffect
        }
        delay(5000L)
        isControlsVisible = false
    }

    val controlsAlpha by animateFloatAsState(
        targetValue = if (isControlsVisible) 1.0f else 0.0f,
        animationSpec = tween(durationMillis = 400),
        label = "ControlsAlpha"
    )

    val edgeIndicators = remember(
        arViewportSize,
        allCatalog,
        currentAzimuth,
        currentAltitude,
        jd,
        uiState.userLocation,
        filterStars,
        filterSun,
        filterMoons,
        filterPlanets,
        filterSatellites,
        filterDeepSky,
        filterMeteorShowers,
        selectedTarget,
        zoomFactor,
        isControlsVisible,
        isAlignmentMode
    ) {
        val width = arViewportSize.width.toFloat()
        val height = arViewportSize.height.toFloat()
        if (width <= 0f || height <= 0f || !isControlsVisible || isAlignmentMode) {
            emptyList()
        } else {
            val fovX = ARProjectionEngine.computeEffectiveFovXDeg(
                screenWidthPx = width,
                screenHeightPx = height,
                intrinsics = cameraIntrinsics,
                zoomFactor = zoomFactor
            ).coerceAtLeast(1.0)
            val fovY = (fovX * height / width).coerceAtLeast(1.0)
            val margin = 28f * currentDensity
            val centerX = width / 2f
            val centerY = height / 2f
            allCatalog.mapNotNull { obj ->
                if (selectedTarget?.id == obj.id) return@mapNotNull null
                if (!isVisibleByArFilter(obj, filterStars, filterSun, filterMoons, filterPlanets, filterSatellites, filterDeepSky, filterMeteorShowers)) return@mapNotNull null
                val interesting = when {
                    obj.type == ObjectType.SUN || obj.type == ObjectType.MOON || obj.type == ObjectType.PLANET || obj.type == ObjectType.DWARF_PLANET -> true
                    obj.type == ObjectType.SATELLITE -> obj.id == "sat_25544" || obj.id.contains("iss", ignoreCase = true) || obj.nameEn.contains("ISS", ignoreCase = true)
                    obj.type == ObjectType.STAR -> obj.magnitude <= 2.0
                    isDeepSkyType(obj.type) -> obj.magnitude <= 5.0
                    else -> false
                }
                if (!interesting) return@mapNotNull null
                val horiz = if (obj.type == ObjectType.SUN) {
                    sunHoriz
                } else if (obj.id == "moon") {
                    moonHoriz
                } else if (obj.type == ObjectType.SATELLITE || obj.id.startsWith("sat_")) {
                    satellitePositions[obj.id]
                        ?: satellitePositions[com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog.resolveCanonicalId(obj.id)]
                        ?: return@mapNotNull null
                } else {
                    CoordinateEngine.equatorialToHorizontal(
                        CoordinateEngine.Equatorial(obj.raDeg, obj.decDeg),
                        lastDeg,
                        uiState.userLocation.latitude
                    )
                }
                if (horiz.altitudeDeg <= 0.0) return@mapNotNull null
                val dAz = normalizeDeltaDegrees(horiz.azimuthDeg - currentAzimuth)
                val dAlt = horiz.altitudeDeg - currentAltitude
                val effectiveDAz = dAz * cos(Math.toRadians(horiz.altitudeDeg))
                val separation = hypot(effectiveDAz, dAlt)
                val insideFov = abs(effectiveDAz) <= fovX / 2.0 && abs(dAlt) <= fovY / 2.0
                if (separation <= 0.01 || separation > 90.0 || insideFov) return@mapNotNull null

                val vx = effectiveDAz.toFloat()
                val vy = (-dAlt).toFloat()
                val tX = if (vx > 0f) (centerX - margin) / vx else if (vx < 0f) -(centerX - margin) / vx else Float.POSITIVE_INFINITY
                val tY = if (vy > 0f) (centerY - margin) / vy else if (vy < 0f) -(centerY - margin) / vy else Float.POSITIVE_INFINITY
                val t = min(tX, tY).takeIf { it.isFinite() && it > 0f } ?: 1f
                val x = (centerX + vx * t).coerceIn(margin, width - margin)
                val y = (centerY + vy * t).coerceIn(margin, height - margin)
                val angle = Math.toDegrees(atan2(vy.toDouble(), vx.toDouble())).toFloat() + 90f
                val priority = when {
                    obj.type == ObjectType.SUN || obj.type == ObjectType.MOON -> 0.0
                    obj.type == ObjectType.PLANET || obj.type == ObjectType.DWARF_PLANET -> 1.0 + obj.magnitude
                    obj.type == ObjectType.SATELLITE -> 2.0
                    obj.type == ObjectType.STAR -> 10.0 + obj.magnitude
                    else -> 20.0 + obj.magnitude
                }
                ArEdgeIndicator(obj, x, y, angle, priority)
            }.sortedBy { it.priority }.take(8)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Solid black when the camera is not showing a live feed: no procedural/starfield
            // fallback layer, the catalogued objects drawn on this Canvas are the whole sky.
            .background(Color.Black)
            .onSizeChanged { arViewportSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1.0f) {
                        zoomFactor = (zoomFactor * zoom).coerceIn(0.75f, 4.0f)
                    }
                }
            }
            .pointerInput(isSensorActive) {
                if (!isSensorActive) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        manualAzimuthOffset = (manualAzimuthOffset - dragAmount.x * 0.2) % 360.0
                        if (manualAzimuthOffset < 0) manualAzimuthOffset += 360.0
                        manualPitchOffset = (manualPitchOffset + dragAmount.y * 0.2).coerceIn(-90.0, 90.0)
                    }
                }
            }
            .testTag("compass_ar_screen")
    ) {
        // Layer 1: Live Camera Feed
        if (hasCameraPermission && isCameraEnabled) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    previewViewInstance = previewView
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                            cameraBindFailed = false
                            cameraStreaming = true
                        } catch (e: Exception) {
                            cameraBindFailed = true
                            cameraStreaming = false
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Layer 2: AR Celestial Overlay Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            resetControlsTimer()
                            tryAwaitRelease()
                        },
                        onTap = alignmentTap@{ touchOffset ->
                            resetControlsTimer()
                            // In AlignmentMode the sky is hidden: taps must not select objects
                            if (isAlignmentModeState) return@alignmentTap
                            if (activeExpandedPanel != null) {
                                activeExpandedPanel = null
                            }

                            val activeAzimuth = curAzimuthState
                            val activeAltitude = curAltitudeState
                            val activeZoom = zoomFactorState
                            val activeOrientation = skyOrientationState
                            val activeDensity = densityState
                            val activeCatalog = allCatalogState
                            val activeSunHoriz = sunHorizState
                            val activeMoonHoriz = moonHorizState
                            val activeSatellitePositions = satellitePositionsState
                            val activeLastDeg = lastDegState
                            val activeUserLat = userLatState
                            val activeIsSensor = isSensorActiveState
                            val activeFilterObjectNames = filterObjectNamesState

                            val canvasWidth = size.width.toFloat()
                            val canvasHeight = size.height.toFloat()

                            val fovForTap = ARProjectionEngine.computeEffectiveFovXDeg(
                                screenWidthPx = canvasWidth,
                                screenHeightPx = canvasHeight,
                                intrinsics = cameraIntrinsics,
                                zoomFactor = activeZoom
                            ).coerceAtLeast(1.0)
                            val pixelsPerDegreeForTap = canvasWidth / fovForTap

                            // Fixed tap radius, independent of how big the object is drawn: a
                            // dot-tier object is exactly as tappable as a resolved one.
                            var bestMatch: CelestialObject? = null
                            var bestDistPx = 70.0f * activeDensity
                            val tapRenderItems = mutableListOf<ArVisibleRenderItem>()

                            for (obj in activeCatalog) {
                                val isVisibleByFilter = isVisibleByArFilter(
                                    obj = obj,
                                    filterStars = filterStarsState,
                                    filterSun = filterSunState,
                                    filterMoons = filterMoonsState,
                                    filterPlanets = filterPlanetsState,
                                    filterSatellites = filterSatellitesState,
                                    filterDeepSky = filterDeepSkyState,
                                    filterMeteorShowers = filterMeteorShowersState
                                )
                                if (!isVisibleByFilter) continue

                                val horiz = if (obj.type == ObjectType.SUN) activeSunHoriz
                                else if (obj.id == "moon") activeMoonHoriz
                                else if (obj.type == ObjectType.SATELLITE || obj.id.startsWith("sat_")) activeSatellitePositions[obj.id] ?: activeSatellitePositions[com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog.resolveCanonicalId(obj.id)] ?: CoordinateEngine.Horizontal(-90.0, 0.0)
                                else CoordinateEngine.equatorialToHorizontal(CoordinateEngine.Equatorial(obj.raDeg, obj.decDeg), activeLastDeg, activeUserLat)

                                val pt = ARProjectionEngine.projectAltAz(
                                    azimuthDeg = horiz.azimuthDeg,
                                    altitudeDeg = horiz.altitudeDeg,
                                    rotationMatrix = if (activeIsSensor) activeOrientation.rotationMatrix else null,
                                    currentAzimuth = activeAzimuth,
                                    currentAltitude = activeAltitude,
                                    currentRoll = activeOrientation.roll.toDouble(),
                                    canvasWidth = canvasWidth,
                                    canvasHeight = canvasHeight,
                                    intrinsics = cameraIntrinsics,
                                    zoomFactor = activeZoom,
                                    sensorToViewMatrix = previewViewInstance?.sensorToViewTransform,
                                    displayRotationDegrees = displayRotationDegrees
                                ) ?: continue

                                if (pt.x in -150f..(canvasWidth + 150f) && pt.y in -150f..(canvasHeight + 150f)) {
                                    // Same shared tier rule as the draw pass (read-only here: the
                                    // render pass owns the hysteresis memory).
                                    val tapDeltaAz = normalizeDeltaDegrees(horiz.azimuthDeg - activeAzimuth)
                                    val tapProximityScale = CelestialObjectSizes.calculateProximityScale(
                                        deltaAzDeg = tapDeltaAz.toFloat(),
                                        deltaAltDeg = (horiz.altitudeDeg - activeAltitude).toFloat()
                                    )
                                    val tapPlan = CelestialObjectSizes.resolveArRenderPlan(
                                        obj = obj,
                                        zoomFactor = activeZoom,
                                        proximityScale = tapProximityScale,
                                        density = activeDensity,
                                        dpPerDegree = (pixelsPerDegreeForTap / activeDensity).toFloat(),
                                        previouslyShapeTier = arTierMemory[obj.id] == true
                                    )
                                    tapRenderItems.add(
                                        ArVisibleRenderItem(obj, horiz, pt.x, pt.y, tapPlan)
                                    )
                                }

                                val dist = hypot(
                                    (touchOffset.x - pt.x).toDouble(),
                                    (touchOffset.y - pt.y).toDouble()
                                ).toFloat()
                                if (dist < bestDistPx) {
                                    bestDistPx = dist
                                    bestMatch = obj
                                }
                            }

                            val tapClusters = buildLabelClusters(
                                labelItems = tapRenderItems.filter { item ->
                                    shouldShowArLabel(
                                        obj = item.obj,
                                        isSelected = item.obj.id == selectedTarget?.id,
                                        isAimed = false,
                                        filterObjectNames = activeFilterObjectNames,
                                        labelEligible = item.plan.labelEligible
                                    )
                                },
                                minDistancePx = max(24f * activeDensity, pixelsPerDegreeForTap.toFloat() * 0.35f),
                                maxClusterRadiusPx = max(24f * activeDensity, pixelsPerDegreeForTap.toFloat() * 0.35f) * 1.4f
                            )
                            val tappedCluster = tapClusters.firstOrNull { cluster ->
                                hypot(
                                    (touchOffset.x - cluster.centerX).toDouble(),
                                    (touchOffset.y - cluster.centerY).toDouble()
                                ) <= (36f * activeDensity).toDouble()
                            }
                            if (tappedCluster != null) {
                                expandedLabelCluster = tappedCluster.objects
                                tappedObject = null
                            } else {
                                tappedObject = bestMatch
                            }
                        }
                    )
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            if (canvasWidth <= 0f || canvasHeight <= 0f) return@Canvas

            val centerX = canvasWidth / 2f
            val centerY = canvasHeight / 2f

            val fovX = ARProjectionEngine.computeEffectiveFovXDeg(
                screenWidthPx = canvasWidth,
                screenHeightPx = canvasHeight,
                intrinsics = cameraIntrinsics,
                zoomFactor = zoomFactor
            ).coerceAtLeast(1.0)
            val pixelsPerDegree = canvasWidth / fovX
            // Used by label decluttering below to keep cluster thresholds tied to angular zoom.
            // No synthetic starfield here: with the camera off (toggled, permission denied, or bind
            // failure on a device without a usable camera) the Box background behind this Canvas is
            // solid black, and the real catalogued objects projected below are the entire sky.

            // Guided 1-Point Alignment: clear sightline — hide ALL projected sky
            // graphics (horizon, orbits, grids, objects, labels, constellation lines)
            // so the user sees only the raw camera feed + alignment reticle/HUD.
            if (isAlignmentMode) return@Canvas

            // Draw Horizon Line
            val horizonPath = Path()
            var horizonFirst = true
            val horizonSteps = 24
            val horizonSpan = fovX * 2.0
            for (i in 0..horizonSteps) {
                val hAz = currentAzimuth - (horizonSpan / 2.0) + (i * horizonSpan / horizonSteps)
                val pt = ARProjectionEngine.projectAltAz(
                    azimuthDeg = (hAz % 360.0 + 360.0) % 360.0,
                    altitudeDeg = 0.0,
                    rotationMatrix = if (isSensorActive) skyOrientation.rotationMatrix else null,
                    currentAzimuth = currentAzimuth,
                    currentAltitude = currentAltitude,
                    currentRoll = skyOrientation.roll.toDouble(),
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    intrinsics = cameraIntrinsics,
                    zoomFactor = zoomFactor,
                    sensorToViewMatrix = previewViewInstance?.sensorToViewTransform,
                    displayRotationDegrees = displayRotationDegrees
                ) ?: continue
                if (horizonFirst) {
                    horizonPath.moveTo(pt.x, pt.y)
                    horizonFirst = false
                } else {
                    horizonPath.lineTo(pt.x, pt.y)
                }
            }
            if (!horizonFirst) {
                drawPath(
                    path = horizonPath,
                    color = AccentPrimary.copy(alpha = 0.6f),
                    style = Stroke(width = 2.5f)
                )
                val horizonCenterPt = ARProjectionEngine.projectAltAz(
                    azimuthDeg = currentAzimuth,
                    altitudeDeg = 0.0,
                    rotationMatrix = if (isSensorActive) skyOrientation.rotationMatrix else null,
                    currentAzimuth = currentAzimuth,
                    currentAltitude = currentAltitude,
                    currentRoll = skyOrientation.roll.toDouble(),
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    intrinsics = cameraIntrinsics,
                    zoomFactor = zoomFactor,
                    sensorToViewMatrix = previewViewInstance?.sensorToViewTransform,
                    displayRotationDegrees = displayRotationDegrees
                )
                if (horizonCenterPt != null && horizonCenterPt.x in 0f..canvasWidth && horizonCenterPt.y in 0f..canvasHeight) {
                    val horizonLabel = if (isFa) "افق (0°)" else "Horizon (0°)"
                    val horizonTextLayout = textMeasurer.measure(
                        text = horizonLabel,
                        style = TextStyle(fontFamily = canvasFontFamily, color = AccentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        softWrap = false
                    )
                    val drawX = 20f
                    val drawY = horizonCenterPt.y - 30f
                    if (drawX.isFinite() && drawY.isFinite() && drawY in 0f..canvasHeight) {
                        drawText(
                            textLayoutResult = horizonTextLayout,
                            topLeft = Offset(drawX, drawY)
                        )
                    }
                }
            }

            // Render Orbit Trajectory Line ONLY when an object is active (Target or Tap Card)
            if (activeOrbitObject != null) {
                // Solid line = orbital portion behind the object (past)
                if (orbitPastPoints.size >= 2) {
                    val pastPath = Path()
                    var pastFirst = true
                    for (pt in orbitPastPoints) {
                        val projPt = ARProjectionEngine.projectAltAz(
                            azimuthDeg = pt.azimuthDeg,
                            altitudeDeg = pt.altitudeDeg,
                            rotationMatrix = if (isSensorActive) skyOrientation.rotationMatrix else null,
                            currentAzimuth = currentAzimuth,
                            currentAltitude = currentAltitude,
                            currentRoll = skyOrientation.roll.toDouble(),
                            canvasWidth = canvasWidth,
                            canvasHeight = canvasHeight,
                            intrinsics = cameraIntrinsics,
                            zoomFactor = zoomFactor,
                            sensorToViewMatrix = previewViewInstance?.sensorToViewTransform,
                            displayRotationDegrees = displayRotationDegrees
                        ) ?: continue

                        if (projPt.x in -600f..(canvasWidth + 600f) && projPt.y in -600f..(canvasHeight + 600f)) {
                            if (pastFirst) {
                                pastPath.moveTo(projPt.x, projPt.y)
                                pastFirst = false
                            } else {
                                pastPath.lineTo(projPt.x, projPt.y)
                            }
                        }
                    }
                    if (!pastFirst) {
                        drawPath(
                            path = pastPath,
                            color = Color(0xFF38BDF8).copy(alpha = 0.85f),
                            style = Stroke(width = 2.5f * density)
                        )
                    }
                }

                // Dotted line = orbital portion in front of the object / yet to be travelled (future)
                if (orbitFuturePoints.size >= 2) {
                    val futurePath = Path()
                    var futureFirst = true
                    for (pt in orbitFuturePoints) {
                        val projPt = ARProjectionEngine.projectAltAz(
                            azimuthDeg = pt.azimuthDeg,
                            altitudeDeg = pt.altitudeDeg,
                            rotationMatrix = if (isSensorActive) skyOrientation.rotationMatrix else null,
                            currentAzimuth = currentAzimuth,
                            currentAltitude = currentAltitude,
                            currentRoll = skyOrientation.roll.toDouble(),
                            canvasWidth = canvasWidth,
                            canvasHeight = canvasHeight,
                            intrinsics = cameraIntrinsics,
                            zoomFactor = zoomFactor,
                            sensorToViewMatrix = previewViewInstance?.sensorToViewTransform,
                            displayRotationDegrees = displayRotationDegrees
                        ) ?: continue

                        if (projPt.x in -600f..(canvasWidth + 600f) && projPt.y in -600f..(canvasHeight + 600f)) {
                            if (futureFirst) {
                                futurePath.moveTo(projPt.x, projPt.y)
                                futureFirst = false
                            } else {
                                futurePath.lineTo(projPt.x, projPt.y)
                            }
                        }
                    }
                    if (!futureFirst) {
                        drawPath(
                            path = futurePath,
                            color = Color(0xFF38BDF8).copy(alpha = 0.85f),
                            style = Stroke(
                                width = 2.5f * density,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
                            )
                        )
                    }
                }
            }

            // Render Galactic Equator Plane / Constellation lines (if Constellations filter enabled)
            if (filterConstellations) {
                val galPoints = GalacticEngine.calculateGalacticPlanePoints(
                    jd = jd,
                    userLatDeg = uiState.userLocation.latitude,
                    userLonDeg = uiState.userLocation.longitude
                )
                val galPath = Path()
                var galFirst = true
                for (gp in galPoints) {
                    val projPt = ARProjectionEngine.projectAltAz(
                        azimuthDeg = gp.azimuthDeg,
                        altitudeDeg = gp.altitudeDeg,
                        rotationMatrix = if (isSensorActive) skyOrientation.rotationMatrix else null,
                        currentAzimuth = currentAzimuth,
                        currentAltitude = currentAltitude,
                        currentRoll = skyOrientation.roll.toDouble(),
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        intrinsics = cameraIntrinsics,
                        zoomFactor = zoomFactor,
                        sensorToViewMatrix = previewViewInstance?.sensorToViewTransform,
                        displayRotationDegrees = displayRotationDegrees
                    ) ?: continue

                    if (galFirst) {
                        galPath.moveTo(projPt.x, projPt.y)
                        galFirst = false
                    } else {
                        galPath.lineTo(projPt.x, projPt.y)
                    }
                }
                if (!galFirst) {
                    drawPath(
                        path = galPath,
                        color = Color(0xFFC084FC).copy(alpha = 0.4f),
                        style = Stroke(width = 2.5f)
                    )
                }
            }

            // 2. Project All Visible Catalog Objects & Identify Aimed Object at Center Reticle
            val visibleRenderItems = mutableListOf<ArVisibleRenderItem>()
            var closestReticleObj: CelestialObject? = null
            var minReticleDist = 70f * density

            for (obj in allCatalog) {
                val isVisibleByFilter = isVisibleByArFilter(
                    obj = obj,
                    filterStars = filterStars,
                    filterSun = filterSun,
                    filterMoons = filterMoons,
                    filterPlanets = filterPlanets,
                    filterSatellites = filterSatellites,
                    filterDeepSky = filterDeepSky,
                    filterMeteorShowers = filterMeteorShowers
                )
                if (!isVisibleByFilter) continue

                val horiz = if (obj.type == ObjectType.SUN) {
                    sunHoriz
                } else if (obj.id == "moon") {
                    moonHoriz
                } else if (obj.type == ObjectType.SATELLITE || obj.id.startsWith("sat_")) {
                    satellitePositions[obj.id] ?: satellitePositions[com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog.resolveCanonicalId(obj.id)] ?: CoordinateEngine.Horizontal(-90.0, 0.0)
                } else {
                    CoordinateEngine.equatorialToHorizontal(
                        CoordinateEngine.Equatorial(obj.raDeg, obj.decDeg),
                        lastDeg,
                        uiState.userLocation.latitude
                    )
                }

                val projPt = ARProjectionEngine.projectAltAz(
                    azimuthDeg = horiz.azimuthDeg,
                    altitudeDeg = horiz.altitudeDeg,
                    rotationMatrix = if (isSensorActive) skyOrientation.rotationMatrix else null,
                    currentAzimuth = currentAzimuth,
                    currentAltitude = currentAltitude,
                    currentRoll = skyOrientation.roll.toDouble(),
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    intrinsics = cameraIntrinsics,
                    zoomFactor = zoomFactor,
                    sensorToViewMatrix = previewViewInstance?.sensorToViewTransform,
                    displayRotationDegrees = displayRotationDegrees
                ) ?: continue

                val px = projPt.x
                val py = projPt.y

                // Padding boundary check
                if (px in -150f..(canvasWidth + 150f) && py in -150f..(canvasHeight + 150f)) {
                    val distToCenter = hypot(
                        (px - centerX).toDouble(),
                        (py - centerY).toDouble()
                    ).toFloat()
                    if (distToCenter < minReticleDist) {
                        minReticleDist = distToCenter
                        closestReticleObj = obj
                    }

                    val deltaAzDeg = normalizeDeltaDegrees(horiz.azimuthDeg - currentAzimuth).toFloat()
                    val deltaAltDeg = (horiz.altitudeDeg - currentAltitude).toFloat()
                    // Centre-proximity magnification, shared by the tier decision and the glyph size.
                    val proximityScale = CelestialObjectSizes.calculateProximityScale(
                        deltaAzDeg = deltaAzDeg,
                        deltaAltDeg = deltaAltDeg
                    )
                    val previousTierWasShape = arTierMemory[obj.id] == true
                    val plan = CelestialObjectSizes.resolveArRenderPlan(
                        obj = obj,
                        zoomFactor = zoomFactor,
                        proximityScale = proximityScale,
                        density = density,
                        dpPerDegree = (pixelsPerDegree / density).toFloat(),
                        previouslyShapeTier = previousTierWasShape
                    )
                    // Memory for next frame's hysteresis band; never pops within one frame because
                    // the plan above is drawn from immediately.
                    if (previousTierWasShape != (plan.tier == CelestialObjectSizes.ArRenderTier.SHAPE)) {
                        arTierMemory[obj.id] = plan.tier == CelestialObjectSizes.ArRenderTier.SHAPE
                    }

                    visibleRenderItems.add(ArVisibleRenderItem(obj, horiz, px, py, plan))
                }
            }

            val labelClusterCandidates = visibleRenderItems.filter { item ->
                val selected = item.obj.id == selectedTarget?.id || (selectedTarget != null && com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog.resolveCanonicalId(item.obj.id) == com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog.resolveCanonicalId(selectedTarget?.id ?: ""))
                val aimed = closestReticleObj != null && item.obj.id == closestReticleObj.id
                shouldShowArLabel(item.obj, selected, aimed, filterObjectNames, item.plan.labelEligible) && !selected && !aimed
            }
            val declutterDistancePx = max(24f * density, pixelsPerDegree.toFloat() * 0.35f)
            val labelClusters = buildLabelClusters(
                labelItems = labelClusterCandidates,
                minDistancePx = declutterDistancePx,
                maxClusterRadiusPx = declutterDistancePx * 1.4f
            )
            val clusteredObjectIds = labelClusters.flatMap { it.objects.map { obj -> obj.id } }.toSet()

            for (item in visibleRenderItems) {
                val obj = item.obj
                val px = item.px
                val py = item.py

                val isSelected = obj.id == selectedTarget?.id || (selectedTarget != null && com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog.resolveCanonicalId(obj.id) == com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog.resolveCanonicalId(selectedTarget?.id ?: ""))
                val isAimed = (closestReticleObj != null && obj.id == closestReticleObj.id)

                val plan = item.plan

                // Dot → shape: the object is drawn at the plan's interpolated radius — the dot size
                // at the start of the transition, its full type-glyph size once resolved — while the
                // point of light is cross-faded out underneath it. Both are driven by the same
                // centre-proximity magnification the glyph always used, so they cannot disagree.
                val radiusPx = plan.drawnRadiusPx

                if (plan.dotAlpha > 0f) {
                    drawCircle(
                        color = arDotColor(obj),
                        radius = plan.dotRadiusPx,
                        center = Offset(px, py),
                        alpha = plan.dotAlpha
                    )
                }

                // A pure dot (blend == 0) draws no glyph at all; "faint objects render as points".
                if (plan.blend > 0f) when (obj.type) {
                    ObjectType.SUN -> {
                        // Solar Corona & Disk
                        drawCircle(
                            color = Color(0xFFFF8800).copy(alpha = 0.2f),
                            radius = radiusPx * 2.2f,
                            center = Offset(px, py)
                        )
                        drawCircle(
                            color = Color(0xFFFFCC00).copy(alpha = 0.45f),
                            radius = radiusPx * 1.5f,
                            center = Offset(px, py)
                        )
                        drawCircle(
                            color = Color(0xFFFFFBEB),
                            radius = radiusPx,
                            center = Offset(px, py)
                        )
                    }
                    ObjectType.MOON -> {
                        if (obj.id == "moon") {
                            // Lunar Halo & Phase-aware Disc
                            drawCircle(
                                color = Color(0xFFE2E8F0).copy(alpha = 0.25f),
                                radius = radiusPx * 1.7f,
                                center = Offset(px, py)
                            )
                            drawCircle(
                                color = Color(0xFFF1FAEE),
                                radius = radiusPx,
                                center = Offset(px, py)
                            )
                            val illumFrac = (moonData.illuminationPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
                            if (illumFrac < 0.98f) {
                                val limbScreenAngleDeg = ARProjectionEngine.calculateMoonLimbScreenAngleDeg(
                                    moonAzimuthDeg = moonHoriz.azimuthDeg,
                                    moonAltitudeDeg = moonHoriz.altitudeDeg,
                                    sunAzimuthDeg = sunHoriz.azimuthDeg,
                                    sunAltitudeDeg = sunHoriz.altitudeDeg,
                                    rotationMatrix = if (isSensorActive) skyOrientation.rotationMatrix else null,
                                    currentAzimuth = currentAzimuth,
                                    currentAltitude = currentAltitude,
                                    currentRoll = skyOrientation.roll.toDouble()
                                ).toFloat()

                                val shadowPath = Path()
                                // Outer shadow arc around left edge (+90° bottom to -90° top)
                                shadowPath.addArc(
                                    Rect(px - radiusPx, py - radiusPx, px + radiusPx, py + radiusPx),
                                    90f,
                                    180f
                                )
                                val k = (2.0 * illumFrac - 1.0).toFloat()
                                val stepInnerWidth = (abs(k) * radiusPx).coerceAtLeast(0f)
                                val innerRect = Rect(px - stepInnerWidth, py - radiusPx, px + stepInnerWidth, py + radiusPx)

                                // Inner terminator arc from -90° top to +90° bottom
                                val innerSweep = if (k >= 0) -180f else 180f
                                shadowPath.arcTo(innerRect, 270f, innerSweep, false)
                                shadowPath.close()

                                rotate(limbScreenAngleDeg, Offset(px, py)) {
                                    drawPath(
                                        path = shadowPath,
                                        color = Color(0xFF0F172A).copy(alpha = 0.85f)
                                    )
                                }
                            }
                        } else {
                            // Jovian Satellite / Galilean Moon Disk
                            drawCircle(
                                color = Color(0xFF818CF8).copy(alpha = 0.35f),
                                radius = radiusPx * 1.6f,
                                center = Offset(px, py)
                            )
                            drawCircle(
                                color = Color(0xFFC7D2FE),
                                radius = radiusPx,
                                center = Offset(px, py)
                            )
                        }
                    }
                    ObjectType.PLANET, ObjectType.DWARF_PLANET -> {
                        if (obj.id == "planet_jupiter") {
                            // Jupiter: Cream disk with 2 horizontal bands
                            drawCircle(
                                color = Color(0xFFE2D1A6),
                                radius = radiusPx,
                                center = Offset(px, py)
                            )
                            drawLine(
                                color = Color(0xFFC0703B),
                                start = Offset(px - radiusPx * 0.8f, py - radiusPx * 0.35f),
                                end = Offset(px + radiusPx * 0.8f, py - radiusPx * 0.35f),
                                strokeWidth = 2.5f * density
                            )
                            drawLine(
                                color = Color(0xFFC0703B),
                                start = Offset(px - radiusPx * 0.8f, py + radiusPx * 0.35f),
                                end = Offset(px + radiusPx * 0.8f, py + radiusPx * 0.35f),
                                strokeWidth = 2.5f * density
                            )
                        } else if (obj.id == "planet_saturn") {
                            // Saturn: Golden disk + tiny ring system
                            drawCircle(
                                color = Color(0xFFFDE047),
                                radius = radiusPx,
                                center = Offset(px, py)
                            )
                            drawOval(
                                color = Color(0xFFFACC15).copy(alpha = 0.85f),
                                topLeft = Offset(px - radiusPx * 2.2f, py - radiusPx * 0.7f),
                                size = Size(radiusPx * 4.4f, radiusPx * 1.4f),
                                style = Stroke(width = 2.5f * density)
                            )
                        } else if (obj.id == "planet_mars") {
                            // Mars: Warm reddish-orange disk
                            drawCircle(
                                color = Color(0xFFEF4444).copy(alpha = 0.3f),
                                radius = radiusPx * 1.8f,
                                center = Offset(px, py)
                            )
                            drawCircle(
                                color = Color(0xFFEF4444),
                                radius = radiusPx,
                                center = Offset(px, py)
                            )
                        } else if (obj.id == "planet_venus") {
                            // Venus: Brilliant golden-white disk
                            drawCircle(
                                color = Color(0xFFFEF08A).copy(alpha = 0.4f),
                                radius = radiusPx * 2.0f,
                                center = Offset(px, py)
                            )
                            drawCircle(
                                color = Color(0xFFFEF08A),
                                radius = radiusPx,
                                center = Offset(px, py)
                            )
                        } else if (obj.id == "planet_uranus" || obj.id == "planet_neptune") {
                            // Blue-green disks
                            drawCircle(
                                color = Color(0xFF38BDF8),
                                radius = radiusPx,
                                center = Offset(px, py)
                            )
                        } else {
                            // Slate / default planet
                            drawCircle(
                                color = Color(0xFFFFD166).copy(alpha = 0.35f),
                                radius = radiusPx * 1.8f,
                                center = Offset(px, py)
                            )
                            drawCircle(
                                color = Color(0xFFFFD166),
                                radius = radiusPx,
                                center = Offset(px, py)
                            )
                        }
                    }
                    ObjectType.SATELLITE -> {
                        // Satellite minimal glyph: center body + 2 solar panel wings
                        drawRect(
                            color = Color(0xFF38BDF8),
                            topLeft = Offset(px - radiusPx * 2.0f, py - radiusPx * 0.4f),
                            size = Size(radiusPx * 4.0f, radiusPx * 0.8f)
                        )
                        drawRect(
                            color = Color(0xFFF59E0B),
                            topLeft = Offset(px - radiusPx * 0.6f, py - radiusPx * 0.6f),
                            size = Size(radiusPx * 1.2f, radiusPx * 1.2f)
                        )
                    }
                    ObjectType.DEEP_SKY, ObjectType.GALAXY, ObjectType.NEBULA,
                    ObjectType.STAR_CLUSTER, ObjectType.GLOBULAR_CLUSTER, ObjectType.BLACK_HOLE -> {
                        if (obj.type == ObjectType.GALAXY || obj.id.contains("m31") || obj.category.contains("Galaxy", ignoreCase = true)) {
                            // Galaxy: tiny elliptical glow + bright core
                            drawOval(
                                color = Color(0xFFC084FC).copy(alpha = 0.45f),
                                topLeft = Offset(px - radiusPx * 1.8f, py - radiusPx * 0.9f),
                                size = Size(radiusPx * 3.6f, radiusPx * 1.8f)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = radiusPx * 0.4f,
                                center = Offset(px, py)
                            )
                        } else if (obj.type == ObjectType.NEBULA || obj.category.contains("Nebula", ignoreCase = true)) {
                            // Nebula: faint diffuse cloud
                            drawCircle(
                                color = Color(0xFFF472B6).copy(alpha = 0.35f),
                                radius = radiusPx * 1.8f,
                                center = Offset(px, py)
                            )
                            drawCircle(
                                color = Color(0xFF38BDF8).copy(alpha = 0.3f),
                                radius = radiusPx * 1.1f,
                                center = Offset(px - radiusPx * 0.3f, py + radiusPx * 0.2f)
                            )
                        } else if (obj.type == ObjectType.GLOBULAR_CLUSTER) {
                            // Globular cluster: compact soft glow
                            drawCircle(
                                color = Color(0xFFE2E8F0).copy(alpha = 0.5f),
                                radius = radiusPx * 1.4f,
                                center = Offset(px, py)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = radiusPx * 0.3f,
                                center = Offset(px, py)
                            )
                        } else {
                            // Star cluster
                            drawCircle(
                                color = Color(0xFFC77DFF).copy(alpha = 0.4f),
                                radius = radiusPx * 1.6f,
                                center = Offset(px, py)
                            )
                            drawCircle(
                                color = Color(0xFFE0AAFF),
                                radius = radiusPx,
                                center = Offset(px, py)
                            )
                        }
                    }
                    else -> {
                        // Star (and every other point-like glyph). The radius comes from the shared
                        // tier plan, which is the old (4 - magnitude) curve once the object is
                        // resolved and the dot size while it is not.
                        val starColor = if (obj.magnitude <= 0.5) Color(0xFFA5F3FC) else if (obj.magnitude <= 2.5) Color(0xFFE2E8F0) else Color(0xFF94A3B8)
                        drawCircle(
                            color = starColor,
                            radius = radiusPx,
                            center = Offset(px, py)
                        )
                        // Soft 4-point diffraction cross for brightest stars (mag <= 0.5)
                        if (obj.magnitude <= 0.5) {
                            val spikeLen = radiusPx * 2.5f
                            drawLine(
                                color = Color.White.copy(alpha = 0.35f),
                                start = Offset(px - spikeLen, py),
                                end = Offset(px + spikeLen, py),
                                strokeWidth = 1.5f
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.35f),
                                start = Offset(px, py - spikeLen),
                                end = Offset(px, py + spikeLen),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                }

                // Selected target ring or Aimed reticle highlight
                if (isSelected) {
                    drawCircle(
                        color = AccentPrimary,
                        radius = radiusPx + 14f,
                        center = Offset(px, py),
                        style = Stroke(width = 2.5f)
                    )
                } else if (isAimed && !filterObjectNames) {
                    drawCircle(
                        color = AccentPrimary.copy(alpha = 0.85f),
                        radius = radiusPx + 10f,
                        center = Offset(px, py),
                        style = Stroke(width = 1.8f)
                    )
                }

                // Label with dark backing pill. Ambient labels require shape tier; a tapped or
                // aimed-at object keeps its existing label/info-card behaviour at any tier.
                val shouldShowLabel = shouldShowArLabel(obj, isSelected, isAimed, filterObjectNames, plan.labelEligible) && obj.id !in clusteredObjectIds

                if (shouldShowLabel) {
                    val labelText = if (isFa) obj.nameFa else obj.nameEn
                    val textLayout = textMeasurer.measure(
                        text = labelText,
                        style = TextStyle(
                            fontFamily = canvasFontFamily,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        softWrap = false
                    )

                    val pillWidth = textLayout.size.width + 20f
                    val pillHeight = textLayout.size.height + 10f
                    val pillLeft = px - pillWidth / 2f
                    val pillTop = py + radiusPx + 8f

                    if (
                        pillLeft.isFinite() &&
                        pillTop.isFinite() &&
                        pillLeft < canvasWidth &&
                        pillTop < canvasHeight &&
                        pillLeft + pillWidth > 0f &&
                        pillTop + pillHeight > 0f
                    ) {
                        drawRoundRect(
                            color = Color.Black.copy(alpha = if (isAimed || isSelected) 0.85f else 0.65f),
                            topLeft = Offset(pillLeft, pillTop),
                            size = Size(pillWidth, pillHeight),
                            cornerRadius = CornerRadius(12f, 12f)
                        )
                        if (isAimed || isSelected) {
                            drawRoundRect(
                                color = AccentPrimary,
                                topLeft = Offset(pillLeft, pillTop),
                                size = Size(pillWidth, pillHeight),
                                cornerRadius = CornerRadius(12f, 12f),
                                style = Stroke(width = 1.5f)
                            )
                        }

                        val textDrawX = pillLeft + 10f
                        val textDrawY = pillTop + 5f
                        if (
                            textDrawX.isFinite() &&
                            textDrawY.isFinite() &&
                            textDrawX < canvasWidth &&
                            textDrawY < canvasHeight &&
                            textDrawX + textLayout.size.width > 0f &&
                            textDrawY + textLayout.size.height > 0f
                        ) {
                            drawText(
                                textLayoutResult = textLayout,
                                topLeft = Offset(textDrawX, textDrawY)
                            )
                        }
                    }
                }
            }

            for (cluster in labelClusters) {
                val labelText = if (isFa) {
                    "${TimeEngine.formatPersianNumbers(cluster.objects.size.toString())} جرم"
                } else {
                    "${cluster.objects.size} objects"
                }
                val textLayout = textMeasurer.measure(
                    text = labelText,
                    style = TextStyle(
                        fontFamily = canvasFontFamily,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    softWrap = false
                )
                val pillWidth = textLayout.size.width + 22f
                val pillHeight = textLayout.size.height + 12f
                val pillLeft = (cluster.centerX - pillWidth / 2f).coerceIn(4f, canvasWidth - pillWidth - 4f)
                val pillTop = (cluster.centerY - pillHeight / 2f).coerceIn(4f, canvasHeight - pillHeight - 4f)
                drawRoundRect(
                    color = AccentPrimary.copy(alpha = 0.82f),
                    topLeft = Offset(pillLeft, pillTop),
                    size = Size(pillWidth, pillHeight),
                    cornerRadius = CornerRadius(14f, 14f)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.4f),
                    topLeft = Offset(pillLeft, pillTop),
                    size = Size(pillWidth, pillHeight),
                    cornerRadius = CornerRadius(14f, 14f),
                    style = Stroke(width = 1.2f)
                )
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(pillLeft + 11f, pillTop + 6f)
                )
            }

            // 3. Center Crosshair Aiming Reticle Frame
            drawCircle(
                color = AccentPrimary,
                radius = 65f,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.5f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.35f),
                radius = 110f,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1.5f)
            )

            // Tick marks
            drawLine(
                color = Color.White,
                start = Offset(centerX - 85f, centerY),
                end = Offset(centerX - 50f, centerY),
                strokeWidth = 2f
            )
            drawLine(
                color = Color.White,
                start = Offset(centerX + 50f, centerY),
                end = Offset(centerX + 85f, centerY),
                strokeWidth = 2f
            )
            drawLine(
                color = Color.White,
                start = Offset(centerX, centerY - 85f),
                end = Offset(centerX, centerY - 50f),
                strokeWidth = 2f
            )
            drawLine(
                color = Color.White,
                start = Offset(centerX, centerY + 50f),
                end = Offset(centerX, centerY + 85f),
                strokeWidth = 2f
            )
        }

        // No GPS status is overlaid on the canvas any more: locator accuracy / staleness belongs to
        // the Sensors & Telemetry page (the sensors pill), where the user can read it in context.

        if (isCameraPermanentlyDenied() && !userToggledCameraOff && !cameraBannerDismissed && !isAlignmentMode) {
            PermissionBlockedBanner(
                text = stringResource(R.string.ar_camera_unavailable_banner),
                actionText = stringResource(R.string.ar_open_settings),
                onDismiss = { cameraBannerDismissed = true },
                onOpenSettings = {
                    val uri = Uri.fromParts("package", context.packageName, null)
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 136.dp, start = 16.dp, end = 16.dp)
            )
        }

        if (magneticInterferenceDetected && !isAlignmentMode) {
            MagneticInterferenceBanner(
                text = stringResource(R.string.ar_magnetic_interference),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (isCameraPermanentlyDenied() && !cameraBannerDismissed) 206.dp else 136.dp, start = 16.dp, end = 16.dp)
            )
        }

        if (recalibrationDecision.shouldPrompt && !recalibrationPromptDismissedThisSession && !isAlignmentMode) {
            RecalibrationSuggestionBanner(
                title = stringResource(R.string.ar_recalibration_title),
                body = stringResource(R.string.ar_recalibration_body),
                action = stringResource(R.string.ar_recalibrate_action),
                onDismiss = { recalibrationPromptDismissedThisSession = true },
                onAction = {
                    recalibrationPromptDismissedThisSession = true
                    isAlignmentMode = true
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 176.dp, start = 16.dp, end = 16.dp)
            )
        }

        // Off-screen mini-radar indicators for nearby notable objects outside the current FOV.
        if (edgeIndicators.isNotEmpty() && !isAlignmentMode) {
            edgeIndicators.forEach { indicator ->
                EdgeIndicatorChip(
                    indicator = indicator,
                    isFa = isFa,
                    onClick = {
                        selectedTarget = indicator.obj
                        hasVibratedForArrival = false
                        resetControlsTimer()
                    }
                )
            }
        }

        // Auto-Hide Restoration Pill
        AnimatedVisibility(
            visible = !isControlsVisible && !isAlignmentMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp)
        ) {
            LiquidGlassSurface(
                onClick = { resetControlsTimer() },
                shape = CircleShape,
                style = LiquidGlassDefaults.Pill,
                fallbackColor = Color.Black.copy(alpha = 0.75f),
                fallbackBorder = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(16.dp))
                    Text(
                        text = if (isFa) "لمس کنید تا ابزارها ظاهر شوند" else "Tap screen to show controls",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }

        // Layer 3: Arrow-Guided Finder Overlay (When an object is selected, remains visible during auto-hide)
        (if (isAlignmentMode) null else finderData)?.let { finder ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(260.dp)
            ) {
                // Direction Arrow Canvas
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .rotate((finder.arrowAngleRad * 180f / PI.toFloat()))
                ) {
                    val w = size.width
                    val h = size.height
                    val cX = w / 2f
                    val cY = h / 2f

                    // Outer guidance progress ring
                    drawCircle(
                        color = AccentPrimary.copy(alpha = 0.25f),
                        radius = 110f,
                        center = Offset(cX, cY),
                        style = Stroke(width = 6f)
                    )
                    drawArc(
                        color = AccentPrimary,
                        startAngle = -90f,
                        sweepAngle = finder.proximityFraction * 360f,
                        useCenter = false,
                        topLeft = Offset(cX - 110f, cY - 110f),
                        size = Size(220f, 220f),
                        style = Stroke(width = 8f)
                    )

                    // Pointing Arrow Path
                    val arrowPath = Path().apply {
                        moveTo(cX, cY - 95f)
                        lineTo(cX + 20f, cY - 65f)
                        lineTo(cX + 8f, cY - 68f)
                        lineTo(cX + 8f, cY - 45f)
                        lineTo(cX - 8f, cY - 45f)
                        lineTo(cX - 8f, cY - 68f)
                        lineTo(cX - 20f, cY - 65f)
                        close()
                    }
                    drawPath(path = arrowPath, color = AccentPrimary)
                }

                // Instruction & Distance Card floating below arrow
                LiquidGlassSurface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 60.dp),
                    shape = RoundedCornerShape(RedCornerRadius.window),
                    style = LiquidGlassDefaults.Card,
                    fallbackColor = RedTheme.colors.surfaceElevated.copy(alpha = 0.95f),
                    fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isFa) finder.instructionFa else finder.instructionEn,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = RedTheme.colors.textPrimary
                        )
                        val distStr = String.format("%.1f°", finder.totalAngularDistanceDeg)
                        Text(
                            text = if (isFa) "فاصله تا هدف: ${TimeEngine.formatPersianNumbers(distStr)}" else "Distance: $distStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = RedTheme.colors.accentRed
                        )
                    }
                }
            }
        }

        // Layer 3.5: Glass Floating Cancel Target Button (Visible whenever target is active at bottom-start, safely above bottom nav bar)
        if (selectedTarget != null && !isAlignmentMode) {
            LiquidGlassSurface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, bottom = 88.dp)
                    .testTag("ar_cancel_target_button"),
                onClick = {
                    selectedTarget = null
                    viewModel.clearTargetObject()
                },
                shape = RoundedCornerShape(RedCornerRadius.window),
                style = LiquidGlassDefaults.Pill,
                fallbackColor = Color(0xCC1A1F36),
                fallbackBorder = BorderStroke(1.dp, Color(0x88FF5252)),
                fallbackShadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = if (isFa) "انصراف از ردیابی" else "Cancel Target",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isFa) "انصراف از ردیابی" else "Cancel Target",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        if (timeMachineState.mode == TimeMachineMode.SIMULATION && !isAlignmentMode) {
            TimeMachineWatermark(
                label = stringResource(R.string.ar_time_machine_label),
                dateTime = TimeEngine.formatDateTime24h(
                    activeTimeMs,
                    uiState.calendarSystem,
                    isFa,
                    java.util.TimeZone.getTimeZone(uiState.userLocation.timezoneId)
                ),
                modifier = Modifier
                    .align(if (selectedTarget == null) Alignment.BottomStart else Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (selectedTarget == null) 88.dp else 128.dp
                    )
            )
        }

        // Layer 4: Floating Top Header Pill & Focus Mode Smart Pills
        // Hidden during Guided 1-Point Alignment (clear sightline, no touch interception).
        if (!isAlignmentMode) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = RedSpacing.xs, start = RedSpacing.md, end = RedSpacing.md)
                .fillMaxWidth()
                .graphicsLayer { alpha = controlsAlpha }
        ) {
            // FLOATING TOP HEADER PILL
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = RedSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Floating Pill: Back Button + AR Sky Title
                LiquidGlassSurface(
                    shape = CircleShape,
                    style = LiquidGlassDefaults.Pill,
                    fallbackColor = RedTheme.colors.surfaceElevated.copy(alpha = 0.88f),
                    fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border),
                    fallbackShadowElevation = RedElevation.floating
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.selectTab(4) },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("ar_back_button")
                        ) {
                            Icon(
                                imageVector = if (isFa) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = RedTheme.colors.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = if (isFa) "آسمان AR" else "AR Sky",
                            style = RedTypographyTokens.bodyPrimary.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            ),
                            color = RedTheme.colors.textPrimary,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                    }
                }

                // Right Floating Pill: Quick Tools (Night Vision + Calibrate)
                val isNightVision = uiState.skyCanvasTheme == SkyCanvasTheme.OBSERVATORY
                LiquidGlassSurface(
                    shape = CircleShape,
                    style = LiquidGlassDefaults.Pill,
                    fallbackColor = RedTheme.colors.surfaceElevated.copy(alpha = 0.88f),
                    fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border),
                    fallbackShadowElevation = RedElevation.floating
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                viewModel.setSkyCanvasTheme(
                                    if (isNightVision) SkyCanvasTheme.REAL_SKY else SkyCanvasTheme.OBSERVATORY
                                )
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NightsStay,
                                contentDescription = "Night Mode",
                                tint = if (isNightVision) RedTheme.colors.accentRed else RedTheme.colors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = { isAlignmentMode = true },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("open_alignment_mode_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.GpsFixed,
                                contentDescription = "Align",
                                tint = RedTheme.colors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // SMART FLOATING PILLS ROW
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pill 1: Search
                ArSmartPill(
                    icon = Icons.Default.Search,
                    label = if (isFa) "جستجو" else "Search",
                    isActive = activeExpandedPanel == ArExpandedPanel.SEARCH,
                    isHighlighted = selectedTarget != null,
                    testTag = "ar_pill_search",
                    onClick = {
                        activeExpandedPanel = if (activeExpandedPanel == ArExpandedPanel.SEARCH) null else ArExpandedPanel.SEARCH
                    }
                )

                // Pill 2: Time Machine
                ArSmartPill(
                    icon = Icons.Default.Schedule,
                    label = if (isFa) "زمان" else "Time",
                    isActive = activeExpandedPanel == ArExpandedPanel.TIME_MACHINE,
                    isHighlighted = timeMachineState.mode == TimeMachineMode.SIMULATION,
                    testTag = "ar_pill_time",
                    onClick = {
                        activeExpandedPanel = if (activeExpandedPanel == ArExpandedPanel.TIME_MACHINE) null else ArExpandedPanel.TIME_MACHINE
                    }
                )

                // Pill 3: Object Filters
                ArSmartPill(
                    icon = Icons.Default.FilterList,
                    label = if (isFa) "فیلترها" else "Filters",
                    isActive = activeExpandedPanel == ArExpandedPanel.FILTERS,
                    isHighlighted = !(filterStars && filterConstellations && filterPlanets && filterMoons && filterSun && filterDeepSky && filterSatellites && filterMeteorShowers && filterObjectNames),
                    testTag = "ar_pill_filters",
                    onClick = {
                        activeExpandedPanel = if (activeExpandedPanel == ArExpandedPanel.FILTERS) null else ArExpandedPanel.FILTERS
                    }
                )

                // Pill 4: Sensors / GPS
                ArSmartPill(
                    icon = Icons.Default.Sensors,
                    label = if (isFa) "حسگرها" else "Sensors",
                    isActive = activeExpandedPanel == ArExpandedPanel.SENSORS,
                    isHighlighted = isGpsActive && isSensorActive,
                    testTag = "ar_pill_sensors",
                    onClick = {
                        activeExpandedPanel = if (activeExpandedPanel == ArExpandedPanel.SENSORS) null else ArExpandedPanel.SENSORS
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // EXPANDED PANEL CONTAINER (Renders only ONE expanded panel at a time)
            AnimatedVisibility(
                visible = activeExpandedPanel != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                LiquidGlassSurface(
                    shape = RoundedCornerShape(RedCornerRadius.xl),
                    style = LiquidGlassDefaults.Card,
                    fallbackColor = RedTheme.colors.surfaceElevated.copy(alpha = 0.96f),
                    fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border),
                    fallbackShadowElevation = RedElevation.floating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (activeExpandedPanel) {
                        ArExpandedPanel.SEARCH -> {
                            // Search Panel Content
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = RedTheme.colors.accentRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextField(
                                        textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        placeholder = {
                                            Text(
                                                text = if (isFa) "جستجوی جرم (ماه، مریخ، شباهنگ...)" else "Search target (Moon, Mars, Sirius...)",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = RedTheme.colors.textSecondary
                                            )
                                        },
                                        singleLine = true,
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                            focusedTextColor = RedTheme.colors.textPrimary,
                                            unfocusedTextColor = RedTheme.colors.textPrimary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .onFocusChanged { isSearchFocused = it.isFocused }
                                            .testTag("ar_search_input")
                                    )
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                // Active Target Chip Banner
                                selectedTarget?.let { target ->
                                    HorizontalDivider(color = CardBorder, thickness = 1.dp, modifier = Modifier.padding(vertical = 6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.GpsFixed,
                                                contentDescription = "Target",
                                                tint = AccentPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = if (isFa) "هدف قفل‌شده: ${target.nameFa}" else "Locked Target: ${target.nameEn}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = AccentPrimary
                                            )
                                        }
                                        TextButton(
                                            onClick = { selectedTarget = null },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = if (isFa) "حذف هدف" else "Clear Target",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }

                                // Search Results or Quick Suggestions
                                if (searchResults.isNotEmpty()) {
                                    HorizontalDivider(color = RedTheme.colors.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp).fillMaxWidth()) {
                                        items(searchResults) { result ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("ar_search_result_${result.celestialObject.id}")
                                                    .clickable {
                                                        selectedTarget = result.celestialObject
                                                        searchQuery = ""
                                                        activeExpandedPanel = null // Collapse panel on target select
                                                    }
                                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        text = if (isFa) result.celestialObject.nameFa else result.celestialObject.nameEn,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = RedTheme.colors.textPrimary
                                                    )
                                                    Text(
                                                        text = if (isFa) result.celestialObject.constellationFa else result.celestialObject.constellationEn,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = RedTheme.colors.textSecondary
                                                    )
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (result.isVisibleNow) StatusGood.copy(alpha = 0.2f) else RedTheme.colors.surfaceVariant,
                                                    border = BorderStroke(1.dp, if (result.isVisibleNow) StatusGood else RedTheme.colors.border)
                                                ) {
                                                    Text(
                                                        text = if (isFa) result.statusFa else result.statusEn,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (result.isVisibleNow) StatusGood else RedTheme.colors.textSecondary,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    HorizontalDivider(color = RedTheme.colors.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                                    Text(
                                        text = if (isFa) "پیشنهادهای سریع:" else "Quick Suggestions:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = RedTheme.colors.textSecondary,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    val quickSuggestions = listOf(
                                        stringResource(R.string.ar_quick_moon),
                                        stringResource(R.string.ar_quick_sun),
                                        stringResource(R.string.ar_quick_mars),
                                        stringResource(R.string.ar_quick_venus),
                                        stringResource(R.string.ar_quick_jupiter),
                                        stringResource(R.string.ar_quick_saturn),
                                        stringResource(R.string.ar_quick_sirius),
                                        stringResource(R.string.ar_quick_andromeda),
                                        stringResource(R.string.ar_quick_orion),
                                        stringResource(R.string.ar_quick_iss)
                                    )
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(quickSuggestions) { sug ->
                                            SuggestionChip(
                                                onClick = { searchQuery = sug },
                                                label = { Text(sug, color = RedTheme.colors.textPrimary) },
                                                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = RedTheme.colors.surfaceVariant)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        ArExpandedPanel.TIME_MACHINE -> {
                            // Time Machine Controls Panel Content
                            TimeMachineControlBar(
                                state = timeMachineState,
                                isFa = isFa,
                                calendarSystem = uiState.calendarSystem,
                                userOccasions = uiState.userOccasions,
                                onSaveOccasion = { id, title, timestampMs, cb ->
                                    viewModel.saveUserOccasion(id, title, timestampMs, cb)
                                },
                                onDeleteOccasion = { id ->
                                    viewModel.deleteUserOccasion(id)
                                },
                                onSimulatedTimeChange = { timeMs, eventName, isBirthday ->
                                    viewModel.setSimulatedTime(timeMs, eventName, isBirthday)
                                },
                                onModeChange = { mode -> viewModel.setTimeMachineMode(mode) },
                                onTogglePlay = { viewModel.toggleTimeMachinePlaying() },
                                onSpeedChange = { speed -> viewModel.setTimeMachineSpeed(speed) },
                                onToggleReverse = { viewModel.toggleTimeMachineReverse() },
                                onToggleExpanded = { viewModel.toggleTimeMachineExpanded() },
                                onReturnToLive = { handleReturnToLive() }
                            )
                        }

                        ArExpandedPanel.FILTERS -> {
                            // Sky Object Visibility Filters Panel Content
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isFa) "فیلترهای نمایش اجرام آسمان" else "Sky Object Visibility",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = RedTheme.colors.textPrimary
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = { setAllFilters(true) },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(if (isFa) "نمایش همه" else "Show All", style = MaterialTheme.typography.labelSmall, color = RedTheme.colors.accentRed)
                                        }
                                        TextButton(
                                            onClick = { setAllFilters(false) },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(if (isFa) "پنهان همه" else "Hide All", style = MaterialTheme.typography.labelSmall, color = RedTheme.colors.textSecondary)
                                        }
                                    }
                                }

                                HorizontalDivider(color = RedTheme.colors.border, thickness = 0.5.dp)

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    item {
                                        FilterChip(
                                            selected = filterObjectNames,
                                            onClick = { updateFilter("filter_object_names", !filterObjectNames) { filterObjectNames = it } },
                                            label = { Text(if (isFa) "🏷 نام اجرام" else "🏷 Object Names") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = filterStars,
                                            onClick = { updateFilter("filter_stars", !filterStars) { filterStars = it } },
                                            label = { Text(if (isFa) "⭐ ستارگان" else "⭐ Stars") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = filterConstellations,
                                            onClick = { updateFilter("filter_constellations", !filterConstellations) { filterConstellations = it } },
                                            label = { Text(if (isFa) "✨ صور فلکی" else "✨ Constellations") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = filterPlanets,
                                            onClick = { updateFilter("filter_planets", !filterPlanets) { filterPlanets = it } },
                                            label = { Text(if (isFa) "🪐 سیارات" else "🪐 Planets") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = filterMoons,
                                            onClick = { updateFilter("filter_moons", !filterMoons) { filterMoons = it } },
                                            label = { Text(if (isFa) "🌙 ماه" else "🌙 Moon") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = filterSun,
                                            onClick = { updateFilter("filter_sun", !filterSun) { filterSun = it } },
                                            label = { Text(if (isFa) "☀️ خورشید" else "☀️ Sun") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = filterDeepSky,
                                            onClick = { updateFilter("filter_deepsky", !filterDeepSky) { filterDeepSky = it } },
                                            label = { Text(if (isFa) "🌌 اعماق فضا" else "🌌 Deep Sky") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = filterSatellites,
                                            onClick = { updateFilter("filter_satellites", !filterSatellites) { filterSatellites = it } },
                                            label = { Text(if (isFa) "🛰 ماهواره‌ها" else "🛰 Satellites") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = filterMeteorShowers,
                                            onClick = { updateFilter("filter_meteor_showers", !filterMeteorShowers) { filterMeteorShowers = it } },
                                            label = { Text(if (isFa) "☄️ بارش‌های شهابی" else "☄️ Meteor Showers") }
                                        )
                                    }
                                }
                            }
                        }

                        ArExpandedPanel.SENSORS -> {
                            // Sensors & GPS Control Panel Content
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = if (isFa) "مدیریت حسگرها و موقعیت‌یاب" else "Sensors & Telemetry Control",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = RedTheme.colors.textPrimary
                                )

                                // Sensor Toggles Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // GPS Toggle
                                    FilterChip(
                                        selected = isGpsActive,
                                        onClick = {
                                            if (!hasLocationPermission) {
                                                locationPermissionLauncher.launch(
                                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                                )
                                            } else {
                                                isGpsActive = !isGpsActive
                                            }
                                        },
                                        label = { Text(if (isFa) "GPS دائم" else "Live GPS") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isGpsActive) Icons.Default.GpsFixed else Icons.Default.GpsOff,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )

                                    // Sensors Toggle
                                    FilterChip(
                                        selected = isSensorActive,
                                        onClick = { isSensorActive = !isSensorActive },
                                        label = { Text(if (isFa) "ژیروسکوپ / قطب‌نما" else "Sensor Fusion") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isSensorActive) Icons.Default.Sensors else Icons.Default.SensorsOff,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )

                                    // Camera Toggle
                                    FilterChip(
                                        selected = isCameraEnabled,
                                        onClick = {
                                            if (!hasCameraPermission) {
                                                permissionLauncher.launch(Manifest.permission.CAMERA)
                                            } else {
                                                val turningOff = isCameraEnabled
                                                isCameraEnabled = !isCameraEnabled
                                                userToggledCameraOff = turningOff
                                                if (turningOff) cameraStreaming = false
                                            }
                                        },
                                        label = { Text(if (isFa) "دوربین AR" else "Camera Feed") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }

                                // Operational Status Summary Card
                                var showDetailsAccordion by remember { mutableStateOf(false) }
                                val overallColor = arHealthColor(overallHealth.level)

                                Surface(
                                    shape = RoundedCornerShape(RedCornerRadius.window),
                                    color = overallColor.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, overallColor.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = when (overallHealth.level) {
                                                        ArHealthLevel.GREEN -> Icons.Default.VerifiedUser
                                                        ArHealthLevel.AMBER -> Icons.Default.WarningAmber
                                                        ArHealthLevel.RED -> Icons.Default.ErrorOutline
                                                        ArHealthLevel.GRAY -> Icons.Default.PowerSettingsNew
                                                    },
                                                    contentDescription = "Status",
                                                    tint = overallColor,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Column {
                                                    Text(
                                                        text = if (isFa) overallHealth.titleFa else overallHealth.titleEn,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = overallColor
                                                    )
                                                    Text(
                                                        text = if (isFa) {
                                                            "GPS: ${gpsHealth.titleFa} • سنسور: ${sensorHealth.titleFa} • دوربین: ${cameraHealth.titleFa}"
                                                        } else {
                                                            "GPS: ${gpsHealth.titleEn} • Sensors: ${sensorHealth.titleEn} • Camera: ${cameraHealth.titleEn}"
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = RedTheme.colors.textSecondary
                                                    )
                                                }
                                            }

                                            TextButton(
                                                onClick = { showDetailsAccordion = !showDetailsAccordion },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = if (showDetailsAccordion) (if (isFa) "بستن جزئیات" else "Hide Details")
                                                    else (if (isFa) "جزئیات تلمتری" else "Telemetry"),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = AccentPrimary
                                                )
                                            }
                                        }

                                        // Locator status is read here, not on the canvas. It stays outside the telemetry
                                        // accordion because a missing live fix changes how the whole panel should be
                                        // interpreted, and it is the one health item people actually act on.
                                        TelemetryDetailRow(if (isFa) "موقعیت‌یاب" else "GPS", gpsHealth, isFa)

                                        if (showDetailsAccordion) {
                                            HorizontalDivider(color = overallColor.copy(alpha = 0.3f), thickness = 0.5.dp)
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TelemetryDetailRow(if (isFa) "حسگر" else "Sensors", sensorHealth, isFa)
                                                TelemetryDetailRow(if (isFa) "دوربین" else "Camera", cameraHealth, isFa)
                                                Text(
                                                    text = if (isFa) "• موقعیت: ${TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.2f°", uiState.userLocation.latitude))}, ${TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.2f°", uiState.userLocation.longitude))}"
                                                    else "• Location: ${String.format(Locale.US, "%.2f°", uiState.userLocation.latitude)}, ${String.format(Locale.US, "%.2f°", uiState.userLocation.longitude)}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = RedTheme.colors.textPrimary
                                                )
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = if (isFa) "• وضعیت کالیبراسیون سنسور: ${calibrationState.name}" else "• Sensor Calibration: ${calibrationState.name}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = RedTheme.colors.textPrimary
                                                    )
                                                    TextButton(
                                                        onClick = { showManualSensorPrompt = true },
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                                    ) {
                                                        Text(
                                                            text = if (isFa) "راهنمای شکل ۸" else "Figure-8 Guide",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = AccentPrimary
                                                        )
                                                    }
                                                }
                                                if (arCalibrationOffsets.isCalibrated) {
                                                    val yawFmt = String.format(Locale.US, "%+.1f°", arCalibrationOffsets.yawOffsetDeg)
                                                    Text(
                                                        text = if (isFa)
                                                            "• آفست تراز AR (سمت): ${TimeEngine.formatPersianNumbers(yawFmt)}"
                                                        else
                                                            "• AR Alignment Offset (Yaw): $yawFmt",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = RedTheme.colors.accentRed
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Guided 1-Point Alignment Action Card
                                Surface(
                                    shape = RoundedCornerShape(RedCornerRadius.window),
                                    color = RedTheme.colors.surfaceElevated,
                                    border = BorderStroke(1.dp, RedTheme.colors.border),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.GpsFixed,
                                                    contentDescription = null,
                                                    tint = RedTheme.colors.accentRed,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Column {
                                                    Text(
                                                        text = if (isFa) "هم‌ترازی تک‌نقطه‌ای AR" else "1-Point Reference Alignment",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = RedTheme.colors.textPrimary
                                                    )
                                                    Text(
                                                        text = if (arCalibrationOffsets.isCalibrated) {
                                                            val yawFmt = String.format("%+.1f°", arCalibrationOffsets.yawOffsetDeg)
                                                            if (isFa) "آفست فعال سمت: $yawFmt" else "Active yaw offset: $yawFmt"
                                                        } else {
                                                            if (isFa) "همترازی نشانگر با جرم درخشان واقعی" else "Align pointer with a bright reference object"
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = RedTheme.colors.textSecondary
                                                    )
                                                }
                                            }

                                            Button(
                                                onClick = { isAlignmentMode = true },
                                                colors = ButtonDefaults.buttonColors(containerColor = RedTheme.colors.accentRed),
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                modifier = Modifier.testTag("open_alignment_btn")
                                            ) {
                                                Text(
                                                    text = if (isFa) "تراز" else "Align",
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

                        else -> {}
                    }
                }
            }
        }
        }

        // Zoom Indicator Floating Badge (Temporary while zooming)
        AnimatedVisibility(
            visible = showZoomIndicator && !isAlignmentMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 54.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.6f)),
                shadowElevation = 8.dp
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f×", zoomFactor),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // Tap Glass Information Card Overlay
        val activeTappedObj = tappedObject
        AnimatedVisibility(
            visible = activeTappedObj != null && !isAlignmentMode,
            enter = fadeIn(animationSpec = tween(250)) + scaleIn(animationSpec = tween(250), initialScale = 0.85f),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(animationSpec = tween(200), targetScale = 0.85f)
        ) {
            val obj = activeTappedObj ?: return@AnimatedVisibility

            val rs = remember(obj, jd) {
                CoordinateEngine.calculateRiseSetTransit(
                    raDeg = obj.raDeg,
                    decDeg = obj.decDeg,
                    latDeg = uiState.userLocation.latitude,
                    lonDeg = uiState.userLocation.longitude,
                    jd = jd,
                    isFa = isFa
                )
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val localDensity = LocalDensity.current
                val densityVal = localDensity.density

                val screenWidthPx = constraints.maxWidth.toFloat()
                val screenHeightPx = constraints.maxHeight.toFloat()
                val canvasWidth = screenWidthPx
                val canvasHeight = screenHeightPx

                val horiz = if (obj.type == ObjectType.SUN) sunHoriz
                else if (obj.id == "moon") moonHoriz
                else if (obj.type == ObjectType.SATELLITE || obj.id.startsWith("sat_")) satellitePositions[obj.id] ?: satellitePositions[com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog.resolveCanonicalId(obj.id)] ?: CoordinateEngine.Horizontal(-90.0, 0.0)
                else CoordinateEngine.equatorialToHorizontal(CoordinateEngine.Equatorial(obj.raDeg, obj.decDeg), lastDeg, uiState.userLocation.latitude)

                val projPt = ARProjectionEngine.projectAltAz(
                    azimuthDeg = horiz.azimuthDeg,
                    altitudeDeg = horiz.altitudeDeg,
                    rotationMatrix = if (isSensorActive) skyOrientation.rotationMatrix else null,
                    currentAzimuth = currentAzimuth,
                    currentAltitude = currentAltitude,
                    currentRoll = skyOrientation.roll.toDouble(),
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    intrinsics = cameraIntrinsics,
                    zoomFactor = zoomFactor,
                    sensorToViewMatrix = previewViewInstance?.sensorToViewTransform,
                    displayRotationDegrees = displayRotationDegrees
                )

                val px = projPt?.x ?: (canvasWidth / 2f)
                val py = projPt?.y ?: (canvasHeight / 2f)

                val cardWidthDp = 270.dp
                val cardHeightDp = 190.dp
                val cardWidthPx = with(localDensity) { cardWidthDp.toPx() }
                val cardHeightPx = with(localDensity) { cardHeightDp.toPx() }

                val targetX = (px - cardWidthPx / 2f).coerceIn(16f * densityVal, screenWidthPx - cardWidthPx - 16f * densityVal)
                val preferredY = if (py - cardHeightPx - 20f * densityVal < 80f * densityVal) {
                    py + 20f * densityVal
                } else {
                    py - cardHeightPx - 20f * densityVal
                }
                val targetY = preferredY.coerceIn(80f * densityVal, screenHeightPx - cardHeightPx - 90f * densityVal)

                val cardXDp = (targetX / densityVal).dp
                val cardYDp = (targetY / densityVal).dp

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(x = cardXDp, y = cardYDp)
                ) {
                    LiquidGlassSurface(
                        modifier = Modifier
                            .width(cardWidthDp)
                            .clickable {
                                tappedObject = null
                                viewModel.openObjectDetail(obj)
                            },
                        shape = RoundedCornerShape(RedCornerRadius.window),
                        style = LiquidGlassDefaults.Card,
                        fallbackColor = RedTheme.colors.surfaceElevated.copy(alpha = 0.96f),
                        fallbackBorder = BorderStroke(1.dp, RedTheme.colors.accentRed.copy(alpha = 0.6f)),
                        fallbackShadowElevation = 12.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isFa) obj.nameFa else obj.nameEn,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = RedTheme.colors.textPrimary
                                    )
                                    Text(
                                        text = if (isFa) obj.type.nameFa else obj.type.nameEn,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = RedTheme.colors.accentRed,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                IconButton(
                                    onClick = { tappedObject = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = RedTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
                                }
                            }

                            HorizontalDivider(color = RedTheme.colors.border, thickness = 0.5.dp)

                            val cardCalculatedState = remember(obj.id, activeTimeMs, uiState.userLocation) {
                                AstroDispatchEngine.calculateState(
                                    idOrAlias = obj.id,
                                    timestampMs = activeTimeMs,
                                    userLatDeg = uiState.userLocation.latitude,
                                    userLonDeg = uiState.userLocation.longitude,
                                    elevationM = uiState.userLocation.elevationMeters
                                )
                            }
                            val distStr = formatObjectDistance(obj, isFa, cardCalculatedState)
                            Text(
                                text = if (isFa) "فاصله از زمین: $distStr" else "Dist: $distStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = RedTheme.colors.textPrimary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isFa) "طلوع: ${rs.riseTimeStr}" else "Rise: ${rs.riseTimeStr}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RedTheme.colors.textSecondary
                                )
                                Text(
                                    text = if (isFa) "غروب: ${rs.setTimeStr}" else "Set: ${rs.setTimeStr}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RedTheme.colors.textSecondary
                                )
                            }

                            Button(
                                onClick = {
                                    tappedObject = null
                                    viewModel.openObjectDetail(obj)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = RedTheme.colors.accentRed),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .testTag("ar_target_detail_button"),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isFa) "مشاهده جزئیات" else "View Details",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        if (expandedLabelCluster.isNotEmpty() && !isAlignmentMode) {
            ClusterSelectionMenu(
                title = stringResource(R.string.ar_cluster_title),
                objects = expandedLabelCluster,
                isFa = isFa,
                onDismiss = { expandedLabelCluster = emptyList() },
                onSelect = { obj ->
                    expandedLabelCluster = emptyList()
                    selectedTarget = obj
                    hasVibratedForArrival = false
                    resetControlsTimer()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 92.dp)
            )
        }

        // Layer 7: Minimal "Object found" Confirmation Banner
        if (finderData?.isArrived == true && !isAlignmentMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-140).dp)
                    .testTag("ar_object_found_banner"),
                shape = RoundedCornerShape(RedCornerRadius.window),
                color = Color(0xDD0D1B2A),
                border = BorderStroke(1.dp, Color(0xFF4CAF50))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (isFa) "جرم پیدا شد" else "Object found",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Layer 7b: "Aligned: offset applied" confirmation pill (brief, after 1-point alignment)
        AnimatedVisibility(
            visible = alignmentConfirmationDeg != null && !isAlignmentMode,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(animationSpec = tween(200), initialScale = 0.9f),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 120.dp)
        ) {
            val applied = alignmentConfirmationDeg ?: 0f
            val offStr = String.format("%+.1f°", applied)
            Surface(
                shape = RoundedCornerShape(RedCornerRadius.window),
                color = Color(0xDD0D1B2A),
                border = BorderStroke(1.dp, StatusGood),
                shadowElevation = 12.dp,
                modifier = Modifier.testTag("alignment_confirmation_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = StatusGood,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (isFa) "تراز شد: آفست (${TimeEngine.formatPersianNumbers(offStr)}) اعمال شد"
                        else "Aligned: offset applied ($offStr)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Layer 8: Guided 1-Point Reference Alignment Mode
        if (isAlignmentMode) {
            AlignmentModeOverlay(
                isFa = isFa,
                targets = alignmentTargets,
                selectedTarget = selectedAlignmentTarget,
                onSelectTarget = { target -> selectedAlignmentTargetId = target.id },
                guidance = alignmentGuidance,
                hasActiveOffset = arCalibrationOffsets.isCalibrated,
                activeOffsetDeg = arCalibrationOffsets.yawOffsetDeg,
                onConfirm = {
                    val target = selectedAlignmentTarget
                    if (target != null) {
                        triggerAlignmentHaptic()
                        val applied = ARCalibrationManager.applyOnePointAlignment(
                            targetAzimuthDeg = target.azimuthDeg,
                            currentAzimuthDeg = currentAzimuth,
                            referenceName = if (isFa) target.nameFa else target.nameEn,
                            context = context,
                            latitude = uiState.userLocation.latitude,
                            longitude = uiState.userLocation.longitude
                        )
                        alignmentConfirmationDeg = applied
                        isAlignmentMode = false
                    }
                },
                onCancel = { isAlignmentMode = false },
                onReset = { ARCalibrationManager.resetAlignment(context) }
            )
        }

        // Layer 9: Automatic / Manual Figure-8 Sensor Calibration Prompt
        if (showManualSensorPrompt || (autoPromptEnabled && !autoPromptDismissedThisSession && !isAlignmentMode && (calibrationState == CalibrationState.NEEDS_CALIBRATION || calibrationState == CalibrationState.POOR || calibrationState == CalibrationState.UNCALIBRATED))) {
            ARSensorCalibrationDialog(
                calibrationState = calibrationState,
                isFa = isFa,
                onDismiss = {
                    if (calibrationState == CalibrationState.GOOD || calibrationState == CalibrationState.EXCELLENT) {
                        ARCalibrationManager.noteCalibrationCompleted(
                            context = context,
                            latitude = uiState.userLocation.latitude,
                            longitude = uiState.userLocation.longitude
                        )
                    }
                    autoPromptDismissedThisSession = true
                    showManualSensorPrompt = false
                },
                onDisableAutoPrompt = {
                    ARCalibrationManager.setAutoPromptEnabled(false, context)
                }
            )
        }
    }
}

@Composable
private fun EdgeIndicatorChip(
    indicator: ArEdgeIndicator,
    isFa: Boolean,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val x = with(density) { indicator.xPx.toDp() }
    val y = with(density) { indicator.yPx.toDp() }
    Surface(
        modifier = Modifier
            .offset(x = x - 42.dp, y = y - 18.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.76f),
        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.65f)),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = AccentPrimary,
                modifier = Modifier
                    .size(15.dp)
                    .rotate(indicator.angleDeg)
            )
            Text(
                text = if (isFa) indicator.obj.nameFa else indicator.obj.nameEn,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }
    }
}


@Composable
private fun TelemetryDetailRow(label: String, health: ArSubsystemHealth, isFa: Boolean) {
    val color = arHealthColor(health.level)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(shape = CircleShape, color = color, modifier = Modifier.size(7.dp)) {}
            Text(
                text = "• $label: ${if (isFa) health.titleFa else health.titleEn}",
                style = MaterialTheme.typography.labelSmall,
                color = RedTheme.colors.textPrimary
            )
        }
        Text(
            text = if (isFa) health.detailFa else health.detailEn,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PermissionBlockedBanner(
    text: String,
    actionText: String,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RedCornerRadius.window),
        color = Color(0xDD1A0B12),
        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.7f)),
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.VideocamOff, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(20.dp))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
            TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Text(actionText, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun MagneticInterferenceBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RedCornerRadius.window),
        color = Color(0xDD2A1800),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.7f)),
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun RecalibrationSuggestionBanner(
    title: String,
    body: String,
    action: String,
    onDismiss: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RedCornerRadius.window),
        color = Color.Black.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.55f)),
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.76f))
            }
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Text(action, style = MaterialTheme.typography.labelSmall, color = AccentPrimary, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun TimeMachineWatermark(label: String, dateTime: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.testTag("ar_time_machine_watermark"),
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.62f)
            )
            Text(
                text = dateTime,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.50f)
            )
        }
    }
}

@Composable
private fun ClusterSelectionMenu(
    title: String,
    objects: List<CelestialObject>,
    isFa: Boolean,
    onDismiss: () -> Unit,
    onSelect: (CelestialObject) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RedCornerRadius.window),
        color = RedTheme.colors.surfaceElevated.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, RedTheme.colors.border),
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = RedTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = RedTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(objects) { obj ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(obj) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFa) obj.nameFa else obj.nameEn,
                                style = MaterialTheme.typography.bodyMedium,
                                color = RedTheme.colors.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isFa) obj.type.nameFa else obj.type.nameEn,
                                style = MaterialTheme.typography.labelSmall,
                                color = RedTheme.colors.textSecondary
                            )
                        }
                        val mag = String.format(Locale.US, "%.1f", obj.magnitude)
                        Text(
                            text = if (isFa) TimeEngine.formatPersianNumbers(mag) else mag,
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentPrimary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Guided 1-Point Reference Alignment overlay.
 *
 * Clear-sightline HUD shown over the raw camera feed while all projected sky graphics are
 * hidden: a center crosshair reticle with a fine `+`, up to 3 recommended bright targets,
 * auto-hiding directional guide arrows, and bottom controls kept above the navigation bar.
 */
@Composable
private fun AlignmentModeOverlay(
    isFa: Boolean,
    targets: List<AlignmentTarget>,
    selectedTarget: AlignmentTarget?,
    onSelectTarget: (AlignmentTarget) -> Unit,
    guidance: AlignmentGuidance?,
    hasActiveOffset: Boolean,
    activeOffsetDeg: Float,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("alignment_mode_overlay")
    ) {
        // 1. Center crosshair reticle with a fine + at the exact center pixel
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("alignment_reticle")
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawCircle(
                color = Color.White.copy(alpha = 0.35f),
                radius = 110f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f)
            )
            drawCircle(
                color = AccentPrimary,
                radius = 65f,
                center = Offset(cx, cy),
                style = Stroke(width = 2.5f)
            )
            // Tick marks
            drawLine(Color.White, Offset(cx - 85f, cy), Offset(cx - 50f, cy), strokeWidth = 2f)
            drawLine(Color.White, Offset(cx + 50f, cy), Offset(cx + 85f, cy), strokeWidth = 2f)
            drawLine(Color.White, Offset(cx, cy - 85f), Offset(cx, cy - 50f), strokeWidth = 2f)
            drawLine(Color.White, Offset(cx, cy + 50f), Offset(cx, cy + 85f), strokeWidth = 2f)
            // Fine + pinned to the exact center pixel for accurate pinning
            val arm = 16f
            drawLine(Color.White, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = 2f)
            drawLine(Color.White, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = 2f)
            drawCircle(Color.White, radius = 2f, center = Offset(cx, cy))
        }

        // 2. Directional guide arrows (auto-hidden once the target nears the reticle)
        if (guidance?.arrowsVisible == true && selectedTarget != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(280.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .rotate(guidance.arrowAngleRad * 180f / PI.toFloat())
                        .testTag("alignment_guidance_arrow")
                ) {
                    val w = size.width
                    val h = size.height
                    val cX = w / 2f
                    val cY = h / 2f
                    drawCircle(
                        color = AccentPrimary.copy(alpha = 0.25f),
                        radius = 120f,
                        center = Offset(cX, cY),
                        style = Stroke(width = 6f)
                    )
                    val arrowPath = Path().apply {
                        moveTo(cX, cY - 105f)
                        lineTo(cX + 22f, cY - 72f)
                        lineTo(cX + 9f, cY - 75f)
                        lineTo(cX + 9f, cY - 50f)
                        lineTo(cX - 9f, cY - 50f)
                        lineTo(cX - 9f, cY - 75f)
                        lineTo(cX - 22f, cY - 72f)
                        close()
                    }
                    drawPath(path = arrowPath, color = AccentPrimary)
                }
                // Live separation readout below the arrow
                val sepStr = String.format("%.1f°", guidance.separationDeg)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.6f)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Text(
                        text = if (isFa) "فاصله: ${TimeEngine.formatPersianNumbers(sepStr)}"
                        else "Distance: $sepStr",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // 3. Top: reference target selector (recommended bright targets)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LiquidGlassSurface(
                shape = RoundedCornerShape(RedCornerRadius.window),
                style = LiquidGlassDefaults.Card,
                fallbackColor = RedTheme.colors.surfaceElevated.copy(alpha = 0.92f),
                fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border),
                fallbackShadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isFa) "جرم شاخص را انتخاب کنید:" else "Select reference target:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = RedTheme.colors.textPrimary
                    )
                    if (targets.isEmpty()) {
                        Text(
                            text = if (isFa) "در حال حاضر هیچ جرم درخشانی بالای افق نیست."
                            else "No bright targets above the horizon right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = RedTheme.colors.textSecondary
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(targets) { target ->
                                val label = if (isFa) target.nameFa else target.nameEn
                                val altStr = String.format("%+.0f°", target.altitudeDeg)
                                FilterChip(
                                    selected = target.id == selectedTarget?.id,
                                    onClick = { onSelectTarget(target) },
                                    label = {
                                        Text(
                                            text = "$label · " + (if (isFa) TimeEngine.formatPersianNumbers(altStr) else altStr),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    modifier = Modifier.testTag("alignment_target_chip_${target.id}")
                                )
                            }
                        }
                    }
                    selectedTarget?.let { target ->
                        val azStr = String.format("%.0f°", target.azimuthDeg)
                        val altStr = String.format("%+.0f°", target.altitudeDeg)
                        Text(
                            text = if (isFa) "موقعیت واقعی: سمت ${TimeEngine.formatPersianNumbers(azStr)} | ارتفاع ${TimeEngine.formatPersianNumbers(altStr)}"
                            else "True position: Az $azStr | Alt $altStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 4. Bottom controls — kept above the bottom nav bar / system gesture bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 88.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val targetName = selectedTarget?.let { if (isFa) it.nameFa else it.nameEn } ?: ""
            val arrowsOn = guidance?.arrowsVisible == true
            LiquidGlassSurface(
                shape = RoundedCornerShape(RedCornerRadius.window),
                style = LiquidGlassDefaults.Pill,
                fallbackColor = Color.Black.copy(alpha = 0.75f),
                fallbackBorder = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f))
            ) {
                Text(
                    text = when {
                        selectedTarget == null -> if (isFa) "جرم شاخصی در دسترس نیست" else "No reference target available"
                        arrowsOn -> if (isFa) "پیکان را دنبال کنید تا $targetName را پیدا کنید"
                        else "Follow the arrow to find $targetName"
                        else -> if (isFa) "علامت + را دقیقاً روی $targetName بگذارید و «تراز» را بزنید"
                        else "Center the + on $targetName, then tap Align"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("alignment_cancel_button")
                ) {
                    Text(
                        text = if (isFa) "انصراف" else "Cancel",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Button(
                    onClick = onConfirm,
                    enabled = selectedTarget != null,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("alignment_confirm_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.GpsFixed,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isFa) "تراز" else "Align",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                if (hasActiveOffset) {
                    OutlinedButton(
                        onClick = onReset,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                        modifier = Modifier.testTag("alignment_reset_button")
                    ) {
                        Text(
                            text = if (isFa) "بازنشانی" else "Reset",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (hasActiveOffset) {
                val offStr = String.format("%+.1f°", activeOffsetDeg)
                Text(
                    text = if (isFa) "آفست فعال: ${TimeEngine.formatPersianNumbers(offStr)}" else "Active offset: $offStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}
