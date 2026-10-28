package com.alijafari.red.astronomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Labs card for the sandbox is *named*, not described.
 *
 * Source-level, like the sandbox's own structural guards: this project has no Compose UI test
 * harness, so what can be proved from the JVM is the string the card renders, the code path that
 * renders it, and the fact that nothing else on the card moved. The title is the feature's name in
 * both languages and carries nothing else — no product prefix, no colon, no ampersand, and no
 * subtitle folded into it. Subtitle, description, icon, ordering and navigation are the card's own
 * business and are pinned here as unchanged.
 */
class LabFeatureTitleTest {

    private val titleEn = "Gravity Sandbox"
    private val titleFa = "میز گرانش"

    @Test
    fun theGravityCardTitleIsExactlyTheFeatureName() {
        val block = featureBlock("GRAVITY_SANDBOX")
        assertEquals(titleEn, value(block, "titleEn"))
        assertEquals(titleFa, value(block, "titleFa"))
    }

    @Test
    fun theTitleCarriesNoPrefixNoSubtitleAndNoForbiddenWording() {
        val block = featureBlock("GRAVITY_SANDBOX")
        val en = value(block, "titleEn")
        val fa = value(block, "titleFa")

        // The wording that was ruled out, byte for byte: the sentence the card used to carry as its
        // title, and every alternative name the sandbox must not be called.
        assertNotEquals("the English title must not be the old sentence", "Gravity Sandbox & N-Body Physics", en)
        assertNotEquals("a bare 'Sandbox' names nothing", "Sandbox", en)
        for (banned in listOf("ZIG", "Zig", ":", "&", "N-Body", "Physics", "Simulator")) {
            assertFalse("the English title must not carry '$banned': $en", en.contains(banned))
        }

        assertNotEquals("the Persian title must not be the old sentence", "شبیه‌ساز گرانش و برهم‌کنش‌های N-جرم", fa)
        for (banned in listOf("شبیه‌ساز گرانش", "آزمایشگاه گرانش", "ZIG", ":", "&", "N-جرم")) {
            assertFalse("the Persian title must not carry '$banned': $fa", fa.contains(banned))
        }

        // A name, not a sentence: two words in each language.
        assertEquals("the English title is a name", 2, en.split(" ").size)
        assertEquals("the Persian title is a name", 2, fa.split(" ").size)
    }

    @Test
    fun theCardStillLocalizesItsTitleAndKeepsEverythingElse() {
        val text = labScreen()

        // The localization mechanism is untouched: one Text per line of the card, chosen by locale,
        // with nothing concatenated onto the feature name.
        assertTrue(text.contains("if (isFa) feature.titleFa else feature.titleEn"))
        assertTrue(text.contains("if (isFa) feature.subtitleFa else feature.subtitleEn"))
        assertTrue("the card is still addressed by its tag", text.contains("lab_feature_card_"))

        val block = featureBlock("GRAVITY_SANDBOX")
        assertEquals("Newton-Kepler Orbital Simulator", value(block, "subtitleEn"))
        assertEquals("آزمایشگاه مکانیک سماوی و مدارهای کپلری", value(block, "subtitleFa"))
        assertEquals("Simulate multi-body gravitational interactions, planetary orbits, binary stars, Lagrange equilibrium points, and black hole slingshots.", value(block, "descriptionEn"))
        assertTrue("the icon is unchanged", block.contains("icon = Icons.Default.Public"))
        assertTrue("the card is still available", block.contains("isAvailable = true"))

        // Ordering and navigation are unchanged: the sandbox is still the second feature, and it
        // still opens the sandbox.
        val timeDilation = text.indexOf("    TIME_DILATION(")
        val gravity = text.indexOf("    GRAVITY_SANDBOX(")
        val gargantua = text.indexOf("    GARGANTUA(")
        assertTrue("all three features are still declared", minOf(timeDilation, gravity, gargantua) > 0)
        assertTrue("and still in the same order", timeDilation < gravity && gravity < gargantua)
        assertTrue(
            "the two 'Coming Soon' placeholder cards must stay removed",
            !text.contains("ORBITAL_RESONANCE") && !text.contains("STELLAR_EVOLUTION")
        )
        assertTrue(
            "tapping the sandbox card still opens the sandbox",
            text.contains("selectedFeature == LabFeatureType.GRAVITY_SANDBOX") &&
                text.contains("com.zig.gravity.ui.GravitySandboxRoot(")
        )
    }

    @Test
    fun theTitleResourcesAgreeWithTheCard() {
        // The resource pair carries the same title as the card in both languages: a second,
        // contradictory name for the feature is how the wrong wording comes back later. The
        // subtitles are descriptions rather than titles and are deliberately left alone.
        assertTrue(
            "the Persian title resource must read exactly the card's Persian title",
            resource("values-fa").contains("<string name=\"gravity_sandbox_title\">$titleFa</string>")
        )
        assertTrue(
            "the English title resource must read exactly the card's English title",
            resource("values").contains("<string name=\"gravity_sandbox_title\">$titleEn</string>")
        )
        assertFalse(
            "the retired Persian title must not survive anywhere in the resources",
            resource("values-fa").contains("شبیه‌ساز گرانش")
        )
        assertTrue(resource("values").contains("<string name=\"gravity_sandbox_subtitle\">N-Body Newton-Kepler Simulator</string>"))
        assertTrue(resource("values-fa").contains("<string name=\"gravity_sandbox_subtitle\">شبیه‌سازی برهم‌کنش‌های N-جرم گرانشی</string>"))
    }

    // -----------------------------------------------------------------------------------------

    private fun featureBlock(name: String): String {
        val text = labScreen()
        val start = text.indexOf("    $name(")
        assertTrue("no LabFeatureType.$name entry in LabScreen.kt", start > 0)
        val end = text.indexOf("\n    ),", start)
        assertTrue("the $name entry does not end where this guard expects", end > start)
        return text.substring(start, end)
    }

    private fun value(block: String, key: String): String {
        // \b matters: "titleEn" is a substring of "subtitleEn", so an unanchored pattern could read
        // the subtitle and quietly assert the wrong thing.
        val match = Regex("\\b$key = \"([^\"]*)\"").find(block)
        assertNotNull("no $key in the card entry", match)
        return match!!.groupValues[1]
    }

    private fun labScreen(): String = readMain("java/com/alijafari/red/astronomy/ui/screens/LabScreen.kt")

    private fun resource(values: String): String = readMain("res/$values/strings.xml")

    private fun readMain(relative: String): String {
        val f = File(mainDir(), relative)
        assertTrue("missing source file $relative", f.isFile)
        return f.readText()
    }

    private fun mainDir(): File {
        // Gradle runs unit tests with the module directory as the working directory, but walk up so
        // the guard does not depend on that detail.
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
