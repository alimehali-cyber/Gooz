package com.alijafari.red.astronomy.ui.components

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextStyle
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
import com.alijafari.red.astronomy.data.catalog.PhysicalData
import com.alijafari.red.astronomy.domain.*
import com.alijafari.red.astronomy.notification.AstroNotificationManager
import com.alijafari.red.astronomy.ui.*
import com.alijafari.red.astronomy.ui.theme.*
import java.util.Locale
import kotlin.math.tan
import com.alijafari.red.astronomy.astro_engine.CoordinateEngineLegacy

private fun isDetailDeepSky(type: ObjectType): Boolean {
    return type == ObjectType.DEEP_SKY ||
            type == ObjectType.GALAXY ||
            type == ObjectType.NEBULA ||
            type == ObjectType.STAR_CLUSTER ||
            type == ObjectType.GLOBULAR_CLUSTER ||
            type == ObjectType.BLACK_HOLE
}

fun objectDetailSectionPlan(type: ObjectType, factCount: Int): List<String> = buildList {
    add("header")
    add("observability_score")
    add("locate_in_ar")
    add("derived_physical_properties")
    if (isDetailDeepSky(type)) add("deep_sky_catalog_data")
    add("precise_schedule")
    if (factCount > 0) add("facts_card")
    add("description")
    add("coordinates")
    add("observation_log")
}

fun objectDetailFactsHeader(count: Int, isFa: Boolean): String =
    if (isFa) {
        "${TimeEngine.formatPersianNumbers(count.toString())} حقیقت شگفت‌انگیز و علمی"
    } else {
        "$count Verified Facts & Stories"
    }

private fun formatAngularSizeArcmin(value: Double?, isFa: Boolean): String? {
    val arcmin = value?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val text = if (arcmin >= 60.0) {
        String.format(Locale.US, "%.2f° (%.0f arcmin)", arcmin / 60.0, arcmin)
    } else {
        String.format(Locale.US, "%.1f arcmin", arcmin)
    }
    return if (isFa) {
        TimeEngine.formatPersianNumbers(text)
            .replace("arcmin", "دقیقه قوسی")
    } else {
        text
    }
}

private fun formatComputedLinearSize(distanceLy: Double, angularSizeArcmin: Double?, isFa: Boolean): String? {
    val arcmin = angularSizeArcmin?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    if (!distanceLy.isFinite() || distanceLy <= 0.0) return null
    val radians = Math.toRadians(arcmin / 60.0)
    val sizeLy = 2.0 * distanceLy * tan(radians / 2.0)
    if (!sizeLy.isFinite() || sizeLy <= 0.0) return null
    val sizePc = sizeLy / 3.26156
    val text = when {
        sizeLy >= 1000.0 -> String.format(Locale.US, "%,.0f ly (%,.0f pc)", sizeLy, sizePc)
        sizeLy >= 10.0 -> String.format(Locale.US, "%.0f ly (%.0f pc)", sizeLy, sizePc)
        else -> String.format(Locale.US, "%.1f ly (%.1f pc)", sizeLy, sizePc)
    }
    return if (isFa) TimeEngine.formatPersianNumbers(text).replace("ly", "سال نوری").replace("pc", "پارسک") else text
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectDetailModal(
    obj: CelestialObject,
    uiState: MainUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isFa = uiState.language == AppLanguage.PERSIAN

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
                if (isFa) "برای دریافت هشدار رصد، لطفاً مجوز اعلان را فعال کنید."
                else "Notification permission is required to receive observation alerts.",
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

    // Obtain single canonical identity from CanonicalAstroCatalog
    val canonicalObj = remember(obj) {
        CanonicalAstroCatalog.getCanonicalObject(obj.id)
            ?: CanonicalAstroCatalog.getCanonicalObject(CanonicalAstroCatalog.resolveCanonicalId(obj.id))
    }

    val timestampMs = remember(uiState.timeMachineState) {
        if (uiState.timeMachineState.mode == TimeMachineMode.SIMULATION) {
            uiState.timeMachineState.simulationTimeMs
        } else {
            System.currentTimeMillis()
        }
    }

    // Obtain dynamic astronomical state from AstroDispatchEngine
    val calculatedState = remember(canonicalObj, obj.id, uiState.userLocation, timestampMs) {
        AstroDispatchEngine.calculateState(
            idOrAlias = canonicalObj?.canonicalId ?: obj.id,
            timestampMs = timestampMs,
            userLatDeg = uiState.userLocation.latitude,
            userLonDeg = uiState.userLocation.longitude,
            elevationM = uiState.userLocation.elevationMeters
        )
    }

    val jd = remember(timestampMs) { TimeEngine.getJulianDate(timestampMs) }

    val horizAlt = calculatedState?.altitudeDeg ?: 0.0
    val horizAz = calculatedState?.azimuthDeg ?: 0.0
    val dynamicRa = calculatedState?.raDeg ?: obj.raDeg
    val dynamicDec = calculatedState?.decDeg ?: obj.decDeg
    val dynamicMag = calculatedState?.magnitude ?: obj.magnitude

    val sunPos = remember(timestampMs) { SunEngine.calculatePosition(jd) }
    val lastDeg = remember(uiState.userLocation, jd) {
        TimeEngine.getLAST(jd, uiState.userLocation.longitude)
    }
    val sunHoriz = remember(lastDeg, uiState.userLocation) {
        CoordinateEngine.equatorialToHorizontal(
            CoordinateEngine.Equatorial(sunPos.raDeg, sunPos.decDeg),
            lastDeg,
            uiState.userLocation.latitude
        )
    }
    val celestialObj = remember(canonicalObj, obj, dynamicRa, dynamicDec, dynamicMag, calculatedState) {
        if (canonicalObj != null) {
            CanonicalAstroCatalog.toCelestialObject(
                canonicalObj = canonicalObj,
                dynamicRa = dynamicRa,
                dynamicDec = dynamicDec,
                dynamicMag = dynamicMag,
                dynamicDistanceLy = calculatedState?.distanceLightYears
            )
        } else {
            obj
        }
    }

    val moonData = remember(jd, uiState.userLocation) {
        MoonEngine.calculateMoon(jd, uiState.userLocation.latitude, uiState.userLocation.longitude)
    }
    val obs = remember(horizAlt, sunHoriz, moonData, uiState.bortleClass, dynamicMag) {
        ObservabilityEngine.calculateObservability(
            altitudeDeg = horizAlt,
            sunAltitudeDeg = sunHoriz.altitudeDeg,
            moonIlluminationPercent = moonData.illuminationPercent,
            objectMagnitude = dynamicMag,
            bortleClass = uiState.bortleClass,
            objectType = celestialObj.type,
            objectId = celestialObj.id
        )
    }

    val physicalProps = remember(celestialObj) {
        PhysicalData.getPhysicalProperties(celestialObj)
    }

    val coolFacts = remember(celestialObj, isFa) {
        val catalogFacts = if (isFa) celestialObj.funFactsFa else celestialObj.funFactsEn
        when {
            catalogFacts.isNotEmpty() -> catalogFacts
            isDetailDeepSky(celestialObj.type) -> emptyList()
            isFa -> PhysicalData.getCoolFactsFa(celestialObj)
            else -> PhysicalData.getCoolFactsEn(celestialObj)
        }
    }

    val liveDistanceText = remember(celestialObj, calculatedState, isFa) {
        ARDistanceFormatter.formatDistance(celestialObj, isFa, calculatedState)
    }

    val isDeepSkyDetail = isDetailDeepSky(celestialObj.type)
    val catalogDesignationText = remember(celestialObj) {
        celestialObj.catalogDesignations.filter { it.isNotBlank() }.distinct().joinToString(" / ")
    }
    val angularSizeText = remember(celestialObj, isFa) { formatAngularSizeArcmin(celestialObj.angularSizeArcmin, isFa) }
    val linearSizeText = remember(celestialObj, isFa) {
        formatComputedLinearSize(celestialObj.distanceLightYears, celestialObj.angularSizeArcmin, isFa)
    }

    val riseSetTransit = remember(dynamicRa, dynamicDec, uiState.userLocation, jd, isFa, celestialObj.id) {
        CoordinateEngine.calculateRiseSetTransit(
            raDeg = dynamicRa,
            decDeg = dynamicDec,
            latDeg = uiState.userLocation.latitude,
            lonDeg = uiState.userLocation.longitude,
            jd = jd,
            isFa = isFa,
            timezoneId = uiState.userLocation.timezoneId,
            horizonAltDeg = CoordinateEngineLegacy.horizonAltDegFor(celestialObj.id)
        )
    }

    var showAddLogDialog by remember { mutableStateOf(false) }
    var showNotificationSheet by remember { mutableStateOf(false) }
    var showScientificCoords by remember { mutableStateOf(false) }

    var notesInput by remember { mutableStateOf("") }
    var ratingInput by remember { mutableStateOf(5) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.testTag("object_detail_modal")
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row: Name & Favorite Button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isFa) celestialObj.nameFa else celestialObj.nameEn,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (catalogDesignationText.isNotBlank()) {
                            Text(
                                text = catalogDesignationText,
                                style = MaterialTheme.typography.labelMedium,
                                color = AccentPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        val constName = if (isFa) celestialObj.constellationFa else celestialObj.constellationEn
                        val categoryText = if (isFa) {
                            canonicalObj?.observationalInfo?.categoryFa?.takeIf { it.isNotBlank() } ?: celestialObj.category
                        } else {
                            canonicalObj?.observationalInfo?.categoryEn?.takeIf { it.isNotBlank() } ?: celestialObj.category
                        }
                        Text(
                            text = "$constName • $categoryText",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = { showNotificationSheet = true },
                            modifier = Modifier.testTag("modal_notification_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Notifications,
                                contentDescription = "Set Observation Notification",
                                tint = AccentPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(
                            onClick = { viewModel.toggleCurrentDetailFavorite() },
                            modifier = Modifier.testTag("modal_favorite_button")
                        ) {
                            Icon(
                                imageVector = if (uiState.isDetailFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Favorite",
                                tint = if (uiState.isDetailFavorite) AccentPrimary else Color.Gray,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            // Observability Badge Banner
            item {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(RedCornerRadius.window),
                    fallbackColor = obs.level.color.copy(alpha = 0.15f),
                    fallbackBorder = BorderStroke(1.dp, obs.level.color.copy(alpha = 0.3f)),
                    style = LiquidGlassDefaults.Card,
                    fallbackShadowElevation = RedElevation.none,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (isFa) "امتیاز رصدپذیری لحظه‌ای" else "Live Observability Score",
                                style = MaterialTheme.typography.labelSmall,
                                color = obs.level.color
                            )
                            Text(
                                text = if (isFa) obs.level.nameFa else obs.level.nameEn,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = obs.level.color
                            )
                            Text(
                                text = if (isFa) obs.bestObservationTimeFa else obs.bestObservationTimeEn,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = "${obs.scorePercent}%",
                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                            color = obs.level.color
                        )
                    }
                }
            }

            // Locate Target in Live AR Sky Button
            item {
                Button(
                    onClick = {
                        viewModel.locateObjectInAR(celestialObj)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("locate_in_ar_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentPrimary
                    )
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = "Locate in AR",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = if (isFa) "ردیابی این جرم در آسمان‌نما (AR)" else "Locate Object in Live Sky (AR)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            // MATHEMATICALLY DERIVED PHYSICAL PROPERTIES CARD
            item {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(RedCornerRadius.window),
                    fallbackBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    style = LiquidGlassDefaults.Card,
                    fallbackShadowElevation = RedElevation.none,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = AccentPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = if (isFa) "مشخصات فیزیکی و محاسباتی مراجع" else "Derived Physical Properties",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PropertyRow(
                                label = if (isFa) "ابعاد و قطر" else "Size / Diameter",
                                value = if (isFa) physicalProps.diameterDisplayFa else physicalProps.diameterDisplayEn
                            )
                            PropertyRow(
                                label = if (isFa) "جرم تقریبی" else "Approximate Mass",
                                value = if (isFa) physicalProps.massKgDisplayFa else physicalProps.massKgDisplayEn
                            )
                            PropertyRow(
                                label = if (isFa) "گرانش سطحی" else "Surface Gravity",
                                value = if (isFa) physicalProps.gravityMssDisplayFa else physicalProps.gravityMssDisplayEn
                            )
                            PropertyRow(
                                label = if (isFa) "فاصله از زمین" else "Distance from Earth",
                                value = liveDistanceText
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = AccentPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = if (isFa) "مشخصات فیزیکی و محاسباتی مراجع" else "Derived Physical Properties",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PropertyRow(
                                label = if (isFa) "ابعاد و قطر" else "Size / Diameter",
                                value = if (isFa) physicalProps.diameterDisplayFa else physicalProps.diameterDisplayEn
                            )
                            PropertyRow(
                                label = if (isFa) "جرم تقریبی" else "Approximate Mass",
                                value = if (isFa) physicalProps.massKgDisplayFa else physicalProps.massKgDisplayEn
                            )
                            PropertyRow(
                                label = if (isFa) "گرانش سطحی" else "Surface Gravity",
                                value = if (isFa) physicalProps.gravityMssDisplayFa else physicalProps.gravityMssDisplayEn
                            )
                            PropertyRow(
                                label = if (isFa) "فاصله از زمین" else "Distance from Earth",
                                value = liveDistanceText
                            )
                        }
                    }
                }
            }

            if (isDeepSkyDetail) {
                item {
                    LiquidGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(RedCornerRadius.window),
                        fallbackBorder = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.24f)),
                        fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        style = LiquidGlassDefaults.Card,
                        fallbackShadowElevation = RedElevation.none,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(20.dp))
                                Text(
                                    text = if (isFa) "اطلاعات ژرف‌آسمان" else "Deep-Sky Catalog Data",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentPrimary
                                )
                            }
                            if (catalogDesignationText.isNotBlank()) {
                                PropertyRow(
                                    label = if (isFa) "شناسه‌های کاتالوگ" else "Catalog designations",
                                    value = catalogDesignationText
                                )
                            }
                            PropertyRow(
                                label = if (isFa) "نوع و صورت فلکی" else "Type + constellation",
                                value = if (isFa) "${celestialObj.type.nameFa} • ${celestialObj.constellationFa.ifBlank { celestialObj.constellationCode }}"
                                else "${celestialObj.type.nameEn} • ${celestialObj.constellationEn.ifBlank { celestialObj.constellationCode }}"
                            )
                            if (celestialObj.constellationCode.isNotBlank()) {
                                PropertyRow(
                                    label = if (isFa) "کد صورت فلکی" else "Constellation code",
                                    value = celestialObj.constellationCode
                                )
                            }
                            PropertyRow(
                                label = if (isFa) "قدر ظاهری" else "Magnitude",
                                value = if (isFa) TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.1f", celestialObj.magnitude)) else String.format(Locale.US, "%.1f", celestialObj.magnitude)
                            )
                            angularSizeText?.let { angular ->
                                PropertyRow(
                                    label = if (isFa) "اندازه زاویه‌ای" else "Angular size",
                                    value = angular
                                )
                            }
                            linearSizeText?.let { linear ->
                                PropertyRow(
                                    label = if (isFa) "اندازه واقعی تخمینی" else "Computed physical size",
                                    value = linear
                                )
                            }
                            PropertyRow(
                                label = if (isFa) "فاصله زنده / کاتالوگی" else "Live/catalog distance",
                                value = liveDistanceText
                            )
                            if (celestialObj.bestViewingMonthEn.isNotBlank() || celestialObj.bestViewingMonthFa.isNotBlank()) {
                                PropertyRow(
                                    label = if (isFa) "بهترین زمان مشاهده" else "Best viewing month",
                                    value = if (isFa) celestialObj.bestViewingMonthFa.ifBlank { celestialObj.bestViewingMonthEn } else celestialObj.bestViewingMonthEn.ifBlank { celestialObj.bestViewingMonthFa }
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(20.dp))
                                Text(
                                    text = if (isFa) "اطلاعات ژرف‌آسمان" else "Deep-Sky Catalog Data",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentPrimary
                                )
                            }
                            if (catalogDesignationText.isNotBlank()) {
                                PropertyRow(
                                    label = if (isFa) "شناسه‌های کاتالوگ" else "Catalog designations",
                                    value = catalogDesignationText
                                )
                            }
                            PropertyRow(
                                label = if (isFa) "نوع و صورت فلکی" else "Type + constellation",
                                value = if (isFa) "${celestialObj.type.nameFa} • ${celestialObj.constellationFa.ifBlank { celestialObj.constellationCode }}"
                                else "${celestialObj.type.nameEn} • ${celestialObj.constellationEn.ifBlank { celestialObj.constellationCode }}"
                            )
                            if (celestialObj.constellationCode.isNotBlank()) {
                                PropertyRow(
                                    label = if (isFa) "کد صورت فلکی" else "Constellation code",
                                    value = celestialObj.constellationCode
                                )
                            }
                            PropertyRow(
                                label = if (isFa) "قدر ظاهری" else "Magnitude",
                                value = if (isFa) TimeEngine.formatPersianNumbers(String.format(Locale.US, "%.1f", celestialObj.magnitude)) else String.format(Locale.US, "%.1f", celestialObj.magnitude)
                            )
                            angularSizeText?.let { angular ->
                                PropertyRow(
                                    label = if (isFa) "اندازه زاویه‌ای" else "Angular size",
                                    value = angular
                                )
                            }
                            linearSizeText?.let { linear ->
                                PropertyRow(
                                    label = if (isFa) "اندازه واقعی تخمینی" else "Computed physical size",
                                    value = linear
                                )
                            }
                            PropertyRow(
                                label = if (isFa) "فاصله زنده / کاتالوگی" else "Live/catalog distance",
                                value = liveDistanceText
                            )
                            if (celestialObj.bestViewingMonthEn.isNotBlank() || celestialObj.bestViewingMonthFa.isNotBlank()) {
                                PropertyRow(
                                    label = if (isFa) "بهترین زمان مشاهده" else "Best viewing month",
                                    value = if (isFa) celestialObj.bestViewingMonthFa.ifBlank { celestialObj.bestViewingMonthEn } else celestialObj.bestViewingMonthEn.ifBlank { celestialObj.bestViewingMonthFa }
                                )
                            }
                        }
                    }
                }
            }

            // Rise, Transit, Set Schedule Card
            item {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(RedCornerRadius.window),
                    fallbackBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    style = LiquidGlassDefaults.Card,
                    fallbackShadowElevation = RedElevation.none,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = AccentPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = if (isFa) "زمان‌بندی دقیق طلوع، ترانزیت و غروب" else "Precise Schedule Today",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = if (isFa) "طلوع" else "Rise",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = riseSetTransit.riseTimeStr,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isFa) "اوج ارتفاع (ترانزیت)" else "Peak Transit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = riseSetTransit.transitTimeStr,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AccentPrimary
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (isFa) "غروب" else "Set",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = riseSetTransit.setTimeStr,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = AccentPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = if (isFa) "زمان‌بندی دقیق طلوع، ترانزیت و غروب" else "Precise Schedule Today",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = if (isFa) "طلوع" else "Rise",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = riseSetTransit.riseTimeStr,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isFa) "اوج ارتفاع (ترانزیت)" else "Peak Transit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = riseSetTransit.transitTimeStr,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AccentPrimary
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (isFa) "غروب" else "Set",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = riseSetTransit.setTimeStr,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Optional verified / authored facts. Omitted when no facts are available.
            if (coolFacts.isNotEmpty()) {
                item {
                    LiquidGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(RedCornerRadius.window),
                        fallbackBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        style = LiquidGlassDefaults.Card,
                        fallbackShadowElevation = RedElevation.none,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = AccentPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = objectDetailFactsHeader(coolFacts.size, isFa),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentPrimary
                                )
                            }

                            coolFacts.forEachIndexed { index, fact ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = if (isFa) "${TimeEngine.formatPersianNumbers((index + 1).toString())}." else "${index + 1}.",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = AccentPrimary
                                    )
                                    Text(
                                        text = fact,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = AccentPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = objectDetailFactsHeader(coolFacts.size, isFa),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentPrimary
                                )
                            }

                            coolFacts.forEachIndexed { index, fact ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = if (isFa) "${TimeEngine.formatPersianNumbers((index + 1).toString())}." else "${index + 1}.",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = AccentPrimary
                                    )
                                    Text(
                                        text = fact,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Description
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (isFa) "توضیحات تکمیلی" else "Description",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentPrimary
                    )
                    Text(
                        text = if (isFa) celestialObj.descriptionFa else celestialObj.descriptionEn,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Scientific Coordinates (Collapsible Section - Hidden by default)
            item {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(RedCornerRadius.window),
                    fallbackBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    style = LiquidGlassDefaults.Card,
                    fallbackShadowElevation = RedElevation.none,
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showScientificCoords = !showScientificCoords },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isFa) "مختصات علمی نجومی (RA / Dec)" else "Astronomical Coordinates",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                imageVector = if (showScientificCoords) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle Coords",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (showScientificCoords) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(text = "RA (بعد)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    Text(text = CoordinateEngine.formatRA(celestialObj.raDeg), style = MaterialTheme.typography.bodyMedium)
                                }
                                Column {
                                    Text(text = "Dec (میل)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    Text(text = CoordinateEngine.formatDec(celestialObj.decDeg), style = MaterialTheme.typography.bodyMedium)
                                }
                                Column {
                                    Text(text = "Magnitude (قدر)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    Text(text = String.format("%.1f", celestialObj.magnitude), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showScientificCoords = !showScientificCoords },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isFa) "مختصات علمی نجومی (RA / Dec)" else "Astronomical Coordinates",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                imageVector = if (showScientificCoords) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle Coords",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (showScientificCoords) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(text = "RA (بعد)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    Text(text = CoordinateEngine.formatRA(celestialObj.raDeg), style = MaterialTheme.typography.bodyMedium)
                                }
                                Column {
                                    Text(text = "Dec (میل)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    Text(text = CoordinateEngine.formatDec(celestialObj.decDeg), style = MaterialTheme.typography.bodyMedium)
                                }
                                Column {
                                    Text(text = "Magnitude (قدر)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    Text(text = String.format("%.1f", celestialObj.magnitude), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            // Observation Log Button & Spacing
            item {
                OutlinedButton(
                    onClick = { showAddLogDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("add_log_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.EditNote, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = if (isFa) "ثبت در دفترچه رصد" else "Add to Observation Log")
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Observation Alert Modal Sheet
    if (showNotificationSheet) {
        AlertDialog(
            onDismissRequest = { showNotificationSheet = false },
            title = {
                Text(text = if (isFa) "🔔 تنظیم هشدار رصد ${celestialObj.nameFa}" else "🔔 Set Observation Alert for ${celestialObj.nameEn}")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (isFa) "زمان‌بندی ترانزیت (اوج ارتفاع): ${riseSetTransit.transitTimeStr}"
                        else "Transit Time: ${riseSetTransit.transitTimeStr}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Button(
                        onClick = {
                            showNotificationSheet = false
                            executeWithNotificationPermission {
                                AstroNotificationManager.scheduleObjectNotification(
                                    context = context,
                                    obj = celestialObj,
                                    targetTimeMs = System.currentTimeMillis() + 3600000L,
                                    eventTypeFa = "اوج ارتفاع (ترانزیت)",
                                    timeStr = riseSetTransit.transitTimeStr,
                                    leadTenMinutesBefore = true
                                )
                                Toast.makeText(
                                    context,
                                    if (isFa) "هشدار رصد با موفقیت تنظیم شد!" else "Observation alert scheduled!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = if (isFa) "تأیید و تنظیم هشدار (۱۰ دقیقه قبل)" else "Confirm Alert (10 min prior)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showNotificationSheet = false }) {
                    Text(text = if (isFa) "انصراف" else "Cancel")
                }
            }
        )
    }

    // Add Observation Dialog
    if (showAddLogDialog) {
        AlertDialog(
            onDismissRequest = { showAddLogDialog = false },
            title = {
                Text(text = if (isFa) "ثبت رصد جدید" else "New Observation Entry")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (isFa) "یادداشت رصد برای ${celestialObj.nameFa}:" else "Notes for ${celestialObj.nameEn}:",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    OutlinedTextField(
                        textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                        value = notesInput,
                        onValueChange = { notesInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(text = if (isFa) "شرایط جوی، تجهیزات رصدی..." else "Weather, telescope...") }
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = if (isFa) "امتیاز رصد:" else "Rating:")
                        for (star in 1..5) {
                            IconButton(onClick = { ratingInput = star }) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Star $star",
                                    tint = if (star <= ratingInput) AccentPrimary else Color.Gray
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addObservationLog(notesInput, ratingInput)
                        showAddLogDialog = false
                    }
                ) {
                    Text(text = if (isFa) "ذخیره" else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddLogDialog = false }) {
                    Text(text = if (isFa) "انصراف" else "Cancel")
                }
            }
        )
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
