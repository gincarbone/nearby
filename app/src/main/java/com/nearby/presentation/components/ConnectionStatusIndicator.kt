package com.nearby.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nearby.data.local.entity.ConnectionState
import com.nearby.presentation.theme.StatusConnected
import com.nearby.presentation.theme.StatusConnecting
import com.nearby.presentation.theme.StatusDiscovered
import com.nearby.presentation.theme.StatusDisconnected
import com.nearby.presentation.theme.StatusUnknown

@Composable
fun ConnectionStatusIndicator(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp
) {
    val color = when (connectionState) {
        ConnectionState.CONNECTED -> StatusConnected
        ConnectionState.CONNECTING -> StatusConnecting
        ConnectionState.DISCOVERED -> StatusDiscovered
        ConnectionState.DISCONNECTED -> StatusDisconnected
        ConnectionState.UNKNOWN -> StatusUnknown
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}
