package com.alijafari.red.astronomy.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.astro_engine.*
import com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.domain.UserLocation
import com.alijafari.red.astronomy.notification.AstroNotificationManager
import com.alijafari.red.astronomy.ui.theme.*
import com.alijafari.red.astronomy.util.toPersianDigits
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteDetailScreen(
    satelliteItem: SatelliteItem,
    userLocation: UserLocation,
    language: AppLanguage,
    onBack: () -> Unit,
    simulationTimestampMs: Long = System.currentTimeMillis()
) {
    // Intercept system back press
    BackHandler(enabled = true) {
        onBack()
    }

    val isFa = language == AppLanguage.PERSIAN
    val context = LocalContext.current
    val isOnline = remember(context) { isNetworkAvailable(context) }

    val canonicalSatellite = remember(satelliteItem) {
        CanonicalAstroCatalog.getCanonicalObject("sat_${satelliteItem.noradId}")
            ?: CanonicalAstroCatalog.getCanonicalObject(satelliteItem.id)
    }

    val calculatedState = remember(canonicalSatellite, satelliteItem, simulationTimestampMs, userLocation) {
        AstroDispatchEngine.calculateState(
            idOrAlias = canonicalSatellite?.canonicalId ?: "sat_${satelliteItem.noradId}",
            timestampMs = simulationTimestampMs,
            userLatDeg = userLocation.latitude,
            userLonDeg = userLocation.longitude
        )
    }

    val state = remember(calculatedState, satelliteItem, simulationTimestampMs, userLocation) {
        (calculatedState?.specializedData as? SatelliteLiveState)
            ?: SatelliteEngine.calculateSatelliteState(
                satellite = satelliteItem,
                timestampMs = simulationTimestampMs,
                userLatDeg = userLocation.latitude,
                userLonDeg = userLocation.longitude
            )
    }

    val roundedStartMs = remember(simulationTimestampMs) {
        (simulationTimestampMs / 60_000L) * 60_000L
    }

    var passes by remember {
        mutableStateOf<List<ISSEngine.ISSPass>?>(null)
    }

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
                if (isFa) "برای دریافت هشدار گذر، لطفاً مجوز اعلان را فعال کنید."
                else "Notification permission is required to receive pass alerts.",
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

    LaunchedEffect(satelliteItem.id, userLocation, roundedStartMs) {
        withContext(Dispatchers.Default) {
            val result = ISSEngine.predictPasses(
                userLatDeg = userLocation.latitude,
                userLonDeg = userLocation.longitude,
                startTimestampMs = roundedStartMs,
                tle = SatelliteEngine.getEffectiveTle(satelliteItem),
                scanDays = 7,
                visibleOnly = true,
                standardMag = satelliteItem.standardMagnitude
            )
            withContext(Dispatchers.Main) {
                passes = result
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("satellite_detail_screen"),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = RedSpacing.lg, vertical = RedSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RedSpacing.md)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(RedTheme.colors.surfaceElevated.copy(alpha = 0.7f))
                            .border(0.5.dp, RedTheme.colors.border.copy(alpha = 0.4f), CircleShape)
                            .testTag("sat_detail_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = RedTheme.colors.textPrimary,
                            modifier = Modifier.size(RedIconSize.sm)
                        )
                    }

                    Column {
                        Text(
                            text = if (isFa) satelliteItem.nameFa else satelliteItem.nameEn,
                            style = RedTypographyTokens.sectionHeading,
                            color = RedTheme.colors.textPrimary
                        )
                        Text(
                            text = if (isFa) "شناسه نوراد: ${satelliteItem.noradId} • ${satelliteItem.designation}".toPersianDigits()
                            else "NORAD ${satelliteItem.noradId} • ${satelliteItem.designation}",
                            style = RedTypographyTokens.caption,
                            color = RedTheme.colors.textSecondary
                        )
                    }
                }

                if (!isOnline) {
                    Surface(
                        shape = RoundedCornerShape(RedCornerRadius.full),
                        color = RedTheme.colors.statusError.copy(alpha = 0.15f),
                        border = BorderStroke(0.5.dp, RedTheme.colors.statusError.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WifiOff,
                                contentDescription = "Offline",
                                tint = RedTheme.colors.statusError,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = if (isFa) "آفلاین" else "Offline",
                                style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = RedTheme.colors.statusError
                            )
                        }
                    }
                }
            }
        },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RedSpacing.lg, vertical = RedSpacing.md),
            verticalArrangement = Arrangement.spacedBy(RedSpacing.lg)
        ) {
            // 1. Hero Status Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(RedCornerRadius.window),
                color = Color(0xFF0F172A),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(AccentPrimary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SatelliteAlt,
                                    contentDescription = null,
                                    tint = AccentPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = if (isFa) satelliteItem.category.labelFa else satelliteItem.category.labelEn,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AccentPrimary
                                )
                                Text(
                                    text = if (isFa) satelliteItem.nameFa else satelliteItem.nameEn,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                        }

                        // Visibility Badge (Uses "قابل مشاهده نیست" when not visible in Persian)
                        val badgeBg = if (state.isNakedEyeVisible) Color(0xFF2DC653) else Color(0xFF64748B)
                        val badgeText = if (state.isNakedEyeVisible) (if (isFa) "با چشم غیرمسلح" else "Naked Eye Visible") else (if (isFa) "قابل مشاهده نیست" else "Not Visible")
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = badgeBg.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, badgeBg.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = badgeBg,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Text(
                        text = if (isFa) satelliteItem.descriptionFa else satelliteItem.descriptionEn,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            // UPCOMING VISIBLE PASSES PREDICTIONS LIST (Chronological Order)
            Text(
                text = if (isFa) "گذرهای قابل مشاهده بعدی (۷ روز آینده)" else "Upcoming Visible Passes (Next 7 Days)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            val currentPasses = passes
            if (currentPasses == null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(RedCornerRadius.window),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ) {
                    Text(
                        text = if (isFa) "در حال محاسبه گذرهای ماهواره..." else "Calculating satellite passes...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else if (currentPasses.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(RedCornerRadius.window),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                ) {
                    Text(
                        text = if (isFa) "هیچ گذر قابل مشهودی مطابق با معیار علمی برای موقعیت شما در ۷ روز آینده یافت نشد."
                        else "No visible passes meeting scientific criteria predicted for your location in the next 7 days.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    currentPasses.forEach { pass ->
                        DetailedVisiblePassCard(
                            satName = if (isFa) satelliteItem.nameFa else satelliteItem.nameEn,
                            pass = pass,
                            cityName = userLocation.cityNameFa,
                            isFa = isFa,
                            onSchedulePassReminder = { leadMins ->
                                executeWithNotificationPermission {
                                    AstroNotificationManager.scheduleSpecificPassAlarm(
                                        context = context,
                                        satellite = satelliteItem,
                                        pass = pass,
                                        userLocation = userLocation,
                                        leadMinutes = leadMins
                                    )
                                    val labelMins = if (leadMins == 1440) (if (isFa) "۱ روز" else "1 day") else (if (isFa) "$leadMins دقیقه" else "$leadMins mins")
                                    Toast.makeText(
                                        context,
                                        if (isFa) "هشدار گذر $labelMins قبل از شروع تنظیم شد!" else "Alert scheduled $labelMins prior to pass!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }

            // 3. SCIENTIFIC MISSION & SATELLITE FACTS SECTION
            Text(
                text = if (isFa) "شناسنامه ماموریت و مشخصات علمی" else "Scientific Mission & Verified Specifications",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            val operatorText = if (isFa) {
                if (satelliteItem.operatorFa.isNotBlank()) satelliteItem.operatorFa else "ناسا / بین‌المللی"
            } else {
                if (satelliteItem.operatorEn.isNotBlank()) satelliteItem.operatorEn else "NASA / International"
            }

            val launchText = if (satelliteItem.launchDate.isNotBlank()) satelliteItem.launchDate else "1998"

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(RedCornerRadius.window),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Mission Meta Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryItem(
                            label = if (isFa) "سازمان / اپراتور" else "Operator",
                            value = operatorText
                        )
                        TelemetryItem(
                            label = if (isFa) "تاریخ پرتاب" else "Launch Date",
                            value = if (isFa) launchText.toPersianDigits() else launchText
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryItem(
                            label = if (isFa) "کد NORAD ID" else "NORAD ID",
                            value = satelliteItem.noradId.toString()
                        )
                        TelemetryItem(
                            label = if (isFa) "کد بین‌المللی" else "Int'l Designation",
                            value = satelliteItem.designation
                        )
                    }

                    if (satelliteItem.missionPurposeFa.isNotBlank() || satelliteItem.missionPurposeEn.isNotBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (isFa) "هدف ماموریت:" else "Mission Purpose:",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentPrimary
                            )
                            Text(
                                text = if (isFa) satelliteItem.missionPurposeFa else satelliteItem.missionPurposeEn,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (satelliteItem.scientificSignificanceFa.isNotBlank() || satelliteItem.scientificSignificanceEn.isNotBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (isFa) "اهمیت علمی و تاریخی:" else "Scientific & Historical Significance:",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentPrimary
                            )
                            Text(
                                text = if (isFa) satelliteItem.scientificSignificanceFa else satelliteItem.scientificSignificanceEn,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    val facts = if (isFa) satelliteItem.verifiedFactsFa else satelliteItem.verifiedFactsEn
                    if (facts.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (isFa) "حقایق علمی تاییدشده:" else "Verified Key Facts:",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentPrimary
                            )
                            facts.forEach { fact ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text("•", color = AccentPrimary, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = if (isFa) fact.toPersianDigits() else fact,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. Scientific Naked-Eye Visibility Assessment Card
            Text(
                text = if (isFa) "ارزیابی علمی قابلیت رؤیت با چشم" else "Scientific Naked-Eye Visibility Assessment",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(RedCornerRadius.window),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.25f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val magStr = String.format(Locale.US, "%+.1f", state.apparentMagnitude)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isFa) "قدر ظاهری محاسبه‌شده:" else "Calculated Apparent Magnitude:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isFa) "mag $magStr".toPersianDigits() else "mag $magStr",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = AccentPrimary
                        )
                    }

                    val reasonText = if (isFa) {
                        if (state.reasonFa.contains("غیرقابل")) "قابل مشاهده نیست" else state.reasonFa
                    } else state.reasonEn

                    Text(
                        text = reasonText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Bottom clearance for floating navigation bar
            Spacer(modifier = Modifier.height(112.dp))
        }
    }
}

/**
 * Compact visual timeline component displaying duration and status of next pass relative to now.
 */
@Composable
private fun NextPassTimelineProgressBar(
    pass: ISSEngine.ISSPass,
    nowMs: Long,
    isFa: Boolean,
    onScheduleReminder: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val riseTimeStr = sdf.format(Date(pass.startTimeMs))
    val maxTimeStr = sdf.format(Date(pass.maxTimeMs))
    val setTimeStr = sdf.format(Date(pass.endTimeMs))

    val passDurationMin = pass.passDurationSec / 60
    val startDiffMs = pass.startTimeMs - nowMs
    val endDiffMs = pass.endTimeMs - nowMs

    val isCurrentlyActive = nowMs in pass.startTimeMs..pass.endTimeMs
    val isFuture = nowMs < pass.startTimeMs

    // Progress math
    val progress: Float = when {
        isCurrentlyActive -> {
            val total = (pass.endTimeMs - pass.startTimeMs).toFloat()
            val elapsed = (nowMs - pass.startTimeMs).toFloat()
            (elapsed / total).coerceIn(0f, 1f)
        }
        isFuture -> {
            val maxWindowMs = 6 * 3600 * 1000L // 6 hour horizon
            val remaining = (pass.startTimeMs - nowMs).toFloat()
            (1f - (remaining / maxWindowMs)).coerceIn(0.05f, 1f)
        }
        else -> 1f
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RedCornerRadius.window),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, Color(pass.classification.colorHex).copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(pass.classification.colorHex))
                    )
                    Text(
                        text = if (isFa) pass.classification.labelFa else pass.classification.labelEn,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(pass.classification.colorHex)
                    )
                }

                val durationText = if (isFa) "$passDurationMin دقیقه".toPersianDigits() else "$passDurationMin min pass"
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Sub-status text
            val relativeStatusText = when {
                isCurrentlyActive -> {
                    val remMins = max(1L, (endDiffMs / 60000L))
                    if (isFa) "در حال انجام! $remMins دقیقه تا پایان".toPersianDigits() else "Pass in progress! $remMins min remaining"
                }
                isFuture -> {
                    val hrs = startDiffMs / (3600 * 1000L)
                    val mins = (startDiffMs % (3600 * 1000L)) / (60 * 1000L)
                    val cdStr = if (hrs > 0) "${hrs}h ${mins}m" else "${mins} min"
                    if (isFa) "شروع تا $cdStr دیگر".toPersianDigits() else "Starts in $cdStr"
                }
                else -> {
                    if (isFa) "پایان یافته" else "Completed"
                }
            }

            Text(
                text = relativeStatusText,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = AccentPrimary
            )

            // Visual Progress Track Bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Color(pass.classification.colorHex),
                    trackColor = Color(pass.classification.colorHex).copy(alpha = 0.2f),
                )

                // Key Timeline Markers Below Track
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (isFa) "طلوع: $riseTimeStr".toPersianDigits() else "Rise: $riseTimeStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isFa) "اوج: $maxTimeStr (${String.format(Locale.US, "%.0f°", pass.maxElevationDeg)})".toPersianDigits()
                            else "Max: $maxTimeStr (${String.format(Locale.US, "%.0f°", pass.maxElevationDeg)})",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = AccentPrimary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (isFa) "غروب: $setTimeStr".toPersianDigits() else "Set: $setTimeStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Reminder Button
            Button(
                onClick = onScheduleReminder,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("schedule_pass_alert"),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
            ) {
                Icon(imageVector = Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = if (isFa) "تنظیم یادآوری این گذر" else "Schedule Reminder Alert")
            }
        }
    }
}

@Composable
private fun TelemetryItem(
    label: String,
    value: String
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun DetailedVisiblePassCard(
    satName: String,
    pass: ISSEngine.ISSPass,
    cityName: String,
    isFa: Boolean,
    onSchedulePassReminder: (leadMinutes: Int) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val calendarSystem = if (isFa) com.alijafari.red.astronomy.domain.CalendarSystem.SOLAR_HIJRI else com.alijafari.red.astronomy.domain.CalendarSystem.GREGORIAN
    val dateStr = TimeEngine.formatDate(pass.startTimeMs, calendarSystem, isFa)
    val riseStr = TimeEngine.formatTime24h(pass.startTimeMs, isFa)
    val maxStr = TimeEngine.formatTime24h(pass.maxTimeMs, isFa)
    val setStr = TimeEngine.formatTime24h(pass.endTimeMs, isFa)

    val startDir = getAzimuthCardinal(pass.startAzimuthDeg, isFa)
    val endDir = getAzimuthCardinal(pass.endAzimuthDeg, isFa)
    val dirStr = if (isFa) "$startDir ← $endDir" else "$startDir → $endDir"

    val magStr = String.format(Locale.US, "%.1f", pass.estimatedMagnitude)
    val maxElevStr = String.format(Locale.US, "%.0f°", pass.maxElevationDeg)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RedCornerRadius.window),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, Color(pass.classification.colorHex).copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Satellite Name & Classification Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.SatelliteAlt,
                        contentDescription = null,
                        tint = AccentPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = satName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(pass.classification.colorHex).copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, Color(pass.classification.colorHex).copy(alpha = 0.5f))
                ) {
                    Text(
                        text = if (isFa) pass.classification.labelFa else pass.classification.labelEn,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(pass.classification.colorHex),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Subheader: Date & Pass Duration
            val durationMin = ((pass.endTimeMs - pass.startTimeMs) / 60000L).coerceAtLeast(1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Event,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (isFa) "مدت: ${TimeEngine.formatPersianNumbers("$durationMin")} دقیقه" else "Duration: $durationMin min",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            // Time Row: Rise, Max, Set
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (isFa) "طلوع (آغاز)" else "Rise",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = riseStr,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isFa) "اوج ($maxElevStr)" else "Max ($maxElevStr)",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentPrimary
                    )
                    Text(
                        text = maxStr,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = AccentPrimary
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isFa) "غروب (پایان)" else "Set",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = setStr,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            // Details Row: Direction, Duration, Mag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = dirStr,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isFa) "قدر: $magStr+".toPersianDigits() else "Mag: +$magStr",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF2DC653)
                    )

                    Box {
                        OutlinedButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.height(32.dp).testTag("pass_card_notify_${pass.startTimeMs}"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            border = BorderStroke(1.dp, AccentPrimary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = AccentPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isFa) "یادآوری" else "Remind",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentPrimary
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isFa) "۱۰ دقیقه قبل" else "10 mins before") },
                                onClick = {
                                    showMenu = false
                                    onSchedulePassReminder(10)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isFa) "۳۰ دقیقه قبل" else "30 mins before") },
                                onClick = {
                                    showMenu = false
                                    onSchedulePassReminder(30)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isFa) "۱ روز قبل (۲۴ ساعت)" else "1 day before (24h)") },
                                onClick = {
                                    showMenu = false
                                    onSchedulePassReminder(1440)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getAzimuthCardinal(azDeg: Double, isFa: Boolean): String {
    val normAz = (azDeg % 360 + 360) % 360
    return if (isFa) {
        when {
            normAz >= 337.5 || normAz < 22.5 -> "شمال"
            normAz < 67.5 -> "شمال‌شرقی"
            normAz < 112.5 -> "شرق"
            normAz < 157.5 -> "جنوب‌شرقی"
            normAz < 202.5 -> "جنوب"
            normAz < 247.5 -> "جنوب‌غربی"
            normAz < 292.5 -> "غرب"
            else -> "شمال‌غربی"
        }
    } else {
        when {
            normAz >= 337.5 || normAz < 22.5 -> "N"
            normAz < 67.5 -> "NE"
            normAz < 112.5 -> "E"
            normAz < 157.5 -> "SE"
            normAz < 202.5 -> "S"
            normAz < 247.5 -> "SW"
            normAz < 292.5 -> "W"
            else -> "NW"
        }
    }
}
