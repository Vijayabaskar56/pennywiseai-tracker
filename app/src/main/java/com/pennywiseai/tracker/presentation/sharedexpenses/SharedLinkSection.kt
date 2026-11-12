package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.sharedexpenses.SharedLinkedTransaction
import com.pennywiseai.tracker.ui.components.cards.GroupedRow
import com.pennywiseai.tracker.ui.components.cards.ListItemPosition
import com.pennywiseai.tracker.ui.components.cards.SectionHeaderV2
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.utils.AppDates
import com.pennywiseai.tracker.utils.CurrencyFormatter

/**
 * The bank transaction of an expense or Settlement: the [linked] one with
 * "Unlink", a [pending] choice that is linked on save, or "Link a bank
 * transaction" when [canLink]. Shows nothing when none applies.
 */
@Composable
fun SharedLinkSection(
    linked: SharedLinkedTransaction?,
    canLink: Boolean,
    hint: String,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
    modifier: Modifier = Modifier,
    pending: SharedLinkedTransaction? = null,
    enabled: Boolean = true,
) {
    val shown = linked ?: pending
    if (shown == null && !canLink) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeaderV2(title = stringResource(R.string.shared_link_section_title), subtitle = hint)
        if (shown != null) {
            GroupedRow(position = ListItemPosition.Single) {
                TransactionLabels(shown, if (linked == null) stringResource(R.string.shared_link_pending) else null, Modifier.weight(1f))
                TextButton(onClick = onUnlink, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.shared_link_unlink))
                }
            }
        } else {
            OutlinedButton(onClick = onLink, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Default.Link, contentDescription = null)
                Text(stringResource(R.string.shared_link_action), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
    }
}

@Composable
private fun TransactionLabels(t: SharedLinkedTransaction, note: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(t.merchant, style = MaterialTheme.typography.bodyLarge)
        Text(
            CurrencyFormatter.formatCurrency(t.amount, t.currency) + " · " + AppDates.dateFormatter().format(t.date),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

/** Picks one of [candidates] (null while loading) in [currency]. */
@Composable
fun SharedLinkPickerDialog(
    currency: String,
    candidates: List<SharedLinkedTransaction>?,
    onPick: (SharedLinkedTransaction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shared_link_pick_title)) },
        text = {
            when {
                candidates == null -> LoadingIndicator()
                candidates.isEmpty() -> Text(stringResource(R.string.shared_link_pick_empty, currency))
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.transactionId }) { c ->
                        TransactionLabels(
                            c,
                            if (c.exactMatch) stringResource(R.string.shared_link_exact) else null,
                            Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = Spacing.sm),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_link_cancel)) }
        },
    )
}
