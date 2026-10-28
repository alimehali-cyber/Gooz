package com.zig.chal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guards for the Chal feature.
 *
 * These are source-level checks for the same reason the rest of the project uses them: there is no
 * Compose or GL test harness on the JVM, so what can be proved is that Chal is wired into the Lab
 * screen, that the GPU pipeline keeps the reference engine's shader contract, and — most
 * importantly — that Chal shares nothing with the Gargantua feature.
 */
class ChalIntegrationGuardTest {

    @Test
    fun chalIsWiredIntoTheLabScreen() {
        val screen = readMain("java/com/alijafari/red/astronomy/ui/screens/LabScreen.kt")

        val start = screen.indexOf("    CHAL(")
        assertTrue("the CHAL entry must be declared in LabFeatureType", start > 0)
        val end = screen.indexOf("\n    )", start)
        assertTrue("the CHAL entry does not end where this guard expects", end > start)
        val block = screen.substring(start, end)

        assertTrue("the Chal card must carry its English title", block.contains("titleEn = \"Chal\""))
        assertTrue("the Chal card must carry its Persian title", block.contains("titleFa = \"چال\""))
        assertTrue("the Chal card must be available", block.contains("isAvailable = true"))

        assertTrue(
            "the Lab screen must route CHAL to the Chal root",
            screen.contains("LabFeatureType.CHAL") && screen.contains("com.zig.chal.ui.ChalRoot")
        )
    }

    @Test
    fun chalSharesNothingWithGargantua() {
        val chalSources = chalSourceFiles()
        assertTrue("expected the Chal package to exist", chalSources.size >= 10)

        for (file in chalSources) {
            // Comments are stripped first: the port deliberately keeps the reference engine's own
            // prose (its dive sequence is literally titled after the film's black hole), and that is
            // documentation, not a dependency. What must never appear is code coupling.
            val code = stripComments(file.readText())
            assertFalse(
                "Chal must not reference the Gargantua feature in code: ${file.name}",
                code.contains("gargantua", ignoreCase = true)
            )
            assertFalse(
                "Chal must not import any other renderer: ${file.name}",
                code.contains("com.zig.gargantua") ||
                    code.contains("com.zig.gravity.renderer") ||
                    code.contains("GargantuaRenderer") ||
                    code.contains("GargantuaSurfaceView")
            )
        }

        // The Chal branch in the Lab screen must not have picked up a Gargantua dependency either.
        val screen = readMain("java/com/alijafari/red/astronomy/ui/screens/LabScreen.kt")
        val chalStart = screen.indexOf("selectedFeature == LabFeatureType.CHAL")
        assertTrue("the CHAL routing branch must exist", chalStart > 0)
        val chalEnd = screen.indexOf("} else {", chalStart)
        val chalBranch = screen.substring(chalStart, if (chalEnd > chalStart) chalEnd else screen.length)
        assertFalse(
            "the CHAL branch must not touch Gargantua",
            chalBranch.contains("gargantua", ignoreCase = true)
        )
    }

    @Test
    fun theShaderPortKeepsTheReferencePipeline() {
        val shader = readMain("java/com/zig/chal/shader/ChalShaderSource.kt")
        val shaderManager = readMain("java/com/zig/chal/shader/ChalShaderManager.kt")
        val features = readMain("java/com/zig/chal/config/ChalFeatures.kt")

        // GLSL ES 3.00 entry point and colour output, exactly as the reference emits it.
        assertTrue(shader.contains("#version 300 es"))
        assertTrue(shader.contains("precision highp float"))
        assertTrue(shader.contains("fragColor"))

        // Kerr closed forms ported from the reference's metric chunk.
        for (symbol in listOf(
            "kerr_horizon",
            "kerr_photon_sphere",
            "kerr_isco",
            "kerr_r",
            "kerr_geodesic_accel",
            "kerr_ergosphere",
            "kerr_shadow_radius",
            "sample_accretion_disk"
        )) {
            assertTrue("the shader port lost the reference symbol '$symbol'", shader.contains(symbol))
        }

        // Uniform contract from the reference fragment shader.
        for (uniform in listOf(
            "u_resolution",
            "u_time",
            "u_mass",
            "u_spin",
            "u_disk_density",
            "u_disk_temp",
            "u_mouse",
            "u_zoom",
            "u_lensing_strength",
            "u_frame_dragging_strength",
            "u_disk_size",
            "u_disk_scale_height",
            "u_maxRaySteps",
            "u_noiseTex",
            "u_blueNoiseTex",
            "u_debug",
            "u_show_redshift",
            "u_show_kerr_shadow",
            "u_shadowShift",
            "u_shadowCurve",
            "u_shadowCount",
            "u_camPos",
            "u_camQuat"
        )) {
            assertTrue("the shader port lost the uniform '$uniform'", shader.contains(uniform))
        }

        // The quality tiers the shader branches on.
        for (quality in listOf(
            "RAY_QUALITY_OFF",
            "RAY_QUALITY_LOW",
            "RAY_QUALITY_MEDIUM",
            "RAY_QUALITY_HIGH",
            "RAY_QUALITY_ULTRA"
        )) {
            assertTrue("the quality tier '$quality' was lost", features.contains(quality))
        }
        assertTrue(
            "the manager must inject the active quality tier",
            shaderManager.contains("shaderDefine")
        )

        // Feature and quality macros used for shader recompilation.
        for (define in listOf(
            "ENABLE_LENSING",
            "ENABLE_DISK",
            "ENABLE_DOPPLER",
            "ENABLE_STARS",
            "ENABLE_PHOTON_GLOW",
            "ENABLE_BLOOM",
            "ENABLE_JETS",
            "ENABLE_REDSHIFT",
            "ENABLE_SHADOW_GUIDE",
            "ENABLE_LINEAR_OUTPUT"
        )) {
            assertTrue("the manager lost the define '$define'", shaderManager.contains(define))
        }
    }

    @Test
    fun theGlesFallbackUsesDimensionlessSpinAtTheRendererBoundary() {
        val renderer = readMain("java/com/zig/chal/render/ChalRenderer.kt")
        val shader = readMain("java/com/zig/chal/shader/ChalShaderSource.kt")

        assertTrue(renderer.contains("set1f(\"u_spin\", frameParams.spin)"))
        assertFalse(renderer.contains("set1f(\"u_spin\", frameParams.spin * frameParams.mass)"))
        assertTrue(shader.contains("float a = u_spin * M;"))
    }

    @Test
    fun theRendererBindsTheSamplerUniformsAsIntegers() {
        val renderer = readMain("java/com/zig/chal/render/ChalRenderer.kt")
        // Sampler uniforms set with uniform1f silently sample texture unit 0 on some drivers; the
        // reference is explicit about the integer path and so is the port.
        assertTrue(renderer.contains("u_noiseTex"))
        assertTrue(renderer.contains("u_blueNoiseTex"))
        assertTrue(
            "sampler uniforms must be set with the integer uploader",
            renderer.contains("glUniform1i")
        )
    }

    @Test
    fun theMobilePerformanceContractIsPinned() {
        val renderer = readMain("java/com/zig/chal/render/ChalRenderer.kt")
        val monitor = readMain("java/com/zig/chal/render/ChalPerformanceMonitor.kt")
        val config = readMain("java/com/zig/chal/config/ChalPerformanceConfig.kt")
        val params = readMain("java/com/zig/chal/config/ChalSimulationParams.kt")
        val shader = readMain("java/com/zig/chal/shader/ChalShaderSource.kt")

        // 1. The ray-step budget must go through the mobile cap.
        assertTrue(
            "the renderer must request the mobile ray-step budget",
            renderer.contains("isMobile = ChalPerformanceConfig.Mobile.IS_MOBILE_HARDWARE")
        )
        assertTrue(
            "every Chal target is mobile hardware",
            config.contains("const val IS_MOBILE_HARDWARE: Boolean = true")
        )

        // 2. When enabled, the adaptive controller owns the render scale; the reference's PID must keep running
        //    alongside the direct rescale (it is the part that trims the scale afterwards).
        assertTrue(
            "the reference's PID controller must still drive the resolution",
            monitor.contains("applyPidScaling(controllerDelta)")
        )
        assertTrue(
            "the direct rescale must not replace the PID",
            monitor.indexOf("applyFastRecalibration()") < monitor.indexOf("applyPidScaling(controllerDelta)")
        )
        assertTrue(
            "resolution changes must be clamped to the mobile cap",
            monitor.contains("ChalPerformanceConfig.Resolution.MOBILE_CAP")
        )
        assertTrue(
            "the direct (non-PID) rescale must exist",
            monitor.contains("fun proportionalScale(") && monitor.contains("fun requestFastRecalibration()")
        )

        // 3. Mobile sessions must start light, not at native resolution.
        assertTrue("the mobile start scale must be configured", config.contains("const val START_SCALE"))
        assertTrue(
            "the mobile entry state must exist and use the balanced preset",
            params.contains("val MOBILE_PARAMS") && params.contains("ChalPresetName.BALANCED")
        )

        // 4. The far-field termination is the single largest GPU saving; the block markers make
        //    the extension auditable against the reference source.
        assertTrue(
            "the marcher must terminate escaped rays",
            shader.contains("if (r > escapeRadius && dot(p, v) > 0.0) break;")
        )
        assertTrue(
            "the far-field termination must be marked as a Chal extension",
            shader.contains("CHAL-LOD-BEGIN") && shader.contains("CHAL-LOD-END")
        )
        assertTrue(
            "the escape radius must track the disc extent",
            shader.contains("float escapeRadius = max(60.0, M * u_disk_size * 1.25);")
        )

        // 5. The background nebula must use the reference's cheap adaptiveFbm path.
        assertTrue(
            "the 4-octave nebula costs 16 redundant texture fetches per pixel",
            shader.contains("adaptiveFbm(dir * 2.0 + u_time * 0.01, 2)")
        )

        // 6. Post-processing must not re-query the driver every frame.
        val bloom = readMain("java/com/zig/chal/render/ChalBloom.kt")
        val reprojection = readMain("java/com/zig/chal/render/ChalReprojection.kt")
        for ((name, source) in listOf("ChalBloom.kt" to bloom, "ChalReprojection.kt" to reprojection)) {
            assertTrue("$name must cache uniform locations", source.contains("LocationCache"))
            assertFalse(
                "$name must not query uniform locations per frame",
                source.contains("GLES30.glGetUniformLocation(")
            )
            assertFalse(
                "$name must not query attribute locations per frame",
                source.contains("GLES30.glGetAttribLocation(")
            )
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Removes line and block comments so the independence guard only ever sees code. */
    private fun stripComments(source: String): String {
        val withoutBlocks = Regex("/\\*[\\s\\S]*?\\*/").replace(source, " ")
        return withoutBlocks.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    private fun chalSourceFiles(): List<File> =
        File(mainDir(), "java/com/zig/chal").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun readMain(relative: String): String {
        val f = File(mainDir(), relative)
        assertTrue("missing source file $relative", f.isFile)
        return f.readText()
    }

    private fun mainDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main")
            if (File(candidate, "java/com/alijafari/red/astronomy").isDirectory) return candidate
            val direct = File(dir, "src/main")
            if (File(direct, "java/com/alijafari/red/astronomy").isDirectory) return direct
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the app main source set")
    }

    /**
     * Source-shape guard (not a GL test): GLSurfaceView swaps buffers after EVERY callback, so a
     * callback that draws nothing presents a stale buffer from two or three frames ago, which
     * shimmers on 90/120 Hz panels and while paused. Every no-new-frame path must re-present the
     * retained frame, or wait until a frame is due, never just return.
     */
    @Test
    fun callbacksThatAreNotDueNeverSwapAStaleBuffer() {
        val renderer = readMain("java/com/zig/chal/render/ChalRenderer.kt")

        assertTrue("the retained-frame presenter must exist", renderer.contains("private fun presentRetainedFrame(): Boolean"))
        assertTrue(
            "a paused, unchanged scene must re-present its frozen frame",
            renderer.contains("if (presentRetainedFrame()) return")
        )
        assertTrue(
            "without a retained frame the thread must wait for the next due frame",
            renderer.contains("sleepQuietly(frameClock.remainingMs(frameStart, targetFrameTime))")
        )
        assertFalse(
            "a bare 'frameDelta(...) ?: return' is the stale-buffer bug",
            Regex("""frameClock\.frameDelta\(.*\)\s*\?:\s*return""").containsMatchIn(
                renderer.replace("force = true) ?: return", "force = true)")
            )
        )
        assertTrue(
            "the error path must present clean black",
            renderer.contains("GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)")
        )
    }
}
