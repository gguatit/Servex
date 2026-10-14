package com.example.servex.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.setAlerts(granted) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_action_back),
                    contentDescription = stringResource(R.string.action_back),
                    tint = ServexColors.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.title_settings),
                style = MaterialTheme.typography.titleLarge,
                color = ServexColors.onSurface,
            )
        }

        SettingRow(stringResource(R.string.field_poll), trimSeconds(s.refreshIntervalS)) {
            viewModel.setRefresh(it.toDoubleOrNull() ?: 5.0)
        }
        SettingRow(stringResource(R.string.field_warning), s.warningPct.toInt().toString()) {
            viewModel.setWarning(it.toDoubleOrNull() ?: 75.0)
        }
        SettingRow(stringResource(R.string.field_critical), s.criticalPct.toInt().toString()) {
            viewModel.setCritical(it.toDoubleOrNull() ?: 90.0)
        }

        SwitchRow(stringResource(R.string.field_alerts), s.alertsEnabled) { on ->
            if (on) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else viewModel.setAlerts(false)
        }

        SwitchRow(stringResource(R.string.field_demo), s.demoMode) { viewModel.setDemo(it) }
        if (s.demoMode) {
            Text(
                stringResource(R.string.demo_note),
                style = MaterialTheme.typography.labelSmall,
                color = ServexColors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.about_license),
            style = MaterialTheme.typography.labelSmall,
            color = ServexColors.onSurfaceVariant,
        )
    }
}

private fun trimSeconds(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()


@Composable
private fun SettingRow(label: String, value: String, onChange: (String) -> Unit) {    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = ServexColors.onSurface)
        OutlinedTextField(value, onChange, modifier = Modifier.width(120.dp), singleLine = true)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = ServexColors.onSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
