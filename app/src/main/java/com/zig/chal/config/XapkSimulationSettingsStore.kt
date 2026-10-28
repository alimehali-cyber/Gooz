package com.zig.chal.config

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/** Original bh.params keys remain compatible; chal.* keys preserve the additional view/settings. */
class XapkSimulationSettingsStore(context: Context) {
    private val context = context.applicationContext
    private val preferences: SharedPreferences = this.context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ChalSimulationParams {
        val quality = ChalRayTracingQuality.fromId(string("chal.quality"))
            ?: XapkRendererContract.chalQualityFor(string("preset")) ?: defaultQuality()
        val features = ChalFeatureToggles(
            gravitationalLensing = boolean("enLens", true), rayTracingQuality = quality,
            accretionDisk = boolean("enDisk", true), dopplerBeaming = boolean("enDoppler", true),
            backgroundStars = boolean("enStars", true), photonSphereGlow = boolean("enPhoton", true),
            bloom = boolean("chal.bloom", true), relativisticJets = boolean("enJets", false),
            gravitationalRedshift = boolean("showRedshift", false), kerrShadow = boolean("chal.shadowGuide", false),
            spacetimeVisualization = false
        )
        return ChalSimulationParams(
            mass = number("mass", ChalSimulationConfig.MASS), spin = number("spin", ChalSimulationConfig.SPIN),
            diskDensity = number("diskDensity", ChalSimulationConfig.DISK_DENSITY),
            diskTemp = number("diskTemp", ChalSimulationConfig.DISK_TEMP), lensing = number("lens", ChalSimulationConfig.LENSING),
            frameDraggingStrength = number("frameDrag", ChalSimulationConfig.FRAME_DRAGGING),
            autoSpin = number("autoSpin", ChalSimulationConfig.AUTO_SPIN), diskSize = number("diskSize", ChalSimulationConfig.DISK_SIZE),
            diskScaleHeight = number("diskScale", ChalSimulationConfig.DISK_SCALE_HEIGHT),
            bloomThreshold = number("bloomThr", ChalSimulationConfig.BLOOM_THRESHOLD),
            bloomIntensity = number("bloomInt", ChalSimulationConfig.BLOOM_INTENSITY),
            zoom = number("chal.zoom", ChalSimulationConfig.ZOOM),
            // Pause is a transient viewing state: it survives rotation (saved runtime state), not a new visit.
            paused = false,
            cameraYaw = ChalRenderPolicy.normalizeYaw(float("chal.yaw", XapkCameraState.DEFAULT_YAW).toDouble()),
            verticalAngle = number("chal.pitch", ChalSimulationConfig.VERTICAL_ANGLE.copy(min = 0.001 * 180.0 / Math.PI, max = 180.0 - 0.001 * 180.0 / Math.PI)),
            adaptiveResolution = boolean("chal.adaptiveResolution", true),
            automaticQuality = boolean("chal.automaticQuality", false),
            batterySaver = boolean("chal.batterySaver", false), reducedMotion = boolean("chal.reducedMotion", systemReducedMotion()),
            renderScale = number("chal.renderScale", ChalSimulationConfig.RENDER_SCALE.copy(
                default = XapkRendererContract.qualityFor(quality).renderScale.toDouble())),
            features = features, performancePreset = ChalFeatures.matchesPreset(features)
        )
    }

    fun save(params: ChalSimulationParams) {
        preferences.edit()
            .putFloat("mass", params.mass.toFloat()).putFloat("spin", params.spin.toFloat())
            .putFloat("lens", params.lensing.toFloat()).putFloat("frameDrag", params.frameDraggingStrength.toFloat())
            .putFloat("diskDensity", params.diskDensity.toFloat()).putFloat("diskTemp", params.diskTemp.toFloat())
            .putFloat("diskSize", params.diskSize.toFloat()).putFloat("diskScale", params.diskScaleHeight.toFloat())
            .putFloat("bloomThr", params.bloomThreshold.toFloat()).putFloat("bloomInt", params.bloomIntensity.toFloat())
            .putFloat("autoSpin", params.autoSpin.toFloat()).putBoolean("enLens", params.features.gravitationalLensing)
            .putBoolean("enDisk", params.features.accretionDisk).putBoolean("enDoppler", params.features.dopplerBeaming)
            .putBoolean("enPhoton", params.features.photonSphereGlow).putBoolean("enStars", params.features.backgroundStars)
            .putBoolean("enJets", params.features.relativisticJets).putBoolean("showRedshift", params.features.gravitationalRedshift)
            .putString("preset", XapkRendererContract.qualityFor(params.features.rayTracingQuality).preferenceName)
            .putString("chal.quality", params.features.rayTracingQuality.id) // preserve legacy OFF without mapping it to LOW
            .putBoolean("chal.bloom", params.features.bloom).putBoolean("chal.shadowGuide", params.features.kerrShadow)
            .putBoolean("chal.adaptiveResolution", params.adaptiveResolution).putBoolean("chal.automaticQuality", params.automaticQuality)
            .putBoolean("chal.batterySaver", params.batterySaver).putBoolean("chal.reducedMotion", params.reducedMotion)
            .putFloat("chal.renderScale", params.renderScale.toFloat()).putFloat("chal.zoom", params.zoom.toFloat())
            .putFloat("chal.yaw", params.cameraYaw.toFloat())
            .putFloat("chal.pitch", params.verticalAngle.toFloat()).apply()
    }

    fun defaults(): ChalSimulationParams {
        val quality = defaultQuality()
        val features = ChalFeatureToggles(true, quality, true, true, true, true, true, false, false, false, false)
        return ChalSimulationParams(features = features, performancePreset = ChalFeatures.matchesPreset(features),
            renderScale = XapkRendererContract.qualityFor(quality).renderScale.toDouble(), reducedMotion = systemReducedMotion())
    }

    private fun number(key: String, config: ChalParameterConfig): Double {
        val value = float(key, config.default.toFloat()).toDouble()
        return (if (value.isFinite()) value else config.default).coerceIn(config.min, config.max)
    }
    private fun float(key: String, default: Float): Float = try { preferences.getFloat(key, default) } catch (_: ClassCastException) { default }
    private fun boolean(key: String, default: Boolean): Boolean = try { preferences.getBoolean(key, default) } catch (_: ClassCastException) { default }
    private fun string(key: String): String? = try { preferences.getString(key, null) } catch (_: ClassCastException) { null }

    private fun systemReducedMotion(): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: SecurityException) { false }

    private fun defaultQuality(): ChalRayTracingQuality {
        // A conservative initial hint, NOT a GPU classification; optional Auto uses measured FPS.
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return ChalRayTracingQuality.LOW
        if (manager.isLowRamDevice) return ChalRayTracingQuality.LOW
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return when {
            info.totalMem >= 6 * BYTES_PER_GIB -> ChalRayTracingQuality.HIGH
            info.totalMem >= 3 * BYTES_PER_GIB -> ChalRayTracingQuality.MEDIUM
            else -> ChalRayTracingQuality.LOW
        }
    }

    companion object {
        const val PREFERENCES_NAME = "bh.params"
        private const val BYTES_PER_GIB = 1_073_741_824.0
    }
}
