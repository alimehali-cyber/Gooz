package com.alijafari.red.astronomy.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.alijafari.red.astronomy.R
import com.alijafari.red.astronomy.ui.theme.squircleShape

@Composable
fun SafeAppLogo(
    modifier: Modifier = Modifier,
    @DrawableRes logoResId: Int = R.drawable.red_app_logo_display,
    fallbackIcon: ImageVector = Icons.Default.Stars,
    cornerRadius: Dp = 10.dp
) {
    val context = LocalContext.current
    val isLoadable = remember(logoResId, context) {
        try {
            val drawable = ContextCompat.getDrawable(context, logoResId)
            drawable != null
        } catch (e: Throwable) {
            false
        }
    }

    if (isLoadable) {
        Image(
            painter = painterResource(id = logoResId),
            contentDescription = "ZIG App Logo",
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(squircleShape(cornerRadius))
        )
    } else {
        Surface(
            modifier = modifier.clip(squircleShape(cornerRadius)),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = "App Logo Fallback",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
