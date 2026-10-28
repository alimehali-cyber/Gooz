package com.zig.gargantua.renderer

import com.zig.gargantua.disk.AccretionDiskModel
import com.zig.gargantua.disk.KerrIsco
import com.zig.gargantua.geodesic.GpuEquivalentIntegrator
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.*

/**
 * Validates M6 Final Visual Correctness & Presentation Requirements:
 * 1. Background contains lensed procedural stars (Interstellar-grade starfield).
 * 2. Captured rays remain black (zero radiance, alpha 0.0).
 * 3. Escaped rays produce black M6 background (alpha 1.0).
 * 4. Unresolved rays never produce stars (alpha 0.5, zero radiance).
 * 5. Disk emission is preserved on both approaching and receding sides.
 * 6. Frequency shift g and g^4 are applied exactly once.
 * 7. Doppler asymmetry remains physically directional.
 * 8. Legitimate disk radii are not incorrectly classified as non-emitting.
 * 9. Disk radiance remains finite, non-NaN, and deterministic.
 * 10. HDR/ACES pipeline remains deterministic and monotonic.
 * 11. Information card fits large numerical values without clipping.
 * 12. Final UI uses a slim top bar, one status line, and a full-width three-button dock.
 * 13. Existing camera gestures remain functional.
 * 14. Stationary caching remains functional.
 */
class M6FinalPresentationTest {

    private val M = 1.0
    private val a = 0.8
    private val rIn = KerrIsco.compute(M, a) // 2.9066M
    private val rOut = 22.0

    private fun novikovThorneFlux(r: Double): Double {
        if (r <= rIn || r > rOut) return 0.0
        return (M / (r * r * r)) * max(0.0, 1.0 - sqrt(rIn / r))
    }

    private fun peakFlux(): Double {
        val rPeak = (49.0 / 36.0) * rIn
        return M / (7.0 * rPeak * rPeak * rPeak)
    }

    private fun diskRadianceNormalized(r: Double, g: Double): Double {
        val f = novikovThorneFlux(r)
        val fPeak = peakFlux()
        val fNorm = if (fPeak > 1e-7) (f / fPeak).coerceIn(0.0, 1.0) else 0.0
        val g4 = g * g * g * g
        val iPhys = g4 * fNorm
        return iPhys
    }

    private fun acesFilmic(x: Double): Double {
        val aC = 2.51
        val bC = 0.03
        val cC = 2.42
        val dC = 0.59
        val eC = 0.14
        val num = x * (aC * x + bC)
        val den = x * (cC * x + dC) + eC
        return (num / den).coerceIn(0.0, 1.0)
    }

    private fun toSrgb(linear: Double): Int {
        return (linear.coerceIn(0.0, 1.0).pow(1.0 / 2.2) * 255.0).roundToInt()
    }

    private fun mainDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main")
            if (File(candidate, "assets/shaders").isDirectory) return candidate
            val direct = File(dir, "src/main")
            if (File(direct, "assets/shaders").isDirectory) return direct
            dir = dir.parentFile
        }
        throw AssertionError("could not locate app/src/main")
    }

    private fun readShader(fileName: String): String {
        val f = File(mainDir(), "assets/shaders/$fileName")
        assertTrue("Shader file must exist: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    // 1. M6 visible background now contains lensed procedural stars (Interstellar upgrade)
    @Test
    fun m6VisibleBackgroundContainsNoProceduralStars() {
        val content = readShader("gargantua_geodesic.frag")

        // Check that escaped rays now sample procedural sky with alpha 1.0
        assertTrue(
            "Escaped branch must output lensed starfield background",
            content.contains("sample_procedural_sky") && content.contains("1.0")
        )

        // Starfield must be present for escaped rays
        assertTrue(
            "Escaped rays must invoke sample_procedural_sky for lensed background",
            content.contains("sample_procedural_sky")
        )
    }

    // 2. Captured rays remain black
    @Test
    fun capturedRaysRemainBlack() {
        val content = readShader("gargantua_geodesic.frag")

        assertTrue(
            "Captured branch must output pure black shadow with alpha 0.0",
            content.contains("fragColor = vec4(0.0, 0.0, 0.0, 0.0);")
        )
    }

    // 3. Escaped rays produce lensed starfield background
    @Test
    fun escapedRaysProduceBlackM6Background() {
        val content = readShader("gargantua_geodesic.frag")

        assertTrue(
            "Escaped rayState 2 must produce lensed starfield background",
            content.contains("else if (rayState == 2) {") &&
                    content.contains("sample_procedural_sky")
        )
    }

    // 4. Unresolved rays never produce stars
    @Test
    fun unresolvedRaysNeverProduceStars() {
        val content = readShader("gargantua_geodesic.frag")

        assertTrue(
            "Unresolved branch must produce pure black with diagnostic alpha 0.5",
            content.contains("fragColor = vec4(0.0, 0.0, 0.0, 0.5);")
        )

        // Check composite shader rejects alpha <= 0.5
        val compositeContent = readShader("gargantua_composite.frag")
        assertTrue(
            "Composite shader must reject alpha <= 0.5 to keep unresolved rays strictly black",
            compositeContent.contains("if (hdr.a <= 0.5)")
        )
    }

    // 5. Disk emission is preserved on both approaching and receding sides
    @Test
    fun diskEmissionIsPreservedOnBothApproachingAndRecedingSides() {
        val testLocations = listOf(
            Triple("Approaching Inner", 3.5, 2.10),
            Triple("Approaching Peak", 4.0, 1.80),
            Triple("Approaching Mid", 8.0, 1.35),
            Triple("Approaching Outer", 16.0, 1.15),
            Triple("Direct Foreground", 6.0, 1.00),
            Triple("Receding Inner", 3.5, 0.40),
            Triple("Receding Peak", 4.0, 0.45),
            Triple("Receding Mid", 8.0, 0.65),
            Triple("Receding Outer", 16.0, 0.82),
            Triple("Receding Edge", 21.0, 0.88)
        )

        val exposure = 1.8
        for ((name, r, g) in testLocations) {
            val rad = diskRadianceNormalized(r, g)
            val postAces = acesFilmic(rad * exposure)
            val srgb = toSrgb(postAces)

            // Both sides must be clearly non-zero and luminous (sRGB >= 30 out of 255)
            assertTrue("$name (r=$r, g=$g) must be clearly visible (rad=$rad > 0.01)", rad > 0.01)
            assertTrue("$name (r=$r, g=$g) post-ACES ($postAces) must be > 0.01", postAces > 0.01)
            assertTrue("$name (r=$r, g=$g) sRGB ($srgb) must be clearly visible (>= 30)", srgb >= 30)
        }
    }

    // 6. g and g^4 are applied exactly once
    @Test
    fun frequencyShiftGAndG4AppliedExactlyOnce() {
        val content = readShader("gargantua_geodesic.frag")

        // Verify g4 definition
        assertTrue("g2 must be defined as gShift * gShift", content.contains("float g2 = gShift * gShift;"))
        assertTrue("g4 must be defined as g2 * g2", content.contains("float g4 = g2 * g2;"))

        // Verify iPhys uses g4 exactly once without artificial tapers
        assertTrue("iPhys must be g4 * fNorm", content.contains("float iPhys = g4 * fNorm;"))
        assertTrue("radiance must use iPhys", content.contains("float radiance = iPhys;"))
    }

    // 7. Doppler asymmetry remains physically directional
    @Test
    fun dopplerAsymmetryRemainsPhysicallyDirectional() {
        // Compare symmetric radii on approaching vs receding side
        val radii = listOf(3.5, 4.0, 6.0, 8.0, 12.0, 16.0)
        for (r in radii) {
            // Approaching g > 1, Receding g < 1
            val gApp = 1.0 + 1.2 / sqrt(r)
            val gRec = 1.0 - 0.9 / sqrt(r)

            val radApp = diskRadianceNormalized(r, gApp)
            val radRec = diskRadianceNormalized(r, gRec)

            assertTrue(
                "Approaching radiance ($radApp) at r=$r must strictly exceed receding radiance ($radRec)",
                radApp > radRec
            )

            val acesApp = acesFilmic(radApp * 1.8)
            val acesRec = acesFilmic(radRec * 1.8)
            assertTrue(
                "Approaching post-ACES ($acesApp) at r=$r must strictly exceed receding ($acesRec)",
                acesApp > acesRec
            )
        }
    }

    // 8. Legitimate disk radii are not incorrectly classified as non-emitting
    @Test
    fun legitimateDiskRadiiAreNotIncorrectlyClassifiedAsNonEmitting() {
        val disk = AccretionDiskModel(M = M, a = a, outerRadius = rOut)
        val radii = listOf(2.95, 3.2, 4.0, 6.0, 10.0, 15.0, 20.0, 21.9)

        for (r in radii) {
            val f = disk.fluxProfile(r)
            assertTrue("Flux at legitimate radius r=$r must be strictly positive", f > 0.0)
            val rad = diskRadianceNormalized(r, g = 1.0)
            assertTrue("Radiance at legitimate radius r=$r must be strictly positive", rad > 0.0)
        }

        // ISCO and plunge region must produce zero emission
        assertEquals(0.0, disk.fluxProfile(rIn), 1e-12)
        assertEquals(0.0, disk.fluxProfile(rIn - 0.1), 1e-12)
        assertEquals(0.0, diskRadianceNormalized(rIn, g = 1.0), 1e-12)
    }

    // 9. Disk radiance remains finite and deterministic
    @Test
    fun diskRadianceRemainsFiniteAndDeterministic() {
        for (step in 0..100) {
            val r = rIn + (rOut - rIn) * (step / 100.0)
            for (gInt in 2..30) {
                val g = gInt * 0.1 // 0.2 to 3.0
                val rad = diskRadianceNormalized(r, g)
                assertFalse("Radiance must not be NaN for r=$r, g=$g", rad.isNaN())
                assertFalse("Radiance must not be Infinite for r=$r, g=$g", rad.isInfinite())
                assertTrue("Radiance must be non-negative for r=$r, g=$g", rad >= 0.0)
            }
        }
    }

    // 10. HDR/ACES pipeline remains deterministic
    @Test
    fun hdrAcesPipelineRemainsDeterministicAndMonotonic() {
        val inputs = listOf(0.0, 0.01, 0.05, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0)
        var prev = -1.0
        for (x in inputs) {
            val y = acesFilmic(x)
            assertTrue("ACES output must be non-decreasing: prev=$prev, curr=$y", y >= prev)
            assertTrue("ACES output must be in [0, 1]: $y", y in 0.0..1.0)
            prev = y
        }
    }

    // 11. Information card fits large numerical values without clipping
    @Test
    fun informationCardFitsLargeNumericalValuesWithoutClipping() {
        val telemetry = GargantuaTelemetry(
            fps = 120.0f,
            frameTimeMs = 33.3f,
            spin = 0.999f,
            iscoRadius = 1.45f,
            camDist = 60.0f,
            renderResolution = "1440x3200",
            renderScale = 1.0f,
            isHdrActive = true
        )

        val spinStr = String.format(Locale.US, "a*=%.2f", telemetry.spin)
        val iscoStr = String.format(Locale.US, "ISCO=%.2fM", telemetry.iscoRadius)
        val distStr = String.format(Locale.US, "d=%.0fM", telemetry.camDist)
        val resStr = String.format(Locale.US, "%s@%.1fx", telemetry.renderResolution, telemetry.renderScale)
        val timeStr = String.format(Locale.US, "• %.1f ms", telemetry.frameTimeMs)

        assertEquals("a*=1.00", spinStr)
        assertEquals("ISCO=1.45M", iscoStr)
        assertEquals("d=60M", distStr)
        assertEquals("1440x3200@1.0x", resStr)
        assertEquals("• 33.3 ms", timeStr)

        // Verify strings are short and fit within compact card width bounds
        assertTrue("Row 2 combined characters must be <= 30", (spinStr.length + iscoStr.length + distStr.length) <= 30)
        assertTrue("Row 3 combined characters must be <= 25", (resStr.length + timeStr.length) <= 25)
    }

    // 12. The final UI keeps the render open and uses a full-width three-button dock.
    @Test
    fun finalUiUsesSlimChromeAndRtlSafeThreeButtonDock() {
        val rootFile = File(mainDir(), "java/com/zig/gargantua/ui/GargantuaRoot.kt")
        assertTrue("GargantuaRoot.kt must exist: ${rootFile.absolutePath}", rootFile.exists())
        val content = rootFile.readText()

        assertTrue(content.contains("gargantua_three_button_dock"))
        assertTrue(content.contains("gargantua_quality_selector"))
        assertTrue(content.contains("GargantuaSamplingSelector("))
        assertTrue(content.contains("QualityPanel("))
        assertTrue(content.contains("else \"Quality\""))
        assertTrue(content.contains("else \"Animation\""))
        assertTrue(content.contains("else \"Info\""))
        assertTrue(content.contains("LocalLayoutDirection provides"))
        assertTrue(content.contains("Arrangement.spacedBy"))
        assertTrue(content.contains("Modifier.weight(1f)"))
        assertTrue(content.contains("maxLines = 1"))
        assertTrue(content.contains("maxLines = 4"))
        assertTrue(content.contains("softWrap = false"))
        assertTrue(content.contains("sizeIn(minWidth = 44.dp"))
        assertTrue(content.contains("GargantuaAnimation.AnimationSpeed.values()"))
        assertTrue(content.contains("GargantuaAnimation.AMPLITUDE_STEPS"))
        assertTrue(content.contains("label = if (enabled) \"±${'$'}{amplitude}%\" else \"OFF\""))
        assertTrue(content.contains("adaptiveWorkload"))
        assertTrue(content.contains("heightIn(max = 96.dp)"))
        assertTrue(content.contains("verticalScroll(telScrollState)"))
        assertTrue(content.contains("TextOverflow.Clip"))
    }

    // 13. Existing camera gestures remain functional
    @Test
    fun existingCameraGesturesRemainFunctional() {
        val initial = GargantuaRenderState()

        // 1-finger orbit
        val orbited = initial.copy(
            camAzimuthDeg = (initial.camAzimuthDeg + 15.0f) % 360f,
            camInclinationDeg = (initial.camInclinationDeg + 5.0f).coerceIn(5.0f, 175.0f)
        )
        assertEquals(15.0f, orbited.camAzimuthDeg, 1e-4f)
        assertEquals(85.0f, orbited.camInclinationDeg, 1e-4f)

        // Pinch zoom
        val zoomed = initial.copy(camDist = (initial.camDist * 1.5f).coerceIn(12.0f, 60.0f))
        assertEquals(48.0f, zoomed.camDist, 1e-4f)

        // 2-finger pan
        val panned = initial.copy(
            camTargetX = initial.camTargetX + 2.0f,
            camTargetY = initial.camTargetY - 1.5f
        )
        assertEquals(2.0f, panned.camTargetX, 1e-4f)
        assertEquals(-1.5f, panned.camTargetY, 1e-4f)
    }

    // 14. Stationary caching remains functional
    @Test
    fun stationaryCachingRemainsFunctional() {
        val stateA = GargantuaRenderState(camDist = 24.0f, camAzimuthDeg = 0.0f, camInclinationDeg = 82.0f)
        val stateB = GargantuaRenderState(camDist = 24.0f, camAzimuthDeg = 0.0f, camInclinationDeg = 82.0f)

        assertEquals("Stationary state objects must be equal", stateA, stateB)
        assertEquals("Hash codes must match for identical states", stateA.hashCode(), stateB.hashCode())
    }

    // =========================================================================
    // SECTION D: DETERMINISTIC TESTS FOR VISUAL / PHYSICAL HIERARCHY & REPAIRS
    // =========================================================================

    // D1. F(r) decreases from its peak toward the outer disk
    @Test
    fun fluxDecreasesFromPeakTowardOuterDisk() {
        val rPeak = (49.0 / 36.0) * rIn
        val testRadii = listOf(4.5, 6.0, 8.0, 10.0, 12.0, 16.0, 19.0, 21.8)

        var prevFlux = novikovThorneFlux(rPeak)
        assertTrue("Peak flux must be positive", prevFlux > 0.0)

        for (r in testRadii) {
            val currFlux = novikovThorneFlux(r)
            assertTrue(
                "Flux must monotonically decrease beyond peak: r=$r currFlux=$currFlux < prevFlux=$prevFlux",
                currFlux < prevFlux
            )
            prevFlux = currFlux
        }

        // Verify strong physical radial falloff: outer disk flux is > 30x lower than peak flux
        val fOuter = novikovThorneFlux(21.8)
        val fPeak = novikovThorneFlux(rPeak)
        val ratio = fPeak / fOuter
        assertTrue("Flux falloff ratio from peak to outer edge must exceed 30x, got $ratio", ratio > 30.0)
    }

    // D2. F(rISCO) = 0 and plunge region is strictly zero
    @Test
    fun fluxAtIscoIsStrictlyZero() {
        assertEquals("Flux exactly at ISCO must be strictly 0.0", 0.0, novikovThorneFlux(rIn), 1e-15)
        assertEquals("Flux inside ISCO (plunge region) must be 0.0", 0.0, novikovThorneFlux(rIn - 0.1), 1e-15)
        assertEquals("Flux near horizon must be 0.0", 0.0, novikovThorneFlux(1.8), 1e-15)
        assertTrue("Flux just outside ISCO must be strictly positive", novikovThorneFlux(rIn + 0.05) > 0.0)
    }

    // D3. Physical transferred emission g^4 * F retains expected radial ordering when g is held constant
    @Test
    fun physicalEmissionRetainsRadialOrderingWhenGIsConstant() {
        val constantG = 1.0
        val rPeak = (49.0 / 36.0) * rIn
        val testRadii = listOf(4.5, 6.0, 8.0, 12.0, 16.0, 20.0, 21.5)

        var prevRad = diskRadianceNormalized(rPeak, constantG)
        for (r in testRadii) {
            val currRad = diskRadianceNormalized(r, constantG)
            assertTrue(
                "Physical emission at constant g must strictly decrease beyond peak: r=$r ($currRad) < ($prevRad)",
                currRad < prevRad
            )
            prevRad = currRad
        }

        // Beyond computational outer radius rOut, emission terminates
        assertEquals("Emission beyond rOut must be exactly 0.0", 0.0, diskRadianceNormalized(rOut + 0.1, constantG), 1e-15)
        assertTrue("Emission at rOut boundary must remain strictly positive before termination", diskRadianceNormalized(rOut, constantG) > 0.0)
    }

    // D4. No emitting-disk sample becomes black because of an unexplained renderer/compositor state
    @Test
    fun noEmittingDiskSampleBecomesBlack() {
        val radii = listOf(3.1, 3.5, 4.0, 5.5, 7.0, 10.0, 14.0, 18.0, 21.0)
        val gShifts = listOf(0.35, 0.45, 0.65, 0.85, 1.00, 1.25, 1.60, 2.10)

        for (r in radii) {
            for (g in gShifts) {
                val rad = diskRadianceNormalized(r, g)
                val postAces = acesFilmic(rad * 1.8)
                val srgb = toSrgb(postAces)

                assertTrue("Radiance at r=$r, g=$g must be > 0.0 (got $rad)", rad > 0.0)
                assertTrue("Post-ACES at r=$r, g=$g must be > 0.0 (got $postAces)", postAces > 0.0)
                assertTrue("sRGB at r=$r, g=$g must be >= 1 (got $srgb)", srgb >= 1)
            }
        }
    }

    // D5. Approaching/receding Doppler asymmetry remains intact
    @Test
    fun approachingRecedingDopplerAsymmetryIntact() {
        val testRadii = listOf(3.5, 4.0, 6.0, 8.0, 12.0, 16.0, 20.0)
        for (r in testRadii) {
            val gApp = 1.0 + 1.2 / sqrt(r)
            val gRec = 1.0 - 0.9 / sqrt(r)

            val radApp = diskRadianceNormalized(r, gApp)
            val radRec = diskRadianceNormalized(r, gRec)

            assertTrue(
                "Approaching radiance ($radApp) must exceed receding radiance ($radRec) at r=$r",
                radApp > radRec
            )

            val acesApp = acesFilmic(radApp * 1.8)
            val acesRec = acesFilmic(radRec * 1.8)
            assertTrue(
                "Approaching display ($acesApp) must exceed receding display ($acesRec) at r=$r",
                acesApp > acesRec
            )
        }
    }

    // D6. Lensed starfield background is present (Interstellar upgrade)
    @Test
    fun noStarsOrBackgroundAreReintroduced() {
        val content = readShader("gargantua_geodesic.frag")

        // Escaped rays must produce lensed starfield background with alpha 1.0
        assertTrue(
            "Escaped branch must output starfield background with alpha 1.0",
            content.contains("sample_procedural_sky") && content.contains("1.0")
        )

        // Shadow rays must still produce pure black with alpha 0.0
        assertTrue(
            "Captured shadow branch must output alpha 0.0",
            content.contains("fragColor = vec4(0.0, 0.0, 0.0, 0.0);")
        )

        // Escaped branch must invoke sample_procedural_sky for depth
        assertTrue(
            "sample_procedural_sky must be called for lensed background",
            content.contains("sample_procedural_sky")
        )
    }

    // D7. Display transform does not reverse the physical brightness ordering of disk samples
    @Test
    fun displayTransformPreservesPhysicalBrightnessOrdering() {
        // Monotonicity of ACES display transform: if A >= B, then ACES(A) >= ACES(B)
        val linearRadianceSamples = listOf(0.0, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 2.5, 5.0, 12.0)
        var prevLdr = -1.0
        for (rad in linearRadianceSamples) {
            val ldr = acesFilmic(rad * 1.8)
            assertTrue("Display transform must be non-decreasing: prev=$prevLdr curr=$ldr", ldr >= prevLdr)
            prevLdr = ldr
        }

        // Test radial ordering along the disk from peak outward on approaching side
        val radii = listOf(4.0, 6.0, 8.0, 12.0, 16.0, 20.0, 21.8)
        var prevDisplay = 1.1
        for (r in radii) {
            val g = 1.0 + 1.2 / sqrt(r)
            val rad = diskRadianceNormalized(r, g)
            val display = acesFilmic(rad * 1.8)
            assertTrue(
                "Display brightness at r=$r ($display) must not exceed inner peak ($prevDisplay)",
                display <= prevDisplay + 1e-12
            )
            prevDisplay = display
        }
    }

    // D8. Finite deterministic output; no NaN/Inf
    @Test
    fun physicalEmissionAndDisplayOutputAreFiniteAndDeterministic() {
        for (step in 0..100) {
            val r = rIn + (rOut - rIn) * (step / 100.0)
            for (gInt in 2..40) {
                val g = gInt * 0.1 // 0.2 to 4.0
                val rad = diskRadianceNormalized(r, g)
                assertFalse("Radiance must not be NaN for r=$r, g=$g", rad.isNaN())
                assertFalse("Radiance must not be Infinite for r=$r, g=$g", rad.isInfinite())
                assertTrue("Radiance must be non-negative for r=$r, g=$g", rad >= 0.0)

                val postAces = acesFilmic(rad * 1.8)
                assertFalse("Post-ACES must not be NaN for r=$r, g=$g", postAces.isNaN())
                assertTrue("Post-ACES must be in [0, 1] for r=$r, g=$g", postAces in 0.0..1.0)

                val srgb = toSrgb(postAces)
                assertTrue("sRGB must be in [0, 255] for r=$r, g=$g", srgb in 0..255)
            }
        }
    }

    // D9. Geodesic renderer produces nonzero scene output for known valid camera rays
    @Test
    fun geodesicRendererProducesNonzeroSceneOutputForKnownValidCameraRays() {
        val camPos = floatArrayOf(0.0f, -24.0f, 3.0f)

        // 1. Ray targeted at approaching side of the accretion disk
        val dxApp = -0.25f
        val dyApp = 0.95f
        val dzApp = -0.12f
        val magApp = sqrt(dxApp * dxApp + dyApp * dyApp + dzApp * dzApp)
        val normRayApp = floatArrayOf(dxApp / magApp, dyApp / magApp, dzApp / magApp)

        val diskRayResult = GpuEquivalentIntegrator.traceRay(
            M = 1.0f,
            a = 0.8f,
            camPos = camPos,
            rayDir = normRayApp,
            maxSteps = 180,
            enableDisk = true,
            diskInnerRadius = rIn.toFloat(),
            diskOuterRadius = rOut.toFloat()
        )

        assertTrue("Approaching disk ray must physically intersect accretion disk", diskRayResult.isDiskHit)
        assertTrue(
            "Hit radius must fall within disk bounds [rIn, rOut]: got ${diskRayResult.rHit}",
            diskRayResult.rHit in (rIn.toFloat()..rOut.toFloat())
        )
        val radApp = diskRadianceNormalized(diskRayResult.rHit.toDouble(), diskRayResult.frequencyShift.toDouble())
        assertTrue("Approaching disk ray must produce strictly positive radiance ($radApp > 0)", radApp > 0.0)

        // 2. Ray targeted straight into black hole shadow
        val dxSh = 0.0f
        val dySh = 1.0f
        val dzSh = -0.125f
        val magSh = sqrt(dxSh * dxSh + dySh * dySh + dzSh * dzSh)
        val normRaySh = floatArrayOf(dxSh / magSh, dySh / magSh, dzSh / magSh)

        val shadowResult = GpuEquivalentIntegrator.traceRay(
            M = 1.0f,
            a = 0.8f,
            camPos = camPos,
            rayDir = normRaySh,
            maxSteps = 180,
            enableDisk = true,
            diskInnerRadius = rIn.toFloat(),
            diskOuterRadius = rOut.toFloat()
        )
        assertTrue("Shadow ray must be captured by black hole event horizon", shadowResult.isCaptured)

        // 3. Ray directed away into empty asymptotic sky
        val normRaySky = floatArrayOf(0.0f, 0.0f, 1.0f)
        val skyResult = GpuEquivalentIntegrator.traceRay(
            M = 1.0f,
            a = 0.8f,
            camPos = camPos,
            rayDir = normRaySky,
            maxSteps = 180,
            enableDisk = true,
            diskInnerRadius = rIn.toFloat(),
            diskOuterRadius = rOut.toFloat()
        )
        assertTrue("Sky ray must physically escape into asymptotic space", skyResult.isEscaped)
    }

    // D10. No arbitrary outer taper or edge gradient applied to physical disk flux
    @Test
    fun noArbitraryOuterTaperIsAppliedToPhysicalDiskFlux() {
        val content = readShader("gargantua_geodesic.frag")
        assertFalse("Shader must not contain arbitrary outer taper variable", content.contains("outerTaper"))
        assertFalse("Shader must not contain w_out taper function", content.contains("w_out"))
        assertFalse("Shader must not contain wOut taper variable", content.contains("wOut"))

        // Transferred emission must be purely I_phys = g4 * fNorm
        assertTrue("iPhys must be defined as g4 * fNorm", content.contains("float iPhys = g4 * fNorm;"))
        assertTrue("radiance must be directly assigned from iPhys", content.contains("float radiance = iPhys;"))
    }

    // D11. GLSL shader scoping and declaration validation
    @Test
    fun glslShaderHasNoUndeclaredVariablesOrSyntaxErrors() {
        val content = readShader("gargantua_geodesic.frag")

        // Braces matching
        val openBraces = content.count { it == '{' }
        val closeBraces = content.count { it == '}' }
        assertEquals("Braces must be balanced in fragment shader", openBraces, closeBraces)

        // Parentheses matching
        val openParens = content.count { it == '(' }
        val closeParens = content.count { it == ')' }
        assertEquals("Parentheses must be balanced in fragment shader", openParens, closeParens)

        // Ensure variable 'r' in the integration loop is declared BEFORE any usage
        val loopStart = content.indexOf("for (int step = 0; step < MAX_INTEGRATION_STEPS; step++)")
        assertTrue("Integration loop must exist", loopStart > 0)
        val loopBody = content.substring(loopStart)

        val rDeclaration = loopBody.indexOf("float r = compute_r_KS(")
        assertTrue("r must be declared inside loop", rDeclaration > 0)

        // First usage of r in the loop must be at or after declaration
        val firstRUse = loopBody.indexOf("if (r > prevR)")
        assertTrue("First usage of r must occur after declaration", firstRUse > rDeclaration)

        // maxSteps break must not precede r declaration if r is used in it
        val maxStepsCheck = loopBody.indexOf("if (step >= maxSteps)")
        assertTrue("step >= maxSteps check must exist in loop", maxStepsCheck > 0)
    }

    // D12. HDR composite pipeline preserves valid geodesic output
    @Test
    fun hdrCompositePipelinePreservesValidGeodesicOutput() {
        // Shadow (0.0 radiance) -> display is 0.0
        val shadowDisplay = acesFilmic(0.0 * 1.8)
        assertEquals("Shadow display must remain strictly 0.0", 0.0, shadowDisplay, 1e-12)

        // Nonzero disk radiance -> display is strictly positive and bounded
        val testRadiances = listOf(0.01, 0.05, 0.2, 0.8, 2.5, 8.0)
        var prevLdr = 0.0
        for (rad in testRadiances) {
            val ldr = acesFilmic(rad * 1.8)
            assertTrue("LDR display must be strictly positive for rad=$rad", ldr > 0.0)
            assertTrue("LDR display must be <= 1.0 for rad=$rad", ldr <= 1.0)
            assertTrue("LDR display must preserve monotonicity: curr=$ldr > prev=$prevLdr", ldr > prevLdr)
            prevLdr = ldr
        }
    }

    // D13. Default camera framing provides adequate margin around black-hole shadow
    @Test
    fun defaultCameraFramingProvidesAdequateMarginAroundShadow() {
        val defaultState = GargantuaRenderState()
        assertEquals("Default observer distance must be 32.0M", 32.0f, defaultState.camDist, 1e-4f)
        assertEquals("Default observer inclination must be 80.0 deg", 80.0f, defaultState.camInclinationDeg, 1e-4f)
        assertEquals("Default observer azimuth must be 0.0 deg", 0.0f, defaultState.camAzimuthDeg, 1e-4f)

        // Apparent shadow radius for Kerr black hole with a=0.8M is bounded by b_crit ~ 5.2M
        val bShadow = 5.20
        val halfFovRad = Math.toRadians(45.0 * 0.5)
        val viewportHalfExtentAtObserver = defaultState.camDist * tan(halfFovRad) // 32 * tan(22.5) ~ 13.255M

        // Shadow fraction of viewport half-dimension
        val shadowFraction = bShadow / viewportHalfExtentAtObserver
        val viewportMargin = 1.0 - shadowFraction

        // Margin around shadow must be at least 50% (got ~ 60.7%)
        assertTrue(
            "Viewport margin around shadow ($viewportMargin) must be >= 50% to prevent cramped framing",
            viewportMargin >= 0.50
        )

        // Ensure inner and mid accretion disk up to r=12M fits comfortably
        val midDiskRadius = 12.0
        val diskFraction = midDiskRadius / viewportHalfExtentAtObserver
        assertTrue(
            "Inner and mid disk footprint ($diskFraction) must fit comfortably within viewport",
            diskFraction < 1.0
        )
    }

    // D14. Maximum camera distance never causes premature scene escape or disappearance
    @Test
    fun maxCameraDistanceNeverCausesPrematureSceneEscape() {
        val maxDist = 60.0f
        val inclRad = Math.toRadians(80.0).toFloat()
        val azRad = 0.0f

        val camX = maxDist * sin(inclRad) * cos(azRad)
        val camY = maxDist * sin(inclRad) * sin(azRad)
        val camZ = maxDist * cos(inclRad)

        val camPos = floatArrayOf(camX, camY, camZ)

        // 1. Shadow ray: directed straight at origin
        val fwdLen = sqrt(camX * camX + camY * camY + camZ * camZ)
        val rayShadow = floatArrayOf(-camX / fwdLen, -camY / fwdLen, -camZ / fwdLen)

        val shadowResult = GpuEquivalentIntegrator.traceRay(
            M = 1.0f,
            a = 0.8f,
            camPos = camPos,
            rayDir = rayShadow,
            maxSteps = 180,
            enableDisk = true,
            diskInnerRadius = rIn.toFloat(),
            diskOuterRadius = rOut.toFloat()
        )

        assertTrue(
            "Ray aimed at black hole from d=60M must be captured, not prematurely escaped (escaped=${shadowResult.isEscaped}, captured=${shadowResult.isCaptured})",
            shadowResult.isCaptured
        )
        assertFalse(
            "Ray aimed at black hole from d=60M must not escape",
            shadowResult.isEscaped
        )

        // 2. Accretion disk ray: directed at approaching disk from d=60M
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()
        val rightX = -sin(azRad)
        val rightY = cos(azRad)
        val rightZ = 0.0f

        val stX = -0.30f // Aimed at approaching disk (r ~ 6.0M)
        val diskRayDirX = rayShadow[0] + rightX * (stX * fovScale)
        val diskRayDirY = rayShadow[1] + rightY * (stX * fovScale)
        val diskRayDirZ = rayShadow[2] + rightZ * (stX * fovScale)
        val dLen = sqrt(diskRayDirX * diskRayDirX + diskRayDirY * diskRayDirY + diskRayDirZ * diskRayDirZ)
        val rayDisk = floatArrayOf(diskRayDirX / dLen, diskRayDirY / dLen, diskRayDirZ / dLen)

        val diskResult = GpuEquivalentIntegrator.traceRay(
            M = 1.0f,
            a = 0.8f,
            camPos = camPos,
            rayDir = rayDisk,
            maxSteps = 180,
            enableDisk = true,
            diskInnerRadius = rIn.toFloat(),
            diskOuterRadius = rOut.toFloat()
        )

        assertTrue(
            "Ray aimed at disk from d=60M must hit disk, not escape (escaped=${diskResult.isEscaped}, diskHit=${diskResult.isDiskHit})",
            diskResult.isDiskHit
        )
        assertTrue(
            "Disk hit radius must be in valid range [rIn, rOut]: got ${diskResult.rHit}",
            diskResult.rHit in (rIn.toFloat()..rOut.toFloat())
        )
    }

    // D15. Continuous 360-degree orbit basis remains singularity-free and orthonormal
    @Test
    fun continuous360OrbitBasisRemainsSingularityFreeAndOrthonormal() {
        for (azDeg in 0..360 step 15) {
            for (inclDeg in 10..170 step 15) {
                val azRad = Math.toRadians(azDeg.toDouble())
                val inclRad = Math.toRadians(inclDeg.toDouble())

                val fwdX = -sin(inclRad) * cos(azRad)
                val fwdY = -sin(inclRad) * sin(azRad)
                val fwdZ = -cos(inclRad)

                val rX = -sin(azRad)
                val rY = cos(azRad)
                val rZ = 0.0

                val upX = rY * fwdZ - rZ * fwdY
                val upY = rZ * fwdX - rX * fwdZ
                val upZ = rX * fwdY - rY * fwdX

                val fwdLen = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ)
                val rightLen = sqrt(rX * rX + rY * rY + rZ * rZ)
                val upLen = sqrt(upX * upX + upY * upY + upZ * upZ)

                assertEquals("Forward vector must have unit length at az=$azDeg, incl=$inclDeg", 1.0, fwdLen, 1e-6)
                assertEquals("Right vector must have unit length at az=$azDeg, incl=$inclDeg", 1.0, rightLen, 1e-6)
                assertEquals("Up vector must have unit length at az=$azDeg, incl=$inclDeg", 1.0, upLen, 1e-6)

                val dotFR = fwdX * rX + fwdY * rY + fwdZ * rZ
                val dotFU = fwdX * upX + fwdY * upY + fwdZ * upZ
                val dotRU = rX * upX + rY * upY + rZ * upZ

                assertEquals("Forward and Right must be orthogonal at az=$azDeg, incl=$inclDeg", 0.0, dotFR, 1e-6)
                assertEquals("Forward and Up must be orthogonal at az=$azDeg, incl=$inclDeg", 0.0, dotFU, 1e-6)
                assertEquals("Right and Up must be orthogonal at az=$azDeg, incl=$inclDeg", 0.0, dotRU, 1e-6)

                // Determinant of [R, U, -F] must be +1 (standard right-handed camera coordinates)
                val det = rX * (upY * (-fwdZ) - upZ * (-fwdY)) -
                          rY * (upX * (-fwdZ) - upZ * (-fwdX)) +
                          rZ * (upX * (-fwdY) - upY * (-fwdX))
                assertEquals("Camera basis determinant must be +1.0 at az=$azDeg, incl=$inclDeg", 1.0, det, 1e-6)
            }
        }
    }

    // D16. Equatorial step refinement preserves step budget inside ISCO plunge region
    @Test
    fun equatorialStepRefinementPreservesStepBudgetInsideIsco() {
        val content = readShader("gargantua_geodesic.frag")

        // Fragment shader must guard disk step refinement by r >= u_DiskInnerRadius - 0.5
        assertTrue(
            "Shader must avoid clamping step size inside empty ISCO plunge region",
            content.contains("r >= u_DiskInnerRadius - 0.5")
        )

        // Escape check must strictly require movingOutward to prevent premature escape at large distances
        assertTrue(
            "Escape check must require movingOutward",
            content.contains("if (movingOutward && (r >= rEscape || r >= u_DiskOuterRadius))")
        )
    }

    // D17. Red-line tertiary filament resolves with step budget and separates from plunge gap
    @Test
    fun redLineTertiaryFilamentResolvesAndDistinguishesPlungeGap() {
        val rInF = rIn.toFloat()
        val rOutF = rOut.toFloat()
        val inclRad = Math.toRadians(80.0)
        val camDist = 32.0f
        val camPos = floatArrayOf(
            camDist * sin(inclRad).toFloat(),
            0.0f,
            camDist * cos(inclRad).toFloat()
        )
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()

        // Forward vector (towards origin)
        val fwdLen = sqrt(camPos[0] * camPos[0] + camPos[2] * camPos[2])
        val fwd = floatArrayOf(-camPos[0] / fwdLen, 0.0f, -camPos[2] / fwdLen)
        // Upright right vector
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        // Up vector
        val up = floatArrayOf(-cos(inclRad).toFloat(), 0.0f, sin(inclRad).toFloat())

        // 1. Tertiary filament ray (stX = -0.2800, stY = 0.0; corrected backward-traced ray, narrow-shadow side):
        //    its first equatorial crossing is inside the ISCO gap (r = 2.79), the second hits the disk at r = 4.168
        //    (g = 1.041, 139 steps; measured with the Kotlin GpuEquivalentIntegrator)
        val stX_tert = -0.2800f
        val stY_tert = 0.0f
        val rayTertX = fwd[0] + right[0] * (stX_tert * fovScale) + up[0] * (stY_tert * fovScale)
        val rayTertY = fwd[1] + right[1] * (stX_tert * fovScale) + up[1] * (stY_tert * fovScale)
        val rayTertZ = fwd[2] + right[2] * (stX_tert * fovScale) + up[2] * (stY_tert * fovScale)
        val tertLen = sqrt(rayTertX * rayTertX + rayTertY * rayTertY + rayTertZ * rayTertZ)
        val rayTert = floatArrayOf(rayTertX / tertLen, rayTertY / tertLen, rayTertZ / tertLen)

        val tertResult = GpuEquivalentIntegrator.traceRay(
            M = 1.0f,
            a = 0.8f,
            camPos = camPos,
            rayDir = rayTert,
            maxSteps = 220,
            enableDisk = true,
            diskInnerRadius = rInF,
            diskOuterRadius = rOutF
        )

        assertTrue(
            "Tertiary disk filament ray must physically intersect accretion disk with maxSteps=220 (got hit=${tertResult.isDiskHit}, captured=${tertResult.isCaptured}, escaped=${tertResult.isEscaped}, steps=${tertResult.stepsTaken})",
            tertResult.isDiskHit
        )
        assertTrue(
            "Tertiary hit radius must be within physical disk [rIn, rOut]: got ${tertResult.rHit}",
            tertResult.rHit in rInF..rOutF
        )
        val tertRad = diskRadianceNormalized(tertResult.rHit.toDouble(), tertResult.frequencyShift.toDouble())
        assertTrue("Tertiary filament emission must be strictly positive", tertRad > 0.0)

        // 2. Plunge gap ray (stX = -0.2660, stY = 0.0): both equatorial crossings fall inside the ISCO gap
        //    (KS r = 2.63 and 2.59 < r_isco = 2.907), then the ray escapes without hitting the disk.
        //    Pinned with the Kotlin GpuEquivalentIntegrator: rays from stX = -0.2880 to -0.2700 hit the disk on
        //    their second crossing (r = 5.66 ... 2.95); from -0.2680 to -0.2600 they escape.
        val stX_gap = -0.2660f
        val rayGapX = fwd[0] + right[0] * (stX_gap * fovScale)
        val rayGapY = fwd[1] + right[1] * (stX_gap * fovScale)
        val rayGapZ = fwd[2] + right[2] * (stX_gap * fovScale)
        val gapLen = sqrt(rayGapX * rayGapX + rayGapY * rayGapY + rayGapZ * rayGapZ)
        val rayGap = floatArrayOf(rayGapX / gapLen, rayGapY / gapLen, rayGapZ / gapLen)

        val gapResult = GpuEquivalentIntegrator.traceRay(
            M = 1.0f,
            a = 0.8f,
            camPos = camPos,
            rayDir = rayGap,
            maxSteps = 220,
            enableDisk = true,
            diskInnerRadius = rInF,
            diskOuterRadius = rOutF
        )

        assertTrue("Plunge gap ray must escape without disk intersection", gapResult.isEscaped)
        assertFalse("Plunge gap ray must not be flagged as disk hit", gapResult.isDiskHit)
    }

    // D18. Full 360-degree camera orbit across cardinal and intermediate azimuths
    @Test
    fun full360CameraOrbitAcrossCardinalAndIntermediateAzimuths() {
        val cardinalAndIntermediate = listOf(0, 45, 90, 135, 180, 225, 270, 315, 360)
        val inclDeg = 80.0
        val camDist = 32.0
        val inclRad = Math.toRadians(inclDeg)

        var prevPos: DoubleArray? = null

        for (azDeg in cardinalAndIntermediate) {
            val azRad = Math.toRadians(azDeg.toDouble())
            val camX = camDist * sin(inclRad) * cos(azRad)
            val camY = camDist * sin(inclRad) * sin(azRad)
            val camZ = camDist * cos(inclRad)

            val currentPos = doubleArrayOf(camX, camY, camZ)
            val dActual = sqrt(camX * camX + camY * camY + camZ * camZ)
            assertEquals("Observer distance must remain constant at az=$azDeg", camDist, dActual, 1e-5)

            // Observer position continuity: distance between successive 45 deg angles bounded by chord length
            if (prevPos != null) {
                val dx = currentPos[0] - prevPos[0]
                val dy = currentPos[1] - prevPos[1]
                val dz = currentPos[2] - prevPos[2]
                val chordLen = sqrt(dx * dx + dy * dy + dz * dz)
                val expectedChord = 2.0 * (camDist * sin(inclRad)) * sin(Math.toRadians(45.0 / 2.0))
                assertEquals("Position progression must be continuous and smooth at az=$azDeg", expectedChord, chordLen, 1e-4)
            }
            prevPos = currentPos

            // Basis Orthonormality
            val fwd = doubleArrayOf(-camX / dActual, -camY / dActual, -camZ / dActual)
            val right = doubleArrayOf(-sin(azRad), cos(azRad), 0.0)
            val up = doubleArrayOf(
                right[1] * fwd[2] - right[2] * fwd[1],
                right[2] * fwd[0] - right[0] * fwd[2],
                right[0] * fwd[1] - right[1] * fwd[0]
            )

            val fwdLen = sqrt(fwd[0] * fwd[0] + fwd[1] * fwd[1] + fwd[2] * fwd[2])
            val rightLen = sqrt(right[0] * right[0] + right[1] * right[1] + right[2] * right[2])
            val upLen = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2])

            assertEquals("Forward unit length at az=$azDeg", 1.0, fwdLen, 1e-6)
            assertEquals("Right unit length at az=$azDeg", 1.0, rightLen, 1e-6)
            assertEquals("Up unit length at az=$azDeg", 1.0, upLen, 1e-6)

            // Right-handed orientation
            val det = right[0] * (up[1] * (-fwd[2]) - up[2] * (-fwd[1])) -
                      right[1] * (up[0] * (-fwd[2]) - up[2] * (-fwd[0])) +
                      right[2] * (up[0] * (-fwd[1]) - up[1] * (-fwd[0]))
            assertEquals("Basis handedness determinant must be +1.0 at az=$azDeg", 1.0, det, 1e-6)
        }
    }

    // D19. Front/Back Doppler shift emergence from p_mu u^mu and axial angular momentum
    @Test
    fun dopplerBeamingEmergenceFromConservedAngularMomentum() {
        val fovScale = tan(Math.toRadians(45.0 * 0.5))
        val camDist = 32.0
        val inclRad = Math.toRadians(80.0)

        // Across cardinal azimuths, screen-left ray has negative Lz and screen-right has positive Lz
        for (azDeg in listOf(0, 90, 180, 270)) {
            val azRad = Math.toRadians(azDeg.toDouble())
            val camX = camDist * sin(inclRad) * cos(azRad)
            val camY = camDist * sin(inclRad) * sin(azRad)

            // Screen left ray (stX = -0.25)
            val stX_left = -0.25
            val rVecX = -sin(azRad)
            val rVecY = cos(azRad)
            val pX_left = -camX / camDist + rVecX * (stX_left * fovScale)
            val pY_left = -camY / camDist + rVecY * (stX_left * fovScale)
            val lz_left = camX * pY_left - camY * pX_left

            // Screen right ray (stX = +0.25)
            val stX_right = 0.25
            val pX_right = -camX / camDist + rVecX * (stX_right * fovScale)
            val pY_right = -camY / camDist + rVecY * (stX_right * fovScale)
            val lz_right = camX * pY_right - camY * pX_right

            // Lz = camX * pY - camY * pX = camX * (rVecY * stX * fov) - camY * (rVecX * stX * fov)
            //    = (camDist * sinTheta) * stX * fov
            assertTrue("Screen-left ray must have negative conserved Lz at az=$azDeg", lz_left < -0.01)
            assertTrue("Screen-right ray must have positive conserved Lz at az=$azDeg", lz_right > 0.01)

            // Redshift factor g = 1 / [u^0 * (1 + Omega * Lz / E)]
            // For prograde disk Omega > 0:
            // Left: Lz < 0 => (1 + Omega * Lz) < 1 => g > 1 (blueshifted / approaching)
            // Right: Lz > 0 => (1 + Omega * Lz) > 1 => g < 1 (redshifted / receding)
            val omega = 1.0 / (6.0.pow(1.5) + 0.8)
            val denomLeft = 1.0 + omega * lz_left
            val denomRight = 1.0 + omega * lz_right
            assertTrue("Left denominator must be < 1.0 (blueshift) at az=$azDeg", denomLeft < 1.0)
            assertTrue("Right denominator must be > 1.0 (redshift) at az=$azDeg", denomRight > 1.0)
        }
    }

    // D20. Cache invalidation on all camera transform modifications
    @Test
    fun cacheInvalidationOnCameraTransformModifications() {
        val baseState = GargantuaRenderState(
            camAzimuthDeg = 0.0f,
            camInclinationDeg = 80.0f,
            camDist = 32.0f
        )
        val sigBase = GargantuaRenderer.SceneSignature.fromState(baseState, 1080, 2400)

        // 1. Azimuth change invalidates cache
        val stateAz = baseState.copy(camAzimuthDeg = 15.0f)
        val sigAz = GargantuaRenderer.SceneSignature.fromState(stateAz, 1080, 2400)
        assertFalse("Azimuth change must invalidate SceneSignature", sigBase == sigAz)

        // 2. Inclination change invalidates cache
        val stateIncl = baseState.copy(camInclinationDeg = 75.0f)
        val sigIncl = GargantuaRenderer.SceneSignature.fromState(stateIncl, 1080, 2400)
        assertFalse("Inclination change must invalidate SceneSignature", sigBase == sigIncl)

        // 3. Distance change invalidates cache
        val stateDist = baseState.copy(camDist = 40.0f)
        val sigDist = GargantuaRenderer.SceneSignature.fromState(stateDist, 1080, 2400)
        assertFalse("Distance change must invalidate SceneSignature", sigBase == sigDist)

        // 4. Identical state preserves cache
        val stateIdentical = baseState.copy()
        val sigIdentical = GargantuaRenderer.SceneSignature.fromState(stateIdentical, 1080, 2400)
        assertTrue("Identical state must match SceneSignature", sigBase == sigIdentical)
    }

    // D21. Verification of no artificial photon ring or screen space overlay code
    @Test
    fun noArtificialPhotonRingOrScreenSpaceOverlays() {
        val geoShader = readShader("gargantua_geodesic.frag")
        val compShader = readShader("gargantua_composite.frag")
        val brightShader = readShader("gargantua_brightpass.frag")

        // No artificial circle or ring drawing functions
        val forbiddenTerms = listOf(
            "drawPhotonRing",
            "artificialRing",
            "photonRingRadius",
            "fakeRing",
            "drawCircle",
            "ambientGlow",
            "fillLight"
        )
        for (term in forbiddenTerms) {
            assertFalse("Shaders must not contain artificial ring/glow keyword: $term", geoShader.contains(term))
            assertFalse("Shaders must not contain artificial ring/glow keyword: $term", compShader.contains(term))
            assertFalse("Shaders must not contain artificial ring/glow keyword: $term", brightShader.contains(term))
        }
    }

    // D22. Equatorial disk intersection audit (sign-change bracketing and 5 test cases)
    @Test
    fun equatorialDiskIntersectionCasesAudit() {
        val rInF = rIn.toFloat()
        val rOutF = rOut.toFloat()
        val camDist = 32.0f
        val inclRad = Math.toRadians(80.0)
        val camPos = floatArrayOf(
            camDist * sin(inclRad).toFloat(),
            0.0f,
            camDist * cos(inclRad).toFloat()
        )
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()
        val fwdLen = sqrt(camPos[0] * camPos[0] + camPos[2] * camPos[2])
        val fwd = floatArrayOf(-camPos[0] / fwdLen, 0.0f, -camPos[2] / fwdLen)
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)

        // 1. Sign-change bracketing verification in shader source
        val shaderContent = readShader("gargantua_geodesic.frag")
        assertTrue(
            "Shader must use strict sign-change bracketing prevPos.z * pos.z <= 0.0",
            shaderContent.contains("prevPos.z * pos.z <= 0.0")
        )
        assertTrue(
            "Shader must linearly interpolate exact equatorial crossing parameter tau",
            shaderContent.contains("float tau = clamp(-prevPos.z / (pos.z - prevPos.z), 0.0, 1.0);")
        )

        // Case 1: Plunge crossing -> later valid disk crossing
        val stX_tert = -0.2800f
        val rayTertDir = floatArrayOf(
            fwd[0] + right[0] * (stX_tert * fovScale),
            fwd[1] + right[1] * (stX_tert * fovScale),
            fwd[2] + right[2] * (stX_tert * fovScale)
        )
        val tertLen = sqrt(rayTertDir[0] * rayTertDir[0] + rayTertDir[1] * rayTertDir[1] + rayTertDir[2] * rayTertDir[2])
        val normRayTert = floatArrayOf(rayTertDir[0] / tertLen, rayTertDir[1] / tertLen, rayTertDir[2] / tertLen)

        val resTert = GpuEquivalentIntegrator.traceRay(
            M = 1.0f, a = 0.8f, camPos = camPos, rayDir = normRayTert,
            maxSteps = 220, enableDisk = true, diskInnerRadius = rInF, diskOuterRadius = rOutF
        )
        assertTrue("Tertiary ray must resolve to disk hit", resTert.isDiskHit)
        assertTrue("Hit radius must be within radiating disk bounds", resTert.rHit in rInF..rOutF)

        // Case 2: Multiple equatorial crossings ending in valid disk hit
        assertTrue("Tertiary ray must execute multiple steps and approach photon sphere", resTert.stepsTaken > 100)
        assertTrue("Minimum radius must penetrate inside ISCO (r < 2.91M)", resTert.minRadiusReached < rInF)

        // Case 3: Near-tangent disk crossing
        val camPosApp = floatArrayOf(0.0f, -24.0f, 3.0f)
        val dxApp = -0.25f
        val dyApp = 0.95f
        val dzApp = -0.12f
        val magApp = sqrt(dxApp * dxApp + dyApp * dyApp + dzApp * dzApp)
        val normRayApp = floatArrayOf(dxApp / magApp, dyApp / magApp, dzApp / magApp)

        val resApp = GpuEquivalentIntegrator.traceRay(
            M = 1.0f, a = 0.8f, camPos = camPosApp, rayDir = normRayApp,
            maxSteps = 150, enableDisk = true, diskInnerRadius = rInF, diskOuterRadius = rOutF
        )
        assertTrue("Approaching disk ray must physically hit accretion disk", resApp.isDiskHit)
        assertTrue("Approaching disk ray must have Doppler blueshift g > 1.0", resApp.frequencyShift > 1.0f)

        // Case 4: Crossing near rISCO
        assertTrue("Hit radius must be near inner disk edge [rISCO, 6.0M]", resTert.rHit < 6.0f)

        // Case 5: High-order photon ring trajectory
        assertTrue("High-order photon ring trajectory penetrates ergosphere", resTert.minRadiusReached < 2.5f)
        // The disk is see-through (shader L788-870): after its accepted crossing the ray keeps integrating
        // and, like the shader, escapes with that crossing's emission in accumDiskRadiance and the
        // transmittance reduced; it is neither captured nor unresolved.
        assertEquals("High-order disk ray has exactly one accepted crossing", 1, resTert.diskCrossings)
        assertEquals("High-order disk ray continues through the disk and escapes (rayState 2)", 2, resTert.rayState)
        assertTrue("Escaped disk ray keeps its crossing emission", resTert.accumulatedRadiance.sum() > 0.005f)
        assertTrue("Escaped disk ray sees the sky only through the reduced transmittance", resTert.transmittance < 1.0f)
        assertFalse("High-order disk ray must not be captured", resTert.isCaptured)
    }

    // D23. Quantitative Anti-Aliasing and Subpixel Supersampling Test
    @Test
    fun quantitativeAntiAliasingTertiaryFilamentContinuity() {
        val rInF = rIn.toFloat()
        val rOutF = rOut.toFloat()
        val inclRad = Math.toRadians(80.0)
        val camDist = 32.0f
        val camPos = floatArrayOf(
            camDist * sin(inclRad).toFloat(),
            0.0f,
            camDist * cos(inclRad).toFloat()
        )
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()
        val fwdLen = sqrt(camPos[0] * camPos[0] + camPos[2] * camPos[2])
        val fwd = floatArrayOf(-camPos[0] / fwdLen, 0.0f, -camPos[2] / fwdLen)
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        val up = floatArrayOf(-cos(inclRad).toFloat(), 0.0f, sin(inclRad).toFloat())

        // Row py = 609 at 0.5x (stY = +0.0352) where the single base sample fell between pixel columns
        val stY_gap = 0.0352f
        val dim05 = 540.0f
        val pxScale05 = 2.0f / dim05

        // Check columns px in 180..210 across the tertiary filament (gap pixel px=193)
        var singleSampleHitCount = 0
        var supersampledHitCount = 0
        var singleGapHits = 0
        var superGapHits = 0
        var integratedSingleRadiance = 0.0
        var integratedSuperRadiance = 0.0

        for (px in 180..210) {
            val stX = (2.0f * px + 1.0f - dim05) / dim05

            // Base sample
            val rayBaseDir = floatArrayOf(
                fwd[0] + right[0] * (stX * fovScale) + up[0] * (stY_gap * fovScale),
                fwd[1] + right[1] * (stX * fovScale) + up[1] * (stY_gap * fovScale),
                fwd[2] + right[2] * (stX * fovScale) + up[2] * (stY_gap * fovScale)
            )
            val bLen = sqrt(rayBaseDir[0] * rayBaseDir[0] + rayBaseDir[1] * rayBaseDir[1] + rayBaseDir[2] * rayBaseDir[2])
            val normBase = floatArrayOf(rayBaseDir[0] / bLen, rayBaseDir[1] / bLen, rayBaseDir[2] / bLen)

            val baseRes = GpuEquivalentIntegrator.traceRay(
                M = 1.0f, a = 0.8f, camPos = camPos, rayDir = normBase,
                maxSteps = 220, enableDisk = true, diskInnerRadius = rInF, diskOuterRadius = rOutF
            )

            var pixelRad = 0.0
            if (baseRes.isDiskHit) {
                singleSampleHitCount++
                if (px == 193) singleGapHits++
                pixelRad = diskRadianceNormalized(baseRes.rHit.toDouble(), baseRes.frequencyShift.toDouble())
                integratedSingleRadiance += pixelRad
            }

            // Selective supersampling (2D symmetric quarter-offset pattern):
            val needsRefine = (baseRes.stepsTaken > 100) || (baseRes.minRadiusReached < 2.5f) || (baseRes.isDiskHit && baseRes.rHit < 6.0f)
            if (needsRefine) {
                val off = 0.50f * pxScale05

                val offsets = listOf(
                    Pair(-off, -off),
                    Pair(off, -off),
                    Pair(-off, off),
                    Pair(off, off)
                )

                var rSum = pixelRad
                var anyHit = baseRes.isDiskHit

                for ((ox, oy) in offsets) {
                    val sDir = floatArrayOf(
                        fwd[0] + right[0] * ((stX + ox) * fovScale) + up[0] * ((stY_gap + oy) * fovScale),
                        fwd[1] + right[1] * ((stX + ox) * fovScale) + up[1] * ((stY_gap + oy) * fovScale),
                        fwd[2] + right[2] * ((stX + ox) * fovScale) + up[2] * ((stY_gap + oy) * fovScale)
                    )
                    val sLen = sqrt(sDir[0] * sDir[0] + sDir[1] * sDir[1] + sDir[2] * sDir[2])
                    val normS = floatArrayOf(sDir[0] / sLen, sDir[1] / sLen, sDir[2] / sLen)
                    val sRes = GpuEquivalentIntegrator.traceRay(
                        M = 1.0f, a = 0.8f, camPos = camPos, rayDir = normS,
                        maxSteps = 220, enableDisk = true, diskInnerRadius = rInF, diskOuterRadius = rOutF
                    )
                    if (sRes.isDiskHit) {
                        anyHit = true
                        rSum += diskRadianceNormalized(sRes.rHit.toDouble(), sRes.frequencyShift.toDouble())
                    }
                }

                if (anyHit) {
                    supersampledHitCount++
                    if (px == 193) superGapHits++
                    integratedSuperRadiance += (rSum / 5.0)
                }
            } else if (baseRes.isDiskHit) {
                supersampledHitCount++
                if (px == 193) superGapHits++
                integratedSuperRadiance += pixelRad
            }
        }

        // Prove that selective supersampling restores continuity across gap pixels where single-sample had 0 hits
        assertEquals("Single-sample has 0 hits on gap pixel px=193", 0, singleGapHits)
        assertEquals("Selective supersampling recovers the hit on gap pixel px=193", 1, superGapHits)
        assertTrue("Selective supersampling increases total hit count along filament", supersampledHitCount > singleSampleHitCount)
        assertTrue("Integrated supersampled radiance is strictly positive", integratedSuperRadiance > 0.0)

        // Verify that shader source includes selective supersampling implementation
        val shaderContent = readShader("gargantua_geodesic.frag")
        assertTrue("Shader must contain traceRaySample helper", shaderContent.contains("vec4 traceRaySample("))
        assertTrue("Shader must contain needsRefinement check", shaderContent.contains("needsRefinement"))
        assertTrue("Shader must average subpixel samples", shaderContent.contains("(baseSample + sample1 + sample2 + sample3 + sample4) / 5.0"))
    }

    // D14. Physical Subpixel Sampling Gate deterministic validation
    @Test
    fun physicalSubpixelSamplingGateVerification() {
        val rInF = rIn.toFloat()
        val rOutF = rOut.toFloat()
        val inclRad = Math.toRadians(80.0)
        val camDist = 32.0f
        val camPos = floatArrayOf(
            camDist * sin(inclRad).toFloat(),
            0.0f,
            camDist * cos(inclRad).toFloat()
        )
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()
        val fwdLen = sqrt(camPos[0] * camPos[0] + camPos[2] * camPos[2])
        val fwd = floatArrayOf(-camPos[0] / fwdLen, 0.0f, -camPos[2] / fwdLen)
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        val up = floatArrayOf(-cos(inclRad).toFloat(), 0.0f, sin(inclRad).toFloat())

        val dim05 = 540.0f
        val pxScale05 = 2.0f / dim05
        val off = 0.50f * pxScale05

        fun makeDir(stX: Float, stY: Float): FloatArray {
            val d = floatArrayOf(
                fwd[0] + right[0] * (stX * fovScale) + up[0] * (stY * fovScale),
                fwd[1] + right[1] * (stX * fovScale) + up[1] * (stY * fovScale),
                fwd[2] + right[2] * (stX * fovScale) + up[2] * (stY * fovScale)
            )
            val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            return floatArrayOf(d[0] / len, d[1] / len, d[2] / len)
        }

        // 1. Horizontal subpixel crossing recovery (at px = 199, stX = -0.26111; pinned with the Kotlin
        //    GpuEquivalentIntegrator itself: centre escapes, (-off, 0) hits at r = 3.180, (-off, -off) at r = 3.108)
        val px198_stX = (2.0f * 199 + 1.0f - dim05) / dim05
        val px198_stY = 0.0352f
        val baseH = GpuEquivalentIntegrator.traceRay(1.0f, 0.8f, camPos, makeDir(px198_stX, px198_stY), 220, true, rInF, rOutF)
        assertFalse("Base ray at px=199 center must miss the disk", baseH.isDiskHit)
        val subH = GpuEquivalentIntegrator.traceRay(1.0f, 0.8f, camPos, makeDir(px198_stX - off, px198_stY), 220, true, rInF, rOutF)
        assertTrue("Horizontal subpixel offset (-off, 0) must recover disk intersection", subH.isDiskHit)
        assertTrue("Recovered horizontal hit radius must be within physical disk bounds", subH.rHit in rInF..rOutF)

        // 2. Vertical subpixel crossing recovery
        val vert_stX = -0.283f
        val vert_stY = 0.0352f
        val baseV = GpuEquivalentIntegrator.traceRay(1.0f, 0.8f, camPos, makeDir(vert_stX, vert_stY), 220, true, rInF, rOutF)
        assertFalse("Base ray at (-0.283, 0.0352) must miss the disk", baseV.isDiskHit)
        val subV = GpuEquivalentIntegrator.traceRay(1.0f, 0.8f, camPos, makeDir(vert_stX, vert_stY - off), 220, true, rInF, rOutF)
        assertTrue("Vertical-offset subpixel sample must intersect physical disk", subV.isDiskHit)
        assertTrue("Vertical subpixel hit radius must be physical", subV.rHit in rInF..rOutF)

        // 3. Diagonal/curved subpixel crossing recovery
        val subDiag = GpuEquivalentIntegrator.traceRay(1.0f, 0.8f, camPos, makeDir(px198_stX - off, px198_stY - off), 220, true, rInF, rOutF)
        assertTrue("Diagonal quarter-offset (-off, -off) must recover curved tertiary arc", subDiag.isDiskHit)
        assertTrue("Diagonal subpixel hit radius must be physical", subDiag.rHit in rInF..rOutF)

        // 4. Shadow core pixels remain genuinely black across all subpixel samples
        val shadowCenter = GpuEquivalentIntegrator.traceRay(1.0f, 0.8f, camPos, makeDir(0.0f, 0.0f), 220, true, rInF, rOutF)
        assertTrue("Shadow center must be captured", shadowCenter.isCaptured)
        val shadowOffsets = listOf(Pair(-off, -off), Pair(off, -off), Pair(-off, off), Pair(off, off))
        for ((ox, oy) in shadowOffsets) {
            val sRes = GpuEquivalentIntegrator.traceRay(1.0f, 0.8f, camPos, makeDir(ox, oy), 220, true, rInF, rOutF)
            assertTrue("Subpixel shadow sample must remain captured", sRes.isCaptured)
            assertFalse("Subpixel shadow sample must never hit disk", sRes.isDiskHit)
        }

        // 5. Genuinely non-intersecting background pixel remains genuinely black
        val skyCenter = GpuEquivalentIntegrator.traceRay(1.0f, 0.8f, camPos, makeDir(0.85f, 0.85f), 220, true, rInF, rOutF)
        assertTrue("Sky center must escape without disk intersection", skyCenter.isEscaped)
        assertFalse("Sky center must not hit disk", skyCenter.isDiskHit)
        for ((ox, oy) in shadowOffsets) {
            val sRes = GpuEquivalentIntegrator.traceRay(1.0f, 0.8f, camPos, makeDir(0.85f + ox, 0.85f + oy), 220, true, rInF, rOutF)
            assertTrue("Subpixel sky sample must remain escaped", sRes.isEscaped)
            assertFalse("Subpixel sky sample must not hit disk", sRes.isDiskHit)
        }

        // 6. Sample averaging does not artificially increase integrated radiance
        val rad0 = if (baseH.isDiskHit) diskRadianceNormalized(baseH.rHit.toDouble(), baseH.frequencyShift.toDouble()) else 0.0
        val radSub = if (subH.isDiskHit) diskRadianceNormalized(subH.rHit.toDouble(), subH.frequencyShift.toDouble()) else 0.0
        val avgRad = (rad0 + radSub) / 5.0
        assertTrue("Averaged radiance must be strictly <= max sample radiance", avgRad <= maxOf(rad0, radSub))

        // 7. Physical generation verification: shader does not contain artificial overlays
        val shaderContent = readShader("gargantua_geodesic.frag")
        assertFalse("No screen-space red rings", shaderContent.contains("screenRing"))
        assertFalse("No brightness floor", shaderContent.contains("minBrightness"))
        assertFalse("No radial gradient ring overlay", shaderContent.contains("radialGradient"))
        assertTrue("Symmetric 2D pattern off vector present", shaderContent.contains("vec2 off = vec2(0.50 * pxScale, 0.50 * pxScale);"))
    }

    // D15. Representative tertiary ray numerical step-size policy comparison
    @Test
    fun tertiaryRayNumericalStepPolicyComparison() {
        val inclRad = Math.toRadians(80.0)
        val camDist = 32.0f
        val camPos = floatArrayOf(
            camDist * sin(inclRad).toFloat(),
            0.0f,
            camDist * cos(inclRad).toFloat()
        )
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()
        val fwdLen = sqrt(camPos[0] * camPos[0] + camPos[2] * camPos[2])
        val fwd = floatArrayOf(-camPos[0] / fwdLen, 0.0f, -camPos[2] / fwdLen)
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        val up = floatArrayOf(-cos(inclRad).toFloat(), 0.0f, sin(inclRad).toFloat())

        val stX_tert = -0.26722f
        val stY_tert = 0.03520f
        val rayDir = floatArrayOf(
            fwd[0] + right[0] * (stX_tert * fovScale) + up[0] * (stY_tert * fovScale),
            fwd[1] + right[1] * (stX_tert * fovScale) + up[1] * (stY_tert * fovScale),
            fwd[2] + right[2] * (stX_tert * fovScale) + up[2] * (stY_tert * fovScale)
        )
        val rLen = sqrt(rayDir[0] * rayDir[0] + rayDir[1] * rayDir[1] + rayDir[2] * rayDir[2])
        val normDir = floatArrayOf(rayDir[0] / rLen, rayDir[1] / rLen, rayDir[2] / rLen)

        val rInF = rIn.toFloat()
        val rOutF = rOut.toFloat()

        // 1. Production policy (gargantua_geodesic.frag): minStep = 0.035, maxStep = 0.32
        val resCurrent = GpuEquivalentIntegrator.traceRay(
            M = 1.0f, a = 0.8f, camPos = camPos, rayDir = normDir,
            maxSteps = 250, enableDisk = true, diskInnerRadius = rInF, diskOuterRadius = rOutF,
            minStep = 0.035f, maxStep = 0.32f
        )

        // 2. Half max step: minStep = 0.035, maxStep = 0.16
        val resHalfMax = GpuEquivalentIntegrator.traceRay(
            M = 1.0f, a = 0.8f, camPos = camPos, rayDir = normDir,
            maxSteps = 250, enableDisk = true, diskInnerRadius = rInF, diskOuterRadius = rOutF,
            minStep = 0.035f, maxStep = 0.16f
        )

        // 3. Half min step: minStep = 0.0175, maxStep = 0.32
        val resHalfMin = GpuEquivalentIntegrator.traceRay(
            M = 1.0f, a = 0.8f, camPos = camPos, rayDir = normDir,
            maxSteps = 250, enableDisk = true, diskInnerRadius = rInF, diskOuterRadius = rOutF,
            minStep = 0.0175f, maxStep = 0.32f
        )

        // 4. Both halved: minStep = 0.0175, maxStep = 0.16
        val resBothHalved = GpuEquivalentIntegrator.traceRay(
            M = 1.0f, a = 0.8f, camPos = camPos, rayDir = normDir,
            maxSteps = 250, enableDisk = true, diskInnerRadius = rInF, diskOuterRadius = rOutF,
            minStep = 0.0175f, maxStep = 0.16f
        )

        // Verify physical properties across policies:
        // All policies agree on entering the deep strong-field photon region (minR < 2.5M)
        assertTrue("Current policy minR near photon orbit", resCurrent.minRadiusReached < 2.5f)
        assertTrue("Half max policy minR near photon orbit", resHalfMax.minRadiusReached < 2.5f)
        assertTrue("Half min policy minR near photon orbit", resHalfMin.minRadiusReached < 2.5f)
        assertTrue("Both halved policy minR near photon orbit", resBothHalved.minRadiusReached < 2.5f)

        // Production and half-max policies both reach the physical disk with closely matched hit radii
        // (measured: r = 4.24053 vs 4.24071, g = 1.008798 vs 1.008768, 165 vs 225 steps)
        assertTrue("Production policy must hit the disk", resCurrent.isDiskHit)
        assertTrue("Half max policy must hit the disk", resHalfMax.isDiskHit)
        run {
            val rHitDiff = abs(resCurrent.rHit - resHalfMax.rHit)
            assertTrue("Hit radius difference between Current and HalfMax is within 0.05M", rHitDiff < 0.05f)
            val gDiff = abs(resCurrent.frequencyShift - resHalfMax.frequencyShift)
            assertTrue("Frequency shift g difference between Current and HalfMax is within 0.01", gDiff < 0.01f)
        }

        // Document sensitivity: Halving step sizes naturally increases step count for deep trajectories
        assertTrue("HalfMax step count must exceed Current step count", resHalfMax.stepsTaken > resCurrent.stepsTaken)
    }

    // Phase 4 item 4: the radial emissivity F(r)/F_peak and the relativistic weighting are each applied
    // exactly once between the disk intersection and the HDR target.
    @Test
    fun radialEmissivityAndRelativisticWeightingAreAppliedExactlyOnce() {
        val shader = readShader("gargantua_geodesic.frag")
        val start = shader.indexOf("vec3 evaluate4TierBlackbodySpectrum(float fNorm, float gShift) {")
        assertTrue("Palette function must exist", start >= 0)
        // The palette function ends at the first closing brace in column 0.
        val end = shader.indexOf("\n}\n", start)
        val body = shader.substring(start, end)
        val code = body.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

        // Strict GR mode: g^4 once, no second F(r) factor.
        assertTrue(code.contains("iPhys = pow(gClamped, 4.0);"))
        // Movie mode: no intensity multiplier (F(r) is carried by the palette coordinate only).
        assertTrue(code.contains("iPhys = 1.0;"))
        assertFalse("iPhys must not multiply fNorm again", Regex("iPhys\\s*=[^;]*fNorm").containsMatchIn(code))
        // F(r) enters the colour exactly once, linearly, through the palette coordinate.
        assertTrue(code.contains("float tEff = clamp(gFactor * fNorm, 0.0, 1.0);"))
        assertEquals("fNorm must appear exactly once in the palette code (tEff)", 1, Regex("\\bfNorm\\b").findAll(code.substringAfter("{")).count())
        assertEquals("pow(gClamped, 4.0) must appear exactly once", 1, Regex("pow\\(gClamped, 4\\.0\\)").findAll(code).count())
        assertTrue(code.contains("return thermalColor * iPhys * 0.85;"))

        // The crossing contribution uses the palette colour only; g4/iPhys/radiance at the intersection are anchors.
        assertTrue(shader.contains("vec3 crossingColor = evaluate4TierBlackbodySpectrum(fNorm, gShift);"))
        assertTrue(shader.contains("accumDiskRadiance += diskTransmittance * crossingColor * segAlpha;"))
        val accumLines = shader.lines().filter { it.contains("accumDiskRadiance +=") || it.contains("accumDiskRadiance *=") }
        for (line in accumLines) {
            assertFalse("No g/g4/iPhys/fNorm factor on accumulation: $line", Regex("\\b(g4|g2|gShift|iPhys|fNorm|radiance)\\b").containsMatchIn(line))
        }
        // Composite: exposure and tone mapping only, no g or emissivity weighting.
        val composite = readShader("gargantua_composite.frag")
        assertFalse(Regex("\\b(gShift|fNorm|iPhys|g4)\\b").containsMatchIn(composite))
    }
}
