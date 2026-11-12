package com.pennywiseai.tracker.presentation.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.sharedexpenses.SharedTransactionTie
import com.pennywiseai.tracker.domain.sharedexpenses.SharedGroup
import com.pennywiseai.tracker.ui.components.cards.PennyWiseCardV2
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.utils.CurrencyFormatter

/** Which Shared group a transaction belongs to, with Open and (for a linked bank transaction) Unlink. */
@Composable
fun SharedTieSection(tie: SharedTransactionTie?, onOpen: (SharedTransactionTie) -> Unit, onUnlink: () -> Unit, modifier: Modifier = Modifier) {
    tie ?: return
    val group = tie.groupName ?: stringResource(R.string.shared_txn_group_unknown)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(R.string.shared_txn_section_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        PennyWiseCardV2(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                when (tie) {
                    is SharedTransactionTie.Expense -> {
                        Text(stringResource(R.string.shared_txn_expense_linked, group), style = MaterialTheme.typography.bodyMedium)
                        val share = tie.myShare
                        Text(
                            if (share != null) {
                                stringResource(R.string.shared_txn_my_share, CurrencyFormatter.formatCurrency(share, tie.currency))
                            } else {
                                stringResource(R.string.shared_txn_expense_gone)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is SharedTransactionTie.Settlement ->
                        Text(stringResource(R.string.shared_txn_settlement_linked, group), style = MaterialTheme.typography.bodyMedium)
                    is SharedTransactionTie.Entry -> {
                        Text(stringResource(R.string.shared_txn_entry, group), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.shared_txn_entry_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(onClick = { onOpen(tie) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_link_open)) }
                    if (tie !is SharedTransactionTie.Entry) {
                        TextButton(onClick = onUnlink, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_link_unlink)) }
                    }
                }
            }
        }
    }
}

/** Picks one of [groups]. */
@Composable
fun SharedGroupPickerDialog(groups: List<SharedGroup>, onPick: (SharedGroup) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shared_txn_pick_group)) },
        text = {
            Column {
                groups.forEach { g ->
                    Text(
                        g.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(g) }.padding(vertical = Spacing.md),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_link_cancel)) }
        },
    )
}
