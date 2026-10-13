package com.example.servex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.servex.R
import com.example.servex.domain.ServerStatus
import com.example.servex.ui.statusStyle

@Composable
fun StatusChip(status: ServerStatus, modifier: Modifier = Modifier) {
    val style = statusStyle(status)
    val labelRes = when (style.labelKey) {
        "warning" -> R.string.status_warning
        "error" -> R.string.status_error
        "offline" -> R.string.status_offline
        else -> R.string.status_normal
    }
    Row(
        modifier = modifier
            .background(style.color.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(style.iconRes),
            contentDescription = null,
            tint = style.color,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(labelRes), color = style.color, style = MaterialTheme.typography.labelSmall)
    }
}
