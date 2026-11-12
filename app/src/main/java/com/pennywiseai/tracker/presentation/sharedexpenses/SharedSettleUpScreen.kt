package com.pennywiseai.tracker.presentation.sharedexpenses

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.domain.sharedexpenses.SharedExpenseError
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.components.cards.GroupedList
import com.pennywiseai.tracker.ui.components.cards.GroupedRow
import com.pennywiseai.tracker.ui.components.cards.ListItemPosition
import com.pennywiseai.tracker.ui.components.cards.RowLabels
import com.pennywiseai.tracker.ui.components.cards.SectionHeaderV2
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.PennyWiseText
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.utils.CurrencyFormatter

/**
 * Record a Settlement, optionally prefilled with the pair, [amount] (a decimal
 * string) and [currency], or edit the recorded one [settlementId] (with Delete).
 * When I pay someone in INR, "Pay with UPI" opens a UPI app; that alone records
 * nothing, so the user still taps Record payment after coming back. The
 * parameters reach the ViewModel through the navigation key.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SharedSettleUpScreen(
    groupId: String,
    fromMemberId: String?,
    toMemberId: String?,
    amount: String?,
    currency: String?,
    settlementId: String?,
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
    @Suppress("UNUSED_PARAMETER") fromTransactionId: Long? = null,
    viewModel: SharedSettleUpViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<SettleDialog?>(null) }

    val savedNotice = state.savedNotice?.asString()
    LaunchedEffect(state.saved, state.gone) {
        if (state.saved || state.gone) {
            // The payment is saved; say why its bank transaction was not linked.
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
    val noUpiApp = stringResource(R.string.shared_upi_no_app)
    var upiFailed by remember { mutableStateOf(false) }
    LaunchedEffect(upiFailed) {
        if (upiFailed) {
            upiFailed = false
            snackbar.showSnackbar(noUpiApp)
        }
    }
    fun payWithUpi() {
        val uri = viewModel.upiUri() ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
        } catch (_: ActivityNotFoundException) {
            upiFailed = true
        }
    }

    PennyWiseScaffold(
        title = stringResource(if (state.editing) R.string.shared_settlement_edit_title else R.string.shared_settle_up_title),
        navigationIcon = { SharedBackButton(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            if (state.editing && !state.loading) {
                IconButton(onClick = { dialog = SettleDialog.DELETE }, enabled = !state.busy, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.shared_delete))
                }
            }
        },
        bottomBar = {
            if (!state.loading) {
                SaveBar(
                    label = stringResource(if (state.editing) R.string.shared_save else R.string.shared_record_payment),
                    enabled = state.canSave,
                    hint = state.error?.takeIf { !state.form.touched },
                    onSave = viewModel::save,
                )
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@PennyWiseScaffold
        }
        val form = state.form
        val shownError = state.error?.takeIf { form.touched }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimensions.Padding.content, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Layout.headerToContent),
        ) {
            SectionHeaderV2(
                title = stringResource(R.string.shared_settle_who),
                topSpacing = Spacing.none,
                action = {
                    IconButton(onClick = viewModel::swap, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.shared_settle_swap))
                    }
                },
            )
            GroupedList {
                GroupedRow(position = ListItemPosition.Top, onClick = { dialog = SettleDialog.FROM }) {
                    RowLabels(title = stringResource(R.string.shared_settle_from))
                    MemberValue(state.members.firstOrNull { it.id == form.fromId })
                }
                GroupedRow(position = ListItemPosition.Bottom, onClick = { dialog = SettleDialog.TO }) {
                    RowLabels(title = stringResource(R.string.shared_settle_to))
                    MemberValue(state.members.firstOrNull { it.id == form.toId })
                }
            }
            shownError?.takeIf { it == SharedExpenseError.SETTLEMENT_SAME_MEMBER || it == SharedExpenseError.UNKNOWN_MEMBER }?.let { InlineError(it) }

            val amountError = shownError?.takeIf { it.formField == FormField.AMOUNT || it.formField == FormField.CURRENCY }
            OutlinedTextField(
                value = form.amount,
                onValueChange = viewModel::setAmount,
                label = { Text(stringResource(R.string.shared_field_amount)) },
                prefix = { Text(CurrencyFormatter.getCurrencySymbol(form.currency)) },
                singleLine = true,
                isError = amountError != null,
                supportingText = amountError?.let { { Text(stringResource(invalidReasonRes(it))) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                textStyle = PennyWiseText.amountMedium,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            )
            GroupedList {
                GroupedRow(position = ListItemPosition.Top, onClick = { dialog = SettleDialog.CURRENCY }) {
                    RowLabels(title = stringResource(R.string.shared_currency_title))
                    Text(form.currency, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
                GroupedRow(position = ListItemPosition.Bottom, onClick = { dialog = SettleDialog.DATE }) {
                    RowLabels(title = stringResource(R.string.shared_field_date))
                    Text(formDate(form.date), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (state.canPayUpi) {
                UpiSection(
                    upiId = state.upiId,
                    enabled = parseNumberInput(form.amount)?.signum() == 1,
                    onPay = { if (state.upiId == null) dialog = SettleDialog.UPI_ID else payWithUpi() },
                    onChangeId = { dialog = SettleDialog.UPI_ID },
                )
            }

            SharedLinkSection(
                linked = state.linked,
                pending = state.pendingLink,
                canLink = state.canLink,
                hint = stringResource(R.string.shared_link_settlement_hint),
                onLink = viewModel::openLinkPicker,
                onUnlink = viewModel::unlink,
                enabled = !state.busy,
            )

            val notesError = shownError?.takeIf { it.formField == FormField.NOTES }
            OutlinedTextField(
                value = form.notes,
                onValueChange = viewModel::setNotes,
                label = { Text(stringResource(R.string.shared_field_notes)) },
                minLines = 2,
                isError = notesError != null,
                supportingText = notesError?.let { { Text(stringResource(invalidReasonRes(it))) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = Spacing.Layout.scrollBottomPadding),
            )
        }
    }

    state.picker?.let { p ->
        SharedLinkPickerDialog(p.currency, p.candidates, onPick = viewModel::pickLink, onDismiss = viewModel::closeLinkPicker)
    }

    when (dialog) {
        SettleDialog.FROM -> SharedMemberPickerDialog(
            title = stringResource(R.string.shared_settle_from),
            members = state.members,
            selectedId = state.form.fromId,
            onSelect = {
                viewModel.setFrom(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        SettleDialog.TO -> SharedMemberPickerDialog(
            title = stringResource(R.string.shared_settle_to),
            members = state.members,
            selectedId = state.form.toId,
            onSelect = {
                viewModel.setTo(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        SettleDialog.CURRENCY -> SharedCurrencyPickerDialog(
            selected = state.form.currency,
            onSelect = {
                viewModel.setCurrency(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        SettleDialog.DATE -> SharedDatePickerDialog(date = state.form.date, onPick = viewModel::setDate, onDismiss = { dialog = null })
        SettleDialog.UPI_ID -> {
            var invalid by rememberSaveable { mutableStateOf(false) }
            SharedTextFieldDialog(
                title = stringResource(R.string.shared_upi_id_title, state.toName.orEmpty()),
                label = stringResource(R.string.shared_upi_id_label),
                message = stringResource(R.string.shared_upi_id_body),
                confirmLabel = stringResource(R.string.shared_save),
                initial = state.upiId.orEmpty(),
                error = if (invalid) stringResource(R.string.shared_upi_id_invalid) else null,
                keyboardType = KeyboardType.Email,
                capitalization = KeyboardCapitalization.None,
                onConfirm = { id ->
                    invalid = !viewModel.saveUpiId(id)
                    if (!invalid) {
                        dialog = null
                        // The ID is remembered at once, so paying can go straight on.
                        payWithUpi()
                    }
                },
                onDismiss = { dialog = null },
            )
        }
        SettleDialog.DELETE -> SharedConfirmDialog(
            title = stringResource(R.string.shared_delete_settlement_title),
            message = stringResource(R.string.shared_delete_settlement_body),
            confirmLabel = stringResource(R.string.shared_delete),
            onConfirm = {
                dialog = null
                viewModel.delete()
            },
            onDismiss = { dialog = null },
            destructive = true,
        )
        null -> Unit
    }
}

private enum class SettleDialog { FROM, TO, CURRENCY, DATE, UPI_ID, DELETE }

@Composable
private fun MemberValue(member: EditorMember?) {
    Text(
        member?.label() ?: stringResource(R.string.shared_settle_choose),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
    )
}

@Composable
private fun InlineError(error: SharedExpenseError) {
    Text(stringResource(invalidReasonRes(error)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun UpiSection(upiId: String?, enabled: Boolean, onPay: () -> Unit, onChangeId: () -> Unit) {
    Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        OutlinedButton(onClick = onPay, enabled = enabled, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(Dimensions.Icon.small))
            Text(stringResource(R.string.shared_pay_with_upi), modifier = Modifier.padding(start = Spacing.sm))
        }
        Text(
            stringResource(R.string.shared_pay_with_upi_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (upiId != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.shared_upi_id_saved, upiId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onChangeId, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_upi_id_change)) }
            }
        }
    }
}
