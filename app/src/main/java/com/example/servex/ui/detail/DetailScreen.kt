package com.example.servex.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.servex.R
import com.example.servex.ui.charts.LineChart
import com.example.servex.ui.components.BigMetric
import com.example.servex.ui.components.MetricRow
import com.example.servex.ui.components.SectionHeader
import com.example.servex.ui.components.StatusChip
import com.example.servex.ui.theme.ServexColors

@Composable
fun DetailScreen(viewModel: DetailViewModel, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val m = state.metrics
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_action_back), contentDescription = stringResource(R.string.action_back), tint = ServexColors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.server?.name.orEmpty(), style = MaterialTheme.typography.titleLarge,
                    color = ServexColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${state.server?.baseUrl.orEmpty()}  ${m?.os.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall, color = ServexColors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            StatusChip(state.status)
            IconButton(onClick = { state.server?.id?.let(onEdit) }) {
                Icon(painterResource(R.drawable.ic_action_edit), contentDescription = stringResource(R.string.action_edit), tint = ServexColors.onSurfaceVariant)
            }
        }
        state.error?.let {
            Text(it, color = ServexColors.error, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BigMetric(R.string.metric_cpu, "${m?.cpu?.usagePct?.toInt() ?: 0}%", R.drawable.ic_metric_cpu)
            BigMetric(R.string.metric_memory, "${m?.memory?.pct?.toInt() ?: 0}%", R.drawable.ic_metric_memory)
            BigMetric(R.string.metric_disk, "${m?.disks?.maxOfOrNull { it.pct }?.toInt() ?: 0}%", R.drawable.ic_metric_disk)
        }
        Spacer(Modifier.height(16.dp))
        SectionHeader(R.string.section_cpu)
        LineChart(state.history["cpu"].orEmpty(), ServexColors.primary)
        Spacer(Modifier.height(8.dp))
        SectionHeader(R.string.metric_memory)
        LineChart(state.history["memory"].orEmpty(), ServexColors.success)
        Spacer(Modifier.height(8.dp))
        SectionHeader(R.string.metric_network)
        LineChart(state.history["network"].orEmpty(), ServexColors.warning)
        Spacer(Modifier.height(16.dp))
        SectionHeader(R.string.section_disks)
        m?.disks?.forEach { d ->
            MetricRow(R.drawable.ic_metric_disk, R.string.metric_disk, "${d.mount}  ${d.pct.toInt()}%")
        }
    }
}
