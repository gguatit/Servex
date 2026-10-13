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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.servex.R
import com.example.servex.ui.charts.ChartUnit
import com.example.servex.ui.charts.LineChart
import com.example.servex.ui.components.ActionButton
import com.example.servex.ui.components.BigMetric
import com.example.servex.ui.components.MetricRow
import com.example.servex.ui.components.SectionHeader
import com.example.servex.ui.components.StatusChip
import com.example.servex.ui.format.formatBytes
import com.example.servex.ui.format.formatRate
import com.example.servex.ui.format.formatTimestamp
import com.example.servex.ui.theme.ServexColors
import java.util.Locale

@Composable
fun DetailScreen(viewModel: DetailViewModel, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val m = state.metrics
    var dialogFor by remember { mutableStateOf<String?>(null) }
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
        state.actionResult?.let {
            Text(it, color = ServexColors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
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
        LineChart(state.history["network"].orEmpty(), ServexColors.warning, unit = ChartUnit.RATE)
        Spacer(Modifier.height(16.dp))
        SectionHeader(R.string.section_disks)
        m?.disks?.forEach { d ->
            MetricRow(R.drawable.ic_metric_disk, R.string.metric_disk, "${d.mount}  ${d.pct.toInt()}%")
        }
        val memInfo = m?.memory
        if (memInfo != null && (memInfo.swapTotal != null || memInfo.cached != null || memInfo.buffers != null)) {
            Spacer(Modifier.height(16.dp))
            SectionHeader(R.string.section_memory_detail)
            memInfo.swapTotal?.let { total ->
                val used = memInfo.swapUsed ?: 0L
                val pctText = memInfo.swapPct?.let { String.format(Locale.US, "%.1f%%", it) }
                    ?: "${(if (total > 0) used * 100.0 / total else 0.0).toInt()}%"
                MetricRow(R.drawable.ic_metric_memory, R.string.metric_swap, "${formatBytes(used)} / ${formatBytes(total)}  $pctText")
            }
            memInfo.cached?.let { MetricRow(R.drawable.ic_metric_memory, R.string.metric_cached, formatBytes(it)) }
            memInfo.buffers?.let { MetricRow(R.drawable.ic_metric_memory, R.string.metric_buffers, formatBytes(it)) }
        }
        m?.diskIo?.let { io ->
            Spacer(Modifier.height(16.dp))
            SectionHeader(R.string.section_disk_io)
            io.readBps?.let { MetricRow(R.drawable.ic_metric_disk, R.string.metric_read, formatRate(it)) }
            io.writeBps?.let { MetricRow(R.drawable.ic_metric_disk, R.string.metric_write, formatRate(it)) }
        }
        val topProcs = m?.processes?.byCpu.orEmpty().take(5)
        if (topProcs.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionHeader(R.string.section_processes)
            topProcs.forEach { p ->
                val cpu = p.cpuPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-"
                val memPct = p.memPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-"
                MetricRow(R.drawable.ic_metric_process, p.name ?: (p.pid?.toString() ?: ""), "$cpu  $memPct")
            }
        }
        m?.services?.let { svc ->
            Spacer(Modifier.height(16.dp))
            SectionHeader(R.string.section_services)
            if (svc.failed.isEmpty()) {
                MetricRow(R.drawable.ic_metric_service, stringResource(R.string.services_none), "")
            } else {
                svc.failed.forEach { MetricRow(R.drawable.ic_metric_service, it, "") }
            }
        }
        val hasSystem = m != null && (m.kernel != null || m.arch != null || m.users != null || m.bootTime != null)
        if (hasSystem) {
            Spacer(Modifier.height(16.dp))
            SectionHeader(R.string.section_system)
            m.kernel?.let { MetricRow(R.drawable.ic_metric_cpu, R.string.metric_kernel, it) }
            m.arch?.let { MetricRow(R.drawable.ic_metric_cpu, R.string.metric_arch, it) }
            m.users?.let { MetricRow(R.drawable.ic_metric_memory, R.string.metric_users, it.toString()) }
            m.bootTime?.let { MetricRow(R.drawable.ic_metric_cpu, R.string.metric_boot_time, formatTimestamp(it)) }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                iconRes = R.drawable.ic_action_refresh,
                textRes = R.string.action_restart_service,
                onClick = { state.server?.let { dialogFor = it.name } },
            )
        }
        dialogFor?.let { name ->
            ActionDialog(
                serverName = name,
                onDismiss = { dialogFor = null },
                onConfirm = { action, args ->
                    state.server?.let { viewModel.runAction(it, action, args) }
                },
            )
        }
    }
}
