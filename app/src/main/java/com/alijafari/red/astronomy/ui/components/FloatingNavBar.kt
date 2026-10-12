package com.alijafari.red.astronomy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.R
import com.alijafari.red.astronomy.ui.theme.*
import com.kyant.backdrop.Backdrop

data class NavItem(
    val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val targetTabIndex: Int,
    val testTag: String
)

@Composable
fun FloatingBottomBar(
    backdrop: Backdrop? = null,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val navItems = listOf(
        NavItem(R.string.nav_home, Icons.Outlined.Home, Icons.Outlined.Home, 4, "nav_item_home"),
        NavItem(R.string.nav_arsky, Icons.Outlined.Explore, Icons.Outlined.Explore, 3, "nav_item_arsky"),
        NavItem(R.string.nav_satellites, Icons.Outlined.SatelliteAlt, Icons.Outlined.SatelliteAlt, 1, "nav_item_satellites"),
        NavItem(R.string.nav_lab, Icons.Outlined.Science, Icons.Outlined.Science, 0, "nav_item_lab")
    )

    val navShape = RoundedCornerShape(32.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = RedSpacing.lg, vertical = RedSpacing.sm)
            .testTag("main_bottom_navigation")
    ) {
        // Continuous native Liquid Glass floating bar surface across the entire bottom navigation
        LiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .testTag("main_bottom_navigation_glass_surface"),
            backdrop = backdrop,
            shape = navShape,
            style = LiquidGlassDefaults.NavigationBar,
            fallbackColor = RedTheme.colors.surfaceElevated,
            fallbackBorder = androidx.compose.foundation.BorderStroke(1.dp, RedTheme.colors.border),
            fallbackShadowElevation = RedElevation.floating,
            glassBorder = androidx.compose.foundation.BorderStroke(0.75.dp, RedTheme.colors.border.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = RedSpacing.xs, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                navItems.forEach { item ->
                    val isSelected = selectedTab == item.targetTabIndex
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    val itemScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.93f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = 450f),
                        label = "NavItemPressScale"
                    )

                    val activeColor by animateColorAsState(
                        targetValue = if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.textSecondary,
                        animationSpec = tween(durationMillis = 200),
                        label = "NavColor"
                    )

                    val activeProgress by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0f,
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
                        label = "NavActiveIndicatorProgress"
                    )

                    val pillShape = RoundedCornerShape(20.dp)

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .scale(itemScale)
                            .clip(pillShape)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) { onTabSelected(item.targetTabIndex) }
                            .padding(vertical = 4.dp)
                            .testTag(item.testTag)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = stringResource(item.titleRes),
                                tint = activeColor,
                                modifier = Modifier.size(RedIconSize.md)
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = stringResource(item.titleRes),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = activeColor,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}


