package com.example.servex.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.servex.R

@Composable
fun ActionDialog(
    serverName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Map<String, String>) -> Unit,
) {
    var service by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_restart_service)) },
        text = {
            Column {
                Text(stringResource(R.string.confirm_restart, serverName))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = service,
                    onValueChange = { service = it },
                    label = { Text(stringResource(R.string.field_service)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm("service_restart", mapOf("service" to service))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
