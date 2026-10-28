package com.alijafari.red.astronomy.ui.persepolis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-shape guards for the Persepolis + Mil-e Azhdaha hosting (not GL tests — the
 * relief needs a GL context, which the JVM does not have).
 *
 * The ruins are hosted on a TextureView ([PersepolisTextureView]) so they composite
 * into the window like the painted layers around them: Compose siblings drawn later
 * (the search overlay, the readout, dialogs-in-window) cover and dim them, and they
 * scroll with the Home feed. The previous window-level overlay surface claimed the top
 * window z-order, which let the relief punch through every Compose overlay on screen
 * — the search window most visibly. These guards keep that regression from returning
 * silently.
 */
class PersepolisHostingGuardTest {

    @Test
    fun reliefIsHostedByATextureViewWithItsOwnEglThread() {
        val host = readMain("java/com/alijafari/red/astronomy/ui/persepolis/PersepolisTextureView.kt")
        assertTrue("the relief host must be a TextureView", host.contains(": TextureView("))
        assertTrue("the host must own an EGL thread driving the scene", host.contains("EGL14"))
        assertTrue("the host must present frames itself", host.contains("eglSwapBuffers"))
        assertTrue("the host must render the unchanged scene", host.contains("PersepolisMilAzhdahaScene"))
    }

    @Test
    fun noWindowTopSurfaceRemainsInThePersepolisPackage() {
        val pkg = File(mainDir(), "java/com/alijafari/red/astronomy/ui/persepolis")
        assertTrue("persepolis package missing", pkg.isDirectory)
        val files = pkg.listFiles { f -> f.isFile && f.extension == "kt" }.orEmpty()
        assertTrue("persepolis package has no Kotlin sources", files.isNotEmpty())
        for (file in files) {
            val text = file.readText()
            assertFalse(
                "${file.name} must not host the relief on a framework GL surface view",
                text.contains("GLSurfaceView")
            )
            assertFalse(
                "${file.name} must not claim the top window z-order (that is what punched through the search overlay)",
                text.contains("setZOrderOnTop")
            )
        }
    }

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
}
