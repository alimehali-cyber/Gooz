package com.zig.gargantua

import com.zig.gargantua.util.GargantuaCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates Gargantua OpenGL ES capability threshold logic.
 */
class GargantuaCapabilityTest {

    @Test
    fun minimumGlesVersionIsConfiguredToGles30() {
        assertEquals(0x00030000, GargantuaCapability.MIN_GLES_VERSION)
    }

    @Test
    fun glesVersionEvaluationAccuratelyDistinguishesGlesVersions() {
        // Direct test of version hex comparison logic
        fun isSupported(reqGlEsVersion: Int): Boolean =
            reqGlEsVersion >= GargantuaCapability.MIN_GLES_VERSION

        // GLES 1.x / 2.0 (legacy) -> false
        assertFalse(isSupported(0x00010000))
        assertFalse(isSupported(0x00020000))
        assertFalse(isSupported(0x0002FFFF))

        // GLES 3.0 / 3.1 / 3.2 -> true
        assertTrue(isSupported(0x00030000)) // GLES 3.0
        assertTrue(isSupported(0x00030001)) // GLES 3.1
        assertTrue(isSupported(0x00030002)) // GLES 3.2
    }
}
