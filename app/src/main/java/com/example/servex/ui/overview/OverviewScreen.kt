package com.example.servex.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.servex.R
import com.example.servex.domain.ServerStatus
import com.example.servex.ui.components.ServerCard
import com.example.servex.ui.statusStyle
import com.example.servex.ui.theme.ServexColors

@Composable
private fun SummaryChip(status: ServerStatus, count: Int, labelRes: Int) {
    val style = statusStyle(status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(style.iconRes), contentDescription = null,
            tint = style.color, modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("$count ${stringResource(labelRes)}", style = MaterialTheme.typography.labelSmall, color = style.color)
    }
}

@Composable
private fun DemoBadge(onClick: () -> Unit) {
    Text(
        stringResource(R.string.demo_badge),
        style = MaterialTheme.typography.labelSmall,
        color = ServexColors.primary,
        modifier = Modifier
            .background(ServexColors.primary.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun OverviewScreen(
    viewModel: OverviewViewModel,
    onOpenServer: (String) -> Unit,
    onEditServer: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ServerRow?>(null) }
    var deleteFor by remember { mutableStateOf<ServerRow?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = ServexColors.onSurface)
            if (state.demoMode) {
                Spacer(Modifier.width(8.dp))
                DemoBadge(onClick = { viewModel.setDemo(false) })
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(painterResource(R.drawable.ic_action_refresh), contentDescription = stringResource(R.string.action_refresh), tint = ServexColors.onSurfaceVariant)
            }
            IconButton(onClick = onAddServer) {
                Icon(painterResource(R.drawable.ic_action_add), contentDescription = stringResource(R.string.action_add), tint = ServexColors.onSurfaceVariant)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(painterResource(R.drawable.ic_action_settings), contentDescription = stringResource(R.string.action_settings), tint = ServexColors.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            SummaryChip(ServerStatus.NORMAL, state.summary.normal, R.string.status_normal)
            SummaryChip(ServerStatus.WARNING, state.summary.warning, R.string.status_warning)
            SummaryChip(ServerStatus.ERROR, state.summary.error, R.string.status_error)
            SummaryChip(ServerStatus.OFFLINE, state.summary.offline, R.string.status_offline)
        }
        Spacer(Modifier.height(8.dp))
        if (state.rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.no_servers),
                    style = MaterialTheme.typography.bodyMedium, color = ServexColors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { viewModel.setDemo(true) }) {
                    Text(stringResource(R.string.demo_try))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.demo_note),
                    style = MaterialTheme.typography.labelSmall, color = ServexColors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.rows, key = { it.server.id }) { row ->
                    ServerCard(
                        name = row.server.name,
                        subtitle = row.server.baseUrl,
                        status = row.status,
                        spark = row.spark,
                        onClick = { onOpenServer(row.server.id) },
                        onLongClick = { menuFor = row },
                    )
                }
            }
        }
    }

    menuFor?.let { row ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(row.server.name) },
            text = { Text(row.server.baseUrl) },
            confirmButton = {
                TextButton(onClick = {
                    menuFor = null
                    onOpenServer(row.server.id)
                }) { Text(stringResource(R.string.action_open)) }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.demoMode) {
                        Text(
                            stringResource(R.string.demo_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = ServexColors.onSurfaceVariant,
                        )
                    } else {
                        TextButton(onClick = {
                            menuFor = null
                            onEditServer(row.server.id)
                        }) { Text(stringResource(R.string.action_edit)) }
                        TextButton(onClick = {
                            menuFor = null
                            deleteFor = row
                        }) { Text(stringResource(R.string.action_delete), color = ServexColors.error) }
                    }
                }
            },
        )
    }

    deleteFor?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.confirm_delete, row.server.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(row.server)
                    deleteFor = null
                }) { Text(stringResource(R.string.action_delete), color = ServexColors.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
