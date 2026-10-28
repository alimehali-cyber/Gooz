package com.alijafari.red.astronomy.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alijafari.red.astronomy.astro_engine.EclipseEngine
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.domain.UserLocation
import com.alijafari.red.astronomy.ui.theme.LiquidGlassDefaults
import com.alijafari.red.astronomy.ui.theme.LiquidGlassSurface
import com.alijafari.red.astronomy.ui.theme.RedCornerRadius
import com.alijafari.red.astronomy.ui.theme.RedElevation
import com.alijafari.red.astronomy.ui.theme.RedSpacing
import com.alijafari.red.astronomy.ui.theme.RedTheme
import com.alijafari.red.astronomy.ui.theme.RedTypographyTokens
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.alijafari.red.astronomy.util.toPersianDigits

/** Which family the window is listing. */
private enum class EclipseFamily { SOLAR, LUNAR }

/**
 * The Eclipses screen: one window, two families, three upcoming events each, and the full detail of any
 * one of them without leaving the window.
 *
 * Why a window rather than a tab: the Home card that opens it is a single tile, and the two families are
 * two different phenomena with different physics, different observing advice and different pictures.
 * They are tabs here, not a filter — switching tabs swaps the whole list, never mixes the two.
 *
 * ### What each tab shows
 *
 * The next **three** eclipses of that family, in time order, each resolved for the observer's own
 * location by [EclipseEngine.getNextEclipseSeries] (the same search Home already runs, passed in so the
 * window costs no ephemeris work of its own). Every row carries the eclipse's picture, its name, its
 * date and whether it is actually visible from where the user is standing — the last of which is the
 * only question anyone opens this screen to answer.
 *
 * ### Navigation
 *
 * Tapping a row pushes the full [EclipseDetailContent] inside this same window rather than opening a
 * second `Dialog`: a nested window would own the back gesture and dismiss the wrong layer. Back — the
 * arrow or the system gesture — returns to the list first, and only then closes the window.
 */
@Composable
fun EclipseWindow(
    solarEclipses: List<EclipseEngine.EclipseResult>,
    lunarEclipses: List<EclipseEngine.EclipseResult>,
    language: AppLanguage,
    userLocation: UserLocation,
    onDismiss: () -> Unit,
    initialFamily: EclipseEngine.EclipseType? = null
) {
    val isFa = language == AppLanguage.PERSIAN
    // Survives the detail/list swap, so coming back from an eclipse lands on the tab it came from.
    var family by rememberSaveable {
        mutableStateOf(
            when {
                initialFamily == null -> EclipseFamily.SOLAR
                initialFamily.isSolarFamily -> EclipseFamily.SOLAR
                else -> EclipseFamily.LUNAR
            }
        )
    }
    var opened by remember { mutableStateOf<EclipseEngine.EclipseResult?>(null) }

    val listed = if (family == EclipseFamily.SOLAR) solarEclipses else lunarEclipses

    BackHandler {
        if (opened != null) opened = null else onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RedTheme.colors.background)
                .testTag("eclipse_window")
        ) {
            AnimatedContent(
                targetState = opened,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "EclipseWindowPage"
            ) { eclipse ->
                if (eclipse == null) {
                    EclipseListPage(
                        isFa = isFa,
                        family = family,
                        onFamilyChange = { family = it },
                        solarCount = solarEclipses.size,
                        lunarCount = lunarEclipses.size,
                        listed = listed,
                        onOpen = { opened = it },
                        onClose = onDismiss
                    )
                } else {
                    EclipseDetailPage(
                        eclipse = eclipse,
                        language = language,
                        userLocation = userLocation,
                        onBack = { opened = null }
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// List
// ------------------------------------------------------------------------------------------------

@Composable
private fun EclipseListPage(
    isFa: Boolean,
    family: EclipseFamily,
    onFamilyChange: (EclipseFamily) -> Unit,
    solarCount: Int,
    lunarCount: Int,
    listed: List<EclipseEngine.EclipseResult>,
    onOpen: (EclipseEngine.EclipseResult) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Header: back to Home, the title, and the reason the screen exists.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = RedSpacing.xs, end = RedSpacing.lg, top = RedSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("eclipse_window_back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (isFa) "بازگشت" else "Back",
                    tint = RedTheme.colors.textPrimary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isFa) "گرفتگی‌ها" else "Eclipses",
                    style = RedTypographyTokens.sectionHeading,
                    color = RedTheme.colors.textPrimary
                )
                Text(
                    text = if (isFa) "سه گرفتگی بعدی، برای موقعیت شما"
                    else "The next three, for where you are",
                    style = RedTypographyTokens.caption.copy(fontSize = 11.sp),
                    color = RedTheme.colors.textSecondary
                )
            }
        }

        // Family tabs. Squircles, not pills: the app has one corner geometry and a tab is a container.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RedSpacing.lg, vertical = RedSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(RedSpacing.sm)
        ) {
            FamilyTab(
                selected = family == EclipseFamily.SOLAR,
                label = if (isFa) "خورشیدگرفتگی" else "Solar eclipses",
                count = solarCount,
                isFa = isFa,
                icon = Icons.Outlined.WbSunny,
                modifier = Modifier
                    .weight(1f)
                    .testTag("eclipse_tab_solar"),
                onClick = { onFamilyChange(EclipseFamily.SOLAR) }
            )
            FamilyTab(
                selected = family == EclipseFamily.LUNAR,
                label = if (isFa) "ماه‌گرفتگی" else "Lunar eclipses",
                count = lunarCount,
                isFa = isFa,
                icon = Icons.Outlined.NightsStay,
                modifier = Modifier
                    .weight(1f)
                    .testTag("eclipse_tab_lunar"),
                onClick = { onFamilyChange(EclipseFamily.LUNAR) }
            )
        }

        if (listed.isEmpty()) {
            EmptyFamily(
                isFa = isFa,
                isSolar = family == EclipseFamily.SOLAR,
                modifier = Modifier.padding(horizontal = RedSpacing.lg)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("eclipse_list_${family.name.lowercase()}"),
                contentPadding = PaddingValues(
                    start = RedSpacing.lg,
                    end = RedSpacing.lg,
                    top = RedSpacing.xs,
                    bottom = RedSpacing.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(RedSpacing.md)
            ) {
                items(listed, key = { it.event.id }) { eclipse ->
                    EclipseListRow(
                        eclipse = eclipse,
                        isFa = isFa,
                        onClick = { onOpen(eclipse) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FamilyTab(
    selected: Boolean,
    label: String,
    count: Int,
    isFa: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LiquidGlassSurface(
        modifier = modifier,
        shape = squircleShape(RedCornerRadius.lg),
        style = LiquidGlassDefaults.Pill,
        clearGlass = selected,
        fallbackColor = if (selected) RedTheme.colors.accentRed else RedTheme.colors.surfaceElevated,
        fallbackBorder = BorderStroke(
            0.75.dp,
            if (selected) RedTheme.colors.accentRed else RedTheme.colors.border
        ),
        fallbackShadowElevation = RedElevation.none,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = RedSpacing.md, vertical = RedSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RedSpacing.xs)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else RedTheme.colors.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = RedTypographyTokens.caption.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.sp
                ),
                color = if (selected) Color.White else RedTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = when {
                    count <= 0 -> "—"
                    isFa -> count.toString().toPersianDigits()
                    else -> count.toString()
                },
                style = RedTypographyTokens.caption.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                ),
                color = if (selected) Color.White.copy(alpha = 0.85f) else RedTheme.colors.textTertiary
            )
        }
    }
}

@Composable
private fun EclipseListRow(
    eclipse: EclipseEngine.EclipseResult,
    isFa: Boolean,
    onClick: () -> Unit
) {
    val name = if (isFa) eclipse.localNameFa else eclipse.localNameEn
    val date = if (isFa) eclipse.formattedDateFa else eclipse.formattedDateEn
    val visibility = if (isFa) eclipse.localVisibilityTextFa else eclipse.localVisibilityTextEn
    val days = eclipse.daysRemaining
    val countdown = when {
        days <= 0 -> if (isFa) "امروز" else "Today"
        days == 1 -> if (isFa) "فردا" else "Tomorrow"
        isFa -> "${days.toString().toPersianDigits()} روز دیگر"
        else -> "in $days days"
    }
    val visibleColor = if (eclipse.isLocallyVisible) RedTheme.colors.statusSuccess else RedTheme.colors.statusWarning

    LiquidGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("eclipse_row_${eclipse.event.id}"),
        shape = squircleShape(RedCornerRadius.window),
        style = LiquidGlassDefaults.Card,
        fallbackColor = RedTheme.colors.surfaceElevated,
        fallbackBorder = BorderStroke(0.75.dp, RedTheme.colors.border),
        fallbackShadowElevation = RedElevation.none,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(RedSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RedSpacing.md)
        ) {
            // The picture of *this* eclipse, not of eclipses in general.
            EclipseThumbnail(
                type = eclipse.event.type,
                size = 72.dp,
                modifier = Modifier.testTag("eclipse_row_render_${eclipse.event.id}")
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = name,
                    style = RedTypographyTokens.bodyPrimary.copy(fontWeight = FontWeight.SemiBold),
                    color = RedTheme.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$date  ·  $countdown",
                    style = RedTypographyTokens.caption.copy(fontWeight = FontWeight.Medium),
                    color = RedTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = visibility,
                    style = RedTypographyTokens.caption.copy(fontSize = 11.sp),
                    color = visibleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = RedTheme.colors.textTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun EmptyFamily(isFa: Boolean, isSolar: Boolean, modifier: Modifier = Modifier) {
    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = squircleShape(RedCornerRadius.window),
        fallbackColor = RedTheme.colors.surfaceElevated,
        fallbackBorder = BorderStroke(0.75.dp, RedTheme.colors.border),
        style = LiquidGlassDefaults.Card,
        fallbackShadowElevation = RedElevation.none
    ) {
        Text(
            text = when {
                isFa && isSolar -> "خورشیدگرفتگی بعدی در دسترس نیست."
                isFa -> "ماه‌گرفتگی بعدی در دسترس نیست."
                isSolar -> "No upcoming solar eclipse is available."
                else -> "No upcoming lunar eclipse is available."
            },
            style = RedTypographyTokens.bodySecondary,
            color = RedTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(RedSpacing.xl)
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Detail
// ------------------------------------------------------------------------------------------------

@Composable
private fun EclipseDetailPage(
    eclipse: EclipseEngine.EclipseResult,
    language: AppLanguage,
    userLocation: UserLocation,
    onBack: () -> Unit
) {
    val isFa = language == AppLanguage.PERSIAN
    val detailed = remember(eclipse, userLocation) {
        EclipseEngine.computeDetailedInfo(
            result = eclipse,
            userLatDeg = userLocation.latitude,
            userLonDeg = userLocation.longitude,
            timezoneId = userLocation.timezoneId
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = RedSpacing.xs, end = RedSpacing.lg, top = RedSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("eclipse_detail_back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (isFa) "بازگشت به فهرست" else "Back to the list",
                    tint = RedTheme.colors.textPrimary
                )
            }
            Text(
                text = if (isFa) "جزئیات گرفتگی" else "Eclipse details",
                style = RedTypographyTokens.bodyPrimary.copy(fontWeight = FontWeight.SemiBold),
                color = RedTheme.colors.textPrimary
            )
        }

        // The detail card fills the rest of the window and scrolls on its own; it is the same card the
        // feed opens, so an eclipse reads identically however it was reached.
        EclipseDetailContent(
            detailedInfo = detailed,
            language = language,
            onDismiss = onBack,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = RedSpacing.lg,
                    end = RedSpacing.lg,
                    top = RedSpacing.xs,
                    bottom = RedSpacing.md
                ),
            showCloseButton = false
        )
    }
}

/** True for the solar family, both at the eclipse-type level and for the tab's default. */
private val EclipseEngine.EclipseType.isSolarFamily: Boolean
    get() = when (this) {
        EclipseEngine.EclipseType.TOTAL_SOLAR,
        EclipseEngine.EclipseType.ANNULAR_SOLAR,
        EclipseEngine.EclipseType.HYBRID_SOLAR,
        EclipseEngine.EclipseType.PARTIAL_SOLAR,
        EclipseEngine.EclipseType.NONE_SOLAR -> true

        else -> false
    }

/**
 * A small tile used where a screen needs the eclipse's picture without its words.
 *
 * There is deliberately no chip or rounded frame behind it: [EclipseRender] already fades its plate out
 * to a soft circle, so the eclipse sits on the row's own glass exactly as it sits on the Home tile. A
 * second rounded rectangle here would only add a rectangle whose corners the picture does not share.
 */
@Composable
fun EclipseThumbnail(
    type: EclipseEngine.EclipseType,
    size: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        EclipseRender(type = type)
    }
}
