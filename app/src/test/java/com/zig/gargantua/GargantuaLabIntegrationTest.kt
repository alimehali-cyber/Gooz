package com.zig.gargantua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates Gargantua integration within the Lab screen and confirms
 * that the existing Lab ordering and Gravity Sandbox guards remain intact.
 */
class GargantuaLabIntegrationTest {

    private val titleEn = "Gargantua"
    private val titleFa = "گارگانتوا"

    @Test
    fun gargantuaFeatureIsDeclaredInLabScreen() {
        val labScreenText = readSource("java/com/alijafari/red/astronomy/ui/screens/LabScreen.kt")
        assertTrue("LabFeatureType must declare GARGANTUA", labScreenText.contains("GARGANTUA("))

        val start = labScreenText.indexOf("    GARGANTUA(")
        assertTrue("GARGANTUA block must be present", start > 0)
        // Terminator tolerates both ")," and the bare ")" that closes the last enum entry: the
        // removed placeholder cards used to follow GARGANTUA, so it no longer ends with a comma.
        val end = labScreenText.indexOf("\n    )", start)
        assertTrue(end > start)
        val block = labScreenText.substring(start, end)

        val enMatch = Regex("\\btitleEn = \"([^\"]*)\"").find(block)
        val faMatch = Regex("\\btitleFa = \"([^\"]*)\"").find(block)
        assertNotNull(enMatch)
        assertNotNull(faMatch)

        assertEquals(titleEn, enMatch!!.groupValues[1])
        assertEquals(titleFa, faMatch!!.groupValues[1])
        assertTrue("isAvailable must be true", block.contains("isAvailable = true"))
    }

    @Test
    fun existingLabFeatureOrderingRemainsUntouched() {
        val text = readSource("java/com/alijafari/red/astronomy/ui/screens/LabScreen.kt")

        val timeDilation = text.indexOf("    TIME_DILATION(")
        val gravity = text.indexOf("    GRAVITY_SANDBOX(")
        val gargantua = text.indexOf("    GARGANTUA(")

        assertTrue("All three features must be declared", minOf(timeDilation, gravity, gargantua) > 0)
        assertTrue(
            "Original relative ordering must remain strictly preserved for LabFeatureTitleTest",
            timeDilation < gravity && gravity < gargantua
        )
        assertTrue(
            "Gargantua must be positioned immediately after Gravity Sandbox",
            gravity < gargantua
        )
        assertTrue(
            "The removed placeholder cards must not come back",
            !text.contains("ORBITAL_RESONANCE") && !text.contains("STELLAR_EVOLUTION")
        )
    }

    @Test
    fun gargantuaRootInvocationIsWiredCorrectly() {
        val text = readSource("java/com/alijafari/red/astronomy/ui/screens/LabScreen.kt")
        assertTrue(
            "Tapping Gargantua card must invoke GargantuaRoot with onBack callback",
            text.contains("selectedFeature == LabFeatureType.GARGANTUA") &&
                text.contains("com.zig.gargantua.ui.GargantuaRoot(")
        )
    }

    @Test
    fun stringResourcesArePresentInBothLocales() {
        val enXml = readSource("res/values/strings.xml")
        val faXml = readSource("res/values-fa/strings.xml")

        assertTrue(enXml.contains("<string name=\"gargantua_title\">$titleEn</string>"))
        assertTrue(faXml.contains("<string name=\"gargantua_title\">$titleFa</string>"))
        assertTrue(enXml.contains("<string name=\"gargantua_subtitle\">"))
        assertTrue(faXml.contains("<string name=\"gargantua_subtitle\">"))
    }

    private fun readSource(relative: String): String {
        val f = File(mainDir(), relative)
        assertTrue("Missing file: ${f.absolutePath}", f.isFile)
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
        throw AssertionError("could not locate app/src/main")
    }
}
