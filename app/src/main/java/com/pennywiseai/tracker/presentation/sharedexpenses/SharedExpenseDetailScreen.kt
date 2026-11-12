package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import com.pennywiseai.tracker.navigation.SharedExpenseEditor
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.components.cards.GroupedList
import com.pennywiseai.tracker.ui.components.cards.GroupedRow
import com.pennywiseai.tracker.ui.components.cards.ListItemPosition
import com.pennywiseai.tracker.ui.components.cards.PennyWiseCardV2
import com.pennywiseai.tracker.ui.components.cards.RowLabels
import com.pennywiseai.tracker.ui.components.cards.SectionHeaderV2
import com.pennywiseai.tracker.ui.icons.categoryLabel
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.PennyWiseText
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.utils.AppDates
import com.pennywiseai.tracker.utils.CurrencyFormatter
import java.time.Instant
import java.time.ZoneId

/** One Shared expense: amounts, who paid, each Share, who changed it; Edit, Delete, Restore, and its sync problem if any. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SharedExpenseDetailScreen(
    groupId: String,
    expenseId: String,
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
    viewModel: SharedExpenseDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.gone) { if (state.gone) onNavigateBack() }
    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            viewModel.clearMessage()
            snackbar.showSnackbar(message)
        }
    }

    val expense = state.expense
    val edit = { onNavigate(SharedExpenseEditor(groupId, expenseId = expenseId)) }
    PennyWiseScaffold(
        title = stringResource(R.string.shared_expense_detail_title),
        navigationIcon = { SharedBackButton(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            if (expense != null && !expense.deleted) {
                IconButton(onClick = edit, enabled = !state.busy, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.shared_edit))
                }
                IconButton(onClick = { confirmDelete = true }, enabled = !state.busy, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.shared_delete))
                }
            }
        },
    ) { padding ->
        if (state.loading || expense == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@PennyWiseScaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Dimensions.Padding.content, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Layout.headerToContent),
        ) {
            state.problem?.let { problem ->
                SharedProblemCard(
                    row = problem,
                    busy = state.busy,
                    onKeepMine = { viewModel.keepMine(problem.itemId) },
                    onUseTheirs = { viewModel.useTheirs(problem.itemId) },
                    onDiscard = { viewModel.discard(problem.itemId) },
                    // A refused change can be fixed in the form and sent again.
                    onOpen = if (problem.kind is ProblemKind.Failed && !expense.deleted) edit else null,
                    openLabel = stringResource(R.string.shared_edit),
                    showSubject = false,
                )
            }
            if (expense.deleted) {
                PennyWiseCardV2(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            stringResource(R.string.shared_expense_deleted),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = viewModel::restore, enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(Dimensions.Icon.small))
                            Text(stringResource(R.string.shared_restore), modifier = Modifier.padding(start = Spacing.xs))
                        }
                    }
                }
            }
            HeaderCard(expense)

            SectionHeaderV2(title = stringResource(R.string.shared_paid_by_title))
            PartList(expense.payers, expense.currency)

            SectionHeaderV2(title = stringResource(R.string.shared_detail_split, splitMethodLabel(expense.method)))
            PartList(expense.shares, expense.currency, expense.method)

            expense.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                SectionHeaderV2(title = stringResource(R.string.shared_field_notes))
                PennyWiseCardV2(modifier = Modifier.fillMaxWidth()) {
                    Text(notes, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Column(Modifier.padding(top = Spacing.md, bottom = Spacing.Layout.scrollBottomPadding), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                MetaLine(stringResource(R.string.shared_detail_added_by, expense.createdBy.label(), dateTime(expense.createdAt)))
                if (expense.edited) {
                    MetaLine(stringResource(R.string.shared_detail_changed_by, expense.updatedBy.label(), dateTime(expense.updatedAt)))
                }
                if (expense.fromRecurring) MetaLine(stringResource(R.string.shared_detail_from_recurring))
            }
        }
    }

    if (confirmDelete) {
        SharedConfirmDialog(
            title = stringResource(R.string.shared_delete_expense_title),
            message = stringResource(R.string.shared_delete_expense_body),
            confirmLabel = stringResource(R.string.shared_delete),
            onConfirm = {
                confirmDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
private fun HeaderCard(expense: ExpenseDetail) {
    PennyWiseCardV2(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(expense.description, style = MaterialTheme.typography.titleLarge)
            Text(CurrencyFormatter.formatCurrency(expense.amount, expense.currency), style = PennyWiseText.amountLarge)
            val meta = listOfNotNull(formDate(expense.date), expense.category?.let { categoryLabel(it) }).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expense.pending) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimensions.Icon.small))
                    Text(stringResource(R.string.shared_mark_pending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PartList(lines: List<PartLine>, currency: String, method: SplitMethod? = null) {
    GroupedList {
        lines.forEachIndexed { i, line ->
            GroupedRow(position = ListItemPosition.from(i, lines.size)) {
                MemberAvatar(line.who.label(), size = Dimensions.Icon.large)
                RowLabels(title = line.who.label(), subtitle = line.weight?.let { weightText(it, method) })
                Text(CurrencyFormatter.formatCurrency(line.amount, currency), style = PennyWiseText.amountRow)
            }
        }
    }
}

/** "50%" for a percent, "2 shares" for a share count. */
@Composable
private fun weightText(weight: String, method: SplitMethod?): String? = when (method) {
    SplitMethod.PERCENT -> stringResource(R.string.shared_weight_percent, weight)
    SplitMethod.SHARES -> weight.toIntOrNull()?.let { pluralStringResource(R.plurals.shared_weight_shares, it, it) }
    else -> null
}

@Composable
private fun MetaLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun dateTime(millis: Long): String =
    AppDates.dateTimeFormatter().format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
