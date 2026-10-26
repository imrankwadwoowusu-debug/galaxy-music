package com.galaxy.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

fun Modifier.appleLiquidGlass(
    cornerRadius: Dp = 28.dp,
    intensity: Float = 0.15f
): Modifier = this
    .shadow(
        elevation = 16.dp,
        shape = RoundedCornerShape(cornerRadius),
        ambientColor = Color.Black.copy(alpha = 0.4f),
        spotColor = Color.White.copy(alpha = 0.1f)
    )
    .background(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = intensity + 0.08f),
                Color.White.copy(alpha = intensity)
            )
        ),
        shape = RoundedCornerShape(cornerRadius)
    )
    .border(
        width = 1.2.dp,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.05f)
            )
        ),
        shape = RoundedCornerShape(cornerRadius)
    )
