package com.nearby.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun RadarAnimation(
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    isActive: Boolean = true,
    ringCount: Int = 3
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    val infiniteTransition = rememberInfiniteTransition(label = "radar")

    val animations = (0 until ringCount).map { index ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 2000,
                    delayMillis = index * (2000 / ringCount),
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "ring_$index"
        )
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = this.size.width / 2
            val centerY = this.size.height / 2
            val maxRadius = minOf(centerX, centerY)

            if (isActive) {
                animations.forEach { animation ->
                    val progress by animation
                    val radius = maxRadius * progress
                    val alpha = 1f - progress

                    drawCircle(
                        color = primaryColor.copy(alpha = alpha * 0.6f),
                        radius = radius,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // Center dot
            drawCircle(
                color = primaryColor,
                radius = 8.dp.toPx(),
                center = Offset(centerX, centerY)
            )
        }
    }
}
