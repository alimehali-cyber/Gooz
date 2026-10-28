package com.zig.chal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.alijafari.red.astronomy.ui.theme.REDTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.zig.chal.config.*
import com.zig.chal.render.ChalBenchmark
import com.zig.chal.render.ChalCompatibilityReason
import com.zig.chal.render.ChalRenderer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ChalUiRegressionTest {
    @get:Rule val rule = createComposeRule()
    private var params by mutableStateOf(ChalSimulationParams())
    private var tab by mutableStateOf(ChalPanelTab.PHYSICS)
    private var showUi by mutableStateOf(true)
    private fun snapshot() = ChalRenderer.ChalSnapshot(params, 45, 22.2, params.features.rayTracingQuality,
        100.0, params.mass * 2, params.mass * 3, params.mass * 6, 0.95, 0.05, false, null, 45,
        actualRenderScale = 0.63)
    private fun edit(native: Boolean, change: ChalSimulationParams.() -> ChalSimulationParams) { params = ChalRenderPolicy.normalize(params.change(), native) }

    private fun panel(native: Boolean = false, persian: Boolean = false) {
        rule.setContent { REDTheme {
            CompositionLocalProvider(LocalLayoutDirection provides if (persian) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                ChalControlPanel(params, persian, false, native, tab, { tab = it }, { params = it.applyTo(params) },
                    { edit(native, it) }, { edit(native) { copy(features = ChalFeatures.getPreset(it)) } },
                    { quality -> edit(native) { copy(features = features.copy(rayTracingQuality = quality)) } },
                    {}, {}, {}, {}, {}, {}, false, null, 0.0, emptyList(), null, 0.63, true, 600.dp)
            }
        } }
    }
    private fun interfaceScreen(native: Boolean = false, persian: Boolean = false, fontScale: Float = 1f,
        backend: ChalBackendUiState = ChalBackendUiState(native), snapshotOverride: ChalRenderer.ChalSnapshot? = null) {
        val actions = ChalActions({}, { edit(native, it) }, { params = it.applyTo(params) },
            { preset -> edit(native) { copy(features = ChalFeatures.getPreset(preset)) } },
            { quality -> edit(native) { copy(features = features.copy(rayTracingQuality = quality)) } },
            {}, {}, {}, {}, {}, {}, { params = params.copy(zoom = 100.0) }, { params = params.copy(paused = !params.paused) }, {}, {}, {})
        rule.setContent { REDTheme {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale),
                LocalLayoutDirection provides if (persian) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    ChalInterface(params, snapshotOverride ?: snapshot(), backend, persian, showUi, { showUi = it }, actions)
                }
            }
        } }
    }

    @Test fun previewOffersOnlyWorkingModulesAndSwitchesExposeCheckedState() {
        params = params.copy(features = ChalFeatures.getPreset(ChalPresetName.MAXIMUM_PERFORMANCE))
        tab = ChalPanelTab.MODULES
        panel()
        rule.onNodeWithText("Gravitational lensing").assertDoesNotExist()
        rule.onNodeWithText("Doppler beaming").assertDoesNotExist()
        rule.onNodeWithText("Relativistic jets").assertDoesNotExist()
        rule.onNodeWithText("Spacetime Visualization").assertDoesNotExist()
        rule.onNodeWithText("Schwarzschild shadow reference").assertDoesNotExist()
        rule.onNodeWithTag("chal_feature_background_stars").assertIsOn().performClick().assertIsOff()
        rule.runOnIdle { assertFalse(params.features.backgroundStars) }
    }
    @Test fun nativeDoesNotAdvertiseUnsupportedBenchmarkToursOrShadowGuide() {
        tab = ChalPanelTab.SYSTEM
        panel(native = true)
        rule.onNodeWithText("Run benchmark").assertDoesNotExist()
        rule.onNodeWithText("Ultra").performScrollTo().performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(ChalRayTracingQuality.ULTRA, params.features.rayTracingQuality) }
        rule.onNodeWithTag("chal_tab_modules").performClick()
        rule.onNodeWithText("Orbit tour").assertDoesNotExist()
        rule.onNodeWithText("Schwarzschild shadow reference").assertDoesNotExist()
        rule.onNodeWithTag("chal_feature_volumetric_bloom").assertIsOn().performClick().assertIsOff()
    }
    @Test fun tabsHaveSelectedSemanticsAndLargeTouchTargets() {
        panel()
        rule.onNodeWithTag("chal_tab_physics").assertIsSelected().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("chal_tab_system").assertIsNotSelected().performClick().assertIsSelected()
    }
    @Test fun typedPersianNumbersApplyWithDeclaredPrecisionWithoutLosingViewState() {
        params = params.copy(cameraYaw = 0.72, zoom = 40.0)
        panel(persian = true)
        rule.onNodeWithTag("chal_value_black_hole_mass").performScrollTo()
        rule.onNodeWithTag("chal_value_black_hole_mass").assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag("chal_numeric_input").performScrollTo().assertIsDisplayed().performTextReplacement("۱۲٫۳")
        rule.onNodeWithTag("chal_apply_number").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(12.3, params.mass, 1e-9)
            assertEquals(0.72, params.cameraYaw, 1e-9)
            assertEquals(40.0, params.zoom, 0.0)
        }
    }
    @Test fun previewRemovesDiskSlidersThatWouldHaveNoEffect() {
        params = params.copy(features = ChalFeatures.getPreset(ChalPresetName.MAXIMUM_PERFORMANCE))
        panel()
        rule.onNodeWithTag("chal_slider_disk_temp").assertDoesNotExist()
        rule.onNodeWithTag("chal_slider_frame_drag").assertDoesNotExist()
    }
    @Test fun disablingAutoResolutionFreezesTheActualRatherThanRequestedScale() {
        tab = ChalPanelTab.SYSTEM
        params = params.copy(adaptiveResolution = true, renderScale = 1.0)
        panel()
        rule.onNodeWithText("Auto resolution").performScrollTo().performClick()
        rule.runOnIdle { assertFalse(params.adaptiveResolution); assertEquals(0.63, params.renderScale, 1e-9) }
    }
    @Test fun hidingInterfaceRemovesAllChalChromeAndCanBeReversed() {
        interfaceScreen()
        rule.onNodeWithTag("chal_hide_ui").performClick()
        rule.onNodeWithText("CHAL").assertDoesNotExist()
        rule.onNodeWithTag("chal_pause").assertDoesNotExist()
        rule.onNodeWithTag("chal_show_ui").assertIsDisplayed().performClick()
        rule.onNodeWithText("CHAL").assertIsDisplayed()
        rule.onNodeWithTag("chal_pause").assertIsDisplayed()
    }
    @Test fun normalCompatibilityIsNotAnErrorOverTheScene() {
        interfaceScreen(backend = ChalBackendUiState(false, compatibilityReason = ChalCompatibilityReason.ABI))
        rule.onNodeWithTag("chal_render_error").assertDoesNotExist()
        rule.onNodeWithText("Approximate · OpenGL").assertIsDisplayed()
        rule.onRoot().captureRoboImage(filePath = "build/chal-ui/english-phone.png")
    }
    @Test fun nativePauseButtonIsRemovedNotLeftInert() {
        interfaceScreen(native = true)
        rule.onNodeWithTag("chal_pause").assertDoesNotExist()
        rule.onNodeWithTag("chal_reset_view").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
    @Test fun unsupportedGraphicsDoesNotOfferAnInertRetryButton() {
        interfaceScreen(backend = ChalBackendUiState(false, graphicsSupported = false, error = "no GLES3"))
        rule.onNodeWithText("Retry renderer").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back to Lab").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w320dp-h568dp-port-xxhdpi", sdk = [36])
    fun smallPersianPhoneAndLargeTextUseScrollableControlsSheet() {
        interfaceScreen(persian = true, fontScale = 1.8f)
        rule.onNodeWithTag("chal_open_controls").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("chal_control_panel").assertIsDisplayed()
        rule.onNodeWithTag("chal_tab_system").performClick().assertIsSelected()
        rule.onRoot().captureRoboImage(filePath = "build/chal-ui/persian-small-large-font.png")
    }
    @Test @Config(qualifiers = "w640dp-h360dp-land-xxhdpi", sdk = [36])
    fun shortLandscapeDoesNotSqueezeAFixedPanelOverTheScene() {
        interfaceScreen()
        rule.onNodeWithTag("chal_open_controls").assertIsDisplayed().performClick()
        rule.onNodeWithTag("chal_tab_modules").performClick().assertIsSelected()
        rule.onRoot().captureRoboImage(filePath = "build/chal-ui/english-landscape.png")
    }
}
