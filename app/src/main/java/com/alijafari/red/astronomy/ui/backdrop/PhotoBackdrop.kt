package com.alijafari.red.astronomy.ui.backdrop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.zig.gravity.ui.ImmersiveScreenState

const val PHOTO_BACKDROP_TAG = "photo_backdrop"

/**
 * Full-window photograph backdrop. [ContentScale.Crop] fills every aspect ratio without letterboxing.
 * Drawables live in drawable-nodpi so density buckets do not downsample them.
 * Non-interactive and without semantics so taps pass through to content.
 */
@Composable
fun PhotoBackdrop(
    mode: AppBackdropMode,
    selectedTab: Int,
    modifier: Modifier = Modifier
) {
    val photoResId = mode.photoResId
    val visible = photoResId != null && LiveSkyPolicy.isBackdropVisible(
        enabled = true,
        selectedTab = selectedTab,
        immersiveActive = ImmersiveScreenState.active
    )
    if (!visible || photoResId == null) return

    Image(
        painter = painterResource(photoResId),
        contentDescription = null,
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
            .testTag(PHOTO_BACKDROP_TAG),
        contentScale = ContentScale.Crop
    )
}
