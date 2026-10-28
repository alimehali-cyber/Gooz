package com.zig.gravity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.gravity.edu.Challenge
import com.zig.gravity.edu.Challenges
import com.zig.gravity.edu.Glossary
import com.zig.gravity.edu.TeachingCatalog
import com.zig.gravity.edu.TeachingTier
import com.zig.gravity.edu.detectors.SimulationDetectors
import com.zig.gravity.sim.SimulationViewModel
import com.zig.gravity.ui.theme.LocalGravityColors

/**
 * §3.14 teaching card — three tiers, opt-in, never blocking.
 *
 * It floats above the tabletop, is always dismissible, and never pauses or gates the simulation.
 */
@Composable
fun TeachingCardView(vm: SimulationViewModel, modifier: Modifier = Modifier) {
    val concept = vm.teachingConcept ?: return
    val card = TeachingCatalog.card(concept) ?: return
    val c = LocalGravityColors.current
    val fa = vm.persian
    val tier = vm.teachingTier

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(squircleShape(18.dp))
            .background(c.chrome)
            .border(1.dp, c.chromeBorder, squircleShape(18.dp))
            .padding(14.dp)
            .testTag("teaching_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(c.accent)
            )
            Spacer(Modifier.width(8.dp))
            val impactHeadline = if (concept == SimulationDetectors.IMPACT_ENERGY) {
                if (fa) vm.impactHeadlineFa else vm.impactHeadlineEn
            } else {
                null
            }
            Text(
                text = impactHeadline ?: (if (fa) card.titleFa else card.titleEn),
                color = c.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickableTag("teaching_dismiss") { vm.dismissTeaching() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = if (fa) "بستن" else "Dismiss", tint = c.onSurfaceDim, modifier = Modifier.size(15.dp))
            }
        }

        // §15 — when the card is about an impact, the real numbers from the real collision are
        // shown first, then the plain-language explanation. Progressive disclosure: headline in the
        // title, one sentence here, the general physics under the WHY/MORE tiers.
        val impactDetail = if (concept == SimulationDetectors.IMPACT_ENERGY) {
            if (fa) vm.impactDetailFa else vm.impactDetailEn
        } else {
            null
        }
        if (impactDetail != null && tier == TeachingTier.WHAT) {
            Text(
                text = impactDetail,
                color = c.onSurface.copy(alpha = 0.92f),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.testTag("impact_explanation")
            )
        } else {
            Text(
                text = TeachingCatalog.text(card, tier, fa),
                color = c.onSurface.copy(alpha = 0.92f),
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }

        if (tier == TeachingTier.MORE && card.formula != null) {
            Text(text = card.formula, color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        // §24 — "Try this". Shown on the first tier, where a newcomer is, and not repeated on the
        // deeper tiers where they are already reading rather than doing.
        val tryThis = if (fa) card.tryThisFa else card.tryThisEn
        if (tier == TeachingTier.WHAT && tryThis != null) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.testTag("teaching_try_this")) {
                Text(
                    text = if (fa) "امتحان کن:" else "Try this:",
                    color = c.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(6.dp))
                Text(text = tryThis, color = c.onSurfaceDim, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in TeachingTier.entries) {
                val label = (if (fa) TeachingCatalog.TIER_LABEL_FA else TeachingCatalog.TIER_LABEL_EN)[t] ?: ""
                val selected = t == tier
                Box(
                    modifier = Modifier
                        .clip(squircleShape(12.dp))
                        .background(if (selected) c.accent.copy(alpha = 0.18f) else Color.Transparent)
                        .border(
                            1.dp,
                            if (selected) c.accent.copy(alpha = 0.5f) else c.chromeBorder,
                            squircleShape(12.dp)
                        )
                        .clickableTag("teaching_tier_${t.name.lowercase()}") { vm.setTeachingTier(t) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(label, color = if (selected) c.accent else c.onSurfaceDim, fontSize = 11.sp)
                }
            }
        }
    }
}

/** §3.14 — the eight POE challenges, plus the locked glossary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeSheet(vm: SimulationViewModel, onDismiss: () -> Unit) {
    val c = LocalGravityColors.current
    val fa = vm.persian
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val active = vm.activeChallenge

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.chrome,
        contentColor = c.onSurface,
        modifier = Modifier.testTag("challenge_sheet")
    ) {
        // Same reasoning as the other sheets: the challenge list scrolls itself, but the prediction
        // controls that follow it must stay reachable when the sheet is taller than the screen.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (active == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Science, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (fa) "آزمایش کن و حدس بزن" else "Predict, then observe",
                        color = c.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(Challenges.all) { ch ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(squircleShape(14.dp))
                                .background(c.onSurface.copy(alpha = 0.05f))
                                .clickableTag("challenge_${ch.kind.name.lowercase()}") { vm.startChallenge(ch) }
                                .padding(14.dp)
                        ) {
                            Text(
                                text = if (fa) ch.titleFa else ch.titleEn,
                                color = c.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (fa) ch.questionFa else ch.questionEn,
                                color = c.onSurfaceDim,
                                fontSize = 11.sp
                            )
                        }
                    }
                    item { GlossaryBlock(fa) }
                }
            } else {
                ActiveChallenge(vm, active, fa, onDismiss)
            }
        }
    }
}

@Composable
private fun ActiveChallenge(
    vm: SimulationViewModel,
    ch: Challenge,
    fa: Boolean,
    onDismiss: () -> Unit
) {
    val c = LocalGravityColors.current
    val prediction = vm.challengePrediction
    val resolved = vm.challengeResultOptionId

    Text(
        text = if (fa) ch.titleFa else ch.titleEn,
        color = c.onSurface,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = if (fa) ch.questionFa else ch.questionEn,
        color = c.onSurface.copy(alpha = 0.9f),
        fontSize = 13.sp,
        lineHeight = 20.sp
    )

    for (option in ch.options) {
        val chosen = option.id == prediction
        val isTruth = resolved != null && option.id == resolved
        val tint = when {
            isTruth -> c.accent
            chosen && resolved != null -> c.onSurfaceDim
            chosen -> c.accent
            else -> c.onSurfaceDim
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(squircleShape(12.dp))
                .background(if (chosen || isTruth) c.accent.copy(alpha = 0.10f) else Color.Transparent)
                .border(1.dp, if (chosen || isTruth) c.accent.copy(alpha = 0.5f) else c.chromeBorder, squircleShape(12.dp))
                .clickableTag("option_${option.id}") { if (prediction == null) vm.submitPrediction(option.id) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (fa) option.textFa else option.textEn,
                color = tint,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (isTruth) {
                Text(
                    text = if (fa) "شبیه‌سازی همین را نشان داد" else "the simulation showed this",
                    color = c.accent,
                    fontSize = 10.sp
                )
            }
        }
    }

    if (prediction == null) {
        Text(
            text = if (fa) "اول حدست را انتخاب کن، بعد آزمایش را تماشا کن." else "Choose your guess first, then watch the experiment.",
            color = c.onSurfaceDim,
            fontSize = 11.sp
        )
    } else {
        Text(
            text = if (fa) ch.setupFa else ch.setupEn,
            color = c.onSurfaceDim,
            fontSize = 11.sp
        )
    }

    if (resolved != null) {
        val correct = resolved == prediction
        Text(
            text = when {
                correct && fa -> "درست حدس زدی. همین اتفاق در شبیه‌سازی افتاد."
                correct -> "Your prediction matched what the simulation actually did."
                fa -> "حدست فرق داشت — و این هیچ اشکالی ندارد. شبیه‌سازی نتیجه دیگری نشان داد؛ ببین چرا."
                else -> "Your guess differed — which is fine. The simulation did something else; here is why."
            },
            color = if (correct) c.accent else c.onSurface,
            fontSize = 12.sp
        )
        TeachingCatalog.card(ch.explainConcept)?.let { card ->
            Text(
                text = if (fa) card.whyFa else card.whyEn,
                color = c.onSurface.copy(alpha = 0.9f),
                fontSize = 12.sp,
                lineHeight = 19.sp
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .clip(squircleShape(12.dp))
                .background(c.accent.copy(alpha = 0.12f))
                .clickableTag("challenge_close") {
                    vm.closeChallenge()
                    onDismiss()
                }
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Text(if (fa) "پایان آزمایش" else "End experiment", color = c.accent, fontSize = 12.sp)
        }
        Box(
            modifier = Modifier
                .clip(squircleShape(12.dp))
                .border(1.dp, c.chromeBorder, squircleShape(12.dp))
                .clickableTag("challenge_watch") { onDismiss() }
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Text(if (fa) "تماشای میز" else "Watch the table", color = c.onSurfaceDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GlossaryBlock(fa: Boolean) {
    val c = LocalGravityColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = c.onSurfaceDim, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (fa) "واژه‌نامه" else "Glossary",
                color = c.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        for (term in Glossary.terms) {
            Column(modifier = Modifier.testTag("glossary_${term.en.lowercase().replace(' ', '_')}")) {
                Text(
                    text = if (fa) term.fa else term.en,
                    color = c.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (fa) term.meaningFa else term.meaningEn,
                    color = c.onSurfaceDim,
                    fontSize = 10.sp
                )
            }
        }
    }
}
