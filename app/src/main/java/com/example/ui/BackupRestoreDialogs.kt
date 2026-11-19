package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ui.theme.LocalLuxyColors

private const val MIN_PASSPHRASE_LENGTH = 6

/** Encrypted-backup export dialog with a masked, length- and match-validated passphrase. */
@Composable
fun ExportBackupDialog(
    onConfirm: (passphrase: String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }

    val tooShort = passphrase.length < MIN_PASSPHRASE_LENGTH
    val mismatch = passphrase != confirmation
    val fieldColors = passphraseFieldColors()

    BackupDialogFrame(
        title = stringResource(R.string.backup_export_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.backup_export_action),
        onConfirm = {
            attempted = true
            if (!tooShort && !mismatch) onConfirm(passphrase)
        }
    ) {
        Text(
            text = stringResource(R.string.backup_export_desc),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.backup_passphrase_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = attempted && tooShort,
            colors = fieldColors,
            supportingText = {
                Text(
                    text = stringResource(
                        if (attempted && tooShort) R.string.backup_passphrase_short
                        else R.string.backup_passphrase_hint
                    ),
                    color = if (attempted && tooShort) colors.error else colors.onSurfaceVariant
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            )
        )
        OutlinedTextField(
            value = confirmation,
            onValueChange = { confirmation = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.backup_passphrase_confirm_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = attempted && mismatch,
            colors = fieldColors,
            supportingText = if (attempted && mismatch) {
                { Text(stringResource(R.string.backup_passphrase_mismatch), color = colors.error) }
            } else null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            )
        )
    }
}

/** Encrypted-backup restore dialog. The passphrase is masked and must be at least six characters. */
@Composable
fun RestoreBackupDialog(
    onConfirm: (passphrase: String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalLuxyColors.current
    var passphrase by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val tooShort = passphrase.length < MIN_PASSPHRASE_LENGTH
    val fieldColors = passphraseFieldColors()

    BackupDialogFrame(
        title = stringResource(R.string.backup_restore_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.backup_restore_action),
        onConfirm = {
            attempted = true
            if (!tooShort) onConfirm(passphrase)
        }
    ) {
        Text(
            text = stringResource(R.string.backup_restore_desc),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.backup_passphrase_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = attempted && tooShort,
            colors = fieldColors,
            supportingText = {
                Text(
                    text = stringResource(
                        if (attempted && tooShort) R.string.backup_passphrase_short
                        else R.string.backup_passphrase_hint
                    ),
                    color = if (attempted && tooShort) colors.error else colors.onSurfaceVariant
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            )
        )
    }
}

@Composable
private fun BackupDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    val colors = LocalLuxyColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = colors.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onBackground
                    )
                    content()
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = stringResource(R.string.sub_cancel),
                                color = colors.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onConfirm) {
                            Text(
                                text = confirmLabel,
                                color = colors.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun passphraseFieldColors() = LocalLuxyColors.current.let { colors ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.outline,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.onSurfaceVariant,
        cursorColor = colors.primary,
        errorBorderColor = colors.error,
        errorLabelColor = colors.error,
        errorCursorColor = colors.error
    )
}
