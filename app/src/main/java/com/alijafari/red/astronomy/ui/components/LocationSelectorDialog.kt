package com.alijafari.red.astronomy.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.alijafari.red.astronomy.data.catalog.GeoCity
import com.alijafari.red.astronomy.data.catalog.GeoLocationCatalog
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.domain.UserLocation
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.theme.*
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.kyant.backdrop.Backdrop
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSelectorDialog(
    backdrop: Backdrop? = null,
    uiState: MainUiState,
    onDismiss: () -> Unit,
    onSelectLocation: (GeoCity) -> Unit,
    onSelectCoordinates: (lat: Double, lon: Double, elevation: Double, nameEn: String, nameFa: String) -> Unit,
    onGpsSelected: (lat: Double, lon: Double, alt: Double?) -> Unit,
    onToggleFavorite: (GeoCity) -> Unit,
    onRemoveFavorite: (String) -> Unit
) {
    val isFa = uiState.language == AppLanguage.PERSIAN
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var searchQuery by remember { mutableStateOf(uiState.locationSearchQuery) }
    var isManualExpanded by remember { mutableStateOf(false) }

    // Manual coordinates state
    var manualLat by remember { mutableStateOf("") }
    var manualLon by remember { mutableStateOf("") }
    var manualElev by remember { mutableStateOf("") }
    var manualName by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }

    // GPS locating feedback
    var isGpsFetching by remember { mutableStateOf(false) }
    var gpsError by remember { mutableStateOf<String?>(null) }

    val filteredCities = remember(searchQuery) {
        GeoLocationCatalog.search(searchQuery, limit = 50)
    }

    // Permission launcher for GPS
    @SuppressLint("MissingPermission")
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            isGpsFetching = true
            gpsError = null
            fetchDeviceLocation(context) { loc ->
                isGpsFetching = false
                if (loc != null) {
                    onGpsSelected(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null)
                } else {
                    gpsError = if (isFa) "موقعیت GPS دریافت نشد. لطفاً مکان‌یاب دستگاه را روشن کنید." else "Could not acquire GPS fix. Please turn on device location."
                }
            }
        } else {
            gpsError = if (isFa) "مجوز دسترسی به موقعیت مکانی داده نشد." else "Location permission was denied."
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            // LOCATION SELECTION WINDOW BACKDROP → Liquid Glass (blur strength: 1 / 24)
            LiquidGlassSurface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.88f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {} // Intercept clicks so window doesn't dismiss
                    )
                    .testTag("location_selector_glass_window"),
                backdrop = backdrop,
                shape = squircleShape(RedCornerRadius.xxl),
                style = LiquidGlassDefaults.Window,
                glassTint = RedTheme.colors.surfaceElevated.copy(alpha = if (RedTheme.colors.isDark) 0.65f else 0.75f),
                glassBorder = BorderStroke(0.75.dp, RedTheme.colors.border.copy(alpha = 0.5f)),
                fallbackColor = RedTheme.colors.surfaceElevated,
                fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border),
                fallbackShadowElevation = RedElevation.modal
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = RedSpacing.lg)
                ) {
                    // Header with Title, Subtitle, and Close Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = RedSpacing.lg),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (isFa) "انتخاب موقعیت رصد" else "Observation Location",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = if (isFa) IranSans else null,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = RedTheme.colors.textPrimary
                            )
                            Text(
                                text = if (isFa) "پایگاه داده آفلاین رصد و شهرهای جهان" else "Offline-first Astronomy Geo Database",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = if (isFa) IranSans else null
                                ),
                                color = RedTheme.colors.textSecondary
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("close_location_dialog_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = if (isFa) "بستن" else "Close",
                                tint = RedTheme.colors.textPrimary,
                                modifier = Modifier.size(RedIconSize.md)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(RedSpacing.md))

                    // 1. MINIMAL SEARCH BAR (Sticky at Top)
                    OutlinedTextField(
                        textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = RedSpacing.lg)
                            .testTag("location_search_input"),
                        placeholder = {
                            Text(
                                text = if (isFa) "جستجوی شهر، استان، کشور (فارسی / انگلیسی)..." else "Search city, province, country...",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = if (isFa) IranSans else null
                                ),
                                color = RedTheme.colors.textTertiary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = RedTheme.colors.textSecondary,
                                modifier = Modifier.size(RedIconSize.sm)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = if (isFa) "پاک کردن" else "Clear",
                                        tint = RedTheme.colors.textSecondary,
                                        modifier = Modifier.size(RedIconSize.xs)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = squircleShape(RedCornerRadius.md),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RedTheme.colors.accentRed,
                            unfocusedBorderColor = RedTheme.colors.border,
                            focusedContainerColor = RedTheme.colors.surfaceGrouped.copy(alpha = if (RedTheme.colors.isDark) 0.50f else 0.60f),
                            unfocusedContainerColor = RedTheme.colors.surfaceGrouped.copy(alpha = if (RedTheme.colors.isDark) 0.35f else 0.45f),
                            focusedTextColor = RedTheme.colors.textPrimary,
                            unfocusedTextColor = RedTheme.colors.textPrimary,
                            cursorColor = RedTheme.colors.accentRed
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                    )

                    Spacer(modifier = Modifier.height(RedSpacing.sm))

                    // Scrollable Content List
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentPadding = PaddingValues(horizontal = RedSpacing.lg, vertical = RedSpacing.xs),
                        verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
                    ) {
                        // 2. GPS / LIVE DEVICE LOCATION CARD (Liquid Glass: 12 / 24)
                        item(key = "gps_device_location") {
                            LiquidGlassSurface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("gps_location_card"),
                                backdrop = backdrop,
                                onClick = {
                                    val fineGranted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    ) == PackageManager.PERMISSION_GRANTED
                                    val coarseGranted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (fineGranted || coarseGranted) {
                                        isGpsFetching = true
                                        gpsError = null
                                        fetchDeviceLocation(context) { loc ->
                                            isGpsFetching = false
                                            if (loc != null) {
                                                onGpsSelected(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null)
                                            } else {
                                                gpsError = if (isFa) "موقعیت GPS دریافت نشد." else "Could not acquire GPS location."
                                            }
                                        }
                                    } else {
                                        locationPermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }
                                },
                                shape = squircleShape(RedCornerRadius.window),
                                style = LiquidGlassDefaults.LocationCard,
                                glassTint = RedTheme.colors.surfaceGrouped.copy(alpha = if (RedTheme.colors.isDark) 0.40f else 0.50f),
                                glassBorder = BorderStroke(0.75.dp, RedTheme.colors.border.copy(alpha = 0.35f)),
                                fallbackColor = RedTheme.colors.surfaceElevated,
                                fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border),
                                fallbackShadowElevation = RedElevation.card
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = RedSpacing.md, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .background(RedTheme.colors.accentRedSubtle, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isGpsFetching || uiState.isGpsLocating) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = RedTheme.colors.accentRed
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.MyLocation,
                                                contentDescription = null,
                                                tint = RedTheme.colors.accentRed,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isFa) "موقعیت زنده GPS دستگاه" else "Use Live GPS Location",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontFamily = if (isFa) IranSans else null,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = RedTheme.colors.textPrimary
                                        )
                                        Text(
                                            text = if (isFa) "تشخیص آفلاین نزدیک‌ترین شهر + سنسور ارتفاع" else "Offline nearest-city lookup + live coordinates",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = if (isFa) IranSans else null,
                                                fontSize = 11.sp
                                            ),
                                            color = RedTheme.colors.textSecondary
                                        )
                                        if (gpsError != null) {
                                            Text(
                                                text = gpsError ?: "",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = if (isFa) IranSans else null
                                                ),
                                                color = RedTheme.colors.statusError
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. FAVORITES (SHOWN WHEN USER HAS FAVORITES, MAX 5)
                        if (uiState.favoriteLocations.isNotEmpty()) {
                            item(key = "favorites_header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFFB800),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (isFa) "مکان‌های برگزیده (${uiState.favoriteLocations.size} از ۵)" else "Favourites (${uiState.favoriteLocations.size}/5)",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = if (isFa) IranSans else null,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = RedTheme.colors.textSecondary
                                    )
                                }
                            }

                            items(uiState.favoriteLocations, key = { "fav_${it.id}" }) { favCity ->
                                val isSelected = isLocationMatching(uiState.userLocation, favCity)
                                CityItemCard(
                                    city = favCity,
                                    isSelected = isSelected,
                                    isFavorite = true,
                                    isFa = isFa,
                                    backdrop = backdrop,
                                    onSelect = { onSelectLocation(favCity) },
                                    onToggleFavorite = { onRemoveFavorite(favCity.id) }
                                )
                            }
                        }

                        // 4. SEARCH RESULTS / ALL CITIES & CAPITALS
                        item(key = "catalog_header") {
                            val headerTitle = if (searchQuery.isNotBlank()) {
                                if (isFa) "نتایج جستجو (${filteredCities.size} مورد)" else "Search Results (${filteredCities.size} found)"
                            } else {
                                if (isFa) "همه شهرها و پایتخت‌ها" else "All Cities & World Capitals"
                            }
                            Text(
                                text = headerTitle,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = if (isFa) IranSans else null,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = RedTheme.colors.textSecondary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                        }

                        if (filteredCities.isEmpty()) {
                            item(key = "no_search_results") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (isFa) "شهری با این نام پیدا نشد. می‌توانید مختصات را دستی وارد کنید." else "No cities match query. You can enter manual coordinates below.",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = if (isFa) IranSans else null
                                        ),
                                        color = RedTheme.colors.textSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            items(filteredCities, key = { "city_${it.id}" }) { city ->
                                val isSelected = isLocationMatching(uiState.userLocation, city)
                                val isFav = uiState.favoriteLocations.any { it.id == city.id }
                                CityItemCard(
                                    city = city,
                                    isSelected = isSelected,
                                    isFavorite = isFav,
                                    isFa = isFa,
                                    backdrop = backdrop,
                                    onSelect = { onSelectLocation(city) },
                                    onToggleFavorite = { onToggleFavorite(city) }
                                )
                            }
                        }

                        // 5. MANUAL COORDINATES (EXPANDABLE CARD AT BOTTOM)
                        item(key = "manual_coordinates_section") {
                            LiquidGlassSurface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manual_coordinates_card"),
                                backdrop = backdrop,
                                shape = squircleShape(RedCornerRadius.window),
                                style = LiquidGlassDefaults.LocationCard,
                                glassTint = RedTheme.colors.surfaceGrouped.copy(alpha = if (RedTheme.colors.isDark) 0.35f else 0.45f),
                                glassBorder = BorderStroke(0.75.dp, RedTheme.colors.border.copy(alpha = 0.35f)),
                                fallbackColor = RedTheme.colors.surfaceElevated,
                                fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border),
                                fallbackShadowElevation = RedElevation.card
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = RedSpacing.md, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { isManualExpanded = !isManualExpanded },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.EditLocation,
                                                contentDescription = null,
                                                tint = RedTheme.colors.textSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = if (isFa) "ورود دستی مختصات جغرافیایی" else "Enter Coordinates Manually",
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontFamily = if (isFa) IranSans else null,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = RedTheme.colors.textPrimary
                                            )
                                        }
                                        IconButton(
                                            onClick = { isManualExpanded = !isManualExpanded },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isManualExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = RedTheme.colors.textSecondary
                                            )
                                        }
                                    }

                                    if (isManualExpanded) {
                                        Spacer(modifier = Modifier.height(10.dp))

                                        OutlinedTextField(
                                            textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                                            value = manualLat,
                                            onValueChange = { manualLat = it; manualError = null },
                                            label = { Text(if (isFa) "عرض جغرافیایی (-۹۰ تا +۹۰)" else "Latitude (-90.0 to +90.0)") },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("manual_lat_input"),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            singleLine = true,
                                            shape = squircleShape(RedCornerRadius.sm),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = RedTheme.colors.accentRed,
                                                unfocusedBorderColor = RedTheme.colors.border,
                                                focusedTextColor = RedTheme.colors.textPrimary,
                                                unfocusedTextColor = RedTheme.colors.textPrimary
                                            )
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        OutlinedTextField(
                                            textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                                            value = manualLon,
                                            onValueChange = { manualLon = it; manualError = null },
                                            label = { Text(if (isFa) "طول جغرافیایی (-۱۸۰ تا +۱۸۰)" else "Longitude (-180.0 to +180.0)") },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("manual_lon_input"),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            singleLine = true,
                                            shape = squircleShape(RedCornerRadius.sm),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = RedTheme.colors.accentRed,
                                                unfocusedBorderColor = RedTheme.colors.border,
                                                focusedTextColor = RedTheme.colors.textPrimary,
                                                unfocusedTextColor = RedTheme.colors.textPrimary
                                            )
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                                                value = manualElev,
                                                onValueChange = { manualElev = it },
                                                label = { Text(if (isFa) "ارتفاع (متر)" else "Elevation (m)") },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("manual_elev_input"),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                shape = squircleShape(RedCornerRadius.sm),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = RedTheme.colors.accentRed,
                                                    unfocusedBorderColor = RedTheme.colors.border,
                                                    focusedTextColor = RedTheme.colors.textPrimary,
                                                    unfocusedTextColor = RedTheme.colors.textPrimary
                                                )
                                            )
                                            OutlinedTextField(
                                                textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                                                value = manualName,
                                                onValueChange = { manualName = it },
                                                label = { Text(if (isFa) "نام مکان" else "Name") },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("manual_name_input"),
                                                singleLine = true,
                                                shape = squircleShape(RedCornerRadius.sm),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = RedTheme.colors.accentRed,
                                                    unfocusedBorderColor = RedTheme.colors.border,
                                                    focusedTextColor = RedTheme.colors.textPrimary,
                                                    unfocusedTextColor = RedTheme.colors.textPrimary
                                                )
                                            )
                                        }

                                        if (manualError != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = manualError ?: "",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = if (isFa) IranSans else null
                                                ),
                                                color = RedTheme.colors.statusError
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Button(
                                            onClick = {
                                                val lat = manualLat.toDoubleOrNull()
                                                val lon = manualLon.toDoubleOrNull()
                                                val elev = manualElev.toDoubleOrNull() ?: 0.0

                                                if (lat == null || lat < -90.0 || lat > 90.0) {
                                                    manualError = if (isFa) "عرض جغرافیایی باید عددی بین -۹۰ و +۹۰ باشد." else "Latitude must be between -90 and +90."
                                                    return@Button
                                                }
                                                if (lon == null || lon < -180.0 || lon > 180.0) {
                                                    manualError = if (isFa) "طول جغرافیایی باید عددی بین -۱۸۰ و +۱۸۰ باشد." else "Longitude must be between -180 and +180."
                                                    return@Button
                                                }

                                                val customName = manualName.trim().ifEmpty {
                                                    if (isFa) "موقعیت سفارشی (${String.format(Locale.US, "%.2f, %.2f", lat, lon)})"
                                                    else "Custom Location (${String.format(Locale.US, "%.2f, %.2f", lat, lon)})"
                                                }

                                                onSelectCoordinates(lat, lon, elev, customName, customName)
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .testTag("apply_manual_coordinates_button"),
                                            shape = squircleShape(RedCornerRadius.md),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = RedTheme.colors.accentRed,
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isFa) "ثبت و اعمال مختصات" else "Apply Coordinates",
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontFamily = if (isFa) IranSans else null,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual Location Item Card rendered with Liquid Glass (12 / 24 blur strength)
 * and special dynamic contrast-aware royal treatment for Nurabad (NC).
 */
@Composable
private fun CityItemCard(
    city: GeoCity,
    isSelected: Boolean,
    isFavorite: Boolean,
    isFa: Boolean,
    backdrop: Backdrop? = null,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val isNurabad = city.id == GeoLocationCatalog.NURABAD_CITY.id || city.nameEn.contains("Nurabad", ignoreCase = true)
    val isDark = RedTheme.colors.isDark

    // Subtle, elegant shimmer transition for Nurabad (NC)
    val royalShimmerTransition = rememberInfiniteTransition(label = "nurabadRoyalShimmer")
    val royalShimmerAlpha by royalShimmerTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "royalAlpha"
    )

    // Dynamic contrast-aware Royal Color:
    // Dark/OLED mode: Warm rich royal gold (WCAG > 7:1)
    // Light mode: High-contrast deep royal plum/burgundy with pristine readability on light surfaces
    val nurabadTitleColor = when {
        isDark -> RedTheme.colors.accentGold.copy(alpha = royalShimmerAlpha)
        else -> Color(0xFF7A1C30)
    }

    val cardBorder = if (isSelected) {
        BorderStroke(1.2.dp, RedTheme.colors.accentRed.copy(alpha = 0.85f))
    } else if (isNurabad) {
        BorderStroke(0.75.dp, if (isDark) RedTheme.colors.accentGold.copy(alpha = 0.45f) else Color(0xFF7A1C30).copy(alpha = 0.35f))
    } else {
        BorderStroke(0.75.dp, RedTheme.colors.border.copy(alpha = 0.35f))
    }

    val cardGlassTint = when {
        isSelected -> RedTheme.colors.accentRedSubtle.copy(alpha = if (isDark) 0.22f else 0.15f)
        isNurabad -> if (isDark) RedTheme.colors.accentGold.copy(alpha = 0.08f) else Color(0xFF7A1C30).copy(alpha = 0.05f)
        else -> RedTheme.colors.surfaceGrouped.copy(alpha = if (isDark) 0.40f else 0.50f)
    }

    val cardFallbackColor = when {
        isSelected -> RedTheme.colors.surfaceGrouped
        isNurabad -> RedTheme.colors.surfaceGrouped
        else -> RedTheme.colors.surfaceElevated
    }

    LiquidGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("city_card_${city.id}"),
        backdrop = backdrop,
        onClick = onSelect,
        shape = squircleShape(RedCornerRadius.window),
        style = LiquidGlassDefaults.LocationCard,
        glassTint = cardGlassTint,
        glassBorder = cardBorder,
        fallbackColor = cardFallbackColor,
        fallbackBorder = cardBorder,
        fallbackShadowElevation = RedElevation.card
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RedSpacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isFa) city.nameFa else city.nameEn,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = if (isFa) IranSans else null,
                            fontWeight = if (isNurabad) FontWeight.ExtraBold else FontWeight.Bold
                        ),
                        color = if (isNurabad) nurabadTitleColor else RedTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isNurabad) {
                        Surface(
                            color = if (isDark) RedTheme.colors.accentGold.copy(alpha = 0.18f) else Color(0xFF7A1C30).copy(alpha = 0.12f),
                            shape = squircleShape(RedCornerRadius.xs)
                        ) {
                            Text(
                                text = "NC",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold
                                ),
                                color = if (isDark) RedTheme.colors.accentGold else Color(0xFF7A1C30)
                            )
                        }
                    } else if (city.isCapital) {
                        Surface(
                            color = RedTheme.colors.surfaceVariant.copy(alpha = 0.6f),
                            shape = squircleShape(RedCornerRadius.xs)
                        ) {
                            Text(
                                text = if (isFa) "مرکز" else "Capital",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = RedTheme.colors.textSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                val subInfo = buildString {
                    if (city.provinceFa.isNotBlank() && isFa) {
                        append(city.provinceFa)
                        append("، ")
                    } else if (city.provinceEn.isNotBlank() && !isFa) {
                        append(city.provinceEn)
                        append(", ")
                    }
                    append(if (isFa) city.countryFa else city.countryEn)
                    append(" • ")
                    append(String.format(Locale.US, "%.2f°N, %.2f°E", city.latitude, city.longitude))
                    if (city.elevationMeters > 0) {
                        append(" • ")
                        append(city.elevationMeters.toInt())
                        append(if (isFa) "متر" else "m")
                    }
                }

                Text(
                    text = subInfo,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = if (isFa) IranSans else null,
                        fontSize = 11.sp
                    ),
                    color = RedTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("fav_btn_${city.id}")
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isFa) "نشان‌گذاری" else "Favorite",
                        tint = if (isFavorite) Color(0xFFFFB800) else RedTheme.colors.textTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = RedTheme.colors.accentRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun fetchDeviceLocation(context: Context, onLocationResult: (Location?) -> Unit) {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onLocationResult(null)
            return
        }

        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        val lastGps = if (isGpsEnabled) locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
        val lastNetwork = if (isNetworkEnabled) locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
        val lastPassive = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

        val bestLastKnown = listOfNotNull(lastGps, lastNetwork, lastPassive).maxByOrNull { it.time }
        if (bestLastKnown != null) {
            onLocationResult(bestLastKnown)
            return
        }

        // If no last known location, fallback
        onLocationResult(null)
    } catch (e: Exception) {
        onLocationResult(null)
    }
}

private fun isLocationMatching(userLoc: UserLocation, city: GeoCity): Boolean {
    val dist = GeoLocationCatalog.distanceKm(userLoc.latitude, userLoc.longitude, city.latitude, city.longitude)
    return dist < 1.0 || (userLoc.cityNameEn.equals(city.nameEn, ignoreCase = true) && dist < 25.0)
}
