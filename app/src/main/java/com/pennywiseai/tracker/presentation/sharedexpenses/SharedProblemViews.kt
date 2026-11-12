package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.sharedexpenses.SharedEntityType
import com.pennywiseai.tracker.data.sharedexpenses.SharedOperation
import com.pennywiseai.tracker.ui.components.cards.PennyWiseCardV2
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.ui.theme.warning
import com.pennywiseai.tracker.utils.CurrencyFormatter

/**
 * A change that needs attention, with what can be done about it: for a
 * conflict, my version against theirs with Keep mine / Use theirs; for a
 * refused change, the reason with Discard. [onOpen] adds a button that opens
 * the row ([openLabel], "Open" by default); [showSubject] names the row and
 * its group, for a list of problems from several rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharedProblemCard(
    row: ProblemRow,
    busy: Boolean,
    onKeepMine: () -> Unit,
    onUseTheirs: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    openLabel: String = stringResource(R.string.shared_problem_open),
    showSubject: Boolean = true,
) {
    PennyWiseCardV2(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.warning, modifier = Modifier.size(Dimensions.Icon.small))
                Text(
                    stringResource(
                        when (row.kind) {
                            is ProblemKind.Conflict -> R.string.shared_problem_conflict_title
                            is ProblemKind.Failed -> R.string.shared_problem_failed_title
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (showSubject) {
                Text(problemSubject(row), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    problemContext(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (val kind = row.kind) {
                is ProblemKind.Conflict -> {
                    Text(
                        stringResource(R.string.shared_problem_conflict_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ConflictTable(kind)
                }
                is ProblemKind.Failed -> Text(
                    stringResource(R.string.shared_problem_failed_body, sharedErrorText(kind.error).asString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                when (row.kind) {
                    is ProblemKind.Conflict -> {
                        FilledTonalButton(onClick = onKeepMine, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.shared_problem_keep_mine))
                        }
                        OutlinedButton(onClick = onUseTheirs, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.shared_problem_use_theirs))
                        }
                    }
                    is ProblemKind.Failed -> OutlinedButton(onClick = onDiscard, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.shared_problem_discard))
                    }
                }
                if (onOpen != null) {
                    TextButton(onClick = onOpen, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(openLabel) }
                }
            }
        }
    }
}

/** What the problem is about: the description, or "Member A paid you" for a Settlement. */
@Composable
private fun problemSubject(row: ProblemRow): String {
    val facts = row.facts ?: return stringResource(R.string.shared_problem_unknown_row)
    val what = when (facts.type) {
        SharedEntityType.SETTLEMENT -> settlementText(facts.from ?: PersonRef.Unknown, facts.to ?: PersonRef.Unknown)
        else -> facts.description.orEmpty()
    }
    return stringResource(R.string.shared_problem_subject, what, CurrencyFormatter.formatCurrency(facts.amount, facts.currency))
}

/** "Your edit to an expense in Flat". */
@Composable
private fun problemContext(row: ProblemRow): String {
    val kind = stringResource(
        when (row.entityType) {
            SharedEntityType.EXPENSE -> R.string.shared_problem_type_expense
            SharedEntityType.SETTLEMENT -> R.string.shared_problem_type_settlement
            SharedEntityType.RECURRING -> R.string.shared_problem_type_recurring
        },
    )
    val change = stringResource(
        when (row.operation) {
            SharedOperation.SAVE -> R.string.shared_problem_op_save
            SharedOperation.DELETE -> R.string.shared_problem_op_delete
            SharedOperation.RESTORE -> R.string.shared_problem_op_restore
        },
        kind,
    )
    return row.groupName?.let { stringResource(R.string.shared_problem_in_group, change, it) } ?: change
}

/** My version and theirs side by side: amount, description (or who paid whom), date, who changed it. */
@Composable
private fun ConflictTable(conflict: ProblemKind.Conflict) {
    val mine = conflict.mine
    val theirs = conflict.theirs
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        CompareRow("", stringResource(R.string.shared_problem_mine), stringResource(R.string.shared_problem_theirs), header = true)
        if (theirs.deleted || mine?.deleted == true) {
            CompareRow(
                stringResource(R.string.shared_problem_state),
                stringResource(if (mine == null || mine.deleted) R.string.shared_problem_deleted else R.string.shared_problem_kept),
                stringResource(if (theirs.deleted) R.string.shared_problem_deleted else R.string.shared_problem_kept),
            )
        }
        CompareRow(
            stringResource(R.string.shared_field_amount),
            mine?.let { CurrencyFormatter.formatCurrency(it.amount, it.currency) } ?: DASH,
            CurrencyFormatter.formatCurrency(theirs.amount, theirs.currency),
        )
        if (theirs.type == SharedEntityType.SETTLEMENT) {
            CompareRow(
                stringResource(R.string.shared_problem_who_paid),
                mine?.let { settlementText(it.from ?: PersonRef.Unknown, it.to ?: PersonRef.Unknown) } ?: DASH,
                settlementText(theirs.from ?: PersonRef.Unknown, theirs.to ?: PersonRef.Unknown),
            )
        } else {
            CompareRow(stringResource(R.string.shared_field_description), mine?.description ?: DASH, theirs.description.orEmpty())
        }
        CompareRow(
            stringResource(if (theirs.type == SharedEntityType.RECURRING) R.string.shared_field_next_date else R.string.shared_field_date),
            mine?.let { formDate(it.date) } ?: DASH,
            formDate(theirs.date),
        )
        CompareRow(stringResource(R.string.shared_problem_changed_by), mine?.changedBy?.label() ?: DASH, theirs.changedBy.label())
    }
}

private const val DASH = "–"

@Composable
private fun CompareRow(label: String, mine: String, theirs: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.8f))
        Text(mine, style = style, color = color, modifier = Modifier.weight(1f))
        Text(theirs, style = style, color = color, modifier = Modifier.weight(1f))
    }
}
