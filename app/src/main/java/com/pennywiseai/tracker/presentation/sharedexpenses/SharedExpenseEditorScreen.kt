package com.pennywiseai.tracker.presentation.sharedexpenses

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import com.pennywiseai.tracker.R

/** Add ([expenseId] null) or edit a Shared expense; [fromTransactionId] fills it from a bank transaction. Placeholder until the form is written. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SharedExpenseEditorScreen(
    groupId: String,
    expenseId: String?,
    fromTransactionId: Long?,
    onNavigateBack: () -> Unit,
    onNavigate: (NavKey) -> Unit,
) {
    SharedPlaceholderScreen(title = stringResource(R.string.shared_expense_editor_title), onNavigateBack = onNavigateBack)
}
