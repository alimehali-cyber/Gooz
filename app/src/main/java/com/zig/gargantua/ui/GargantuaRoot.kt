package com.zig.gargantua.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.gargantua.renderer.GargantuaAnimation
import com.zig.gargantua.renderer.GargantuaGpuDiagnostics
import com.zig.gargantua.renderer.GargantuaRenderState
import com.zig.gargantua.renderer.GargantuaSurfaceView
import com.zig.gargantua.renderer.GargantuaTelemetry
import com.zig.gargantua.util.GargantuaCapability
import com.zig.gravity.ui.ImmersiveScreenState
import java.util.Locale
import kotlinx.coroutines.delay

/** Root screen for the Gargantua renderer and its deliberately small control surface. */
@Composable
fun GargantuaRoot(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    startInPersian: Boolean = false,
    startInDarkTheme: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isPersian = startInPersian
    val isGles3Supported = remember(context) { GargantuaCapability.isGles3Supported(context) }

    BackHandler(enabled = true, onBack = onBack)
    DisposableEffect(Unit) {
        ImmersiveScreenState.enter()
        onDispose { ImmersiveScreenState.exit() }
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isPersian) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        if (!isGles3Supported) {
            GargantuaUnsupportedScreen(onBack, isPersian, modifier)
        } else {
            GargantuaRendererScreen(
                onBack = onBack,
                isPersian = isPersian,
                startInDarkTheme = startInDarkTheme,
                lifecycleOwner = lifecycleOwner,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun GargantuaUnsupportedScreen(
    onBack: () -> Unit,
    isPersian: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C14))
            .testTag("gargantua_unsupported_root")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GargantuaIconButton(
                onClick = onBack,
                contentDescription = if (isPersian) "بازگشت" else "Back",
                modifier = Modifier.testTag("gargantua_back_button")
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPersian) "گارگانتوا" else "Gargantua",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isPersian) "عدم پشتیبانی سخت‌افزاری" else "Hardware Not Supported",
                    color = Color(0xFFEF9A9A),
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .clip(squircleShape(20.dp))
                .background(Color(0xEE161A26))
                .border(1.dp, Color(0x33FFA726), squircleShape(20.dp))
                .padding(24.dp)
                .testTag("gargantua_unsupported_fallback"),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFA726)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFA726),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = if (isPersian) "نیازمند OpenGL ES 3.x" else "OpenGL ES 3.x Required",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (isPersian) {
                        "آزمایشگاه نسبیتی گارگانتوا به OpenGL ES 3.0 نیاز دارد که توسط پردازنده گرافیکی این دستگاه پشتیبانی نمی‌شود."
                    } else {
                        "Gargantua's laboratory requires OpenGL ES 3.0 or higher, which is not supported by this device's GPU."
                    },
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )
                ElevatedButton(
                    onClick = onBack,
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = Color(0xFF263238),
                        contentColor = Color(0xFF90CAF9)
                    ),
                    shape = squircleShape(12.dp),
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("gargantua_fallback_back_button")
                ) {
                    Text(if (isPersian) "بازگشت" else "Return")
                }
            }
        }
    }
}

private enum class GargantuaPanel { QUALITY, ANIMATION, INFO }

@Composable
private fun GargantuaRendererScreen(
    onBack: () -> Unit,
    isPersian: Boolean,
    startInDarkTheme: Boolean,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier
) {
    var surfaceViewRef by remember { mutableStateOf<GargantuaSurfaceView?>(null) }
    var telemetry by remember { mutableStateOf(GargantuaTelemetry()) }
    var renderState by remember { mutableStateOf(GargantuaRenderState()) }
    var openPanel by remember { mutableStateOf<GargantuaPanel?>(null) }

    DisposableEffect(lifecycleOwner, surfaceViewRef) {
        val surfaceView = surfaceViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> surfaceView?.onPause()
                Lifecycle.Event.ON_RESUME -> surfaceView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (surfaceView != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            surfaceView.requestFrameForLifecycle()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            surfaceView?.onPause()
        }
    }

    LaunchedEffect(surfaceViewRef) {
        val surfaceView = surfaceViewRef ?: return@LaunchedEffect
        while (true) {
            telemetry = surfaceView.renderer.stateHolder.getTelemetry()
            renderState = surfaceView.renderer.stateHolder.getState()
            delay(250)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("gargantua_root")
    ) {
        // The surface owns the whole screen so orbit, pan, and pinch remain available anywhere
        // that is not an explicit control. Panels are intentionally small and edge anchored.
        AndroidView(
            factory = { context ->
                GargantuaSurfaceView(context).also { surfaceView ->
                    surfaceView.renderer.stateHolder.updateState {
                        it.copy(isDarkTheme = startInDarkTheme, isPersian = isPersian)
                    }
                    surfaceView.onRenderTap = { openPanel = null }
                    surfaceViewRef = surfaceView
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        surfaceView.onResume()
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("gargantua_gl_surface")
        )

        GargantuaTopBar(
            isPersian = isPersian,
            onBack = onBack,
            onReset = { surfaceViewRef?.resetCamera() },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        Text(
            text = statusLine(telemetry, isPersian),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
                .testTag("gargantua_status_line"),
            color = Color(0xFFD0D8E8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )

        if (renderState.diagnosticView != 0) {
            GpuDiagnosticsOverlay(
                status = telemetry.gpuDiagnosticsStatus,
                report = telemetry.gpuDiagnosticsReport,
                view = renderState.diagnosticView,
                onSelectView = { index ->
                    surfaceViewRef?.renderer?.stateHolder?.updateState { current ->
                        if (current.diagnosticView == 0) current else current.copy(diagnosticView = index)
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 76.dp, start = 8.dp, end = 8.dp)
                    .fillMaxWidth()
                    .testTag("gargantua_gpu_diagnostics")
            )
        }

        openPanel?.let { panel ->
            GargantuaPanelCard(
                panel = panel,
                isPersian = isPersian,
                telemetry = telemetry,
                renderState = renderState,
                surfaceView = surfaceViewRef,
                onClose = { openPanel = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 76.dp)
                    .fillMaxWidth()
                    .testTag("gargantua_panel")
            )
        }

        GargantuaDock(
            isPersian = isPersian,
            selectedPanel = openPanel,
            onPanelSelected = { selected ->
                openPanel = if (openPanel == selected) null else selected
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("gargantua_three_button_dock")
        )
    }
}

@Composable
private fun GargantuaTopBar(
    isPersian: Boolean,
    onBack: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GargantuaIconButton(
            onClick = onBack,
            contentDescription = if (isPersian) "بازگشت" else "Back",
            modifier = Modifier.testTag("gargantua_back_button")
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isPersian) "گارگانتوا" else "Gargantua",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isPersian) "شبیه‌سازی کر" else "Kerr spacetime",
                color = Color(0xFF8FA3BD),
                fontSize = 10.sp,
                maxLines = 1,
                softWrap = false
            )
        }
        GargantuaIconButton(
            onClick = onReset,
            contentDescription = if (isPersian) "بازنشانی دوربین" else "Reset camera",
            modifier = Modifier.testTag("gargantua_reset_camera_button")
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = if (isPersian) "بازنشانی دوربین" else "Reset camera",
                tint = Color(0xFF90CAF9),
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
private fun GargantuaIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .clip(CircleShape)
            .background(Color(0xAA181B26))
            .border(1.dp, Color(0x448899AA), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun GargantuaDock(
    isPersian: Boolean,
    selectedPanel: GargantuaPanel?,
    onPanelSelected: (GargantuaPanel) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0xEE111622))
            .border(1.dp, Color(0x33446688)),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            GargantuaPanel.QUALITY to if (isPersian) "کیفیت" else "Quality",
            GargantuaPanel.ANIMATION to if (isPersian) "حرکت" else "Animation",
            GargantuaPanel.INFO to if (isPersian) "اطلاعات" else "Info"
        ).forEach { (panel, label) ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clickable { onPanelSelected(panel) }
                    .background(
                        if (selectedPanel == panel) Color(0xFF263B52) else Color.Transparent
                    )
                    .testTag("gargantua_dock_${panel.name.lowercase(Locale.US)}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selectedPanel == panel) Color(0xFFB3E5FC) else Color(0xFFD7E3F4),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun GargantuaPanelCard(
    panel: GargantuaPanel,
    isPersian: Boolean,
    telemetry: GargantuaTelemetry,
    renderState: GargantuaRenderState,
    surfaceView: GargantuaSurfaceView?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val panelScrollState = rememberScrollState()
    Box(
        modifier = modifier
            .heightIn(max = 300.dp)
            .clip(squircleShape(14.dp))
            .background(Color(0xF2141A27))
            .border(1.dp, Color(0x55446688), squircleShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier.verticalScroll(panelScrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = when (panel) {
                        GargantuaPanel.QUALITY -> if (isPersian) "کیفیت نمونه‌برداری" else "Quality"
                        GargantuaPanel.ANIMATION -> if (isPersian) "حرکت دیسک" else "Animation"
                        GargantuaPanel.INFO -> if (isPersian) "اطلاعات رندر" else "Info"
                    },
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isPersian) "بستن" else "Close",
                        color = Color(0xFF90CAF9),
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            when (panel) {
                GargantuaPanel.QUALITY -> QualityPanel(isPersian, renderState, surfaceView)
                GargantuaPanel.ANIMATION -> AnimationPanel(isPersian, renderState, surfaceView)
                GargantuaPanel.INFO -> InfoPanel(isPersian, telemetry, renderState, surfaceView)
            }
        }
    }
}

@Composable
private fun QualityPanel(
    isPersian: Boolean,
    state: GargantuaRenderState,
    surfaceView: GargantuaSurfaceView?
) {
    val currentMode = state.debugCoarseSamplingBlockSize
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (isPersian) {
                "کیفیت نمونه‌برداری · ${currentMode}×${currentMode}"
            } else {
                "Sampling quality · ${currentMode}×${currentMode}"
            },
            color = Color(0xFFB0BEC5),
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false
        )
        GargantuaSamplingSelector(
            surfaceView = surfaceView,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("gargantua_quality_selector")
        )
        Text(
            text = if (isPersian) {
                "حالت SAMPLE تعداد پرتوهای اولیه را کاهش می‌دهد و رزولوشن خروجی را ثابت نگه می‌دارد."
            } else {
                "SAMPLE changes the ray-grid density while keeping the output resolution fixed."
            },
            color = Color(0xFF90A4AE),
            fontSize = 10.sp,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Clip
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Relativistic Doppler Effect Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    surfaceView?.renderer?.stateHolder?.updateState { it.copy(enableDoppler = !it.enableDoppler) }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPersian) "اثر دوپلر نسبیتی" else "Relativistic Doppler Effect",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = if (isPersian) {
                        "خاموش: حالت سینمایی نولان (پیش‌فرض) · روشن: پرتوتابی دقیق نسبیت عام (g⁴)"
                    } else {
                        "OFF: Christopher Nolan Movie Mode (Default) · ON: Strict GR Beaming (g⁴)"
                    },
                    color = Color(0xFF90A4AE),
                    fontSize = 10.sp,
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Clip
                )
            }
            Switch(
                checked = state.enableDoppler,
                onCheckedChange = { checked ->
                    surfaceView?.renderer?.stateHolder?.updateState { it.copy(enableDoppler = checked) }
                },
                modifier = Modifier.testTag("gargantua_doppler_switch")
            )
        }
    }
}

@Composable
private fun AnimationPanel(
    isPersian: Boolean,
    state: GargantuaRenderState,
    surfaceView: GargantuaSurfaceView?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (isPersian) "دامنه حرکت" else "Amplitude",
            color = Color(0xFFB0BEC5),
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GargantuaAnimation.AMPLITUDE_STEPS.forEach { (enabled, amplitude) ->
                PanelButton(
                    label = if (enabled) "±${amplitude}%" else "OFF",
                    selected = if (enabled) {
                        state.enableAnimation && state.animationAmplitudePercent == amplitude
                    } else {
                        !state.enableAnimation
                    },
                    onClick = {
                        surfaceView?.renderer?.stateHolder?.updateState { old ->
                            if (enabled) {
                                old.copy(
                                    enableWorkloadTelemetry = false,
                                    enableAnimation = true,
                                    animationAmplitudePercent = amplitude
                                )
                            } else {
                                old.copy(
                                    enableAnimation = false,
                                    animationAmplitudePercent = 0
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isPersian) "دوره مدار ISCO" else "ISCO period",
                color = Color(0xFFB0BEC5),
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false
            )
            GargantuaAnimation.AnimationSpeed.values().forEach { speed ->
                PanelButton(
                    label = if (isPersian) speed.persianLabel else speed.englishLabel,
                    selected = state.animationSpeed == speed,
                    onClick = {
                        surfaceView?.renderer?.stateHolder?.updateState { old ->
                            old.copy(animationSpeed = speed)
                        }
                    },
                    modifier = Modifier.weight(0.7f)
                )
            }
        }
        Text(
            text = if (isPersian) {
                "TEL و ANIM هم‌زمان فعال نمی‌شوند."
            } else {
                "TEL and ANIM cannot be enabled together."
            },
            color = Color(0xFF90A4AE),
            fontSize = 10.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InfoPanel(
    isPersian: Boolean,
    telemetry: GargantuaTelemetry,
    state: GargantuaRenderState,
    surfaceView: GargantuaSurfaceView?
) {
    val telScrollState = rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "${telemetry.animationStatus} · ${telemetry.fps.toInt()} FPS",
                modifier = Modifier.weight(1f),
                color = Color(0xFFB3E5FC),
                fontSize = 11.sp,
                maxLines = 4,
                softWrap = true,
                overflow = TextOverflow.Clip
            )
            PanelButton(
                label = if (state.enableWorkloadTelemetry) "TEL ON" else "TEL OFF",
                selected = state.enableWorkloadTelemetry,
                onClick = {
                    surfaceView?.renderer?.stateHolder?.updateState { current ->
                        if (current.enableWorkloadTelemetry) {
                            current.copy(enableWorkloadTelemetry = false)
                        } else {
                            current.copy(
                                enableWorkloadTelemetry = true,
                                enableAnimation = false,
                                animationAmplitudePercent = 0
                            )
                        }
                    }
                },
                modifier = Modifier.weight(0.55f)
            )
        }

        // TEMPORARY physical-GPU forensic run: one tap starts the fully automated diagnostic matrix.
        PanelButton(
            label = if (state.diagnosticView == 0) "DIAG: OFF (tap to run forensic diagnostics)" else "DIAG: ON (tap to turn off)",
            selected = state.diagnosticView != 0,
            onClick = {
                surfaceView?.renderer?.stateHolder?.updateState { current ->
                    current.copy(
                        diagnosticView = if (current.diagnosticView == 0) GargantuaGpuDiagnostics.View.D6_FINAL.ordinal else 0
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = telemetry.animationDiagnostics,
            color = Color(0xFF90A4AE),
            fontSize = 9.sp,
            maxLines = 4,
            softWrap = true,
            overflow = TextOverflow.Clip
        )

        if (telemetry.animationStatus.startsWith("ANIM FAILED")) {
            Text(
                text = telemetry.animationStatus,
                color = Color(0xFFFFAB91),
                fontSize = 9.sp,
                maxLines = Int.MAX_VALUE,
                softWrap = true,
                overflow = TextOverflow.Clip
            )
        }

        if (state.enableWorkloadTelemetry) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 96.dp)
                    .verticalScroll(telScrollState)
                    .background(Color(0x55263342), squircleShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = "TEL: ${telemetry.adaptiveWorkload}",
                    color = Color(0xFFFFCC80),
                    fontSize = 9.sp,
                    maxLines = Int.MAX_VALUE,
                    softWrap = true,
                    overflow = TextOverflow.Clip
                )
            }
        }

        Text(
            text = if (isPersian) {
                "${telemetry.renderResolution} · HDR ${if (telemetry.isHdrActive) "روشن" else "خاموش"}"
            } else {
                "${telemetry.renderResolution} · HDR ${if (telemetry.isHdrActive) "on" else "off"}"
            },
            color = Color(0xFF90A4AE),
            fontSize = 9.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PanelButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(squircleShape(8.dp))
            .background(if (selected) Color(0xFF365A75) else Color(0xFF263242))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun statusLine(telemetry: GargantuaTelemetry, isPersian: Boolean): String {
    val fps = if (telemetry.fps > 0f) String.format(Locale.US, "%.0f FPS", telemetry.fps) else "-- FPS"
    return if (isPersian) {
        "${telemetry.animationStatus} · $fps"
    } else {
        "${telemetry.animationStatus} · $fps"
    }
}

/** TEMPORARY forensic run overlay: progress while running; COPY REPORT and view buttons afterwards. */
@Composable
private fun GpuDiagnosticsOverlay(
    status: String,
    report: String,
    view: Int,
    onSelectView: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val finished = report.isNotEmpty()
    Column(
        modifier = modifier.background(Color(0xC0000000), squircleShape(6.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = status.ifEmpty { "FORENSIC RUN\nstarting..." },
            color = if (finished) Color(0xFFA5D6A7) else Color(0xFFFFE082),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        if (finished) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PanelButton(
                    label = "COPY REPORT",
                    selected = false,
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("gargantua-forensic-report", report))
                    },
                    modifier = Modifier.weight(1f)
                )
                PanelButton(
                    label = if (expanded) "HIDE TEXT" else "SHOW TEXT",
                    selected = expanded,
                    onClick = { expanded = !expanded },
                    modifier = Modifier.weight(1f)
                )
            }
            val views = listOf(
                GargantuaGpuDiagnostics.View.D0_STATIC_HDR to "D0",
                GargantuaGpuDiagnostics.View.D1_RAY_RECORDS to "D1",
                GargantuaGpuDiagnostics.View.D2_MATERIAL to "D2",
                GargantuaGpuDiagnostics.View.D5_PRE_TONEMAP to "D5",
                GargantuaGpuDiagnostics.View.D7_HIGHER_ORDER to "D7",
                GargantuaGpuDiagnostics.View.D6_FINAL to "FINAL"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((v, label) in views) {
                    PanelButton(
                        label = label,
                        selected = view == v.ordinal,
                        onClick = { onSelectView(v.ordinal) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // Lensing views of the geodesic classification (available after the LENS capture of phase D).
            val lensViews = listOf(
                GargantuaGpuDiagnostics.View.L1_CAPTURE to "L1",
                GargantuaGpuDiagnostics.View.L2_DISK_HIT to "L2",
                GargantuaGpuDiagnostics.View.L3_R_HIT to "L3",
                GargantuaGpuDiagnostics.View.L4_PHI_HIT to "L4",
                GargantuaGpuDiagnostics.View.L5_HO_COUNT to "L5",
                GargantuaGpuDiagnostics.View.L6_BOUNDARY to "L6"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((v, label) in lensViews) {
                    PanelButton(
                        label = label,
                        selected = view == v.ordinal,
                        onClick = { onSelectView(v.ordinal) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (expanded) {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(scroll)) {
                    Text(
                        text = report,
                        color = Color(0xFFE0F2F1),
                        fontSize = 8.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        softWrap = true
                    )
                }
            }
        }
    }
}
