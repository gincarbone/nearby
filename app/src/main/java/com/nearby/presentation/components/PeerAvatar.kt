package com.nearby.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

@Composable
fun PeerAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val colors = generateAvatarColors(name)
    val initials = name.take(2).uppercase()

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(colors)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = when {
                size >= 48.dp -> MaterialTheme.typography.titleMedium
                size >= 32.dp -> MaterialTheme.typography.bodyMedium
                else -> MaterialTheme.typography.bodySmall
            },
            color = Color.White
        )
    }
}

private fun generateAvatarColors(name: String): List<Color> {
    val hash = name.hashCode().absoluteValue
    val hue1 = (hash % 360).toFloat()
    val hue2 = ((hash / 360) % 360).toFloat()

    return listOf(
        Color.hsl(hue1, 0.7f, 0.5f),
        Color.hsl(hue2, 0.6f, 0.4f)
    )
}
