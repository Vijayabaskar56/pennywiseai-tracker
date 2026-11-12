package com.pennywiseai.tracker.presentation.sharedexpenses

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.navigation.SharedActivityFeed
import com.pennywiseai.tracker.navigation.SharedExpenseDetail
import com.pennywiseai.tracker.navigation.SharedExpenseEditor
import com.pennywiseai.tracker.navigation.SharedGroupSettings
import com.pennywiseai.tracker.navigation.SharedMembers
import com.pennywiseai.tracker.navigation.SharedRecurringList
import com.pennywiseai.tracker.navigation.SharedSettleUp
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.components.cards.GroupedRow
import com.pennywiseai.tracker.ui.components.cards.ListItemPosition
import com.pennywiseai.tracker.ui.components.cards.PennyWiseCardV2
import com.pennywiseai.tracker.ui.components.cards.RowLabels
import com.pennywiseai.tracker.ui.components.cards.SectionHeaderV2
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.PennyWiseText
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.ui.theme.income
import com.pennywiseai.tracker.utils.AppDates
import com.pennywiseai.tracker.utils.CurrencyFormatter

/**
 * One Shared group: my balance, who owes whom, and the ledger of expenses and
 * Settlements by month. Leaves with a message when the group disappears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedGroupDetailScreen(
    groupId: String,
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
    viewModel: SharedGroupDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.gone) {
        if (state.gone) {
            Toast.makeText(context, R.string.shared_group_gone, Toast.LENGTH_LONG).show()
            onNavigateBack()
        }
    }

    var menu by remember { mutableStateOf(false) }
    PennyWiseScaffold(
        title = state.groupName,
        navigationIcon = { SharedBackButton(onNavigateBack) },
        actions = {
            IconButton(onClick = { onNavigate(SharedMembers(groupId)) }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Default.People, contentDescription = stringResource(R.string.shared_members_title))
            }
            IconButton(onClick = { onNavigate(SharedGroupSettings(groupId)) }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.shared_group_settings_title))
            }
            Box {
                IconButton(onClick = { menu = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.shared_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_activity_title)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null) },
                        onClick = {
                            menu = false
                            onNavigate(SharedActivityFeed(groupId))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_recurring_title)) },
                        leadingIcon = { Icon(Icons.Default.Repeat, contentDescription = null) },
                        onClick = {
                            menu = false
                            onNavigate(SharedRecurringList(groupId))
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (!state.loading && !state.gone) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = Dimensions.Padding.content, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        OutlinedButton(onClick = { onNavigate(SharedSettleUp(groupId)) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.shared_settle_up_title))
                        }
                        Button(onClick = { onNavigate(SharedExpenseEditor(groupId)) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(Dimensions.Icon.small))
                            Text(stringResource(R.string.shared_add_expense), modifier = Modifier.padding(start = Spacing.xs))
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (state.loading || state.gone) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@PennyWiseScaffold
        }
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Dimensions.Padding.content, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.Layout.groupedListGap),
            ) {
                item(key = "banner") {
                    SharedSyncBanner(
                        error = state.syncError?.let(::sharedErrorText),
                        offline = state.syncError?.isOffline == true,
                        problemCount = state.problemCount,
                        syncing = state.syncing,
                        onRetry = viewModel::refresh,
                        modifier = Modifier.padding(bottom = Spacing.sm),
                    )
                }
                item(key = "balance") {
                    PennyWiseCardV2(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shared_my_balance), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BalanceLinesText(state.myLines, style = PennyWiseText.amountMedium, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
                item(key = "debts-header") {
                    SectionHeaderV2(title = stringResource(R.string.shared_who_owes_whom), topSpacing = Spacing.md)
                }
                if (state.debts.isEmpty()) {
                    item(key = "debts-none") {
                        Text(
                            stringResource(R.string.shared_all_settled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.sm),
                        )
                    }
                }
                itemsIndexed(state.debts, key = { _, d -> "debt-${d.key}" }) { i, debt ->
                    GroupedRow(
                        position = ListItemPosition.from(i, state.debts.size),
                        onClick = {
                            onNavigate(SharedSettleUp(groupId, debt.fromId, debt.toId, debt.amount.toPlainString(), debt.currency))
                        },
                        modifier = Modifier.animateItem(),
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        RowLabels(title = debt.text(), titleMaxLines = 2)
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimensions.Icon.inline))
                    }
                }
                item(key = "ledger-header") {
                    SectionHeaderV2(title = stringResource(R.string.shared_ledger_title), topSpacing = Spacing.md)
                }
                if (state.ledger.isEmpty()) {
                    item(key = "ledger-none") {
                        Text(
                            stringResource(R.string.shared_ledger_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.sm),
                        )
                    }
                }
                itemsIndexed(
                    state.ledger,
                    key = { _, item -> item.key },
                    contentType = { _, item -> item::class },
                ) { i, item ->
                    when (item) {
                        is LedgerItem.Month -> Text(
                            AppDates.pattern("MMMM yyyy").format(item.month),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.animateItem().padding(top = Spacing.md, bottom = Spacing.xs),
                        )
                        is LedgerItem.Expense -> ExpenseRow(
                            item = item,
                            position = rowPosition(state.ledger, i),
                            onClick = { onNavigate(SharedExpenseDetail(groupId, item.id)) },
                            modifier = Modifier.animateItem(),
                        )
                        is LedgerItem.Settlement -> SettlementRow(item, rowPosition(state.ledger, i), Modifier.animateItem())
                    }
                }
            }
        }
    }
}

/** A row's place in its month's block, so each month reads as one grouped list. */
private fun rowPosition(ledger: List<LedgerItem>, index: Int): ListItemPosition {
    val first = ledger.getOrNull(index - 1) !is LedgerItem.Expense && ledger.getOrNull(index - 1) !is LedgerItem.Settlement
    val last = ledger.getOrNull(index + 1).let { it == null || it is LedgerItem.Month }
    return when {
        first && last -> ListItemPosition.Single
        first -> ListItemPosition.Top
        last -> ListItemPosition.Bottom
        else -> ListItemPosition.Middle
    }
}

@Composable
private fun ExpenseRow(item: LedgerItem.Expense, position: ListItemPosition, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GroupedRow(position = position, onClick = onClick, modifier = modifier) {
        DateBadge(item.date)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(item.description, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                SyncMarkIcon(item.mark)
            }
            Text(item.payer.text(item.amount, item.currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            item.myPart.text(),
            style = MaterialTheme.typography.labelMedium,
            color = when (item.myPart) {
                is MyPart.Lent -> MaterialTheme.colorScheme.income
                is MyPart.Borrowed -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
        )
    }
}

@Composable
private fun SettlementRow(item: LedgerItem.Settlement, position: ListItemPosition, modifier: Modifier = Modifier) {
    GroupedRow(position = position, modifier = modifier) {
        DateBadge(item.date)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(settlementText(item.from, item.to), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            SyncMarkIcon(item.mark)
        }
        Text(CurrencyFormatter.formatCurrency(item.amount, item.currency), style = PennyWiseText.amountRow)
    }
}

/** The day of the month over its short name, as a row's leading column. */
@Composable
private fun DateBadge(date: java.time.LocalDate) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = Dimensions.Icon.avatar)) {
        Text(AppDates.pattern("MMM").format(date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium)
    }
}
