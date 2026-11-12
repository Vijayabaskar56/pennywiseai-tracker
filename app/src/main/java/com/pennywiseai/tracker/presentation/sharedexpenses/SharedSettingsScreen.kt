package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.components.cards.SectionHeaderV2
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.utils.AppDates
import java.time.Instant
import java.time.ZoneId

/** Display name, server address (switching wipes the local copy, so it asks first), Sync now and the last sync. */
@Composable
fun SharedSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SharedSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = state.form
    val snackbar = remember { SnackbarHostState() }
    val message = form.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            viewModel.clearMessage()
            snackbar.showSnackbar(message)
        }
    }

    var name by rememberSaveable(state.displayName) { mutableStateOf(state.displayName) }
    var address by rememberSaveable(state.serverAddress) { mutableStateOf(state.serverAddress) }
    val saveName = { if (!form.busy && name.isNotBlank()) viewModel.saveDisplayName(name) }
    val saveAddress = { if (!form.busy && address.isNotBlank()) viewModel.requestServerAddress(address) }

    PennyWiseScaffold(
        title = stringResource(R.string.shared_settings_title),
        navigationIcon = { SharedBackButton(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimensions.Padding.content, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SectionHeaderV2(title = stringResource(R.string.shared_settings_you), topSpacing = Spacing.none)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.shared_name_label)) },
                singleLine = true,
                isError = form.nameError != null,
                supportingText = { Text(form.nameError?.asString() ?: stringResource(R.string.shared_settings_name_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveName() }),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = saveName, enabled = !form.busy && name.isNotBlank() && name.trim() != state.displayName, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.shared_save))
            }

            SectionHeaderV2(title = stringResource(R.string.shared_settings_server))
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.shared_server_label)) },
                placeholder = { Text(stringResource(R.string.shared_server_placeholder)) },
                singleLine = true,
                isError = form.addressError != null,
                supportingText = { Text(form.addressError?.asString() ?: stringResource(R.string.shared_settings_server_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveAddress() }),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = saveAddress, enabled = !form.busy && address.isNotBlank() && address.trim() != state.serverAddress, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.shared_save))
            }

            SectionHeaderV2(title = stringResource(R.string.shared_settings_sync))
            Text(syncStatus(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = viewModel::syncNow,
                enabled = !state.syncing && state.serverAddress.isNotEmpty(),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (state.syncing) R.string.shared_settings_syncing else R.string.shared_settings_sync_now))
            }
        }
    }

    form.confirmAddress?.let { pending ->
        SharedConfirmDialog(
            title = stringResource(R.string.shared_settings_server_confirm_title),
            message = stringResource(R.string.shared_settings_server_confirm_body, serverHost(pending)),
            confirmLabel = stringResource(R.string.shared_settings_server_confirm),
            onConfirm = viewModel::confirmServerAddress,
            onDismiss = viewModel::dismissAddressConfirm,
            destructive = true,
        )
    }
}

/** "Last synced …", the last sync's error, or "Not synced yet". */
@Composable
private fun syncStatus(state: SharedSettingsUiState): String {
    val error = state.lastError
    val last = state.lastSuccessAt?.let { AppDates.dateTimeFormatter().format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
    return when {
        error != null -> stringResource(R.string.shared_settings_sync_failed, sharedErrorText(error).asString())
        last != null -> stringResource(R.string.shared_settings_last_sync, last)
        else -> stringResource(R.string.shared_settings_never_synced)
    }
}
