package com.example.servex.ui

import androidx.compose.ui.graphics.Color
import com.example.servex.R
import com.example.servex.domain.ServerStatus
import com.example.servex.ui.theme.ServexColors

data class StatusStyle(val color: Color, val iconRes: Int, val labelKey: String)

fun statusStyle(status: ServerStatus): StatusStyle = when (status) {
    ServerStatus.NORMAL -> StatusStyle(ServexColors.success, R.drawable.ic_status_normal, "normal")
    ServerStatus.WARNING -> StatusStyle(ServexColors.warning, R.drawable.ic_status_warning, "warning")
    ServerStatus.ERROR -> StatusStyle(ServexColors.error, R.drawable.ic_status_error, "error")
    ServerStatus.OFFLINE -> StatusStyle(ServexColors.offline, R.drawable.ic_status_offline, "offline")
}
