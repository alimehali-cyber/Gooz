package com.alijafari.red.astronomy.ui

import android.app.Application
import android.content.Context
import android.location.Geocoder
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alijafari.red.astronomy.astro_engine.AstroDispatchEngine
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.data.AppRepository
import com.alijafari.red.astronomy.data.catalog.AstronomyCatalog
import com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog
import com.alijafari.red.astronomy.data.catalog.GeoCity
import com.alijafari.red.astronomy.data.catalog.GeoLocationCatalog
import com.alijafari.red.astronomy.data.database.AppDatabase
import com.alijafari.red.astronomy.data.database.ObservationLogEntity
import com.alijafari.red.astronomy.data.database.UserOccasionEntity
import com.alijafari.red.astronomy.domain.*
import com.alijafari.red.astronomy.ui.backdrop.AppBackdropMode
import com.alijafari.red.astronomy.ui.backdrop.LiveSkyPolicy
import com.alijafari.red.astronomy.ui.backdrop.SkyLiveClock
import com.alijafari.red.astronomy.ui.theme.LiquidGlassConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class MainUiState(
    val language: AppLanguage = AppLanguage.PERSIAN,
    val calendarSystem: CalendarSystem = CalendarSystem.SOLAR_HIJRI,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val skyCanvasTheme: SkyCanvasTheme = SkyCanvasTheme.REAL_SKY,
    val userLocation: UserLocation = UserLocation(),
    val selectedTab: Int = 4,
    val searchQuery: String = "",
    val selectedObjectForDetail: CelestialObject? = null,
    val isDetailFavorite: Boolean = false,
    val showFavoritesDialog: Boolean = false,
    val showMoonWindow: Boolean = false,

    /**
     * The fullscreen sky viewer. This lives in the ViewModel rather than in HomeScreen's own state
     * because opening it locks the activity to landscape, and the app declares no `configChanges`, so
     * that rotation recreates the activity: composition-local state would be lost and the viewer would
     * slam shut the moment it opened.
     */
    val showSkyViewer: Boolean = false,
    /**
     * Lunar day offset in days (-30..+30) driven by dragging the Moon render. The Home moon card and the
     * Moon window read the same value, so scrubbing one moves the other.
     */
    val moonDayOffset: Float = 0f,
    val showSettingsDialog: Boolean = false,
    val showLocationSelector: Boolean = false,
    val showObservationLogDialog: Boolean = false,
    val bortleClass: Int = 3,
    val favoritesList: List<String> = emptyList(),
    val favoriteLocations: List<GeoCity> = emptyList(),
    val locationSearchQuery: String = "",
    val isGpsLocating: Boolean = false,
    val observationLogs: List<ObservationLogEntity> = emptyList(),
    val userOccasions: List<UserOccasionEntity> = emptyList(),
    val timeMachineState: TimeMachineState = TimeMachineState(),
    val selectedTargetObject: CelestialObject? = null,
    val selectedSatelliteId: String? = null,
    val isLiquidGlassEnabled: Boolean = true,
    val liquidGlassConfig: LiquidGlassConfig = LiquidGlassConfig(),
    /** Live Sky app backdrop preference. Default OFF. Eligibility per screen is decided by [LiveSkyPolicy]. */
    val liveSkyBackdropEnabled: Boolean = LiveSkyPolicy.DEFAULT_ENABLED,
    /** Mutually exclusive full-window backdrop. [liveSkyBackdropEnabled] is true only for [AppBackdropMode.LIVE_SKY]. */
    val appBackdropMode: AppBackdropMode = AppBackdropMode.NONE
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(AppDatabase.getDatabase(application))
    private val prefs = application.getSharedPreferences("astro_app_prefs", Context.MODE_PRIVATE)

    /** One live clock for the Home hero and the Live Sky backdrop. It runs only while something collects it. */
    val skyLiveClock = SkyLiveClock(viewModelScope)

    private val _skyDragOffsetHours = MutableStateFlow(0f)

    /**
     * The Home time-machine drag offset in hours (±12), including its animated return to zero. Home publishes it; the
     * Live Sky backdrop reads it, so both scenes use the same effective instant. Home resets it to zero when it leaves.
     */
    val skyDragOffsetHours: StateFlow<Float> = _skyDragOffsetHours.asStateFlow()

    fun setSkyDragOffsetHours(hours: Float) {
        if (_skyDragOffsetHours.value != hours) _skyDragOffsetHours.value = hours
    }

    private val _uiState = MutableStateFlow(
        MainUiState(
            isLiquidGlassEnabled = prefs.getBoolean("liquid_glass_enabled", true),
            liveSkyBackdropEnabled = LiveSkyPolicy.loadEnabled(prefs),
            appBackdropMode = AppBackdropMode.fromPrefs(prefs),
            liquidGlassConfig = LiquidGlassConfig(
                enabled = prefs.getBoolean("liquid_glass_enabled", true),
                clarity = prefs.getFloat("liquid_glass_clarity", 1.0f),
                blurRadiusDp = prefs.getFloat("liquid_glass_blur", 0.0f),
                refractionHeightDp = prefs.getFloat("liquid_glass_refraction_height", 28.0f),
                refractionAmountDp = prefs.getFloat("liquid_glass_refraction_amount", 28.0f),
                chromaticAberration = prefs.getBoolean("liquid_glass_chromatic_aberration", true),
                hasHighlight = prefs.getBoolean("liquid_glass_highlight", true),
                hasShadow = prefs.getBoolean("liquid_glass_shadow", true),
                tintAlpha = prefs.getFloat(
                    "liquid_glass_tint_alpha",
                    LiquidGlassConfig.DEFAULT_TINT_ALPHA
                )
            ),
            language = try {
                AppLanguage.valueOf(prefs.getString("app_language", AppLanguage.PERSIAN.name) ?: AppLanguage.PERSIAN.name)
            } catch (e: Exception) {
                AppLanguage.PERSIAN
            },
            calendarSystem = try {
                CalendarSystem.valueOf(prefs.getString("calendar_system", CalendarSystem.SOLAR_HIJRI.name) ?: CalendarSystem.SOLAR_HIJRI.name)
            } catch (e: Exception) {
                CalendarSystem.SOLAR_HIJRI
            },
            skyCanvasTheme = try {
                SkyCanvasTheme.valueOf(prefs.getString("sky_canvas_theme", SkyCanvasTheme.REAL_SKY.name) ?: SkyCanvasTheme.REAL_SKY.name)
            } catch (e: Exception) {
                SkyCanvasTheme.REAL_SKY
            },
            themeMode = try {
                ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
            } catch (e: Exception) {
                ThemeMode.DARK
            },
            bortleClass = prefs.getInt("bortle_class", 3),
            userLocation = run {
                val savedCityEn = prefs.getString("user_city_name_en", null)
                val savedCityFa = prefs.getString("user_city_name_fa", null)
                val savedLat = if (prefs.contains("user_latitude")) {
                    prefs.getFloat("user_latitude", 30.1141f).toDouble()
                } else if (prefs.contains("user_lat")) {
                    prefs.getFloat("user_lat", 30.1141f).toDouble()
                } else {
                    null
                }
                val savedLon = if (prefs.contains("user_longitude")) {
                    prefs.getFloat("user_longitude", 51.5217f).toDouble()
                } else if (prefs.contains("user_lon")) {
                    prefs.getFloat("user_lon", 51.5217f).toDouble()
                } else {
                    null
                }
                val savedElevation = prefs.getFloat("user_elevation", 940.0f).toDouble()
                val savedTimezone = prefs.getString("user_timezone_id", "Asia/Tehran") ?: "Asia/Tehran"
                val savedCountry = prefs.getString("user_country_code", "IR") ?: "IR"
                val savedProvEn = prefs.getString("user_province_en", "Fars") ?: "Fars"
                val savedProvFa = prefs.getString("user_province_fa", "فارس") ?: "فارس"
                val bortle = prefs.getInt("bortle_class", 3)

                if (savedCityEn != null && savedCityFa != null && savedLat != null && savedLon != null) {
                    UserLocation(
                        cityNameEn = savedCityEn,
                        cityNameFa = savedCityFa,
                        latitude = savedLat,
                        longitude = savedLon,
                        elevationMeters = savedElevation,
                        bortleClass = bortle,
                        timezoneId = savedTimezone,
                        countryCode = savedCountry,
                        provinceEn = savedProvEn,
                        provinceFa = savedProvFa
                    )
                } else {
                    UserLocation(bortleClass = bortle)
                }
            }
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        // Sync local database cache for offline accessibility
        viewModelScope.launch {
            repository.syncCelestialCatalogIfNeeded()
        }

        // Observe DB favorites and observation logs
        viewModelScope.launch {
            repository.favoritesFlow.collect { favs ->
                val ids = favs.map { it.objectId }
                _uiState.update { it.copy(favoritesList = ids) }
                // Update detail favorite state if object is open
                _uiState.value.selectedObjectForDetail?.let { currentObj ->
                    _uiState.update { it.copy(isDetailFavorite = ids.contains(currentObj.id)) }
                }
            }
        }

        // Observe DB favorite locations
        viewModelScope.launch {
            repository.favoriteLocationsFlow.collect { favEntities ->
                val list = favEntities.map { entity ->
                    GeoCity(
                        id = entity.id,
                        nameEn = entity.nameEn,
                        nameFa = entity.nameFa,
                        latitude = entity.lat,
                        longitude = entity.lon,
                        elevationMeters = entity.elevationMeters,
                        timezoneId = entity.timezoneId,
                        countryEn = entity.countryEn,
                        countryFa = entity.countryFa,
                        provinceEn = entity.provinceEn,
                        provinceFa = entity.provinceFa,
                        isIran = entity.isIran,
                        isCapital = entity.isCapital
                    )
                }
                _uiState.update { it.copy(favoriteLocations = list) }
            }
        }

        viewModelScope.launch {
            repository.observationLogsFlow.collect { logs ->
                _uiState.update { it.copy(observationLogs = logs) }
            }
        }

        viewModelScope.launch {
            repository.userOccasionsFlow.collect { occasions ->
                _uiState.update { it.copy(userOccasions = occasions) }
            }
        }
    }

    fun selectTab(tabIndex: Int) {
        _uiState.update { it.copy(selectedTab = tabIndex) }
    }

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString("app_language", language.name).apply()
        _uiState.update { it.copy(language = language) }
    }

    fun setCalendarSystem(calendarSystem: CalendarSystem) {
        prefs.edit().putString("calendar_system", calendarSystem.name).apply()
        _uiState.update { it.copy(calendarSystem = calendarSystem) }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        prefs.edit().putString("theme_mode", themeMode.name).apply()
        _uiState.update { it.copy(themeMode = themeMode) }
    }

    fun setLiveSkyBackdropEnabled(enabled: Boolean) {
        setAppBackdropMode(if (enabled) AppBackdropMode.LIVE_SKY else AppBackdropMode.NONE)
    }

    fun setAppBackdropMode(mode: AppBackdropMode) {
        AppBackdropMode.save(prefs, mode)
        _uiState.update {
            it.copy(
                appBackdropMode = mode,
                liveSkyBackdropEnabled = mode == AppBackdropMode.LIVE_SKY
            )
        }
    }

    fun setLiquidGlassEnabled(enabled: Boolean) {
        val updated = _uiState.value.liquidGlassConfig.copy(enabled = enabled)
        updateLiquidGlassConfig(updated)
    }

    fun updateLiquidGlassConfig(config: LiquidGlassConfig) {
        prefs.edit()
            .putBoolean("liquid_glass_enabled", config.enabled)
            .putFloat("liquid_glass_clarity", config.clarity)
            .putFloat("liquid_glass_blur", config.blurRadiusDp)
            .putFloat("liquid_glass_refraction_height", config.refractionHeightDp)
            .putFloat("liquid_glass_refraction_amount", config.refractionAmountDp)
            .putBoolean("liquid_glass_chromatic_aberration", config.chromaticAberration)
            .putBoolean("liquid_glass_highlight", config.hasHighlight)
            .putBoolean("liquid_glass_shadow", config.hasShadow)
            .putFloat("liquid_glass_tint_alpha", config.tintAlpha)
            .apply()
        _uiState.update { it.copy(liquidGlassConfig = config, isLiquidGlassEnabled = config.enabled) }
    }

    fun resetLiquidGlassConfig() {
        val defaultConfig = LiquidGlassConfig()
        updateLiquidGlassConfig(defaultConfig)
    }

    fun setSkyCanvasTheme(theme: SkyCanvasTheme) {
        prefs.edit().putString("sky_canvas_theme", theme.name).apply()
        _uiState.update { it.copy(skyCanvasTheme = theme) }
    }

    fun setShowLocationSelector(show: Boolean) {
        _uiState.update { it.copy(showLocationSelector = show, locationSearchQuery = if (!show) "" else it.locationSearchQuery) }
    }

    fun setLocationSearchQuery(query: String) {
        _uiState.update { it.copy(locationSearchQuery = query) }
    }

    fun toggleFavoriteLocation(city: GeoCity) {
        viewModelScope.launch {
            repository.toggleFavoriteLocation(city)
        }
    }

    fun removeFavoriteLocation(id: String) {
        viewModelScope.launch {
            repository.removeFavoriteLocation(id)
        }
    }

    fun setLocation(
        cityEn: String,
        cityFa: String,
        lat: Double,
        lon: Double,
        elevationMeters: Double = 0.0,
        timezoneId: String = "Asia/Tehran",
        countryCode: String = "IR",
        provinceEn: String = "",
        provinceFa: String = ""
    ) {
        val newLoc = UserLocation(
            cityNameEn = cityEn,
            cityNameFa = cityFa,
            latitude = lat,
            longitude = lon,
            elevationMeters = elevationMeters,
            bortleClass = _uiState.value.bortleClass,
            timezoneId = timezoneId,
            countryCode = countryCode,
            provinceEn = provinceEn,
            provinceFa = provinceFa
        )
        // Persist to SharedPreferences so location remains across app restarts
        prefs.edit()
            .putString("user_city_name_en", cityEn)
            .putString("user_city_name_fa", cityFa)
            .putFloat("user_latitude", lat.toFloat())
            .putFloat("user_longitude", lon.toFloat())
            .putFloat("user_lat", lat.toFloat())
            .putFloat("user_lon", lon.toFloat())
            .putFloat("user_elevation", elevationMeters.toFloat())
            .putString("user_timezone_id", timezoneId)
            .putString("user_country_code", countryCode)
            .putString("user_province_en", provinceEn)
            .putString("user_province_fa", provinceFa)
            .apply()

        // Also persist to astro_prefs for notification and background workers
        val astroPrefs = getApplication<Application>().getSharedPreferences("astro_prefs", Context.MODE_PRIVATE)
        astroPrefs.edit()
            .putString("user_city_name_en", cityEn)
            .putString("user_city_name_fa", cityFa)
            .putFloat("user_lat", lat.toFloat())
            .putFloat("user_lon", lon.toFloat())
            .apply()

        _uiState.update { it.copy(userLocation = newLoc, showLocationSelector = false) }
        com.alijafari.red.astronomy.notification.AstroNotificationManager.handleLocationChanged(getApplication(), newLoc)
    }

    fun setGpsLocation(lat: Double, lon: Double, altMeters: Double? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isGpsLocating = true) }

            // 1. Instant offline resolution using local database
            val nearest = GeoLocationCatalog.findNearestCity(lat, lon)
            val distKm = GeoLocationCatalog.distanceKm(lat, lon, nearest.latitude, nearest.longitude)
            val elevation = altMeters ?: nearest.elevationMeters

            val isIran = nearest.isIran && distKm < 1000.0
            val initialCityEn = if (distKm < 5.0) nearest.nameEn else "GPS (${String.format(Locale.US, "%.2f°N, %.2f°E", lat, lon)})"
            val initialCityFa = if (distKm < 5.0) nearest.nameFa else "موقعیت زنده GPS (${nearest.nameFa})"

            val initialLoc = UserLocation(
                cityNameEn = initialCityEn,
                cityNameFa = initialCityFa,
                latitude = lat,
                longitude = lon,
                elevationMeters = elevation,
                bortleClass = _uiState.value.bortleClass,
                timezoneId = nearest.timezoneId,
                countryCode = if (isIran) "IR" else "GLOBAL",
                provinceEn = nearest.provinceEn,
                provinceFa = nearest.provinceFa
            )

            withContext(Dispatchers.Main) {
                setLocation(
                    cityEn = initialLoc.cityNameEn,
                    cityFa = initialLoc.cityNameFa,
                    lat = initialLoc.latitude,
                    lon = initialLoc.longitude,
                    elevationMeters = initialLoc.elevationMeters,
                    timezoneId = initialLoc.timezoneId,
                    countryCode = initialLoc.countryCode,
                    provinceEn = initialLoc.provinceEn,
                    provinceFa = initialLoc.provinceFa
                )
                _uiState.update { it.copy(isGpsLocating = false) }
            }

            // 2. Opportunistic Geocoder reverse lookup if online (won't crash or block if offline)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val geocoder = Geocoder(getApplication(), Locale.getDefault())
                    geocoder.getFromLocation(lat, lon, 1) { addresses ->
                        if (addresses.isNotEmpty()) {
                            val addr = addresses[0]
                            val locality = addr.locality ?: addr.subAdminArea ?: addr.adminArea
                            val country = addr.countryName
                            if (!locality.isNullOrBlank()) {
                                val enrichedEn = "$locality, $country"
                                val enrichedFa = "$locality ($country)"
                                viewModelScope.launch(Dispatchers.Main) {
                                    setLocation(
                                        cityEn = enrichedEn,
                                        cityFa = enrichedFa,
                                        lat = lat,
                                        lon = lon,
                                        elevationMeters = elevation,
                                        timezoneId = nearest.timezoneId,
                                        countryCode = addr.countryCode ?: if (isIran) "IR" else "GLOBAL",
                                        provinceEn = addr.adminArea ?: nearest.provinceEn,
                                        provinceFa = addr.adminArea ?: nearest.provinceFa
                                    )
                                }
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val geocoder = Geocoder(getApplication(), Locale.getDefault())
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val locality = addr.locality ?: addr.subAdminArea ?: addr.adminArea
                        val country = addr.countryName
                        if (!locality.isNullOrBlank()) {
                            val enrichedEn = "$locality, $country"
                            val enrichedFa = "$locality ($country)"
                            withContext(Dispatchers.Main) {
                                setLocation(
                                    cityEn = enrichedEn,
                                    cityFa = enrichedFa,
                                    lat = lat,
                                    lon = lon,
                                    elevationMeters = elevation,
                                    timezoneId = nearest.timezoneId,
                                    countryCode = addr.countryCode ?: if (isIran) "IR" else "GLOBAL",
                                    provinceEn = addr.adminArea ?: nearest.provinceEn,
                                    provinceFa = addr.adminArea ?: nearest.provinceFa
                                )
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {
                // Offline or geocoder unavailable - graceful degradation
            }
        }
    }

    fun selectSatelliteById(satId: String?) {
        _uiState.update { it.copy(selectedSatelliteId = satId) }
    }

    fun setBortleClass(bortle: Int) {
        prefs.edit().putInt("bortle_class", bortle).apply()
        _uiState.update {
            it.copy(
                bortleClass = bortle,
                userLocation = it.userLocation.copy(bortleClass = bortle)
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    private fun effectiveTimestampMs(overrideMs: Long? = null): Long {
        if (overrideMs != null) return overrideMs
        val tm = _uiState.value.timeMachineState
        return if (tm.mode == TimeMachineMode.SIMULATION) tm.simulationTimeMs else System.currentTimeMillis()
    }

    fun openObjectDetail(obj: CelestialObject, timestampMs: Long? = null) {
        val evalTimeMs = effectiveTimestampMs(timestampMs)
        val canonicalId = CanonicalAstroCatalog.resolveCanonicalId(obj.id)
        val canonicalObj = CanonicalAstroCatalog.getCanonicalObject(canonicalId)
        val finalObj = if (canonicalObj != null) {
            val calc = AstroDispatchEngine.calculateState(
                idOrAlias = canonicalObj.canonicalId,
                timestampMs = evalTimeMs,
                userLatDeg = _uiState.value.userLocation.latitude,
                userLonDeg = _uiState.value.userLocation.longitude,
                elevationM = _uiState.value.userLocation.elevationMeters
            )
            CanonicalAstroCatalog.toCelestialObject(
                canonicalObj = canonicalObj,
                dynamicRa = calc?.raDeg ?: obj.raDeg,
                dynamicDec = calc?.decDeg ?: obj.decDeg,
                dynamicMag = calc?.magnitude ?: obj.magnitude
            )
        } else {
            obj
        }
        val isFav = _uiState.value.favoritesList.contains(finalObj.id) || _uiState.value.favoritesList.contains(obj.id)
        _uiState.update {
            it.copy(
                selectedObjectForDetail = finalObj,
                isDetailFavorite = isFav
            )
        }
    }

    fun openObjectDetailById(idOrAlias: String, timestampMs: Long? = null) {
        val evalTimeMs = effectiveTimestampMs(timestampMs)
        val canonicalId = CanonicalAstroCatalog.resolveCanonicalId(idOrAlias)
        val canonicalObj = CanonicalAstroCatalog.getCanonicalObject(canonicalId)
        if (canonicalObj != null) {
            val calc = AstroDispatchEngine.calculateState(
                idOrAlias = canonicalObj.canonicalId,
                timestampMs = evalTimeMs,
                userLatDeg = _uiState.value.userLocation.latitude,
                userLonDeg = _uiState.value.userLocation.longitude,
                elevationM = _uiState.value.userLocation.elevationMeters
            )
            val celObj = CanonicalAstroCatalog.toCelestialObject(
                canonicalObj = canonicalObj,
                dynamicRa = calc?.raDeg ?: 0.0,
                dynamicDec = calc?.decDeg ?: 0.0,
                dynamicMag = calc?.magnitude ?: canonicalObj.physicalProperties.magnitude
            )
            openObjectDetail(celObj, evalTimeMs)
        } else {
            val jd = TimeEngine.getJulianDate(evalTimeMs)
            AstronomyCatalog.getById(idOrAlias, jd)?.let { openObjectDetail(it, evalTimeMs) }
        }
    }

    fun closeObjectDetail() {
        _uiState.update { it.copy(selectedObjectForDetail = null) }
    }

    fun toggleCurrentDetailFavorite() {
        val obj = _uiState.value.selectedObjectForDetail ?: return
        viewModelScope.launch {
            repository.toggleFavorite(obj.id, obj.type.name)
        }
    }

    fun toggleFavoriteById(objectId: String, objectType: String) {
        viewModelScope.launch {
            repository.toggleFavorite(objectId, objectType)
        }
    }

    fun setShowFavoritesDialog(show: Boolean) {
        _uiState.update { it.copy(showFavoritesDialog = show) }
    }

    fun setShowMoonWindow(show: Boolean) {
        _uiState.update { it.copy(showMoonWindow = show) }
    }

    /** Opens or closes the fullscreen sky viewer. */
    fun setShowSkyViewer(show: Boolean) {
        _uiState.update { if (it.showSkyViewer == show) it else it.copy(showSkyViewer = show) }
    }

    /** Scrub the Moon in days. Clamped to the same +/-30 day window the Moon screen always used. */
    fun setMoonDayOffset(offsetDays: Float) {
        val clamped = offsetDays.coerceIn(-30f, 30f)
        _uiState.update { if (it.moonDayOffset == clamped) it else it.copy(moonDayOffset = clamped) }
    }

    /**
     * Relative scrub for drag gestures. The delta is applied to the state the store actually holds, not to
     * the snapshot the last recomposition captured — drag events outpace frames, and stacking them on a
     * stale base would swallow motion and make the Moon lag the finger.
     */
    fun nudgeMoonDayOffset(deltaDays: Float) {
        _uiState.update {
            val clamped = (it.moonDayOffset + deltaDays).coerceIn(-30f, 30f)
            if (it.moonDayOffset == clamped) it else it.copy(moonDayOffset = clamped)
        }
    }

    fun setShowSettingsDialog(show: Boolean) {
        _uiState.update { it.copy(showSettingsDialog = show) }
    }

    fun setShowObservationLogDialog(show: Boolean) {
        _uiState.update { it.copy(showObservationLogDialog = show) }
    }

    fun addObservationLog(notes: String, rating: Int) {
        val obj = _uiState.value.selectedObjectForDetail ?: return
        val name = if (_uiState.value.language == AppLanguage.PERSIAN) obj.nameFa else obj.nameEn
        viewModelScope.launch {
            repository.addObservationLog(obj.id, name, notes, rating)
        }
    }

    fun deleteObservationLog(log: ObservationLogEntity) {
        viewModelScope.launch {
            repository.deleteObservationLog(log)
        }
    }

    // User Occasions (My Occasions)
    fun saveUserOccasion(id: String?, title: String, timestampMs: Long, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = repository.saveUserOccasion(id, title, timestampMs)
            onResult(success)
        }
    }

    fun deleteUserOccasion(id: String) {
        viewModelScope.launch {
            repository.deleteUserOccasion(id)
        }
    }

    // Time Machine Functions
    fun setSimulatedTime(timeMs: Long, eventName: String? = null, isBirthday: Boolean = false) {
        val clamped = timeMs.coerceIn(TimeMachineState.MIN_TIMESTAMP_MS, TimeMachineState.MAX_TIMESTAMP_MS)
        _uiState.update {
            it.copy(
                timeMachineState = it.timeMachineState.copy(
                    mode = TimeMachineMode.SIMULATION,
                    simulationTimeMs = clamped,
                    eventName = eventName,
                    isBirthdayMode = isBirthday
                )
            )
        }
    }

    fun setTimeMachineMode(mode: TimeMachineMode) {
        _uiState.update {
            val currentSimTime = if (mode == TimeMachineMode.SIMULATION && it.timeMachineState.mode == TimeMachineMode.LIVE) {
                System.currentTimeMillis()
            } else {
                it.timeMachineState.simulationTimeMs
            }
            it.copy(
                timeMachineState = it.timeMachineState.copy(
                    mode = mode,
                    simulationTimeMs = currentSimTime
                )
            )
        }
    }

    fun toggleTimeMachinePlaying() {
        _uiState.update {
            val isNowPlaying = !it.timeMachineState.isPlaying
            val newMode = if (isNowPlaying && it.timeMachineState.mode == TimeMachineMode.LIVE) {
                TimeMachineMode.SIMULATION
            } else {
                it.timeMachineState.mode
            }
            val newSimTime = if (it.timeMachineState.mode == TimeMachineMode.LIVE) {
                System.currentTimeMillis()
            } else {
                it.timeMachineState.simulationTimeMs
            }
            it.copy(
                timeMachineState = it.timeMachineState.copy(
                    mode = newMode,
                    isPlaying = isNowPlaying,
                    simulationTimeMs = newSimTime
                )
            )
        }
    }

    fun setTimeMachinePlaying(playing: Boolean) {
        _uiState.update {
            val newMode = if (playing && it.timeMachineState.mode == TimeMachineMode.LIVE) {
                TimeMachineMode.SIMULATION
            } else {
                it.timeMachineState.mode
            }
            val newSimTime = if (it.timeMachineState.mode == TimeMachineMode.LIVE) {
                System.currentTimeMillis()
            } else {
                it.timeMachineState.simulationTimeMs
            }
            it.copy(
                timeMachineState = it.timeMachineState.copy(
                    mode = newMode,
                    isPlaying = playing,
                    simulationTimeMs = newSimTime
                )
            )
        }
    }

    fun setTimeMachineSpeed(speed: TimeSimulationSpeed) {
        _uiState.update {
            it.copy(
                timeMachineState = it.timeMachineState.copy(speed = speed)
            )
        }
    }

    fun toggleTimeMachineReverse() {
        _uiState.update {
            it.copy(
                timeMachineState = it.timeMachineState.copy(isReverse = !it.timeMachineState.isReverse)
            )
        }
    }

    fun toggleTimeMachineExpanded() {
        _uiState.update {
            it.copy(
                timeMachineState = it.timeMachineState.copy(isExpanded = !it.timeMachineState.isExpanded)
            )
        }
    }

    fun setTimeMachineExpanded(expanded: Boolean) {
        _uiState.update {
            it.copy(
                timeMachineState = it.timeMachineState.copy(isExpanded = expanded)
            )
        }
    }

    fun returnToLiveTime() {
        _uiState.update {
            it.copy(
                timeMachineState = it.timeMachineState.copy(
                    mode = TimeMachineMode.LIVE,
                    isPlaying = false,
                    eventName = null,
                    isBirthdayMode = false
                )
            )
        }
    }

    fun locateObjectInAR(obj: CelestialObject) {
        _uiState.update {
            it.copy(
                selectedTargetObject = obj,
                selectedObjectForDetail = null,
                selectedTab = 3
            )
        }
    }

    fun clearTargetObject() {
        _uiState.update {
            it.copy(selectedTargetObject = null)
        }
    }
}
