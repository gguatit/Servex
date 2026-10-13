package com.example.servex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.servex.R
import com.example.servex.ui.theme.ServexColors

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_action_back), contentDescription = stringResource(R.string.action_back), tint = ServexColors.onSurfaceVariant)
            }
            Text(stringResource(R.string.title_settings), style = MaterialTheme.typography.titleLarge, color = ServexColors.onSurface)
        }
        SettingRow(stringResource(R.string.field_poll), s.refreshIntervalS.toString()) { viewModel.setRefresh(it.toIntOrNull() ?: 5) }
        SettingRow(stringResource(R.string.field_warning), s.warningPct.toInt().toString()) { viewModel.setWarning(it.toDoubleOrNull() ?: 75.0) }
        SettingRow(stringResource(R.string.field_critical), s.criticalPct.toInt().toString()) { viewModel.setCritical(it.toDoubleOrNull() ?: 90.0) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(R.string.field_alerts), modifier = Modifier.weight(1f), color = ServexColors.onSurface)
            Switch(checked = s.alertsEnabled, onCheckedChange = viewModel::setAlerts)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.about_license), style = MaterialTheme.typography.labelSmall, color = ServexColors.onSurfaceVariant)
    }
}

@Composable
private fun SettingRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = ServexColors.onSurface)
        OutlinedTextField(value, onChange, modifier = Modifier.width(120.dp), singleLine = true)
    }
}
