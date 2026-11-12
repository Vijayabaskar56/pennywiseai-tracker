package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.ui.UiText
import com.pennywiseai.tracker.ui.components.PennyWiseEmptyState
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.ui.theme.income
import com.pennywiseai.tracker.ui.theme.warning
import com.pennywiseai.tracker.utils.CurrencyFormatter
import kotlinx.coroutines.delay

// Small pieces every Shared screen uses. They take plain values, so the screens
// the next task writes (expense form, settle up, activity) can use them as-is.

/** The back arrow every Shared screen's top bar starts with. */
@Composable
fun SharedBackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.shared_back))
    }
}

/** A member's initial in a tonal circle. [dimmed] for a removed seat. */
@Composable
fun MemberAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = Dimensions.Icon.avatar,
    dimmed: Boolean = false,
) {
    val container = if (dimmed) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.secondaryContainer
    val content = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        modifier = modifier.size(size).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
    }
}

/** A member's name as a row shows it: "You" for this phone's seat. Use [lowercase] mid-sentence ("Member A paid you"). */
@Composable
fun PersonRef.label(lowercase: Boolean = false): String = when (this) {
    PersonRef.Me -> stringResource(if (lowercase) R.string.shared_person_you_lower else R.string.shared_person_you)
    is PersonRef.Named -> name
    PersonRef.Unknown -> stringResource(R.string.shared_person_unknown)
}

/** "You are owed ₹500" / "You owe $12". */
@Composable
fun BalanceLine.text(): String {
    val formatted = CurrencyFormatter.formatCurrency(amount, currency)
    return when (direction) {
        BalanceDirection.OWED -> stringResource(R.string.shared_balance_owed, formatted)
        BalanceDirection.OWE -> stringResource(R.string.shared_balance_owe, formatted)
    }
}

/** "You paid ₹1,240", "Member A paid ₹1,240", "2 people paid ₹1,240". */
@Composable
fun PayerSummary.text(amount: java.math.BigDecimal, currency: String): String {
    val formatted = CurrencyFormatter.formatCurrency(amount, currency)
    return when (this) {
        is PayerSummary.One -> when (who) {
            PersonRef.Me -> stringResource(R.string.shared_paid_by_you, formatted)
            else -> stringResource(R.string.shared_paid_by_one, who.label(), formatted)
        }
        is PayerSummary.Several -> pluralStringResource(R.plurals.shared_paid_by_several, count, count, formatted)
    }
}

/** "you lent ₹620", "you borrowed ₹620", "your share is paid", "not involved". */
@Composable
fun MyPart.text(): String = when (this) {
    is MyPart.Lent -> stringResource(R.string.shared_part_lent, CurrencyFormatter.formatCurrency(amount, currency))
    is MyPart.Borrowed -> stringResource(R.string.shared_part_borrowed, CurrencyFormatter.formatCurrency(amount, currency))
    MyPart.Even -> stringResource(R.string.shared_part_even)
    MyPart.NotInvolved -> stringResource(R.string.shared_part_not_involved)
}

/** "Member A paid Member B", "You paid Member B", "Member A paid you". */
@Composable
fun settlementText(from: PersonRef, to: PersonRef): String =
    stringResource(R.string.shared_settlement_row, from.label(), to.label(lowercase = true))

/** "Member A owes Member B ₹500", "You owe Member B ₹500", "Member A owes you ₹500". */
@Composable
fun DebtLine.text(): String {
    val formatted = CurrencyFormatter.formatCurrency(amount, currency)
    return when {
        from == PersonRef.Me -> stringResource(R.string.shared_debt_you_owe, to.label(), formatted)
        to == PersonRef.Me -> stringResource(R.string.shared_debt_owes_you, from.label(), formatted)
        else -> stringResource(R.string.shared_debt_owes, from.label(), to.label(), formatted)
    }
}

/**
 * My balance per currency, one line each (currencies are never added), or
 * "Settled up" when [lines] is empty. Owed reads in the income colour; owing
 * stays ink, since owing is not a problem.
 */
@Composable
fun BalanceLinesText(
    lines: List<BalanceLine>,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        if (lines.isEmpty()) {
            Text(stringResource(R.string.shared_balance_settled), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lines.forEach { line ->
            Text(
                text = line.text(),
                style = style,
                color = if (line.direction == BalanceDirection.OWED) MaterialTheme.colorScheme.income else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The small mark on a ledger row: not yet on the server, or a change that needs attention. Nothing for [SyncMark.NONE]. */
@Composable
fun SyncMarkIcon(mark: SyncMark, modifier: Modifier = Modifier) {
    when (mark) {
        SyncMark.NONE -> Unit
        SyncMark.PENDING -> Icon(
            Icons.Default.CloudUpload,
            contentDescription = stringResource(R.string.shared_mark_pending),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(Dimensions.Icon.small),
        )
        SyncMark.PROBLEM -> Icon(
            Icons.Default.WarningAmber,
            contentDescription = stringResource(R.string.shared_mark_problem),
            tint = MaterialTheme.colorScheme.warning,
            modifier = modifier.size(Dimensions.Icon.small),
        )
    }
}

/**
 * The unobtrusive sync status strip: offline or the last sync's error with
 * Retry, and how many changes could not be sent. Draws nothing when all is well.
 */
@Composable
fun SharedSyncBanner(
    error: UiText?,
    offline: Boolean,
    problemCount: Int,
    syncing: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (error == null && problemCount == 0) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (error != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(
                        if (offline) Icons.Default.CloudOff else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Dimensions.Icon.small),
                    )
                    Text(
                        text = if (offline) stringResource(R.string.shared_banner_offline) else error.asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry, enabled = !syncing, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.shared_retry))
                    }
                }
            }
            if (problemCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.heightIn(min = Dimensions.Component.minTouchTarget),
                ) {
                    Icon(
                        Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.warning,
                        modifier = Modifier.size(Dimensions.Icon.small),
                    )
                    Text(
                        text = pluralStringResource(R.plurals.shared_banner_problems, problemCount, problemCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A dialog with one text field, for a name or an address. Done on the keyboard
 * confirms. [error] shows under the field; [busy] disables the buttons while
 * the action runs. Dismissing is always possible unless [busy].
 */
@Composable
fun SharedTextFieldDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    message: String? = null,
    error: String? = null,
    busy: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val confirm = { if (!busy && value.isNotBlank()) onConfirm(value) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = !busy && value.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_cancel)) }
        },
    )
    // After the dialog's window is attached, or the request is lost.
    androidx.compose.runtime.LaunchedEffect(focus) {
        delay(FOCUS_DELAY_MS)
        runCatching { focus.requestFocus() }
    }
}

private const val FOCUS_DELAY_MS = 100L

/** A yes/no question before a change that can't be undone. [destructive] tints the confirm button. */
@Composable
fun SharedConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                shapes = ButtonDefaults.shapes(),
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_cancel)) }
        },
    )
}

/** Picks one of [currencies] (ISO codes), opening on [selected]. */
@Composable
fun SharedCurrencyPickerDialog(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    currencies: List<String> = remember { CurrencyFormatter.getSupportedCurrencies().sorted() },
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currencies.indexOf(selected) - 2).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shared_currency_title)) },
        text = {
            LazyColumn(state = listState) {
                items(currencies, key = { it }) { code ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimensions.Component.minTouchTarget)
                            .selectable(selected = code == selected, role = Role.RadioButton, onClick = { onSelect(code) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        RadioButton(selected = code == selected, onClick = null)
                        Text(
                            stringResource(R.string.shared_currency_option, code, CurrencyFormatter.getCurrencySymbol(code)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_cancel)) }
        },
    )
}

/** A settings-style row: a label and its current value, tappable. */
@Composable
fun SharedValueRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimensions.Component.minTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The stand-in for a Shared screen another task builds: a top bar with a back
 * arrow and [title], and a short note. Its callers keep their signatures when
 * the real screen replaces it.
 */
@Composable
internal fun SharedPlaceholderScreen(title: String, onNavigateBack: () -> Unit) {
    PennyWiseScaffold(title = title, navigationIcon = { SharedBackButton(onNavigateBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            PennyWiseEmptyState(
                icon = Icons.Default.Construction,
                headline = title,
                description = stringResource(R.string.shared_placeholder_body),
            )
        }
    }
}
