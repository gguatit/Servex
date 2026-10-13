package com.example.servex.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
fun EditServerScreen(viewModel: EditServerViewModel, onBack: () -> Unit) {
    val s by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_action_back), contentDescription = stringResource(R.string.action_back), tint = ServexColors.onSurfaceVariant)
            }
            Text(stringResource(R.string.title_edit_server), style = MaterialTheme.typography.titleLarge, color = ServexColors.onSurface)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = s.name, onValueChange = viewModel::onName,
            label = { Text(stringResource(R.string.field_name)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = s.baseUrl, onValueChange = viewModel::onUrl,
            label = { Text(stringResource(R.string.field_url)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = s.token, onValueChange = viewModel::onToken,
            label = { Text(stringResource(R.string.field_token)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = s.certFingerprint, onValueChange = viewModel::onFingerprint,
            label = { Text(stringResource(R.string.field_fingerprint)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = s.pollIntervalS.toString(),
            onValueChange = { viewModel.onPoll(it.toIntOrNull() ?: 5) },
            label = { Text(stringResource(R.string.field_poll)) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        s.errorKey?.let {
            Text(
                stringResource(if (it == "err_name") R.string.err_name else R.string.err_url),
                color = ServexColors.error, style = MaterialTheme.typography.labelSmall,
            )
        }
        s.testResult?.let {
            Text(it, color = ServexColors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.testConnection() }, shape = RoundedCornerShape(10.dp)) {
                Text(stringResource(R.string.action_test))
            }
            Button(onClick = { viewModel.save(onBack) }, enabled = !s.saving, shape = RoundedCornerShape(10.dp)) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
