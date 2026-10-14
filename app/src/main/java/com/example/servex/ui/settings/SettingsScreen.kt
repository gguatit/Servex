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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
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

        NumberRow(stringResource(R.string.field_poll), trimNumber(s.refreshIntervalS)) {
            it.toDoubleOrNull()?.let(viewModel::setRefresh)
        }
        NumberRow(stringResource(R.string.field_warning), trimNumber(s.warningPct)) {
            it.toDoubleOrNull()?.let(viewModel::setWarning)
        }
        NumberRow(stringResource(R.string.field_critical), trimNumber(s.criticalPct)) {
            it.toDoubleOrNull()?.let(viewModel::setCritical)
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

/** 5.0 -> "5", 0.5 -> "0.5" */
private fun trimNumber(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

/**
 * Keeps the typed text locally so partial input ("0.", "") is not replaced by the
 * stored value; the caller commits only when the text parses to a number.
 */
@Composable
private fun NumberRow(label: String, initial: String, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = ServexColors.onSurface)
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                text = new
                onCommit(new)
            },
            modifier = Modifier.width(120.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = ServexColors.onSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
