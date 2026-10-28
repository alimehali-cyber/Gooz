package com.zig.gargantua.renderer

/**
 * Lifecycle contract for the first dirty-render request.
 *
 * A request is emitted only after both the GLES resources and a non-empty surface size are ready.
 * It emits once for each ready surface/resource generation, including a same-size EGL recreation.
 */
internal class GargantuaRenderReadyGate(
    private val onReady: () -> Unit
) {
    private var resourcesReady = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var requestEmitted = false

    fun reset() {
        resourcesReady = false
        surfaceWidth = 0
        surfaceHeight = 0
        requestEmitted = false
    }

    fun markResourcesReady() {
        resourcesReady = true
        emitIfReady()
    }

    fun markSurfaceSize(width: Int, height: Int) {
        val changed = width != surfaceWidth || height != surfaceHeight
        surfaceWidth = width
        surfaceHeight = height
        if (changed) requestEmitted = false
        emitIfReady()
    }

    private fun emitIfReady() {
        if (
            resourcesReady &&
            surfaceWidth > 0 &&
            surfaceHeight > 0 &&
            !requestEmitted
        ) {
            requestEmitted = true
            onReady()
        }
    }
}
