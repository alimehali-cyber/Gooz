package com.alijafari.red.astronomy.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * RED Design System - Surface & Material Primitives
 * Built on modern Apple-inspired principles:
 * - Visual restraint (no heavy glows, excessive gradients, or noisy borders)
 * - Concentric geometry (consistent corner radius hierarchy)
 * - Content-first layout with high-contrast text and adaptive surfaces
 * - Subtle depth (hairline borders and very soft elevations)
 */

/**
 * Base RED Content Card
 */
@Composable
fun RedCard(
    modifier: Modifier = Modifier,
    shape: Shape = squircleShape(RedCornerRadius.lg),
    backgroundColor: Color = RedTheme.colors.surface,
    borderColor: Color = RedTheme.colors.border,
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(RedSpacing.lg),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // Both card primitives are liquid glass. They render through the same fallback path as every other
    // card when no backdrop is reachable, so a `RedCard` call site gets the glass material, the user's
    // tint and the contrast-safe ink without knowing any of it exists.
    LiquidGlassSurface(
        modifier = modifier,
        shape = shape,
        style = LiquidGlassDefaults.Card,
        fallbackColor = backgroundColor,
        fallbackBorder = BorderStroke(borderWidth, borderColor),
        fallbackShadowElevation = RedElevation.none,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * Elevated RED Card for primary highlights / floating panels
 */
@Composable
fun RedElevatedCard(
    modifier: Modifier = Modifier,
    shape: Shape = squircleShape(RedCornerRadius.xl),
    backgroundColor: Color = RedTheme.colors.surfaceElevated,
    borderColor: Color = RedTheme.colors.border,
    borderWidth: Dp = 1.dp,
    shadowElevation: Dp = RedElevation.card,
    contentPadding: PaddingValues = PaddingValues(RedSpacing.lg),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    LiquidGlassSurface(
        modifier = modifier,
        shape = shape,
        style = LiquidGlassDefaults.Card,
        fallbackColor = backgroundColor,
        fallbackBorder = BorderStroke(borderWidth, borderColor),
        fallbackShadowElevation = shadowElevation,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * Clean Section Header with strong visual hierarchy
 */
@Composable
fun RedSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badgeText: String? = null,
    actionText: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RedSpacing.xs, vertical = RedSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)
            ) {
                Text(
                    text = title,
                    style = RedTypographyTokens.sectionTitle,
                    color = RedTheme.colors.textPrimary
                )

                val badge = badgeText ?: actionText
                if (badge != null) {
                    RedBadge(text = badge)
                }
            }

            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = RedTypographyTokens.caption,
                    color = RedTheme.colors.textSecondary
                )
            }
        }

        if (action != null) {
            action()
        }
    }
}

/**
 * Refined Pill / Capsule Badge
 */
@Composable
fun RedBadge(
    text: String,
    modifier: Modifier = Modifier,
    isAccent: Boolean = false,
    backgroundColor: Color = if (isAccent) RedTheme.colors.accentRed.copy(alpha = 0.15f) else RedTheme.colors.surfaceGrouped,
    textColor: Color = if (isAccent) RedTheme.colors.accentRed else RedTheme.colors.textSecondary,
    borderColor: Color = if (isAccent) RedTheme.colors.accentRed.copy(alpha = 0.4f) else RedTheme.colors.border
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .border(1.dp, borderColor, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            ),
            color = textColor
        )
    }
}

/**
 * Semantic Status Pill Badge
 */
@Composable
fun RedStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    statusColor: Color = RedTheme.colors.statusSuccess,
    containerColor: Color = RedTheme.colors.statusSuccessContainer
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .border(1.dp, statusColor.copy(alpha = 0.3f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = statusColor
        )
    }
}

/**
 * Hairline Divider / Separator
 */
@Composable
fun RedHairlineDivider(
    modifier: Modifier = Modifier,
    color: Color = RedTheme.colors.separator,
    thickness: Dp = 1.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color)
    )
}

/**
 * Standard Concentric Action Control / Button
 */
@Composable
fun RedControl(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isPrimary: Boolean = false,
    enabled: Boolean = true
) {
    val bg = if (isPrimary) RedTheme.colors.accentRed else RedTheme.colors.surfaceElevated
    val fg = if (isPrimary) Color.White else RedTheme.colors.textPrimary
    val borderCol = if (isPrimary) Color.Transparent else RedTheme.colors.border

    Surface(
        modifier = modifier
            .height(RedControlHeight.regular)
            .clip(squircleShape(RedCornerRadius.md))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = if (isPrimary) Color.White.copy(alpha = 0.2f) else RedTheme.colors.accentRed),
                onClick = onClick
            ),
        shape = squircleShape(RedCornerRadius.md),
        color = bg,
        border = BorderStroke(1.dp, borderCol)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = RedSpacing.lg),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(RedIconSize.sm)
                )
                Spacer(modifier = Modifier.width(RedSpacing.sm))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = fg
            )
        }
    }
}

/**
 * Shared RED Slider
 * Modern Apple-inspired unified premium slider:
 * - Very thin, refined horizontal capsule track (4dp)
 * - Pure circular thumb (18dp idle -> 22dp pressed/dragged) with smooth spring animation
 * - Solid crisp surface with micro-border and subtle drop shadow
 * - High-contrast active track using RedTheme.colors.accentRed (or custom accentColor)
 * - Restrained subtle inactive track adapting to surface
 * - Seamless RTL support, accessibility, and zero bulky Material3 gaps
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    accentColor: Color = RedTheme.colors.accentRed,
    inactiveTrackColor: Color = RedTheme.colors.border.copy(alpha = if (RedTheme.colors.isDark) 0.35f else 0.45f),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isInteracting = isPressed || isDragged

    val thumbSize by animateDpAsState(
        targetValue = if (isInteracting && enabled) 22.dp else 18.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "redSliderThumbSize"
    )

    val thumbElevation by animateDpAsState(
        targetValue = if (isInteracting && enabled) 4.dp else 2.dp,
        animationSpec = tween(150),
        label = "redSliderThumbElevation"
    )

    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        colors = SliderDefaults.colors(
            thumbColor = Color.Transparent,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        ),
        thumb = {
            Box(
                modifier = Modifier
                    .size(thumbSize)
                    .shadow(
                        elevation = if (enabled) thumbElevation else 0.dp,
                        shape = CircleShape,
                        clip = false
                    )
                    .background(
                        color = if (enabled) {
                            if (RedTheme.colors.isDark) Color(0xFFFCFCFF) else Color.White
                        } else {
                            RedTheme.colors.border
                        },
                        shape = CircleShape
                    )
                    .border(
                        width = 0.5.dp,
                        color = if (enabled) {
                            if (RedTheme.colors.isDark) Color(0x33000000) else Color(0x1F000000)
                        } else {
                            RedTheme.colors.separator
                        },
                        shape = CircleShape
                    )
            )
        },
        track = { sliderState ->
            val activeColor = if (enabled) accentColor else RedTheme.colors.textTertiary.copy(alpha = 0.35f)
            val inactiveColor = if (enabled) inactiveTrackColor else RedTheme.colors.surfaceGrouped.copy(alpha = 0.25f)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            ) {
                val trackHeight = size.height
                val cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)

                // 1. Draw subtle inactive track (full width capsule)
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = cornerRadius
                )

                // 2. Compute active fraction and draw active capsule
                val startVal = sliderState.valueRange.start
                val endVal = sliderState.valueRange.endInclusive
                val totalSpan = endVal - startVal
                val fraction = if (totalSpan > 0f) {
                    ((sliderState.value - startVal) / totalSpan).coerceIn(0f, 1f)
                } else 0f

                val activeWidth = size.width * fraction
                if (activeWidth > 0f) {
                    if (isRtl) {
                        drawRoundRect(
                            color = activeColor,
                            topLeft = Offset(size.width - activeWidth, 0f),
                            size = Size(activeWidth, trackHeight),
                            cornerRadius = cornerRadius
                        )
                    } else {
                        drawRoundRect(
                            color = activeColor,
                            topLeft = Offset.Zero,
                            size = Size(activeWidth, trackHeight),
                            cornerRadius = cornerRadius
                        )
                    }
                }
            }
        }
    )
}

/**
 * Shared RED Range Slider
 * Modern Apple-inspired unified premium dual-thumb range slider
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    accentColor: Color = RedTheme.colors.accentRed,
    inactiveTrackColor: Color = RedTheme.colors.border.copy(alpha = if (RedTheme.colors.isDark) 0.35f else 0.45f),
    startInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    endInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val startPressed by startInteractionSource.collectIsPressedAsState()
    val startDragged by startInteractionSource.collectIsDraggedAsState()
    val isStartInteracting = startPressed || startDragged

    val endPressed by endInteractionSource.collectIsPressedAsState()
    val endDragged by endInteractionSource.collectIsDraggedAsState()
    val isEndInteracting = endPressed || endDragged

    val startThumbSize by animateDpAsState(
        targetValue = if (isStartInteracting && enabled) 22.dp else 18.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "redRangeSliderStartThumbSize"
    )

    val endThumbSize by animateDpAsState(
        targetValue = if (isEndInteracting && enabled) 22.dp else 18.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "redRangeSliderEndThumbSize"
    )

    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    RangeSlider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        startInteractionSource = startInteractionSource,
        endInteractionSource = endInteractionSource,
        colors = SliderDefaults.colors(
            thumbColor = Color.Transparent,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        ),
        startThumb = {
            Box(
                modifier = Modifier
                    .size(startThumbSize)
                    .shadow(
                        elevation = if (enabled) (if (isStartInteracting) 4.dp else 2.dp) else 0.dp,
                        shape = CircleShape,
                        clip = false
                    )
                    .background(
                        color = if (enabled) {
                            if (RedTheme.colors.isDark) Color(0xFFFCFCFF) else Color.White
                        } else {
                            RedTheme.colors.border
                        },
                        shape = CircleShape
                    )
                    .border(
                        width = 0.5.dp,
                        color = if (enabled) {
                            if (RedTheme.colors.isDark) Color(0x33000000) else Color(0x1F000000)
                        } else {
                            RedTheme.colors.separator
                        },
                        shape = CircleShape
                    )
            )
        },
        endThumb = {
            Box(
                modifier = Modifier
                    .size(endThumbSize)
                    .shadow(
                        elevation = if (enabled) (if (isEndInteracting) 4.dp else 2.dp) else 0.dp,
                        shape = CircleShape,
                        clip = false
                    )
                    .background(
                        color = if (enabled) {
                            if (RedTheme.colors.isDark) Color(0xFFFCFCFF) else Color.White
                        } else {
                            RedTheme.colors.border
                        },
                        shape = CircleShape
                    )
                    .border(
                        width = 0.5.dp,
                        color = if (enabled) {
                            if (RedTheme.colors.isDark) Color(0x33000000) else Color(0x1F000000)
                        } else {
                            RedTheme.colors.separator
                        },
                        shape = CircleShape
                    )
            )
        },
        track = { rangeSliderState ->
            val activeColor = if (enabled) accentColor else RedTheme.colors.textTertiary.copy(alpha = 0.35f)
            val inactiveColor = if (enabled) inactiveTrackColor else RedTheme.colors.surfaceGrouped.copy(alpha = 0.25f)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            ) {
                val trackHeight = size.height
                val cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)

                // 1. Inactive track
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = cornerRadius
                )

                // 2. Active span
                val startRange = rangeSliderState.valueRange.start
                val endRange = rangeSliderState.valueRange.endInclusive
                val totalSpan = endRange - startRange
                if (totalSpan > 0f) {
                    val startFraction = ((rangeSliderState.activeRangeStart - startRange) / totalSpan).coerceIn(0f, 1f)
                    val endFraction = ((rangeSliderState.activeRangeEnd - startRange) / totalSpan).coerceIn(0f, 1f)

                    val startX = if (isRtl) size.width * (1f - endFraction) else size.width * startFraction
                    val spanWidth = size.width * (endFraction - startFraction).coerceAtLeast(0f)

                    if (spanWidth > 0f) {
                        drawRoundRect(
                            color = activeColor,
                            topLeft = Offset(startX, 0f),
                            size = Size(spanWidth, trackHeight),
                            cornerRadius = cornerRadius
                        )
                    }
                }
            }
        }
    )
}

