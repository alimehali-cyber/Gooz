package com.zig.chal.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.alijafari.red.astronomy.ui.theme.RedCornerRadius
import com.alijafari.red.astronomy.ui.theme.RedSpacing
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.chal.config.ChalCameraConfig
import com.zig.chal.config.ChalFeatures
import com.zig.chal.config.ChalPresetName
import com.zig.chal.config.ChalRayTracingQuality
import com.zig.chal.config.ChalRuntimeState
import com.zig.chal.config.ChalSimulationParams
import com.zig.chal.config.ChalXapkScenario
import com.zig.chal.config.XapkSimulationSettingsStore
import com.zig.chal.render.ChalBenchmark
import com.zig.chal.render.ChalCamera
import com.zig.chal.render.ChalCompatibilityReason
import com.zig.chal.render.ChalQualityController
import com.zig.chal.render.ChalRenderer
import com.zig.chal.render.ChalSurfaceHost
import com.zig.gravity.ui.ImmersiveScreenState
import kotlinx.coroutines.delay

/** Header ownership is Chal-only: other immersive laboratories keep their existing app chrome. */
object ChalAppChrome {
    private var owners = 0
    var ownsHeader by mutableStateOf(false)
        private set
    fun enter() { owners++; ownsHeader = true }
    fun exit() { owners = (owners - 1).coerceAtLeast(0); ownsHeader = owners > 0 }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChalRoot(onBack: () -> Unit, modifier: Modifier = Modifier, startInPersian: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context.findActivity()
    DisposableEffect(activity) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (oldStatus != null) controller?.isAppearanceLightStatusBars = oldStatus
            if (oldNavigation != null) controller?.isAppearanceLightNavigationBars = oldNavigation
        }
    }
    val settingsStore = remember(context.applicationContext) { XapkSimulationSettingsStore(context.applicationContext) }
    // Save state, never the View/Activity itself. Recreated EGL/Vulkan resources get the saved pose,
    // paused state, tour progress and (GLES) shader phase before their first frame.
    val hostSaver = remember(context) {
        Saver<ChalSurfaceHost, ChalRuntimeState>(save = { it.saveRuntimeState() },
            restore = { ChalSurfaceHost(context, it.params, it) })
    }
    val surfaceView = rememberSaveable(saver = hostSaver) { ChalSurfaceHost(context, settingsStore.load()) }
    val qualityController = remember { ChalQualityController() }
    var params by remember { mutableStateOf(surfaceView.renderer.params) }
    var snapshot by remember { mutableStateOf(surfaceView.renderer.snapshot()) }
    var backend by remember { mutableStateOf(surfaceView.uiState()) }
    var showUi by rememberSaveable { mutableStateOf(true) }
    var benchmarkStarting by remember { mutableStateOf(false) }

    fun refresh() {
        val before = surfaceView.renderer
        val readout = before.snapshot()
        surfaceView.recoverNativeFailureIfNeeded()
        snapshot = if (surfaceView.renderer === before) readout else surfaceView.renderer.snapshot()
        params = surfaceView.renderer.params
        backend = surfaceView.uiState()
        if (snapshot.benchmarkState != ChalBenchmark.State.IDLE || backend.error != null || backend.native) benchmarkStarting = false
    }
    fun edit(change: ChalSimulationParams.() -> ChalSimulationParams) {
        qualityController.reset()
        params = surfaceView.renderer.editParams(change)
        settingsStore.save(surfaceView.renderer.paramsForPersistence())
    }
    fun leave() {
        surfaceView.onPause()
        settingsStore.save(surfaceView.renderer.paramsForPersistence())
        onBack()
    }
    BackHandler(onBack = ::leave)

    DisposableEffect(surfaceView) {
        ImmersiveScreenState.enter()
        ChalAppChrome.enter()
        onDispose {
            surfaceView.onPause()
            settingsStore.save(surfaceView.renderer.paramsForPersistence())
            surfaceView.onBackendChanged = null
            surfaceView.onTap = null
            surfaceView.release()
            ChalAppChrome.exit()
            ImmersiveScreenState.exit()
        }
    }
    DisposableEffect(lifecycleOwner, surfaceView) {
        val observer = LifecycleEventObserver { _: LifecycleOwner, event: Lifecycle.Event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surfaceView.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    // GLSurfaceView pauses after processing its queued camera/benchmark events.
                    surfaceView.onPause()
                    settingsStore.save(surfaceView.renderer.paramsForPersistence())
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) surfaceView.onResume()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(surfaceView, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastSaved = surfaceView.renderer.paramsForPersistence()
            var savedAt = 0L
            while (true) {
                refresh()
                if (params.automaticQuality && snapshot.isReady && !params.paused && !benchmarkStarting &&
                    snapshot.benchmarkState != ChalBenchmark.State.RUNNING) {
                    qualityController.observe(snapshot.currentFps, snapshot.targetFps, params.features.rayTracingQuality,
                        backend.native, android.os.SystemClock.elapsedRealtime().toDouble())?.let { quality ->
                        params = surfaceView.renderer.editParams { copy(features = features.copy(rayTracingQuality = quality)) }
                    }
                } else qualityController.reset()
                // Also persist gesture-owned camera state, without writing at every rendered frame.
                val live = surfaceView.renderer.paramsForPersistence()
                val now = android.os.SystemClock.elapsedRealtime()
                if (live != lastSaved && now - savedAt >= 1_500) {
                    settingsStore.save(live)
                    lastSaved = live
                    savedAt = now
                }
                delay(200)
            }
        }
    }
    SideEffect {
        surfaceView.onTap = { showUi = !showUi }
        surfaceView.onBackendChanged = { qualityController.reset(); benchmarkStarting = false; refresh() }
    }

    val actions = ChalActions(
        back = ::leave,
        editParams = ::edit,
        selectScenario = { scenario ->
            surfaceView.stopCinematic()
            edit { scenario.applyTo(this).copy(automaticQuality = false) }
            surfaceView.resetScenarioPitch()
        },
        selectPreset = { preset -> edit { copy(features = ChalFeatures.getPreset(preset), automaticQuality = false) } },
        selectQuality = { quality -> edit { copy(features = features.copy(rayTracingQuality = quality), automaticQuality = false) } },
        startCinematic = { tool -> surfaceView.startCinematic(if (tool == ChalCinematicTool.ORBIT) ChalCamera.CinematicMode.ORBIT else ChalCamera.CinematicMode.DIVE, params.reducedMotion) },
        stopCinematic = { surfaceView.stopCinematic() },
        startBenchmark = { benchmarkStarting = true; surfaceView.startBenchmark() },
        cancelBenchmark = { surfaceView.cancelBenchmark() },
        applyRecommendation = {
            snapshot.benchmarkRecommendation?.let { preset ->
                edit { copy(features = ChalFeatures.getPreset(preset), automaticQuality = false,
                    renderScale = snapshot.benchmarkRenderScale ?: renderScale) }
            }
        },
        restoreDefaults = {
            surfaceView.stopCinematic()
            edit { settingsStore.defaults() }
            surfaceView.resetCamera()
        },
        resetView = {
            edit { copy(zoom = ChalCameraConfig.DEFAULT_ZOOM, autoSpin = ChalCameraConfig.DEFAULT_AUTO_SPIN, paused = false) }
            surfaceView.resetCamera()
        },
        togglePause = { edit { copy(paused = !paused) } },
        retryRenderer = { surfaceView.retryRenderer(); refresh() },
        restoreWorkingOptions = { snapshot.lastWorkingFeatures?.let { working -> edit { copy(features = working) } } },
        retryNative = { surfaceView.retryNative(); refresh() }
    )
    CompositionLocalProvider(LocalLayoutDirection provides if (startInPersian) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Box(modifier.fillMaxSize().background(Color.Black).testTag("chal_root").semantics { testTagsAsResourceId = true }) {
            AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
            ChalInterface(params, snapshot, backend, startInPersian, showUi, { showUi = it }, actions,
                benchmarkStarting = benchmarkStarting)
        }
    }
}

internal data class ChalBackendUiState(
    val native: Boolean,
    val graphicsSupported: Boolean = true,
    val error: String? = null,
    val compatibilityReason: ChalCompatibilityReason? = null,
    val diagnosticDetails: String? = null,
    val canRetryNative: Boolean = false
)
private fun ChalSurfaceHost.uiState() = ChalBackendUiState(isUsingXapkRenderer, hasGraphicsSupport, errorMessage,
    compatibilityReason, diagnosticDetails, canRetryNative)

/** Real actions are supplied by the host; keeping the overlay separate also makes it UI-testable. */
internal data class ChalActions(
    val back: () -> Unit,
    val editParams: (ChalSimulationParams.() -> ChalSimulationParams) -> Unit,
    val selectScenario: (ChalXapkScenario) -> Unit,
    val selectPreset: (ChalPresetName) -> Unit,
    val selectQuality: (ChalRayTracingQuality) -> Unit,
    val startCinematic: (ChalCinematicTool) -> Unit,
    val stopCinematic: () -> Unit,
    val startBenchmark: () -> Unit,
    val cancelBenchmark: () -> Unit,
    val applyRecommendation: () -> Unit,
    val restoreDefaults: () -> Unit,
    val resetView: () -> Unit,
    val togglePause: () -> Unit,
    val retryRenderer: () -> Unit,
    val restoreWorkingOptions: () -> Unit,
    val retryNative: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun ChalInterface(params: ChalSimulationParams, snapshot: ChalRenderer.ChalSnapshot, backend: ChalBackendUiState,
    isPersian: Boolean, showUi: Boolean, onShowUiChange: (Boolean) -> Unit, actions: ChalActions,
    modifier: Modifier = Modifier, benchmarkStarting: Boolean = false) {
    var tab by rememberSaveable { mutableStateOf(ChalPanelTab.PHYSICS) }
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    var showControlsSheet by rememberSaveable { mutableStateOf(false) }
    var showMetrics by rememberSaveable { mutableStateOf(false) }
    var showErrorDetails by rememberSaveable { mutableStateOf(false) }
    val running = benchmarkStarting || snapshot.benchmarkState == ChalBenchmark.State.RUNNING

    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(RedSpacing.sm)) {
        val contentHeight = maxHeight
        val fontScale = LocalDensity.current.fontScale
        val sheetControls = contentHeight < 520.dp || fontScale > 1.5f
        val wide = !sheetControls && maxWidth >= 640.dp && maxWidth > maxHeight
        val panelWidth = minOf(360.dp, maxWidth * 0.43f)
        val sidebarHeight = contentHeight
        val bodyHeight = if (wide) (sidebarHeight - 160.dp).coerceAtLeast(48.dp)
            else (contentHeight * 0.30f - 64.dp).coerceAtLeast(48.dp)

        @Composable
        fun panel(maxBodyHeight: Dp) {
            ChalControlPanel(params = params, isPersian = isPersian, isCinematic = snapshot.isCinematic,
                isXapkRenderer = backend.native, tab = tab, onTabChange = { tab = it },
                onScenarioSelected = actions.selectScenario, onParamsChange = actions.editParams,
                onPresetSelected = actions.selectPreset, onQualitySelected = actions.selectQuality,
                onStartCinematic = actions.startCinematic, onStopCinematic = actions.stopCinematic,
                onStartBenchmark = actions.startBenchmark, onCancelBenchmark = actions.cancelBenchmark,
                onApplyBenchmarkRecommendation = actions.applyRecommendation, onRestoreDefaults = actions.restoreDefaults,
                isBenchmarkRunning = running, benchmarkPreset = snapshot.benchmarkPreset,
                benchmarkProgress = snapshot.benchmarkProgress, benchmarkResults = snapshot.benchmarkResults,
                benchmarkRecommendation = snapshot.benchmarkRecommendation, actualRenderScale = snapshot.actualRenderScale,
                postProcessingAvailable = snapshot.postProcessingAvailable, maxContentHeight = maxBodyHeight,
                cinematicMode = snapshot.cinematicMode, bloomThresholdMax = snapshot.bloomThresholdMax, compatibilityReason = backend.compatibilityReason,
                diagnosticDetails = backend.diagnosticDetails,
                onRetryNative = if (backend.canRetryNative) actions.retryNative else null)
        }
        @Composable
        fun commandRail() {
            if (!running) ChalCommandRail(isPersian, snapshot.isCinematic, params.paused, backend.native,
                if (snapshot.isCinematic) actions.stopCinematic else actions.resetView, actions.togglePause)
        }
        @Composable
        fun dock() {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChalActionButton(if (isPersian) {
                    if (controlsExpanded) "جمع کردن کنترل‌ها" else "نمایش کنترل‌ها"
                } else { if (controlsExpanded) "Collapse controls" else "Show controls" },
                    { controlsExpanded = !controlsExpanded }, Modifier.fillMaxWidth().testTag("chal_toggle_controls"))
                if (controlsExpanded) panel(bodyHeight)
                commandRail()
            }
        }
        @Composable
        fun header() {
            ChalHeader(isPersian, backend.native, fontScale <= 1.8f, actions.back, { onShowUiChange(false) },
                if (snapshot.isReady) ({ showMetrics = true }) else null)
            if (snapshot.isReady && fontScale <= 1.8f && contentHeight >= 360.dp) {
                ChalTelemetry(snapshot, isPersian, backend.native, compact = true)
            }
        }

        if (showUi) {
            if (wide && snapshot.isReady && backend.error == null) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { header() }
                    Column(Modifier.width(panelWidth).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) { dock() }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { header() }
                    if (snapshot.isReady && backend.error == null) {
                        if (sheetControls) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChalActionButton(if (isPersian) "کنترل‌ها" else "Controls", { showControlsSheet = true },
                                Modifier.fillMaxWidth().testTag("chal_open_controls"))
                            commandRail()
                        } else dock()
                    } else if (backend.error == null) {
                        ChalCaption(if (isPersian) "در حال آماده‌سازی رندر…" else "Preparing renderer…")
                    }
                }
            }
        } else {
            ChalChromeButton(Icons.Default.Visibility, if (isPersian) "نمایش رابط" else "Show interface", { onShowUiChange(true) },
                Modifier.align(Alignment.TopEnd).testTag("chal_show_ui"))
        }

        if (backend.error != null) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 420.dp).fillMaxWidth()
                .heightIn(max = contentHeight * 0.8f).clip(squircleShape(RedCornerRadius.lg))
                .background(Color(0xF21B1115)).border(1.dp, Color(0xFFB86A70), squircleShape(RedCornerRadius.lg))
                .verticalScroll(rememberScrollState()).padding(RedSpacing.md).testTag("chal_render_error"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (isPersian) "مشکل رندر" else "Rendering problem", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                ChalCaption(if (!backend.graphicsSupported) {
                    if (isPersian) "این دستگاه از OpenGL ES 3.0 لازم برای حالت سازگار پشتیبانی نمی‌کند. با دکمهٔ بازگشت به آزمایشگاه برگردید."
                    else "This device does not support the OpenGL ES 3.0 required for compatibility mode. Use Back to return to Lab."
                } else if (snapshot.lastWorkingFeatures != null && snapshot.lastWorkingFeatures != params.features) {
                    if (isPersian) "برخی گزینه‌ها اعمال نشدند؛ آخرین تصویر سالم حفظ شده است. می‌توانید رندر را بازسازی کنید یا گزینه‌های سالم را برگردانید."
                    else "Some options could not be applied; the last working image was kept. Retry rendering or restore the working options."
                } else { if (isPersian) "رندر آماده نشد. برای بازسازی آن تلاش دوباره را انتخاب کنید." else "Rendering could not start. Retry to rebuild the renderer." })
                if (backend.graphicsSupported) ChalActionButton(if (isPersian) "تلاش دوباره" else "Retry renderer", actions.retryRenderer, Modifier.fillMaxWidth())
                if (snapshot.lastWorkingFeatures != null && snapshot.lastWorkingFeatures != params.features) {
                    ChalActionButton(if (isPersian) "بازگرداندن گزینه‌های سالم" else "Restore working options", actions.restoreWorkingOptions, Modifier.fillMaxWidth())
                }
                ChalActionButton(if (isPersian) { if (showErrorDetails) "بستن جزئیات" else "جزئیات فنی" }
                    else { if (showErrorDetails) "Hide details" else "Technical details" }, { showErrorDetails = !showErrorDetails })
                if (showErrorDetails) Text(backend.error, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }
        if (showUi && showControlsSheet && backend.error == null && snapshot.isReady) {
            ModalBottomSheet(onDismissRequest = { showControlsSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF101014), contentColor = Color.White) {
                Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.padding(horizontal = RedSpacing.sm, vertical = RedSpacing.sm), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    panel((contentHeight * 0.68f - 96.dp).coerceAtLeast(48.dp))
                    commandRail()
                    ChalActionButton(if (isPersian) "بستن کنترل‌ها" else "Close controls", { showControlsSheet = false }, Modifier.fillMaxWidth())
                }
            }
        }
        if (showUi && showMetrics) {
            ModalBottomSheet(onDismissRequest = { showMetrics = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF101014), contentColor = Color.White) {
                Column(Modifier.fillMaxWidth().heightIn(max = contentHeight * 0.85f).verticalScroll(rememberScrollState())
                    .padding(RedSpacing.md), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isPersian) "داده‌های زنده" else "Live readouts", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    ChalTelemetry(snapshot, isPersian, backend.native, compact = false)
                    ChalCaption(modelDisclosure(isPersian, backend.native))
                    ChalCaption(if (isPersian) "r_g = GM/c² شعاع گرانشی جرم انتخاب‌شده است. داده‌های کر برای چرخش هم‌جهت هستند. «—» یعنی برآورد ناظر ایستای شوارتزشیلد در این فاصله تعریف نمی‌شود."
                        else "r_g = GM/c² for the selected mass. Kerr readouts are prograde. A dash means the stationary Schwarzschild observer estimate is not defined at this distance.")
                    ChalActionButton(if (isPersian) "بستن داده‌ها" else "Close readouts", { showMetrics = false }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ChalHeader(persian: Boolean, native: Boolean, showSubtitle: Boolean, onBack: () -> Unit, onHide: () -> Unit, onMetrics: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ChalChromeButton(Icons.AutoMirrored.Filled.ArrowBack, if (persian) "بازگشت به آزمایشگاه" else "Back to Lab", onBack)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CHAL", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showSubtitle) Text(if (native) { if (persian) "رندر بومی · Vulkan" else "Native · Vulkan" }
                else { if (persian) "تخمینی · OpenGL" else "Approximate · OpenGL" },
                color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        onMetrics?.let { ChalChromeButton(Icons.Default.Analytics, if (persian) "نمایش داده‌های زنده" else "Show live readouts", it) }
        ChalChromeButton(Icons.Default.VisibilityOff, if (persian) "پنهان کردن رابط" else "Hide interface", onHide, Modifier.testTag("chal_hide_ui"))
    }
}

@Composable
private fun ChalChromeButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.78f))
        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ChalCommandRail(persian: Boolean, cinematic: Boolean, paused: Boolean, native: Boolean, onResetOrStop: () -> Unit, onPause: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val resetLabel = if (cinematic) { if (persian) "توقف تور" else "Stop tour" } else { if (persian) "بازنشانی دید" else "Reset View" }
        ChalActionButton(resetLabel, onResetOrStop, Modifier.weight(1f).testTag("chal_reset_view"))
        if (!native) ChalActionButton(if (paused) { if (persian) "ادامه" else "Resume" } else { if (persian) "توقف" else "Pause" },
            onPause, Modifier.weight(1f).testTag("chal_pause"))
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}
