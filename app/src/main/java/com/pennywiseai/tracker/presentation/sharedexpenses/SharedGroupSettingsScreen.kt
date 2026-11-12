package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.components.PreferenceSwitch
import com.pennywiseai.tracker.ui.components.cards.GroupedList
import com.pennywiseai.tracker.ui.components.cards.GroupedRow
import com.pennywiseai.tracker.ui.components.cards.ListItemPosition
import com.pennywiseai.tracker.ui.components.cards.RowLabels
import com.pennywiseai.tracker.ui.components.cards.SectionHeaderV2
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing

/** A group's name, default currency, simplify-debts switch, and Leave / Delete. */
@Composable
fun SharedGroupSettingsScreen(
    groupId: String,
    onNavigateBack: () -> Unit,
    viewModel: SharedGroupSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = state.form
    val snackbar = remember { SnackbarHostState() }

    // Left or deleted: the group detail under this screen says so and leaves too.
    LaunchedEffect(state.gone) { if (state.gone) onNavigateBack() }
    val message = form.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            viewModel.clearMessage()
            snackbar.showSnackbar(message)
        }
    }

    PennyWiseScaffold(
        title = stringResource(R.string.shared_group_settings_title),
        navigationIcon = { SharedBackButton(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading || state.gone) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@PennyWiseScaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimensions.Padding.content, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Layout.headerToContent),
        ) {
            SectionHeaderV2(title = stringResource(R.string.shared_group_settings_section), topSpacing = Spacing.none)
            GroupedList {
                GroupedRow(position = ListItemPosition.Top, onClick = { viewModel.showDialog(SharedGroupSettingsDialog.RENAME) }, enabled = !form.busy) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    RowLabels(title = stringResource(R.string.shared_group_name_label), subtitle = state.name)
                }
                GroupedRow(position = ListItemPosition.Middle, onClick = { viewModel.showDialog(SharedGroupSettingsDialog.CURRENCY) }, enabled = !form.busy) {
                    Icon(Icons.Default.Payments, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    RowLabels(title = stringResource(R.string.shared_default_currency), subtitle = state.defaultCurrency)
                }
                PreferenceSwitch(
                    title = stringResource(R.string.shared_simplify_title),
                    subtitle = stringResource(R.string.shared_simplify_body),
                    checked = state.simplifyDebts,
                    onCheckedChange = { if (!form.busy) viewModel.setSimplifyDebts(it) },
                    position = ListItemPosition.Bottom,
                )
            }

            SectionHeaderV2(title = stringResource(R.string.shared_group_leave_section))
            GroupedList {
                GroupedRow(
                    position = ListItemPosition.Top,
                    onClick = { viewModel.showDialog(SharedGroupSettingsDialog.LEAVE) },
                    enabled = !form.busy && state.canLeave,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    RowLabels(
                        title = stringResource(R.string.shared_leave_group),
                        subtitle = stringResource(if (state.canLeave) R.string.shared_leave_body else R.string.shared_leave_blocked),
                    )
                }
                GroupedRow(position = ListItemPosition.Bottom, onClick = { viewModel.showDialog(SharedGroupSettingsDialog.DELETE) }, enabled = !form.busy) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    RowLabels(title = stringResource(R.string.shared_delete_group), subtitle = stringResource(R.string.shared_delete_group_body))
                }
            }
            if (!state.canLeave) {
                Text(
                    stringResource(R.string.shared_leave_balance_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                BalanceLinesText(state.myLines)
            }
        }
    }

    when (form.dialog) {
        null -> Unit
        SharedGroupSettingsDialog.RENAME -> SharedTextFieldDialog(
            title = stringResource(R.string.shared_rename_group),
            label = stringResource(R.string.shared_group_name_label),
            confirmLabel = stringResource(R.string.shared_save),
            initial = state.name,
            onConfirm = viewModel::rename,
            onDismiss = viewModel::dismissDialog,
            error = form.fieldError?.asString(),
            busy = form.busy,
        )
        SharedGroupSettingsDialog.CURRENCY -> SharedCurrencyPickerDialog(
            selected = state.defaultCurrency,
            onSelect = viewModel::setDefaultCurrency,
            onDismiss = viewModel::dismissDialog,
        )
        SharedGroupSettingsDialog.LEAVE -> SharedConfirmDialog(
            title = stringResource(R.string.shared_leave_title, state.name),
            message = stringResource(R.string.shared_leave_confirm_body),
            confirmLabel = stringResource(R.string.shared_leave_group),
            onConfirm = viewModel::leave,
            onDismiss = viewModel::dismissDialog,
            destructive = true,
        )
        SharedGroupSettingsDialog.DELETE -> SharedConfirmDialog(
            title = stringResource(R.string.shared_delete_title, state.name),
            message = stringResource(R.string.shared_delete_confirm_body),
            confirmLabel = stringResource(R.string.shared_delete_group),
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDialog,
            destructive = true,
        )
    }
}
