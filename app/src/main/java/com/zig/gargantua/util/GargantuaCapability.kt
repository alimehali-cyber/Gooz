package com.zig.gargantua.util

import android.app.ActivityManager
import android.content.Context

/**
 * Hardware capability detector for Gargantua GPU renderer.
 * Validates OpenGL ES 3.x support before creating any GLES3 resources.
 */
object GargantuaCapability {

    const val MIN_GLES_VERSION = 0x00030000 // OpenGL ES 3.0

    fun isGles3Supported(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val reqGlEsVersion = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        return reqGlEsVersion >= MIN_GLES_VERSION
    }
}
