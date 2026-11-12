package com.pennywiseai.tracker.presentation.sharedexpenses

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.domain.sharedexpenses.RecurringInterval
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpenseError
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import com.pennywiseai.tracker.ui.components.CategoryMenuItems
import com.pennywiseai.tracker.ui.components.ConnectedChoiceRow
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.components.SelectedCategoryIcon
import com.pennywiseai.tracker.ui.components.cards.GroupedList
import com.pennywiseai.tracker.ui.components.cards.GroupedRow
import com.pennywiseai.tracker.ui.components.cards.ListItemPosition
import com.pennywiseai.tracker.ui.components.cards.RowLabels
import com.pennywiseai.tracker.ui.components.cards.SectionHeaderV2
import com.pennywiseai.tracker.ui.icons.categoryLabel
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.PennyWiseText
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.utils.CurrencyFormatter
import java.math.BigDecimal

/**
 * Add ([expenseId] null) or edit a Shared expense, or edit a recurring
 * expense's template ([recurringId]). [fromTransactionId] fills a new expense
 * from a bank transaction. The parameters are read by the ViewModel from the
 * navigation key; they are listed here so the entry point stays explicit.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SharedExpenseEditorScreen(
    groupId: String,
    expenseId: String?,
    fromTransactionId: Long?,
    recurringId: String?,
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
    viewModel: SharedExpenseEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val context = LocalContext.current
    val savedNotice = state.savedNotice?.asString()
    LaunchedEffect(state.saved, state.gone) {
        if (state.saved || state.gone) {
            // The expense is saved; say why its bank transaction was not linked.
            if (savedNotice != null) Toast.makeText(context, savedNotice, Toast.LENGTH_LONG).show()
            onNavigateBack()
        }
    }
    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            viewModel.clearMessage()
            snackbar.showSnackbar(message)
        }
    }

    PennyWiseScaffold(
        title = stringResource(
            when (state.mode) {
                EditorMode.NEW -> R.string.shared_expense_editor_title
                EditorMode.EDIT_EXPENSE -> R.string.shared_expense_edit_title
                EditorMode.EDIT_RECURRING -> R.string.shared_recurring_edit_title
            },
        ),
        navigationIcon = { SharedBackButton(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!state.loading) {
                SaveBar(
                    label = stringResource(R.string.shared_save),
                    enabled = state.canSave,
                    // A problem in a section not touched yet shows here, so a disabled Save says why.
                    hint = state.preview.error?.takeIf { it.formField !in state.form.touched },
                    onSave = viewModel::save,
                )
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@PennyWiseScaffold
        }
        EditorForm(state, viewModel, Modifier.padding(padding))
    }
}

/** The bottom bar with the form's main button; it rides above the keyboard. */
@Composable
internal fun SaveBar(label: String, enabled: Boolean, hint: SharedExpenseError?, onSave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = Dimensions.Padding.content, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (hint != null && !enabled) {
                Text(
                    stringResource(invalidReasonRes(hint)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = onSave, enabled = enabled, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                Text(label)
            }
        }
    }
}

@Composable
private fun EditorForm(state: SharedExpenseEditorUiState, vm: SharedExpenseEditorViewModel, modifier: Modifier = Modifier) {
    val form = state.form
    val preview = state.preview
    val touched = form.touched
    var picker by rememberSaveable { mutableStateOf<EditorPicker?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimensions.Padding.content, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.Layout.headerToContent),
    ) {
        val descriptionError = preview.errorAt(FormField.DESCRIPTION, touched)
        OutlinedTextField(
            value = form.description,
            onValueChange = vm::setDescription,
            label = { Text(stringResource(R.string.shared_field_description)) },
            singleLine = true,
            isError = descriptionError != null,
            supportingText = descriptionError?.let { { Text(stringResource(invalidReasonRes(it))) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        val amountError = preview.errorAt(FormField.AMOUNT, touched)
        OutlinedTextField(
            value = form.amount,
            onValueChange = vm::setAmount,
            label = { Text(stringResource(R.string.shared_field_amount)) },
            prefix = { Text(CurrencyFormatter.getCurrencySymbol(form.currency)) },
            singleLine = true,
            isError = amountError != null,
            supportingText = amountError?.let { { Text(stringResource(invalidReasonRes(it))) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            textStyle = PennyWiseText.amountMedium,
            modifier = Modifier.fillMaxWidth(),
        )

        val currencyError = preview.errorAt(FormField.CURRENCY, touched) ?: preview.errorAt(FormField.CATEGORY, touched)
        GroupedList {
            GroupedRow(position = ListItemPosition.Top, onClick = { picker = EditorPicker.CURRENCY }) {
                RowLabels(title = stringResource(R.string.shared_currency_title))
                Text(form.currency, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            GroupedRow(position = ListItemPosition.Middle, onClick = { picker = EditorPicker.DATE }) {
                RowLabels(
                    title = stringResource(if (state.mode == EditorMode.EDIT_RECURRING) R.string.shared_field_next_date else R.string.shared_field_date),
                )
                Text(formDate(form.date), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                GroupedRow(position = ListItemPosition.Bottom, onClick = { picker = EditorPicker.CATEGORY }) {
                    SelectedCategoryIcon(state.categories.find { it.name == form.category })
                    RowLabels(title = stringResource(R.string.shared_field_category))
                    Text(
                        form.category?.let { categoryLabel(it) } ?: stringResource(R.string.shared_category_none),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = Dimensions.Component.tableCellWidth),
                    )
                }
                DropdownMenu(expanded = picker == EditorPicker.CATEGORY, onDismissRequest = { picker = null }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_category_none)) },
                        onClick = {
                            vm.setCategory(null)
                            picker = null
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                    HorizontalDivider()
                    CategoryMenuItems(
                        categories = state.categories,
                        isIncome = false,
                        onSelected = {
                            vm.setCategory(it.name)
                            picker = null
                        },
                    )
                }
            }
        }
        currencyError?.let { ErrorText(it) }

        PaidBySection(state, vm, onPick = { picker = EditorPicker.PAYER })
        SplitSection(state, vm)

        if (state.mode != EditorMode.EDIT_EXPENSE) {
            SectionHeaderV2(title = stringResource(R.string.shared_repeat_title))
            val options: List<RecurringInterval?> =
                if (state.mode == EditorMode.EDIT_RECURRING) RecurringInterval.entries else listOf(null) + RecurringInterval.entries
            ConnectedChoiceRow(
                options = options,
                selected = form.repeat,
                onSelect = vm::setRepeat,
                label = { repeatLabel(it) },
            )
            if (form.repeat != null && state.mode == EditorMode.NEW) {
                Text(
                    stringResource(R.string.shared_repeat_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SharedLinkSection(
            linked = state.linked,
            pending = state.pendingLink,
            canLink = state.canLink,
            hint = stringResource(R.string.shared_link_expense_hint),
            onLink = vm::openLinkPicker,
            onUnlink = vm::unlink,
            enabled = !state.busy,
            modifier = Modifier.padding(top = Spacing.md),
        )

        val notesError = preview.errorAt(FormField.NOTES, touched)
        OutlinedTextField(
            value = form.notes,
            onValueChange = vm::setNotes,
            label = { Text(stringResource(R.string.shared_field_notes)) },
            minLines = 2,
            isError = notesError != null,
            supportingText = notesError?.let { { Text(stringResource(invalidReasonRes(it))) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md, bottom = Spacing.Layout.scrollBottomPadding),
        )
    }

    state.picker?.let { p ->
        SharedLinkPickerDialog(p.currency, p.candidates, onPick = vm::pickLink, onDismiss = vm::closeLinkPicker)
    }

    when (picker) {
        EditorPicker.CURRENCY -> SharedCurrencyPickerDialog(
            selected = form.currency,
            onSelect = {
                vm.setCurrency(it)
                picker = null
            },
            onDismiss = { picker = null },
        )
        EditorPicker.DATE -> SharedDatePickerDialog(date = form.date, onPick = vm::setDate, onDismiss = { picker = null })
        EditorPicker.PAYER -> SharedMemberPickerDialog(
            title = stringResource(R.string.shared_paid_by_title),
            members = state.members,
            selectedId = form.payerId,
            onSelect = {
                vm.setPayer(it)
                picker = null
            },
            onDismiss = { picker = null },
            extra = stringResource(R.string.shared_paid_by_several_option) to {
                vm.setSeveralPayers()
                picker = null
            },
            extraSelected = form.paidBy == PaidBy.SEVERAL,
        )
        EditorPicker.CATEGORY, null -> Unit
    }
}

private enum class EditorPicker { CURRENCY, DATE, CATEGORY, PAYER }

@Composable
private fun ErrorText(error: SharedExpenseError) {
    Text(stringResource(invalidReasonRes(error)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun repeatLabel(interval: RecurringInterval?): String = stringResource(
    when (interval) {
        null -> R.string.shared_repeat_none
        RecurringInterval.WEEKLY -> R.string.shared_repeat_weekly
        RecurringInterval.MONTHLY -> R.string.shared_repeat_monthly
        RecurringInterval.YEARLY -> R.string.shared_repeat_yearly
    },
)

@Composable
private fun PaidBySection(state: SharedExpenseEditorUiState, vm: SharedExpenseEditorViewModel, onPick: () -> Unit) {
    val form = state.form
    SectionHeaderV2(title = stringResource(R.string.shared_paid_by_title))
    val several = form.paidBy == PaidBy.SEVERAL
    val rows = if (several) state.members.size + 1 else 1
    GroupedList {
        GroupedRow(position = ListItemPosition.from(0, rows), onClick = onPick) {
            RowLabels(title = stringResource(R.string.shared_paid_by_title))
            Text(
                if (several) {
                    stringResource(R.string.shared_paid_by_several_option)
                } else {
                    state.members.firstOrNull { it.id == form.payerId }?.label() ?: stringResource(R.string.shared_person_unknown)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
        if (several) {
            state.members.forEachIndexed { i, member ->
                // Keyed, so a focused field stays with its member if the list changes while typing.
                key(member.id) {
                    GroupedRow(position = ListItemPosition.from(i + 1, rows)) {
                        MemberAvatar(member.label(), size = Dimensions.Icon.large, dimmed = member.removed)
                        RowLabels(title = member.label())
                        MoneyField(
                            value = form.payerAmounts[member.id].orEmpty(),
                            onValueChange = { vm.setPayerAmount(member.id, it) },
                            currency = form.currency,
                        )
                    }
                }
            }
        }
    }
    if (several) {
        LeftText(preview = state.preview.payersLeft, currency = form.currency)
    }
    state.preview.errorAt(FormField.PAYERS, form.touched)?.let { ErrorText(it) }
}

@Composable
private fun SplitSection(state: SharedExpenseEditorUiState, vm: SharedExpenseEditorViewModel) {
    val form = state.form
    val preview = state.preview
    SectionHeaderV2(title = stringResource(R.string.shared_split_title))
    ConnectedChoiceRow(
        options = SplitMethod.entries,
        selected = form.method,
        onSelect = vm::setMethod,
        label = { splitMethodLabel(it) },
    )
    GroupedList(Modifier.padding(top = Spacing.sm)) {
        state.members.forEachIndexed { i, member -> key(member.id) {
            val position = ListItemPosition.from(i, state.members.size)
            val result = preview.shares[member.id]
            val resultText = result?.let { CurrencyFormatter.formatCurrency(it, form.currency) }
            when (form.method) {
                SplitMethod.EQUAL -> GroupedRow(position = position, onClick = { vm.toggleEqual(member.id) }) {
                    Checkbox(checked = member.id in form.equalIds, onCheckedChange = null)
                    RowLabels(title = member.label())
                    ShareAmount(resultText)
                }
                SplitMethod.EXACT -> GroupedRow(position = position) {
                    MemberAvatar(member.label(), size = Dimensions.Icon.large, dimmed = member.removed)
                    RowLabels(title = member.label())
                    MoneyField(
                        value = form.exactAmounts[member.id].orEmpty(),
                        onValueChange = { vm.setExactAmount(member.id, it) },
                        currency = form.currency,
                    )
                }
                SplitMethod.PERCENT -> GroupedRow(position = position) {
                    MemberAvatar(member.label(), size = Dimensions.Icon.large, dimmed = member.removed)
                    RowLabels(title = member.label(), subtitle = resultText)
                    OutlinedTextField(
                        value = form.percents[member.id].orEmpty(),
                        onValueChange = { vm.setPercent(member.id, it) },
                        suffix = { Text(stringResource(R.string.shared_percent_suffix)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        textStyle = PennyWiseText.amountRow,
                        modifier = Modifier.width(Dimensions.Component.tableCellWidth),
                    )
                }
                SplitMethod.SHARES -> GroupedRow(position = position) {
                    val count = form.shareCounts[member.id] ?: 0
                    RowLabels(title = member.label(), subtitle = resultText)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { vm.changeShareCount(member.id, -1) },
                            enabled = count > 0,
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.shared_shares_less, member.label()))
                        }
                        Text(count.toString(), style = PennyWiseText.amountRow, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = Dimensions.Icon.medium))
                        IconButton(
                            onClick = { vm.changeShareCount(member.id, 1) },
                            enabled = count < MAX_SHARE_COUNT,
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.shared_shares_more, member.label()))
                        }
                    }
                }
            }
        } }
    }
    when (form.method) {
        SplitMethod.EXACT -> LeftText(preview = preview.sharesLeft, currency = form.currency)
        SplitMethod.PERCENT -> preview.sharesLeft?.let { left ->
            val text = when (left.signum()) {
                0 -> stringResource(R.string.shared_left_done)
                1 -> stringResource(R.string.shared_left_percent, left.toPlainString())
                else -> stringResource(R.string.shared_over_percent, left.negate().toPlainString())
            }
            LeftLine(text, problem = left.signum() != 0)
        }
        else -> Unit
    }
    preview.errorAt(FormField.SHARES, form.touched)?.let { ErrorText(it) }
}

@Composable
private fun ShareAmount(text: String?) {
    if (text != null) Text(text, style = PennyWiseText.amountRow, color = MaterialTheme.colorScheme.onSurface)
}

/** "₹200.00 left to assign", "₹50.00 too much" or "Adds up", for money left over. Nothing until there is an amount. */
@Composable
private fun LeftText(preview: BigDecimal?, currency: String) {
    val left = preview ?: return
    val text = when (left.signum()) {
        0 -> stringResource(R.string.shared_left_done)
        1 -> stringResource(R.string.shared_left_money, CurrencyFormatter.formatCurrency(left, currency))
        else -> stringResource(R.string.shared_over_money, CurrencyFormatter.formatCurrency(left.negate(), currency))
    }
    LeftLine(text, problem = left.signum() != 0)
}

@Composable
private fun LeftLine(text: String, problem: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A compact amount field for a member's row. The text is kept as typed; nothing reformats it. */
@Composable
private fun MoneyField(value: String, onValueChange: (String) -> Unit, currency: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        prefix = { Text(CurrencyFormatter.getCurrencySymbol(currency)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        textStyle = PennyWiseText.amountRow,
        modifier = Modifier.width(Dimensions.Component.tableCellWidth),
    )
}
