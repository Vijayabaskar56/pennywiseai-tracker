package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.domain.sharedexpenses.SplitMethod
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.utils.AppDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Pieces the Shared forms (expense, settle up) share.

/** A member's name in a form: "You" for this phone's seat, and a removed seat marked as such. */
@Composable
fun EditorMember.label(): String = when {
    isMe -> stringResource(R.string.shared_person_you)
    removed -> stringResource(R.string.shared_member_removed_label, name)
    else -> name
}

/** "Equal", "Exact", "Percent", "Shares". */
@Composable
fun splitMethodLabel(method: SplitMethod): String = stringResource(
    when (method) {
        SplitMethod.EQUAL -> R.string.shared_split_equal
        SplitMethod.EXACT -> R.string.shared_split_exact
        SplitMethod.PERCENT -> R.string.shared_split_percent
        SplitMethod.SHARES -> R.string.shared_split_shares
    },
)

/** A date as the forms show it ("12 Nov 2026"). */
fun formDate(date: LocalDate): String = AppDates.dateFormatter().format(date)

/**
 * Picks one of [members]. [extra] adds one more choice at the end (such as
 * "Several people"), selected when [selectedId] is null and [extraSelected].
 */
@Composable
fun SharedMemberPickerDialog(
    title: String,
    members: List<EditorMember>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    extra: Pair<String, () -> Unit>? = null,
    extraSelected: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = Dimensions.Component.dialogListMaxHeight)) {
                items(members, key = { it.id }) { member ->
                    PickerRow(member.label(), selected = !extraSelected && member.id == selectedId) { onSelect(member.id) }
                }
                if (extra != null) {
                    item(key = "extra") { PickerRow(extra.first, selected = extraSelected, onClick = extra.second) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_cancel)) }
        },
    )
}

@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimensions.Component.minTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The app's date picker, opening on [date]. Dates are whole days (UTC midnight in the picker). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedDatePickerDialog(date: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    onDismiss()
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.shared_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shared_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}
