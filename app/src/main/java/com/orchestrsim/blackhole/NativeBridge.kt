package com.orchestrsim.blackhole

import android.content.Context
import android.view.Surface
import java.io.File

/**
 * JNI contract exported by the XAPK's `libblackhole.so`.
 *
 * The ARM64 payload lives in assets rather than `jniLibs`: this keeps the app installable on other
 * ABIs, where Chal falls back to GLES. It is extracted and loaded only after the Vulkan/ABI gate.
 * Keep this class/package/method shape in sync with the symbols in the extracted native library.
 */
object NativeBridge {
    private const val ASSET_DIRECTORY = "xapk-native/arm64-v8a"
    private const val CACHE_DIRECTORY = "xapk-native/arm64-v8a"

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var loaded = false

    @Volatile
    var loadFailure: String? = null
        private set

    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true
        if (loadAttempted) return false
        loadAttempted = true

        return try {
            val nativeDirectory = File(context.codeCacheDir, CACHE_DIRECTORY)
            if (!nativeDirectory.exists() && !nativeDirectory.mkdirs()) {
                error("could not create native-library cache directory")
            }

            val cxxLibrary = extractAsset(context, "libc++_shared.so", nativeDirectory)
            val rendererLibrary = extractAsset(context, "libblackhole.so", nativeDirectory)
            // Load the dependency first; libblackhole.so has no RUNPATH and imports its C++ runtime
            // by SONAME. Both files are in this app's private linker namespace.
            System.load(cxxLibrary.absolutePath)
            System.load(rendererLibrary.absolutePath)
            loaded = true
            true
        } catch (failure: Throwable) {
            loadFailure = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
            false
        }
    }

    /** Fast status check for renderer callbacks after the context-aware load attempt. */
    fun ensureLoaded(): Boolean = loaded

    private fun extractAsset(context: Context, fileName: String, destination: File): File {
        val target = File(destination, fileName)
        context.assets.open("$ASSET_DIRECTORY/$fileName").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        check(target.setReadable(true, true)) { "could not make $fileName readable" }
        check(target.setExecutable(true, true)) { "could not make $fileName executable" }
        return target
    }

    external fun nativeGetTelemetry(output: FloatArray)
    external fun nativeGravitasVersion(): String
    external fun nativeKerrErgosphere(mass: Double, spin: Double, cosTheta: Double): Double
    external fun nativeKerrHorizon(mass: Double, spin: Double): Double
    external fun nativeKerrIsco(mass: Double, spin: Double, prograde: Boolean): Double
    external fun nativeKerrPhotonSphere(mass: Double, spin: Double): Double
    external fun nativeOnPause(paused: Boolean)
    external fun nativeOnSurfaceChanged(width: Int, height: Int)
    external fun nativeOnSurfaceCreated(surface: Surface)
    external fun nativeOnSurfaceDestroyed()
    external fun nativeSetCamera(yaw: Float, pitch: Float, distance: Float)
    external fun nativeSetParams(parameters: FloatArray, flags: IntArray)
}
