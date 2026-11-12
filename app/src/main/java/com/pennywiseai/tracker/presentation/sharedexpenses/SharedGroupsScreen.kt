package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.navigation.SharedActivityFeed
import com.pennywiseai.tracker.navigation.SharedGroupDetail
import com.pennywiseai.tracker.navigation.SharedJoin
import com.pennywiseai.tracker.navigation.SharedSettings
import com.pennywiseai.tracker.ui.components.DockNavAction
import com.pennywiseai.tracker.ui.components.LocalNavDock
import com.pennywiseai.tracker.ui.components.NavDockAction
import com.pennywiseai.tracker.ui.components.PennyWiseEmptyState
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.components.cards.IconTile
import com.pennywiseai.tracker.ui.components.cards.PennyWiseCardV2
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.PennyWiseText
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.ui.theme.income
import com.pennywiseai.tracker.utils.CurrencyFormatter
import com.pennywiseai.tracker.utils.Money

/**
 * The Shared tab's first screen: what I owe and am owed across groups, a card
 * per group, and New group / Join. With no server address yet it asks for one.
 * It sits under the overlaid nav bar, so its list and FAB clear it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedGroupsScreen(
    onNavigate: (NavKey) -> Unit,
    viewModel: SharedGroupsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = state.form

    LaunchedEffect(form.openGroupId) {
        form.openGroupId?.let {
            viewModel.onGroupOpened()
            onNavigate(SharedGroupDetail(it))
        }
    }
    LaunchedEffect(form.openInvite) {
        form.openInvite?.let {
            viewModel.onInviteOpened()
            onNavigate(SharedJoin(it.server, it.token))
        }
    }

    val ready = !state.loading && state.addressSet
    // Under the floating bar New group docks beside it; otherwise it is a FAB above the bar.
    val docked = LocalNavDock.current != null
    if (ready && state.groups.isNotEmpty() && docked) {
        DockNavAction(NavDockAction(icon = Icons.Default.Add, contentDescription = stringResource(R.string.shared_new_group), onClick = viewModel::startNewGroup))
    }

    PennyWiseScaffold(
        title = stringResource(R.string.shared_tab_title),
        actions = {
            if (ready) {
                IconButton(onClick = { onNavigate(SharedActivityFeed()) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = stringResource(R.string.shared_activity_title))
                }
            }
            IconButton(onClick = { onNavigate(SharedSettings) }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.shared_settings_title))
            }
        },
        floatingActionButton = {
            if (ready && state.groups.isNotEmpty() && !docked) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::startNewGroup,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.shared_new_group)) },
                    modifier = Modifier.padding(bottom = Dimensions.Component.bottomBarHeight),
                )
            }
        },
    ) { padding ->
        // The overlaid nav bar (and the FAB above it) cover the bottom of the screen.
        val bottom = padding.calculateBottomPadding() + Dimensions.Component.bottomBarHeight + Dimensions.Component.fabScrollClearance
        fun listPadding(top: androidx.compose.ui.unit.Dp) =
            PaddingValues(start = Dimensions.Padding.content, end = Dimensions.Padding.content, top = top, bottom = bottom)
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            !state.addressSet -> ServerSetup(
                busy = form.busy,
                error = form.fieldError?.asString(),
                onSave = viewModel::setServerAddress,
                contentPadding = listPadding(padding.calculateTopPadding() + Spacing.sm),
            )
            else -> PullToRefreshBox(
                isRefreshing = state.syncing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listPadding(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Layout.listGap),
                ) {
                    item(key = "banner") {
                        SharedSyncBanner(
                            error = state.syncError?.let(::sharedErrorText),
                            offline = state.syncError?.isOffline == true,
                            problemCount = state.problemCount,
                            syncing = state.syncing,
                            onRetry = viewModel::refresh,
                        )
                    }
                    if (state.groups.isEmpty()) {
                        item(key = "empty") {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                PennyWiseEmptyState(
                                    icon = Icons.Default.Groups,
                                    headline = stringResource(R.string.shared_empty_title),
                                    description = stringResource(R.string.shared_empty_body),
                                    actionLabel = stringResource(R.string.shared_new_group),
                                    onAction = viewModel::startNewGroup,
                                )
                                TextButton(onClick = viewModel::startJoin, shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(R.string.shared_join_with_link))
                                }
                            }
                        }
                    } else {
                        item(key = "summary") { SummaryCard(state.summary.owe.values.toList(), state.summary.owed.values.toList()) }
                        item(key = "join") {
                            OutlinedButton(onClick = viewModel::startJoin, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(Dimensions.Icon.small))
                                Text(stringResource(R.string.shared_join_with_link), modifier = Modifier.padding(start = Spacing.sm))
                            }
                        }
                        items(state.groups, key = { it.id }) { card ->
                            GroupCard(card, onClick = { onNavigate(SharedGroupDetail(card.id)) }, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }

    when (val dialog = form.dialog) {
        null -> Unit
        is SharedGroupsDialog.Name -> SharedTextFieldDialog(
            title = stringResource(R.string.shared_name_dialog_title),
            message = stringResource(R.string.shared_name_dialog_body),
            label = stringResource(R.string.shared_name_label),
            confirmLabel = stringResource(R.string.shared_continue),
            onConfirm = viewModel::setDisplayName,
            onDismiss = viewModel::dismissDialog,
            error = form.fieldError?.asString(),
            busy = form.busy,
        )
        SharedGroupsDialog.NewGroup -> NewGroupDialog(
            baseCurrency = state.baseCurrency,
            busy = form.busy,
            error = form.fieldError?.asString(),
            onCreate = viewModel::createGroup,
            onDismiss = viewModel::dismissDialog,
        )
        SharedGroupsDialog.Join -> SharedTextFieldDialog(
            title = stringResource(R.string.shared_join_dialog_title),
            message = stringResource(R.string.shared_join_dialog_body),
            label = stringResource(R.string.shared_join_link_label),
            confirmLabel = stringResource(R.string.shared_continue),
            onConfirm = viewModel::joinWithLink,
            onDismiss = viewModel::dismissDialog,
            error = form.fieldError?.asString(),
            busy = form.busy,
            keyboardType = KeyboardType.Uri,
            capitalization = KeyboardCapitalization.None,
        )
    }
}

/** First use: what a server address is for, and a field to enter it. */
@Composable
private fun ServerSetup(busy: Boolean, error: String?, onSave: (String) -> Unit, contentPadding: PaddingValues) {
    var address by rememberSaveable { mutableStateOf("") }
    val save = { if (!busy && address.isNotBlank()) onSave(address) }
    LazyColumn(modifier = Modifier.fillMaxSize().imePadding(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        item {
            PennyWiseEmptyState(
                icon = Icons.Default.Groups,
                headline = stringResource(R.string.shared_setup_title),
                description = stringResource(R.string.shared_setup_body),
            )
        }
        item {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.shared_server_label)) },
                placeholder = { Text(stringResource(R.string.shared_server_placeholder)) },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: stringResource(R.string.shared_server_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(onClick = save, enabled = !busy && address.isNotBlank(), shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.shared_save))
            }
        }
    }
}

/** What I owe and am owed across every group: one line per currency, never added together. */
@Composable
private fun SummaryCard(owe: List<Money>, owed: List<Money>) {
    PennyWiseCardV2(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SummaryColumn(stringResource(R.string.shared_summary_owe), owe, owedSide = false, modifier = Modifier.weight(1f))
            SummaryColumn(stringResource(R.string.shared_summary_owed), owed, owedSide = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryColumn(label: String, amounts: List<Money>, owedSide: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (amounts.isEmpty()) {
            Text(stringResource(R.string.shared_summary_nothing), style = PennyWiseText.amountMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        amounts.forEach { money ->
            Text(
                CurrencyFormatter.formatCurrency(money.amount, money.currency),
                style = PennyWiseText.amountMedium,
                color = if (owedSide) MaterialTheme.colorScheme.income else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupCard(card: SharedGroupCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PennyWiseCardV2(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            IconTile(
                icon = Icons.Default.Groups,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                size = Dimensions.Icon.avatar,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.shared_member_count, card.memberCount, card.memberCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BalanceLinesText(card.lines)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimensions.Icon.inline))
        }
    }
}

/** Name and default currency for a new Shared group. */
@Composable
private fun NewGroupDialog(
    baseCurrency: String,
    busy: Boolean,
    error: String?,
    onCreate: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf(baseCurrency) }
    var picking by remember { mutableStateOf(false) }
    val create = { if (!busy && name.isNotBlank()) onCreate(name, currency) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shared_new_group)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shared_group_name_label)) },
                    placeholder = { Text(stringResource(R.string.shared_group_name_placeholder)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                SharedValueRow(label = stringResource(R.string.shared_default_currency), value = currency, onClick = { picking = true }, enabled = !busy)
            }
        },
        confirmButton = {
            TextButton(onClick = create, enabled = !busy && name.isNotBlank(), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.shared_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_cancel)) }
        },
    )
    if (picking) {
        SharedCurrencyPickerDialog(
            selected = currency,
            onSelect = {
                currency = it
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}
