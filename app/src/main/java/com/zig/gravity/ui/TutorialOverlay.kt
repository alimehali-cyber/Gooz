package com.zig.gravity.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.gravity.edu.TutorialContent
import com.zig.gravity.edu.TutorialGesture
import com.zig.gravity.edu.TutorialStep
import com.zig.gravity.physics.EngineConstants
import com.zig.gravity.ui.theme.LocalGravityColors
import com.zig.gravity.util.PersianDigits

/**
 * The first-launch tutorial (and the same thing again behind the `?` button).
 *
 * ### What it deliberately is not
 *
 * Not a dialog, not a full-screen carousel, not a stack of slides about an app you cannot see. The
 * simulation keeps running behind it and stays visible: the scrim is light, the card sits at the
 * bottom, and the step being explained is the actual interface the sentence is about.
 *
 * ### What it never touches
 *
 * Nothing. This composable takes no `SimulationViewModel`. It cannot add a body, move one, change
 * a mass, change the speed or advance the clock, because it has no reference with which to do so.
 * That is enforced by the signature rather than by discipline.
 */
@Composable
fun TutorialOverlay(
    persian: Boolean,
    onDismiss: () -> Unit
) {
    val c = LocalGravityColors.current
    val steps = TutorialContent.steps
    var index by remember { mutableIntStateOf(0) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    val last = index == steps.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("tutorial_overlay")
            // A single scrim that also swallows taps, so a stray press during the tutorial can
            // never reach the tabletop and nudge the experiment (§17).
            .background(Color.Black.copy(alpha = if (c.isDark) 0.42f else 0.24f))
            .clickableTag("tutorial_scrim") { }
    ) {
        // ---- skip, always reachable, always secondary (§13) ---------------------------------
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 10.dp, end = 14.dp)
                .clip(squircleShape(16.dp))
                .background(c.chrome)
                .border(1.dp, c.chromeBorder, squircleShape(16.dp))
                .semantics {
                    contentDescription =
                        if (persian) TutorialContent.SKIP_A11Y_FA else TutorialContent.SKIP_A11Y_EN
                }
                .clickableTag("tutorial_skip") { onDismiss() }
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Text(
                text = if (persian) TutorialContent.SKIP_FA else TutorialContent.SKIP_EN,
                color = c.onSurfaceDim,
                fontSize = 13.sp
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 18.dp)
                .clip(squircleShape(24.dp))
                .background(c.chrome)
                .border(1.dp, c.chromeBorder, squircleShape(24.dp))
                .padding(20.dp)
                .testTag("tutorial_card"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (step.gesture != TutorialGesture.NONE) {
                GestureDemo(step.gesture, c.accent)
            }

            Text(
                text = if (persian) step.titleFa else step.titleEn,
                color = c.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stepBody(step, persian),
                color = c.onSurface.copy(alpha = 0.86f),
                fontSize = 14.sp,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // ---- step indicator (§19). Laid out with Row, so Compose mirrors it in RTL
                // automatically and the "you are here" dot stays on the leading side.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tutorial_progress")
                ) {
                    for (i in steps.indices) {
                        Box(
                            modifier = Modifier
                                .size(if (i == index) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == index) c.accent
                                    else c.onSurfaceDim.copy(alpha = 0.35f)
                                )
                        )
                    }
                }

                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .clip(squircleShape(14.dp))
                            .clickableTag("tutorial_back") { index-- }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (persian) TutorialContent.BACK_FA else TutorialContent.BACK_EN,
                            color = c.onSurfaceDim,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }

                Box(
                    modifier = Modifier
                        .clip(squircleShape(16.dp))
                        .background(c.accent.copy(alpha = 0.18f))
                        .border(1.dp, c.accent.copy(alpha = 0.55f), squircleShape(16.dp))
                        .clickableTag(if (last) "tutorial_finish" else "tutorial_next") {
                            if (last) onDismiss() else index++
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = when {
                            last && persian -> TutorialContent.FINISH_FA
                            last -> TutorialContent.FINISH_EN
                            persian -> TutorialContent.NEXT_FA
                            else -> TutorialContent.NEXT_EN
                        },
                        color = c.accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * The speed step quotes the real ladder.
 *
 * [EngineConstants.SPEED_LABELS] is the single source of truth, so this sentence cannot drift out
 * of sync with the engine the way a hard-coded "1x, 10x, 100x" would.
 */
private fun stepBody(step: TutorialStep, persian: Boolean): String {
    val base = if (persian) step.bodyFa else step.bodyEn
    if (step.id != "time") return base
    val raw = EngineConstants.SPEED_LABELS.joinToString(" · ")
    val rungs = if (persian) PersianDigits.convert(raw.replace("x", "×")) else raw
    return "$base\n$rungs"
}

/**
 * A small, cheap finger animation.
 *
 * One `rememberInfiniteTransition` driving one float, drawn as two circles on a `Canvas`. No
 * per-frame allocation, no image assets, and nothing that touches the simulation clock.
 */
@Composable
private fun GestureDemo(gesture: TutorialGesture, tint: Color) {
    val transition = rememberInfiniteTransition(label = "tutorial_gesture")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tutorial_gesture_phase"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(squircleShape(14.dp))
            .background(tint.copy(alpha = 0.06f))
            .testTag("tutorial_gesture_demo")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cy = size.height * 0.5f
            val cx = size.width * 0.5f
            val r = 9.dp.toPx()
            val ease = t * t * (3f - 2f * t)

            when (gesture) {
                TutorialGesture.PINCH -> {
                    val spread = 14.dp.toPx() + ease * 36.dp.toPx()
                    drawCircle(tint.copy(alpha = 0.75f), r, Offset(cx - spread, cy))
                    drawCircle(tint.copy(alpha = 0.75f), r, Offset(cx + spread, cy))
                    drawLine(
                        color = tint.copy(alpha = 0.30f),
                        start = Offset(cx - spread, cy),
                        end = Offset(cx + spread, cy),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }

                TutorialGesture.DRAG -> {
                    val travel = 44.dp.toPx()
                    val x = cx - travel * 0.5f + ease * travel
                    drawLine(
                        color = tint.copy(alpha = 0.25f),
                        start = Offset(cx - travel * 0.5f, cy),
                        end = Offset(cx + travel * 0.5f, cy),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawCircle(tint.copy(alpha = 0.75f), r, Offset(x, cy))
                }

                else -> {
                    // Tap: a contact dot with one expanding, fading ring.
                    drawCircle(tint.copy(alpha = 0.75f), r, Offset(cx, cy))
                    drawCircle(
                        color = tint.copy(alpha = 0.35f * (1f - ease)),
                        radius = r + ease * 16.dp.toPx(),
                        center = Offset(cx, cy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 1.5.dp.toPx()
                        )
                    )
                }
            }
        }
    }
}
