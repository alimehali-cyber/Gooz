package com.zig.chal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ui.theme.RedCornerRadius
import com.alijafari.red.astronomy.ui.theme.RedSpacing
import com.alijafari.red.astronomy.ui.theme.RedTheme
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.chal.config.ChalFeatureToggle
import com.zig.chal.config.ChalFeatures
import com.zig.chal.config.ChalMotion
import com.zig.chal.config.ChalParameterConfig
import com.zig.chal.config.ChalPresetName
import com.zig.chal.config.ChalRayTracingQuality
import com.zig.chal.config.ChalRenderPolicy
import com.zig.chal.config.ChalSimulationConfig
import com.zig.chal.config.ChalSimulationParams
import com.zig.chal.config.ChalSliderMath
import com.zig.chal.config.ChalXapkScenario
import com.zig.chal.config.XapkRendererContract
import com.zig.chal.render.ChalBenchmark
import com.zig.chal.render.ChalCamera
import com.zig.chal.render.ChalCompatibilityReason
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Tabs are hoisted so hiding controls, opening a sheet, and rotation preserve the selected tab. */
enum class ChalPanelTab {
    PHYSICS, SYSTEM, MODULES;
    fun label(persian: Boolean): String = when (this) {
        PHYSICS -> if (persian) "فیزیک" else "Physics"
        SYSTEM -> if (persian) "سامانه" else "System"
        MODULES -> if (persian) "ماژول‌ها" else "Modules"
    }
}
enum class ChalCinematicTool { ORBIT, DIVE }

/** Backend-specific controls, without decorative/no-op modules or duplicate GLES quality tiers. */
@Composable
fun ChalControlPanel(
    params: ChalSimulationParams,
    isPersian: Boolean,
    isCinematic: Boolean,
    isXapkRenderer: Boolean,
    tab: ChalPanelTab,
    onTabChange: (ChalPanelTab) -> Unit,
    onScenarioSelected: (ChalXapkScenario) -> Unit,
    onParamsChange: (ChalSimulationParams.() -> ChalSimulationParams) -> Unit,
    onPresetSelected: (ChalPresetName) -> Unit,
    onQualitySelected: (ChalRayTracingQuality) -> Unit,
    onStartCinematic: (ChalCinematicTool) -> Unit,
    onStopCinematic: () -> Unit,
    onStartBenchmark: () -> Unit,
    onCancelBenchmark: () -> Unit,
    onApplyBenchmarkRecommendation: () -> Unit,
    onRestoreDefaults: () -> Unit,
    isBenchmarkRunning: Boolean,
    benchmarkPreset: ChalPresetName?,
    benchmarkProgress: Double,
    benchmarkResults: List<ChalBenchmark.BenchmarkResult>,
    benchmarkRecommendation: ChalPresetName?,
    actualRenderScale: Double,
    postProcessingAvailable: Boolean,
    maxContentHeight: Dp,
    cinematicMode: ChalCamera.CinematicMode? = null,
    bloomThresholdMax: Double = 4.0,
    compatibilityReason: ChalCompatibilityReason? = null,
    diagnosticDetails: String? = null,
    onRetryNative: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val fullPhysics = isXapkRenderer || ChalRenderPolicy.hasRayMarching(params.features.rayTracingQuality)
    val editable = !isBenchmarkRunning
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var confirmDefaults by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("chal_control_panel")
            .clip(squircleShape(RedCornerRadius.xl))
            .background(Color.Black.copy(alpha = 0.86f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), squircleShape(RedCornerRadius.xl))
            .padding(RedSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)
    ) {
        ScrollableTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent,
            contentColor = Color.White, edgePadding = 0.dp, divider = {}, indicator = {}) {
            ChalPanelTab.entries.forEach { entry ->
                Tab(selected = tab == entry, onClick = { onTabChange(entry) },
                    modifier = Modifier.testTag("chal_tab_${entry.name.lowercase(Locale.US)}")
                        .heightIn(min = 48.dp)
                        .background(if (tab == entry) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                            squircleShape(RedCornerRadius.sm)),
                    text = { Text(entry.label(isPersian), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) })
            }
        }

        Column(Modifier.fillMaxWidth().heightIn(max = maxContentHeight.coerceAtLeast(48.dp))
            .verticalScroll(rememberScrollState()).testTag("chal_panel_scroll"),
            verticalArrangement = Arrangement.spacedBy(RedSpacing.sm)) {
            if (isBenchmarkRunning) {
                ChalCaption(if (isPersian) "تا پایان سنجش، تنظیمات و دوربین ثابت می‌مانند. می‌توانید سنجش را متوقف کنید."
                    else "Settings and camera stay fixed during measurement. You can stop the benchmark.")
                ChalActionButton(if (isPersian) "توقف سنجش" else "Stop benchmark", onCancelBenchmark,
                    Modifier.fillMaxWidth().testTag("chal_cancel_benchmark"))
            }
            when (tab) {
                ChalPanelTab.PHYSICS -> {
                    ChalPanelSection(if (isPersian) "سناریوها" else "Scenarios") {
                        ChalChoices(ChalXapkScenario.entries.toList(), { if (isPersian) it.persianLabel else it.label },
                            { ChalXapkScenario.matching(params, isXapkRenderer) == it }, editable, onScenarioSelected)
                        if (ChalXapkScenario.SGR_A_PROXY.matches(params, isXapkRenderer)) {
                            ChalCaption(if (isPersian) "این مدل کمان ای* با ۴ جرم خورشیدی نمایش داده می‌شود؛ جرم واقعی چندمیلیون‌برابری مرکز کهکشان بازسازی نمی‌شود."
                                else "This Sgr A* proxy uses 4 solar masses, not the real Galactic-center object's millions of solar masses.")
                        }
                    }
                    if (!fullPhysics) ChalCaption(if (isPersian) "حالت ساده ردیابی پرتو ندارد. برای همگرایی، دوپلر و فواره‌ها، کیفیت متوسط یا زیاد را انتخاب کنید."
                        else "Preview is analytic, with no ray marching. Choose Medium or High for lensing, Doppler effects and jets.")
                    ChalPanelSection(if (isPersian) "سیاه‌چاله و دید" else "Black hole & view") {
                        ChalSlider(ChalSimulationConfig.MASS, params.mass, isPersian, enabled = editable) { v -> onParamsChange { copy(mass = v) } }
                        ChalSlider(ChalSimulationConfig.SPIN, params.spin, isPersian, enabled = editable) { v -> onParamsChange { copy(spin = v) } }
                        if (!isXapkRenderer && !isCinematic) {
                            ChalSlider(ChalSimulationConfig.ZOOM, params.zoom, isPersian, enabled = editable) { v -> onParamsChange { copy(zoom = v) } }
                            ChalCaption(if (isPersian) "فاصله برحسب شعاع شوارتزشیلد خورشید در مختصات صحنه است، نه شعاع سیاه‌چالهٔ انتخاب‌شده."
                                else "View distance uses solar Schwarzschild scene units, not the selected black hole's radius.")
                        }
                        if (fullPhysics) {
                            ChalSlider(ChalSimulationConfig.LENSING, params.lensing, isPersian, enabled = editable) { v -> onParamsChange { copy(lensing = v) } }
                            if (isXapkRenderer || (params.features.gravitationalLensing && params.spin > 0.0)) {
                                ChalSlider(ChalSimulationConfig.FRAME_DRAGGING, params.frameDraggingStrength, isPersian, enabled = editable) { v -> onParamsChange { copy(frameDraggingStrength = v) } }
                            }
                        }
                    }
                    if (!isCinematic && !params.reducedMotion) ChalPanelSection(if (isPersian) "حرکت دوربین" else "Camera motion") {
                        val config = if (isXapkRenderer) ChalSimulationConfig.AUTO_SPIN.copy(unit = if (isPersian) "واحد موتور" else "native units") else ChalSimulationConfig.AUTO_SPIN
                        ChalSlider(config, params.autoSpin, isPersian, enabled = editable,
                            displayFactor = if (isXapkRenderer) 1.0 else ChalMotion.REFERENCE_FPS) { v -> onParamsChange { copy(autoSpin = v) } }
                    }
                    if (isCinematic) ChalCaption(if (isPersian) "تور، حرکت و فاصلهٔ دوربین را کنترل می‌کند. برای تنظیم دستی آن را متوقف کنید."
                        else "The tour controls camera motion and distance. Stop it to edit them manually.")
                    if (fullPhysics && params.features.accretionDisk) ChalPanelSection(if (isPersian) "قرص برافزایشی" else "Accretion disk") {
                        ChalSlider(ChalSimulationConfig.DISK_SIZE, params.diskSize, isPersian, enabled = editable) { v -> onParamsChange { copy(diskSize = v) } }
                        ChalSlider(ChalSimulationConfig.DISK_SCALE_HEIGHT, params.diskScaleHeight, isPersian, enabled = editable) { v -> onParamsChange { copy(diskScaleHeight = v) } }
                        ChalSlider(ChalSimulationConfig.DISK_TEMP, params.diskTemp, isPersian, enabled = editable, logarithmic = true) { v -> onParamsChange { copy(diskTemp = v) } }
                        ChalSlider(ChalSimulationConfig.DISK_DENSITY, params.diskDensity, isPersian, enabled = editable) { v -> onParamsChange { copy(diskDensity = v) } }
                        if (!isXapkRenderer) ChalCaption(if (isPersian) "دما، مقیاس منبع یک‌جرم‌خورشیدی است و در این مدل با توان منفی یک‌چهارم جرم تغییر می‌کند."
                            else "Temperature is a one-solar-mass scale; this model adjusts it with mass to the power −¼.")
                    }
                    if (postProcessingAvailable && params.features.bloom) ChalPanelSection(if (isPersian) "درخشش" else "Bloom") {
                        ChalSlider(ChalSimulationConfig.BLOOM_THRESHOLD.copy(max = bloomThresholdMax), params.bloomThreshold, isPersian, enabled = editable) { v -> onParamsChange { copy(bloomThreshold = v) } }
                        ChalSlider(ChalSimulationConfig.BLOOM_INTENSITY, params.bloomIntensity, isPersian, enabled = editable) { v -> onParamsChange { copy(bloomIntensity = v) } }
                        if (bloomThresholdMax < 1.0) ChalCaption(if (isPersian) "این دستگاه هدف HDR ندارد؛ آستانهٔ درخشش در بازهٔ رنگ معمولی محدود می‌شود."
                            else "This device uses an LDR target; bloom threshold is limited to its available color range.")
                    }
                }
                ChalPanelTab.SYSTEM -> {
                    if (!isXapkRenderer) ChalPanelSection(if (isPersian) "پیش‌تنظیم‌های کارایی" else "Performance presets") {
                        ChalChoices(ChalRenderPolicy.fallbackPresets, { it.uiLabel(isPersian) }, { params.performancePreset == it }, editable, onPresetSelected)
                    }
                    ChalPanelSection(if (isPersian) "کیفیت رندر" else "Rendering quality") {
                        ChalToggleRow(if (isPersian) "کیفیت خودکار" else "Auto quality", params.automaticQuality, editable) { enabled ->
                            onParamsChange { copy(automaticQuality = enabled) }
                        }
                        ChalCaption(if (isPersian) "حالت خودکار از نرخ فریم اندازه‌گیری‌شده استفاده می‌کند؛ حافظهٔ دستگاه به‌تنهایی توان پردازش گرافیکی را تعیین نمی‌کند."
                            else "Auto uses measured FPS. Device memory alone is not a GPU rating. Choosing a tier switches to manual quality.")
                        val tiers = if (isXapkRenderer) ChalRenderPolicy.nativeQualities else ChalRenderPolicy.fallbackQualities
                        ChalChoices(tiers, { it.uiLabel(isPersian, isXapkRenderer) }, { params.features.rayTracingQuality == it }, editable, onQualitySelected)
                        val steps = if (isXapkRenderer) XapkRendererContract.qualityFor(params.features.rayTracingQuality).steps
                            else ChalFeatures.getMaxRaySteps(params.features.rayTracingQuality, isMobile = true)
                        ChalCaption(if (!fullPhysics) {
                            if (isPersian) "پیش‌نمایش تحلیلی؛ بدون ردیابی پرتو." else "Analytic preview; no ray marching."
                        } else if (isPersian) "سقف گام پرتو: $steps" else "Ray-step limit: $steps")
                    }
                    if (!isXapkRenderer) ChalPanelSection(if (isPersian) "توان و وضوح" else "Power & resolution") {
                        ChalToggleRow(if (isPersian) "صرفه‌جویی باتری" else "Battery saver", params.batterySaver, editable) { enabled -> onParamsChange { copy(batterySaver = enabled) } }
                        ChalCaption(if (isPersian) "صرفه‌جویی باتری: حداکثر ۳۰ فریم در ثانیه و مقیاس وضوح ۷۵٪."
                            else "Battery saver caps pacing at 30 FPS and resolution at 75%.")
                        if (postProcessingAvailable) {
                            ChalToggleRow(if (isPersian) "وضوح خودکار" else "Auto resolution", params.adaptiveResolution, editable) { enabled ->
                                onParamsChange { copy(adaptiveResolution = enabled, renderScale = if (enabled) renderScale else actualRenderScale) }
                            }
                            ChalCaption(if (isPersian) "وضوح واقعی: ${Math.round(actualRenderScale * 100)}٪" else "Actual resolution: ${Math.round(actualRenderScale * 100)}%")
                            if (!params.adaptiveResolution) ChalSlider(ChalSimulationConfig.RENDER_SCALE.copy(max = if (params.batterySaver) 0.75 else 1.0),
                                params.renderScale, isPersian, enabled = editable) { v -> onParamsChange { copy(renderScale = v) } }
                        } else ChalCaption(if (isPersian) "پس‌پردازش در دسترس نیست؛ تصویر مستقیم با وضوح کامل نمایش داده می‌شود."
                            else "Post-processing is unavailable; rendering directly at full resolution.")
                    }
                    ChalPanelSection(if (isPersian) "ترجیحات حرکت" else "Motion preferences") {
                        ChalToggleRow(if (isPersian) "کاهش حرکت" else "Reduced motion", params.reducedMotion, editable) { enabled -> onParamsChange { copy(reducedMotion = enabled) } }
                        ChalCaption(if (isPersian) "حرکت خودکار دوربین و تورها را متوقف می‌کند؛ چرخاندن دستی همچنان فعال است."
                            else "Stops automatic camera motion and tours. Manual gestures still work.")
                    }
                    if (!isXapkRenderer) ChalPanelSection(if (isPersian) "سنجش کارایی" else "Performance benchmark") {
                        if (!isCinematic && !isBenchmarkRunning) ChalActionButton(if (isPersian) "اجرای سنجش کارایی" else "Run benchmark", onStartBenchmark,
                            Modifier.fillMaxWidth().testTag("chal_start_benchmark"))
                        ChalCaption(if (isPersian) "سه پیش‌تنظیم متمایز، هرکدام ۵ ثانیه و با وضوح ثابت مقایسه می‌شوند. تنظیمات قبلی پس از پایان یا لغو بازمی‌گردند."
                            else "Compares three distinct presets for 5 seconds each at fixed resolution. Your settings are restored after completion or cancellation.")
                        if (isCinematic) ChalCaption(if (isPersian) "پیش از سنجش، تور را متوقف کنید." else "Stop the tour before benchmarking.")
                        if (isBenchmarkRunning) {
                            Text("${benchmarkPreset?.uiLabel(isPersian).orEmpty()} · ${Math.round(benchmarkProgress * 100)}%", color = Color.White, fontSize = 13.sp)
                            Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.15f))) {
                                Spacer(Modifier.fillMaxWidth(benchmarkProgress.coerceIn(0.0, 1.0).toFloat()).height(4.dp).background(RedTheme.colors.accentRed))
                            }
                        }
                        benchmarkResults.forEach { result ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(result.presetName.uiLabel(isPersian), modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                                Text(String.format(Locale.US, "%.1f", result.averageFPS) + if (isPersian) " فریم/ثانیه" else " FPS", color = Color.White, fontSize = 13.sp)
                            }
                        }
                        if (!isBenchmarkRunning && benchmarkRecommendation != null) {
                            ChalCaption((if (isPersian) "پیشنهاد: " else "Recommended: ") + benchmarkRecommendation.uiLabel(isPersian))
                            ChalActionButton(if (isPersian) "اعمال پیشنهاد" else "Apply recommendation", onApplyBenchmarkRecommendation,
                                Modifier.fillMaxWidth().testTag("chal_apply_recommendation"))
                            ChalCaption(if (isPersian) "اعمال پیشنهاد، وضوح را نیز روی مقدار آزمایش‌شده تنظیم می‌کند." else "Applying also sets the tested resolution.")
                        }
                    }
                    ChalPanelSection(if (isPersian) "دربارهٔ رندر" else "About this renderer") {
                        ChalCaption(modelDisclosure(isPersian, isXapkRenderer))
                        compatibilityReason?.let { ChalCaption(it.explanation(isPersian)) }
                        if (isXapkRenderer) ChalCaption(if (isPersian) "سرعت حرکت خودکار در این حالت برحسب واحدهای موتور بومی است." else "Auto-pan uses the native engine's units in Vulkan mode.")
                        diagnosticDetails?.let { details ->
                            ChalActionButton(if (isPersian) { if (showDetails) "بستن جزئیات فنی" else "جزئیات فنی" }
                                else { if (showDetails) "Hide technical details" else "Technical details" }, { showDetails = !showDetails })
                            if (showDetails) Text(details, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                        }
                        onRetryNative?.let { retry -> ChalActionButton(if (isPersian) "تلاش دوباره برای رندر بومی" else "Try native again", retry, enabled = editable) }
                    }
                    ChalActionButton(if (isPersian) "بازگرداندن تنظیمات پیش‌فرض" else "Restore defaults", { confirmDefaults = true },
                        Modifier.fillMaxWidth().testTag("chal_restore_defaults"), enabled = editable)
                }
                ChalPanelTab.MODULES -> {
                    ChalPanelSection(if (isPersian) "ماژول‌های تصویر" else "Visual modules") {
                        ChalFeatureToggle.available(params, isXapkRenderer, postProcessingAvailable).forEach { toggle ->
                            ChalToggleRow(toggle.label(isPersian), toggle.read(params.features), editable,
                                Modifier.testTag("chal_feature_${toggle.name.lowercase(Locale.US)}")) { enabled ->
                                onParamsChange { copy(features = toggle.write(features, enabled)) }
                            }
                        }
                    }
                    if (!fullPhysics) ChalCaption(if (isPersian) "در حالت ساده فقط قرص، ستارگان، حلقهٔ فوتونی و درخشش پشتیبانی می‌شوند."
                        else "Preview supports the disk, stars, photon ring and bloom only.")
                    if (!isXapkRenderer && fullPhysics) ChalCaption(if (isPersian) "مرجع سایه، دایرهٔ شوارتزشیلد است و چرخش را در نظر نمی‌گیرد؛ سایهٔ محاسبه‌شدهٔ کر نیست."
                        else "The shadow reference is a Schwarzschild circle that ignores spin, not a computed Kerr shadow.")
                    if (!isXapkRenderer && !params.reducedMotion && !isBenchmarkRunning) ChalPanelSection(if (isPersian) "تورهای نمایشی" else "Illustrative tours") {
                        if (isCinematic) ChalActionButton(if (isPersian) "توقف تور" else "Stop tour", onStopCinematic, Modifier.fillMaxWidth())
                        else ChalChoices(ChalCinematicTool.entries.toList(), { if (it == ChalCinematicTool.ORBIT) {
                            if (isPersian) "تور مداری" else "Orbit tour"
                        } else { if (isPersian) "سقوط نمایشی" else "Illustrative dive" } },
                            { cinematicMode?.name == it.name }, true, onStartCinematic)
                        ChalCaption(if (isPersian) "این مسیرهای سینمایی نمایشی هستند، نه مدارهای ژئودزیکی تأییدشده." else "These cinematic paths are illustrative, not validated geodesic trajectories.")
                    }
                }
            }
        }
    }

    if (confirmDefaults) AlertDialog(onDismissRequest = { confirmDefaults = false },
        title = { Text(if (isPersian) "بازگرداندن پیش‌فرض‌ها؟" else "Restore defaults?") },
        text = { Text(if (isPersian) "فیزیک، ماژول‌ها، کیفیت و دید بازنشانی می‌شوند. «بازنشانی دید» فقط دوربین را تغییر می‌دهد."
            else "Resets physics, modules, quality and view. Reset View changes only the camera.") },
        confirmButton = { TextButton(onClick = { confirmDefaults = false; onRestoreDefaults() }) { Text(if (isPersian) "بازگرداندن" else "Restore") } },
        dismissButton = { TextButton(onClick = { confirmDefaults = false }) { Text(if (isPersian) "لغو" else "Cancel") } })
}

@Composable
internal fun ChalPanelSection(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.035f), squircleShape(RedCornerRadius.lg))
        .padding(RedSpacing.sm), verticalArrangement = Arrangement.spacedBy(RedSpacing.xs)) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
internal fun ChalCaption(text: String) { Text(text, color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp) }

@Composable
private fun <T> ChalChoices(items: List<T>, label: (T) -> String, selected: (T) -> Boolean, enabled: Boolean, onClick: (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimum = 120.dp * LocalDensity.current.fontScale
        val columns = (maxWidth / minimum).toInt().coerceIn(1, 3)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { item ->
                        ChalChoiceButton(label(item), selected(item), { onClick(item) }, Modifier.weight(1f), enabled)
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ChalChoiceButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, enabled: Boolean) {
    Box(modifier.clip(squircleShape(RedCornerRadius.sm))
        .background(if (selected) RedTheme.colors.accentRed.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.055f))
        .border(1.dp, if (selected) RedTheme.colors.accentRed.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.18f), squircleShape(RedCornerRadius.sm))
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 0.95f else 0.4f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ChalActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.clip(squircleShape(RedCornerRadius.sm)).background(Color.White.copy(alpha = 0.07f))
        .border(1.dp, Color.White.copy(alpha = 0.2f), squircleShape(RedCornerRadius.sm))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 48.dp)
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 0.95f else 0.4f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChalToggleRow(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
        .heightIn(min = 48.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.White.copy(alpha = if (enabled) 0.9f else 0.4f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = RedTheme.colors.accentRed.copy(alpha = 0.65f),
            uncheckedThumbColor = Color.White.copy(alpha = 0.85f), uncheckedTrackColor = Color.White.copy(alpha = 0.1f)))
    }
}

@Composable
private fun ChalSlider(config: ChalParameterConfig, value: Double, isPersian: Boolean, enabled: Boolean = true,
    logarithmic: Boolean = false, displayFactor: Double = 1.0, onChange: (Double) -> Unit) {
    val safeValue = (if (value.isFinite()) value else config.default).coerceIn(config.min, config.max)
    val minPos = if (logarithmic) ln(config.min.coerceAtLeast(0.0001)) else config.min
    val maxPos = if (logarithmic) ln(config.max) else config.max
    val valuePos = if (logarithmic) ln(safeValue) else safeValue
    val label = if (isPersian) persianLabel(config.label) else config.label
    val tag = config.label.replace(" ", "_").lowercase(Locale.US)
    var editing by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val formatted = String.format(Locale.US, "%.${config.decimals}f", safeValue * displayFactor)

    Column(Modifier.fillMaxWidth().testTag("chal_slider_$tag")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, modifier = Modifier.weight(1f), color = Color.White.copy(alpha = if (enabled) 0.85f else 0.4f), fontSize = 13.sp)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp).testTag("chal_value_$tag")
                    .semantics { contentDescription = if (isPersian) "ویرایش $label: $formatted ${config.unit}" else "Edit $label: $formatted ${config.unit}" }
                    .clickable(enabled = enabled, role = Role.Button) { input = formatted; editing = true }
                    .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$formatted ${config.unit}", color = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Slider(value = valuePos.toFloat(), onValueChange = { raw ->
            val converted = if (logarithmic) exp(raw.toDouble()) else raw.toDouble()
            onChange(ChalSliderMath.quantize(converted, config))
        }, valueRange = minPos.toFloat()..maxPos.toFloat(),
            steps = if (logarithmic) 0 else (((config.max - config.min) / config.step).roundToInt() - 1).coerceAtLeast(0),
            enabled = enabled, colors = SliderDefaults.colors(thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.9f), inactiveTrackColor = Color.White.copy(alpha = 0.18f)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label })
    }
    if (editing) {
        val entered = ChalSliderMath.parseNumber(input)?.div(displayFactor)
        val valid = entered != null && entered in config.min..config.max
        val range = "${config.min * displayFactor} – ${config.max * displayFactor} ${config.unit}"
        // Edit in the scrollable panel/sheet itself. Avoid a second nested popup, keep the scene
        // visible, and let large text/IME users scroll the field and its actions into view.
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !valid,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("chal_numeric_input"),
                    label = { Text(if (isPersian) "مقدار" else "Value") })
            }
            ChalCaption((if (isPersian) "بازه: " else "Range: ") + range)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = valid && enabled,
                    onClick = { entered?.let { onChange(ChalSliderMath.quantize(it, config)) }; editing = false },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("chal_apply_number")) {
                    Text(if (isPersian) "اعمال" else "Apply")
                }
                TextButton(onClick = { editing = false }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (isPersian) "لغو" else "Cancel")
                }
            }
        }
    }
}

private fun persianLabel(label: String): String = when (label) {
    ChalSimulationConfig.MASS.label -> "جرم سیاه‌چاله"
    ChalSimulationConfig.ZOOM.label -> "فاصلهٔ دید"
    ChalSimulationConfig.SPIN.label -> "پارامتر چرخش"
    ChalSimulationConfig.LENSING.label -> "شدت همگرایی"
    ChalSimulationConfig.AUTO_SPIN.label -> "سرعت حرکت خودکار"
    ChalSimulationConfig.DISK_SIZE.label -> "شعاع بیشینهٔ قرص"
    ChalSimulationConfig.DISK_SCALE_HEIGHT.label -> "ضخامت قرص"
    ChalSimulationConfig.DISK_TEMP.label -> "دمای قرص"
    ChalSimulationConfig.DISK_DENSITY.label -> "چگالی نوری"
    ChalSimulationConfig.FRAME_DRAGGING.label -> "کشش چارچوب"
    ChalSimulationConfig.BLOOM_THRESHOLD.label -> "آستانهٔ درخشش"
    ChalSimulationConfig.BLOOM_INTENSITY.label -> "شدت درخشش"
    ChalSimulationConfig.RENDER_SCALE.label -> "مقیاس وضوح"
    else -> label
}
