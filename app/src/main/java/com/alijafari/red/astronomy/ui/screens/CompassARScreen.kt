package com.alijafari.red.astronomy.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
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
import androidx.compose.ui.platform.LocalView
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
import com.alijafari.red.astronomy.domain.*
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.MainViewModel
import com.alijafari.red.astronomy.ui.components.ARSensorCalibrationDialog
import com.alijafari.red.astronomy.ui.components.TimeMachineControlBar
import com.alijafari.red.astronomy.ui.rendering.*
import com.alijafari.red.astronomy.ui.theme.*
import kotlinx.coroutines.CancellationException
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
import android.os.SystemClock
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import com.alijafari.red.astronomy.ar.android.ArAssets
import com.alijafari.red.astronomy.ar.android.ArCameraBinder
import com.alijafari.red.astronomy.ar.android.ArGlyphCache
import com.alijafari.red.astronomy.ar.android.ArSkyGlFrame
import com.alijafari.red.astronomy.ar.android.ArSkyGlView
import com.alijafari.red.astronomy.ar.core.ArCameraIntrinsics
import com.alijafari.red.astronomy.ar.core.ArCameraModel
import com.alijafari.red.astronomy.ar.core.ArConstellation
import com.alijafari.red.astronomy.ar.core.ArObserver
import com.alijafari.red.astronomy.ar.core.ArPlaced
import com.alijafari.red.astronomy.ar.core.ArSkyFrame
import com.alijafari.red.astronomy.ar.core.ArSkyLighting
import com.alijafari.red.astronomy.ar.core.ArSkySceneComputer
import com.alijafari.red.astronomy.ar.core.ArSkySnapshot
import com.alijafari.red.astronomy.ar.core.ArStarCatalog
import com.alijafari.red.astronomy.ar.core.M3
import com.alijafari.red.astronomy.ar.core.V3
import com.alijafari.red.astronomy.ui.screens.ar.ArDrawnItem
import com.alijafari.red.astronomy.ui.screens.ar.ArOverlayOptions
import com.alijafari.red.astronomy.ui.screens.ar.ArOverlayState
import com.alijafari.red.astronomy.ui.screens.ar.ArSatelliteInfo
import com.alijafari.red.astronomy.ui.screens.ar.ArScreenSupport
import com.alijafari.red.astronomy.ui.screens.ar.ArSkyOverlay
import com.alijafari.red.astronomy.ui.screens.ar.SimulatedSkyFallback

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

private data class ArEdgeIndicator(
    val obj: CelestialObject,
    val xPx: Float,
    val yPx: Float,
    val angleDeg: Float,
    val priority: Double
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

private const val AR_TAG = "CompassAR"

/**
 * The [Activity] hosting a view's composition. `LocalContext` cannot be used for this: the app
 * replaces it with a locale-configured context (`createConfigurationContext`), which is neither an
 * Activity nor a wrapper around one.
 */
private fun Context.findHostActivity(): Activity? {
    var current: Context = this
    while (true) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
}

/**
 * Runs background AR work, returning null (and logging) if it fails. Coroutine cancellation is
 * re-thrown; every other failure is contained — including Errors such as OutOfMemoryError or
 * ExceptionInInitializerError, which a `catch (e: Exception)` would let escape and crash the app.
 */
private inline fun <T> arBackground(what: String, block: () -> T): T? = try {
    block()
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    Log.e(AR_TAG, "$what failed", t)
    null
}

/** Inputs of one AR sky computation; the worker recomputes whenever these change. */
private data class ArSkyInputs(
    val timeMs: Long,
    val observer: ArObserver,
    val stars: ArStarCatalog?,
    val figures: List<ArConstellation>,
    val bortle: Int
)

private fun calibrationLabel(state: CalibrationState, isFa: Boolean): String = when (state) {
    CalibrationState.EXCELLENT -> if (isFa) "عالی" else "Excellent"
    CalibrationState.GOOD -> if (isFa) "خوب" else "Good"
    CalibrationState.POOR -> if (isFa) "ضعیف" else "Poor"
    CalibrationState.NEEDS_CALIBRATION -> if (isFa) "نیازمند کالیبراسیون" else "Needs calibration"
    CalibrationState.UNCALIBRATED -> if (isFa) "در حال سنجش" else "Measuring"
}

/** Live sensor-fusion readouts for the Sensors & Telemetry panel. */
@Composable
private fun ArLiveSensorLines(status: ArSensorStatus, atmosphere: ArAtmosphereReading, isFa: Boolean) {
    val f = status.fusion
    fun n(v: Double, d: Int): String {
        if (!v.isFinite()) return "—"
        val t = String.format(Locale.US, "%.${d}f", v)
        return if (isFa) TimeEngine.formatPersianNumbers(t) else t
    }
    fun sgn(v: Double, d: Int): String {
        if (!v.isFinite()) return "—"
        val t = String.format(Locale.US, "%+.${d}f", v)
        return if (isFa) TimeEngine.formatPersianNumbers(t) else t
    }
    val mode = when (f.mode) {
        com.alijafari.red.astronomy.ar.core.HeadingFusion.Mode.FUSED -> if (isFa) "ژیروسکوپ + قطب‌نمای کنترل‌شده با WMM" else "Gyro + WMM-gated compass"
        com.alijafari.red.astronomy.ar.core.HeadingFusion.Mode.ROTATION_VECTOR -> if (isFa) "بردار چرخش اندروید" else "Android rotation vector"
        com.alijafari.red.astronomy.ar.core.HeadingFusion.Mode.GYRO_ONLY -> if (isFa) "فقط ژیروسکوپ" else "Gyro only"
        com.alijafari.red.astronomy.ar.core.HeadingFusion.Mode.ACCEL_MAG -> if (isFa) "شتاب‌سنج + قطب‌نما" else "Accelerometer + compass"
        com.alijafari.red.astronomy.ar.core.HeadingFusion.Mode.NONE -> if (isFa) "در انتظار داده" else "Waiting for data"
    }
    val lines = if (isFa) listOf(
        "• منبع جهت‌یابی: $mode",
        "• عدم قطعیت جهت: ±${n(f.headingSigmaDeg, 1)}°",
        "• انحراف مغناطیسی WMM2025: ${sgn(f.declinationDeg, 2)}° (±${n(f.declinationSigmaDeg, 2)}°)",
        "• میدان: ${n(f.fieldMeasuredUt, 1)} / ${n(f.fieldExpectedUt, 1)} µT • شیب: ${n(f.dipMeasuredDeg, 1)}° / ${n(f.dipExpectedDeg, 1)}°",
        "• فشار ${n(atmosphere.pressureHpa, 1)} hPa" + (if (atmosphere.pressureMeasured) " (فشارسنج)" else " (استاندارد)") +
            " • دما ${n(atmosphere.temperatureC, 0)}°C"
    ) else listOf(
        "• Attitude source: $mode",
        "• Heading uncertainty: ±${n(f.headingSigmaDeg, 1)}°",
        "• WMM2025 declination: ${sgn(f.declinationDeg, 2)}° (±${n(f.declinationSigmaDeg, 2)}°)",
        "• Field: ${n(f.fieldMeasuredUt, 1)} / ${n(f.fieldExpectedUt, 1)} µT • Dip: ${n(f.dipMeasuredDeg, 1)}° / ${n(f.dipExpectedDeg, 1)}°",
        "• Pressure ${n(atmosphere.pressureHpa, 1)} hPa" + (if (atmosphere.pressureMeasured) " (barometer)" else " (standard)") +
            " • Temp ${n(atmosphere.temperatureC, 0)}°C"
    )
    lines.forEach { line ->
        Text(text = line, style = MaterialTheme.typography.labelSmall, color = RedTheme.colors.textPrimary)
    }
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
    val textMeasurer = rememberTextMeasurer(cacheSize = 96)

    // Keep screen awake
    val hostView = LocalView.current
    val activity = remember(hostView) { hostView.context.findHostActivity() }
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
    val sensorStatus by orientationProvider.status.collectAsState()
    val atmosphereReading by orientationProvider.atmosphere.collectAsState()
    val arCalibrationOffsets by ARCalibrationManager.calibrationFlow.collectAsState()
    val autoPromptEnabled by ARCalibrationManager.autoPromptEnabledFlow.collectAsState()
    var isAlignmentMode by remember { mutableStateOf(false) }
    var autoPromptDismissedThisSession by remember { mutableStateOf(false) }
    var showManualSensorPrompt by remember { mutableStateOf(false) }
    var alignmentConfirmationDeg by remember { mutableStateOf<Float?>(null) }
    var alignmentRejected by remember { mutableStateOf(false) }
    var recalibrationPromptDismissedThisSession by remember { mutableStateOf(false) }
    var autoCalibrationWindowOpen by remember { mutableStateOf(false) }

    // Auto-dismiss the "Aligned: offset applied" confirmation pill
    LaunchedEffect(alignmentConfirmationDeg, alignmentRejected) {
        if (alignmentConfirmationDeg != null || alignmentRejected) {
            delay(3200L)
            alignmentConfirmationDeg = null
            alignmentRejected = false
        }
    }

    // Camera optics: the intrinsics of the camera CameraX actually bound (Camera2CameraInfo), plus a
    // documented typical-phone assumption until the camera is up.
    var cameraIntrinsics by remember { mutableStateOf(ArCameraIntrinsics.defaultAssumption()) }
    var boundCamera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var previewViewInstance by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

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
    var lastFixElapsedNanos by remember { mutableStateOf<Long?>(null) }
    var isSensorActive by remember { mutableStateOf(true) }
    var cameraStreaming by remember { mutableStateOf(false) }
    var cameraBindFailed by remember { mutableStateOf(false) }
    var userToggledCameraOff by remember { mutableStateOf(false) }
    var arViewportSize by remember { mutableStateOf(IntSize.Zero) }
    val cameraPrefs = remember { context.getSharedPreferences("ar_camera_permission_prefs", Context.MODE_PRIVATE) }
    var cameraPermissionAsked by remember { mutableStateOf(cameraPrefs.getBoolean("camera_permission_asked", false)) }
    var cameraBannerDismissed by remember { mutableStateOf(false) }
    // Live observer from GPS (full rate); the app-wide location is only refreshed on real moves.
    var liveLat by remember { mutableStateOf<Double?>(null) }
    var liveLon by remember { mutableStateOf<Double?>(null) }
    var liveAltM by remember { mutableStateOf<Double?>(null) }
    var lastPushedLat by remember { mutableStateOf<Double?>(null) }
    var lastPushedLon by remember { mutableStateOf<Double?>(null) }
    val latestUserLocation by rememberUpdatedState(uiState.userLocation)

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
        val activityContext = activity ?: return false
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
            boundCamera = null
        } else {
            // A fresh attempt each time the camera is (re-)enabled or permission is granted.
            cameraBindFailed = false
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

    // Live GPS: the AR sky uses every fix at full precision. The app-wide location (and with it the
    // time zone, resolved by the view model from the offline city database) is only replaced when the
    // user has really moved (> 1 km), never with a hard-coded zone.
    fun acceptFix(location: Location) {
        val alt = if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) {
            location.mslAltitudeMeters
        } else if (location.hasAltitude()) {
            location.altitude
        } else {
            null
        }
        liveLat = location.latitude
        liveLon = location.longitude
        if (alt != null) liveAltM = alt
        gpsAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
        lastFixTimestampMs = if (location.time > 0L) location.time else System.currentTimeMillis()
        lastFixElapsedNanos = location.elapsedRealtimeNanos
        val appLoc = latestUserLocation
        val farFromApp = ARCalibrationManager.haversineDistanceKm(
            appLoc.latitude, appLoc.longitude, location.latitude, location.longitude
        ) > 1.0
        val pLat = lastPushedLat
        val pLon = lastPushedLon
        val farFromLastPush = pLat == null || pLon == null ||
            ARCalibrationManager.haversineDistanceKm(pLat, pLon, location.latitude, location.longitude) > 1.0
        if (farFromApp && farFromLastPush) {
            lastPushedLat = location.latitude
            lastPushedLon = location.longitude
            viewModel.setGpsLocation(location.latitude, location.longitude, alt)
        }
    }

    DisposableEffect(isGpsActive, hasLocationPermission) {
        if (isGpsActive && hasLocationPermission) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    acceptFix(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    lastFixTimestampMs = null
                    lastFixElapsedNanos = null
                }
            }

            try {
                // Last known location for instant startup
                val lastGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                lastGps?.let { acceptFix(it) }

                if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, locationListener)
                } else if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1.0f, locationListener)
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

    // Observer used by every AR computation: live GPS when available, else the app location.
    val observerLat = liveLat ?: uiState.userLocation.latitude
    val observerLon = liveLon ?: uiState.userLocation.longitude
    val observerHeightM = liveAltM ?: uiState.userLocation.elevationMeters
    // ~1 km grid key for slow derived data (paths, rise/set) so GPS jitter does not recompute them.
    val observerGridKey = (observerLat * 100.0).roundToInt() * 100_000 + (observerLon * 100.0).roundToInt()

    // Manual Drag Fallback when sensors paused
    var manualAzimuthOffset by remember { mutableStateOf(0.0) }
    var manualPitchOffset by remember { mutableStateOf(20.0) }

    val currentAzimuth = if (isSensorActive) skyOrientation.azimuth.toDouble() else manualAzimuthOffset
    val currentAltitude = if (isSensorActive) skyOrientation.pitch.toDouble() else manualPitchOffset

    // Automatic calibration window: opens only when poor compass accuracy persists for 2 s (Android
    // reports transient low accuracy right after start-up), then stays open until the user closes it.
    val needsCalibration = isSensorActive &&
        (calibrationState == CalibrationState.NEEDS_CALIBRATION || calibrationState == CalibrationState.POOR)
    LaunchedEffect(needsCalibration, autoPromptEnabled, autoPromptDismissedThisSession, isAlignmentMode) {
        if (needsCalibration && autoPromptEnabled && !autoPromptDismissedThisSession && !isAlignmentMode && !autoCalibrationWindowOpen) {
            delay(2000L)
            autoCalibrationWindowOpen = true
        }
    }

    // Sensors start/stop only with the user's toggle and the screen's lifetime; a GPS fix merely
    // updates the geomagnetic model (no restart, so the fusion and interference state survive).
    DisposableEffect(isSensorActive) {
        if (isSensorActive) orientationProvider.start() else orientationProvider.stop()
        onDispose { orientationProvider.stop() }
    }
    LaunchedEffect(observerLat, observerLon, observerHeightM) {
        orientationProvider.updateLocation(observerLat, observerLon, observerHeightM)
    }

    // Time Machine State & Ticking Handlers
    val timeMachineState = uiState.timeMachineState
    val coroutineScope = rememberCoroutineScope()
    val latestTimeMachineState by rememberUpdatedState(timeMachineState)

    // Smooth playback: advances from the latest state every frame, so a jump made while playing
    // (date picker, event shortcut) is adopted instead of being overwritten by a stale local clock.
    LaunchedEffect(timeMachineState.mode, timeMachineState.isPlaying, timeMachineState.speed, timeMachineState.isReverse) {
        if (timeMachineState.mode == TimeMachineMode.SIMULATION && timeMachineState.isPlaying) {
            var lastFrameNanos = 0L
            var carryMs = 0.0
            var currentSimMs = timeMachineState.simulationTimeMs
            var lastSeenStateMs = currentSimMs
            val recentWrites = ArrayDeque<Long>()
            while (true) {
                withFrameNanos { frameTimeNanos ->
                    val st = latestTimeMachineState
                    // A value we did not write ourselves is an external jump (date picker, event
                    // shortcut, slider) made while playing: continue from there.
                    if (st.simulationTimeMs != lastSeenStateMs && st.simulationTimeMs !in recentWrites) {
                        currentSimMs = st.simulationTimeMs
                        carryMs = 0.0
                    }
                    lastSeenStateMs = st.simulationTimeMs
                    if (lastFrameNanos == 0L) {
                        lastFrameNanos = frameTimeNanos
                    } else {
                        val deltaRealSec = (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
                        lastFrameNanos = frameTimeNanos
                        val direction = if (st.isReverse) -1.0 else 1.0
                        carryMs += deltaRealSec * st.speed.multiplier * direction * 1000.0
                        val whole = carryMs.toLong()
                        if (whole != 0L) {
                            carryMs -= whole
                            currentSimMs = (currentSimMs + whole).coerceIn(
                                TimeMachineState.MIN_TIMESTAMP_MS,
                                TimeMachineState.MAX_TIMESTAMP_MS
                            )
                            recentWrites.addLast(currentSimMs)
                            while (recentWrites.size > 16) recentWrites.removeFirst()
                            viewModel.setSimulatedTime(currentSimMs, st.eventName, st.isBirthdayMode)
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
    val jd = remember(activeTimeMs) { TimeEngine.getJulianDate(activeTimeMs) }

    // Live TLE background fetch for ISS orbital precision
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ISSEngine.fetchLatestTLE()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The AR sky model: apparent places of date for the observer, computed off the UI thread.
    // ---------------------------------------------------------------------------------------------
    val allCatalog = remember { ArScreenSupport.arCatalogue() }
    val staticTargets = remember(allCatalog) { ArScreenSupport.staticTargets(allCatalog) }
    var starCatalog by remember { mutableStateOf<ArStarCatalog?>(null) }
    var constellationFigures by remember { mutableStateOf<List<ArConstellation>>(emptyList()) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            arBackground("Loading the star catalogue") { ArAssets.stars(context) to ArAssets.constellations(context) }
        }
        if (loaded != null) {
            starCatalog = loaded.first
            constellationFigures = loaded.second
        }
    }
    val glyphCache = remember { ArGlyphCache(context) }
    DisposableEffect(glyphCache) { onDispose { glyphCache.release() } }
    val overlayState = remember { ArOverlayState() }

    var skySnapshot by remember { mutableStateOf<ArSkySnapshot?>(null) }
    var skyLighting by remember { mutableStateOf<ArSkyLighting?>(null) }
    var arSatellites by remember { mutableStateOf<List<ArSatelliteInfo>>(emptyList()) }
    val observerForSky = ArObserver(
        latitudeDeg = observerLat,
        longitudeDeg = observerLon,
        heightM = observerHeightM,
        // Quantised so that barometer noise does not trigger recomputation (0.5 hPa ≈ 0.05 % of refraction).
        pressureHpa = (atmosphereReading.pressureHpa * 2.0).roundToInt() / 2.0,
        temperatureC = (atmosphereReading.temperatureC * 2.0).roundToInt() / 2.0
    )
    val latestSkyInputs by rememberUpdatedState(
        ArSkyInputs(activeTimeMs, observerForSky, starCatalog, constellationFigures, uiState.bortleClass)
    )
    // One long-lived worker computes the sky from the latest inputs whenever they change. In-flight
    // work is never cancelled, so fast time-machine playback still advances the sky continuously.
    LaunchedEffect(Unit) {
        var last: ArSkyInputs? = null
        while (true) {
            val inputs = latestSkyInputs
            if (inputs == last) {
                delay(16L)
                continue
            }
            last = inputs
            val result = withContext(Dispatchers.Default) {
                arBackground("AR sky computation") {
                    val obs = inputs.observer
                    val sats = ArScreenSupport.satellites(inputs.timeMs, obs.latitudeDeg, obs.longitudeDeg, obs.heightM)
                    val satMap = HashMap<String, Pair<Double, Double>>()
                    sats.forEach { s -> satMap[s.id] = s.azimuthDeg to s.altitudeDeg }
                    val builder = ArAssets.lightingBuilder()
                    synchronized(builder) {
                        val snap = ArSkySceneComputer.compute(
                            inputs.timeMs, obs, inputs.stars, inputs.figures, staticTargets, satMap, builder.atmosphereModel
                        )
                        val light = builder.build(
                            snap.sun.placed.enu, snap.moon.placed.enu,
                            snap.moon.glyph?.phaseAngleDeg ?: 90.0, obs.heightM, inputs.bortle
                        )
                        Triple(snap, light, sats)
                    }
                }
            }
            if (result != null) {
                skySnapshot = result.first
                skyLighting = result.second
                arSatellites = result.third
            }
        }
    }

    fun placementOf(obj: CelestialObject): ArPlaced? {
        val snap = skySnapshot ?: return null
        snap.placementOf(obj.id)?.let { return it }
        snap.placementOf(com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog.resolveCanonicalId(obj.id))?.let { return it }
        if (obj.type == ObjectType.SATELLITE || obj.id.startsWith("sat_")) {
            val sat = arSatellites.firstOrNull { s -> ArScreenSupport.satelliteIdsFor(s).contains(obj.id) }
            if (sat != null) return snap.placementOf(sat.id)
        }
        return null
    }

    val gpsFixAgeMs = lastFixElapsedNanos?.let { ((SystemClock.elapsedRealtimeNanos() - it) / 1_000_000L).coerceAtLeast(0L) }
        ?: lastFixTimestampMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
    val gpsHealth = remember(isGpsActive, hasLocationPermission, gpsAccuracyMeters, gpsFixAgeMs?.div(1000L)) {
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

    val sensorHealth = remember(isSensorActive, calibrationState, sensorStatus.fusion.headingSigmaDeg.toInt()) {
        val sigma = sensorStatus.fusion.headingSigmaDeg
        val sigmaEn = if (sigma.isFinite()) "±${String.format(Locale.US, "%.1f", sigma)}° heading" else calibrationLabel(calibrationState, false)
        val sigmaFa = if (sigma.isFinite()) "جهت ±${TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.1f", sigma))}°" else calibrationLabel(calibrationState, true)
        if (!isSensorActive) {
            ArSubsystemHealth(ArHealthLevel.GRAY, "Sensors off", "حسگرها خاموش", "Manual pointing mode", "حالت نشانه‌روی دستی")
        } else {
            when (calibrationState) {
                CalibrationState.EXCELLENT, CalibrationState.GOOD -> ArSubsystemHealth(ArHealthLevel.GREEN, "Sensors calibrated", "حسگرها کالیبره", sigmaEn, sigmaFa)
                CalibrationState.POOR -> ArSubsystemHealth(ArHealthLevel.AMBER, "Sensor accuracy low", "دقت حسگر متوسط", sigmaEn, sigmaFa)
                CalibrationState.NEEDS_CALIBRATION, CalibrationState.UNCALIBRATED -> ArSubsystemHealth(ArHealthLevel.RED, "Sensors need calibration", "حسگرها نیازمند کالیبراسیون", sigmaEn, sigmaFa)
            }
        }
    }

    val cameraHealth = remember(hasCameraPermission, isCameraEnabled, cameraStreaming, cameraBindFailed, userToggledCameraOff) {
        when {
            userToggledCameraOff && !isCameraEnabled -> ArSubsystemHealth(ArHealthLevel.GRAY, "Camera off", "دوربین خاموش", "Simulated sky", "آسمان شبیه‌سازی‌شده")
            !hasCameraPermission -> ArSubsystemHealth(ArHealthLevel.RED, "Camera permission denied", "مجوز دوربین رد شده", "Showing the simulated sky", "نمایش آسمان شبیه‌سازی‌شده")
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

    val recalibrationDecision = remember(arCalibrationOffsets, observerLat, observerLon, lastFixTimestampMs) {
        ARCalibrationManager.shouldSuggestRecalibration(
            currentTimeMs = System.currentTimeMillis(),
            currentLatitude = observerLat,
            currentLongitude = observerLon
        )
    }

    // Guided 1-Point Reference Alignment: bright targets at their live apparent places.
    val alignmentTargets = remember(skySnapshot) {
        skySnapshot?.let { ArScreenSupport.alignmentTargets(it, allCatalog) } ?: emptyList()
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

    val searchResults = remember(searchQuery, observerLat, observerLon, jd) {
        if (searchQuery.isNotBlank()) {
            CelestialSearchEngine.search(
                query = searchQuery,
                userLat = observerLat,
                userLon = observerLon,
                jd = jd
            )
        } else {
            emptyList()
        }
    }

    // AR Sky Zoom State: physical camera zoom while the camera is live, virtual zoom otherwise.
    var zoomFactor by remember { mutableFloatStateOf(1.0f) }
    var showZoomIndicator by remember { mutableStateOf(false) }
    var previousZoomFactor by remember { mutableFloatStateOf(zoomFactor) }
    var lastZoomChangeMs by remember { mutableLongStateOf(0L) }
    val cameraLive = hasCameraPermission && isCameraEnabled && cameraStreaming

    LaunchedEffect(zoomFactor, boundCamera) {
        val cam = boundCamera
        if (cam != null) {
            val zs = cam.cameraInfo.zoomState.value
            val target = if (zs != null) zoomFactor.coerceIn(zs.minZoomRatio, zs.maxZoomRatio) else zoomFactor
            try {
                cam.cameraControl.setZoomRatio(target)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    LaunchedEffect(zoomFactor) {
        if (zoomFactor != previousZoomFactor) {
            previousZoomFactor = zoomFactor
            lastZoomChangeMs = System.currentTimeMillis()
        }
    }
    LaunchedEffect(lastZoomChangeMs) {
        if (lastZoomChangeMs == 0L) return@LaunchedEffect
        showZoomIndicator = true
        delay(1500L)
        showZoomIndicator = false
    }

    // Night vision (deep red, preserves dark adaptation); local to the AR screen.
    val arPrefs = remember { context.getSharedPreferences("ar_filters_prefs", Context.MODE_PRIVATE) }
    var nightVision by remember { mutableStateOf(arPrefs.getBoolean("ar_night_vision", false)) }

    // Camera model of the current frame (exact CameraX/Camera2 pipeline, or the virtual camera).
    fun currentAttitude(): DoubleArray {
        val m = skyOrientation.rotationMatrix
        return if (isSensorActive && ArScreenSupport.isValidRotation(m)) ArScreenSupport.toDouble(m)
        else ArScreenSupport.manualAttitude(manualAzimuthOffset, manualPitchOffset)
    }

    fun buildCameraModel(width: Float, height: Float): ArCameraModel {
        val model = if (cameraLive) {
            val values = previewViewInstance?.sensorToViewTransform?.let { mtx ->
                FloatArray(9).also { mtx.getValues(it) }
            }
            val ratio = boundCamera?.cameraInfo?.zoomState?.value?.zoomRatio?.toDouble() ?: zoomFactor.toDouble()
            ArCameraModel.camera(width, height, displayRotationDegrees, cameraIntrinsics, values, ratio)
        } else {
            val vfov = ArCameraModel.cameraVerticalFovDeg(width, height, displayRotationDegrees, cameraIntrinsics).coerceIn(45.0, 80.0)
            ArCameraModel.virtual(width, height, displayRotationDegrees, vfov, zoomFactor.toDouble().coerceIn(0.5, 12.0))
        }
        model.setAttitude(currentAttitude())
        return model
    }

    val composeCameraModel = if (arViewportSize.width > 0 && arViewportSize.height > 0) {
        buildCameraModel(arViewportSize.width.toFloat(), arViewportSize.height.toFloat())
    } else null
    val latestPixelsPerDegree by rememberUpdatedState(composeCameraModel?.pixelsPerDegree() ?: 30.0)

    // Finder: live apparent place of the target, guidance through the projection (roll-correct).
    var finderArrived by remember { mutableStateOf(false) }
    val finderData = run {
        val target = selectedTarget ?: return@run null
        val cam = composeCameraModel ?: return@run null
        val placed = placementOf(target) ?: return@run null
        ArScreenSupport.finder(target, placed, cam, finderArrived)
    }
    LaunchedEffect(finderData?.isArrived) {
        val arrived = finderData?.isArrived == true
        finderArrived = arrived
        if (arrived && !hasVibratedForArrival) {
            hasVibratedForArrival = true
            triggerAlignmentHaptic(300L)
        } else if (!arrived) {
            hasVibratedForArrival = false
        }
    }

    // Focus Mode Smart Panel State
    var activeExpandedPanel by remember { mutableStateOf<ArExpandedPanel?>(null) }

    // AR Object Visibility Filters Persistence
    val prefs = arPrefs
    var filterStars by remember { mutableStateOf(prefs.getBoolean("filter_stars", true)) }
    var filterUnnamedStars by remember { mutableStateOf(prefs.getBoolean("filter_unnamed_stars", true)) }
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
        filterUnnamedStars = value
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
            .putBoolean("filter_unnamed_stars", value)
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

    val currentDensity = LocalDensity.current.density
    val isAlignmentModeState by rememberUpdatedState(isAlignmentMode)

    // AR Info Card Object State & Auto-Dismiss Timer
    var tappedObject by remember { mutableStateOf<CelestialObject?>(null) }
    var expandedLabelCluster by remember { mutableStateOf<List<CelestialObject>>(emptyList()) }

    LaunchedEffect(tappedObject) {
        if (tappedObject != null) {
            delay(8000L)
            tappedObject = null
        }
    }

    // Active Orbit Object (Target or tapped object)
    val activeOrbitObject = selectedTarget ?: tappedObject

    // Path of the active object (apparent, for this observer), computed in the background.
    var orbitPaths by remember { mutableStateOf(Pair(emptyList<CoordinateEngine.Horizontal>(), emptyList<CoordinateEngine.Horizontal>())) }
    LaunchedEffect(activeOrbitObject?.id, activeTimeMs / 60_000L, observerGridKey) {
        val target = activeOrbitObject
        if (target == null) {
            orbitPaths = Pair(emptyList(), emptyList())
        } else {
            val obs = observerForSky
            val t = activeTimeMs
            val stars = starCatalog
            orbitPaths = withContext(Dispatchers.Default) {
                arBackground("Path of ${target.id}") { ArScreenSupport.path(target, t, obs, stars) }
            } ?: Pair(emptyList(), emptyList())
        }
    }
    val (orbitPastPoints, orbitFuturePoints) = orbitPaths

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

    // Off-screen indicators: notable objects outside the real field of view (from the exact camera
    // model), in screen-space direction so they are right for any roll.
    val edgeIndicators = run {
        val cam = composeCameraModel
        val snap = skySnapshot
        val width = arViewportSize.width.toFloat()
        val height = arViewportSize.height.toFloat()
        val margin = 28f * currentDensity
        if (cam == null || snap == null || width <= 2f * margin || height <= 2f * margin || !isControlsVisible || isAlignmentMode) {
            emptyList()
        } else {
            val centerX = width / 2f
            val centerY = height / 2f
            val bore = cam.boresightEnu()
            val c = DoubleArray(2)
            val p = FloatArray(2)
            allCatalog.mapNotNull { obj ->
                if (selectedTarget?.id == obj.id) return@mapNotNull null
                if (!isVisibleByArFilter(obj, filterStars, filterSun, filterMoons, filterPlanets, filterSatellites, filterDeepSky, filterMeteorShowers)) return@mapNotNull null
                val interesting = when {
                    obj.type == ObjectType.SUN || obj.id == "moon" || obj.type == ObjectType.PLANET -> true
                    obj.type == ObjectType.SATELLITE -> obj.id == "sat_25544" || obj.id.contains("iss", ignoreCase = true)
                    obj.type == ObjectType.STAR -> obj.magnitude <= 1.5
                    else -> false
                }
                if (!interesting) return@mapNotNull null
                val placed = placementOf(obj) ?: return@mapNotNull null
                if (placed.altDeg <= 0.0) return@mapNotNull null
                val sep = Math.toDegrees(V3.angleBetween(bore, placed.enu))
                if (sep > 100.0) return@mapNotNull null
                if (cam.project(placed.enu[0], placed.enu[1], placed.enu[2], p) &&
                    p[0] in 0f..width && p[1] in 0f..height
                ) return@mapNotNull null
                if (!cam.screenDirection(placed.enu[0], placed.enu[1], placed.enu[2], c)) return@mapNotNull null
                val vx = c[0].toFloat()
                val vy = c[1].toFloat()
                val norm = hypot(vx, vy).coerceAtLeast(1e-6f)
                val ux = vx / norm
                val uy = vy / norm
                val tX = if (ux > 0f) (centerX - margin) / ux else if (ux < 0f) -(centerX - margin) / ux else Float.POSITIVE_INFINITY
                val tY = if (uy > 0f) (centerY - margin) / uy else if (uy < 0f) -(centerY - margin) / uy else Float.POSITIVE_INFINITY
                val t = min(tX, tY).takeIf { it.isFinite() && it > 0f } ?: 1f
                val x = (centerX + ux * t).coerceIn(margin, width - margin)
                val y = (centerY + uy * t).coerceIn(margin, height - margin)
                val angle = Math.toDegrees(atan2(uy.toDouble(), ux.toDouble())).toFloat() + 90f
                val body = snap.bodies.firstOrNull { it.id == obj.id }
                val priority = when {
                    obj.type == ObjectType.SUN || obj.id == "moon" -> 0.0
                    obj.type == ObjectType.PLANET -> 1.0 + (body?.magnitude ?: obj.magnitude) * 0.2
                    obj.type == ObjectType.SATELLITE -> 1.5
                    else -> 10.0 + obj.magnitude
                }
                ArEdgeIndicator(obj, x, y, angle, priority)
            }.sortedBy { it.priority }.take(8)
        }
    }

    // The GL sky (camera off) and its availability.
    var glSkyReady by remember { mutableStateOf(false) }
    var glSkyFailed by remember { mutableStateOf(false) }
    var glSkyView by remember { mutableStateOf<ArSkyGlView?>(null) }
    val showSimulatedSky = !cameraLive

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { arViewportSize = it }
            .drawWithContent {
                drawContent()
                if (nightVision) {
                    // Night vision: everything (camera included) through a deep red filter.
                    drawRect(color = Color(0xFFFF1E1E), blendMode = BlendMode.Multiply)
                }
            }
            .pointerInput(cameraLive) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1.0f) {
                        val range = if (cameraLive) {
                            boundCamera?.cameraInfo?.zoomState?.value?.let { it.minZoomRatio to it.maxZoomRatio } ?: (1f to 4f)
                        } else {
                            0.5f to 12f
                        }
                        zoomFactor = (zoomFactor * zoom).coerceIn(range.first, range.second)
                    }
                }
            }
            .pointerInput(isSensorActive) {
                if (!isSensorActive) {
                    // "Grab the sky": the drag moves the sky by exactly the angle under the finger.
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val ppd = latestPixelsPerDegree.coerceAtLeast(1.0)
                        val cosAlt = cos(Math.toRadians(manualPitchOffset)).coerceAtLeast(0.2)
                        manualAzimuthOffset = (manualAzimuthOffset - dragAmount.x / ppd / cosAlt) % 360.0
                        if (manualAzimuthOffset < 0) manualAzimuthOffset += 360.0
                        manualPitchOffset = (manualPitchOffset + dragAmount.y / ppd).coerceIn(-89.0, 89.0)
                    }
                }
            }
            .testTag("compass_ar_screen")
    ) {
        // Layer 0: simulated sky when the camera feed is not showing (physically based GL sky with the
        // Milky Way; a Canvas gradient from the same model if GL is unavailable).
        if (showSimulatedSky) {
            val lighting = skyLighting
            if (lighting != null && (!glSkyReady || glSkyFailed)) {
                SimulatedSkyFallback(lighting = lighting, cameraModel = composeCameraModel)
            }
            if (!glSkyFailed) {
                AndroidView(
                    factory = { ctx ->
                        ArSkyGlView(ctx).also { view ->
                            view.onStatusChanged = { ok ->
                                if (ok) glSkyReady = true else glSkyFailed = true
                            }
                            glSkyView = view
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        view.release()
                        if (glSkyView === view) glSkyView = null
                        glSkyReady = false
                    }
                )
            }
        }

        // Layer 1: Live Camera Feed (removed after a bind failure so the simulated sky shows instead of
        // a black preview; switching the camera off and on retries).
        if (hasCameraPermission && isCameraEnabled && !cameraBindFailed) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        // TextureView-backed so that the night-vision filter also tints the preview.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        // Transparent until the first camera frame: the simulated sky stays visible.
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                    previewViewInstance = previewView
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        // The view may already have left the composition (fast camera toggle).
                        if (previewView.getTag(R.id.ar_camera_released_tag) == true) return@addListener
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val bound = ArCameraBinder.bind(cameraProvider, lifecycleOwner, previewView)
                            cameraProviderRef = cameraProvider
                            cameraIntrinsics = bound.intrinsics
                            boundCamera = bound.camera
                            cameraBindFailed = false
                            cameraStreaming = true
                        } catch (e: Exception) {
                            cameraBindFailed = true
                            cameraStreaming = false
                            boundCamera = null
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view ->
                    // Close the camera when the feed is switched off or the screen is left.
                    view.setTag(R.id.ar_camera_released_tag, true)
                    try {
                        cameraProviderRef?.unbindAll()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    cameraProviderRef = null
                    boundCamera = null
                    cameraStreaming = false
                    if (previewViewInstance === view) previewViewInstance = null
                }
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
                            // Hit-test exactly what was drawn in the last frame.
                            val items = overlayState.items.toList()
                            val tapRadius = 28f * currentDensity
                            var best: ArDrawnItem? = null
                            var bestScore = Double.MAX_VALUE
                            for (item in items) {
                                val d = hypot((touchOffset.x - item.x).toDouble(), (touchOffset.y - item.y).toDouble())
                                if (d > tapRadius + item.radiusPx) continue
                                val score = d + item.priority * 1.5
                                if (score < bestScore) { bestScore = score; best = item }
                            }
                            val crowd = items.filter {
                                hypot((touchOffset.x - it.x).toDouble(), (touchOffset.y - it.y).toDouble()) <= tapRadius
                            }
                            if (crowd.size >= 3 && best != null && best.radiusPx < tapRadius) {
                                expandedLabelCluster = crowd.sortedBy { it.priority }.map { it.obj }.distinctBy { it.id }
                                tappedObject = null
                            } else {
                                tappedObject = best?.obj
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

            // Guided 1-Point Alignment: clear sightline — hide all projected sky graphics.
            if (isAlignmentMode) return@Canvas

            val snap = skySnapshot
            val cam = buildCameraModel(canvasWidth, canvasHeight)

            // Feed the GL sky with exactly the same virtual camera.
            val glView = glSkyView
            if (showSimulatedSky && glView != null && snap != null) {
                val lighting = skyLighting
                if (lighting != null) glView.updateLighting(lighting)
                val c2e = cam.cameraToEnuMatrix()
                val e2g = M3.transpose(snap.frame.enuFromGcrs)
                glView.updateFrame(
                    ArSkyGlFrame(
                        camToEnu = FloatArray(9) { c2e[it].toFloat() },
                        enuToGcrs = FloatArray(9) { e2g[it].toFloat() },
                        focalPx = cam.focalLengthViewPx().toFloat(),
                        viewWidth = canvasWidth.toInt(),
                        viewHeight = canvasHeight.toInt(),
                        refractionScale = ArScreenSupport.refractionScale(snap.frame.observer)
                    )
                )
            }

            if (snap != null) {
                ArSkyOverlay.draw(
                    scope = this,
                    state = overlayState,
                    snapshot = snap,
                    lighting = skyLighting,
                    camera = cam,
                    catalogue = allCatalog,
                    satellites = arSatellites,
                    glyphs = glyphCache,
                    opts = ArOverlayOptions(
                        cameraMode = cameraLive,
                        zoom = if (cameraLive) (boundCamera?.cameraInfo?.zoomState?.value?.zoomRatio?.toDouble() ?: 1.0) else zoomFactor.toDouble(),
                        density = density,
                        showStars = filterStars,
                        showUnnamedStars = filterUnnamedStars,
                        showConstellations = filterConstellations,
                        showPlanets = filterPlanets,
                        showMoon = filterMoons,
                        showSun = filterSun,
                        showDeepSky = filterDeepSky,
                        showSatellites = filterSatellites,
                        showMeteors = filterMeteorShowers,
                        showNames = filterObjectNames,
                        isFa = isFa,
                        timeMs = System.currentTimeMillis(),
                        selectedId = selectedTarget?.id,
                        accent = AccentPrimary
                    ),
                    textMeasurer = textMeasurer,
                    fontFamily = canvasFontFamily,
                    aimRadiusPx = 24f * density
                )

                // Path of the active object: solid = past, dashed = future.
                if (activeOrbitObject != null) {
                    fun pathOf(points: List<CoordinateEngine.Horizontal>): Path? {
                        val path = Path()
                        var first = true
                        val p = FloatArray(2)
                        for (pt in points) {
                            val e = ArSkyFrame.enuFromAltAz(pt.altitudeDeg, pt.azimuthDeg)
                            if (!cam.project(e[0], e[1], e[2], p)) { first = true; continue }
                            if (p[0] !in -600f..(canvasWidth + 600f) || p[1] !in -600f..(canvasHeight + 600f)) { first = true; continue }
                            if (first) { path.moveTo(p[0], p[1]); first = false } else path.lineTo(p[0], p[1])
                        }
                        return path
                    }
                    pathOf(orbitPastPoints)?.let {
                        drawPath(path = it, color = Color(0xFF38BDF8).copy(alpha = 0.8f), style = Stroke(width = 2.2f * density))
                    }
                    pathOf(orbitFuturePoints)?.let {
                        drawPath(
                            path = it,
                            color = Color(0xFF38BDF8).copy(alpha = 0.8f),
                            style = Stroke(width = 2.2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 8f * density), 0f))
                        )
                    }
                }
            }

            // Centre reticle (its inner ring is also the aim radius used above).
            val ring = 24f * density
            drawCircle(color = AccentPrimary, radius = ring, center = Offset(centerX, centerY), style = Stroke(width = 1.8f * density))
            drawCircle(color = Color.White.copy(alpha = 0.3f), radius = ring * 1.7f, center = Offset(centerX, centerY), style = Stroke(width = 1f * density))
            val t0 = ring * 1.25f
            val t1 = ring * 0.72f
            drawLine(Color.White.copy(alpha = 0.85f), Offset(centerX - t0, centerY), Offset(centerX - t1, centerY), strokeWidth = 1.4f * density)
            drawLine(Color.White.copy(alpha = 0.85f), Offset(centerX + t1, centerY), Offset(centerX + t0, centerY), strokeWidth = 1.4f * density)
            drawLine(Color.White.copy(alpha = 0.85f), Offset(centerX, centerY - t0), Offset(centerX, centerY - t1), strokeWidth = 1.4f * density)
            drawLine(Color.White.copy(alpha = 0.85f), Offset(centerX, centerY + t1), Offset(centerX, centerY + t0), strokeWidth = 1.4f * density)
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
                        val distStr = String.format(Locale.US, "%.1f°", finder.totalAngularDistanceDeg)
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
                val isNightVision = nightVision
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
                                // AR-local red night vision (the app-wide theme is left untouched).
                                nightVision = !nightVision
                                arPrefs.edit().putBoolean("ar_night_vision", nightVision).apply()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NightsStay,
                                contentDescription = if (isFa) "دید در شب" else "Night vision",
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
                                            selected = filterUnnamedStars,
                                            onClick = { updateFilter("filter_unnamed_stars", !filterUnnamedStars) { filterUnnamedStars = it } },
                                            label = { Text(if (isFa) "✦ ستارگان بی‌نام" else "✦ Unnamed Stars") }
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

                                LiquidGlassSurface(
                                    shape = RoundedCornerShape(RedCornerRadius.window),
                                    fallbackColor = overallColor.copy(alpha = 0.12f),
                                    fallbackBorder = BorderStroke(1.dp, overallColor.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth(),
                                    style = LiquidGlassDefaults.Card,
                                    fallbackShadowElevation = RedElevation.none,
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
                                                    text = if (isFa) "• موقعیت: ${TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.4f°", observerLat))}, ${TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.4f°", observerLon))} • ${TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.0f", observerHeightM))} متر"
                                                    else "• Location: ${String.format(Locale.US, "%.4f°", observerLat)}, ${String.format(Locale.US, "%.4f°", observerLon)} • ${String.format(Locale.US, "%.0f", observerHeightM)} m",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = RedTheme.colors.textPrimary
                                                )
                                                ArLiveSensorLines(sensorStatus, atmosphereReading, isFa)
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = if (isFa) "• وضعیت کالیبراسیون سنسور: ${calibrationLabel(calibrationState, true)}" else "• Sensor Calibration: ${calibrationLabel(calibrationState, false)}",
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
                                LiquidGlassSurface(
                                    shape = RoundedCornerShape(RedCornerRadius.window),
                                    fallbackColor = RedTheme.colors.surfaceElevated,
                                    fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border),
                                    modifier = Modifier.fillMaxWidth(),
                                    style = LiquidGlassDefaults.Card,
                                    fallbackShadowElevation = RedElevation.none,
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
                                                            val yawFmt = String.format(Locale.US, "%+.1f°", arCalibrationOffsets.yawOffsetDeg)
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

            // Rise / set of the body's true (moving) apparent place for this observer, off the UI thread.
            var riseSet by remember(obj.id) { mutableStateOf<ArScreenSupport.RiseSet?>(null) }
            val riseSetHourKey = activeTimeMs / 3_600_000L
            LaunchedEffect(obj.id, riseSetHourKey, observerGridKey, uiState.userLocation.timezoneId) {
                val o = observerForSky
                val t = activeTimeMs
                val tz = uiState.userLocation.timezoneId
                riseSet = withContext(Dispatchers.Default) {
                    arBackground("Rise/set of ${obj.id}") { ArScreenSupport.riseSet(obj, t, o, tz) }
                }
            }
            val satInfo = if (obj.type == ObjectType.SATELLITE || obj.id.startsWith("sat_")) {
                arSatellites.firstOrNull { ArScreenSupport.satelliteIdsFor(it).contains(obj.id) }
            } else null
            val cardTimeZone = TimeEngine.resolveTimeZone(uiState.userLocation.timezoneId)
            fun fmtTime(ms: Long?): String = if (ms == null) "—" else TimeEngine.formatTime24h(ms, isFa, cardTimeZone)
            val riseText: String
            val setText: String
            val rsNow = riseSet
            when {
                satInfo != null -> {
                    val tle = satInfo.tle
                    val ageDays = tle?.epochAgeDays ?: Double.NaN
                    val source = when (tle?.source) {
                        TleDataSource.LIVE_NETWORK -> if (isFa) "زنده" else "live"
                        TleDataSource.PERSISTED_CACHE, TleDataSource.MEMORY_CACHE -> if (isFa) "ذخیره" else "cached"
                        TleDataSource.HARDCODED_FALLBACK -> if (isFa) "داخلی" else "built-in"
                        null -> "—"
                    }
                    val ageStr = if (ageDays.isFinite()) String.format(Locale.US, "%.1f", abs(ageDays)) else "—"
                    riseText = if (isFa) "TLE: ${TimeEngine.formatPersianNumbers(ageStr)} روز ($source)" else "TLE: $ageStr d old ($source)"
                    setText = if (isFa) {
                        "ارتفاع ${TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.0f", satInfo.altitudeDeg))}° · " +
                            (if (satInfo.sunlit) "روشن" else "در سایه زمین")
                    } else {
                        "Alt ${String.format(Locale.US, "%.0f", satInfo.altitudeDeg)}° · " + (if (satInfo.sunlit) "sunlit" else "in Earth's shadow")
                    }
                }
                rsNow == null -> {
                    riseText = if (isFa) "طلوع: …" else "Rise: …"
                    setText = if (isFa) "غروب: …" else "Set: …"
                }
                rsNow.alwaysUp -> {
                    riseText = if (isFa) "همیشه بالای افق" else "Always above horizon"
                    setText = ""
                }
                rsNow.neverUp -> {
                    riseText = if (isFa) "امروز طلوع نمی‌کند" else "Does not rise today"
                    setText = ""
                }
                else -> {
                    riseText = if (isFa) "طلوع: ${fmtTime(rsNow.riseMs)}" else "Rise: ${fmtTime(rsNow.riseMs)}"
                    setText = if (isFa) "غروب: ${fmtTime(rsNow.setMs)}" else "Set: ${fmtTime(rsNow.setMs)}"
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val localDensity = LocalDensity.current
                val densityVal = localDensity.density

                val screenWidthPx = constraints.maxWidth.toFloat()
                val screenHeightPx = constraints.maxHeight.toFloat()
                val canvasWidth = screenWidthPx
                val canvasHeight = screenHeightPx

                // Anchor the card at the object exactly where the overlay drew it this frame.
                val drawnItem = overlayState.items.firstOrNull { it.obj.id == obj.id }
                val projected = drawnItem?.let { Offset(it.x, it.y) } ?: run {
                    val pl = placementOf(obj)
                    val cam = composeCameraModel
                    if (pl != null && cam != null) {
                        val out = FloatArray(2)
                        if (cam.project(pl.enu[0], pl.enu[1], pl.enu[2], out)) Offset(out[0], out[1]) else null
                    } else null
                }
                val px = projected?.x?.coerceIn(0f, canvasWidth) ?: (canvasWidth / 2f)
                val py = projected?.y?.coerceIn(0f, canvasHeight) ?: (canvasHeight / 2f)

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

                            val cardCalculatedState = remember(obj.id, activeTimeMs / 1000L, observerLat, observerLon) {
                                AstroDispatchEngine.calculateState(
                                    idOrAlias = obj.id,
                                    timestampMs = activeTimeMs,
                                    userLatDeg = observerLat,
                                    userLonDeg = observerLon,
                                    elevationM = observerHeightM
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
                                    text = riseText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RedTheme.colors.textSecondary
                                )
                                Text(
                                    text = setText,
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
            LiquidGlassSurface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-140).dp)
                    .testTag("ar_object_found_banner"),
                shape = RoundedCornerShape(RedCornerRadius.window),
                fallbackColor = Color(0xDD0D1B2A),
                fallbackBorder = BorderStroke(1.dp, Color(0xFF4CAF50)),
                style = LiquidGlassDefaults.Card,
                fallbackShadowElevation = RedElevation.none,
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
            val offStr = String.format(Locale.US, "%+.1f°", applied)
            LiquidGlassSurface(
                shape = RoundedCornerShape(RedCornerRadius.window),
                fallbackColor = Color(0xDD0D1B2A),
                fallbackBorder = BorderStroke(1.dp, StatusGood),
                fallbackShadowElevation = 12.dp,
                modifier = Modifier.testTag("alignment_confirmation_pill"),
                style = LiquidGlassDefaults.Card,
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

        // Layer 7c: alignment rejected (implausibly large correction)
        AnimatedVisibility(
            visible = alignmentRejected,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 120.dp, start = 16.dp, end = 16.dp)
        ) {
            LiquidGlassSurface(
                shape = RoundedCornerShape(RedCornerRadius.window),
                fallbackColor = Color(0xDD2A0D0D),
                fallbackBorder = BorderStroke(1.dp, Color(0xFFF59E0B)),
                fallbackShadowElevation = 12.dp,
                modifier = Modifier.testTag("alignment_rejected_pill"),
                style = LiquidGlassDefaults.Card,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                    Text(
                        text = if (isFa) "تصحیح بیش از ۴۵° است؛ هدف درست را در مرکز قرار دهید یا ابتدا قطب‌نما را کالیبره کنید."
                        else "Correction over 45° — centre the right object, or calibrate the compass first.",
                        style = MaterialTheme.typography.labelMedium,
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
                        // currentAzimuth is the displayed (already corrected) heading, so the manager
                        // accumulates the residual onto the active offset.
                        val applied = ARCalibrationManager.applyOnePointAlignment(
                            targetAzimuthDeg = target.azimuthDeg,
                            currentAzimuthDeg = currentAzimuth,
                            referenceName = if (isFa) target.nameFa else target.nameEn,
                            context = context,
                            latitude = observerLat,
                            longitude = observerLon
                        )
                        if (applied.isNaN()) {
                            // Total correction would exceed the plausible compass error: not applied.
                            alignmentRejected = true
                            alignmentConfirmationDeg = null
                        } else {
                            alignmentConfirmationDeg = applied
                            isAlignmentMode = false
                        }
                    }
                },
                onCancel = { isAlignmentMode = false },
                onReset = { ARCalibrationManager.resetAlignment(context) }
            )
        }

        // Layer 9: Automatic / Manual Figure-8 Sensor Calibration window. Once shown it stays open
        // (latched) until the user closes it, whatever the live calibration state does meanwhile.
        if (showManualSensorPrompt || autoCalibrationWindowOpen) {
            ARSensorCalibrationDialog(
                calibrationState = calibrationState,
                isFa = isFa,
                onDismiss = {
                    if (calibrationState == CalibrationState.GOOD || calibrationState == CalibrationState.EXCELLENT) {
                        ARCalibrationManager.noteCalibrationCompleted(
                            context = context,
                            latitude = observerLat,
                            longitude = observerLon
                        )
                    }
                    autoPromptDismissedThisSession = true
                    autoCalibrationWindowOpen = false
                    showManualSensorPrompt = false
                },
                onDisableAutoPrompt = {
                    ARCalibrationManager.setAutoPromptEnabled(false, context)
                },
                status = sensorStatus
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
    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RedCornerRadius.window),
        fallbackColor = Color(0xDD1A0B12),
        fallbackBorder = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.7f)),
        fallbackShadowElevation = 12.dp,
        style = LiquidGlassDefaults.Card,
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
    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RedCornerRadius.window),
        fallbackColor = Color(0xDD2A1800),
        fallbackBorder = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.7f)),
        fallbackShadowElevation = 12.dp,
        style = LiquidGlassDefaults.Card,
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
    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RedCornerRadius.window),
        fallbackColor = Color.Black.copy(alpha = 0.78f),
        fallbackBorder = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.55f)),
        fallbackShadowElevation = 10.dp,
        style = LiquidGlassDefaults.Card,
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
    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RedCornerRadius.window),
        fallbackColor = RedTheme.colors.surfaceElevated.copy(alpha = 0.96f),
        fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border),
        fallbackShadowElevation = 12.dp,
        style = LiquidGlassDefaults.Card,
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
                val sepStr = String.format(Locale.US, "%.1f°", guidance.separationDeg)
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
                                val altStr = String.format(Locale.US, "%+.0f°", target.altitudeDeg)
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
                        val azStr = String.format(Locale.US, "%.0f°", target.azimuthDeg)
                        val altStr = String.format(Locale.US, "%+.0f°", target.altitudeDeg)
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
                val offStr = String.format(Locale.US, "%+.1f°", activeOffsetDeg)
                Text(
                    text = if (isFa) "آفست فعال: ${TimeEngine.formatPersianNumbers(offStr)}" else "Active offset: $offStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}
